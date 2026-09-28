package io.github.unsalable.goodbyedpi.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DpiConfigTest {
    @Test
    fun defaultSummary() {
        assertEquals("sahte paket (TTL 5) · ters sıra bölme · QUIC engeli · Discord ses", DpiConfig().summary)
    }

    @Test
    fun summaryVariants() {
        assertEquals(
            "sahte paket (MD5) · QUIC engeli · Discord ses",
            DpiConfig(fakeTtl = false, fakeMd5Sig = true, splitTls = false).summary,
        )
        assertEquals(
            "sahte paket (TTL 3 + MD5)",
            DpiConfig(ttl = 3, fakeMd5Sig = true, splitTls = false, blockQuic = false, voiceFake = false).summary,
        )
        assertEquals(
            "sahte paket (TTL 5, bölünmüş)",
            DpiConfig(splitFake = true, splitTls = false, blockQuic = false, voiceFake = false).summary,
        )
        assertEquals(
            "boş sahte (TTL 5)",
            DpiConfig(fakePayload = FakePayload.ZEROS, splitTls = false, blockQuic = false, voiceFake = false).summary,
        )
        assertEquals(
            "sahte paket (korumasız)",
            DpiConfig(fakeTtl = false, splitTls = false, blockQuic = false, voiceFake = false).summary,
        )
        assertEquals(
            "bölme · TLS kayıt bölme",
            DpiConfig(
                fakePacket = false, reverseSplit = false, tlsRecordSplit = true,
                blockQuic = false, voiceFake = false,
            ).summary,
        )
    }

    @Test
    fun nothingSelected() {
        val none = DpiConfig(fakePacket = false, splitTls = false, blockQuic = false, voiceFake = false)
        assertEquals("Atlatma tekniği seçilmedi.", none.summary)
    }

    @Test
    fun sanitizedClampsRanges() {
        val s = DpiConfig(ttl = 0, splitPosition = -3, voiceFakeRepeats = 99).sanitized()
        assertEquals(1, s.ttl)
        assertEquals(1, s.splitPosition)
        assertEquals(20, s.voiceFakeRepeats)

        val hi = DpiConfig(ttl = 300, splitPosition = 1000, voiceFakeRepeats = 0).sanitized()
        assertEquals(64, hi.ttl)
        assertEquals(64, hi.splitPosition)
        assertEquals(1, hi.voiceFakeRepeats)
    }

    @Test
    fun sanitizedFixesFakeSni() {
        assertEquals("www.w3.org", DpiConfig(fakeSni = "   ").sanitized().fakeSni)
        assertEquals("www.w3.org", DpiConfig(fakeSni = "a b.com").sanitized().fakeSni)
        assertEquals("www.w3.org", DpiConfig(fakeSni = "x;rm -rf").sanitized().fakeSni)
        assertEquals("example.com", DpiConfig(fakeSni = " example.com ").sanitized().fakeSni)
    }

    @Test
    fun sanitizedKeepsValidAndIsIdempotent() {
        val c = DpiConfig(ttl = 7, splitPosition = 3, voiceFakeRepeats = 4, fakeSni = "a.example")
        assertEquals(c, c.sanitized())
        assertEquals(c.sanitized(), c.sanitized().sanitized())
        // Mantiksal secimlere dokunmaz: korumasiz sahte paket oldugu gibi kalir.
        val unprotected = DpiConfig(fakeTtl = false, fakeMd5Sig = false)
        assertFalse(unprotected.sanitized().fakeTtl)
        assertFalse(unprotected.hasFakeProtection)
        assertTrue(DpiConfig().hasFakeProtection)
    }
}
