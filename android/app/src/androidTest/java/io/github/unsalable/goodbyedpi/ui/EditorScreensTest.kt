package io.github.unsalable.goodbyedpi.ui

import android.Manifest
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import io.github.unsalable.goodbyedpi.MainActivity
import io.github.unsalable.goodbyedpi.model.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Duzenleyicilerin gorunumu (ozel profil, ozel DNS + uyari, silme onayi) iki temada.
 * Ekran goruntuleri gozle denetim icin; akislarin dogrulugu SettingsFlowsTest'te.
 */
@RunWith(AndroidJUnit4::class)
class EditorScreensTest {

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(FreshSettingsRule())
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(compose)

    private fun capture(theme: ThemeMode, tag: String) {
        runBlocking { repo.update { it.copy(themeMode = theme) } }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Ayarlar").performClick()

        compose.onNodeWithContentDescription("Yeni özel ayar").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Profil adı").performScrollTo()
        screenshot("editor_profile_$tag")

        // Bolme grubunu katla: ok doner, icerik kapanir.
        compose.onNodeWithText("BÖLME").performScrollTo().performClick()
        compose.waitForIdle()
        screenshot("editor_collapsed_$tag")

        compose.onNode(hasScrollToKeyAction()).performScrollToKey("dns")
        compose.onNodeWithContentDescription("Yeni DNS").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("IPv4 adresi").performScrollTo().performTextReplacement("1.2.3")
        compose.onNodeWithContentDescription("IPv6 adresi").performScrollTo()
        compose.waitForIdle()
        screenshot("editor_dns_invalid_$tag")

        compose.onNodeWithContentDescription("DNS girişini sil").performClick()
        compose.waitForIdle()
        screenshot("editor_delete_dialog_$tag")
        compose.onNodeWithText("Vazgeç").performClick()
    }

    @Test
    fun editors_light() = capture(ThemeMode.LIGHT, "light")

    @Test
    fun editors_dark() = capture(ThemeMode.DARK, "dark")
}
