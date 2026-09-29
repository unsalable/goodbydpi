package io.github.unsalable.goodbyedpi.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import io.github.unsalable.goodbyedpi.data.SettingsRepository

/**
 * Cihaz acilisi ve uygulama guncellemesi sonrasi baglantiyi geri getirir.
 *
 * - BOOT_COMPLETED: "Acilista baslat" acik VE kapanmadan once bagliydi (wantRunning).
 * - MY_PACKAGE_REPLACED: guncellemeden once bagliydi. Guncelleme sureci oldurur; kullanici
 *   "guncelle"ye bastigi icin baglantinin kopmasi beklenmez.
 *
 * Ikisi de VPN izni hala gecerliyse (prepare()==null); degilse izin ekrani bir alicidan
 * acilamaz, kullanici uygulamayi acinca baglanir. Ayarlar senkron okunuyor (kucuk dosya,
 * SettingsRepository ilk eriste yukler); asenkron is olmadigi icin goAsync gerekmiyor.
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
            if (VpnService.prepare(context) != null) {
                Log.i(TAG, "$action: VPN izni yok, baslatilmiyor")
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
