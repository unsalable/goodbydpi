package io.github.unsalable.goodbyedpi.ui.theme

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * Masaustu Services/Motion.cs + SpringEase.cs karsiligi: arayuzun her yeri ayni "fizikle"
 * hareket etsin diye tum egriler burada, bir kez tanimli. Compose sistemdeki animasyon
 * olcegine kendisi uyar; gelistirici ayarlarindan animasyonlar kapatilinca her sey aninda
 * yerine oturur, burada ayrica bir kontrol gerekmiyor.
 */
object Motion {
    /** Ana yay: hafif esner, hizla oturur (SwiftUI'daki "smooth" yayina yakin). */
    const val DAMPING = 0.8f
    const val STIFFNESS = 400f

    fun <T> spring(): FiniteAnimationSpec<T> = spring(dampingRatio = DAMPING, stiffness = STIFFNESS)

    /** Birakinca esneyen basma geri donusu (masaustu ReleaseSpring, Bounce 0.4). */
    fun <T> release(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.55f, stiffness = 500f)

    /** Basma: hizli ve yumusak, esnemeden (masaustu PressEase 110 ms). */
    fun <T> press(): FiniteAnimationSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1400f)

    /** Renk ve saydamlik gecisleri; renkte esneme olmasin diye sonumlu. */
    fun <T> soft(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 300f)

    /** Masaustu EaseOut (QuadraticEase) karsiligi. */
    val EaseOut = CubicBezierEasing(0.25f, 0.46f, 0.45f, 0.94f)

    /** Masaustu FadeIn 160 ms / FadeOut 260 ms. */
    fun <T> fadeIn(): FiniteAnimationSpec<T> = tween(durationMillis = 160, easing = EaseOut)
    fun <T> fadeOut(): FiniteAnimationSpec<T> = tween(durationMillis = 220, easing = EaseOut)

    /** Masaustu RefreshText'in kayma mesafesi. */
    val RefreshOffset = 6.dp
}

/**
 * Masaustu RefreshText: metin degisince yenisi hafifce asagidan yukselerek belirir, eskisi
 * hizla soner. Boyut degisimi de yay ile olur ki alttaki satirlar ziplamasin.
 */
@Composable
fun <S> rememberRefreshTransform(): AnimatedContentTransitionScope<S>.() -> ContentTransform {
    val offsetPx = with(LocalDensity.current) { Motion.RefreshOffset.roundToPx() }
    return remember(offsetPx) {
        {
            (
                fadeIn(Motion.fadeIn()) +
                    slideInVertically(Motion.spring<IntOffset>()) { offsetPx }
                ) togetherWith fadeOut(tween(durationMillis = 90)) using
                SizeTransform(clip = false) { _, _ -> Motion.spring<IntSize>() }
        }
    }
}
