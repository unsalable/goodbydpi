package io.github.unsalable.goodbyedpi.engine

import java.math.BigInteger

/** Tek bir IP blogu. [address] ag adresi (konak bitleri sifir), [bits] 32 (IPv4) ya da 128. */
data class Cidr(val address: BigInteger, val prefix: Int, val bits: Int) {
    init {
        require(bits == 32 || bits == 128) { "bits: $bits" }
        require(prefix in 0..bits) { "prefix: $prefix" }
        require(address.signum() >= 0 && address.bitLength() <= bits) { "address" }
        require(address == address.and(mask(prefix, bits))) { "konak bitleri sifir degil: $this" }
    }

    val isV6: Boolean get() = bits == 128

    /** Blogun ilk adresi. */
    val first: BigInteger get() = address

    /** Blogun son adresi (dahil). */
    val last: BigInteger get() = address + BigInteger.ONE.shiftLeft(bits - prefix) - BigInteger.ONE

    /** VpnService.Builder.addRoute'un bekledigi adres metni. */
    val host: String get() = format(address, bits)

    fun contains(ip: BigInteger): Boolean = ip >= first && ip <= last

    override fun toString(): String = "$host/$prefix"

    companion object {
        /** "10.0.0.0/8", "fc00::/7" ya da tek adres ("198.18.0.53" -> /32). */
        fun parse(text: String): Cidr {
            val slash = text.indexOf('/')
            val ip = if (slash < 0) text else text.substring(0, slash)
            val v6 = ':' in ip
            val bits = if (v6) 128 else 32
            val prefix = if (slash < 0) bits else text.substring(slash + 1).toInt()
            return Cidr(if (v6) parseV6(ip) else parseV4(ip), prefix, bits)
        }

        internal fun mask(prefix: Int, bits: Int): BigInteger {
            val all = BigInteger.ONE.shiftLeft(bits) - BigInteger.ONE
            val host = BigInteger.ONE.shiftLeft(bits - prefix) - BigInteger.ONE
            return all.andNot(host)
        }

        internal fun parseV4(s: String): BigInteger {
            val p = s.split('.')
            require(p.size == 4) { "IPv4: $s" }
            var v = 0L
            for (x in p) {
                val n = x.toInt()
                require(n in 0..255) { "IPv4: $s" }
                v = (v shl 8) or n.toLong()
            }
            return BigInteger.valueOf(v)
        }

        internal fun parseV6(s: String): BigInteger {
            val dbl = s.indexOf("::")
            val groups: List<String> = if (dbl < 0) {
                s.split(':')
            } else {
                val l = s.substring(0, dbl).let { if (it.isEmpty()) emptyList() else it.split(':') }
                val r = s.substring(dbl + 2).let { if (it.isEmpty()) emptyList() else it.split(':') }
                l + List(8 - l.size - r.size) { "0" } + r
            }
            require(groups.size == 8) { "IPv6: $s" }
            var v = BigInteger.ZERO
            for (g in groups) {
                val n = g.toInt(16)
                require(n in 0..0xffff) { "IPv6: $s" }
                v = v.shiftLeft(16).or(BigInteger.valueOf(n.toLong()))
            }
            return v
        }

        // Tam 8 grup, bastaki sifirlar atilmis: Android'in sayisal adres ayristiricisi kabul eder,
        // "::" kisaltmasi gereksiz karmasiklik olurdu.
        internal fun format(v: BigInteger, bits: Int): String =
            if (bits == 32) {
                val x = v.toLong()
                "${(x shr 24) and 255}.${(x shr 16) and 255}.${(x shr 8) and 255}.${x and 255}"
            } else {
                (7 downTo 0).joinToString(":") { i ->
                    v.shiftRight(i * 16).and(BigInteger.valueOf(0xffff)).toLong().toString(16)
                }
            }
    }
}

/**
 * VPN'e verilecek yollar. "Yerel agi haric tut" acikken tum adres uzayindan yerel/ozel bloklar
 * cikarilip kalan en az sayida CIDR'a bolunur. Android 13'teki excludeRoute yerine tum surumlerde
 * ayni kod yolu: davranis cihazdan cihaza degismesin ve saf Kotlin olarak JVM'de sinansin.
 *
 * Cikarilan bloklar tun'a hic girmez, alttaki agdan dogrudan gider: yazici, NAS, Chromecast,
 * modem arayuzu, 255.255.255.255'e yayinla kesif calismaya devam eder.
 *
 * 100.64.0.0/10 (CGNAT, RFC 6598) de cikarilir: operatorun kendi ic agi (mobil hatlarin kendi
 * adresi, bazi operator DNS/portal adresleri, tethering). Orada DPI yok ve atlatilacak bir engel
 * yok; tun'dan gecirmek yalnizca gecikme ve fd maliyeti ekler.
 *
 * Tun'un kendi bloklari (198.18.0.0/15 ve fd00:6764:7069::/64, sanal DNS dahil) cikarilan bir
 * blokla cakissa bile her zaman kapsanir: fd00::/8 fc00::/7'nin icinde.
 */
object VpnRoutes {
    val LAN_V4: List<Cidr> = listOf(
        "10.0.0.0/8",
        "100.64.0.0/10",
        "169.254.0.0/16",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "224.0.0.0/4",
        // E sinifi ayrilmis blok; icinde sinirli yayin 255.255.255.255 var. Tun'a girerse yayinla
        // yapilan yerel kesif (LIFX, Tuya kurulumu vb.) byedpi'de SO_BROADCAST'siz sendto ile
        // olur. Internette kullanilmiyor; 224/4 ile birlikte 224/3 (WireGuard da boyle yapar).
        "240.0.0.0/4",
    ).map(Cidr::parse)

    val LAN_V6: List<Cidr> = listOf(
        "fc00::/7",
        "fe80::/10",
        "ff00::/8",
    ).map(Cidr::parse)

    /** Tun ve sanal DNS; yerel ag cikarilirken bile yollarda kalmali. */
    val TUN_V4: List<Cidr> = listOf(Cidr.parse("198.18.0.0/15"))
    val TUN_V6: List<Cidr> = listOf(Cidr.parse("fd00:6764:7069::/64"))

    fun ipv4(excludeLan: Boolean): List<Cidr> =
        if (excludeLan) complement(32, LAN_V4, TUN_V4) else listOf(Cidr(BigInteger.ZERO, 0, 32))

    fun ipv6(excludeLan: Boolean): List<Cidr> =
        if (excludeLan) complement(128, LAN_V6, TUN_V6) else listOf(Cidr(BigInteger.ZERO, 0, 128))

    /**
     * Tum uzay eksi ([excluded] eksi [keep]); sonuc sirali, cakismasiz ve her aralik icin en az
     * sayida CIDR.
     */
    internal fun complement(bits: Int, excluded: List<Cidr>, keep: List<Cidr>): List<Cidr> {
        val top = BigInteger.ONE.shiftLeft(bits) - BigInteger.ONE
        // Once disarida kalacak araliklar: excluded'dan keep'i oyuyoruz.
        var out = merge(excluded.map { it.first to it.last })
        for (k in keep) out = subtract(out, k.first, k.last)

        val result = ArrayList<Cidr>()
        var cur = BigInteger.ZERO
        for ((a, b) in out) {
            if (a > cur) result += rangeToCidrs(cur, a - BigInteger.ONE, bits)
            cur = b + BigInteger.ONE
        }
        if (cur <= top) result += rangeToCidrs(cur, top, bits)
        return result
    }

    private fun merge(ranges: List<Pair<BigInteger, BigInteger>>): List<Pair<BigInteger, BigInteger>> {
        val sorted = ranges.sortedBy { it.first }
        val out = ArrayList<Pair<BigInteger, BigInteger>>()
        for (r in sorted) {
            val last = out.lastOrNull()
            if (last != null && r.first <= last.second + BigInteger.ONE) {
                out[out.size - 1] = last.first to maxOf(last.second, r.second)
            } else {
                out += r
            }
        }
        return out
    }

    private fun subtract(
        ranges: List<Pair<BigInteger, BigInteger>>,
        a: BigInteger,
        b: BigInteger,
    ): List<Pair<BigInteger, BigInteger>> {
        val out = ArrayList<Pair<BigInteger, BigInteger>>()
        for ((x, y) in ranges) {
            if (y < a || x > b) {
                out += x to y
                continue
            }
            if (x < a) out += x to (a - BigInteger.ONE)
            if (y > b) out += (b + BigInteger.ONE) to y
        }
        return out
    }

    /** [a, b] araligini en az sayida hizali CIDR'a boler (klasik "range to prefix" algoritmasi). */
    internal fun rangeToCidrs(a: BigInteger, b: BigInteger, bits: Int): List<Cidr> {
        val out = ArrayList<Cidr>()
        var start = a
        while (start <= b) {
            // Baslangicin hizasinin izin verdigi en buyuk blok...
            var size = if (start.signum() == 0) bits else start.lowestSetBit.coerceAtMost(bits)
            // ...araligin disina tasmayacak kadar kucultulur.
            while (size > 0 && start + BigInteger.ONE.shiftLeft(size) - BigInteger.ONE > b) size--
            out += Cidr(start, bits - size, bits)
            start += BigInteger.ONE.shiftLeft(size)
        }
        return out
    }
}
