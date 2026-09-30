package io.github.unsalable.goodbyedpi.service

import io.github.unsalable.goodbyedpi.service.VpnGate.Unattended
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnGateTest {
    private val me = 10_123

    /** prepare() etkin baska bir VPN'i dusurur: cagrildiysa test bozulur. */
    private val prepareMustNotRun: () -> Boolean = { throw AssertionError("prepare cagrildi") }

    @Test
    fun otherVpnWinsWithoutTouchingPrepare() {
        assertEquals(Unattended.OTHER_VPN, VpnGate.decide(true, { true }, prepareMustNotRun))
        // Izin okunamasa bile (eski surum) baska VPN varken prepare'e dusulmez.
        assertEquals(Unattended.OTHER_VPN, VpnGate.decide(true, { null }, prepareMustNotRun))
    }

    @Test
    fun consentFromAppOpDoesNotPrepare() {
        assertEquals(Unattended.OK, VpnGate.decide(false, { true }, prepareMustNotRun))
        assertEquals(Unattended.NO_CONSENT, VpnGate.decide(false, { false }, prepareMustNotRun))
    }

    @Test
    fun unreadableAppOpFallsBackToPrepareOnlyWithoutOtherVpn() {
        var calls = 0
        assertEquals(Unattended.OK, VpnGate.decide(false, { null }, { calls++; true }))
        assertEquals(Unattended.NO_CONSENT, VpnGate.decide(false, { null }, { calls++; false }))
        assertEquals(2, calls)
    }

    @Test
    fun foreignVpnByOwner() {
        // Kendi VPN'imiz (sahip uid'i biz) baska sayilmaz.
        assertFalse(VpnGate.isForeignVpn(isVpn = true, ownerUid = me, myUid = me))
        // Baskasinin VPN'inde sahip gizlenir (INVALID_UID = -1) ya da farkli uid.
        assertTrue(VpnGate.isForeignVpn(isVpn = true, ownerUid = -1, myUid = me))
        assertTrue(VpnGate.isForeignVpn(isVpn = true, ownerUid = 10_999, myUid = me))
        // API 30 oncesi sahip bilinmez: temkinli, baska.
        assertTrue(VpnGate.isForeignVpn(isVpn = true, ownerUid = null, myUid = me))
        // VPN olmayan ag hic sayilmaz.
        assertFalse(VpnGate.isForeignVpn(isVpn = false, ownerUid = -1, myUid = me))
    }
}
