package io.github.unsalable.goodbyedpi.service

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.github.unsalable.goodbyedpi.MainActivity
import io.github.unsalable.goodbyedpi.data.SettingsRepository
import io.github.unsalable.goodbyedpi.model.DnsProfile
import io.github.unsalable.goodbyedpi.model.IspProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Karonun "baglan" istegi (sozlesme C5): MainActivity disa acik oldugu icin EXTRA_CONNECT
 * yalnizca disa kapali ConnectRequest takma adindan gelirse islenir; son kullanilanlardan
 * yeniden acilis (FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) istegi tekrar etmez.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
class ConnectRequestTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val repo = SettingsRepository.get(ctx)

    @Before
    fun setUp() {
        shell("appops set ${ctx.packageName} ACTIVATE_VPN allow")
        shell("pm grant ${ctx.packageName} android.permission.POST_NOTIFICATIONS")
        // Ilk Running'den sonra acilan "hizli ayarlara ekle" sistem penceresi (1.0.1) odagi alip
        // sonraki arayuz testlerini dusurmesin.
        QuickTileState.markPrompted(ctx)
        runBlocking {
            if (EngineStateHolder.state.value != EngineState.Stopped) {
                ServiceController.stop(ctx)
                withTimeout(15_000) { EngineStateHolder.state.first { it is EngineState.Stopped || it is EngineState.Failed } }
            }
            repo.update {
                it.copy(
                    isp = IspProfile.GENERAL_ID, method = "split2", dns = DnsProfile.YANDEX_ID,
                    wantRunning = false, autoConnect = false,
                )
            }
        }
    }

    @After
    fun tearDown() {
        ServiceController.stop(ctx)
        runBlocking { withTimeout(15_000) { EngineStateHolder.state.first { it is EngineState.Stopped || it is EngineState.Failed } } }
        shell("input keyevent KEYCODE_HOME")
    }

    private fun launch(component: ComponentName, extraFlags: Int = 0) {
        val i = Intent().setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or extraFlags)
            .putExtra(ServiceController.EXTRA_CONNECT, true)
        ctx.startActivity(i)
    }

    /** [ms] boyunca Running/Starting'e gecmedi mi. */
    private fun staysStopped(ms: Long): Boolean = runBlocking {
        withTimeoutOrNull(ms) {
            EngineStateHolder.state.first { it is EngineState.Running || it == EngineState.Starting }
        } == null
    }

    @Test
    fun extraOnExportedActivityIsIgnored() {
        // Baska bir uygulamanin yapabilecegi: disa acik MainActivity'ye dogrudan EXTRA_CONNECT.
        launch(ComponentName(ctx, MainActivity::class.java))
        assertTrue("disaridan gelen istek VPN'i acti", staysStopped(4_000))
        assertEquals(EngineState.Stopped, EngineStateHolder.state.value)
    }

    @Test
    fun aliasRequestConnects() {
        launch(ComponentName(ctx, ServiceController.CONNECT_ALIAS))
        val r = runBlocking { withTimeout(15_000) { EngineStateHolder.state.first { it is EngineState.Running } } }
        assertTrue(r is EngineState.Running)
        assertTrue(repo.current.wantRunning)
    }

    @Test
    fun relaunchFromHistoryIsIgnored() {
        launch(ComponentName(ctx, ServiceController.CONNECT_ALIAS), Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertTrue("son kullanilanlardan acilis yeniden baglandi", staysStopped(4_000))
    }

    @Test
    fun aliasIsNotExported() {
        val info = ctx.packageManager.getActivityInfo(ComponentName(ctx, ServiceController.CONNECT_ALIAS), 0)
        assertTrue(!info.exported)
        assertEquals(MainActivity::class.java.name, info.targetActivity)
    }

    private fun shell(cmd: String): String {
        val pfd = inst.uiAutomation.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() }
    }
}
