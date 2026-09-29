package io.github.unsalable.goodbyedpi

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import io.github.unsalable.goodbyedpi.data.SettingsRepository
import io.github.unsalable.goodbyedpi.service.ServiceController

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        // Hicbiri uygulamayi acilista dusurmemeli: servis sistem tarafindan (her zaman acik VPN,
        // cihaz acilisi) arayuz olmadan da baslatiliyor, orada cokme kullaniciya gorunmez kalir.
        runCatching { createNotificationChannels() }
            .onFailure { Log.w(TAG, "Bildirim kanallari olusturulamadi", it) }

        // Ayar dosyasini simdi okuyalim: ilk ekran ve servis bekletmeden hazir bulsun.
        runCatching { SettingsRepository.get(this) }
            .onFailure { Log.w(TAG, "Ayarlar yuklenemedi", it) }

        // Surec baska bir nedenle dogduysa (karo baglamasi, alici, periyodik is, bekci) ve VPN
        // bu surecin onceki hali olurken dustuyse hemen geri getir: sistem DpiVpnService'i
        // kendisi yeniden baslatmiyor (bkz. service.Recovery). Kosullar (istek acik, ayni
        // acilis, kullanici durdurmamis, VPN izni var, test sureci degil) ServiceController'da.
        runCatching { ServiceController.recoverInBackground(this, "surec baslangici") }
            .onFailure { Log.w(TAG, "Baglanti geri getirilemedi", it) }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return

        // Kalici baglanti bildirimi: sessiz, rozetsiz; yalnizca durum cubugunda simge.
        val status = NotificationChannel(
            CHANNEL_STATUS,
            getString(R.string.channel_status_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_status_description)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }

        val alerts = NotificationChannel(
            CHANNEL_ALERTS,
            getString(R.string.channel_alerts_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.channel_alerts_description)
        }

        nm.createNotificationChannels(listOf(status, alerts))
    }

    companion object {
        private const val TAG = "App"

        /** Baglanti durumu (on plan servis) bildirim kanali. */
        const val CHANNEL_STATUS = "status"

        /** Hata ve guncelleme uyarilari kanali. */
        const val CHANNEL_ALERTS = "alerts"
    }
}
