package io.github.unsalable.goodbyedpi.ui

import android.Manifest
import android.os.Handler
import android.os.HandlerThread
import android.view.Window
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.unsalable.goodbyedpi.MainActivity
import io.github.unsalable.goodbyedpi.service.EngineState
import io.github.unsalable.goodbyedpi.service.EngineStateHolder
import io.github.unsalable.goodbyedpi.ui.components.HALO_PULSES
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bagli durumda ana ekran acik kalinca pencere bosta kalmali: hale birkac nabizdan sonra
 * sabit parilti olur. Onceden nabiz sonsuzdu ve 10 s'de ~610 kare ciziliyordu.
 *
 * Compose test kurali kullanilmaz: onun saati kareleri kendisi surer; burada gercek vsync ile
 * cizilen kareler (FrameMetrics) sayilir.
 */
@RunWith(AndroidJUnit4::class)
class HaloIdleTest {

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(FreshSettingsRule())
        .around(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /** [window] icin [millis] boyunca cizilen kare sayisi. */
    private fun countFrames(scenario: ActivityScenario<MainActivity>, millis: Long): Int {
        val count = AtomicInteger()
        val thread = HandlerThread("frames").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, _, _ -> count.incrementAndGet() }
        scenario.onActivity { it.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) }
        Thread.sleep(millis)
        scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
        thread.quitSafely()
        return count.get()
    }

    @Test
    fun connectedHaloSettlesAndDoesNotReplayAfterSettings() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            device.wait(Until.hasObject(By.desc("Bağlan")), 5_000)

            EngineStateHolder.set(EngineState.Starting)
            Thread.sleep(600)
            EngineStateHolder.set(EngineState.Running(0L, "Ters sıra", "Yandex (1253)", 1080))

            // Nabiz suresince ekran canli (baglanma ani vurgulanir)...
            val pulsing = countFrames(scenario, 2_000)
            assertTrue("nabiz sirasinda kare cizilmeli: $pulsing", pulsing > 20)

            // ...sonra oturur: HALO_PULSES x 2.4 s + pay.
            Thread.sleep(HALO_PULSES * 2_400L - 2_000L + 1_500L)
            val idle = countFrames(scenario, 5_000)
            assertTrue("oturmus hale bosta kare cizdirmemeli: $idle kare / 5 s", idle <= 5)

            // Ayarlar'a gidip donunce "yeni baglandi" nabzi yeniden baslamamali.
            device.findObject(By.desc("Ayarlar")).click()
            device.wait(Until.hasObject(By.text("İNTERNET SAĞLAYICI")), 5_000)
            device.pressBack()
            device.wait(Until.hasObject(By.desc("Bağlantıyı kes")), 5_000)
            Thread.sleep(1_500)
            val afterReturn = countFrames(scenario, 4_000)
            assertTrue("Ayarlar'dan donuste nabiz tekrarlanmamali: $afterReturn kare / 4 s", afterReturn <= 5)
        }
    }
}
