package io.github.unsalable.goodbyedpi.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * YALNIZCA HATA AYIKLAMA DERLEMESI (src/debug): IPv6 erisim denemesinin sonucunu adb'den
 * zorlamak icin. Emulatorun IPv6 cikisi yok; "ag IPv6'li ve calisiyor" yolu (tun kuresel
 * adresle yeniden kurulur) ancak boyle sinanabilir. Alici disa acik ama android.permission.DUMP
 * istiyor (yalnizca shell/sistem). Surum APK'sina girmez.
 *
 *   adb shell am broadcast -n <pkg>/io.github.unsalable.goodbyedpi.service.DebugIpv6Receiver \
 *       --es probe pass|fail|real
 *
 * pass/fail: sonraki denemeler aga cikmadan bu sonucu verir; real: gercek deneme. Servis
 * degisikligi gorunce onbellegi atip hemen yeniden dener; sonuc logcat'te GdpiVpnService
 * etiketiyle ("ipv6: ...").
 */
class DebugIpv6Receiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val value = when (intent.getStringExtra("probe")) {
            "pass" -> true
            "fail" -> false
            "real", null -> null
            else -> {
                Log.e(TAG, "bilinmeyen deger: ${intent.getStringExtra("probe")} (pass|fail|real)")
                return
            }
        }
        Ipv6Probe.setDebugOverride(value)
        Log.i(TAG, "IPv6 denemesi zorlamasi=$value")
    }

    private companion object {
        const val TAG = "GDPI_IPV6"
    }
}
