package io.github.unsalable.goodbyedpi.engine

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.unsalable.goodbyedpi.engine.EngineTestSupport.byedpiThreads
import io.github.unsalable.goodbyedpi.engine.EngineTestSupport.fdCount
import io.github.unsalable.goodbyedpi.engine.EngineTestSupport.httpsGetViaSocks
import io.github.unsalable.goodbyedpi.engine.EngineTestSupport.plainConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * byedpi uygulama surecinde: baslat -> SOCKS uzerinden HTTPS -> durdur, 5 kez; fd ve is
 * parcacigi sizintisi yok. Uygulama paketi VPN disinda oldugu icin istek dogrudan emulatorun
 * agina cikar (VPN acik olsa bile).
 */
@RunWith(AndroidJUnit4::class)
class ByeDpiInProcessTest {
    private var runner: ByeDpiRunner? = null

    @After
    fun tearDown() {
        runner?.stop()
    }

    private fun startRunner(): ByeDpiRunner {
        val port = DpiEngine.freeLoopbackPort()
        val r = ByeDpiRunner(ByeDpiArgs.build(plainConfig(port)), port)
        runner = r
        r.start()
        return r
    }

    @Test
    fun httpsThroughSocksAndRestartFiveTimes() {
        // Isinma: ilk TLS kullanimi sertifika deposu vb. acar; olcum ondan sonra.
        startRunner().let { r ->
            assertTrue(httpsGetViaSocks(r.port).startsWith("HTTP/1.1 "))
            assertTrue(r.stop())
        }
        System.gc()
        Thread.sleep(200)
        val fdBefore = fdCount()

        repeat(5) { i ->
            val r = startRunner()
            val status = httpsGetViaSocks(r.port)
            Log.i("GdpiTest", "tur $i: $status (port ${r.port})")
            assertEquals("tur $i", "HTTP/1.1 200 OK", status)
            val t0 = System.nanoTime()
            assertTrue("tur $i durmadi", r.stop())
            Log.i("GdpiTest", "tur $i durdurma ${(System.nanoTime() - t0) / 1_000_000} ms")
            assertFalse(r.isAlive)
            assertEquals(NativeBridge.OK, r.exitCode)
        }
        runner = null

        Thread.sleep(200)
        val fdAfter = fdCount()
        Log.i("GdpiTest", "fd: $fdBefore -> $fdAfter")
        // Kucuk pay: ART/Conscrypt'in tembel actiklari (orn. tek bir cache dosyasi).
        assertTrue("fd sizintisi: $fdBefore -> $fdAfter", fdAfter <= fdBefore + 2)
        assertEquals(0, byedpiThreads())
        // Artik calisan byedpi yok.
        assertEquals(-1, NativeBridge.byedpiStop())
    }

    @Test
    fun stopBeforeReadyIsSafe() {
        val port = DpiEngine.freeLoopbackPort()
        repeat(20) {
            val r = ByeDpiRunner(ByeDpiArgs.build(plainConfig(port)), port)
            // start() icinde hazir olmayi beklemeden hemen durdurma (baslatma yarisi).
            val t = Thread { runCatching { r.start() } }
            t.start()
            r.stop()
            t.join(5_000)
            assertFalse(r.isAlive)
        }
        assertEquals(0, byedpiThreads())
    }
}
