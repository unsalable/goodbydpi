package io.github.unsalable.goodbyedpi.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException

class Ipv6ProbeTest {
    @Test
    fun describeKeepsErrnoButNeverAddresses() {
        val e = ConnectException(
            "failed to connect to /2606:4700:4700::1111 (port 443) from /2001:db8:aaaa::16 (port 52832) " +
                "after 2500ms: isConnected failed: ECONNREFUSED (Connection refused)",
        )
        val d = Ipv6Probe.describe(e)
        assertEquals("ConnectException (ECONNREFUSED)", d)
        assertFalse(d.contains(":"))
        assertEquals(
            "SocketTimeoutException",
            Ipv6Probe.describe(SocketTimeoutException("failed to connect to /2001:4860:4860::8888 (port 443) after 2500ms")),
        )
    }

    @Test
    fun targetsAreGlobalV6Literals() {
        assertEquals(listOf("2001:4860:4860::8888", "2606:4700:4700::1111"), Ipv6Probe.TARGETS)
        assertEquals(443, Ipv6Probe.PORT)
    }
}
