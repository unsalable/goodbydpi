package io.github.unsalable.goodbyedpi.ui.components

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.unsalable.goodbyedpi.ui.PowerPhase
import io.github.unsalable.goodbyedpi.ui.theme.DiagonalGradient
import io.github.unsalable.goodbyedpi.ui.theme.GdpiTheme
import io.github.unsalable.goodbyedpi.ui.theme.Motion
import kotlinx.coroutines.launch

/** Masaustu PowerSize. */
private const val BOX = 168f

/**
 * Buyuk guc dugmesi (masaustu PowerButton stili). Kompozisyon, distan ice: nabiz halesi >
 * ray > donen yay > govde > glif.
 *
 * Her durumun kendi katmani var ve saydamligi updateTransition ile yay uzerinden kayiyor;
 * durumlar arasi gecis boylece capraz gecis oluyor, renk bir karede ziplamiyor. Tum
 * degerler cizim evresinde okunur: animasyon suresince yalnizca bu dugme yeniden cizilir,
 * hicbir sey yeniden kurulmaz ya da yerlesmez.
 *
 * Surekli animasyonlar (donen yay, nefes, nabiz) yalnizca ilgili durumda calisir; Kapali
 * durumda hicbir saat donmez. Ekran gorunmezken Compose kare saati zaten durur.
 */
@Composable
fun PowerButton(phase: PowerPhase, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = GdpiTheme.colors
    val density = LocalDensity.current

    val transition = updateTransition(phase, label = "power")
    val connectedA by transition.animateFloat({ Motion.soft() }, label = "connected") { if (it == PowerPhase.Connected) 1f else 0f }
    val connectingA by transition.animateFloat({ Motion.soft() }, label = "connecting") { if (it == PowerPhase.Connecting) 1f else 0f }
    val failedA by transition.animateFloat({ Motion.soft() }, label = "failed") { if (it == PowerPhase.Failed) 1f else 0f }
    val glyphColor by transition.animateColor({ Motion.soft() }, label = "glyph") {
        when (it) {
            PowerPhase.Off -> c.muted
            PowerPhase.Connecting -> c.accent
            PowerPhase.Connected -> Color.White
            PowerPhase.Failed -> c.danger
        }
    }

    val spin = remember { Animatable(0f) }
    val breath = remember { Animatable(1f) }
    val pulse = remember { Animatable(0f) }
    val settle = remember { Animatable(1f) }
    val shake = remember { Animatable(0f) }

    // Baglaniyor: yay doner, dugme hafifce "nefes alir". Durum degisince yay oldugu yerde
    // kalir (masaustu PauseStoryboard) ki solarak kaybolurken basa ziplamasin.
    LaunchedEffect(phase == PowerPhase.Connecting) {
        if (phase == PowerPhase.Connecting) {
            launch {
                while (true) {
                    spin.snapTo(spin.value % 360f)
                    spin.animateTo(spin.value + 360f, tween(1100, easing = LinearEasing))
                }
            }
            while (true) {
                breath.animateTo(0.965f, tween(750, easing = FastOutSlowInEasing))
                breath.animateTo(1f, tween(750, easing = FastOutSlowInEasing))
            }
        } else {
            breath.animateTo(1f, Motion.spring())
        }
    }

    // Bagli: basari halesi yavasca disari dogru genisleyip soner (2.4 s, masaustuyle ayni).
    LaunchedEffect(phase == PowerPhase.Connected) {
        if (phase == PowerPhase.Connected) {
            // Baglanti kuruldugu an govde kisa bir an icine cokup esneyerek oturur.
            launch {
                settle.animateTo(0.92f, tween(120, easing = Motion.EaseOut))
                settle.animateTo(1f, Motion.release())
            }
            while (true) {
                pulse.snapTo(0f)
                pulse.animateTo(1f, tween(2400, easing = LinearEasing))
            }
        }
    }

    // Hata: bir kez yana sallanir.
    LaunchedEffect(phase) {
        if (phase == PowerPhase.Failed) {
            shake.snapTo(0f)
            shake.animateTo(
                0f,
                keyframes {
                    durationMillis = 480
                    -12f at 60
                    11f at 130
                    -8f at 200
                    6f at 270
                    -3f at 340
                    0f at 480
                },
            )
        }
    }

    // Cizimde kullanilan nesneler bir kez kurulur: cizim dongusunde ayirma yok.
    val strokes = remember(density) {
        val u = with(density) { 1.dp.toPx() }
        Strokes(
            ring = Stroke(width = 4f * u),
            arc = Stroke(width = 4f * u, cap = StrokeCap.Round),
            glyph = Stroke(width = 5f * u, cap = StrokeCap.Round),
        )
    }
    val gradient = remember(c.accent, c.accentAlt) { DiagonalGradient(c.accent, c.accentAlt) }
    val arcBrush = remember(c.accent) {
        Brush.sweepGradient(0f to c.accent.copy(alpha = 0f), 0.25f to c.accent)
    }
    val glowBrush = remember(c.success, density) {
        Brush.radialGradient(
            0f to c.success.copy(alpha = 0.34f),
            0.62f to c.success.copy(alpha = 0.10f),
            1f to c.success.copy(alpha = 0f),
            radius = with(density) { 118.dp.toPx() },
        )
    }

    val interaction = remember { MutableInteractionSource() }
    val on = phase == PowerPhase.Connected || phase == PowerPhase.Connecting
    val description = if (on) "Bağlantıyı kes" else "Bağlan"
    val state = when (phase) {
        PowerPhase.Off -> "Kapalı"
        PowerPhase.Connecting -> "Bağlanıyor"
        PowerPhase.Connected -> "Bağlı"
        PowerPhase.Failed -> "Bağlantı kurulamadı"
    }

    Box(
        modifier = modifier
            .size(BOX.dp)
            .pressScale(interaction, 0.94f)
            .graphicsLayer {
                translationX = shake.value * density.density
                val b = breath.value
                scaleX = b
                scaleY = b
            }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                stateDescription = state
            }
            .drawBehind {
                val u = size.minDimension / BOX
                val center = this.center

                // --- hale: yumusak parilti + disari yayilan nabiz halkasi
                val ca = connectedA
                if (ca > 0.002f) {
                    val p = pulse.value
                    val eased = 1f - (1f - p) * (1f - p) * (1f - p)
                    // Parilti nabizla birlikte hafifce guclenip zayiflar (yavas "nefes").
                    val glow = 0.75f + 0.25f * (1f - kotlin.math.abs(2f * p - 1f))
                    drawCircle(glowBrush, radius = 118f * u, center = center, alpha = ca * glow)
                    drawCircle(
                        color = c.success,
                        radius = 80f * u * (0.92f + 0.25f * eased),
                        center = center,
                        alpha = ca * 0.24f * (1f - p),
                    )
                }

                // --- ray: bosta notr, hata kirmizi, bagli vurgu gecisi
                val ringR = 76f * u
                drawCircle(c.track, radius = ringR, center = center, style = strokes.ring)
                val fa = failedA
                if (fa > 0.002f) drawCircle(c.danger, radius = ringR, center = center, alpha = fa, style = strokes.ring)
                if (ca > 0.002f) drawCircle(gradient, radius = ringR, center = center, alpha = ca, style = strokes.ring)

                // --- baglaniyor: donen kuyruklu yay
                val na = connectingA
                if (na > 0.002f) {
                    rotate(spin.value, center) {
                        drawArc(
                            brush = arcBrush,
                            startAngle = 0f,
                            sweepAngle = 90f,
                            useCenter = false,
                            topLeft = Offset(center.x - ringR, center.y - ringR),
                            size = Size(ringR * 2f, ringR * 2f),
                            alpha = na,
                            style = strokes.arc,
                        )
                    }
                }

                // --- govde ve glif: baglaninca birlikte icine cokup esneyerek oturur
                val s = settle.value
                scale(s, s, center) {
                    val bodyR = 64f * u
                    drawCircle(c.surfaceAlt, radius = bodyR, center = center)
                    if (ca > 0.002f) drawCircle(gradient, radius = bodyR, center = center, alpha = ca)

                    // Guc simgesi: ustu acik halka + dikey cizgi (masaustu PowerGlyph yolu).
                    val gr = 22f * u
                    val gy = center.y + 4f * u
                    val gc = glyphColor
                    drawArc(
                        color = gc,
                        startAngle = -40f,
                        sweepAngle = 260f,
                        useCenter = false,
                        topLeft = Offset(center.x - gr, gy - gr),
                        size = Size(gr * 2f, gr * 2f),
                        style = strokes.glyph,
                    )
                    drawLine(
                        color = gc,
                        start = Offset(center.x, center.y - 26f * u),
                        end = Offset(center.x, center.y),
                        strokeWidth = strokes.glyph.width,
                        cap = StrokeCap.Round,
                    )
                }
            },
    )
}

private class Strokes(val ring: Stroke, val arc: Stroke, val glyph: Stroke)
