package io.github.unsalable.goodbyedpi.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.unsalable.goodbyedpi.service.TrafficStats
import io.github.unsalable.goodbyedpi.ui.components.CardShape
import io.github.unsalable.goodbyedpi.ui.components.GdpiIconButton
import io.github.unsalable.goodbyedpi.ui.components.GdpiIcons
import io.github.unsalable.goodbyedpi.ui.components.GradientDot
import io.github.unsalable.goodbyedpi.ui.components.PickerSheet
import io.github.unsalable.goodbyedpi.ui.components.PowerButton
import io.github.unsalable.goodbyedpi.ui.components.TrafficPanel
import io.github.unsalable.goodbyedpi.ui.components.UpdateBanner
import io.github.unsalable.goodbyedpi.ui.components.pressScale
import io.github.unsalable.goodbyedpi.ui.theme.GdpiTheme
import io.github.unsalable.goodbyedpi.ui.theme.GdpiType
import io.github.unsalable.goodbyedpi.ui.theme.Motion
import io.github.unsalable.goodbyedpi.ui.theme.rememberAccentGradient
import io.github.unsalable.goodbyedpi.ui.theme.rememberRefreshTransform
import io.github.unsalable.goodbyedpi.update.UpdateState
import kotlinx.coroutines.flow.StateFlow

/**
 * Ana ekranin dikey yerlesimi. Cocuklar: [dugme + durum], [canli trafik], [serit + cipler].
 *
 * Dugme ekranin optik merkezine (biraz yukari) sabitlenir; alttaki trafik paneli ya da
 * guncelleme seridi acilinca dugme yerinden oynamaz; ancak yer kalmazsa yukari itilir.
 * Cipler basparmak mesafesinde, en altta. Kucuk ekranda bos alan yoktur, govde kaydirilir.
 */
private val PowerLayout = object : Arrangement.Vertical {
    override fun Density.arrange(totalSize: Int, sizes: IntArray, outPositions: IntArray) {
        if (sizes.isEmpty()) return
        val top = sizes.first()
        val bottom = if (sizes.size > 1) sizes.last() else 0
        val middle = sizes.sum() - top - bottom
        val preferred = ((totalSize - top) * 0.3f).toInt()
        var y = preferred.coerceAtMost(totalSize - bottom - middle - top).coerceAtLeast(0)
        sizes.forEachIndexed { i, h ->
            if (i == sizes.lastIndex && i > 0) y = maxOf(y, totalSize - h)
            outPositions[i] = y
            y += h
        }
    }
}

/** Hangi secici alt sayfasi acik (ana ekran ve Ayarlar ayni turleri kullanir). */
enum class Picker { Isp, Method, Dns }

/**
 * Ana ekran: ust cubuk, guc dugmesi, durum, canli trafik, guncelleme seridi ve secim cipleri.
 * Durum parametre olarak gelir; burada is mantigi yok, yalnizca gosterim ve olaylar.
 */
@Composable
fun MainScreen(
    settings: SettingsUi,
    connection: ConnectionUi,
    updateState: UpdateState,
    traffic: StateFlow<TrafficStats>,
    isDark: Boolean,
    onPower: () -> Unit,
    onToggleTheme: () -> Unit,
    onOpenSettings: () -> Unit,
    onSelectIsp: (String) -> Unit,
    onSelectMethod: (String) -> Unit,
    onSelectDns: (String) -> Unit,
    onUpdate: () -> Unit,
    onUpdateLater: () -> Unit,
    onOpenInstallPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var picker by rememberSaveable { mutableStateOf<Picker?>(null) }

    Column(modifier.fillMaxSize()) {
        TopBar(isDark = isDark, onToggleTheme = onToggleTheme, onOpenSettings = onOpenSettings)

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val viewport = maxHeight
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = viewport)
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = PowerLayout,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Hale dugmenin disina tasiyor; ust bosluk onu kesmesin diye genis.
                    Spacer(Modifier.height(44.dp))
                    PowerButton(phase = connection.phase, onClick = onPower)
                    Spacer(Modifier.height(40.dp))
                    StatusBlock(connection)
                }

                AnimatedVisibility(
                    visible = connection.phase == PowerPhase.Connected,
                    enter = fadeIn(Motion.fadeIn()) + expandVertically(Motion.spring()),
                    exit = fadeOut(Motion.fadeOut()) + shrinkVertically(Motion.spring()),
                    label = "traffic",
                ) {
                    TrafficPanel(traffic, Modifier.padding(top = 20.dp).widthIn(max = 400.dp))
                }

                Column(Modifier.fillMaxWidth().widthIn(max = 480.dp).padding(top = 24.dp, bottom = 16.dp)) {
                    UpdateBanner(
                        state = updateState,
                        onUpdate = onUpdate,
                        onLater = onUpdateLater,
                        onOpenPermission = onOpenInstallPermission,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SelectionChip("SAĞLAYICI", settings.isp.name, Modifier.weight(1f)) { picker = Picker.Isp }
                        SelectionChip("YÖNTEM", settings.method.name, Modifier.weight(1f)) { picker = Picker.Method }
                        SelectionChip("DNS", settings.dns.name, Modifier.weight(1f)) { picker = Picker.Dns }
                    }
                }
            }
        }
    }

    PickerHost(
        picker = picker,
        settings = settings,
        onSelectIsp = onSelectIsp,
        onSelectMethod = onSelectMethod,
        onSelectDns = onSelectDns,
        onDismiss = { picker = null },
    )
}

/** Acik seciciyi gosterir; ana ekran ve Ayarlar ortak kullanir. */
@Composable
fun PickerHost(
    picker: Picker?,
    settings: SettingsUi,
    onSelectIsp: (String) -> Unit,
    onSelectMethod: (String) -> Unit,
    onSelectDns: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    when (picker) {
        Picker.Isp -> PickerSheet("İnternet sağlayıcı", settings.isps, settings.isp.id, onSelectIsp, onDismiss)
        Picker.Method -> PickerSheet("Yöntem", settings.methods, settings.method.id, onSelectMethod, onDismiss)
        Picker.Dns -> PickerSheet("DNS", settings.dnsList, settings.dns.id, onSelectDns, onDismiss)
        null -> Unit
    }
}

// ----------------------------------------------------------------- ust cubuk

@Composable
private fun TopBar(isDark: Boolean, onToggleTheme: () -> Unit, onOpenSettings: () -> Unit) {
    val c = GdpiTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GradientDot(10.dp, rememberAccentGradient())
        Text(
            "GoodbyeDPI",
            style = GdpiType.appTitle,
            color = c.text,
            modifier = Modifier.padding(start = 10.dp).weight(1f),
        )
        ThemeToggle(isDark, onToggleTheme)
        GdpiIconButton(Icons.Filled.Settings, "Ayarlar", onOpenSettings)
    }
}

/**
 * Tema dugmesi: acik temada ay, koyu temada gunes (masaustuyle ayni). Simge degisirken
 * eskisi donerek kuculur, yenisi donerek buyur.
 */
@Composable
fun ThemeToggle(isDark: Boolean, onToggle: () -> Unit) {
    val c = GdpiTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = ripple(bounded = true), role = Role.Button, onClick = onToggle)
            .semantics {
                contentDescription = "Temayı değiştir"
                stateDescription = if (isDark) "Koyu tema" else "Açık tema"
            },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = isDark,
            transitionSpec = {
                (fadeIn(Motion.fadeIn()) + scaleIn(Motion.release(), initialScale = 0.4f)) togetherWith
                    (fadeOut(Motion.fadeOut()) + scaleOut(Motion.spring(), targetScale = 0.4f))
            },
            modifier = Modifier.pressScale(interaction, 0.84f),
            label = "themeIcon",
        ) { dark ->
            // Donus, gecis suresince AnimatedContent'in kendi saydamligiyla birlikte hissedilir.
            Icon(
                imageVector = if (dark) GdpiIcons.LightMode else GdpiIcons.DarkMode,
                contentDescription = null,
                tint = c.muted,
                modifier = Modifier
                    .size(22.dp)
                    .graphicsLayer { rotationZ = if (dark) 0f else -15f },
            )
        }
    }
}

// --------------------------------------------------------------------- durum

/**
 * Durum satiri: nokta rengi capraz gecisle degisir, baslik ve ayrinti degisince yeni metin
 * hafifce yukselerek gelir (masaustu RefreshText).
 */
@Composable
private fun StatusBlock(connection: ConnectionUi) {
    val c = GdpiTheme.colors
    val dotColor by animateColorAsState(
        when (connection.phase) {
            PowerPhase.Off -> c.muted
            PowerPhase.Connecting -> c.accent
            PowerPhase.Connected -> c.success
            PowerPhase.Failed -> c.danger
        },
        Motion.soft(),
        label = "dot",
    )
    val detailColor by animateColorAsState(
        if (connection.phase == PowerPhase.Failed) c.danger else c.muted,
        Motion.soft(),
        label = "detailColor",
    )
    val refresh = rememberRefreshTransform<String>()

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).background(dotColor, CircleShape))
            AnimatedContent(
                targetState = connection.title,
                transitionSpec = refresh,
                modifier = Modifier.padding(start = 10.dp),
                label = "statusTitle",
            ) { title ->
                Text(title, style = GdpiType.status, color = c.text)
            }
        }
        AnimatedContent(
            targetState = connection.detail,
            transitionSpec = refresh,
            modifier = Modifier.padding(top = 6.dp).widthIn(max = 340.dp),
            label = "statusDetail",
        ) { detail ->
            Text(
                detail,
                style = GdpiType.detail,
                color = detailColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ------------------------------------------------------------------ cipler

/**
 * Alttaki secim cipi: ustte kucuk etiket, altta secili deger. Dokununca ilgili secici
 * alt sayfasi acilir; deger degisince yeni ad hafifce yukselerek gelir.
 */
@Composable
private fun SelectionChip(label: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = GdpiTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .heightIn(min = 64.dp)
            .pressScale(interaction, 0.95f)
            .clip(CardShape)
            .background(c.surface)
            .border(1.dp, c.stroke, CardShape)
            .clickable(interactionSource = interaction, indication = ripple(), role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$label: $value" }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, style = GdpiType.chipLabel, color = c.muted, maxLines = 1)
        AnimatedContent(
            targetState = value,
            transitionSpec = rememberRefreshTransform(),
            modifier = Modifier.padding(top = 3.dp),
            label = "chip",
        ) { v ->
            Text(v, style = GdpiType.chipValue, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
