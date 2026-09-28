package io.github.unsalable.goodbyedpi.engine

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.unsalable.goodbyedpi.engine.EngineTestSupport.byedpiThreads
import io.github.unsalable.goodbyedpi.engine.EngineTestSupport.fdCount
import io.github.unsalable.goodbyedpi.engine.EngineTestSupport.httpsGetViaSocks
import io.github.unsalable.goodbyedpi.engine.EngineTestSupport.plainConfig
import io.github.unsalable.goodbyedpi.model.DnsProfile
import io.github.unsalable.goodbyedpi.service.TrafficStats
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * DpiEngine'in baslatma/durdurma sirasi ve hata yollari; tun yerine soket cifti (hev gercekten
 * calisir). Her hata yolundan sonra: byedpi is parcacigi yok, hev calismiyor, fd sizintisi yok.
 */
@RunWith(AndroidJUnit4::class)
class DpiEngineTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private var engine: DpiEngine? = null
    private val tuns = ArrayList<EngineTestSupport.FakeTun>()

    @Before
    fun setUp() {
        dir = File(ctx.cacheDir, "enginetest").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        engine?.stop()
        tuns.forEach { it.close() }
        assertNoEngineLeft()
    }

    private fun newTun() = EngineTestSupport.FakeTun().also { tuns += it }

    private fun assertNoEngineLeft() {
        assertEquals("byedpi is parcacigi kaldi", 0, byedpiThreads())
        assertFalse("hev hala calisiyor", TProxy.TProxyIsRunning())
        assertEquals(-1, NativeBridge.byedpiStop())
    }

    private fun warmUp() {
        // Yerel kutuphaneler ve ilk TLS'in actiklari olcume girmesin.
        val tun = newTun()
        val e = DpiEngine(dir, { tun.engineEnd() })
        val cfg = e.start(plainConfig())
        httpsGetViaSocks(cfg.socksPort)
        e.stop()
        System.gc()
        Thread.sleep(200)
    }

    @Test
    fun fullStartStopCyclesWithFakeTun() {
        warmUp()
        val fdBefore = fdCount()
        repeat(5) { i ->
            val tun = newTun()
            var handed: ParcelFileDescriptor? = null
            val e = DpiEngine(dir, { handed = tun.engineEnd(); handed })
            engine = e
            val cfg = e.start(plainConfig(dns = DnsProfile.Yandex))
            assertTrue(e.isRunning)
            assertTrue(TProxy.TProxyIsRunning())
            assertNull(e.checkHealth())
            assertTrue(cfg.socksPort in 1..65535)
            val yml = e.hevConfigFile.readText()
            assertTrue(yml.contains("  port: ${cfg.socksPort}\n"))
            // hev calisirken byedpi de SOCKS'a cevap veriyor.
            assertEquals("HTTP/1.1 200 OK", httpsGetViaSocks(cfg.socksPort))
            assertEquals(TrafficStats.ZERO, e.stats().copy())

            e.stop()
            assertFalse(e.isRunning)
            assertFalse(TProxy.TProxyIsRunning())
            // Motor tun fd'sini kapatti (sahibi o).
            try {
                handed!!.fd
                fail("tur $i: pfd acik kaldi")
            } catch (_: IllegalStateException) {
            }
            engine = null
            tun.close()
            tuns.remove(tun)
        }
        Thread.sleep(200)
        val fdAfter = fdCount()
        Log.i("GdpiTest", "motor fd: $fdBefore -> $fdAfter")
        assertTrue("fd sizintisi: $fdBefore -> $fdAfter", fdAfter <= fdBefore + 2)
    }

    @Test
    fun portBusyFailsFastAndCleansUp() {
        warmUp()
        val fdBefore = fdCount()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { busy ->
            val e = DpiEngine(dir, { fail("port doluyken tun kurulmamali"); null })
            val t0 = System.nanoTime()
            try {
                e.start(plainConfig(port = busy.localPort))
                fail("baslamamaliydi")
            } catch (ex: DpiEngine.StartException) {
                Log.i("GdpiTest", "port dolu: ${ex.message}")
                assertTrue(ex.portBusy)
                assertTrue(ex.retryable)
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue("yavas: $ms ms", ms < 1_000)
            assertFalse(e.isRunning)
        }
        assertNoEngineLeft()
        assertTrue(fdCount() <= fdBefore + 1)
    }

    @Test
    fun missingVpnPermissionCleansUp() {
        var port = 0
        val e = DpiEngine(dir, { cfg -> port = cfg.socksPort; null })
        try {
            e.start(plainConfig())
            fail("baslamamaliydi")
        } catch (ex: DpiEngine.StartException) {
            assertEquals("VPN izni yok", ex.message)
            assertFalse(ex.retryable)
        }
        assertFalse(e.isRunning)
        assertNoEngineLeft()
        // byedpi portu artik dinlenmiyor.
        assertTrue(port > 0)
        assertFalse(canConnect(port))
    }

    @Test
    fun establishThrowingCleansUp() {
        val e = DpiEngine(dir, { throw IllegalStateException("baska VPN her zaman acik") })
        try {
            e.start(plainConfig())
            fail("baslamamaliydi")
        } catch (ex: DpiEngine.StartException) {
            assertTrue(ex.message!!.startsWith("VPN arayüzü kurulamadı"))
            assertTrue(ex.retryable)
        }
        assertNoEngineLeft()
    }

    @Test
    fun invalidHevConfigPathCleansUp() {
        // filesDir yerine bir DOSYA: hev.yml yazilamaz -> baslatma hatasi, tun fd kapanir.
        val notDir = File(dir, "file").apply { writeText("x") }
        val tun = newTun()
        var handed: ParcelFileDescriptor? = null
        val e = DpiEngine(notDir, { handed = tun.engineEnd(); handed })
        try {
            e.start(plainConfig())
            fail("baslamamaliydi")
        } catch (ex: DpiEngine.StartException) {
            Log.i("GdpiTest", "hev.yml: ${ex.message}")
            assertTrue(ex.retryable)
        }
        assertNotNull(handed)
        try {
            handed!!.fd
            fail("pfd acik kaldi")
        } catch (_: IllegalStateException) {
        }
        assertNoEngineLeft()
    }

    @Test
    fun hevExitIsDetectedByHealthCheck() {
        val tun = newTun()
        val e = DpiEngine(dir, { tun.engineEnd() })
        engine = e
        e.start(plainConfig())
        assertNull(e.checkHealth())
        // Tun'un karsi ucu kapaninca hev (P3) ~1 sn icinde kendisi durur.
        tun.closePeer()
        val deadline = System.currentTimeMillis() + 5_000
        var reason: String? = null
        while (reason == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
            reason = e.checkHealth()
        }
        Log.i("GdpiTest", "saglik: $reason")
        assertEquals("Tünel beklenmedik şekilde kapandı.", reason)
        // Watchdog'un yapacagi gibi: durdur, sonra yeniden baslat.
        e.stop()
        val tun2 = newTun()
        val e2 = DpiEngine(dir, { tun2.engineEnd() })
        engine = e2
        val cfg = e2.start(plainConfig())
        assertEquals("HTTP/1.1 200 OK", httpsGetViaSocks(cfg.socksPort))
    }

    @Test
    fun byedpiExitListenerNotCalledOnNormalStop() {
        val tun = newTun()
        val e = DpiEngine(dir, { tun.engineEnd() })
        val called = CountDownLatch(1)
        e.exitListener = DpiEngine.ExitListener { called.countDown() }
        e.start(plainConfig())
        e.stop()
        assertFalse(called.await(300, TimeUnit.MILLISECONDS))
    }

    private fun canConnect(port: Int): Boolean =
        try {
            Socket("127.0.0.1", port).close()
            true
        } catch (_: Exception) {
            false
        }
}
