package io.github.unsalable.goodbyedpi.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.unsalable.goodbyedpi.data.SettingsRepository
import io.github.unsalable.goodbyedpi.model.AppSettings
import io.github.unsalable.goodbyedpi.service.EngineState
import io.github.unsalable.goodbyedpi.service.EngineStateHolder
import io.github.unsalable.goodbyedpi.service.QuickTileState
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource
import java.io.File

internal val targetContext: Context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

internal val repo: SettingsRepository
    get() = SettingsRepository.get(targetContext)

/**
 * Her testten ONCE (aktivite acilmadan) ayarlari varsayilana cevirir. Surecteki tek
 * SettingsRepository ornegi de sifirlanir, yani testler birbirinin secimlerini gormez.
 * Otomatik guncelleme kapali: ag erisimi olan bir denetim testte serit cikarmasin.
 * Bildirim izni "soruldu" sayilir: sistem izin penceresi testin onune gecmesin.
 * Hizli ayarlar karosu da "soruldu" sayilir: ilk Running'den 1,5 sn sonra acilan sistem
 * penceresi (1.0.1) odagi aliyor, sonraki testler pencere odagi bekleyip dusuyordu.
 */
class FreshSettingsRule : ExternalResource() {
    override fun before() {
        runBlocking { repo.update { AppSettings(autoUpdate = false) } }
        targetContext.getSharedPreferences(MainViewModel.UI_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(MainViewModel.KEY_NOTIF_ASKED, true).commit()
        QuickTileState.markPrompted(targetContext)
        EngineStateHolder.set(EngineState.Stopped)
    }

    override fun after() {
        EngineStateHolder.set(EngineState.Stopped)
    }
}

/** Kosul saglanana kadar Compose saatini ilerletir (ayar yazmasi eszamansiz). */
internal fun ComposeTestRule.waitFor(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
    try {
        waitUntil(timeoutMs) { condition() }
    } catch (e: Throwable) {
        throw AssertionError("Beklenen durum olusmadi: $what (ayarlar: ${repo.current})", e)
    }
}

/**
 * Ekran goruntusu: uygulamanin dis depolamasina yazar, oradan adb ile cekilir
 * (/sdcard/Android/data/<paket>/files/screens).
 */
internal fun screenshot(name: String) {
    // Test saati durdurulmus olsa bile pencere gercek vsync ile cizilir; son karenin ekrana
    // ulasmasi icin kisa bir bekleme (ana is parcacigi serbest).
    Thread.sleep(400)
    val bmp: Bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
    val dir = File(targetContext.getExternalFilesDir(null), "screens").apply { mkdirs() }
    File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
}
