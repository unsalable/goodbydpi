package io.github.unsalable.goodbyedpi.service

import android.content.Context
import androidx.core.content.edit

/**
 * Hizli ayarlar karosunun panelde olup olmadigi ve ekleme isteginin bir kez gosterilip
 * gosterilmedigi. Android bunu sorgulatmiyor; karo servisinin onTileAdded/onTileRemoved
 * cagrilarindan ve ekleme isteginin sonucundan biz tutuyoruz.
 */
internal object QuickTileState {
    private const val PREFS = "tile"
    private const val KEY_ADDED = "added"
    private const val KEY_PROMPTED = "prompted"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isAdded(context: Context): Boolean =
        runCatching { prefs(context).getBoolean(KEY_ADDED, false) }.getOrDefault(false)

    fun setAdded(context: Context, added: Boolean) {
        runCatching { prefs(context).edit { putBoolean(KEY_ADDED, added) } }
    }

    /** Kendiliginden (ilk baglantidan sonra) ekleme istegi yalnizca bir kez sorulur. */
    fun wasPrompted(context: Context): Boolean =
        runCatching { prefs(context).getBoolean(KEY_PROMPTED, false) }.getOrDefault(true)

    fun markPrompted(context: Context) {
        runCatching { prefs(context).edit { putBoolean(KEY_PROMPTED, true) } }
    }
}
