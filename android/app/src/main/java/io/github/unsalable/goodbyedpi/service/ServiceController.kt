package io.github.unsalable.goodbyedpi.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.unsalable.goodbyedpi.data.SettingsRepository
import kotlinx.coroutines.runBlocking

// SOZLESME (wave 2): imzalar sabit. Arayuz, karo ve alici servise yalnizca buradan gider.

object ServiceController {
    private const val TAG = "GdpiController"

    /**
     * Hizli ayar karosu (VPN izni eksikken) "baglanmak istiyorum" der: arayuz izni isteyip
     * start'i cagirmali. Yalnizca [CONNECT_ALIAS] uzerinden gelen istekte gecerli (sozlesme C5).
     */
    const val EXTRA_CONNECT = "io.github.unsalable.goodbyedpi.extra.CONNECT"

    /**
     * Karonun baglanma istegini tasiyan, DISA KAPALI activity-alias (hedefi MainActivity).
     * MainActivity disa acik (baslatici); herhangi bir uygulama ona EXTRA_CONNECT koyabilirdi.
     * Diger uygulamalar disa kapali bilesene Intent gonderemez, yalnizca bizim uid'imiz.
     * Sinif adi namespace'e gore; paket (applicationId) sonekli olabilir, ComponentName
     * paketi baglamdan alir.
     */
    const val CONNECT_ALIAS = "io.github.unsalable.goodbyedpi.ConnectRequest"

    /** Ayni surecte arka arkaya gelen kurtarma istekleri (App + bekci + karo) tek baslatma olsun. */
    private const val RECOVERY_DEDUPE_MS = 5_000L

    private val recoveryDedupe = RecoveryDedupe(RECOVERY_DEDUPE_MS)

    /** Son [releaseStaleService] istegi (elapsedRealtime); vazgecme + genel yol ayni anda cagirabilir. */
    @Volatile
    private var lastReleaseAt = 0L

    /**
     * Bu surec bir enstrumantasyon testi mi? Testler byedpi'yi surec icinde kendileri
     * calistiriyor; cihazdaki ayarlarda wantRunning=true kalmissa arka plan kurtarmasi ikinci
     * bir byedpi baslatip testleri bozardi. Test APK'si hedef surecin sinif yukleyicisine
     * eklendigi icin androidx.test yalnizca test calisirken bulunur (uygulamanin kendi
     * bagimliliklarinda yok).
     */
    internal val isInstrumentationProcess: Boolean by lazy {
        runCatching { Class.forName("androidx.test.platform.app.InstrumentationRegistry"); true }
            .getOrDefault(false)
    }

    /**
     * Baglantiyi baslatir. VPN izni henuz verilmemisse izin ekranini acan Intent'i
     * dondurur (cagiran onu baslatir, RESULT_OK gelince start'i tekrar cagirir);
     * izin varsa servisi baslatip null dondurur. wantRunning'i servis kendisi true yapar.
     */
    fun start(context: Context): Intent? {
        // Kullanici (ya da acilis) baglaniyor: onceki arka plan kurtarmalarinin sayaci sifirlanir.
        runCatching { Recovery.resetBackgroundStreak(context.applicationContext) }
        return start(context, reportFailure = true)
    }

    /**
     * Durum bildirimini yeniden gosterir (servis on plandayken). Bildirim izni servis
     * basladiktan SONRA verildiginde (ilk baglanma: once VPN izni, sonra bildirim izni) on plan
     * bildirimi izinsiz gonderildigi icin hic gorunmemisti ve durum degisene kadar gelmiyordu;
     * servis REFRESH_NOTIFICATION'da startForeground'u yeniden cagirir (instrumented F1).
     */
    fun refreshNotification(context: Context) {
        when (EngineStateHolder.state.value) {
            is EngineState.Running, EngineState.Starting ->
                startServiceCompat(context, DpiVpnService.ACTION_REFRESH_NOTIFICATION, foreground = false, reportFailure = false)
            else -> Unit
        }
    }

    /**
     * Motor bu surecte yokken servisi REFRESH_NOTIFICATION ile baslatir; servis motorsuz
     * oldugunu gorup on plandan cikar ve durur. Cokmus surecin servis kaydi ActivityManager'da
     * "on planda" asili kalinca eski "Bagli" bildirimi (Durdur dugmesiyle) VPN yokken
     * gorunmeye devam ediyordu (Recovery.giveUp). On plan olarak: arka plandan duz startService
     * reddedilir; servis her istekte once startForeground cagiriyor.
     */
    internal fun releaseStaleService(context: Context): Boolean {
        if (EngineStateHolder.state.value != EngineState.Stopped) return false
        val now = SystemClock.elapsedRealtime()
        // Ayni kayit icin ikinci bir bos servis baslatmasi gereksiz (giveUp + recoverInBackground,
        // App.onCreate + bekci ayni surec dogumunda).
        if (lastReleaseAt != 0L && now - lastReleaseAt in 0 until RECOVERY_DEDUPE_MS) return false
        lastReleaseAt = now
        startServiceCompat(context, DpiVpnService.ACTION_REFRESH_NOTIFICATION, foreground = true, reportFailure = false)
        return true
    }

    /**
     * Motor bu surecte yokken durum bildirimi hala gorunuyorsa olu bir surecten kalmistir:
     * birakir. Arka plan kurtarmasi reddedildiginde (kurulu degil, kullanici durdurmus, VPN izni
     * gitmis) cagrilir; aksi halde "Bagli" bildirimi VPN yokken asili kalirdi (E2E-V7-1).
     * Bildirim yoksa hicbir sey yapmaz: her surec dogumunda bos yere servis baslatilmasin.
     */
    private fun releaseStaleServiceIfShown(context: Context) {
        if (EngineStateHolder.state.value != EngineState.Stopped) return
        // Android 12+'da arka plandan on plan servisi baslatma izni bize yalnizca VPN izni
        // (OP_ACTIVATE_VPN) sayesinde var; izin gittiyse baslatma reddedilir, denemeyelim.
        // Kalan bildirim, sistemin ertelenmis yapiskan yeniden baslatmasinda ya da uygulama
        // acilinca kalkar (SPEC 8, bilinen sinir). VpnService.prepare DEGIL: o, izin onceden
        // verilmisse etkin baska bir VPN'i dusurur (bkz. VpnGate).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !VpnGate.hasConsent(context)) return
        val shown = runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.activeNotifications?.any { it.id == Notifications.STATUS_ID } == true
        }.getOrDefault(false)
        if (shown && releaseStaleService(context)) {
            Log.i(TAG, "motor yokken durum bildirimi gorunuyor (olu surecten kalma); birakildi")
        }
    }

    private fun start(context: Context, reportFailure: Boolean): Intent? {
        // Bilerek prepare: baglanmak istiyoruz, etkin baska bir VPN varsa devralinir (onun
        // uygulamasi onRevoke alir). Kullanicinin "baglan"i disinda buraya yalnizca
        // VpnGate.unattendedStart'tan (baska VPN yok) gecen kurtarma gelir.
        val consent = try {
            VpnService.prepare(context)
        } catch (e: Exception) {
            Log.w(TAG, "VpnService.prepare", e)
            null
        }
        if (consent != null) return consent
        startServiceCompat(context, DpiVpnService.ACTION_START, foreground = true, reportFailure = reportFailure)
        return null
    }

    fun stop(context: Context) {
        when (EngineStateHolder.state.value) {
            // Servis zaten yok (Failed'da kendini durdurmustu): yalnizca son istegi kapat ve
            // hata durumunu temizle; bosuna servis baslatip hemen durdurmayalim.
            EngineState.Stopped, is EngineState.Failed -> {
                EngineStateHolder.set(EngineState.Stopped)
                Notifications.cancelFailure(context)
                val app = context.applicationContext
                // Askiya almadan: yolda olan bir START ile yarismasin, bellekteki deger hemen
                // degissin (dosya tek yazicida sirayla).
                runCatching { SettingsRepository.get(app).updateNow { it.copy(wantRunning = false) } }
                Recovery.disarm(app)
            }
            else -> startServiceCompat(context, DpiVpnService.ACTION_STOP, foreground = false)
        }
    }

    /**
     * Ayar degisti ve motor calisiyorsa yeni ayarla yeniden baslatir. Servis ayarlari zaten
     * izleyip degisince (400 ms sonra) kendisi yeniden kuruyor; bu cagri yalnizca beklemeyi
     * kisaltir. Yapilandirma ayniysa servis hicbir sey yapmaz.
     */
    fun restartIfRunning(context: Context) {
        when (EngineStateHolder.state.value) {
            is EngineState.Running, EngineState.Starting ->
                startServiceCompat(context, DpiVpnService.ACTION_RESTART, foreground = false)
            else -> Unit
        }
    }

    /**
     * Surec oldurulduyse (LMK, kill -9) baglantiyi geri getirir: son istek "acik" (wantRunning),
     * bu surecte motor yok (Stopped), VPN izni hala gecerli ve baska bir uygulamanin VPN'i etkin
     * degil (kullanicinin VPN'ini devralmayiz). Uygulamanin arayuzu acilinca ve
     * hizli ayar paneli acilinca cagrilir; arka plan yollari icin [recoverInBackground].
     *
     * Kullanici uygulamayi sistemden bilerek durdurduysa (durmaya zorla, Etkin uygulamalar >
     * Durdur) geri getirmez, istegi kapatir (bkz. [Recovery.userStoppedApp]).
     *
     * @return baslatma istendiyse true
     */
    fun recoverIfNeeded(context: Context): Boolean = recover(context, background = false, source = "arayuz")

    /**
     * Arayuz olmadan kurtarma: bekci servisi, isler ve App.onCreate. Ek olarak yalnizca
     * kurtarma kuruluyken (disarm edilmemis: kullanici durdurmadi, kalici hata yok), motorun
     * kuruldugu acilista (yeniden baslatmadan sonra karar BootReceiver'in), ust uste kurtarma
     * sinirinin altinda (Recovery.beginBackgroundRecovery) ve enstrumantasyon testi disinda.
     * Basarisiz baslatma Failed yazmaz: kullanici bir sey yapmadi, sonraki firsat (arayuz,
     * karo) yine denesin.
     */
    internal fun recoverInBackground(context: Context, source: String): Boolean {
        val started = recover(context, background = true, source = source)
        if (!started && !isInstrumentationProcess) releaseStaleServiceIfShown(context.applicationContext)
        return started
    }

    private fun recover(context: Context, background: Boolean, source: String): Boolean {
        // Arayuz/karo acildi: kullanici burada. Soguk surecte App.onCreate'in arka plan
        // kurtarmasi motoru coktan baslatmis (durum Starting) ya da sayaci ilerletmis olabilir;
        // o da ust uste "gozetimsiz" kurtarma sayilip kullaniciyi vazgecmeye itmesin
        // (REC-DEDUPE-GIVEUP). Durum denetiminden ONCE: aksi halde Starting'de hic sifirlanmaz.
        if (!background) runCatching { Recovery.resetBackgroundStreak(context.applicationContext) }
        if (EngineStateHolder.state.value != EngineState.Stopped) return false
        if (background && isInstrumentationProcess) {
            Log.i(TAG, "$source: enstrumantasyon sureci, arka plan kurtarmasi atlandi")
            return false
        }
        val app = context.applicationContext
        val repo = runCatching { SettingsRepository.get(app) }.getOrNull() ?: return false
        if (!repo.current.wantRunning) return false
        if (runCatching { Recovery.userStoppedApp(app) }.getOrDefault(false)) {
            // Senkron: durdurulmus paketin bos sureci hemen oldurulebiliyor (goruldu), yazma kaybolmasin.
            runBlocking { repo.update { it.copy(wantRunning = false) } }
            Recovery.disarm(app)
            return false
        }
        if (background && !Recovery.backgroundArmed(app)) {
            Log.i(TAG, "$source: arka plan kurtarmasi kurulu degil (durduruldu, kalici hata ya da yeni acilis)")
            return false
        }
        // VpnService.prepare ile bakilmaz: izin onceden verilmisse o cagri etkin baska bir VPN'i
        // dusurur (panel acilinca kullanicinin WireGuard'i kapaniyordu). Karar degistirmeden
        // (VpnGate); asagidaki start() icindeki prepare yalnizca baska VPN yokken calisir.
        val gate = VpnGate.unattendedStart(app)
        if (gate != VpnGate.Unattended.OK) {
            if (background) {
                // Surec olukken VPN izni gitmis ya da kullanici baska bir VPN'e gecmis (onu
                // hazirlayan uygulama bizi dusurdu ama surec olu oldugu icin onRevoke hic
                // calismadi). Kullanici bilerek baska bir tunele gecti; onRevoke gibi son istegi
                // kapat ki sonraki surec dogumlari ve yapiskan yeniden baslatma bosuna ugrasmasin.
                Log.i(TAG, "$source: $gate; arka plan kurtarmasi kapatildi")
                runBlocking { repo.update { it.copy(wantRunning = false) } }
                Recovery.disarm(app)
            } else {
                Log.i(TAG, "$source: $gate; kurtarma atlandi")
            }
            return false
        }
        // Arka plan: ust uste cokme dongusunde vazgecer (bildirim + disarm), damga yazilmaz ve
        // hemen ardindan acilan arayuz yine baglanir; aksi halde kontrol isini kurar.
        val decision = recoveryDedupe.tryBegin(SystemClock.elapsedRealtime()) {
            !background || Recovery.beginBackgroundRecovery(app)
        }
        when (decision) {
            RecoveryDedupe.Decision.DUPLICATE -> return true
            RecoveryDedupe.Decision.REFUSED -> return false
            RecoveryDedupe.Decision.BEGIN -> Unit
        }
        Log.i(TAG, "$source: son istek acikti ama motor yok, baglanti geri getiriliyor")
        return start(app, reportFailure = !background) == null
    }

    private fun startServiceCompat(context: Context, action: String, foreground: Boolean, reportFailure: Boolean = true) {
        val intent = Intent(context, DpiVpnService::class.java).setAction(action)
        try {
            if (foreground) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                // Servis on plandayken uygulama "on planda" sayilir, duz startService serbest.
                context.startService(intent)
            }
        } catch (e: IllegalStateException) {
            // Arka plandan startService yasagi (servis beklenmedik sekilde on planda degil).
            Log.w(TAG, "startService reddedildi, on plan olarak deneniyor", e)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { failStart(it, reportFailure) }
        } catch (e: Exception) {
            failStart(e, reportFailure)
        }
    }

    private fun failStart(t: Throwable, report: Boolean) {
        Log.e(TAG, "servis baslatilamadi", t)
        if (report) EngineStateHolder.set(EngineState.Failed("Servis başlatılamadı. Uygulamayı açıp tekrar deneyin."))
    }
}
