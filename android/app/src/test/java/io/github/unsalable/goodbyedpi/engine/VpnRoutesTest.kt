package io.github.unsalable.goodbyedpi.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class VpnRoutesTest {
    private fun space(bits: Int): BigInteger = BigInteger.ONE.shiftLeft(bits)

    private fun size(c: Cidr): BigInteger = BigInteger.ONE.shiftLeft(c.bits - c.prefix)

    private fun covered(routes: List<Cidr>, ip: String): Boolean {
        val c = Cidr.parse(ip)
        return routes.any { it.contains(c.address) }
    }

    /** Sirali, cakismasiz ve [excluded - keep] ile birlesince tum uzay. */
    private fun assertPartition(bits: Int, routes: List<Cidr>, excluded: List<Cidr>, keep: List<Cidr>) {
        // Siralama ve cakismazlik.
        routes.zipWithNext().forEach { (a, b) -> assertTrue("$a / $b", a.last < b.first) }
        // Rota + disarida kalan = tum uzay: disarida kalanlarin toplami ile rotalarin toplami.
        val routeTotal = routes.fold(BigInteger.ZERO) { s, c -> s + size(c) }
        var outRanges = excluded.map { it.first to it.last }
        for (k in keep) {
            outRanges = outRanges.flatMap { (x, y) ->
                if (y < k.first || x > k.last) listOf(x to y)
                else listOfNotNull(
                    if (x < k.first) x to k.first - BigInteger.ONE else null,
                    if (y > k.last) k.last + BigInteger.ONE to y else null,
                )
            }
        }
        val outTotal = outRanges.fold(BigInteger.ZERO) { s, (x, y) -> s + (y - x + BigInteger.ONE) }
        assertEquals(space(bits), routeTotal + outTotal)
        // Hicbir rota disarida kalan bir aralikla kesismez.
        for (r in routes) for ((x, y) in outRanges) assertTrue("$r kesisiyor", r.last < x || r.first > y)
    }

    @Test
    fun ipv4WithoutLanExclusionIsDefaultRoute() {
        assertEquals(listOf("0.0.0.0/0"), VpnRoutes.ipv4(excludeLan = false).map { it.toString() })
    }

    @Test
    fun ipv6WithoutLanExclusionIsDefaultRoute() {
        assertEquals(listOf("0:0:0:0:0:0:0:0/0"), VpnRoutes.ipv6(excludeLan = false).map { it.toString() })
    }

    @Test
    fun ipv4LanExcludedPartition() {
        val r = VpnRoutes.ipv4(excludeLan = true)
        assertPartition(32, r, VpnRoutes.LAN_V4, VpnRoutes.TUN_V4)
        for (ip in listOf("198.18.0.1", "198.18.0.53", "198.19.255.255", "1.1.1.1", "77.88.8.8", "8.8.8.8", "100.63.255.255", "100.128.0.0", "223.255.255.255", "172.32.0.0", "0.0.0.1")) {
            assertTrue(ip, covered(r, ip))
        }
        for (ip in listOf("10.0.2.3", "192.168.1.1", "172.16.0.1", "172.31.255.255", "169.254.1.1", "224.0.0.251", "239.255.255.250", "100.64.0.1", "100.127.255.255")) {
            assertFalse(ip, covered(r, ip))
        }
        // Uc esik: 240/4 (ayrilmis) ve 255.255.255.255 kapsanir; LAN listesinde yok.
        assertTrue(covered(r, "240.0.0.1"))
        assertTrue("${r.size} rota", r.size < 64)
    }

    @Test
    fun ipv6LanExcludedPartition() {
        val r = VpnRoutes.ipv6(excludeLan = true)
        assertPartition(128, r, VpnRoutes.LAN_V6, VpnRoutes.TUN_V6)
        for (ip in listOf("fd00:6764:7069::1", "fd00:6764:7069::53", "2001:4860:4860::8888", "2a02:6b8::feed:ff", "::1", "fbff:ffff::1", "fe00::1", "fec0::1")) {
            assertTrue(ip, covered(r, ip))
        }
        for (ip in listOf("fd12:3456::1", "fc00::1", "fe80::1", "febf:ffff::1", "ff02::1", "fd00:6764:706a::1")) {
            assertFalse(ip, covered(r, ip))
        }
        assertTrue("${r.size} rota", r.size < 150)
    }

    @Test
    fun routesAreAlignedAndFormattedForBuilder() {
        for (c in VpnRoutes.ipv4(true) + VpnRoutes.ipv6(true)) {
            // Cidr init konak bitlerini denetliyor; metin geri ayristirilinca ayni blok.
            assertEquals(c, Cidr.parse(c.toString()))
            assertFalse(c.host.contains("::"))
        }
    }

    @Test
    fun complementEdgeCases() {
        val all4 = VpnRoutes.complement(32, emptyList(), emptyList())
        assertEquals(listOf("0.0.0.0/0"), all4.map { it.toString() })
        val none = VpnRoutes.complement(32, listOf(Cidr.parse("0.0.0.0/0")), emptyList())
        assertTrue(none.isEmpty())
        // Cakisan/ic ice dislamalar birlestirilir.
        val r = VpnRoutes.complement(32, listOf(Cidr.parse("10.0.0.0/8"), Cidr.parse("10.1.0.0/16"), Cidr.parse("11.0.0.0/8")), emptyList())
        assertPartition(32, r, listOf(Cidr.parse("10.0.0.0/7")), emptyList())
        // keep, dislanan blogun ortasini delebilir.
        val k = VpnRoutes.complement(32, listOf(Cidr.parse("0.0.0.0/0")), listOf(Cidr.parse("198.18.0.0/15")))
        assertEquals(listOf("198.18.0.0/15"), k.map { it.toString() })
    }

    @Test
    fun rangeToCidrsMinimal() {
        val r = VpnRoutes.rangeToCidrs(Cidr.parseV4("10.0.0.1"), Cidr.parseV4("10.0.0.6"), 32)
        assertEquals(listOf("10.0.0.1/32", "10.0.0.2/31", "10.0.0.4/31", "10.0.0.6/32"), r.map { it.toString() })
    }

    @Test
    fun parseAndFormat() {
        assertEquals("fd00:6764:7069:0:0:0:0:53/128", Cidr.parse("fd00:6764:7069::53").toString())
        assertEquals("2a02:6b8:0:0:0:0:feed:ff/128", Cidr.parse("2a02:6b8::feed:0ff").toString())
        assertEquals("198.18.0.53/32", Cidr.parse("198.18.0.53").toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsHostBits() {
        Cidr.parse("10.0.0.1/8")
    }
}
