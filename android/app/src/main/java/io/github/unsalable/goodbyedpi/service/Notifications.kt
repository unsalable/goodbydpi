package io.github.unsalable.goodbyedpi.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.unsalable.goodbyedpi.App
import io.github.unsalable.goodbyedpi.MainActivity
import io.github.unsalable.goodbyedpi.R

/**
 * Durum (kalici, on plan servis) ve uyari bildirimleri. Kanallar App.onCreate'te kuruluyor.
 *
 * POST_NOTIFICATIONS reddedilmisse notify sessizce hicbir sey gostermez; on plan servis yine
 * calisir (bildirim yalnizca Gorev Yoneticisi'nde gorunur). Bu yuzden hicbir cagri firlatmaz.
 */
object Notifications {
    private const val TAG = "GdpiNotify"

    const val STATUS_ID = 1
    const val ALERT_ID = 2

    private const val REQ_OPEN = 1
    private const val REQ_STOP = 2

    /** Bildirime dokununca uygulama; zaten aciksa one gelir, yenisi acilmaz. */
    fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context, REQ_OPEN, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * "Durdur" dogrudan servise gider: servis on planda oldugu icin startService serbest ve
     * arayuzun acilmasina gerek yok.
     */
    private fun stopIntent(context: Context): PendingIntent {
        val intent = Intent(context, DpiVpnService::class.java).setAction(DpiVpnService.ACTION_STOP)
        return PendingIntent.getService(
            context, REQ_STOP, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** On plan servis bildirimi; Stopped/Failed icin de "Baglaniyor" gorunumu (servis yeni dogmus). */
    fun status(context: Context, state: EngineState): Notification {
        val b = NotificationCompat.Builder(context, App.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_power)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent(context))
            // Android 12+ on plan servis bildirimini 10 sn geciktirebiliyor; VPN'in acik oldugu
            // hemen gorunsun.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        when (state) {
            is EngineState.Running -> {
                b.setContentTitle(context.getString(R.string.notif_connected_title, state.methodName))
                b.setContentText(context.getString(R.string.notif_connected_text, state.dnsName))
            }
            EngineState.Stopping -> b.setContentTitle(context.getString(R.string.notif_stopping_title))
            else -> {
                b.setContentTitle(context.getString(R.string.conn_state_connecting))
                b.setContentText(context.getString(R.string.notif_connecting_text))
            }
        }
        if (state !is EngineState.Stopping) {
            b.addAction(0, context.getString(R.string.action_stop), stopIntent(context))
        }
        return b.build()
    }

    /** Durum bildirimini gunceller (servis on plandayken). */
    fun updateStatus(context: Context, state: EngineState) {
        notify(context, STATUS_ID, status(context, state))
    }

    /** Watchdog vazgecti ya da baslatma kalici olarak basarisiz: ayri kanalda uyari. */
    fun showFailure(context: Context, message: String) {
        val n = NotificationCompat.Builder(context, App.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_power)
            .setContentTitle(context.getString(R.string.notif_failed_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context))
            .build()
        notify(context, ALERT_ID, n)
    }

    fun cancelFailure(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(ALERT_ID) }
    }

    private fun notify(context: Context, id: Int, n: Notification) {
        val nm = NotificationManagerCompat.from(context)
        // Izin yoksa gostermeyi hic denemiyoruz; Android 13+ bazi cihazlarda SecurityException atiyor.
        if (!nm.areNotificationsEnabled()) return
        try {
            nm.notify(id, n)
        } catch (e: SecurityException) {
            Log.w(TAG, "bildirim izni yok", e)
        }
    }
}
