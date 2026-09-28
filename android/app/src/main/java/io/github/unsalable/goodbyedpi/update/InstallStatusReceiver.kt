package io.github.unsalable.goodbyedpi.update

import android.app.ActivityManager
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
                UpdateManager.onInstallResult(context, PackageInstaller.STATUS_FAILURE, "onay ekranı yok")
                return
            }
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (isVisible()) {
                try {
                    context.startActivity(confirm)
                } catch (e: Exception) {
                    Log.w(TAG, "Onay ekrani acilamadi, bildirime dusuluyor", e)
                    UpdateNotifier.showConfirm(context.applicationContext, confirm)
                }
            } else {
                UpdateNotifier.showConfirm(context.applicationContext, confirm)
            }
            UpdateManager.onInstallResult(context, status, message)
            return
        }

        val pending = goAsync()
        UpdateManager.onInstallResult(context, status, message) { pending.finish() }
    }

    @Suppress("DEPRECATION")
    private fun confirmIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    /** Arayuz gorunur mu? Gorunmuyorsa sistem arka plandan etkinlik baslatmamiza izin vermez. */
    private fun isVisible(): Boolean = try {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    } catch (e: Exception) {
        false
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
