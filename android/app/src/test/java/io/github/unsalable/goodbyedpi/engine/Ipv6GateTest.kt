package io.github.unsalable.goodbyedpi.engine

import io.github.unsalable.goodbyedpi.model.DnsProfile
import io.github.unsalable.goodbyedpi.model.DpiConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/** Tunelde IPv6 kapisi: alttaki ag IPv6'siz ya da bozuksa tun hic IPv6 sunmaz (1.0.2). */
class Ipv6GateTest {
    private fun ip(s: String): InetAddress = InetAddress.getByName(s)

    private val yandex = DnsProfile.Yandex

    private fun cfg(ipv6: Boolean, dns: DnsProfile = yandex) =
        EngineConfig("x", DpiConfig(), emptyList(), dns, excludeLan = true, ipv6 = ipv6, socksPort = 10808)

    @Test
    fun globalMeans2000Slash3Only() {
        assertTrue(Ipv6Gate.isGlobal(ip("2001:db8::16")))
        assertTrue(Ipv6Gate.isGlobal(ip("2a02:6b8::feed:ff")))
        assertTrue(Ipv6Gate.isGlobal(ip("3fff::1")))
        assertFalse(Ipv6Gate.isGlobal(ip("fd00:6764:7069::1"))) // ULA
        assertFalse(Ipv6Gate.isGlobal(ip("fc00::1")))
        assertFalse(Ipv6Gate.isGlobal(ip("fe80::1"))) // link-local
        assertFalse(Ipv6Gate.isGlobal(ip("fec0::5054:ff:fe12:3456"))) // emulatorun site-local'i
        assertFalse(Ipv6Gate.isGlobal(ip("::1")))
        assertFalse(Ipv6Gate.isGlobal(ip("4000::1")))
        assertFalse(Ipv6Gate.isGlobal(ip("10.0.2.15")))
    }

    @Test
    fun gateTable() {
        // Plan T0 tablosu.
        assertTrue(Ipv6Gate.hasGlobalV6(listOf(ip("10.0.2.15"), ip("2001:db8:aaaa::16")), v6DefaultRoute = true))
        assertFalse(Ipv6Gate.hasGlobalV6(listOf(ip("fec0::1")), v6DefaultRoute = true))
        assertFalse(Ipv6Gate.hasGlobalV6(listOf(ip("fd00::1")), v6DefaultRoute = true))
        assertFalse(Ipv6Gate.hasGlobalV6(listOf(ip("2001:db8:aaaa::16")), v6DefaultRoute = false))
        assertFalse(Ipv6Gate.hasGlobalV6(listOf(ip("fe80::1"), ip("10.0.2.15")), v6DefaultRoute = true))
        assertFalse(Ipv6Gate.hasGlobalV6(emptyList(), v6DefaultRoute = true))
    }

    @Test
    fun globalAddressesKeepsOnlyGlobal() {
        val got = Ipv6Gate.globalAddresses(listOf(ip("10.0.2.15"), ip("fe80::1"), ip("2001:db8::1"), ip("fec0::2")))
        assertEquals(setOf(ip("2001:db8::1")), got)
    }

    @Test
    fun decideFailsClosed() {
        // Kapi kapali: ne olursa olsun false.
        assertFalse(Ipv6Gate.decide(gate = false, probeOk = true, sameNetwork = true, current = true))
        // Sonuc belli: o.
        assertTrue(Ipv6Gate.decide(gate = true, probeOk = true, sameNetwork = false, current = false))
        assertFalse(Ipv6Gate.decide(gate = true, probeOk = false, sameNetwork = true, current = true))
        // Deneme suruyor: ayni agda eski deger korunur (gizlilik adresi donunce kapanip acilmasin)...
        assertTrue(Ipv6Gate.decide(gate = true, probeOk = null, sameNetwork = true, current = true))
        assertFalse(Ipv6Gate.decide(gate = true, probeOk = null, sameNetwork = true, current = false))
        // ...yeni agda sonuc gelene kadar kapali.
        assertFalse(Ipv6Gate.decide(gate = true, probeOk = null, sameNetwork = false, current = true))
    }

    @Test
    fun effectiveNeedsSettingAndNetwork() {
        assertTrue(Ipv6Gate.effective(setting = true, underlyingUsable = true))
        assertFalse(Ipv6Gate.effective(setting = true, underlyingUsable = false))
        assertFalse(Ipv6Gate.effective(setting = false, underlyingUsable = true))
    }

    @Test
    fun withUnderlyingV6NeverEnablesAndDropsAllV6() {
        val on = cfg(ipv6 = true)
        assertSame(on, on.withUnderlyingV6(true))
        assertFalse(cfg(ipv6 = false).withUnderlyingV6(true).ipv6)

        val off = on.withUnderlyingV6(false)
        assertFalse(off.ipv6)
        // IPv6 sanal cozucu ve onun --redirect'i yok; IPv4 yonlendirmesi duruyor.
        assertNull(off.dnsTargetV6)
        assertEquals(on.dnsTargetV4, off.dnsTargetV4)
        val argv = ByeDpiArgs.build(off)
        assertFalse(argv.any { it.startsWith("[${ByeDpiArgs.VIRTUAL_DNS_V6}]") })
        assertTrue(argv.any { it.startsWith("${ByeDpiArgs.VIRTUAL_DNS_V4}:53=") })
        assertTrue(ByeDpiArgs.build(on).any { it.startsWith("[${ByeDpiArgs.VIRTUAL_DNS_V6}]:53=") })
        // VPN'e yalnizca IPv4 sanal cozucu verilir.
        assertEquals(listOf(ip(ByeDpiArgs.VIRTUAL_DNS_V4)), VpnTunBuilder.dnsServers(off, emptyList()))
        // hev'e IPv6 adresi gitmez.
        assertFalse(HevConfig.yaml(10808, off.ipv6).contains("ipv6"))
        assertTrue(HevConfig.yaml(10808, on.ipv6).contains("ipv6: '${HevConfig.TUN_IPV6}'"))
        // Kapi degisince tun yeniden kurulur, motor da (runtimeKey).
        assertNotEquals(VpnTunBuilder.tunKey(on, emptyList()), VpnTunBuilder.tunKey(off, emptyList()))
        assertFalse(on.sameEngineAs(off))
    }

    @Test
    fun dnsOffWithoutV6DropsUnderlyingV6Servers() {
        val off = cfg(ipv6 = true, dns = DnsProfile.Off).withUnderlyingV6(false)
        val servers = VpnTunBuilder.dnsServers(off, listOf(ip("10.0.2.3"), ip("2001:db8::53")))
        assertEquals(listOf(ip("10.0.2.3")), servers)
    }

    @Test
    fun tunAddressIsGlobalAndDenied() {
        // Kuresel kapsam: RFC 6724'te IPv4'ten once (etiket 1, oncelik 40); ULA IPv4'u one aliyordu.
        assertTrue(Ipv6Gate.isGlobal(ip(HevConfig.TUN_IPV6)))
        // Sanal DNS ULA'da kaliyor (aile secimi DNS sunucusunun adresine bakmaz).
        assertEquals("fd00:6764:7069::53", ByeDpiArgs.VIRTUAL_DNS_V6)
        // Tun'un kendi blogu byedpi'de reddedilir (disari anlamsiz SYN gitmesin).
        val tunNet = Cidr.parse("2001:db8:6764:7069::/64")
        assertTrue(ByeDpiArgs.VIRTUAL_NETS.map(Cidr::parse).any { it == tunNet })
        assertTrue(tunNet.contains(Cidr.parse(HevConfig.TUN_IPV6).address))
        assertTrue(ByeDpiArgs.build(cfg(ipv6 = true)).containsAll(listOf("--deny-net", "2001:db8:6764:7069::/64")))
        // Yollar tun adresini kapsiyor (yerel ag haric tutulsa da).
        val addr = Cidr.parse(HevConfig.TUN_IPV6).address
        assertTrue(VpnRoutes.ipv6(excludeLan = true).any { it.contains(addr) })
    }
}
