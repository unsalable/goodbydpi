package io.github.unsalable.goodbyedpi.model

/**
 * Secilebilir hazir yontem. Her biri bir [DpiConfig] uretir; hangi yontemin hangi internet
 * saglayicisinda onerildigi [IspProfile] icinde tanimli. Kullanicinin ozel profilleri de
 * listede ayni tiple gorunur (CustomMethodProfile.toPreset).
 *
 * Masaustundeki yontemlerin root gerektiren parcalari (otomatik TTL, yanlis saglama/SEQ,
 * seqovl) Android'de yok; aciklamalar bunu saklamadan en yakin byedpi karsiligini anlatir.
 */
data class MethodPreset(
    val id: String,
    val name: String,
    val description: String,
    val build: () -> DpiConfig,
) {
    /**
     * Esitlik yalnizca kimlige bakar. Ozel profiller her ayar degisiminde yeniden uretiliyor;
     * lambda'yi da karsilastiran varsayilan esitlik secili ogeyi her seferinde kaybettirirdi.
     */
    override fun equals(other: Any?): Boolean =
        other is MethodPreset && id.equals(other.id, ignoreCase = true)

    override fun hashCode(): Int = id.lowercase().hashCode()

    override fun toString(): String = "MethodPreset($id, $name)"

    companion object {
        const val DEFAULT_ID = "default"

        // Kaynak: SplitWire-Turkey (cagritaskn) zapret / zapret2 Turkiye ISS hazir ayarlari,
        // byedpi seceneklerine cevrilmis hali. Parantezdeki karsiliklar aciklamada yazili.

        @JvmField
        val Default = MethodPreset(
            DEFAULT_ID,
            "Varsayılan",
            "Sahte paket (TTL 5) + ters sıra bölme + QUIC engeli + Discord ses desteği.",
        ) { DpiConfig() }

        @JvmField
        val FixedTtl = MethodPreset(
            "fixedttl",
            "Sabit TTL",
            "Sahte paket TTL 5 ile gider, istek bölünmez (GoodbyeDPI-Turkey \"--set-ttl 5\" karşılığı).",
        ) { DpiConfig(splitTls = false, splitSni = false) }

        /**
         * Masaustunde seqovl=1 de vardi: ikinci parcanin onune 1 cop bayt eklenip SEQ bir geri
         * cekiliyordu, segmentleri birlestiren DPI bozuk bir ClientHello goruyordu. Cekirdek TCP
         * soketiyle ayni sira araligina farkli icerik yazilamaz (SEQ'i cekirdek belirler), bu
         * yuzden ortusme yok; en yakin karsilik "ilk kopyayi DPI gorur, sunucu gormez" etkisini
         * saglayan sahte paket yontemleri. Bu nedenle ISS listelerinde onerilen degil, yedek.
         */
        @JvmField
        val Disorder = MethodPreset(
            "disorder",
            "Ters sıra",
            "Sahte paket yok: istek 2. bayttan bölünür, ilk parça geç ulaşacak şekilde gönderilir (byedpi disorder). " +
                "Masaüstündeki sıra örtüşmesi (seqovl) Android'de yapılamadığı için masaüstündeki \"Ters sıra\"dan zayıf olabilir.",
        ) {
            DpiConfig(
                fakePacket = false,
                splitTls = true,
                reverseSplit = true,
                splitPosition = 2,
                splitSni = false,
            )
        }

        @JvmField
        val FakeTtl4 = MethodPreset(
            "ttl4",
            "Sahte TTL 4",
            "Sahte paket TTL 4 ile gider, istek bölünmez (zapret fake ttl=4).",
        ) { DpiConfig(ttl = 4, splitTls = false, splitSni = false) }

        @JvmField
        val FakeTtl3 = MethodPreset(
            "ttl3",
            "Sahte TTL 3",
            "Sahte paket TTL 3 ile gider, istek bölünmez (zapret fake ttl=3).",
        ) { DpiConfig(ttl = 3, splitTls = false, splitSni = false) }

        @JvmField
        val Md5Sig = MethodPreset(
            "md5sig",
            "MD5 imzası",
            "Sahte paket TCP MD5 imzası ve TTL 5 ile gider (zapret fake md5sig). Çekirdek MD5'i desteklemiyorsa " +
                "(yeni Android sürümlerinin çoğu) yalnızca TTL 5 kullanılır; o zaman Sabit TTL ile aynıdır.",
        ) { DpiConfig(fakeTtl = false, fakeMd5Sig = true, splitTls = false, splitSni = false) }

        @JvmField
        val Md5Ttl3 = MethodPreset(
            "md5ttl3",
            "MD5 + TTL 3",
            "Sahte paket hem MD5 imzası hem TTL 3 ile gider (zapret fake md5sig ttl=3). Çekirdek MD5'i " +
                "desteklemiyorsa Sahte TTL 3 ile aynıdır.",
        ) { DpiConfig(ttl = 3, fakeMd5Sig = true, splitTls = false, splitSni = false) }

        @JvmField
        val SplitFake5 = MethodPreset(
            "fakesplit5",
            "Bölünmüş sahte",
            "Sahte istek 2. bayttan iki parça halinde TTL 5 ile gider, ikinci parça sahteyi kaldığı yerden sürdürür; " +
                "gerçek istek değişmez (zapret2 multisplit blob=fake pos=2 ip_ttl=5).",
        ) { DpiConfig(ttl = 5, splitFake = true, splitTls = false, splitSni = false) }

        @JvmField
        val ZeroFake = MethodPreset(
            "zerofake",
            "Boş sahte",
            "Sahte paket olarak sıfır baytlar TTL 5 ile gider; DPI akışı tanıyamaz (zapret2 fake blob=0x00000000 ip_ttl=5).",
        ) { DpiConfig(ttl = 5, fakePayload = FakePayload.ZEROS, splitTls = false, splitSni = false) }

        @JvmField
        val PlainSplit = MethodPreset(
            "split2",
            "Düz bölme",
            "Sahte paket yok: istek 2. bayttan bölünür, parçalar normal sırada gider (zapret multisplit pos=2).",
        ) {
            DpiConfig(
                fakePacket = false,
                splitTls = true,
                reverseSplit = false,
                splitPosition = 2,
                splitSni = false,
            )
        }

        @JvmField
        val SplitOnly = MethodPreset(
            "split",
            "Sadece bölme",
            "Sahte paket yok: istek 2. bayttan ve SNI adının ortasından bölünür; çoğu ISS'de tek başına yetmez.",
        ) {
            DpiConfig(
                fakePacket = false,
                splitTls = true,
                reverseSplit = false,
                splitPosition = 2,
                splitSni = true,
            )
        }

        /** Masaustundeki "Yanlis saglama"nin yerini alir (Genel listesinde). */
        @JvmField
        val TlsRec = MethodPreset(
            "tlsrec",
            "TLS kayıt bölme",
            "Sahte paket yok: TLS isteği iki ayrı TLS kaydına bölünür, TCP düzeyinde bölme gerekmez (byedpi tlsrec).",
        ) { DpiConfig(fakePacket = false, tlsRecordSplit = true, splitTls = false, splitSni = false) }

        /** Tum hazir yontemler (ayar dosyasindaki kimligi cozmek icin), gosterim sirasiyla. */
        @JvmField
        val all: List<MethodPreset> = listOf(
            Default, FixedTtl, Disorder, FakeTtl4, FakeTtl3, Md5Sig, Md5Ttl3,
            SplitFake5, ZeroFake, PlainSplit, SplitOnly, TlsRec,
        )

        /**
         * Kimlige gore hazir yontemi bulur (buyuk/kucuk harf duyarsiz). Masaustunden gelen
         * "checksum" ve bilinmeyen kimlikler Varsayilan'a duser; ozel profil kimliklerini
         * ayarlari bilen taraf cozer (Selection.selectedMethod).
         */
        fun fromId(id: String?): MethodPreset =
            all.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: Default
    }
}
