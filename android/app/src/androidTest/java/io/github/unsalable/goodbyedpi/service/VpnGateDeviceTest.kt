package io.github.unsalable.goodbyedpi.service

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * VpnGate izni ACTIVATE_VPN'den okur (VpnService.prepare'siz): appops degisince karar degisir.
 * prepare'in yan etkisi (etkin baska VPN'i dusurmek) ikinci bir VPN uygulamasi gerektirdigi
 * icin elle dogrulanir (SPEC 8 "Passive VPN checks").
 */
@RunWith(AndroidJUnit4::class)
class VpnGateDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext

    @After
    fun tearDown() {
        shell("appops set ${ctx.packageName} ACTIVATE_VPN allow")
    }

    @Test
    fun consentFollowsAppOp() {
        // Cihazda baska bir VPN aciksa karar OTHER_VPN olur; bu test onu olcmuyor.
        assumeFalse("baska bir VPN etkin", VpnGate.otherVpnActive(ctx))

        shell("appops set ${ctx.packageName} ACTIVATE_VPN allow")
        assertTrue(VpnGate.hasConsent(ctx))
        assertEquals(VpnGate.Unattended.OK, VpnGate.unattendedStart(ctx))

        shell("appops set ${ctx.packageName} ACTIVATE_VPN ignore")
        assertFalse(VpnGate.hasConsent(ctx))
        assertEquals(VpnGate.Unattended.NO_CONSENT, VpnGate.unattendedStart(ctx))
    }

    private fun shell(cmd: String): String {
        val pfd = inst.uiAutomation.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() }
    }
}
