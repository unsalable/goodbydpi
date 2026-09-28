package io.github.unsalable.goodbyedpi.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.TileService

// STUB (wave 2): manifest'teki bilesenler var olsun diye. "runtime" ajani bu dosyayi
// silip her sinifi kendi dosyasinda gercekler (DpiVpnService.kt, BootReceiver.kt,
// QuickTileService.kt).

class DpiVpnService : VpnService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf()
        return START_NOT_STICKY
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {}
}

class QuickTileService : TileService()
