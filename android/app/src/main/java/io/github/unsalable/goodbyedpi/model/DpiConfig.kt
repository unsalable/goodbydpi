package io.github.unsalable.goodbyedpi.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Sahte istegin icerigi. */
@Serializable
enum class FakePayload {
    /** Engelsiz siteye (fakeSni) ait gecerli gorunumlu TLS ClientHello / HTTP istegi. */
    @SerialName("TLS")
    TLS,

    /** Birkac sifir bayt. DPI akisi taniyamaz, "bilinmeyen protokol" sayip birakir. */
    @SerialName("ZEROS")
    ZEROS,
}

/**
 * DPI atlatma motorunun (byedpi) davranis ayarlari; masaustu NativeDpiConfig'in karsiligi.
 *
 * Hazir yontemlerin yani sira kullanicinin adlandirdigi ozel profiller de bu tiple tasinir,
 * JSON adlari anlamin ayni oldugu yerde masaustuyle ayni. Degismez: duzenleme copy() ile.
 *
 * Masaustunden farki: root olmadan ham paket yazilamadigi icin otomatik TTL (SYN-ACK'tan
 * olcum), yanlis saglama toplami, yanlis SEQ ve sira ortusmesi (seqovl) burada yok.
 * [fakeTtl] sahte paketin dusuk TTL ile korunmasi demek; TTL her zaman [ttl] degeridir.
 */
@Serializable
data class DpiConfig(
    // ------------------------------------------------------------ sahte paket

    /** Gercek istekten once engelsiz bir siteye ait sahte istek gonder. */
    val fakePacket: Boolean = true,
    /** Sahte paket dusuk TTL ile gitsin (DPI gorur, sunucuya ulasmaz). */
    val fakeTtl: Boolean = true,
    /** Sahte paketin IP TTL degeri. */
    val ttl: Int = DEFAULT_TTL,
    /** Sahte pakete TCP MD5 imzasi eklensin; MD5 anahtari olmayan sunucu paketi atar. */
    val fakeMd5Sig: Boolean = false,
    val fakePayload: FakePayload = FakePayload.TLS,
    /** Sahte TLS isteginde gorunen engelsiz ad. */
    val fakeSni: String = DEFAULT_FAKE_SNI,
    /** Sahte istegin kendisi de iki parca halinde gitsin. */
    val splitFake: Boolean = false,

    // ------------------------------------------------------------------ bolme

    /** Gercek TLS ClientHello'yu TCP parcalarina bol. */
    val splitTls: Boolean = true,
    /** Ilk parca gec ulasacak sekilde gonder (byedpi disorder); segment birlestiremeyen DPI'lari asar. */
    val reverseSplit: Boolean = true,
    /** Sabit bolme konumu (istek icindeki bayt ofseti). */
    val splitPosition: Int = DEFAULT_SPLIT_POSITION,
    /** Ayrica SNI adinin ortasindan bol: ad hicbir parcada tam gorunmez. */
    val splitSni: Boolean = true,
    /** ClientHello'yu iki TLS kaydina bol (byedpi tlsrec); masaustunde yok, Android'e ozel. */
    val tlsRecordSplit: Boolean = false,

    // ------------------------------------------------------------------ diger

    /** QUIC/HTTP3 (UDP 443) paketlerini dusur; uygulamalar TCP+TLS'e duser. */
    val blockQuic: Boolean = true,
    /** HTTP (port 80) isteklerine de ayni teknikleri uygula. */
    val fragmentHttp: Boolean = true,
    /** Discord ses / STUN paketlerinden once sahte UDP paketleri gonder. */
    val voiceFake: Boolean = true,
    /** Ses paketinden once gonderilecek sahte UDP paketi sayisi. */
    val voiceFakeRepeats: Int = DEFAULT_VOICE_REPEATS,
) {
    /**
     * Sahte paketi koruyan en az bir yontem (TTL / MD5) secili mi? Hicbiri yoksa sahte istek
     * sunucuya ulasip baglantiyi bozar; motor bu durumda TTL'e duser. Bunu burada (sanitized)
     * duzeltmiyoruz: duzenleyicide iki anahtar birden kapatilinca biri kendiliginden geri
     * acilirsa kullanici ne oldugunu anlamaz.
     */
    val hasFakeProtection: Boolean
        get() = fakeTtl || fakeMd5Sig

    /**
     * Listede profilin altinda gorunen kisa ozet: hangi tekniklerin acik oldugu.
     * Kullanicinin adlandirdigi profiller birbirinden ancak bu satirla ayirt edilebiliyor.
     */
    val summary: String
        get() {
            val parts = ArrayList<String>(5)

            if (fakePacket) {
                val how = when {
                    fakeTtl && fakeMd5Sig -> "TTL $ttl + MD5"
                    fakeTtl -> "TTL $ttl"
                    fakeMd5Sig -> "MD5"
                    else -> "korumasız"
                }
                val payload = if (fakePayload == FakePayload.ZEROS) "boş sahte" else "sahte paket"
                parts += if (splitFake) "$payload ($how, bölünmüş)" else "$payload ($how)"
            }

            if (splitTls) parts += if (reverseSplit) "ters sıra bölme" else "bölme"
            if (tlsRecordSplit) parts += "TLS kayıt bölme"
            if (blockQuic) parts += "QUIC engeli"
            if (voiceFake) parts += "Discord ses"

            return if (parts.isEmpty()) "Atlatma tekniği seçilmedi." else parts.joinToString(" · ")
        }

    /**
     * Sayilari gecerli araliga ceker, bos/garip sahte SNI'yi varsayilana dondurur. Elle
     * duzenlenmis ya da eski bir ayar dosyasi motora anlamsiz arguman gondermesin diye
     * hem yuklemede hem motora vermeden once cagrilir. Mantiksal secimlere dokunmaz.
     */
    fun sanitized(): DpiConfig {
        val sni = fakeSni.trim()
        return copy(
            ttl = ttl.coerceIn(TTL_RANGE),
            splitPosition = splitPosition.coerceIn(SPLIT_RANGE),
            voiceFakeRepeats = voiceFakeRepeats.coerceIn(REPEATS_RANGE),
            fakeSni = if (isValidSni(sni)) sni else DEFAULT_FAKE_SNI,
        )
    }

    companion object {
        const val DEFAULT_TTL = 5
        const val DEFAULT_SPLIT_POSITION = 2
        const val DEFAULT_VOICE_REPEATS = 6
        const val DEFAULT_FAKE_SNI = "www.w3.org"

        @JvmField val TTL_RANGE: IntRange = 1..64

        /**
         * Masaustunde 0 "sabit konumdan bolme" demekti. byedpi'de SNI bolmesi ayri bir
         * konum oldugu icin sabit konum her zaman gecerli bir bayt ofseti (1..64) tutulur;
         * yalnizca SNI'den bolmek isteyen yine de 1 konumunu secip splitSni'yi acabilir.
         */
        @JvmField val SPLIT_RANGE: IntRange = 1..64

        @JvmField val REPEATS_RANGE: IntRange = 1..20

        // Arguman olarak motora gidiyor: bosluk ya da kabuk karakteri tasimasin.
        private fun isValidSni(s: String): Boolean =
            s.isNotEmpty() && s.length <= 253 && !s.startsWith('.') && !s.endsWith('.') &&
                s.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '-' }
    }
}
