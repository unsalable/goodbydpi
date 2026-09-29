package io.github.unsalable.goodbyedpi.engine

import io.github.unsalable.goodbyedpi.model.AppSettings
import io.github.unsalable.goodbyedpi.model.CustomDnsEntry
import io.github.unsalable.goodbyedpi.model.DnsProfile
import io.github.unsalable.goodbyedpi.model.DpiConfig
import io.github.unsalable.goodbyedpi.model.FakePayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/** Canli ayar degisiminde neyin yeniden kuruldugu (C4, e2e RT-2, RT-2). */
class EngineConfigTest {
    private fun cfg(dns: DnsProfile = DnsProfile.Off, ipv6: Boolean = true, name: String = "x") =
        EngineConfig(name, DpiConfig(), emptyList(), dns, excludeLan = true, ipv6 = ipv6)

    private fun ip(s: String): InetAddress = InetAddress.getByName(s)

    @Test
    fun renamingCustomProfileKeepsEngine() {
        // Ozel profil adini yazarken motor yeniden kurulmamali: yalnizca ad farkli.
        val s0 = AppSettings().migrate()
        val id = s0.customProfiles.first().id
        val a = s0.copy(method = id)
        val b = a.copy(customProfiles = a.customProfiles.map { it.copy(name = "Türk Telekom deneme") })
        val ca = EngineConfig.from(a)
        val cb = EngineConfig.from(b)
        assertNotEquals(ca.methodName, cb.methodName)
        assertTrue(ca.sameEngineAs(cb))
        assertEquals(ca.runtimeKey(), cb.runtimeKey())
    }

    @Test
    fun renamingCustomDnsKeepsEngineButAddressChangeDoesNot() {
        val a = cfg(dns = CustomDnsEntry(name = "Ev", v4 = "9.9.9.9").toProfile())
        val renamed = cfg(dns = CustomDnsEntry(name = "Ev modemi", v4 = "9.9.9.9").toProfile())
        assertNotEquals(a.dnsName, renamed.dnsName)
        assertTrue(a.sameEngineAs(renamed))
        val moved = cfg(dns = CustomDnsEntry(name = "Ev", v4 = "9.9.9.9", v4Port = 5353).toProfile())
        assertFalse(a.sameEngineAs(moved))
    }

    @Test
    fun methodChangeChangesEngine() {
        val a = cfg()
        val b = a.copy(primary = DpiConfig(splitTls = false))
        assertFalse(a.sameEngineAs(b))
        // Ad degisse de davranis ayniysa ayni motor.
        assertTrue(a.sameEngineAs(a.copy(methodName = "baska")))
    }

    @Test
    fun customSniChangeChangesEngine() {
        val p = DpiConfig(fakePacket = true, fakePayload = FakePayload.TLS, fakeSni = "www.w3.org")
        val a = cfg().copy(primary = p)
        assertFalse(a.sameEngineAs(a.copy(primary = p.copy(fakeSni = "discord.com"))))
    }

    @Test
    fun tunKeyWithRedirectIgnoresUnderlyingDns() {
        val c = cfg(dns = DnsProfile.Cloudflare)
        assertTrue(c.redirectsDns)
        assertEquals(
            VpnTunBuilder.tunKey(c, listOf(ip("192.168.1.1"))),
            VpnTunBuilder.tunKey(c, listOf(ip("10.0.0.1"), ip("10.0.0.2"))),
        )
    }

    @Test
    fun tunKeyDnsOffComparesEffectiveServersAsSet() {
        val c = cfg(ipv6 = false)
        val a = VpnTunBuilder.tunKey(c, listOf(ip("192.168.1.1"), ip("192.168.1.2")))
        // Sira farki: ayni tun.
        assertEquals(a, VpnTunBuilder.tunKey(c, listOf(ip("192.168.1.2"), ip("192.168.1.1"))))
        // IPv6 kapaliyken VPN'e verilmeyecek IPv6 sunucu: ayni tun (bosuna yeniden kurulmasin).
        assertEquals(a, VpnTunBuilder.tunKey(c, listOf(ip("192.168.1.1"), ip("fe80::1"), ip("192.168.1.2"))))
        // Gercek degisim (baska agin DNS'i): yeni tun.
        assertNotEquals(a, VpnTunBuilder.tunKey(c, listOf(ip("10.0.0.1"))))
        // IPv6 acikken IPv6 sunucu da sayilir.
        val c6 = cfg(ipv6 = true)
        assertNotEquals(
            VpnTunBuilder.tunKey(c6, listOf(ip("192.168.1.1"))),
            VpnTunBuilder.tunKey(c6, listOf(ip("192.168.1.1"), ip("2001:db8::1"))),
        )
    }

    @Test
    fun tunKeySeesRoutesAndAddresses() {
        val c = cfg(dns = DnsProfile.Cloudflare)
        val dns = emptyList<InetAddress>()
        assertNotEquals(VpnTunBuilder.tunKey(c, dns), VpnTunBuilder.tunKey(c.copy(excludeLan = false), dns))
        assertNotEquals(VpnTunBuilder.tunKey(c, dns), VpnTunBuilder.tunKey(c.copy(ipv6 = false), dns))
        // Yalnizca yontem degisimi tun'a dokunmaz (byedpi degisir).
        assertEquals(VpnTunBuilder.tunKey(c, dns), VpnTunBuilder.tunKey(c.copy(primary = DpiConfig(splitTls = false)), dns))
    }
}
