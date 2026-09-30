package io.github.unsalable.goodbyedpi.diag

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.unsalable.goodbyedpi.engine.ByeDpiArgs
import io.github.unsalable.goodbyedpi.engine.ByeDpiRunner
import io.github.unsalable.goodbyedpi.engine.DpiEngine
import io.github.unsalable.goodbyedpi.engine.EngineTestSupport.plainConfig
import io.github.unsalable.goodbyedpi.model.DnsProfile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread

/**
 * Baglanti testi gercek byedpi'ye karsi, ART'ta (RT-1): JVM'de getLoopbackAddress() 127.0.0.1
 * donerken ART'ta ::1 donuyordu ve vekil yolu cihazda hep "Baglanti kurulamadi" veriyordu.
 * Ayrica C3: ad, byedpi'nin --redirect'i uzerinden SECILI DNS'e soruluyor mu; DNS kapaliyken
 * sistem cozucusune dusuluyor mu.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionTesterDeviceTest {
    private var runner: ByeDpiRunner? = null
    private var dns: ServerSocket? = null

    @After
    fun tearDown() {
        runner?.stop()
        runCatching { dns?.close() }
    }

    private fun startByeDpi(profile: DnsProfile): Int {
        val port = DpiEngine.freeLoopbackPort()
        val r = ByeDpiRunner(ByeDpiArgs.build(plainConfig(port, profile)), port)
        runner = r
        r.start()
        return port
    }

    @Test
    fun viaProxyResolvesWithSelectedDnsAndPasses() = runBlocking {
        // Secili DNS: test surecindeki kucuk bir DNS-over-TCP sunucusu. example.com'u sistemin
        // verdigi gercek adrese cevaplar ve gelen sorulari kaydeder.
        val real = InetAddress.getAllByName("example.com").first { it.address.size == 4 }
        val queries = Collections.synchronizedList(ArrayList<String>())
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1")).also { dns = it }
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    runCatching {
                        s.use { sock ->
                            val inp = DataInputStream(sock.getInputStream())
                            while (true) {
                                val q = runCatching { DnsWire.readTcp(inp) }.getOrNull() ?: break
                                val (name, type) = question(q)
                                queries += "$name/$type"
                                val a = if (type == DnsWire.TYPE_A && name == "example.com") real.address else null
                                DnsWire.writeTcp(sock.getOutputStream(), answer(q, a))
                            }
                        }
                    }
                }
            }
        }
        val profile = DnsProfile("custom-test", "Test", "", "127.0.0.1", server.localPort, null, 0)
        val port = startByeDpi(profile)

        val r = ConnectionTester.run(port, listOf("example.com")).single()
        Log.i("GdpiTest", "vekil+secili DNS: $r, sorular=$queries")
        assertTrue(r.toString(), r.ok)
        // A ve AAAA ayri sorulur (aile bazli test); sunucu AAAA icin bos cevap veriyor.
        assertEquals(listOf("example.com/${DnsWire.TYPE_A}", "example.com/${DnsWire.TYPE_AAAA}"), queries.toList())
        assertEquals(DnsInfo(1, 0, DnsSource.SELECTED), r.dns)
    }

    @Test
    fun viaProxyWithDnsOffFallsBackToSystemResolver() = runBlocking {
        val port = startByeDpi(DnsProfile.Off)
        val started = System.nanoTime()
        val r = ConnectionTester.run(port, listOf("example.com")).single()
        val ms = (System.nanoTime() - started) / 1_000_000
        Log.i("GdpiTest", "vekil+DNS kapali: $r ($ms ms)")
        assertTrue(r.toString(), r.ok)
    }

    private fun question(q: ByteArray): Pair<String, Int> {
        var pos = 12
        val labels = ArrayList<String>()
        while (q[pos].toInt() != 0) {
            labels += String(q, pos + 1, q[pos].toInt(), Charsets.US_ASCII)
            pos += 1 + q[pos].toInt()
        }
        pos++
        return labels.joinToString(".") to (((q[pos].toInt() and 0xFF) shl 8) or (q[pos + 1].toInt() and 0xFF))
    }

    /** Soruyu tekrarlayan cevap; [a] null ise bos (NOERROR, kayit yok). */
    private fun answer(q: ByteArray, a: ByteArray?): ByteArray {
        var end = 12
        while (q[end].toInt() != 0) end += 1 + q[end].toInt()
        end += 5
        val out = java.io.ByteArrayOutputStream()
        out.write(q, 0, 2)
        out.write(byteArrayOf(0x81.toByte(), 0x80.toByte(), 0, 1, 0, if (a != null) 1 else 0, 0, 0, 0, 0))
        out.write(q, 12, end - 12)
        if (a != null) {
            out.write(byteArrayOf(0xC0.toByte(), 0x0C, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4))
            out.write(a)
        }
        return out.toByteArray()
    }
}
