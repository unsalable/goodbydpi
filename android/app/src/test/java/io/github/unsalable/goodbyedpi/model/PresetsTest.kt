package io.github.unsalable.goodbyedpi.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetsTest {
    @Test
    fun methodIdsUniqueAndOrdered() {
        val ids = MethodPreset.all.map { it.id }
        assertEquals(ids.size, ids.map { it.lowercase() }.toSet().size)
        assertEquals(
            listOf(
                "default", "fixedttl", "disorder", "ttl4", "ttl3", "md5sig", "md5ttl3",
                "fakesplit5", "zerofake", "split2", "split", "tlsrec",
            ),
            ids,
        )
        assertFalse("checksum" in ids)
        MethodPreset.all.forEach {
            assertTrue(it.name.isNotBlank())
            assertTrue(it.description.isNotBlank())
            // Her yontem gecerli aralikta bir yapilandirma uretir.
            assertEquals(it.build(), it.build().sanitized())
        }
    }

    @Test
    fun methodFromId() {
        assertSame(MethodPreset.Disorder, MethodPreset.fromId("disorder"))
        assertSame(MethodPreset.Disorder, MethodPreset.fromId("DisOrder"))
        assertSame(MethodPreset.TlsRec, MethodPreset.fromId("TLSREC"))
        assertSame(MethodPreset.Default, MethodPreset.fromId("checksum"))
        assertSame(MethodPreset.Default, MethodPreset.fromId("nope"))
        assertSame(MethodPreset.Default, MethodPreset.fromId(null))
        assertSame(MethodPreset.Default, MethodPreset.fromId("custom"))
    }

    @Test
    fun methodEqualityById() {
        val a = MethodPreset("custom:1", "A", "x") { DpiConfig() }
        val b = MethodPreset("CUSTOM:1", "B", "y") { DpiConfig(ttl = 3) }
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, MethodPreset.Default)
    }

    @Test
    fun presetConfigs() {
        assertEquals(DpiConfig(), MethodPreset.Default.build())
        MethodPreset.Disorder.build().let {
            assertFalse(it.fakePacket); assertTrue(it.splitTls); assertTrue(it.reverseSplit)
            assertEquals(2, it.splitPosition); assertFalse(it.splitSni)
        }
        MethodPreset.FakeTtl3.build().let { assertEquals(3, it.ttl); assertFalse(it.splitTls) }
        MethodPreset.FakeTtl4.build().let { assertEquals(4, it.ttl); assertFalse(it.splitTls) }
        MethodPreset.Md5Sig.build().let { assertFalse(it.fakeTtl); assertTrue(it.fakeMd5Sig) }
        MethodPreset.Md5Ttl3.build().let { assertTrue(it.fakeTtl); assertTrue(it.fakeMd5Sig); assertEquals(3, it.ttl) }
        MethodPreset.SplitFake5.build().let { assertTrue(it.splitFake); assertEquals(5, it.ttl) }
        MethodPreset.ZeroFake.build().let { assertEquals(FakePayload.ZEROS, it.fakePayload) }
        MethodPreset.PlainSplit.build().let { assertFalse(it.fakePacket); assertFalse(it.reverseSplit); assertFalse(it.splitSni) }
        MethodPreset.SplitOnly.build().let { assertFalse(it.fakePacket); assertFalse(it.reverseSplit); assertTrue(it.splitSni) }
        MethodPreset.TlsRec.build().let { assertFalse(it.fakePacket); assertTrue(it.tlsRecordSplit); assertFalse(it.splitTls) }
    }

    @Test
    fun ispProfiles() {
        assertEquals(
            listOf(
                "general", "turktelekom", "superonline", "vodafone", "turknet",
                "kablonet", "telekommobil", "turkcellmobil", "vodafonemobil",
            ),
            IspProfile.all.map { it.id },
        )
        assertEquals(
            listOf(
                "Genel", "Türk Telekom", "Superonline", "Vodafone", "TürkNet",
                "Kablonet", "TT Mobil", "Turkcell Mobil", "Vodafone Mobil",
            ),
            IspProfile.all.map { it.name },
        )

        val known = MethodPreset.all.map { it.id }.toSet()
        for (isp in IspProfile.all) {
            assertTrue(isp.methodIds.isNotEmpty())
            // Her kimlik gercek bir hazir yonteme cozulur (Varsayilan'a dusmez).
            isp.methodIds.forEach { assertTrue("${isp.id}: $it", it in known) }
            assertEquals(isp.methodIds, isp.methods.map { it.id })
            assertEquals(isp.methodIds.first(), isp.recommendedId)
            assertFalse("checksum" in isp.methodIds)
            if (isp === IspProfile.General) assertNull(isp.dnsId) else assertEquals("yandex", isp.dnsId)
        }

        assertEquals(listOf("default", "fixedttl", "disorder", "tlsrec", "split"), IspProfile.General.methodIds)
        assertEquals(listOf("ttl4", "disorder", "ttl3", "default"), IspProfile.TurkTelekom.methodIds)
        assertEquals(listOf("ttl3", "md5sig", "disorder", "md5ttl3"), IspProfile.Superonline.methodIds)
        assertEquals("zerofake", IspProfile.TelekomMobil.recommendedId)
        assertEquals("fakesplit5", IspProfile.Vodafone.recommendedId)
        assertEquals("fakesplit5", IspProfile.VodafoneMobil.recommendedId)
        assertEquals("default", IspProfile.TurkcellMobil.recommendedId)
        assertEquals("ttl4", IspProfile.Kablonet.recommendedId)

        // Android "Ters sira"sinda masaustundeki ortusme (seqovl) yok ve Turk hatlarinda
        // denenmedi: her saglayicida onerilen sahte paketli bir yontem, Ters sira yedeklerde.
        for (isp in IspProfile.all) {
            if (isp === IspProfile.General) continue
            assertTrue(isp.id, isp.recommended.build().fakePacket)
            assertTrue(isp.id, "disorder" in isp.methodIds.drop(1))
            // Aciklama gercek onerilen yontemin adini tasiyor.
            assertTrue(isp.id, isp.description.contains("Önerilen: ${isp.recommended.name}"))
        }
    }

    @Test
    fun ispFromId() {
        assertSame(IspProfile.Superonline, IspProfile.fromId("SuperOnline"))
        assertSame(IspProfile.General, IspProfile.fromId("unknown"))
        assertSame(IspProfile.General, IspProfile.fromId(null))
    }
}
