package io.github.unsalable.goodbyedpi.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsProfileTest {
    private val validV4 = listOf("0.0.0.0", "1.1.1.1", "77.88.8.8", "255.255.255.255", "10.0.0.1", "192.168.1.100")
    private val invalidV4 = listOf(
        "", "1.2.3", "1.2.3.4.5", "256.1.1.1", "1.2.3.256", "01.2.3.4", "1.02.3.4", "1..2.3",
        "a.b.c.d", "1.2.3.4 ", " 1.2.3.4", "1.2.3.-4", "+1.2.3.4", "1.2.3.4/24", "1234.1.1.1",
        "dns.google", "localhost", "::1", "１.２.３.４",
    )
    private val validV6 = listOf(
        "::", "::1", "1::", "2a02:6b8::feed:0ff", "2606:4700:4700::1111", "fe80::1",
        "2001:db8:0:0:0:0:2:1", "2001:DB8::2:1", "::ffff:1.2.3.4", "::1.2.3.4",
        "64:ff9b::192.0.2.33", "1:2:3:4:5:6:1.2.3.4", "1:2:3:4:5:6:7::", "::2:3:4:5:6:7:8",
        "1:2:3:4:5:6:7:8", "fd00:6764:7069::53",
    )
    private val invalidV6 = listOf(
        "", ":", ":::", "1::2::3", "gggg::", "12345::", "1:2:3:4:5:6:7:8:9", "1:2:3:4:5:6:7",
        "1:2:3:4:5:6:7:8::", "::1:2:3:4:5:6:7:8", ":1::", "1::2:", "1.2.3.4", "fe80::1%wlan0",
        "[::1]", "::ffff:1.2.3", "::ffff:256.1.1.1", "::ffff:01.2.3.4", "1:2:3:4:5:6:7:1.2.3.4",
        "1.2.3.4::", "::1.2.3.4:1", "dns.google", " ::1", "2001:db8::g",
    )

    @Test
    fun ipv4Literals() {
        validV4.forEach { assertTrue("gecerli olmali: '$it'", IpLiterals.isIpv4(it)) }
        invalidV4.forEach { assertFalse("gecersiz olmali: '$it'", IpLiterals.isIpv4(it)) }
    }

    @Test
    fun ipv6Literals() {
        validV6.forEach { assertTrue("gecerli olmali: '$it'", IpLiterals.isIpv6(it)) }
        invalidV6.forEach { assertFalse("gecersiz olmali: '$it'", IpLiterals.isIpv6(it)) }
    }

    @Test
    fun builtInsAreValid() {
        DnsProfile.builtIn.forEach { assertNull(it.id, it.validate()) }
        assertTrue(DnsProfile.Yandex.isActive)
        assertFalse(DnsProfile.Off.isActive)
        assertEquals(1253, DnsProfile.Yandex.v4Port)
        assertEquals("2a02:6b8::feed:0ff", DnsProfile.Yandex.v6Addr)
        assertEquals("Yandex (1253)", DnsProfile.Yandex.name)
    }

    @Test
    fun validateMessages() {
        fun custom(v4: String?, p4: Int = 53, v6: String? = null, p6: Int = 53) =
            DnsProfile.createCustom("custom", "Özel", v4, p4, v6, p6)

        assertNull(custom(null).validate()) // adres yok: yonlendirme kapali, hata degil
        assertNull(custom("9.9.9.9", 9953).validate())
        assertNull(custom(" 9.9.9.9 ").validate())
        assertNull(custom(null, v6 = "2620:fe::fe").validate())
        assertEquals("Geçersiz IPv4 adresi.", custom("dns.google").validate())
        assertEquals("Geçersiz IPv4 adresi.", custom("1.2.3").validate())
        assertEquals("Geçersiz IPv4 adresi.", custom("::1").validate())
        assertEquals("Geçersiz IPv6 adresi.", custom("1.1.1.1", v6 = "1.1.1.1").validate())
        assertEquals("Geçersiz IPv6 adresi.", custom(null, v6 = "dns.google").validate())
        assertEquals("Port 0-65535 aralığında olmalı.", custom("1.1.1.1", 65536).validate())
        assertEquals("Port 0-65535 aralığında olmalı.", custom("1.1.1.1", 53, "::1", 70000).validate())
        assertEquals(
            "Port 0-65535 aralığında olmalı.",
            DnsProfile("x", "x", "", "1.1.1.1", -1, null, 53).validate(),
        )
    }

    @Test
    fun createCustomNormalizes() {
        val p = DnsProfile.createCustom("custom:1", "Ev", "  ", 0, " ::1 ", -5)
        assertNull(p.v4Addr)
        assertEquals(53, p.v4Port)
        assertEquals("::1", p.v6Addr)
        assertEquals(53, p.v6Port)
        assertEquals("Kendi DNS sunucun: [::1]:53", p.description)
        assertEquals("Kendi DNS sunucun: adres girilmedi", DnsProfile.createCustom("c", "c", null, 53, null, 53).description)
        assertEquals("Kendi DNS sunucun: 9.9.9.9:9953", DnsProfile.createCustom("c", "c", "9.9.9.9", 9953, null, 53).description)
    }

    @Test
    fun fromIdAndEquality() {
        assertSame(DnsProfile.Yandex, DnsProfile.fromId("YANDEX"))
        assertSame(DnsProfile.Off, DnsProfile.fromId("off"))
        assertSame(DnsProfile.Cloudflare, DnsProfile.fromId("custom"))
        assertSame(DnsProfile.Cloudflare, DnsProfile.fromId(null))
        assertEquals(
            DnsProfile.createCustom("custom:1", "A", "1.1.1.1", 53, null, 53),
            DnsProfile.createCustom("CUSTOM:1", "B", "8.8.8.8", 53, null, 53),
        )
    }
}
