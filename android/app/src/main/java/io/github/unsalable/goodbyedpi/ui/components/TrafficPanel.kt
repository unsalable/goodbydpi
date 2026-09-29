package io.github.unsalable.goodbyedpi.ui.components

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.unsalable.goodbyedpi.service.TrafficStats
import io.github.unsalable.goodbyedpi.ui.theme.GdpiTheme
import io.github.unsalable.goodbyedpi.ui.theme.GdpiType
import io.github.unsalable.goodbyedpi.ui.theme.rememberRefreshTransform
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale

@Immutable
internal data class TrafficUi(val upPerSec: Long, val downPerSec: Long, val upTotal: Long, val downTotal: Long) {
    companion object {
        val Zero = TrafficUi(0, 0, 0, 0)
    }
}

/**
 * Canli hiz ve toplamlar. Trafik akisi YALNIZCA burada toplanir: saniyede bir gelen deger
 * ana ekranin geri kalanini yeniden kurdurmasin. Akis ekran gorunurken (STARTED) dinlenir;
 * servis de ornekleme yapmayi ancak biri dinlerken surduruyor, yani arka planda bedava.
 *
 * Hiz iki ornek arasindaki farktan hesaplanir. Trafik durunca akis ayni degeri tekrar
 * yaymaz (StateFlow esit degerleri yutar); bu yuzden hiz ayri bir saniyelik tikla, son
 * gorulen degerden hesaplanir ki "0 B/s"e dusebilsin.
 */
@Composable
fun TrafficPanel(traffic: StateFlow<TrafficStats>, modifier: Modifier = Modifier) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var ui by remember { mutableStateOf(TrafficUi.Zero) }

    LaunchedEffect(traffic, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var latest = traffic.value
            launch { traffic.collect { latest = it } }

            var prev = latest
            var prevAt = SystemClock.elapsedRealtime()
            ui = TrafficUi(0, 0, latest.txBytes, latest.rxBytes)
            while (true) {
                delay(1000)
                val now = SystemClock.elapsedRealtime()
                val cur = latest
                val seconds = (now - prevAt).coerceAtLeast(1) / 1000.0
                // Sayac sifirlanirsa (motor yeniden basladi) negatif hiz gostermeyelim.
                val up = ((cur.txBytes - prev.txBytes).coerceAtLeast(0) / seconds).toLong()
                val down = ((cur.rxBytes - prev.rxBytes).coerceAtLeast(0) / seconds).toLong()
                ui = TrafficUi(up, down, cur.txBytes, cur.rxBytes)
                prev = cur
                prevAt = now
            }
        }
    }

    val c = GdpiTheme.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TrafficCell("↑", "Gönderilen", formatRate(ui.upPerSec), formatBytes(ui.upTotal), c.accent, Modifier.weight(1f))
        TrafficCell("↓", "Alınan", formatRate(ui.downPerSec), formatBytes(ui.downTotal), c.successText, Modifier.weight(1f))
    }
}

@Composable
private fun TrafficCell(
    arrow: String,
    label: String,
    rate: String,
    total: String,
    tint: Color,
    modifier: Modifier,
) {
    val c = GdpiTheme.colors
    SurfaceCard(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "$label: saniyede $rate, toplam $total"
        },
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(arrow, style = GdpiType.number, color = tint)
            Text(label, style = GdpiType.chipLabel, color = c.muted, modifier = Modifier.padding(start = 6.dp))
        }
        Column(Modifier.padding(top = 4.dp)) {
            AnimatedContent(targetState = rate, transitionSpec = rememberRefreshTransform(), label = "rate") {
                Text(it, style = GdpiType.number, color = c.text, maxLines = 1)
            }
            AnimatedContent(targetState = total, transitionSpec = rememberRefreshTransform(), label = "total") {
                Text("Toplam $it", style = GdpiType.optionHint, color = c.muted, maxLines = 1)
            }
        }
    }
}

private val tr = Locale.forLanguageTag("tr")

/** 1024 tabanli, Turkce ondalik virgulle: "0 B", "12,3 KB", "1,20 MB". */
internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(tr, if (kb < 100) "%.1f KB" else "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(tr, if (mb < 100) "%.1f MB" else "%.0f MB", mb)
    return String.format(tr, "%.2f GB", mb / 1024.0)
}

internal fun formatRate(bytesPerSec: Long): String = formatBytes(bytesPerSec) + "/s"
