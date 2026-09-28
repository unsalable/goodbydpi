package io.github.unsalable.goodbyedpi.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.unsalable.goodbyedpi.BuildConfig
import io.github.unsalable.goodbyedpi.update.UpdateState
import io.github.unsalable.goodbyedpi.ui.theme.GdpiTheme
import io.github.unsalable.goodbyedpi.ui.theme.GdpiType
import io.github.unsalable.goodbyedpi.ui.theme.Motion
import io.github.unsalable.goodbyedpi.ui.theme.rememberAccentGradient
import io.github.unsalable.goodbyedpi.ui.theme.rememberRefreshTransform
import java.util.Locale

/** Seridin gosterilecegi durumlar; digerlerinde (bosta, denetleniyor, guncel) gizli. */
internal fun UpdateState.showsBanner(): Boolean = when (this) {
    is UpdateState.Available,
    is UpdateState.Downloading,
    is UpdateState.Verifying,
    is UpdateState.Installing,
    is UpdateState.NeedsPermission,
    -> true
    // Surum bilgisi olmayan hata arka plan denetiminin hatasi: ana ekranda gurultu olmasin,
    // Ayarlar > Hakkinda'da gorunur.
    is UpdateState.Failed -> info != null
    else -> false
}

/**
 * Guncelleme seridi (masaustu guncelleme ekraninin sade hali). Durumlar arasi gecis
 * RefreshText gibi: metin hafifce yukselerek degisir, serit yay ile acilip kapanir.
 */
@Composable
fun UpdateBanner(
    state: UpdateState,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
    onOpenPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = state.showsBanner()
    // Kapanirken son gorunen icerik kalsin; Idle'a donunce serit bos kutu olarak solmasin.
    val shown = remember { Holder<UpdateState>(state) }
    if (visible) shown.value = state

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(Motion.fadeIn()) + expandVertically(Motion.spring<IntSize>()),
        exit = fadeOut(Motion.fadeOut()) + shrinkVertically(Motion.spring<IntSize>()),
        label = "updateBanner",
    ) {
        BannerContent(shown.value, onUpdate, onLater, onOpenPermission)
    }
}

private class Holder<T>(var value: T)

@Composable
private fun BannerContent(
    state: UpdateState,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
    onOpenPermission: () -> Unit,
) {
    val c = GdpiTheme.colors
    val failed = state is UpdateState.Failed
    SurfaceCard(
        borderColor = if (failed) c.danger else c.accent,
        contentPadding = PaddingValues(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 6.dp),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = if (failed) Icons.Filled.Warning else Icons.Filled.Refresh,
                contentDescription = null,
                tint = if (failed) c.danger else c.accent,
                modifier = Modifier.padding(top = 2.dp).size(20.dp),
            )
            AnimatedContent(
                targetState = texts(state),
                transitionSpec = rememberRefreshTransform(),
                modifier = Modifier.weight(1f).padding(start = 12.dp, end = 6.dp),
                label = "updateText",
            ) { (title, detail) ->
                Column {
                    Text(title, style = GdpiType.optionTitle, color = c.text)
                    if (detail.isNotEmpty()) {
                        Text(detail, style = GdpiType.optionHint, color = if (failed) c.danger else c.muted, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }

        if (state is UpdateState.Downloading) {
            val fraction = if (state.totalBytes > 0) (state.doneBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f) else 0f
            ProgressBar(fraction, Modifier.padding(start = 32.dp, end = 8.dp, top = 10.dp, bottom = 8.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (state) {
                is UpdateState.Available -> {
                    GhostButton("Daha sonra", onLater, color = c.muted)
                    GhostButton("Güncelle", onUpdate)
                }
                is UpdateState.NeedsPermission -> {
                    GhostButton("Devam et", onUpdate, color = c.muted)
                    GhostButton("İzin ver", onOpenPermission)
                }
                is UpdateState.Failed -> {
                    GhostButton("Kapat", onLater, color = c.muted)
                    GhostButton("Tekrar dene", onUpdate)
                }
                // Indirme/dogrulama/kurulum sirasinda dugme yok: islem kendiliginden ilerliyor.
                else -> Box(Modifier.height(6.dp))
            }
        }
    }
}

/** Ilerleme cubugu (masaustu ProgressTrack): dolgu soldan saga yay ile buyur. */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    val c = GdpiTheme.colors
    val gradient = rememberAccentGradient()
    val f by animateFloatAsState(fraction, Motion.soft(), label = "progress")
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .drawBehind {
                val r = CornerRadius(size.height / 2f)
                drawRoundRect(c.track, cornerRadius = r)
                if (f > 0f) drawRoundRect(gradient, size = Size(size.width * f, size.height), cornerRadius = r)
            },
    )
}

private fun mb(bytes: Long): String = String.format(Locale.forLanguageTag("tr"), "%.1f", bytes / 1_048_576.0)

private fun texts(state: UpdateState): Pair<String, String> = when (state) {
    is UpdateState.Available -> "Yeni sürüm var: ${state.info.version}" to
        "${BuildConfig.VERSION_NAME} → ${state.info.version}" +
        (if (state.info.sizeBytes > 0) " · ${mb(state.info.sizeBytes)} MB" else "")
    is UpdateState.Downloading -> {
        val pct = if (state.totalBytes > 0) (state.doneBytes * 100 / state.totalBytes).toInt() else 0
        val detail = if (state.totalBytes > 0) {
            "${mb(state.doneBytes)} / ${mb(state.totalBytes)} MB"
        } else {
            "${mb(state.doneBytes)} MB"
        }
        "İndiriliyor… %$pct" to detail
    }
    is UpdateState.Verifying -> "Dosya doğrulanıyor…" to "İnen dosyanın GitHub'daki sürümle aynı olduğu kontrol ediliyor."
    is UpdateState.Installing -> "Güncelleme kuruluyor…" to "Android kurulum onayı isteyebilir."
    is UpdateState.NeedsPermission -> "Kurulum izni gerekli" to
        "Ayarlar'da GoodbyeDPI için \"Bilinmeyen uygulamaları yükle\" iznini aç, sonra \"Devam et\"e dokun."
    is UpdateState.Failed -> "Güncelleme tamamlanamadı" to state.message
    else -> "" to ""
}
