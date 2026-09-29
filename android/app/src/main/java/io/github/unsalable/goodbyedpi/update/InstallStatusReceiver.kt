package io.github.unsalable.goodbyedpi.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log

/**
 * PackageInstaller oturumunun sonucunu alir (manifest'te exported=false: yalnizca sistemin
 * doldurdugu kendi PendingIntent'imiz ulasir).
 *
 * Basarili bir kendi-kendini-guncellemede surec genellikle bu yayin gelmeden sonlandirilir;
 * "guncellendi" bilgisi bu yuzden buraya degil ayarlardaki pendingUpdate'e dayanir.
 */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UpdateInstaller.ACTION_INSTALL_STATUS) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val version = intent.getStringExtra(UpdateInstaller.EXTRA_VERSION)
        Log.i(TAG, "Kurulum durumu: $status ($message) surum=$version")

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = confirmIntent(intent)
            if (confirm == null) {
                UpdateManager.onInstallResult(context, PackageInstaller.STATUS_FAILURE, "onay ekranı yok", version)
                return
            }
            // Gorunurluk karari (etkinlik mi, bildirim mi) UpdateManager'da: surec onceligi VPN'in
            // on plan servisi yuzunden "gorunur" diyor ama sistem arka plandan etkinlik acmaya
            // izin vermiyor (UPD-1).
            val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
            UpdateManager.onConfirmRequired(context, confirm, sessionId, version)
            return
        }

        val pending = goAsync()
        UpdateManager.onInstallResult(context, status, message, version) { pending.finish() }
    }

    @Suppress("DEPRECATION")
    private fun confirmIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    private companion object {
        const val TAG = "InstallStatusReceiver"
    }
}

/**
 * Uygulama guncellendikten sonra (MY_PACKAGE_REPLACED) calisir. Kendi guncellememiz surecimizi
 * sonlandirdigi icin kullanici uygulamanin "kayboldugunu" goruyor; kisa bir bildirim neyin
 * oldugunu soyler ve dokununca uygulamayi geri acar. VPN'i geri getirmek servis katmaninin
 * BootReceiver'inin isi.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        UpdateManager.onPackageReplaced(context)
    }
}
