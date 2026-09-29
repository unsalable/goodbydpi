package io.github.unsalable.goodbyedpi.diag

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress

class DnsWireTest {
    private fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun queryMatchesRfc1035Layout() {
        val q = DnsWire.query(0x1234, "example.com", DnsWire.TYPE_A)
        val expected = hex(
            "1234 0100 0001 0000 0000 0000" +
                "07 6578616d706c65 03 636f6d 00" +
                "0001 0001",
        )
        assertArrayEquals(expected, q)
        // Sondaki nokta ve bosluk ayni adi verir; AAAA turu dogru yazilir.
        val q6 = DnsWire.query(0x1234, " example.com. ", DnsWire.TYPE_AAAA)
        assertArrayEquals(expected.copyOf(expected.size - 4) + hex("001c 0001"), q6)
    }

    @Test
    fun internationalNamesArePunycoded() {
        val q = DnsWire.query(1, "müzik.com", DnsWire.TYPE_A)
        assertEquals("müzik.com" to DnsWire.TYPE_A, FakeDns.question(q).let { (n, t) -> java.net.IDN.toUnicode(n) to t })
        assertTrue(String(q, Charsets.US_ASCII).contains("xn--"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyLabelRejected() {
        DnsWire.query(1, "a..b", DnsWire.TYPE_A)
    }

    @Test
    fun tcpFramingRoundTrip() {
        val msg = DnsWire.query(7, "discord.com", DnsWire.TYPE_A)
        val buf = ByteArrayOutputStream()
        DnsWire.writeTcp(buf, msg)
        val framed = buf.toByteArray()
        assertEquals(msg.size, ((framed[0].toInt() and 0xFF) shl 8) or (framed[1].toInt() and 0xFF))
        assertArrayEquals(msg, DnsWire.readTcp(ByteArrayInputStream(framed)))
    }

    @Test
    fun parsesAnswersFollowingCnameAndCompression() {
        val q = DnsWire.query(0xBEEF, "discord.com", DnsWire.TYPE_A)
        val reply = FakeDns.answer(
            q,
            FakeDns.Reply(cname = "edge.discord.com", a = listOf(byteArrayOf(162.toByte(), 159.toByte(), 128.toByte(), 233.toByte()), byteArrayOf(1, 2, 3, 4))),
        )
        val ans = DnsWire.parse(reply, 0xBEEF, DnsWire.TYPE_A)
        assertEquals(DnsWire.RCODE_NOERROR, ans.rcode)
        assertEquals(listOf("162.159.128.233", "1.2.3.4"), ans.addresses.map { it.hostAddress })
        // AAAA istenirken A kayitlari sayilmaz.
        assertEquals(emptyList<InetAddress>(), DnsWire.parse(reply, 0xBEEF, DnsWire.TYPE_AAAA).addresses)
    }

    @Test
    fun parsesAaaaAndNxdomain() {
        val q = DnsWire.query(9, "v6.example", DnsWire.TYPE_AAAA)
        val v6 = ByteArray(16).also { it[0] = 0x20; it[1] = 0x01; it[2] = 0x0d; it[3] = 0xb8.toByte(); it[15] = 5 }
        val ans = DnsWire.parse(FakeDns.answer(q, FakeDns.Reply(aaaa = listOf(v6))), 9, DnsWire.TYPE_AAAA)
        assertEquals(listOf(InetAddress.getByAddress(v6)), ans.addresses)

        val nx = DnsWire.parse(FakeDns.answer(q, FakeDns.Reply(rcode = DnsWire.RCODE_NXDOMAIN)), 9, DnsWire.TYPE_AAAA)
        assertEquals(DnsWire.RCODE_NXDOMAIN, nx.rcode)
        assertTrue(nx.addresses.isEmpty())
    }

    @Test
    fun rejectsForeignOrBrokenMessages() {
        val q = DnsWire.query(5, "example.com", DnsWire.TYPE_A)
        val good = FakeDns.answer(q, FakeDns.Reply(a = listOf(byteArrayOf(1, 1, 1, 1))))
        assertFormat { DnsWire.parse(good, 6, DnsWire.TYPE_A) } // kimlik tutmuyor
        assertFormat { DnsWire.parse(q, 5, DnsWire.TYPE_A) } // cevap bayragi yok
        assertFormat { DnsWire.parse(good.copyOf(good.size - 2), 5, DnsWire.TYPE_A) } // kesik veri
        assertFormat { DnsWire.parse(ByteArray(5), 0, DnsWire.TYPE_A) }
        // Sonsuz isaretci dongusu yok: isaretci tek adimda biter; bozuk etiket turu reddedilir.
        val bad = good.copyOf().also { it[12] = 0x80.toByte() }
        assertFormat { DnsWire.parse(bad, 5, DnsWire.TYPE_A) }
        // Uzunluk oneki baslik boyundan kisaysa.
        assertFormat { DnsWire.readTcp(ByteArrayInputStream(byteArrayOf(0, 3, 1, 2, 3))) }
    }

    private fun assertFormat(block: () -> Unit) {
        try {
            block()
        } catch (e: DnsWire.FormatException) {
            return
        }
        throw AssertionError("FormatException bekleniyordu")
    }
}
