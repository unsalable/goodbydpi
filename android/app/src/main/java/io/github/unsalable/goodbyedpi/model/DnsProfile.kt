package io.github.unsalable.goodbyedpi.model

/**
 * DNS yonlendirmesi. Uygulamalar tun icindeki sanal cozucuye sorar; byedpi sorguyu buradaki
 * sunucuya ve PORTA iletir. Port onemli: Turk ISS'leri 53. portu kaciriyor, Yandex'in 1253'u
 * bundan kurtuluyor.
 */
data class DnsProfile(
    val id: String,
    val name: String,
    val description: String,
    val v4Addr: String?,
    val v4Port: Int,
    val v6Addr: String?,
    val v6Port: Int,
) {
    /**
     * Esitlik yalnizca kimlige bakar. Ozel girisler adres degistikce yeniden uretiliyor;
     * tum alanlari karsilastirmak secili ogenin her duzenlemede kaybolmasina yol aciyordu.
     */
    override fun equals(other: Any?): Boolean =
        other is DnsProfile && id.equals(other.id, ignoreCase = true)

    override fun hashCode(): Int = id.lowercase().hashCode()

    /** DNS yonlendirmesi acik mi (en az bir adres tanimli mi)? */
    val isActive: Boolean
        get() = !v4Addr.isNullOrBlank() || !v6Addr.isNullOrBlank()

    /** Adres metinlerinin gecerli IP olup olmadigini denetler; hata yoksa null. */
    fun validate(): String? {
        if (!isActive) return null

        val v4 = v4Addr?.trim()?.takeIf { it.isNotEmpty() }
        val v6 = v6Addr?.trim()?.takeIf { it.isNotEmpty() }

        if (v4 != null && !IpLiterals.isIpv4(v4)) return "Geçersiz IPv4 adresi."
        if (v6 != null && !IpLiterals.isIpv6(v6)) return "Geçersiz IPv6 adresi."
        if (v4Port !in 0..65535 || v6Port !in 0..65535) return "Port 0-65535 aralığında olmalı."

        return null
    }

    companion object {
        const val CLOUDFLARE_ID = "cloudflare"
        const val YANDEX_ID = "yandex"
        const val OFF_ID = "off"

        /**
         * Cloudflare yalnizca 53. portta hizmet verir. Hizli ve gizlilik dostudur ama
         * ISS 53. portu kaciriyorsa yonlendirme etkisiz kalir.
         */
        @JvmField
        val Cloudflare = DnsProfile(
            CLOUDFLARE_ID,
            "Cloudflare",
            "1.1.1.1:53 — hızlı, ancak ISS 53. portu yönlendiriyorsa etkisiz kalabilir.",
            "1.1.1.1", 53, "2606:4700:4700::1111", 53,
        )

        /**
         * Turkiye icin asil ise yarayan secenek: 1253 standart disi bir port oldugu icin
         * ISS'in 53. porttaki DNS kacirmasindan kurtulur.
         */
        @JvmField
        val Yandex = DnsProfile(
            YANDEX_ID,
            "Yandex (1253)",
            "77.88.8.8:1253 — standart dışı port, ISS DNS yönlendirmesini aşar.",
            "77.88.8.8", 1253, "2a02:6b8::feed:0ff", 1253,
        )

        /** Yonlendirme yok: ag kendi DNS sunucularini kullanir (tun yine de uzerinden gecer). */
        @JvmField
        val Off = DnsProfile(
            OFF_ID,
            "Kapalı",
            "DNS'e dokunulmaz, yalnızca DPI atlatma yapılır.",
            null, 0, null, 0,
        )

        /** Yerlesik (kullanicinin duzenlemedigi) profiller. */
        @JvmField
        val builtIn: List<DnsProfile> = listOf(Cloudflare, Yandex, Off)

        /**
         * Kimlige gore yerlesik profili bulur. Kullanicinin kendi girdigi sunucular ayar
         * dosyasindaki listede tutuldugu icin onlari cagiran taraf cozer (Selection.selectedDns).
         */
        fun fromId(id: String?): DnsProfile =
            builtIn.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: Cloudflare

        /** Kullanicinin girdigi adres/porttan adlandirilmis bir "Ozel" profil olusturur. */
        fun createCustom(
            id: String,
            name: String,
            v4Addr: String?,
            v4Port: Int,
            v6Addr: String?,
            v6Port: Int,
        ): DnsProfile {
            val v4 = v4Addr?.trim()?.takeIf { it.isNotEmpty() }
            val v6 = v6Addr?.trim()?.takeIf { it.isNotEmpty() }
            val p4 = if (v4Port <= 0) 53 else v4Port
            val p6 = if (v6Port <= 0) 53 else v6Port

            val summary = when {
                v4 != null -> "$v4:$p4"
                v6 != null -> "[$v6]:$p6"
                else -> "adres girilmedi"
            }

            return DnsProfile(id, name, "Kendi DNS sunucun: $summary", v4, p4, v6, p6)
        }
    }
}

/**
 * IP adresi metni denetimi. InetAddress.getByName kullanilmiyor: ad verilirse DNS sorgusu
 * yapar (ana is parcaciginda NetworkOnMainThread, VPN kurulurken de kendi tun'umuza sorar)
 * ve "01.2.3.4" gibi belirsiz yazimlari sessizce kabul eder.
 */
internal object IpLiterals {
    /** Nokta ile ayrilmis dort ondalik bayt; bastaki sifir (sekizlik belirsizligi) reddedilir. */
    fun isIpv4(s: String): Boolean {
        val parts = s.split('.')
        if (parts.size != 4) return false
        return parts.all { p ->
            p.length in 1..3 &&
                p.all { it in '0'..'9' } &&
                (p.length == 1 || p[0] != '0') &&
                p.toInt() <= 255
        }
    }

    /**
     * RFC 4291 metin bicimi: 8 onaltilik grup, en fazla bir "::" kisaltmasi, sonda gomulu
     * IPv4 (::ffff:1.2.3.4) olabilir. Bolge kimligi (%wlan0) ve koseli parantez reddedilir:
     * motora giden adres tek basina bir IP olmali.
     */
    fun isIpv6(s: String): Boolean {
        if (s.isEmpty() || s.length > 45) return false
        if (s.any { !(it.isAsciiHex() || it == ':' || it == '.') }) return false

        var body = s
        var extraGroups = 0

        if ('.' in s) {
            val lastColon = s.lastIndexOf(':')
            if (lastColon < 0 || !isIpv4(s.substring(lastColon + 1))) return false
            body = s.substring(0, lastColon + 1)
            // "::1.2.3.4" -> "::", "::ffff:1.2.3.4" -> "::ffff"
            if (!body.endsWith("::")) body = body.dropLast(1)
            extraGroups = 2
        }

        val dbl = body.indexOf("::")
        if (dbl < 0) {
            val groups = body.split(':')
            return groups.size + extraGroups == 8 && groups.all(::isHexGroup)
        }

        // Ikinci bir "::" (":::" dahil) gecersiz.
        if (body.indexOf("::", dbl + 1) >= 0) return false

        val left = body.substring(0, dbl)
        val right = body.substring(dbl + 2)
        val l = if (left.isEmpty()) emptyList() else left.split(':')
        val r = if (right.isEmpty()) emptyList() else right.split(':')
        if (!l.all(::isHexGroup) || !r.all(::isHexGroup)) return false

        // "::" en az bir sifir grubunun yerini tutar.
        return l.size + r.size + extraGroups <= 7
    }

    private fun isHexGroup(g: String): Boolean = g.length in 1..4 && g.all { it.isAsciiHex() }

    private fun Char.isAsciiHex(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
