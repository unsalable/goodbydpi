package io.github.unsalable.goodbyedpi.update

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.unsalable.goodbyedpi.App
import io.github.unsalable.goodbyedpi.R

/**
 * Guncelleme bildirimleri ("Uyarilar" kanali). Bildirim izni yoksa sessizce vazgecer:
 * guncelleme akisi bildirime bagli degil, uygulama acilinca afis zaten gorunur.
 */
internal object UpdateNotifier {
    private const val TAG = "UpdateNotifier"

    // Diger bildirimlerle (servis 1..) cakismasin diye ayri bir aralik.
    private const val ID_AVAILABLE = 4101
    private const val ID_UPDATED = 4102
    private const val ID_CONFIRM = 4103

    fun showAvailable(context: Context, info: ReleaseInfo) {
        post(context, ID_AVAILABLE, "Yeni sürüm hazır: ${info.version}", "Güncellemek için dokunun.", openAppIntent(context))
    }

    fun showUpdated(context: Context, version: String) {
        post(context, ID_UPDATED, "GoodbyeDPI güncellendi", "Sürüm $version kuruldu. Açmak için dokunun.", openAppIntent(context))
    }

    /**
     * Uygulama arka plandayken sistem onay ekranini dogrudan acamiyoruz (Android 10+
     * arka plan etkinlik kisiti); onay ekrani bildirime dokunulunca acilir.
     */
    fun showConfirm(context: Context, confirm: Intent) {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getActivity(context, ID_CONFIRM, confirm, flags)
        post(context, ID_CONFIRM, "Güncelleme onay bekliyor", "Kurulumu onaylamak için dokunun.", pi)
    }

    /**
     * Uygulama acilinca bilgi bildirimleri isini gordu. Onay bildirimi kalir: kurulum hala
     * kullanicinin dokunmasini bekliyor olabilir.
     */
    fun cancelInfo(context: Context) {
        runCatching {
            val nm = NotificationManagerCompat.from(context)
            nm.cancel(ID_AVAILABLE)
            nm.cancel(ID_UPDATED)
        }
    }

    private fun openAppIntent(context: Context): PendingIntent? {
        // Etkinlik sinifina dogrudan baglanmiyoruz: arayuz katmani onu yeniden duzenleyebilir.
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return PendingIntent.getActivity(
            context, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun post(context: Context, id: Int, title: String, text: String, tap: PendingIntent?) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val n = NotificationCompat.Builder(context, App.CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_power)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .apply { if (tap != null) setContentIntent(tap) }
                .build()
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (e: Exception) {
            // SecurityException (izin tam o an geri alindi) dahil: bildirim olmasa da olur.
            Log.w(TAG, "Bildirim gosterilemedi", e)
        }
    }
}
