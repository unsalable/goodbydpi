package io.github.unsalable.goodbyedpi.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.unsalable.goodbyedpi.ui.theme.GdpiTheme
import kotlinx.coroutines.launch

/**
 * Alt sayfanin durumu: 0 = kapali (asagida), 1 = acik. Secici, secimden sonra sayfayi
 * kendisi kapatabilsin diye disari acik.
 */
@Stable
class SheetController internal constructor() {
    internal val progress = Animatable(0f)
    internal var onHidden: () -> Unit = {}

    /** Sayfayi asagi kaydirip kapatir, bitince onDismiss cagrilir. */
    suspend fun hide() {
        progress.animateTo(0f, tween(durationMillis = 200, easing = FastOutLinearInEasing))
        onHidden()
    }
}

@Composable
fun rememberSheetController(): SheetController = remember { SheetController() }

/**
 * Uygulamanin alt sayfasi. Material'in ModalBottomSheet'i her acilista ayri bir pencere
 * (Dialog) kuruyordu; emulatorde ilk kare 150-200 ms suruyor, acilis takiliyordu. Bu sayfa
 * ayni pencerede, ekranin ustunde cizilir: perde solar, panel yay ile asagidan gelir,
 * tutamaktan asagi surukleyince ya da perdeye dokununca kapanir, geri hareketi de kapatir.
 *
 * Ekranin kok Box'inin icinde cagrilmali (tum alani kaplar).
 */
@Composable
fun GdpiSheet(
    onDismiss: () -> Unit,
    controller: SheetController = rememberSheetController(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = GdpiTheme.colors
    val scope = rememberCoroutineScope()
    val dismiss by rememberUpdatedState(onDismiss)
    controller.onHidden = { dismiss() }
    var panelHeight by remember { mutableIntStateOf(0) }

    // Acilis: kritik sonumlu yay. Esneyen (0.8) yay paneli bir an yukari tasiyip altinda
    // bosluk birakiyordu; burada esneme yok, hiz ayni.
    LaunchedEffect(controller) {
        controller.progress.animateTo(1f, spring(dampingRatio = 1f, stiffness = 380f))
    }

    val close: () -> Unit = { scope.launch { controller.hide() } }
    BackHandler(onBack = close)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Perde: acikligi panelle birlikte artar; dokununca kapatir.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = controller.progress.value.coerceIn(0f, 1f) }
                .background(Scrim)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClickLabel = "Kapat",
                    onClick = close,
                ),
        )

        val drag = rememberDraggableState { delta ->
            val h = panelHeight
            if (h > 0) {
                scope.launch { controller.progress.snapTo((controller.progress.value - delta / h).coerceIn(0f, 1f)) }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .statusBarsPadding()
                .padding(top = 24.dp)
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .heightIn(max = maxHeight * 0.9f)
                .onSizeChanged { panelHeight = it.height }
                .graphicsLayer { translationY = (1f - controller.progress.value) * panelHeight }
                .clip(SheetShape)
                .background(c.bg)
                .draggable(
                    state = drag,
                    orientation = Orientation.Vertical,
                    onDragStopped = { velocity ->
                        // Yeterince asagi cekildiyse ya da hizla firlatildiysa kapat, degilse geri otur.
                        if (controller.progress.value < 0.7f || velocity > 1800f) {
                            controller.hide()
                        } else {
                            controller.progress.animateTo(1f, spring(dampingRatio = 1f, stiffness = 380f))
                        }
                    },
                )
                .semantics { isTraversalGroup = true }
                .navigationBarsPadding(),
        ) {
            // Tutamak: suruklenebilir oldugunu gosterir.
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 10.dp, bottom = 14.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(c.stroke),
            )
            content()
        }
    }
}

private val SheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
private val Scrim = Color.Black.copy(alpha = 0.42f)
