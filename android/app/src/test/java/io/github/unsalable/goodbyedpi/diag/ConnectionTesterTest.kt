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

    // ------------------------------------------------ SOCKS yolu (sozlesme C3)

    @Test
    fun socksPathResolvesThroughRedirectedDnsAndConnectsByIp() = runBlocking {
        // byedpi --redirect taklidi: 198.18.0.53:53 kabul edilir ve DNS sunucusu gibi cevap verir
        // (once CNAME sonra A: zincir atlanmali); asil hedef reddedilir.
        FakeSocks(dnsAnswer = { name, type ->
            if (type == DnsWire.TYPE_A) FakeDns.Reply(cname = "cdn.$name", a = listOf(byteArrayOf(10, 9, 8, 7))) else FakeDns.Reply()
        }).use { proxy ->
            val r = ConnectionTester.run(proxy.port, listOf("discord.com"), 2_000).single()
            assertFalse(r.ok)
            assertEquals(ERR_CONNECT, r.error)
            assertEquals(listOf("1:198.18.0.53:53", "1:10.9.8.7:443"), proxy.requests())
            // Ayni DNS baglantisinda A sonra AAAA (bos): IPv6 denenmez, "kayit yok".
            assertEquals(listOf("discord.com/A", "discord.com/AAAA"), proxy.dnsQueries())
            assertEquals(DnsInfo(1, 0, DnsSource.SELECTED), r.dns)
            assertEquals(ERR_CONNECT, r.v4?.error)
            assertNull(r.v6)
        }
    }

    @Test
    fun socksPathFallsBackToSystemResolverWhenRedirectRefused() = runBlocking {
        // DNS "Kapali": yonlendirme yok, byedpi 198.18.0.53'u reddeder (C2). Ad sistemde cozulur,
        // vekile yine IP gider; hicbir istek ad (ATYP=3) tasimaz.
        FakeSocks(dnsAnswer = null).use { proxy ->
            val started = System.nanoTime()
            val r = ConnectionTester.run(proxy.port, listOf("localhost"), 2_000).single()
            val ms = (System.nanoTime() - started) / 1_000_000
            assertFalse(r.ok)
            assertEquals(ERR_CONNECT, r.error)
            // localhost sistemde ::1 de donebilir; IPv6 denemesi paralel, sirasi belirsiz.
            val reqs = proxy.requests()
            assertEquals("1:198.18.0.53:53", reqs.first())
            assertTrue(reqs.toString(), "1:127.0.0.1:443" in reqs)
            assertTrue("vekile ad gitmemeli: $reqs", reqs.none { it.startsWith("3:") })
            assertEquals(DnsSource.SYSTEM, r.dns?.source)
            assertTrue("reddedilen DNS adimi beklememeli ($ms ms)", ms < 1_500)
        }
    }

    @Test
    fun socksPathReportsNxdomainFromSelectedDnsWithoutFallback() = runBlocking {
        FakeSocks(dnsAnswer = { _, _ -> FakeDns.Reply(rcode = DnsWire.RCODE_NXDOMAIN) }).use { proxy ->
            val r = ConnectionTester.run(proxy.port, listOf("no-such-host.example"), 2_000).single()
            assertFalse(r.ok)
            assertEquals(ERR_DNS, r.error)
            // Yalnizca DNS baglantisi; sistem cozucusu ya da hedefe CONNECT yok.
            assertEquals(listOf("1:198.18.0.53:53"), proxy.requests())
        }
    }

    @Test
    fun socksPathTriesAaaaWhenNoARecord() = runBlocking {
        val v6 = ByteArray(16).also { it[0] = 0x20; it[1] = 0x01; it[15] = 1 }
        FakeSocks(dnsAnswer = { _, type ->
            if (type == DnsWire.TYPE_AAAA) FakeDns.Reply(aaaa = listOf(v6)) else FakeDns.Reply()
        }).use { proxy ->
            val r = ConnectionTester.run(proxy.port, listOf("v6only.example"), 2_000).single()
            assertEquals(ERR_CONNECT, r.error)
            assertEquals(listOf("v6only.example/A", "v6only.example/AAAA"), proxy.dnsQueries())
            assertEquals("4:2001:0:0:0:0:0:0:1:443", proxy.requests().last())
        }
    }

    @Test
    fun socksPathTestsBothFamiliesSeparately() = runBlocking {
        val v6 = ByteArray(16).also { it[0] = 0x2a; it[1] = 0x00; it[15] = 5 }
        FakeSocks(dnsAnswer = { _, type ->
            if (type == DnsWire.TYPE_A) {
                FakeDns.Reply(a = listOf(byteArrayOf(10, 0, 0, 1), byteArrayOf(10, 0, 0, 2)))
            } else {
                FakeDns.Reply(aaaa = listOf(v6))
            }
        }).use { proxy ->
            val r = ConnectionTester.run(proxy.port, listOf("www.google.com"), 2_000).single()
            assertEquals(DnsInfo(2, 1, DnsSource.SELECTED), r.dns)
            // Her aileden yalnizca ilk adres denenir.
            val reqs = proxy.requests()
            assertEquals(reqs.toString(), 3, reqs.size)
            assertTrue(reqs.toString(), "1:10.0.0.1:443" in reqs && "4:2a00:0:0:0:0:0:0:5:443" in reqs)
            assertFalse(r.v4!!.ok)
            assertFalse(r.v6!!.ok)
            assertEquals(ERR_CONNECT, r.v6!!.error)
            // Birincil sonuc IPv4'tur; rapor ayrintisi IP icermez.
            assertEquals(r.v4!!.error, r.error)
            val detail = r.v6!!.errorDetail!!
            assertTrue(detail, detail.startsWith("SocketException"))
            assertFalse(detail, detail.contains("2a00"))
        }
    }

    @Test
    fun socksPathKeepsIpv4WhenAaaaQueryFails() = runBlocking {
        FakeSocks(dnsAnswer = { _, type ->
            if (type == DnsWire.TYPE_A) FakeDns.Reply(a = listOf(byteArrayOf(10, 0, 0, 1))) else FakeDns.Reply(rcode = 2)
        }).use { proxy ->
            val r = ConnectionTester.run(proxy.port, listOf("example.com"), 2_000).single()
            assertEquals(DnsInfo(1, 0, DnsSource.SELECTED), r.dns)
            assertEquals(listOf("1:198.18.0.53:53", "1:10.0.0.1:443"), proxy.requests())
        }
    }

    // ------------------------------------------------ HTTP kurallari (Google / YouTube)

    @Test
    fun hostSpecificChecks() {
        assertEquals(ConnectionTester.HttpCheck.GOOGLE_SEARCH, ConnectionTester.checkFor("www.google.com"))
        assertEquals(ConnectionTester.HttpCheck.GOOGLE_SEARCH, ConnectionTester.checkFor("google.com.tr"))
        assertEquals(ConnectionTester.HttpCheck.YOUTUBE_204, ConnectionTester.checkFor("www.youtube.com"))
        assertEquals(ConnectionTester.HttpCheck.YOUTUBE_204, ConnectionTester.checkFor("m.youtube.com"))
        assertEquals(ConnectionTester.HttpCheck.ANY, ConnectionTester.checkFor("discord.com"))
        assertEquals(ConnectionTester.HttpCheck.ANY, ConnectionTester.checkFor("notgoogle.com"))
        assertEquals("/search?q=test", ConnectionTester.HttpCheck.GOOGLE_SEARCH.path)
        assertEquals("/generate_204", ConnectionTester.HttpCheck.YOUTUBE_204.path)
    }

    @Test
    fun googleCaptchaAndYoutube204() {
        val g = ConnectionTester.HttpCheck.GOOGLE_SEARCH
        fun h(c: ConnectionTester.HttpCheck, status: Int, location: String? = null) =
            ConnectionTester.evaluate(c, ConnectionTester.Head(status, location), 10)
        assertTrue(h(g, 429).let { it.ok && it.captcha })
        assertTrue(h(g, 302, "https://www.google.com/sorry/index?continue=x").captcha)
        assertFalse(h(g, 302, "https://www.google.com.tr/search?q=test").captcha)
        assertFalse(h(g, 200).captcha)
        assertEquals(200, h(g, 200).httpStatus)

        val y = ConnectionTester.HttpCheck.YOUTUBE_204
        assertTrue(h(y, 204).ok)
        val bad = h(y, 200)
        assertFalse(bad.ok)
        assertEquals("Beklenmeyen yanıt (HTTP 200)", bad.error)
        assertTrue(h(ConnectionTester.HttpCheck.ANY, 403).ok)
    }

    @Test
    fun readHeadParsesStatusAndLocation() {
        fun head(s: String) = ConnectionTester.readHead(s.byteInputStream(Charsets.US_ASCII))
        assertEquals(
            ConnectionTester.Head(302, "https://www.google.com/sorry/index"),
            head("HTTP/1.1 302 Found\r\nContent-Type: text/html\r\nLocation:  https://www.google.com/sorry/index\r\n\r\nbody"),
        )
        assertEquals(ConnectionTester.Head(204, null), head("HTTP/1.1 204 No Content\r\n\r\n"))
        assertEquals(ConnectionTester.Head(200, null), head("HTTP/1.0 200 OK\n"))
        assertTrue(runCatching { head("") }.exceptionOrNull() is EOFException)
        assertEquals(ERR_CONNECT, d(runCatching { head("SSH-2.0-x\r\n\r\n") }.exceptionOrNull()!!))
    }

    @Test
    fun errorDetailHasClassesErrnoAndSocksReplyButNoAddresses() {
        assertEquals(
            "SocketException (SOCKS: ağa ulaşılamıyor / network unreachable)",
            ConnectionTester.errorDetail(SocketException("SOCKS: Network unreachable")),
        )
        val reset = SSLHandshakeException("Read error: ssl=0x7b: I/O error")
            .apply { initCause(SocketException("recvfrom failed: ECONNRESET (Connection reset by peer)")) }
        assertEquals(
            "SSLHandshakeException/SocketException ECONNRESET (recvfrom failed: ECONNRESET (Connection reset by peer))",
            ConnectionTester.errorDetail(reset),
        )
        assertEquals(
            "SocketException (SOCKS: vekilden bozuk cevap / malformed reply)",
            ConnectionTester.errorDetail(SocketException("Malformed reply from SOCKS server")),
        )
        // 1.0.1 raporunda Ingilizce kalan iki metin (IPv6'siz mobil veri / bozuk IPv6 yolu).
        assertEquals(
            "SocketException (SOCKS: vekil hedefe bağlanamadı / general failure)",
            ConnectionTester.errorDetail(SocketException("SOCKS server general failure")),
        )
        assertEquals(
            "SocketException (SOCKS: bağlantı reddedildi / connection refused)",
            ConnectionTester.errorDetail(SocketException("SOCKS: Connection refused")),
        )
        assertEquals("SOCKS: vekil izin vermedi / not allowed", ConnectionTester.socksReply("SOCKS: Connection not allowed by ruleset"))
        // Bilinmeyen cevap ozgun haliyle; SOCKS'suz mesaj ceviriye girmez.
        assertEquals("SOCKS: Something new", ConnectionTester.socksReply("SOCKS: Something new"))
        assertEquals(null, ConnectionTester.socksReply("Connection refused"))
        val withIp = ConnectException(
            "failed to connect to /100.64.12.34 (port 443) from /10.0.0.2 after 3000ms: isConnected failed: EHOSTUNREACH",
        )
        assertEquals("ConnectException EHOSTUNREACH", ConnectionTester.errorDetail(withIp))
    }

    @Test
    fun defaultHostsCoverDualStackAndV4OnlySites() {
        assertEquals(
            listOf("www.google.com", "www.youtube.com", "discord.com", "roblox.com", "www.instagram.com", "example.com"),
            ConnectionTester.DEFAULT_HOSTS,
        )
    }

    @Test
    fun socksPathSkipsDnsForIpLiterals() = runBlocking {
        FakeSocks(dnsAnswer = null).use { proxy ->
            ConnectionTester.run(proxy.port, listOf("192.0.2.1:8443"), 2_000).single()
            assertEquals(listOf("1:192.0.2.1:8443"), proxy.requests())
        }
    }
}

/** Test icin DNS cevabi uretir (sorgunun sorusunu aynen tekrarlar, adlari isaretciyle yazar). */
internal object FakeDns {
    data class Reply(
        val rcode: Int = DnsWire.RCODE_NOERROR,
        val cname: String? = null,
        val a: List<ByteArray> = emptyList(),
        val aaaa: List<ByteArray> = emptyList(),
    )

    /** Sorgudan (ad, tur) okur. */
    fun question(q: ByteArray): Pair<String, Int> {
        var pos = 12
        val labels = ArrayList<String>()
        while (q[pos].toInt() != 0) {
            val len = q[pos].toInt()
            labels += String(q, pos + 1, len, Charsets.US_ASCII)
            pos += 1 + len
        }
        pos++
        val type = ((q[pos].toInt() and 0xFF) shl 8) or (q[pos + 1].toInt() and 0xFF)
        return labels.joinToString(".") to type
    }

    fun answer(q: ByteArray, r: Reply): ByteArray {
        val qEnd = run {
            var pos = 12
            while (q[pos].toInt() != 0) pos += 1 + q[pos].toInt()
            pos + 5
        }
        val out = java.io.ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v ushr 8 and 0xFF); out.write(v and 0xFF) }
        fun rr(type: Int, name: Int, data: ByteArray) {
            u16(name); u16(type); u16(1); u16(0); u16(60); u16(data.size); out.write(data)
        }
        val count = (if (r.cname != null) 1 else 0) + r.a.size + r.aaaa.size
        out.write(q, 0, 2) // kimlik
        u16(0x8180 or r.rcode)
        u16(1); u16(count); u16(0); u16(0)
        out.write(q, 12, qEnd - 12)
        var owner = 0xC00C
        if (r.cname != null) {
            val enc = java.io.ByteArrayOutputStream()
            r.cname.split('.').forEach { enc.write(it.length); enc.write(it.toByteArray()) }
            enc.write(0)
            val cnameAt = out.size() + 12
            rr(5, owner, enc.toByteArray())
            owner = 0xC000 or cnameAt
        }
        r.a.forEach { rr(DnsWire.TYPE_A, owner, it) }
        r.aaaa.forEach { rr(DnsWire.TYPE_AAAA, owner, it) }
        return out.toByteArray()
    }
}

/**
 * En kucuk SOCKS5 vekil: her CONNECT'i "ATYP:adres:port" olarak kaydeder. 198.18.0.53:53
 * [dnsAnswer] verilmisse kabul edilip DNS-over-TCP sunucusu gibi cevaplanir; digerleri
 * "connection refused" (5) ile reddedilir. 127.0.0.1'de dinler: byedpi gibi.
 */
internal class FakeSocks(private val dnsAnswer: ((String, Int) -> FakeDns.Reply)?) : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val log = java.util.Collections.synchronizedList(ArrayList<String>())
    private val dnsLog = java.util.Collections.synchronizedList(ArrayList<String>())
    val port: Int get() = server.localPort

    fun requests(): List<String> = synchronized(log) { log.toList() }
    fun dnsQueries(): List<String> = synchronized(dnsLog) { dnsLog.toList() }

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { s.use { handle(it) } } }
            }
        }
    }

    private fun handle(s: java.net.Socket) {
        val inp = java.io.DataInputStream(s.getInputStream())
        val out = s.getOutputStream()
        inp.readUnsignedByte() // VER
        repeat(inp.readUnsignedByte()) { inp.readUnsignedByte() }
        out.write(byteArrayOf(5, 0)); out.flush()
        inp.readUnsignedByte(); inp.readUnsignedByte(); inp.readUnsignedByte() // VER CMD RSV
        val atyp = inp.readUnsignedByte()
        val addr = when (atyp) {
            1 -> InetAddress.getByAddress(ByteArray(4).also { inp.readFully(it) }).hostAddress
            4 -> InetAddress.getByAddress(ByteArray(16).also { inp.readFully(it) }).hostAddress
            3 -> String(ByteArray(inp.readUnsignedByte()).also { inp.readFully(it) }, Charsets.US_ASCII)
            else -> "?"
        }
        val dport = inp.readUnsignedShort()
        log += "$atyp:$addr:$dport"
        val answer = dnsAnswer
        if (atyp == 1 && addr == "198.18.0.53" && dport == 53 && answer != null) {
            out.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 53)); out.flush()
            while (true) {
                val q = runCatching { DnsWire.readTcp(inp) }.getOrNull() ?: return
                val (name, type) = FakeDns.question(q)
                dnsLog += "$name/" + if (type == DnsWire.TYPE_A) "A" else if (type == DnsWire.TYPE_AAAA) "AAAA" else "$type"
                DnsWire.writeTcp(out, FakeDns.answer(q, answer(name, type)))
            }
        }
        out.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
    }

    override fun close() {
        server.close()
    }
}
