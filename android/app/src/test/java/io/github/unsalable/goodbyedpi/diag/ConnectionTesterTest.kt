package io.github.unsalable.goodbyedpi.diag

import io.github.unsalable.goodbyedpi.diag.ConnectionTester.ERR_CERT
import io.github.unsalable.goodbyedpi.diag.ConnectionTester.ERR_CONNECT
import io.github.unsalable.goodbyedpi.diag.ConnectionTester.ERR_DNS
import io.github.unsalable.goodbyedpi.diag.ConnectionTester.ERR_RESET
import io.github.unsalable.goodbyedpi.diag.ConnectionTester.ERR_TIMEOUT
import io.github.unsalable.goodbyedpi.diag.ConnectionTester.ERR_TLS
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLProtocolException
import kotlin.concurrent.thread

class ConnectionTesterTest {
    private fun d(e: Throwable) = ConnectionTester.describeError(e)

    @Test
    fun typedExceptions() {
        assertEquals(ERR_DNS, d(UnknownHostException("Unable to resolve host \"discord.com\"")))
        assertEquals(ERR_TIMEOUT, d(SocketTimeoutException("failed to connect to /1.2.3.4 (port 443) after 8000ms")))
        assertEquals(ERR_TIMEOUT, d(SocketTimeoutException("Read timed out")))
        assertEquals(ERR_CONNECT, d(ConnectException("failed to connect to /1.2.3.4 (port 443): ECONNREFUSED")))
        assertEquals(ERR_CONNECT, d(NoRouteToHostException("No route to host")))
        assertEquals(ERR_RESET, d(EOFException()))
        assertEquals(ERR_CONNECT, d(IOException()))
        assertEquals(ERR_CONNECT, d(RuntimeException()))
    }

    @Test
    fun androidStyleResetMessages() {
        // Android/Conscrypt'in DPI sifirlamasinda urettigi tipik mesajlar.
        assertEquals(ERR_RESET, d(SocketException("Connection reset")))
        assertEquals(ERR_RESET, d(SocketException("recvfrom failed: ECONNRESET (Connection reset by peer)")))
        assertEquals(
            ERR_RESET,
            d(SSLHandshakeException("Read error: ssl=0x7b: I/O error during system call, Connection reset by peer")),
        )
        assertEquals(ERR_RESET, d(SSLHandshakeException("connection closed")))
        assertEquals(ERR_RESET, d(IOException("unexpected end of stream on https://discord.com/...")))
        assertEquals(ERR_RESET, d(SocketException("sendto failed: EPIPE (Broken pipe)")))
        assertEquals(ERR_RESET, d(SocketException("Software caused connection abort")))
    }

    @Test
    fun certificateProblems() {
        val pathFail = SSLHandshakeException("java.security.cert.CertPathValidatorException: Trust anchor for certification path not found.")
            .apply { initCause(CertPathValidatorException("Trust anchor for certification path not found.")) }
        assertEquals(ERR_CERT, d(pathFail))
        assertEquals(ERR_CERT, d(SSLPeerUnverifiedException("Hostname discord.com not verified")))
        assertEquals(ERR_CERT, d(SSLHandshakeException("x").apply { initCause(CertificateException("expired")) }))
        // Neden zinciri kaybolmus olsa da mesajdan taninir.
        assertEquals(ERR_CERT, d(SSLHandshakeException("Trust anchor for certification path not found.")))
    }

    @Test
    fun otherTlsFailures() {
        assertEquals(ERR_TLS, d(SSLProtocolException("SSL handshake aborted: ssl=0x7b: Failure in SSL library, usually a protocol error")))
        assertEquals(ERR_TLS, d(SSLHandshakeException("Handshake failed")))
    }

    @Test
    fun wrappedCausesAreFollowed() {
        assertEquals(ERR_DNS, d(IOException("wrap", UnknownHostException("x"))))
        assertEquals(ERR_TIMEOUT, d(RuntimeException(IOException(SocketTimeoutException()))))
        assertEquals(ERR_RESET, d(IOException("wrap", SocketException("Connection reset by peer"))))
    }

    @Test
    fun socksProxyFailures() {
        // Java SocksSocketImpl'in vekilden gelen hata kodlari icin mesajlari.
        assertEquals(ERR_CONNECT, d(SocketException("SOCKS: Host unreachable")))
        assertEquals(ERR_CONNECT, d(SocketException("SOCKS : General failure")))
        assertEquals(ERR_RESET, d(SocketException("SOCKS: Connection refused").let { IOException("Connection reset", it) }))
    }

    // ------------------------------------------------ gercek soketlerle (JVM, yerel)

    @Test
    fun runReportsEachHostInParallelWithMappedErrors() = runBlocking {
        val closedPort = ServerSocket(0).use { it.localPort }

        // Kabul edip hemen RST ile kapatan sunucu (SO_LINGER 0): DPI sifirlamasi taklidi.
        val resetServer = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val accepted = AtomicInteger()
        thread(isDaemon = true) {
            while (!resetServer.isClosed) {
                val s = runCatching { resetServer.accept() }.getOrNull() ?: break
                accepted.incrementAndGet()
                s.setSoLinger(true, 0)
                Thread.sleep(50)
                s.close()
            }
        }
        // Kabul edip hic cevap vermeyen sunucu: el sikismasi zaman asimi.
        val silentServer = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val held = ArrayList<java.net.Socket>()
        thread(isDaemon = true) {
            while (!silentServer.isClosed) {
                val s = runCatching { silentServer.accept() }.getOrNull() ?: break
                synchronized(held) { held += s }
            }
        }

        try {
            val started = System.nanoTime()
            val results = ConnectionTester.run(
                socksPort = null,
                hosts = listOf(
                    "127.0.0.1:$closedPort",
                    "127.0.0.1:${resetServer.localPort}",
                    "127.0.0.1:${silentServer.localPort}",
                    "no-such-host.invalid",
                ),
                timeoutMs = 1_500,
            )
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            assertEquals(4, results.size)
            results.forEach { assertFalse(it.toString(), it.ok); assertNull(it.millis) }
            assertEquals(ERR_CONNECT, results[0].error)
            assertEquals(ERR_RESET, results[1].error)
            assertEquals(ERR_TIMEOUT, results[2].error)
            assertEquals(ERR_DNS, results[3].error)
            assertEquals("127.0.0.1:$closedPort", results[0].host)
            // Paralel: toplam sure tek bir zaman asiminin kabaca ustunde, dort katinda degil.
            assertTrue("sure $elapsedMs ms", elapsedMs < 4_000)
            assertTrue(accepted.get() >= 1)
        } finally {
            resetServer.close()
            silentServer.close()
            synchronized(held) { held.forEach { it.close() } }
        }
    }

    @Test
    fun socksPathSendsHostnameToProxy() = runBlocking {
        // Kucuk bir SOCKS5 sunucusu: istemcinin adres turunu (ATYP) kaydedip reddeder.
        val proxy = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val atyp = AtomicInteger(-1)
        val hostSeen = arrayOfNulls<String>(1)
        thread(isDaemon = true) {
            val s = runCatching { proxy.accept() }.getOrNull() ?: return@thread
            s.use {
                val inp = it.getInputStream()
                val out = it.getOutputStream()
                inp.read() // VER
                val n = inp.read() // NMETHODS
                repeat(n) { inp.read() }
                out.write(byteArrayOf(5, 0)); out.flush()
                inp.read(); inp.read(); inp.read() // VER CMD RSV
                val t = inp.read()
                atyp.set(t)
                if (t == 3) {
                    val len = inp.read()
                    hostSeen[0] = String(ByteArray(len) { inp.read().toByte() })
                }
                // Hata: host unreachable (4)
                out.write(byteArrayOf(5, 4, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
            }
        }
        try {
            val r = ConnectionTester.run(proxy.localPort, listOf("discord.com"), 2_000).single()
            assertFalse(r.ok)
            assertEquals(ERR_CONNECT, r.error)
            // Elle kurulan SOCKS yolu adi vekile birakir; JDK'nin HttpURLConnection'i burada ATYP=1
            // (yerelde cozulmus IP) gonderiyordu, bu test o yuzden var.
            assertEquals(3, atyp.get())
            assertEquals("discord.com", hostSeen[0])
        } finally {
            proxy.close()
        }
    }
}
