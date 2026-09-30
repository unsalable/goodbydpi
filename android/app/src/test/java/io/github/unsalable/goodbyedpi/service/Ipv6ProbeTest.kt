package io.github.unsalable.goodbyedpi.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

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

    /**
     * MTU kara deligi benzetimi: TCP el sikismasi gecer ama sunucudan hicbir veri gelmez. Eski
     * deneme (yalnizca connect) bunu "gecti" sayip IPv6'yi aciyordu; TLS el sikismasi zaman
     * asimiyla basarisiz olmali.
     */
    @Test
    fun handshakeThatNeverGetsServerFlightFails() {
        ServerSocket(0, 5, InetAddress.getByName("127.0.0.1")).use { server ->
            val held = ArrayList<Socket>()
            val t = thread(isDaemon = true) {
                runCatching { while (true) held += server.accept() }
            }
            var bound = 0
            val started = System.nanoTime()
            val r = Ipv6Probe.runWith(listOf("127.0.0.1"), server.localPort, timeoutMs = 600) { bound++ }
            val ms = (System.nanoTime() - started) / 1_000_000
            assertFalse(r.toString(), r.ok)
            assertTrue(r.detail, r.detail.startsWith("SocketTimeoutException"))
            assertEquals(1, bound)
            assertTrue("sure sinirli olmali: $ms ms", ms < 3_000)
            server.close()
            t.join(1_000)
            held.forEach { runCatching { it.close() } }
        }
    }

    @Test
    fun serverThatClosesOrRefusesFailsAndTriesEveryTarget() {
        ServerSocket(0, 5, InetAddress.getByName("127.0.0.1")).use { server ->
            val t = thread(isDaemon = true) {
                runCatching { while (true) server.accept().close() }
            }
            val r = Ipv6Probe.runWith(listOf("127.0.0.1", "127.0.0.1"), server.localPort, timeoutMs = 1_000) {}
            assertFalse(r.ok)
            assertFalse(r.detail, r.detail.contains("127.0.0.1"))
            server.close()
            t.join(1_000)
        }
        // Kapali port: baglanti reddedilir.
        val free = ServerSocket(0).use { it.localPort }
        val refused = Ipv6Probe.runWith(listOf("127.0.0.1"), free, timeoutMs = 1_000) {}
        assertFalse(refused.ok)
        assertTrue(refused.detail, refused.detail.startsWith("ConnectException"))
    }
}
