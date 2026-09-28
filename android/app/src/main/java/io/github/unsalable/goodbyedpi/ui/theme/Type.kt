package io.github.unsalable.goodbyedpi.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Masaustu Controls.xaml'deki yazi stillerinin (SectionLabel, RowTitle, RowHint, OptionTitle,
 * OptionHint) telefon boyutuna olceklenmis hali. Yazi tipi sistemin kendi yazi tipi: gomulu
 * font APK'yi buyutur, Turkce harfleri de sistem fontu zaten eksiksiz ciziyor.
 */
@Immutable
object GdpiType {
    /** Durum basligi ("Bagli"). */
    val status = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)

    /** Durumun altindaki ayrinti satiri. */
    val detail = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)

    /** Ust cubuk basligi. */
    val appTitle = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)

    /** Ekran basligi ("Ayarlar"). */
    val screenTitle = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)

    /** Bolum etiketi ("INTERNET SAGLAYICI"). */
    val section = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.08.em,
    )

    val rowTitle = TextStyle(fontSize = 16.sp, lineHeight = 22.sp)
    val rowHint = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val optionTitle = TextStyle(fontSize = 15.sp, lineHeight = 20.sp)
    val optionHint = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp)
    val button = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    val chipLabel = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.06.em)
    val chipValue = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium)
    val number = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    val mono = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontFamily = FontFamily.Monospace)
}

/** Material bilesenlerinin (alt sayfa, iletisim kutusu, snackbar) kullandigi yazi olcegi. */
internal val GdpiTypography = Typography(
    titleLarge = GdpiType.screenTitle,
    titleMedium = GdpiType.appTitle,
    bodyLarge = GdpiType.rowTitle,
    bodyMedium = GdpiType.detail,
    bodySmall = GdpiType.rowHint,
    labelLarge = GdpiType.button,
)
