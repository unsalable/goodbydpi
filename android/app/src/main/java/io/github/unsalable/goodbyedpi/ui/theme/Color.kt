package io.github.unsalable.goodbyedpi.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Masaustu Themes/Dark.xaml ve Light.xaml paletinin birebir karsiligi. Anahtarlar iki
 * temada ayni; arayuz renkleri yalnizca buradan okur, Material renk semasi da bundan
 * turetilir (Theme.kt) ki hazir bilesenler (alt sayfa, iletisim kutusu) ayni gorunsun.
 */
@Immutable
data class GdpiColors(
    val bg: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val stroke: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val accentSoft: Color,
    val accentAlt: Color,
    val success: Color,
    val danger: Color,
    val track: Color,
    val isDark: Boolean,
) {
    companion object {
        val Dark = GdpiColors(
            bg = Color(0xFF0E1014),
            surface = Color(0xFF171A21),
            surfaceAlt = Color(0xFF1E222B),
            stroke = Color(0xFF2A2F3A),
            text = Color(0xFFF1F5F9),
            muted = Color(0xFF94A3B8),
            accent = Color(0xFF818CF8),
            accentSoft = Color(0xFF262A3F),
            accentAlt = Color(0xFFA78BFA),
            success = Color(0xFF34D399),
            danger = Color(0xFFF87171),
            track = Color(0xFF232833),
            isDark = true,
        )

        val Light = GdpiColors(
            bg = Color(0xFFFFFFFF),
            surface = Color(0xFFF6F7F9),
            surfaceAlt = Color(0xFFEEF0F4),
            stroke = Color(0xFFE4E7EC),
            text = Color(0xFF0F172A),
            muted = Color(0xFF64748B),
            accent = Color(0xFF6366F1),
            accentSoft = Color(0xFFE4E6F8),
            accentAlt = Color(0xFF8B5CF6),
            success = Color(0xFF10B981),
            danger = Color(0xFFEF4444),
            track = Color(0xFFE9EBF0),
            isDark = false,
        )
    }
}

/**
 * Tema gecisinde deger her karede degisiyor; static olsaydi her renk adiminda tum agac
 * yeniden kurulurdu. Boyle yalnizca rengi okuyan bilesenler yenilenir.
 */
val LocalGdpiColors = compositionLocalOf { GdpiColors.Light }
