package io.github.unsalable.goodbyedpi.service

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.unsalable.goodbyedpi.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// SOZLESME (wave 2): imzalar sabit. Arayuz, karo ve alici servise yalnizca buradan gider.

object ServiceController {
    private const val TAG = "GdpiController"

    /**
     * MainActivity'yi bu ekstra (true) ile acan taraf (hizli ayar karosu, VPN izni eksikken)
     * "baglanmak istiyorum" der: arayuz izni isteyip start'i cagirmali.
     */
    const val EXTRA_CONNECT = "io.github.unsalable.goodbyedpi.extra.CONNECT"

    // Servis calismiyorken wantRunning'i kapatmak icin; surec omru boyunca, GlobalScope degil.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Baglantiyi baslatir. VPN izni henuz verilmemisse izin ekranini acan Intent'i
     * dondurur (cagiran onu baslatir, RESULT_OK gelince start'i tekrar cagirir);
     * izin varsa servisi baslatip null dondurur. wantRunning'i servis kendisi true yapar.
     */
    fun start(context: Context): Intent? {
        val consent = try {
            VpnService.prepare(context)
        } catch (e: Exception) {
            Log.w(TAG, "VpnService.prepare", e)
            null
        }
        if (consent != null) return consent
        startServiceCompat(context, DpiVpnService.ACTION_START, foreground = true)
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
                scope.launch {
                    runCatching { SettingsRepository.get(app).update { it.copy(wantRunning = false) } }
                }
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
     * bu surecte motor yok (Stopped) ve VPN izni hala gecerli. Uygulamanin arayuzu acilinca,
     * hizli ayar paneli acilinca ve cihaz acilisinda cagrilir.
     *
     * Neden gerekli: API 36'da VPN servisinin sureci SIGKILL ile olunce cekirdek once tun'u
     * kapatiyor, sistemin Vpn sinifi servise bagini kopariyor (interfaceRemoved -> unbind,
     * DeadObjectException) ve ActivityManager servisi "baslatilmis ama sureci yok" halde
     * birakiyor; START_STICKY yeniden baslatmasi hic planlanmiyor (emulatorde 4/4 denemede
     * goruldu). Sistemin kendisi geri getirmedigi icin ilk firsatta biz getiriyoruz.
     *
     * @return baslatma istendiyse true
     */
    fun recoverIfNeeded(context: Context): Boolean {
        if (EngineStateHolder.state.value != EngineState.Stopped) return false
        val settings = runCatching { SettingsRepository.get(context).current }.getOrNull() ?: return false
        if (!settings.wantRunning) return false
        val prepared = runCatching { VpnService.prepare(context) == null }.getOrDefault(false)
        if (!prepared) return false
        Log.i(TAG, "son istek acikti ama motor yok: baglanti geri getiriliyor")
        return start(context) == null
    }

    private fun startServiceCompat(context: Context, action: String, foreground: Boolean) {
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
                .onFailure { failStart(it) }
        } catch (e: Exception) {
            failStart(e)
        }
    }

    private fun failStart(t: Throwable) {
        Log.e(TAG, "servis baslatilamadi", t)
        EngineStateHolder.set(EngineState.Failed("Servis başlatılamadı. Uygulamayı açıp tekrar deneyin."))
    }
}
