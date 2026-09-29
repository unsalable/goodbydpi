package io.github.unsalable.goodbyedpi.service

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
import kotlin.math.abs

/**
 * Surec olunce (LMK, kill -9, byedpi/hev'de yerel cokme) baglantiyi arayuz acilmadan geri
 * getiren parcalar ve "kullanici uygulamayi bilerek durdurdu" ayrimi.
 *
 * Neden: API 36'da VPN servisinin sureci olunce cekirdek once tun'u kapatiyor, Vpn sinifi
 * servise bagini koparirken DeadObjectException aliyor ve ActivityManager servis kaydini
 * dusuruyor; DpiVpnService icin START_STICKY yeniden baslatmasi HIC planlanmiyor (e2e RT-1,
 * her baslatma ve oldurme yolunda goruldu). Uc katman:
 *
 * 1. [KeeperService]: ayni surecte, Vpn'in BAGLAMADIGI duz bir START_STICKY servis. Surec
 *    olunce sistem onu ~1 sn sonra yeniden baslatir; onStartCommand(null) kurtarmayi cagirir.
 *    VPN izni (OP_ACTIVATE_VPN) olan uygulamanin arka plandan on plan servis baslatmasi serbest.
 * 2. App.onCreate: surec baska bir nedenle (karo, alici, is) dogdugunda da bakilir.
 * 3. [RecoveryJobService]: 15 dk'lik kalici periyodik is; ilk ikisi bir sekilde kacarsa emniyet.
 * 4. Kontrol isi ([CHECK_JOB_ID]): her arka plan kurtarmasindan sonra 30 sn-8 dk icinde tek
 *    seferlik, kalici olmayan is. Surec kurtarmadan hemen sonra yine cokerse (ayni trafik ayni
 *    yerel cokmeyi tetikliyor) ActivityManager bekcinin yeniden baslatmasini 30 dk erteliyor
 *    ve periyodik is 15-34 dk uzakta; JobScheduler bu cokme cezasina tabi degil (e2e E2E-F1:
 *    zorla calistirilan is VPN'i 1,3 sn'de geri getirdi). Ust uste [RecoveryPolicy.MAX_STREAK]
 *    kurtarmadan sonra vazgecilir: arka plan yolu kapatilir ve "Baglanti koptu" bildirimi
 *    cikar (dokununca baglanir); deterministik bir cokme dongusu pili tuketmesin.
 *
 * Arka plan kurtarmasi yalnizca [arm] ile kurulup [disarm] ile kaldirilmamissa calisir
 * ([backgroundArmed]): kullanici durdurmasi, izin geri alinmasi ve kalici hata (fail) onu
 * kapatir; arayuz ve karo (on plan) yollari yine dener.
 *
 * Bilerek durdurma (Ayarlar > Durmaya zorla, Android 13+ Etkin uygulamalar > Durdur) geri
 * alinmamali. Durmaya zorla servisleri yeniden baslatmaz ve isleri iptal eder; "Durdur"
 * (stop-app) isleri iptal etmez. Ikisi de API 30+'da ApplicationExitInfo'da
 * REASON_USER_REQUESTED olarak gorunur: kurtarmadan once buna bakilir ve istek kapatilir.
 * API 30 alti: durmaya zorla periyodik isimizi iptal eder; "is kurmustuk ama yok" isareti.
 */
internal object Recovery {
    private const val TAG = "GdpiRecovery"
    private const val PREFS = "gdpi_recovery"

    private const val KEY_ARMED_AT = "armedAtWall"
    private const val KEY_ARMED_BOOT = "armedBoot"
    private const val KEY_ARMED_ELAPSED = "armedElapsed"
    private const val KEY_JOB_ARMED = "jobArmed"
    private const val KEY_HANDLED_EXIT = "handledExit"

    /** arm() true, disarm() false yapar; arka plan kurtarmasi buna bakar (review REC-1). */
    private const val KEY_BG_ARMED = "bgArmed"

    // Ust uste arka plan kurtarmalari (RecoveryPolicy.Streak).
    private const val KEY_STREAK_COUNT = "bgStreakCount"
    private const val KEY_STREAK_LAST = "bgStreakLast"
    private const val KEY_STREAK_BOOT = "bgStreakBoot"

    const val JOB_ID = 0x6764_0001

    /** Kurtarmadan sonraki tek seferlik kontrol isi; kalici degil (yeniden baslatmada gerek yok). */
    const val CHECK_JOB_ID = 0x6764_0002
    private const val JOB_PERIOD_MS = 15 * 60 * 1000L

    // Yazmalar commit (senkron): durdurulmus paketin bos sureci is bitince hemen
    // oldurulebiliyor (emulatorde goruldu); apply ile isaret kaybolur ve ayni karar tekrarlanir.
    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Motor calisiyor ve son istek "acik": kurtarma katmanlarini kurar. Her basarili motor
     * baslatmasinda cagrilir (ucuz; is zaten kuruluysa dokunulmaz). Zaman damgasi, bundan
     * onceki bir "durmaya zorla"nin artik gecersiz oldugunu soyler.
     */
    fun arm(context: Context) {
        val app = context.applicationContext
        val jobOk = scheduleJob(app)
        prefs(app).edit(commit = true) {
            putLong(KEY_ARMED_AT, System.currentTimeMillis())
            putInt(KEY_ARMED_BOOT, bootCount(app))
            putLong(KEY_ARMED_ELAPSED, SystemClock.elapsedRealtime())
            putBoolean(KEY_JOB_ARMED, jobOk)
            putBoolean(KEY_BG_ARMED, true)
        }
        // Servis on plandayken uygulama "on planda" sayilir; duz startService serbest.
        runCatching { app.startService(Intent(app, KeeperService::class.java)) }
            .onFailure { Log.w(TAG, "bekci servisi baslatilamadi", it) }
    }

    /**
     * Kullanici durdurdu / izin geri alindi / kalici hata: kurtarma katmanlarini kaldirir ve
     * arka plan yolunu kapatir. KEY_ARMED_AT kalir: userStoppedAfterArm "durdurma motor
     * kurulduktan sonra mi" sorusunu ona gore cevapliyor.
     */
    fun disarm(context: Context) {
        val app = context.applicationContext
        runCatching { app.stopService(Intent(app, KeeperService::class.java)) }
        val js = runCatching { app.getSystemService(JobScheduler::class.java) }.getOrNull()
        runCatching { js?.cancel(JOB_ID) }
        runCatching { js?.cancel(CHECK_JOB_ID) }
        prefs(app).edit(commit = true) {
            putBoolean(KEY_JOB_ARMED, false)
            putBoolean(KEY_BG_ARMED, false)
        }
    }

    /**
     * Arka plan kurtarmasi (bekci, isler, App.onCreate) yapilabilir mi: kurtarma [arm] ile
     * kurulmus, sonra [disarm] edilmemis ve ayni acilistayiz. Kalici hatadan (fail -> disarm)
     * sonra wantRunning bilerek true kalir (arayuz acilinca tekrar denensin); bu bayrak
     * olmadan 6 saatlik guncelleme isinin dogurdugu surec VPN'i kendiliginden geri acardi.
     */
    fun backgroundArmed(context: Context): Boolean =
        RecoveryPolicy.canRecoverInBackground(
            bgArmed = prefs(context).getBoolean(KEY_BG_ARMED, false),
            sameBoot = sameBootAsArmed(context),
        )

    private fun readStreak(p: SharedPreferences) = RecoveryPolicy.Streak(
        count = p.getInt(KEY_STREAK_COUNT, 0),
        lastElapsed = p.getLong(KEY_STREAK_LAST, 0L),
        boot = p.getInt(KEY_STREAK_BOOT, -1),
    )

    /**
     * Arka plan kurtarmasi motoru baslatmak uzere: ust uste sayaci ilerletir ve kontrol isini
     * kurar. Sinir asildiysa vazgecer ([giveUp]) ve false doner; cagiran baslatmamali.
     */
    fun beginBackgroundRecovery(context: Context): Boolean {
        val app = context.applicationContext
        val p = prefs(app)
        val prev = readStreak(p)
        val next = RecoveryPolicy.nextStreak(prev, SystemClock.elapsedRealtime(), bootCount(app))
        if (RecoveryPolicy.shouldGiveUp(next)) {
            giveUp(app, prev.count)
            return false
        }
        p.edit(commit = true) {
            putInt(KEY_STREAK_COUNT, next.count)
            putLong(KEY_STREAK_LAST, next.lastElapsed)
            putInt(KEY_STREAK_BOOT, next.boot)
        }
        scheduleCheck(app, RecoveryPolicy.checkDelayMs(next.count))
        Log.i(TAG, "arka plan kurtarmasi ${next.count}/${RecoveryPolicy.MAX_STREAK}")
        return true
    }

    /** Kullanici kendisi baglandi (arayuz, karo, acilis): onceki arka plan kurtarmalari sayilmaz. */
    fun resetBackgroundStreak(context: Context) {
        val p = prefs(context)
        if (!p.contains(KEY_STREAK_COUNT)) return
        p.edit(commit = true) {
            remove(KEY_STREAK_COUNT)
            remove(KEY_STREAK_LAST)
            remove(KEY_STREAK_BOOT)
        }
    }

    /**
     * Kontrol isi ve periyodik is. Motor yoksa kurtarir (beginBackgroundRecovery bir sonraki
     * kontrolu kurar). Motor ayaktaysa ve son kurtarma yeniyse izlemeyi surdurur: AMS cezasi
     * kurtarmadan dakikalar sonraki bir cokmede de (goruldu: 78 sn) bekciyi 30 dk erteliyor.
     */
    fun runCheck(context: Context, source: String) {
        val app = context.applicationContext
        if (ServiceController.recoverInBackground(app, source)) return
        val state = EngineStateHolder.state.value
        if (state !is EngineState.Running && state != EngineState.Starting) return
        if (!backgroundArmed(app)) return
        val delay = RecoveryPolicy.watchDelayMs(readStreak(prefs(app)), SystemClock.elapsedRealtime(), bootCount(app))
            ?: return
        scheduleCheck(app, delay)
    }

    /**
     * Ust uste cokmede vazgec: arka plan yolu kapanir (disarm), wantRunning kalir (arayuz ya
     * da karo acilinca baglanir) ve kullanici bildirimle haberdar edilir; yoksa VPN sessizce
     * kapali kalir, DPI engelleri geri gelirdi.
     */
    private fun giveUp(app: Context, count: Int) {
        Log.w(TAG, "arka plan kurtarmasi $count kez ust uste tutmadi; vazgeciliyor")
        disarm(app)
        resetBackgroundStreak(app)
        // Olen surecin on plan bildirimi ("Bagli", Durdur) ActivityManager'da asili kaliyor
        // (goruldu; uygulama on plan bildirimini cancel ile silemiyor): servisi bos bir istekle
        // bu surecte ayaga kaldirip birakmak kaydi ve bildirimi temizler.
        ServiceController.releaseStaleService(app)
        Notifications.showDisconnected(app)
    }

    private fun scheduleCheck(context: Context, delayMs: Long) {
        val js = context.getSystemService(JobScheduler::class.java) ?: return
        try {
            val job = JobInfo.Builder(CHECK_JOB_ID, ComponentName(context, RecoveryJobService::class.java))
                .setMinimumLatency(delayMs)
                .setOverrideDeadline(delayMs + RecoveryPolicy.CHECK_DEADLINE_SLACK_MS)
                // Cokme dongusu yeniden baslatmadan sonra anlamsiz; acilista karar BootReceiver'in.
                .setPersisted(false)
                .build()
            if (js.schedule(job) != JobScheduler.RESULT_SUCCESS) Log.w(TAG, "kontrol isi kurulamadi")
        } catch (e: Exception) {
            Log.w(TAG, "kontrol isi kurulamadi", e)
        }
    }

    private fun scheduleJob(context: Context): Boolean {
        val js = context.getSystemService(JobScheduler::class.java) ?: return false
        return try {
            if (js.getPendingJob(JOB_ID) != null) return true
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, RecoveryJobService::class.java))
                .setPeriodic(JOB_PERIOD_MS)
                // Yeniden baslatmadan sonra da kalsin (RECEIVE_BOOT_COMPLETED var); acilista
                // yalnizca farkli acilis oldugunu gorup bir sey yapmaz, BootReceiver karar verir.
                .setPersisted(true)
                .build()
            js.schedule(job) == JobScheduler.RESULT_SUCCESS
        } catch (e: Exception) {
            Log.w(TAG, "periyodik is kurulamadi", e)
            false
        }
    }

    /**
     * Motor bu acilista mi kuruldu? Arka plan kurtarmasi (bekci, is, App.onCreate) yalnizca
     * ayni acilista calisir: yeniden baslatmadan sonra karar BootReceiver'in ("Acilista
     * baslat" ayarina bakar).
     */
    fun sameBootAsArmed(context: Context): Boolean {
        val p = prefs(context)
        if (!p.contains(KEY_ARMED_AT)) return false
        val armedBoot = p.getInt(KEY_ARMED_BOOT, -1)
        val nowBoot = bootCount(context)
        if (armedBoot >= 0 && nowBoot >= 0) return armedBoot == nowBoot
        // BOOT_COUNT okunamadiysa: elapsedRealtime yalnizca ayni acilista geriye gitmez.
        return p.getLong(KEY_ARMED_ELAPSED, Long.MAX_VALUE) <= SystemClock.elapsedRealtime()
    }

    private fun bootCount(context: Context): Int =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }
            .getOrDefault(-1)

    /**
     * Motor en son kurulduktan sonra kullanici uygulamayi sistemden mi durdurdu? true ise
     * cagiran istegi (wantRunning) kapatmali ve kurtarmamali. Ayni cikis iki kez sayilmaz.
     */
    fun userStoppedApp(context: Context): Boolean {
        val app = context.applicationContext
        val p = prefs(app)
        val armedAt = p.getLong(KEY_ARMED_AT, 0L)
        val lastUpdate = runCatching {
            @Suppress("DEPRECATION")
            app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val exit = lastExit(app) ?: return false
            val stopped = RecoveryPolicy.userStoppedAfterArm(
                exit = exit,
                armedAtMs = armedAt,
                handledExitMs = p.getLong(KEY_HANDLED_EXIT, 0L),
                lastUpdateMs = lastUpdate,
            )
            if (stopped) {
                Log.i(TAG, "son surec cikisi kullanici istegi (${exit.description}); baglanti geri getirilmeyecek")
                p.edit(commit = true) { putLong(KEY_HANDLED_EXIT, exit.timestampMs) }
            }
            return stopped
        }

        val jobPending = runCatching {
            app.getSystemService(JobScheduler::class.java)?.getPendingJob(JOB_ID) != null
        }.getOrDefault(true)
        val stopped = RecoveryPolicy.jobCancelledByForceStop(
            jobArmed = p.getBoolean(KEY_JOB_ARMED, false),
            jobPending = jobPending,
            sameBoot = sameBootAsArmed(app),
            updatedSinceArm = lastUpdate >= armedAt,
        )
        if (stopped) {
            Log.i(TAG, "periyodik is iptal edilmis (durmaya zorla); baglanti geri getirilmeyecek")
            p.edit(commit = true) { putBoolean(KEY_JOB_ARMED, false) }
        }
        return stopped
    }

    private fun lastExit(context: Context): RecoveryPolicy.Exit? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        val info: ApplicationExitInfo = runCatching {
            am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
        }.getOrNull() ?: return null
        return RecoveryPolicy.Exit(info.reason, info.timestamp, info.description)
    }
}

/** Kurtarma kararlarinin saf (JVM'de sinanan) kismi. */
internal object RecoveryPolicy {
    /** ApplicationExitInfo.REASON_USER_REQUESTED (durmaya zorla ve Etkin uygulamalar > Durdur). */
    const val REASON_USER_REQUESTED = 10

    /** Bu kadar yakin bir olum uygulama guncellemesinin oldurmesi sayilir (API 30-31). */
    const val UPDATE_WINDOW_MS = 60_000L

    data class Exit(val reason: Int, val timestampMs: Long, val description: String?)

    /**
     * API 30+: en son surec cikisi kullanicinin durdurmasi mi ve motor en son kurulduktan
     * SONRA mi oldu? Onceki bir durdurma, kullanici sonradan yeniden baglandiysa sayilmaz.
     * API 30-31'de guncelleme oldurmesi de USER_REQUESTED yazilabiliyor; paketin guncellenme
     * anina cok yakin olumler bu yuzden sayilmaz (MY_PACKAGE_REPLACED yolu baglantiyi getirir).
     */
    fun userStoppedAfterArm(exit: Exit?, armedAtMs: Long, handledExitMs: Long, lastUpdateMs: Long): Boolean {
        if (exit == null || exit.reason != REASON_USER_REQUESTED) return false
        if (exit.timestampMs <= armedAtMs) return false
        if (exit.timestampMs == handledExitMs) return false
        if (lastUpdateMs > 0 && abs(exit.timestampMs - lastUpdateMs) < UPDATE_WINDOW_MS) return false
        return true
    }

    /** Arka plan kurtarmasi: kurulmus (disarm edilmemis) ve ayni acilis. */
    fun canRecoverInBackground(bgArmed: Boolean, sameBoot: Boolean): Boolean = bgArmed && sameBoot

    /** Ust uste arka plan kurtarmalari: kac tane, sonuncusu ne zaman (elapsedRealtime), hangi acilista. */
    data class Streak(val count: Int, val lastElapsed: Long, val boot: Int)

    /** Bu kadar arka plan kurtarmasi ust uste tutmazsa (bir sonraki denemede) vazgecilir. */
    const val MAX_STREAK = 5

    /** Iki kurtarma arasi bundan uzunsa onceki tutmus sayilir, sayac bastan baslar. */
    const val STREAK_GAP_MS = 15 * 60_000L

    /** Kurtarmadan sonra motor ayakta olsa da bu sure boyunca kontrol isi surer. */
    const val WATCH_MS = 10 * 60_000L

    /** Kontrol isinin en erken ve en gec calismasi arasindaki pay (JobInfo overrideDeadline). */
    const val CHECK_DEADLINE_SLACK_MS = 90_000L

    private const val CHECK_BASE_MS = 30_000L
    private const val CHECK_MAX_MS = 8 * 60_000L
    private const val WATCH_MIN_MS = 60_000L

    /**
     * Yeni bir arka plan kurtarmasi: oncekinden [STREAK_GAP_MS] icinde ve ayni acilistaysa
     * sayac artar, degilse 1'den baslar. Acilis sayisi okunamadiysa (-1) yalnizca zamana bakilir.
     */
    fun nextStreak(prev: Streak, nowElapsed: Long, nowBoot: Int): Streak {
        val sameBoot = prev.boot < 0 || nowBoot < 0 || prev.boot == nowBoot
        val gap = nowElapsed - prev.lastElapsed
        val continues = prev.count > 0 && sameBoot && gap in 0 until STREAK_GAP_MS
        return Streak(if (continues) prev.count + 1 else 1, nowElapsed, nowBoot)
    }

    fun shouldGiveUp(next: Streak): Boolean = next.count > MAX_STREAK

    /** n'inci kurtarmadan sonraki kontrol: 30 sn, 1, 2, 4, 8 dk (ustel, 8 dk'da sabit). */
    fun checkDelayMs(count: Int): Long {
        val shift = (count - 1).coerceIn(0, 10)
        return (CHECK_BASE_MS shl shift).coerceAtMost(CHECK_MAX_MS)
    }

    /**
     * Motor ayakta bulundu: son kurtarma [WATCH_MS]'den yeniyse bir sonraki kontrolun
     * gecikmesi (en az 1 dk), degilse null (izleme biter).
     */
    fun watchDelayMs(streak: Streak, nowElapsed: Long, nowBoot: Int): Long? {
        if (streak.count <= 0) return null
        if (streak.boot >= 0 && nowBoot >= 0 && streak.boot != nowBoot) return null
        val age = nowElapsed - streak.lastElapsed
        if (age !in 0 until WATCH_MS) return null
        return maxOf(checkDelayMs(streak.count), WATCH_MIN_MS)
    }

    /**
     * API 30 alti: periyodik isi kurmustuk, ayni acilistayiz, arada guncelleme yok ama is
     * artik yok -> durmaya zorla iptal etmistir (AOSP'de ACTION_PACKAGE_RESTARTED isleri siler).
     */
    fun jobCancelledByForceStop(jobArmed: Boolean, jobPending: Boolean, sameBoot: Boolean, updatedSinceArm: Boolean): Boolean =
        jobArmed && !jobPending && sameBoot && !updatedSinceArm
}
