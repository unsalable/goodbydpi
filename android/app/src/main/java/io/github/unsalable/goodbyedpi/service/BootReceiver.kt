package io.github.unsalable.goodbyedpi.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.unsalable.goodbyedpi.data.SettingsRepository

/**
 * Cihaz acilisi ve uygulama guncellemesi sonrasi baglantiyi geri getirir.
 *
 * - BOOT_COMPLETED: "Acilista baslat" acik VE kapanmadan once bagliydi (wantRunning).
 * - MY_PACKAGE_REPLACED: guncellemeden once bagliydi. Guncelleme sureci oldurur; kullanici
 *   "guncelle"ye bastigi icin baglantinin kopmasi beklenmez.
 *
 * Ikisi de VPN izni hala gecerliyse ve baska bir uygulamanin VPN'i etkin degilse
 * (VpnGate.unattendedStart); izin yoksa izin ekrani bir alicidan acilamaz, kullanici uygulamayi
 * acinca baglanir. Baska VPN etkinse (her zaman acik is VPN'i, acilista baglanan WireGuard)
 * onu devralmayiz: kullanici istemeden onun VPN'ini dusurmek olurdu. Ayarlar senkron
 * okunuyor (kucuk dosya, SettingsRepository ilk eriste yukler); asenkron is olmadigi icin
 * goAsync gerekmiyor.
 * Her iki yayin da Android'in arka plan on plan servis yasagindan muaf.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        try {
            val s = SettingsRepository.get(context).current
            val want = when (action) {
                Intent.ACTION_BOOT_COMPLETED -> s.startOnBoot && s.wantRunning
                else -> s.wantRunning
            }
            if (!want) return
            // VpnService.prepare ile bakilmaz: izin onceden verilmisse etkin baska VPN'i dusurur.
            val gate = VpnGate.unattendedStart(context)
            if (gate != VpnGate.Unattended.OK) {
                Log.i(TAG, "$action: $gate, baslatilmiyor")
                return
            }
            Log.i(TAG, "$action: baglanti geri getiriliyor")
            ServiceController.start(context)
        } catch (e: Exception) {
            Log.e(TAG, "$action islenemedi", e)
        }
    }

    private companion object {
        const val TAG = "GdpiBoot"
    }
}
