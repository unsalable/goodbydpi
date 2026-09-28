package io.github.unsalable.goodbyedpi.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri

/**
 * Sistem ayar ekranlarina gecisler. Uretici ROM'lari bu ekranlarin bazilarini kaldirabiliyor;
 * bulunamazsa daha genel bir ekrana dusulur, hicbiri uygulamayi dusurmez.
 */
internal object SystemIntents {
    private const val TAG = "SystemIntents"
    const val GITHUB_URL = "https://github.com/unsalable/goodbydpi"

    /** Guncellemeyi kurabilmek icin "Bilinmeyen uygulamalari yukle" izni. */
    fun openUnknownSources(context: Context) {
        val primary = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri(context))
        } else {
            // Android 7'de izin uygulama basina degil, genel "Bilinmeyen kaynaklar" anahtari.
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        launch(context, primary, Intent(Settings.ACTION_SETTINGS))
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        runCatching {
            context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
        }.getOrDefault(false)

    /**
     * Pil optimizasyonu istisnasi. Zaten istisnadaysa dogrudan istek ekrani bir sey
     * gostermiyor; o durumda kullanici geri almak isterse diye genel liste acilir.
     * VPN uygulamalari Play politikasinda bu izni isteyebilen kategoride.
     */
    @SuppressLint("BatteryLife")
    fun openBatteryOptimization(context: Context) {
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        if (isIgnoringBatteryOptimizations(context)) {
            launch(context, list, Intent(Settings.ACTION_SETTINGS))
        } else {
            launch(context, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context)), list)
        }
    }

    /** "Her zaman acik VPN" ayari sistemin VPN ekraninda (uygulamanin disli simgesi). */
    fun openVpnSettings(context: Context) {
        launch(context, Intent(Settings.ACTION_VPN_SETTINGS), Intent(Settings.ACTION_WIRELESS_SETTINGS))
    }

    fun openUrl(context: Context, url: String) {
        launch(context, Intent(Intent.ACTION_VIEW, url.toUri()), null)
    }

    private fun packageUri(context: Context): Uri = ("package:" + context.packageName).toUri()

    private fun launch(context: Context, intent: Intent, fallback: Intent?) {
        // Uygulama baglamindan cagrilabilir; yeni gorev bayragi o durumda sart.
        val flags = if (context is android.app.Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK
        try {
            context.startActivity(intent.addFlags(flags))
        } catch (e: ActivityNotFoundException) {
            if (fallback == null) {
                Log.w(TAG, "Ekran acilamadi: ${intent.action}", e)
                return
            }
            try {
                context.startActivity(fallback.addFlags(flags))
            } catch (e2: Exception) {
                Log.w(TAG, "Yedek ekran da acilamadi: ${fallback.action}", e2)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Ekran acilamadi: ${intent.action}", e)
        }
    }
}
