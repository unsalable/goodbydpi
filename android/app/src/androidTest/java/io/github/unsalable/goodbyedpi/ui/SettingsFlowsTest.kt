package io.github.unsalable.goodbyedpi.ui

import android.Manifest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import io.github.unsalable.goodbyedpi.MainActivity
import io.github.unsalable.goodbyedpi.data.SettingsRepository
import io.github.unsalable.goodbyedpi.model.CustomIds
import io.github.unsalable.goodbyedpi.model.ThemeMode
import io.github.unsalable.goodbyedpi.model.selectedMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

/**
 * SPEC 7 arayuz akislari: gercek aktivite, gercek SettingsRepository (her testten once
 * varsayilana cekilir). VPN baslatilmaz; guc dugmesine dokunulmuyor.
 */
@RunWith(AndroidJUnit4::class)
class SettingsFlowsTest {

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

    private fun openSettings() {
        compose.onNodeWithContentDescription("Ayarlar").performClick()
        compose.onNodeWithText("İNTERNET SAĞLAYICI").assertIsDisplayed()
    }

    private fun scrollTo(key: String) {
        compose.onNode(hasScrollToKeyAction()).performScrollToKey(key)
    }

    @Test
    fun selectingIsp_changesMethodList() {
        openSettings()
        // Genel yontem listesinde Android'e ozel TLS kayit bolme var, TTL 4 yok.
        compose.onNodeWithContentDescription("Yöntem: Varsayılan").performClick()
        compose.onNodeWithText("TLS kayıt bölme").assertExists()
        compose.onNodeWithText("Sahte TTL 4").assertDoesNotExist()
        compose.onNode(hasText("Varsayılan") and isSelectable()).performClick()
        // Secimden sonra alt sayfa kisa bir gecikmeyle kendiliginden kapanir.
        compose.waitFor("secici kapandi") { compose.onAllNodesWithText("TLS kayıt bölme").fetchSemanticsNodes().isEmpty() }

        compose.onNodeWithContentDescription("İnternet sağlayıcı: Genel").performClick()
        compose.onNodeWithText("Türk Telekom").performClick()
        compose.waitFor("isp=turktelekom") { repo.current.isp == "turktelekom" }
        compose.waitForIdle()

        // Onerilen yontem ve DNS birlikte uygulanir.
        assertEquals("disorder", repo.current.method)
        assertEquals("yandex", repo.current.dns)
        compose.onNodeWithContentDescription("Yöntem: Ters sıra").assertIsDisplayed()

        compose.onNodeWithContentDescription("Yöntem: Ters sıra").performClick()
        compose.onNodeWithText("Sahte TTL 4").assertExists()
        compose.onNodeWithText("Sahte TTL 3").assertExists()
        compose.onNodeWithText("TLS kayıt bölme").assertDoesNotExist()
        compose.onNodeWithText("Sahte TTL 4").performClick()
        compose.waitFor("method=ttl4") { repo.current.method == "ttl4" }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Yöntem: Sahte TTL 4").assertIsDisplayed()
    }

    @Test
    fun customProfile_createRenameDuplicateDelete() {
        openSettings()

        // Olustur: secili hazir yontemden "(ozel)" ekiyle.
        compose.onNodeWithContentDescription("Yeni özel ayar").performClick()
        compose.waitFor("ozel profil secili") { CustomIds.isCustom(repo.current.method) }
        compose.waitForIdle()
        assertEquals("Varsayılan (özel)", repo.current.selectedMethod().name)
        compose.onNodeWithContentDescription("Yöntem: Varsayılan (özel)").assertIsDisplayed()

        // Yeniden adlandir.
        compose.onNodeWithContentDescription("Profil adı").performScrollTo().performTextReplacement("Oyun")
        compose.waitFor("ad=Oyun") { repo.current.selectedMethod().name == "Oyun" }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Yöntem: Oyun").assertExists()

        // Cogalt: "Oyun 2" olusur ve secilir.
        compose.onNodeWithContentDescription("Profili çoğalt").performClick()
        compose.waitFor("Oyun 2 secili") { repo.current.selectedMethod().name == "Oyun 2" }
        compose.waitForIdle()
        val names = repo.current.customProfiles.map { it.name }
        assertTrue("Oyun" in names && "Oyun 2" in names)
        compose.onNodeWithContentDescription("Yöntem: Oyun 2").assertExists()

        // Sil (onayla): profil gider, yontem saglayicinin onerisine doner.
        compose.onNodeWithContentDescription("Profili sil").performScrollTo().performClick()
        compose.onNodeWithText("Sil").performClick()
        compose.waitFor("Oyun 2 silindi") { repo.current.customProfiles.none { it.name == "Oyun 2" } }
        compose.waitForIdle()
        assertEquals("default", repo.current.method)
        assertTrue(repo.current.customProfiles.any { it.name == "Oyun" })
        compose.onNodeWithContentDescription("Yöntem: Varsayılan").assertExists()
        compose.onNodeWithContentDescription("Profil adı").assertDoesNotExist()
    }

    @Test
    fun customDns_showsValidationMessages() {
        openSettings()
        scrollTo("dns")
        compose.onNodeWithContentDescription("Yeni DNS").performClick()
        compose.waitFor("ozel DNS secili") { CustomIds.isCustom(repo.current.dns) }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("IPv4 adresi").performScrollTo().performTextReplacement("1.2.3")
        compose.onNodeWithText("Geçersiz IPv4 adresi.").assertExists()

        compose.onNodeWithContentDescription("IPv4 adresi").performTextReplacement("1.2.3.4")
        compose.waitForIdle()
        compose.onNodeWithText("Geçersiz IPv4 adresi.").assertDoesNotExist()
        compose.waitFor("v4 kaydedildi") { repo.current.customDns.any { it.v4 == "1.2.3.4" } }

        compose.onNodeWithContentDescription("IPv4 adresi portu").performTextReplacement("70000")
        compose.onNodeWithText("Port 0-65535 aralığında olmalı.").assertExists()
        // Gecersiz port kaydedilmez.
        assertTrue(repo.current.customDns.none { it.v4Port == 70000 })

        compose.onNodeWithContentDescription("IPv4 adresi portu").performTextReplacement("1253")
        compose.waitFor("port kaydedildi") { repo.current.customDns.any { it.v4 == "1.2.3.4" && it.v4Port == 1253 } }
        compose.onNodeWithText("Port 0-65535 aralığında olmalı.").assertDoesNotExist()

        compose.onNodeWithContentDescription("IPv6 adresi").performTextReplacement("2001:db8::zz")
        compose.onNodeWithText("Geçersiz IPv6 adresi.").assertExists()
    }

    @Test
    fun themeToggle_flipsAndPersists() {
        val toggle = compose.onNodeWithContentDescription("Temayı değiştir")
        val wasDark = toggle.fetchSemanticsNode().config[SemanticsProperties.StateDescription] == "Koyu tema"

        toggle.performClick()
        val expected = if (wasDark) ThemeMode.LIGHT else ThemeMode.DARK
        compose.waitFor("tema=$expected") { repo.current.themeMode == expected }
        compose.waitForIdle()
        compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, if (wasDark) "Açık tema" else "Koyu tema"),
        ).assertExists()

        toggle.performClick()
        compose.waitFor("tema geri dondu") { repo.current.themeMode != expected }

        // Ayarlar'daki uc secenekli secim: Sistem.
        openSettings()
        scrollTo("general")
        compose.onNodeWithText("Sistem").performClick()
        compose.waitFor("tema=SYSTEM") { repo.current.themeMode == ThemeMode.SYSTEM }
    }

    @Test
    fun settingsSurviveActivityRecreation() {
        // Ana ekran cipi uzerinden saglayici sec.
        compose.onNodeWithContentDescription("SAĞLAYICI: Genel").performClick()
        compose.onNodeWithText("Superonline").performClick()
        compose.waitFor("isp=superonline") { repo.current.isp == "superonline" }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Temayı değiştir").performClick()
        compose.waitFor("tema acik secim") { repo.current.themeMode != ThemeMode.SYSTEM }
        val theme = repo.current.themeMode

        // Ayarlar ekrani acikken yeniden kur: ekran da korunmali.
        openSettings()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithText("İNTERNET SAĞLAYICI").assertIsDisplayed()
        compose.onNodeWithContentDescription("İnternet sağlayıcı: Superonline").assertIsDisplayed()

        // Geri: ana ekran, secimler ayni.
        Espresso.pressBack()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("SAĞLAYICI: Superonline").assertIsDisplayed()
        compose.onNodeWithContentDescription("YÖNTEM: Ters sıra").assertIsDisplayed()

        // Diskteki dosya da ayni: yeni bir depo ornegi dosyadan okur.
        val file = File(targetContext.filesDir, "settings.json")
        compose.waitFor("dosyaya yazildi") {
            runCatching { SettingsRepository(file).current }.getOrNull()?.let {
                it.isp == "superonline" && it.themeMode == theme
            } == true
        }
        assertFalse(repo.current.autoUpdate)
    }
}
