package io.github.unsalable.goodbyedpi.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.unsalable.goodbyedpi.ui.theme.GdpiTheme
import io.github.unsalable.goodbyedpi.ui.theme.GdpiType
import io.github.unsalable.goodbyedpi.ui.theme.Motion
import io.github.unsalable.goodbyedpi.ui.theme.rememberAccentGradient
import io.github.unsalable.goodbyedpi.ui.theme.rememberRefreshTransform
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown

// Masaustu Controls.xaml'deki ortak parcalarin (anahtar, hayalet dugme, giris kutusu, bolum
// basligi, secim kutusu) Compose karsiliklari. Hepsi ayni yay ve renk kaynaklarini kullanir.

/** Kart/kutu koseleri: masaustu 12 px; telefonda biraz daha yumusak. */
val CardShape = RoundedCornerShape(16.dp)
val FieldShape = RoundedCornerShape(12.dp)

/**
 * Basiliyken oge [scale]'e kuculur, birakinca yay ile esneyerek geri doner (masaustu
 * Motion.Pressed). Olcek cizim katmaninda uygulanir: yerlesim ve kompozisyon tetiklenmez.
 */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource, scale: Float = 0.96f): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(
        targetValue = if (pressed) scale else 1f,
        animationSpec = if (pressed) Motion.press() else Motion.release(),
        label = "press",
    )
    return graphicsLayer {
        scaleX = s
        scaleY = s
    }
}

/** Devre disi satirlar yumusakca soluklasir (masaustu OptionRow IsEnabled=False -> 0.4). */
@Composable
fun Modifier.enabledAlpha(enabled: Boolean): Modifier {
    val a by animateFloatAsState(if (enabled) 1f else 0.4f, Motion.soft(), label = "enabled")
    return graphicsLayer { alpha = a }
}

// ------------------------------------------------------------------ basliklar

/** Bolum etiketi ("INTERNET SAGLAYICI"); sagda istege bagli bir eylem ("+ Yeni DNS"). */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = GdpiTheme.colors.muted,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = GdpiType.section,
            color = color,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke(this)
    }
}

// ------------------------------------------------------------------- dugmeler

/** Metin dugmesi: zemin yok, basinca yay ile icine coker (masaustu GhostButton). */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = GdpiTheme.colors.accent,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = color),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = GdpiType.button,
            color = color,
            modifier = Modifier.pressScale(interaction, 0.94f),
            maxLines = 1,
        )
    }
}

/** Dolgulu vurgu dugmesi: vurgu gecisli hap (masaustu AccentButton). */
@Composable
fun AccentButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val gradient = rememberAccentGradient()
    val c = GdpiTheme.colors
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .pressScale(interaction, 0.95f)
            .enabledAlpha(enabled)
            .clip(RoundedCornerShape(24.dp))
            .background(gradient)
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = Color.White),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Koyu temada vurgu renkleri acik tonlu: beyaz yazi orada yeterli karsitlik vermiyor
        // (~2.9:1), koyu zemin rengi veriyor (~6.5:1).
        Text(text = text, style = GdpiType.button, color = if (c.isDark) c.bg else Color.White, maxLines = 1)
    }
}

/** Simge dugmesi: 48 dp dokunma alani, basinca simge 0.84'e coker (masaustu TitleBarButton). */
@Composable
fun GdpiIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = GdpiTheme.colors.muted,
    enabled: Boolean = true,
    iconSize: Dp = 22.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = true),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .enabledAlpha(enabled)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(iconSize).pressScale(interaction, 0.84f),
        )
    }
}

// -------------------------------------------------------------------- anahtar

/**
 * iOS tarzi anahtar (masaustu Switch stili): dugme yay ile esneyerek kayar, zemin rengi
 * solarak gecer. Yalnizca gorsel; tiklamayi tum satir (ToggleRow) alir ki dokunma alani
 * genis olsun.
 */
@Composable
fun GdpiSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val c = GdpiTheme.colors
    val track by animateColorAsState(if (checked) c.accent else c.track, Motion.soft(), label = "track")
    val shift by animateDpAsState(if (checked) 20.dp else 0.dp, Motion.release(), label = "thumb")
    Box(
        modifier = modifier
            .size(width = 48.dp, height = 28.dp)
            .clip(CircleShape)
            .background(track),
    ) {
        Box(
            Modifier
                .padding(4.dp)
                // Konum yerlesim evresinde okunur: kayarken yeniden kurulum olmaz.
                .offset { IntOffset(shift.roundToPx(), 0) }
                .size(20.dp)
                .shadow(2.dp, CircleShape)
                .background(Color.White, CircleShape),
        )
    }
}

/** Anahtarli ayar satiri: baslik + istege bagli aciklama solda, anahtar sagda. */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    enabled: Boolean = true,
    titleStyle: TextStyle = GdpiType.optionTitle,
    hintStyle: TextStyle = GdpiType.optionHint,
) {
    val c = GdpiTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .enabledAlpha(enabled)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 14.dp)) {
            Text(title, style = titleStyle, color = c.text)
            if (hint != null) Text(hint, style = hintStyle, color = c.muted, modifier = Modifier.padding(top = 2.dp))
        }
        GdpiSwitch(checked)
    }
}

// --------------------------------------------------------------------- sayac

/**
 * Sayi ayari (TTL, bolme konumu, tekrar sayisi): masaustundeki elle yazilan kutunun yerine
 * - / + dugmeleri; telefonda klavye acmadan ayarlanir, aralik disina da cikilamaz.
 * Yeni sayi, artiyorsa asagidan, azaliyorsa yukaridan kayarak gelir.
 */
@Composable
fun StepperRow(
    title: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    enabled: Boolean = true,
) {
    val c = GdpiTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .enabledAlpha(enabled)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = GdpiType.optionTitle, color = c.text)
            if (hint != null) Text(hint, style = GdpiType.optionHint, color = c.muted, modifier = Modifier.padding(top = 2.dp))
        }
        Row(
            modifier = Modifier
                .clip(FieldShape)
                .background(c.surfaceAlt)
                .semantics(mergeDescendants = false) { stateDescription = "$title: $value" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepButton("−", "$title azalt", enabled && value > range.first) { onValueChange((value - 1).coerceIn(range)) }
            AnimatedContent(
                targetState = value,
                transitionSpec = {
                    val up = targetState > initialState
                    (
                        slideInVertically(Motion.spring()) { h -> if (up) h / 2 else -h / 2 } + fadeIn(Motion.fadeIn())
                        ) togetherWith (
                        slideOutVertically(Motion.spring()) { h -> if (up) -h / 2 else h / 2 } + fadeOut(Motion.fadeOut())
                        )
                },
                contentAlignment = Alignment.Center,
                modifier = Modifier.widthIn(min = 36.dp),
                label = "stepper",
            ) { v ->
                Text(
                    text = v.toString(),
                    style = GdpiType.number,
                    color = c.text,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(min = 36.dp),
                )
            }
            StepButton("+", "$title artır", enabled && value < range.last) { onValueChange((value + 1).coerceIn(range)) }
        }
    }
}

@Composable
private fun StepButton(glyph: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val c = GdpiTheme.colors
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = true),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            style = GdpiType.number,
            color = if (enabled) c.accent else c.muted.copy(alpha = 0.5f),
            modifier = Modifier.pressScale(interaction, 0.8f),
        )
    }
}

// --------------------------------------------------------------- giris kutusu

/**
 * Tek satirlik giris kutusu (masaustu InputBox): yuzey zemini, odaklaninca kenar vurgu
 * rengine doner. Material OutlinedTextField'in yuzen etiketi burada gereksiz kalabalik.
 */
@Composable
fun GdpiTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    label: String? = null,
    isError: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    textAlign: TextAlign = TextAlign.Start,
    enabled: Boolean = true,
) {
    val c = GdpiTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val border by animateColorAsState(
        when {
            isError -> c.danger
            focused -> c.accent
            else -> c.stroke
        },
        Motion.soft(),
        label = "border",
    )
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = GdpiType.optionTitle.copy(color = c.text, textAlign = textAlign),
        cursorBrush = SolidColor(c.accent),
        interactionSource = interaction,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
        modifier = modifier
            .heightIn(min = 48.dp)
            .enabledAlpha(enabled)
            .semantics { if (label != null) contentDescription = label },
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(FieldShape)
                    .background(c.surfaceAlt)
                    .border(BorderStroke(1.dp, border), FieldShape)
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                contentAlignment = when (textAlign) {
                    TextAlign.Center -> Alignment.Center
                    else -> Alignment.CenterStart
                },
            ) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        placeholder,
                        style = GdpiType.optionTitle,
                        color = c.muted.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = textAlign,
                    )
                }
                inner()
            }
        },
    )
}

// ----------------------------------------------------------------- kutular

/** Yuzey kutusu (masaustu ozel ayar paneli): ince kenarlik, yumusak kose. */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    borderColor: Color = GdpiTheme.colors.stroke,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = GdpiTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(c.surface)
            .border(1.dp, borderColor, CardShape)
            .padding(contentPadding),
        content = content,
    )
}

/** Ince ayirici cizgi (masaustu GroupDivider). */
@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(GdpiTheme.colors.stroke),
    )
}

/**
 * Acilir liste gorunumlu secim kutusu (masaustu Dropdown): secili ad + asagi ok. Dokununca
 * cagiran taraf alt sayfayi acar.
 */
@Composable
fun SelectorField(
    value: String,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val c = GdpiTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .pressScale(interaction, 0.985f)
            .clip(FieldShape)
            .background(c.surface)
            .border(1.dp, c.stroke, FieldShape)
            .clickable(interactionSource = interaction, indication = ripple(), role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { this.contentDescription = contentDescription }
            .padding(start = 16.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(
            targetState = value,
            transitionSpec = rememberRefreshTransform(),
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart,
            label = "selector",
        ) { v ->
            Text(v, style = GdpiType.rowTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = c.muted,
        )
    }
}

/**
 * Yan yana secenekler (Tema: Sistem / Acik / Koyu). Secili zemin bir secenekten digerine
 * yay ile kayar; her secenek ayri bir radyo dugmesi olarak erisilebilir.
 */
@Composable
fun <T> SegmentedChoice(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = GdpiTheme.colors
    val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val position by animateFloatAsState(index.toFloat(), Motion.spring(), label = "segment")
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(FieldShape)
            .background(c.surfaceAlt)
            .padding(4.dp),
    ) {
        val segment = maxWidth / options.size
        Box(
            Modifier
                .fillMaxHeight()
                .width(segment)
                .graphicsLayer { translationX = position * segment.toPx() }
                .shadow(1.dp, RoundedCornerShape(9.dp))
                .background(if (c.isDark) c.stroke else Color.White, RoundedCornerShape(9.dp)),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            options.forEachIndexed { i, (value, label) ->
                val isSelected = i == index
                val textColor by animateColorAsState(if (isSelected) c.text else c.muted, Motion.soft(), label = "segText")
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(9.dp))
                        .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(value) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = GdpiType.button, color = textColor, maxLines = 1)
                }
            }
        }
    }
}

/** Kucuk aralik; Spacer(Modifier.height(x)) yazmaktan kisa. */
@Composable
fun VSpace(height: Dp) = Spacer(Modifier.height(height))

/** Vurgu gecisli kucuk nokta (masaustu baslik cubugundaki logo noktasi). */
@Composable
fun GradientDot(size: Dp, brush: Brush, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(brush))
}
