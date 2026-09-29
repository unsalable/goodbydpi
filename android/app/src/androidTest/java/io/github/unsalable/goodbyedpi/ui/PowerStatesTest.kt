package io.github.unsalable.goodbyedpi.ui

import android.Manifest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import io.github.unsalable.goodbyedpi.MainActivity
import io.github.unsalable.goodbyedpi.model.ThemeMode
import io.github.unsalable.goodbyedpi.service.EngineState
import io.github.unsalable.goodbyedpi.service.EngineStateHolder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Guc dugmesinin dort durumu: motor durumu dogrudan EngineStateHolder'a yazilir (servis
 * baslatilmaz, VPN kurulmaz). Her durumda baslik, ayrinti ve dugmenin erisilebilirlik
 * metinleri denetlenir; iki temada ekran goruntusu alinir.
 */
@RunWith(AndroidJUnit4::class)
class PowerStatesTest {

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(FreshSettingsRule())
        .around(
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                GrantPermissionRule.grant()
            },
        )
        .around(compose)

    private fun state(description: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description)

    private fun showAll(theme: ThemeMode, tag: String) {
        compose.runOnIdle { kotlinx.coroutines.runBlocking { repo.update { it.copy(themeMode = theme) } } }
        compose.waitForIdle()

        compose.onNodeWithText("Kapalı").assertIsDisplayed()
        compose.onNodeWithContentDescription("Bağlan").assert(state("Kapalı"))
        screenshot("state_off_$tag")

        // Sonsuz animasyonlar (donen yay, nabiz) varken Compose hic "bos" olmaz: saati elle ilerlet.
        compose.mainClock.autoAdvance = false
        try {
            EngineStateHolder.set(EngineState.Starting)
            compose.mainClock.advanceTimeBy(700)
            compose.onNodeWithText("Bağlanıyor…").assertExists()
            compose.onNodeWithContentDescription("Bağlantıyı kes").assert(state("Bağlanıyor"))
            screenshot("state_connecting_$tag")

            EngineStateHolder.set(EngineState.Running(0L, "Ters sıra", "Yandex (1253)", 1080))
            compose.mainClock.advanceTimeBy(1400)
            compose.onNodeWithText("Bağlı").assertExists()
            compose.onNodeWithText("Ters sıra · DNS: Yandex (1253)").assertExists()
            compose.onNodeWithContentDescription("Bağlantıyı kes").assert(state("Bağlı"))
            compose.onNodeWithText("Gönderilen").assertExists()
            screenshot("state_connected_$tag")

            EngineStateHolder.set(EngineState.Failed("byedpi başlatılamadı: port kullanımda"))
            compose.mainClock.advanceTimeBy(900)
            compose.onNodeWithText("Bağlantı kurulamadı").assertExists()
            compose.onNodeWithText("byedpi başlatılamadı: port kullanımda").assertExists()
            compose.onNodeWithContentDescription("Bağlan").assert(state("Bağlantı kurulamadı"))
            screenshot("state_failed_$tag")

            // Kapanirken dugme "Baglaniyor" diye okunmaz ve dokunusa kapali.
            EngineStateHolder.set(EngineState.Stopping)
            compose.mainClock.advanceTimeBy(700)
            compose.onNodeWithText("Durduruluyor…").assertExists()
            compose.onNodeWithContentDescription("Bağlantıyı kes").assert(state("Durduruluyor")).assertIsNotEnabled()

            EngineStateHolder.set(EngineState.Stopped)
            compose.mainClock.advanceTimeBy(900)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.onNodeWithText("Kapalı").assertExists()
    }

    @Test
    fun powerButtonStates_light() = showAll(ThemeMode.LIGHT, "light")

    @Test
    fun powerButtonStates_dark() = showAll(ThemeMode.DARK, "dark")
}
