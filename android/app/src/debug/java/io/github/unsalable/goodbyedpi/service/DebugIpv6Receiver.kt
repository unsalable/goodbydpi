package io.github.unsalable.goodbyedpi.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.unsalable.goodbyedpi.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * YALNIZCA HATA AYIKLAMA DERLEMESI (src/debug): IPv6 erisim denemesinin sonucunu adb'den
 * zorlamak icin. Emulatorun IPv6 cikisi yok; "ag IPv6'li ve calisiyor" yolu (tun kuresel
 * adresle yeniden kurulur) ancak boyle sinanabilir. Alici disa acik ama android.permission.DUMP
 * istiyor (yalnizca shell/sistem). Surum APK'sina girmez.
 *
 *   adb shell am broadcast -n <pkg>/io.github.unsalable.goodbyedpi.service.DebugIpv6Receiver \
 *       [--es probe pass|fail|real] [--es ipv6 on|off]
 *
 * probe pass/fail: sonraki denemeler aga cikmadan bu sonucu verir; real: gercek deneme. Servis
 * degisikligi gorunce onbellegi atip hemen yeniden dener. ipv6 on/off: Ayarlar > IPv6'yi
 * arayuzdeki anahtar gibi yazar (servis calisirken ayar gozlemcisinin yolunu sinamak icin).
 * Sonuc logcat'te GdpiVpnService etiketiyle ("ipv6: ...").
 */
class DebugIpv6Receiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.hasExtra("probe")) {
            val value = when (intent.getStringExtra("probe")) {
                "pass" -> true
                "fail" -> false
                "real" -> null
                else -> {
                    Log.e(TAG, "bilinmeyen deger: ${intent.getStringExtra("probe")} (pass|fail|real)")
                    return
                }
            }
            Ipv6Probe.setDebugOverride(value)
            Log.i(TAG, "IPv6 denemesi zorlamasi=$value")
        }
        val setting = when (intent.getStringExtra("ipv6")) {
            null -> return
            "on" -> true
            "off" -> false
            else -> {
                Log.e(TAG, "bilinmeyen deger: ${intent.getStringExtra("ipv6")} (on|off)")
                return
            }
        }
        val app = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                SettingsRepository.get(app).update { it.copy(ipv6 = setting) }
                Log.i(TAG, "IPv6 ayari=$setting")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "GDPI_IPV6"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
