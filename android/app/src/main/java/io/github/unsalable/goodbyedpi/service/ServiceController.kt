package io.github.unsalable.goodbyedpi.service

import android.content.Context
import android.content.Intent
import android.net.VpnService
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

    @Volatile
    private var lastRecoveryAt = 0L

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
    fun start(context: Context): Intent? = start(context, reportFailure = true)

    private fun start(context: Context, reportFailure: Boolean): Intent? {
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
     * bu surecte motor yok (Stopped) ve VPN izni hala gecerli. Uygulamanin arayuzu acilinca ve
     * hizli ayar paneli acilinca cagrilir; arka plan yollari icin [recoverInBackground].
     *
     * Kullanici uygulamayi sistemden bilerek durdurduysa (durmaya zorla, Etkin uygulamalar >
     * Durdur) geri getirmez, istegi kapatir (bkz. [Recovery.userStoppedApp]).
     *
     * @return baslatma istendiyse true
     */
    fun recoverIfNeeded(context: Context): Boolean = recover(context, background = false, source = "arayuz")

    /**
     * Arayuz olmadan kurtarma: bekci servisi, periyodik is ve App.onCreate. Ek olarak yalnizca
     * motorun kuruldugu acilista (yeniden baslatmadan sonra karar BootReceiver'in) ve
     * enstrumantasyon testi disinda. Basarisiz baslatma Failed yazmaz: kullanici bir sey
     * yapmadi, sonraki firsat (arayuz, karo) yine denesin.
     */
    internal fun recoverInBackground(context: Context, source: String): Boolean =
        recover(context, background = true, source = source)

    private fun recover(context: Context, background: Boolean, source: String): Boolean {
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
        if (background && !Recovery.sameBootAsArmed(app)) {
            Log.i(TAG, "$source: motor bu acilista kurulmadi; karar BootReceiver'in")
            return false
        }
        val prepared = runCatching { VpnService.prepare(app) == null }.getOrDefault(false)
        if (!prepared) return false
        synchronized(this) {
            val now = SystemClock.elapsedRealtime()
            if (lastRecoveryAt != 0L && now - lastRecoveryAt < RECOVERY_DEDUPE_MS) return true
            lastRecoveryAt = now
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
