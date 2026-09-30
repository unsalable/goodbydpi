package io.github.unsalable.goodbyedpi.service

import io.github.unsalable.goodbyedpi.engine.Ipv6Gate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deneme onbellegi: basari da bayatlar ama tazelenene kadar gecerli kalir (1.0.2 F2). */
class Ipv6ProbeCacheTest {
    private val ok = Ipv6Probe.Result(true, "40 ms")
    private val fail = Ipv6Probe.Result(false, "SocketTimeoutException")

    private fun cache() = Ipv6ProbeCache<String>(okTtlMs = 600_000, failTtlMs = 300_000, maxSize = 3)

    @Test
    fun failureExpiresAndIsDropped() {
        val c = cache()
        c.put("wifi", fail, nowMs = 0)
        assertEquals(fail, c.get("wifi", 300_000))
        assertFalse(c.needsProbe("wifi", 300_000))
        // Suresi dolunca atilir: sonuc yok, ayni agda Ipv6Gate eski degeri (kapali) korur.
        assertNull(c.get("wifi", 300_001))
        assertNull(c.peek("wifi"))
        assertTrue(c.needsProbe("wifi", 300_001))
    }

    @Test
    fun successGoesStaleButStaysUsableUntilRefreshed() {
        val c = cache()
        c.put("sim", ok, nowMs = 0)
        assertFalse(c.isStale("sim", 600_000))
        assertFalse(c.needsProbe("sim", 600_000))

        // Eskiden basari hic bitmiyordu: IPv6 sonradan bozulsa da tun ag degisene kadar sunuyordu.
        val later = 600_001L
        assertTrue(c.isStale("sim", later))
        assertTrue(c.needsProbe("sim", later))
        // Bayat basari karar icin hala gecerli: yeniden deneme surerken tun kapanip acilmaz.
        assertEquals(ok, c.get("sim", later))
        assertTrue(Ipv6Gate.decide(gate = true, probeOk = c.get("sim", later)?.ok, sameNetwork = true, current = true))

        // Yeniden deneme basarili: sayac sifirlanir.
        c.put("sim", ok, later + 100)
        assertFalse(c.needsProbe("sim", later + 100 + 600_000))
        assertTrue(c.needsProbe("sim", later + 100 + 600_001))
    }

    @Test
    fun singleFailureAfterSuccessIsNotTrustedUntilConfirmed() {
        val c = cache()
        c.put("sim", ok, nowMs = 0)
        val stale = 600_001L
        assertTrue(c.isStale("sim", stale))

        // Tek basarisizlik (asansorde sinyal kaybi): yazilmaz, IPv6 acik kalir, motor yenilenmez.
        assertFalse(c.put("sim", fail, stale + 10))
        assertEquals(ok, c.get("sim", stale + 10))
        assertEquals(ok, c.peek("sim"))
        assertTrue(Ipv6Gate.decide(gate = true, probeOk = c.get("sim", stale + 10)?.ok, sameNetwork = true, current = true))
        // Hala bayat: saglik dongusu hemen yeniden dener.
        assertTrue(c.isStale("sim", stale + 10))
        assertTrue(c.needsProbe("sim", stale + 10))

        // Ikinci ardisik basarisizlik: yazilir, IPv6 kapanir.
        assertTrue(c.put("sim", fail, stale + 20_000))
        assertEquals(fail, c.get("sim", stale + 20_000))
        assertFalse(Ipv6Gate.decide(gate = true, probeOk = c.get("sim", stale + 20_000)?.ok, sameNetwork = true, current = true))
        assertFalse(c.needsProbe("sim", stale + 20_000))
    }

    @Test
    fun successInBetweenResetsTheFailureCount() {
        val c = cache()
        c.put("sim", ok, 0)
        assertFalse(c.put("sim", fail, 700_000))
        assertTrue(c.put("sim", ok, 700_100))
        // Yeni basarinin ustune gelen ilk basarisizlik yine dogrulanmadan yazilmaz.
        assertFalse(c.put("sim", fail, 1_400_000))
        assertEquals(ok, c.get("sim", 1_400_000))
    }

    @Test
    fun failureWithoutPriorSuccessIsStoredAtOnce() {
        val c = cache()
        // Yeni ag / yeni baglanti: bozuk IPv6 bir an bile sunulmasin (fail closed).
        assertTrue(c.put("new", fail, 0))
        assertEquals(fail, c.get("new", 0))
        // Basarisizligin ustune basarisizlik da hemen yazilir (zaman ilerler).
        assertTrue(c.put("new", fail, 1_000))
        assertEquals(fail, c.get("new", 301_000))
    }

    @Test
    fun dropStaleRemovesOnlyAStaleSuccess() {
        val c = cache()
        c.put("sim", ok, 0)
        assertFalse(c.dropStale("sim", 600_000))
        assertEquals(ok, c.peek("sim"))
        assertTrue(c.dropStale("sim", 600_001))
        assertNull(c.peek("sim"))
        c.put("bad", fail, 0)
        assertFalse(c.dropStale("bad", 10_000_000))
        assertFalse(c.dropStale("missing", 0))
    }

    @Test
    fun oldestEntryIsEvicted() {
        val c = cache()
        c.put("a", ok, 0)
        c.put("b", ok, 0)
        c.put("c", ok, 0)
        c.put("a", ok, 1) // yeniden yazilan sona gecer
        c.put("d", ok, 2)
        assertNull(c.peek("b"))
        assertEquals(ok, c.peek("a"))
        assertEquals(ok, c.peek("d"))
        c.remove("a")
        assertNull(c.peek("a"))
        c.clear()
        assertNull(c.peek("c"))
    }
}
