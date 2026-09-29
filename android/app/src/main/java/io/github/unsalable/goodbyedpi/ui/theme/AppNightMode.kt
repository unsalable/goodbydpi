package io.github.unsalable.goodbyedpi.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import io.github.unsalable.goodbyedpi.model.ThemeMode

/**
 * Uygulama icindeki tema secimini sisteme de bildirir (Android 12+).
 *
 * Neden: pencere zemini ve Android 12+ acilis ekrani values/values-night kaynaklarindan
 * gelir, yani sistemin gece ayarini izler. "Koyu" secilmis ama sistem acik temadaysa her
 * soguk acilista once beyaz acilis ekrani, sonra koyu arayuz gorunurdu. setApplicationNightMode
 * bu secimi paket duzeyinde kalici yapar; sistem acilis ekranini da dogru paletle cizer.
 *
 * Deger degisince yapilandirma degisir; MainActivity manifestte uiMode degisimini kendisi
 * karsiladigi icin aktivite yeniden kurulmaz, Compose temasi renkleri yumusakca kaydirir.
 * "Sistem" seciminde gecersiz kilma kaldirilir (MODE_NIGHT_AUTO), boylece
 * isSystemInDarkTheme() yine gercek sistem ayarini okur.
 *
 * Android 12 oncesinde paket duzeyinde gece ayari yok; orada acilis ekrani sistemi izler.
 */
object AppNightMode {
    private const val TAG = "AppNightMode"
    private const val PREFS = "ui"
    private const val KEY = "appNightMode"

    fun apply(context: Context, mode: ThemeMode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val value = when (mode) {
            ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
            ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
            ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Ayni degeri her acilista yeniden yazmak gereksiz bir sistem cagrisi olurdu.
        if (prefs.getInt(KEY, -1) == value) return
        runCatching {
            context.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(value)
        }.onSuccess {
            prefs.edit { putInt(KEY, value) }
        }.onFailure {
            Log.w(TAG, "Uygulama gece modu ayarlanamadi", it)
        }
    }
}
