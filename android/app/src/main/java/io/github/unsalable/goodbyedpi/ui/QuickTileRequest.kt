package io.github.unsalable.goodbyedpi.ui

import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import io.github.unsalable.goodbyedpi.R
import io.github.unsalable.goodbyedpi.service.QuickTileService
import io.github.unsalable.goodbyedpi.service.QuickTileState

/**
 * Hizli ayarlar paneline (Wi-Fi, mobil veri, ucak modu dugmelerinin yanina) GoodbyeDPI
 * karosunu ekletme. Android 13+ sistem penceresiyle tek dokunusla ekler; daha eskilerde bunu
 * yalnizca kullanici panelin duzenleme ekranindan yapabiliyor.
 */
internal object QuickTileRequest {
    private const val TAG = "QuickTileRequest"

    enum class Result { ADDED, ALREADY_ADDED, DECLINED, FAILED }

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** Sistem penceresini acar; sonuc ana is parcaciginda gelir. Pencere yalnizca on planda acilir. */
    fun request(activity: Activity, onResult: (Result) -> Unit) {
        if (!supported) {
            onResult(Result.FAILED)
            return
        }
        try {
            requestApi33(activity, onResult)
        } catch (e: Exception) {
            Log.w(TAG, "karo ekleme istegi acilamadi", e)
            onResult(Result.FAILED)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun requestApi33(activity: Activity, onResult: (Result) -> Unit) {
        val sbm = activity.getSystemService(StatusBarManager::class.java)
        if (sbm == null) {
            onResult(Result.FAILED)
            return
        }
        sbm.requestAddTileService(
            ComponentName(activity, QuickTileService::class.java),
            activity.getString(R.string.tile_label),
            Icon.createWithResource(activity, R.drawable.ic_stat_power),
            activity.mainExecutor,
        ) { code ->
            val result = when (code) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> Result.ADDED
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> Result.ALREADY_ADDED
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> Result.DECLINED
                else -> {
                    Log.w(TAG, "karo ekleme istegi hatasi: $code")
                    Result.FAILED
                }
            }
            if (result == Result.ADDED || result == Result.ALREADY_ADDED) QuickTileState.setAdded(activity, true)
            onResult(result)
        }
    }

    /** Compose'un LocalContext'i aktivitenin sarmalayicisi olabilir. */
    fun Context.findActivity(): Activity? {
        var c: Context? = this
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}
