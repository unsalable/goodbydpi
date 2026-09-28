package io.github.unsalable.goodbyedpi.service

import android.content.Context
import android.content.Intent
import android.net.VpnService

// SOZLESME (wave 2): imzalar sabit. STUB govde; "runtime" ajani gercek servisi baglar.

object ServiceController {
    /**
     * Baglantiyi baslatir. VPN izni henuz verilmemisse izin ekranini acan Intent'i
     * dondurur (cagiran onu baslatir, RESULT_OK gelince start'i tekrar cagirir);
     * izin varsa servisi baslatip null dondurur.
     */
    fun start(context: Context): Intent? = VpnService.prepare(context)

    fun stop(context: Context) {}

    /** Ayar degisti ve motor calisiyorsa yeni ayarla yeniden baslatir. */
    fun restartIfRunning(context: Context) {}
}
