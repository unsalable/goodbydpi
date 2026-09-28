package io.github.unsalable.goodbyedpi.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.unsalable.goodbyedpi.ui.Choice
import io.github.unsalable.goodbyedpi.ui.Choices
import io.github.unsalable.goodbyedpi.ui.theme.GdpiTheme
import io.github.unsalable.goodbyedpi.ui.theme.GdpiType
import io.github.unsalable.goodbyedpi.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Secim alt sayfasi (masaustu acilir liste): her satirda ad ve tek satirlik aciklama, secili
 * satirin gostergesi yay ile dolar. Satirlar acilista sirayla, hafifce asagidan yukselerek
 * gelir (masaustu Motion.Stagger). Secince gosterge dolarken kisa bir an beklenip kapanir:
 * kullanici neyi sectigini gorsun.
 */
@Composable
fun PickerSheet(
    title: String,
    choices: Choices,
    selectedId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberSheetController()
    val scope = rememberCoroutineScope()
    val c = GdpiTheme.colors

    GdpiSheet(onDismiss = onDismiss, controller = sheet) {
        Text(
            text = title,
            style = GdpiType.screenTitle,
            color = c.text,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
        )
        LazyColumn(
            modifier = Modifier.selectableGroup(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(choices.items, key = { _, it -> it.id }) { index, choice ->
                ChoiceRow(
                    choice = choice,
                    selected = choice.id.equals(selectedId, ignoreCase = true),
                    index = index,
                    onClick = {
                        onSelect(choice.id)
                        scope.launch {
                            delay(170)
                            sheet.hide()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ChoiceRow(choice: Choice, selected: Boolean, index: Int, onClick: () -> Unit) {
    val c = GdpiTheme.colors
    val bg by animateColorAsState(if (selected) c.accentSoft else c.bg, Motion.soft(), label = "rowBg")

    // Sirali beliris: gecikme bir yerden sonra artmaz ki uzun listede son satir beklemesin.
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(minOf(index, 8) * 26L)
        appear.animateTo(1f, Motion.spring())
    }
    val shift = with(LocalDensity.current) { 7.dp.toPx() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .graphicsLayer {
                alpha = appear.value.coerceIn(0f, 1f)
                translationY = (1f - appear.value) * shift
            }
            .clip(FieldShape)
            .background(bg)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(choice.name, style = GdpiType.rowTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (choice.description.isNotEmpty()) {
                Text(
                    choice.description,
                    style = GdpiType.optionHint,
                    color = c.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        RadioMark(selected)
    }
}

/** Radyo isareti: cerceve rengi soner, ic nokta yay ile buyur. */
@Composable
fun RadioMark(selected: Boolean, modifier: Modifier = Modifier) {
    val c = GdpiTheme.colors
    val ring by animateColorAsState(if (selected) c.accent else c.stroke, Motion.soft(), label = "radioRing")
    val dot by animateFloatAsState(if (selected) 1f else 0f, Motion.release(), label = "radioDot")
    Box(
        modifier = modifier
            .size(22.dp)
            .border(2.dp, ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .graphicsLayer {
                    scaleX = dot
                    scaleY = dot
                    alpha = dot.coerceIn(0f, 1f)
                }
                .background(c.accent, CircleShape),
        )
    }
}
