package io.github.unsalable.goodbyedpi.ui.theme

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.drawable.toDrawable
import io.github.unsalable.goodbyedpi.model.ThemeMode

/** Kisayol: GdpiTheme.colors.accent gibi okunur. */
object GdpiTheme {
    val colors: GdpiColors
        @Composable get() = LocalGdpiColors.current
}

/** Tema modu + sistem ayari -> koyu mu? */
@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * Uygulamanin temasi. Masaustu tema degisimini eski goruntuyu soldurarak yapiyordu;
 * burada her renk kendi basina yumusakca kayiyor, sonuc ayni capraz gecis.
 *
 * Denendi: masaustu gibi eski ekranin resmini alip soldurmak (GraphicsLayer.toImageBitmap).
 * Emulatorde tam ekran resmin okunmasi ve her karede cizilmesi, renklerin kaydirilmasindan
 * daha pahali cikti (dumpsys gfxinfo: %14-21 takilan kare, renk gecisinde %7).
 *
 * Sistem cubugu simgeleri (saat, pil) de temayi izler: koyu temada acik, acik temada koyu.
 */
@Composable
fun GoodbyeDpiTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val dark = themeMode.isDark()
    val target = if (dark) GdpiColors.Dark else GdpiColors.Light
    val colors = animatedColors(target)

    SystemBarsFollowTheme(dark, target.bg)

    // Material bilesenleri (diyalog, metin kutusu imleci) hedef paletten beslenir: her
    // karede yeni sema kurmak yerine gecisin sonundaki degerler yeterli.
    val scheme = remember(target) { target.toColorScheme() }

    CompositionLocalProvider(LocalGdpiColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = GdpiTypography, content = content)
    }
}

private val ThemeSpec: AnimationSpec<Color> = tween(durationMillis = 360, easing = Motion.EaseOut)

@Composable
private fun animatedColors(target: GdpiColors): GdpiColors {
    val bg by animateColorAsState(target.bg, ThemeSpec, label = "bg")
    val surface by animateColorAsState(target.surface, ThemeSpec, label = "surface")
    val surfaceAlt by animateColorAsState(target.surfaceAlt, ThemeSpec, label = "surfaceAlt")
    val stroke by animateColorAsState(target.stroke, ThemeSpec, label = "stroke")
    val text by animateColorAsState(target.text, ThemeSpec, label = "text")
    val muted by animateColorAsState(target.muted, ThemeSpec, label = "muted")
    val accent by animateColorAsState(target.accent, ThemeSpec, label = "accent")
    val accentSoft by animateColorAsState(target.accentSoft, ThemeSpec, label = "accentSoft")
    val accentAlt by animateColorAsState(target.accentAlt, ThemeSpec, label = "accentAlt")
    val success by animateColorAsState(target.success, ThemeSpec, label = "success")
    val danger by animateColorAsState(target.danger, ThemeSpec, label = "danger")
    val track by animateColorAsState(target.track, ThemeSpec, label = "track")
    // Nesne yalnizca bir renk gercekten degisince yenilenir; bosta her kurulumda ayni ornek.
    return remember(bg, surface, surfaceAlt, stroke, text, muted, accent, accentSoft, accentAlt, success, danger, track) {
        GdpiColors(
            bg = bg, surface = surface, surfaceAlt = surfaceAlt, stroke = stroke, text = text,
            muted = muted, accent = accent, accentSoft = accentSoft, accentAlt = accentAlt,
            success = success, danger = danger, track = track, isDark = target.isDark,
        )
    }
}

@Composable
private fun SystemBarsFollowTheme(dark: Boolean, background: Color) {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    DisposableEffect(activity, dark) {
        // Cubuklar seffaf kalir (kenardan kenara); yalnizca simge rengi temaya gore secilir.
        // 3 dugmeli gezinmede sistem yine de hafif bir perde ekler, okunabilirlik icin.
        val transparent = android.graphics.Color.TRANSPARENT
        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(transparent, transparent) { dark },
            navigationBarStyle = SystemBarStyle.auto(LightScrim, DarkScrim) { dark },
        )
        // Pencere zemini de temaya uysun: klavye acilirken ya da geri hareketinde bir an
        // gorunen zemin yanlis renkte parlamasin.
        activity.window.setBackgroundDrawable(background.toArgb().toDrawable())
        onDispose { }
    }
}

// androidx.activity'nin varsayilan perdeleriyle ayni degerler.
private val LightScrim = android.graphics.Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DarkScrim = android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)

private fun GdpiColors.toColorScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = Color.White,
        primaryContainer = accentSoft,
        onPrimaryContainer = text,
        secondary = accentAlt,
        onSecondary = Color.White,
        secondaryContainer = accentSoft,
        onSecondaryContainer = text,
        background = bg,
        onBackground = text,
        surface = bg,
        onSurface = text,
        surfaceVariant = surfaceAlt,
        onSurfaceVariant = muted,
        surfaceContainerLowest = bg,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surface,
        surfaceContainerHighest = surfaceAlt,
        surfaceBright = surfaceAlt,
        surfaceDim = bg,
        inverseSurface = text,
        inverseOnSurface = bg,
        inversePrimary = accentAlt,
        outline = stroke,
        outlineVariant = stroke,
        error = danger,
        onError = Color.White,
        scrim = Color.Black,
    )
}

/**
 * Masaustu AccentGradient: accent -> accentAlt, sol ustten sag alta (135 derece). Golgelendirici
 * yalnizca boyut degisince yeniden kurulur; cizim dongusunde yeni nesne uretilmez.
 */
class DiagonalGradient(private val from: Color, private val to: Color) : ShaderBrush() {
    override fun createShader(size: Size): Shader =
        LinearGradientShader(
            from = Offset.Zero,
            to = Offset(size.width, size.height),
            colors = listOf(from, to),
        )

    override fun equals(other: Any?): Boolean =
        other is DiagonalGradient && other.from == from && other.to == to

    override fun hashCode(): Int = 31 * from.hashCode() + to.hashCode()
}

/** Temanin anlik renkleriyle vurgu gecisi; renkler degismedikce ayni firca doner. */
@Composable
fun rememberAccentGradient(): Brush {
    val c = GdpiTheme.colors
    return remember(c.accent, c.accentAlt) { DiagonalGradient(c.accent, c.accentAlt) }
}
