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

    const val JOB_ID = 0x6764_0001
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
        }
        // Servis on plandayken uygulama "on planda" sayilir; duz startService serbest.
        runCatching { app.startService(Intent(app, KeeperService::class.java)) }
            .onFailure { Log.w(TAG, "bekci servisi baslatilamadi", it) }
    }

    /** Kullanici durdurdu / izin geri alindi / kalici hata: kurtarma katmanlarini kaldirir. */
    fun disarm(context: Context) {
        val app = context.applicationContext
        runCatching { app.stopService(Intent(app, KeeperService::class.java)) }
        runCatching { app.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID) }
        prefs(app).edit(commit = true) { putBoolean(KEY_JOB_ARMED, false) }
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

    /**
     * API 30 alti: periyodik isi kurmustuk, ayni acilistayiz, arada guncelleme yok ama is
     * artik yok -> durmaya zorla iptal etmistir (AOSP'de ACTION_PACKAGE_RESTARTED isleri siler).
     */
    fun jobCancelledByForceStop(jobArmed: Boolean, jobPending: Boolean, sameBoot: Boolean, updatedSinceArm: Boolean): Boolean =
        jobArmed && !jobPending && sameBoot && !updatedSinceArm
}
