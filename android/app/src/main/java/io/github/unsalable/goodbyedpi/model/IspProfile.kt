package io.github.unsalable.goodbyedpi.model

/**
 * Internet saglayicisina ozel hazir ayar. Her ISS'in DPI kutusu farkli davrandigi icin
 * birinde calisan yontem digerinde calismayabilir; burada her saglayici icin bilinen
 * calisan yontemler oneri sirasiyla tutulur (ilki onerilen).
 *
 * Kaynak: SplitWire-Turkey (cagritaskn) zapret / zapret2 Turkiye ISS hazir ayarlari ve
 * GoodbyeDPI-Turkey. Turk ISS'lerinin hepsi 53. porttaki DNS'i kaciriyor; bu yuzden hazir
 * ayarlar standart disi portlu Yandex DNS'i secer.
 */
data class IspProfile(
    val id: String,
    val name: String,
    val description: String,
    val methodIds: List<String>,
    /** Onerilen DNS (DnsProfile.id); null = DNS'e dokunma. */
    val dnsId: String?,
) {
    /** Onerilen yontem (listenin ilki). */
    val recommendedId: String
        get() = methodIds.first()

    val recommended: MethodPreset
        get() = MethodPreset.fromId(recommendedId)

    val methods: List<MethodPreset>
        get() = methodIds.map(MethodPreset::fromId)

    companion object {
        const val GENERAL_ID = "general"

        /** Belirli bir saglayici secilmemis: genel yontemler listelenir, DNS'e dokunulmaz. */
        @JvmField
        val General = IspProfile(
            GENERAL_ID,
            "Genel",
            "Sağlayıcıya özel ayar yok; genel yöntemler listelenir.",
            // Masaustundeki "checksum" root gerektiriyor; yerine Android'e ozel tlsrec.
            listOf("default", "fixedttl", "disorder", "tlsrec", "split"),
            dnsId = null,
        )

        @JvmField
        val TurkTelekom = IspProfile(
            "turktelekom",
            "Türk Telekom",
            "Önerilen: Ters sıra (sahte paket yok). Olmazsa Sahte TTL 4 / 3. Discord sesi için UDP desteği açık, DNS: Yandex.",
            listOf("disorder", "ttl4", "ttl3", "default"),
            DnsProfile.YANDEX_ID,
        )

        @JvmField
        val Superonline = IspProfile(
            "superonline",
            "Superonline",
            "Önerilen: Ters sıra. Olmazsa MD5 imzası / MD5 + TTL 3 / Sahte TTL 3. DNS: Yandex.",
            listOf("disorder", "md5sig", "md5ttl3", "ttl3"),
            DnsProfile.YANDEX_ID,
        )

        @JvmField
        val Vodafone = IspProfile(
            "vodafone",
            "Vodafone",
            "Önerilen: Bölünmüş sahte (TTL 5). Olmazsa Ters sıra / Varsayılan. DNS: Yandex.",
            listOf("fakesplit5", "disorder", "default"),
            DnsProfile.YANDEX_ID,
        )

        @JvmField
        val TurkNet = IspProfile(
            "turknet",
            "TürkNet",
            "Önerilen: Varsayılan (TTL 5). Olmazsa Sabit TTL / Ters sıra. DNS: Yandex.",
            listOf("default", "fixedttl", "disorder"),
            DnsProfile.YANDEX_ID,
        )

        @JvmField
        val Kablonet = IspProfile(
            "kablonet",
            "Kablonet",
            "Türksat Kablonet. Önerilen: Ters sıra. Olmazsa Sahte TTL 4 / Varsayılan. DNS: Yandex.",
            listOf("disorder", "ttl4", "default"),
            DnsProfile.YANDEX_ID,
        )

        @JvmField
        val TelekomMobil = IspProfile(
            "telekommobil",
            "TT Mobil",
            "Türk Telekom mobil hat / hotspot. Önerilen: Boş sahte (TTL 5). Olmazsa Ters sıra / Sahte TTL 4. DNS: Yandex.",
            listOf("zerofake", "disorder", "ttl4"),
            DnsProfile.YANDEX_ID,
        )

        @JvmField
        val TurkcellMobil = IspProfile(
            "turkcellmobil",
            "Turkcell Mobil",
            "Turkcell mobil hat / hotspot. Önerilen: Ters sıra. Olmazsa Varsayılan / Sahte TTL 3. DNS: Yandex.",
            listOf("disorder", "default", "ttl3"),
            DnsProfile.YANDEX_ID,
        )

        @JvmField
        val VodafoneMobil = IspProfile(
            "vodafonemobil",
            "Vodafone Mobil",
            "Vodafone mobil hat / hotspot. Önerilen: Ters sıra. Olmazsa Düz bölme / Bölünmüş sahte. DNS: Yandex.",
            listOf("disorder", "split2", "fakesplit5"),
            DnsProfile.YANDEX_ID,
        )

        @JvmField
        val all: List<IspProfile> = listOf(
            General, TurkTelekom, Superonline, Vodafone, TurkNet, Kablonet,
            TelekomMobil, TurkcellMobil, VodafoneMobil,
        )

        fun fromId(id: String?): IspProfile =
            all.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: General
    }
}
