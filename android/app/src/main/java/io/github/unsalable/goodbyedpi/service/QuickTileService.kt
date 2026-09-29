package io.github.unsalable.goodbyedpi.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import io.github.unsalable.goodbyedpi.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Hizli ayarlar karosu: durumu gosterir (acik/kapali/gecis) ve tek dokunusla ac/kapa.
 *
 * Durumu yalnizca karo gorunurken (onStartListening..onStopListening) dinler; panel kapaliyken
 * hicbir sey calismaz.
 */
class QuickTileService : TileService() {
    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        scope?.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { s ->
            s.launch { EngineStateHolder.state.collect { render(it) } }
        }
        // Surec olmus ve VPN dusmusse (bkz. ServiceController.recoverIfNeeded) panel acilinca
        // geri getir; karo "kapali" gosterip kullaniciyi yaniltmasin.
        ServiceController.recoverIfNeeded(this)
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        when (EngineStateHolder.state.value) {
            is EngineState.Running, EngineState.Starting -> ServiceController.stop(this)
            // Durdurulurken tiklama yok sayilir (karo zaten "kullanilamaz" gorunur).
            EngineState.Stopping -> Unit
            EngineState.Stopped, is EngineState.Failed -> {
                // Kilit ekraninda VPN acilmasin diye once kilidi actir.
                if (isLocked) unlockAndRun { connect() } else connect()
            }
        }
    }

    private fun connect() {
        val consent = ServiceController.start(this)
        if (consent != null) openApp()
    }

    /**
     * VPN izni yok: izin ekrani yalnizca bir etkinlikten acilabilir; uygulamayi ac, o istesin.
     * Disa kapali ConnectRequest takma adi uzerinden (C5): MainActivity istegi yalnizca oradan
     * kabul ediyor. Paket applicationId (sonekli olabilir), sinif adi namespace'ten.
     */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent()
            .setComponent(ComponentName(this, ServiceController.CONNECT_ALIAS))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(ServiceController.EXTRA_CONNECT, true)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pi = PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "uygulama acilamadi", e)
        }
    }

    private fun render(state: EngineState) {
        val tile = qsTile ?: return
        tile.state = when (state) {
            is EngineState.Running, EngineState.Starting -> Tile.STATE_ACTIVE
            EngineState.Stopping -> Tile.STATE_UNAVAILABLE
            EngineState.Stopped, is EngineState.Failed -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(
                when (state) {
                    is EngineState.Running -> R.string.tile_subtitle_on
                    EngineState.Starting, EngineState.Stopping -> R.string.tile_subtitle_connecting
                    EngineState.Stopped -> R.string.tile_subtitle_off
                    is EngineState.Failed -> R.string.tile_subtitle_failed
                },
            )
        }
        tile.updateTile()
    }

    private companion object {
        const val TAG = "GdpiTile"
    }
}
