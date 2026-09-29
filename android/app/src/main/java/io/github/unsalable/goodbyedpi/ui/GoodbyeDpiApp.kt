package io.github.unsalable.goodbyedpi.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.unsalable.goodbyedpi.ui.theme.AppNightMode
import io.github.unsalable.goodbyedpi.ui.theme.GdpiColors
import io.github.unsalable.goodbyedpi.ui.theme.GdpiTheme
import io.github.unsalable.goodbyedpi.ui.theme.GoodbyeDpiTheme
import io.github.unsalable.goodbyedpi.ui.theme.Motion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterNotNull

/** Iki ekran: gezinme kutuphanesine gerek yok, durum tek bir kaydedilebilir deger. */
enum class Screen { Main, Settings }

/**
 * Uygulamanin koku: tema, ekranlar arasi gecis, geri hareketi ve alt mesajlar.
 *
 * Ayarlar ekrani yandan kayarak gelir (ortak eksenli gecis). Android 14+ ongorulu geri
 * hareketinde ekran parmagi izleyerek kuculur ve kayar; hareket iptal edilirse yerine
 * doner, tamamlanirsa ayni gecisle ana ekrana donulur.
 */
@Composable
fun GoodbyeDpiApp(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()

    GoodbyeDpiTheme(settings.themeMode) {
        val c = GdpiTheme.colors
        var screen by rememberSaveable { mutableStateOf(Screen.Main) }
        var backProgress by remember { mutableFloatStateOf(0f) }
        val snackbar = remember { SnackbarHostState() }
        val context = LocalContext.current

        PredictiveBackHandler(enabled = screen == Screen.Settings) { progress ->
            try {
                progress.collect { backProgress = it.progress }
                screen = Screen.Main
            } catch (e: CancellationException) {
                // Hareket iptal: ekran yay ile yerine doner (asagidaki animasyon degil, deger sifirlanir).
                backProgress = 0f
                throw e
            }
        }

        LaunchedEffect(vm) {
            vm.messages.collect { m ->
                // Eylemli mesaj ("Geri al") biraz daha uzun kalir; kullanici dugmeye yetissin.
                val result = snackbar.showSnackbar(
                    message = m.text,
                    actionLabel = m.actionLabel,
                    duration = if (m.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) m.onAction?.invoke()
            }
        }

        // Uygulama ici tema secimi sisteme de bildirilir: acilis ekrani ve pencere zemini
        // soguk acilista da secilen paletle gelsin (bkz. AppNightMode).
        LaunchedEffect(settings.themeMode) { AppNightMode.apply(context, settings.themeMode) }

        // Guc dugmesinin tek seferlik efektlerinin en son oynatildigi durum. Ana ekran Ayarlar
        // acikken kompozisyondan cikiyor; burada (ve donmeye dayanikli) tutulmasa donuste
        // "yeni baglandi" cokmesi ve nabiz ya da hata sallantisi yeniden oynardi.
        val seenPhase = rememberSaveable { mutableStateOf<PowerPhase?>(null) }

        // Guncellemeden sonraki ilk acilis: once tuket, sonra goster ki ekran donunce tekrarlanmasin.
        // Anahtarsiz etki: degere anahtarlanan LaunchedEffect tuketince (deger null olunca) kendini
        // iptal ediyor ve showSnackbar hemen kapaniyordu; mesaj hic gorunmuyordu.
        LaunchedEffect(vm) {
            vm.justUpdatedTo.filterNotNull().collect { version ->
                vm.consumeJustUpdated()
                snackbar.showSnackbar("Güncellendi: sürüm $version")
            }
        }

        Box(Modifier.fillMaxSize().background(c.bg)) {
            val density = LocalDensity.current
            AnimatedContent(
                targetState = screen,
                transitionSpec = { sharedAxis(forward = targetState == Screen.Settings, distance = with(density) { 48.dp.roundToPx() }) },
                label = "screen",
            ) { target ->
                when (target) {
                    Screen.Main -> {
                        val connection by vm.connection.collectAsStateWithLifecycle()
                        val update by vm.updateState.collectAsStateWithLifecycle()
                        MainScreen(
                            settings = settings,
                            connection = connection,
                            updateState = update,
                            traffic = vm.traffic,
                            isDark = c.isDark,
                            onPower = vm::onPowerClick,
                            onToggleTheme = { vm.toggleTheme(c.isDark) },
                            onOpenSettings = {
                                backProgress = 0f
                                screen = Screen.Settings
                            },
                            onSelectIsp = vm::selectIsp,
                            onSelectMethod = vm::selectMethod,
                            onSelectDns = vm::selectDns,
                            onUpdate = vm::startUpdate,
                            onUpdateLater = vm::dismissUpdate,
                            onOpenInstallPermission = { SystemIntents.openUnknownSources(context) },
                            // Yatay ekranda yan gezinme cubugu ve kamera kesigi de: icerik altlarinda kalmasin.
                            modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
                            seenPhase = seenPhase,
                        )
                    }
                    Screen.Settings -> SettingsScreen(
                        vm = vm,
                        settings = settings,
                        onBack = { screen = Screen.Main },
                        modifier = Modifier
                            // Yatayda yan gezinme cubugu/kamera kesigi; alt kenar listenin kendi
                            // icerik boslugunda (liste cubugun altina kadar kayabilsin). Ongorulu
                            // geri olcegi bu bosluklardan sonra uygulanir.
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                            .statusBarsPadding()
                            .graphicsLayer {
                                // Ongorulu geri: hafifce kuculur, saga kayar, koseler yuvarlanir.
                                val p = backProgress
                                val s = 1f - 0.1f * p
                                scaleX = s
                                scaleY = s
                                translationX = p * 24.dp.toPx()
                                shape = RoundedCornerShape((28f * p).dp)
                                clip = p > 0f
                            },
                    )
                }
            }

            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
            ) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = c.text,
                    contentColor = c.bg,
                    // Ters zeminde (koyu uzerine acik ya da tersi) vurgu rengi okunur kalsin.
                    actionColor = if (c.isDark) GdpiColors.Light.accentText else GdpiColors.Dark.accent,
                    shape = RoundedCornerShape(14.dp),
                )
            }
        }
    }
}

/** Ortak eksenli yatay gecis: yeni ekran yandan az bir mesafe kayarak ve solarak gelir. */
private fun AnimatedContentTransitionScope<Screen>.sharedAxis(forward: Boolean, distance: Int): ContentTransform {
    val sign = if (forward) 1 else -1
    return (
        slideInHorizontally(Motion.spring<IntOffset>()) { sign * distance } + fadeIn(Motion.fadeIn())
        ) togetherWith (
        slideOutHorizontally(Motion.spring<IntOffset>()) { -sign * distance } + fadeOut(Motion.fadeOut())
        )
}
