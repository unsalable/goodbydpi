package io.github.unsalable.goodbyedpi.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.unsalable.goodbyedpi.model.ThemeMode
import io.github.unsalable.goodbyedpi.ui.components.UpdateBanner
import io.github.unsalable.goodbyedpi.ui.theme.GoodbyeDpiTheme
import io.github.unsalable.goodbyedpi.update.ReleaseInfo
import io.github.unsalable.goodbyedpi.update.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Guncelleme seridinin her durumu (UpdateManager bu dalda taslak; durumlar elle verilir). */
@RunWith(AndroidJUnit4::class)
class UpdateBannerTest {

    @get:Rule
    val compose = createComposeRule()

    private val info = ReleaseInfo(
        version = "1.2.0",
        tag = "v1.2.0",
        assetName = "GoodbyeDPI-Android.apk",
        downloadUrl = "https://example.invalid/a.apk",
        sizeBytes = 8L * 1024 * 1024,
        sha256 = null,
        notes = "",
        htmlUrl = "",
    )

    @Test
    fun everyStateShowsItsTextAndActions() {
        var state by mutableStateOf<UpdateState>(UpdateState.Idle)
        var updates = 0
        var later = 0
        var permission = 0
        compose.setContent {
            GoodbyeDpiTheme(ThemeMode.LIGHT) {
                Column(Modifier.padding(16.dp)) {
                    UpdateBanner(state, { updates++ }, { later++ }, { permission++ })
                }
            }
        }

        compose.onNodeWithText("Güncelle").assertDoesNotExist()

        state = UpdateState.Available(info)
        compose.onNodeWithText("Yeni sürüm var: 1.2.0").assertExists()
        compose.onNodeWithText("Güncelle").performClick()
        compose.onNodeWithText("Daha sonra").performClick()
        assertEquals(1, updates)
        assertEquals(1, later)

        state = UpdateState.Downloading(info, 4L * 1024 * 1024, 8L * 1024 * 1024)
        compose.onNodeWithText("İndiriliyor… %50").assertExists()
        compose.onNodeWithText("4,0 / 8,0 MB").assertExists()
        screenshot("update_downloading_light")
        // Indirme (otomatik guncellemede istenmeden de baslar) seritten iptal edilebilir.
        compose.onNodeWithText("Vazgeç").performClick()
        assertEquals(2, later)

        state = UpdateState.Verifying(info)
        compose.onNodeWithText("Dosya doğrulanıyor…").assertExists()
        // Dogrulamada iptal yok (birkac saniye surer).
        compose.onNodeWithText("Vazgeç").assertDoesNotExist()

        state = UpdateState.Installing(info)
        compose.onNodeWithText("Güncelleme kuruluyor…").assertExists()
        // Arka planda kacirilan kurulum onayi seritten yeniden acilabilir.
        compose.onNodeWithText("Onayla").performClick()
        assertEquals(2, updates)

        state = UpdateState.NeedsPermission(info)
        compose.onNodeWithText("İzin ver").performClick()
        assertEquals(1, permission)

        state = UpdateState.Failed(info, "İndirme tamamlanamadı.")
        compose.onNodeWithText("İndirme tamamlanamadı.").assertExists()
        compose.onNodeWithText("Tekrar dene").performClick()
        assertEquals(3, updates)

        // Surum bilgisi olmayan hata (arka plan denetimi) ana ekranda gosterilmez.
        state = UpdateState.Failed(null, "GitHub'a ulaşılamadı.")
        compose.waitForIdle()
        compose.onNodeWithText("GitHub'a ulaşılamadı.").assertDoesNotExist()
    }
}
