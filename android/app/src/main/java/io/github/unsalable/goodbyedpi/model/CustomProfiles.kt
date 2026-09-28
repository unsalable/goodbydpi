package io.github.unsalable.goodbyedpi.model

import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * Kullanicinin kendi olusturdugu profillerin kimlik ve ad kurallari (masaustuyle ayni).
 *
 * Ilk profil duz "custom" kimligini tasir (masaustunun eski ayar dosyalariyla ayni kural);
 * sonradan eklenenler "custom:" onekiyle benzersiz kimlik alir.
 */
object CustomIds {
    /** Ilk profilin kimligi. */
    const val LEGACY = "custom"

    /** Sonradan eklenen profillerin kimlik oneki. */
    const val PREFIX = "custom:"

    /** Kimlik kullanicinin duzenledigi bir profile mi ait? */
    fun isCustom(id: String?): Boolean =
        id != null && (id.equals(LEGACY, ignoreCase = true) || id.startsWith(PREFIX, ignoreCase = true))

    /** Listede kullanilmayan yeni bir kimlik uretir. */
    fun newId(taken: Collection<String>, random: () -> String = ::randomHex8): String {
        val used = taken.mapTo(HashSet()) { it.lowercase() }

        // Ilk profil hic yoksa duz kimlikle basla.
        if (LEGACY !in used) return LEGACY

        while (true) {
            val id = PREFIX + random()
            if (id.lowercase() !in used) return id
        }
    }

    /**
     * "Özel", "Özel 2", "Özel 3"... seklinde kullanilmayan bir ad uretir. "Özel 2" cogaltilinca
     * "Özel 2 2" degil "Özel 3" olur. Karsilastirma buyuk/kucuk harf duyarsiz.
     */
    fun newName(baseName: String, taken: Collection<String>): String {
        val used = taken.mapTo(HashSet()) { foldName(it) }
        if (foldName(baseName) !in used) return baseName

        val (root, start) = splitCounter(baseName)
        var i = start
        while (true) {
            val name = "$root $i"
            if (foldName(name) !in used) return name
            i++
        }
    }

    /** Bos ya da yalnizca bosluktan olusan adlari yedek ada dusurur. */
    fun cleanName(name: String?, fallback: String): String {
        val trimmed = name?.trim()
        return if (trimmed.isNullOrEmpty()) fallback else trimmed
    }

    /**
     * Ad karsilastirma anahtari. Turkce I/ı/İ/i ciftleri cihazin diline gore farkli
     * kuculdugu icin (tr'de "I" -> "ı", digerlerinde "i") dordu de ayni harf sayilir:
     * "ÖZEL", "Özel" ve "özel" ayni ad; "Dış"/"DIŞ" da oyle. Fazla kati olmanin bedeli
     * yalnizca "... 2" son eki, gevsek olmanin bedeli listede ayirt edilemeyen iki ad.
     */
    internal fun foldName(name: String): String =
        name.trim()
            .lowercase(java.util.Locale.ROOT)
            .replace("̇", "") // "İ".lowercase(ROOT) = "i" + birlestirici nokta
            .replace('ı', 'i')

    private fun splitCounter(name: String): Pair<String, Int> {
        val space = name.lastIndexOf(' ')
        if (space > 0) {
            val n = name.substring(space + 1).toIntOrNull()
            if (n != null && n > 1) return name.substring(0, space) to n + 1
        }
        return name to 2
    }

    private fun randomHex8(): String =
        Random.nextInt().toUInt().toString(16).padStart(8, '0')
}

/**
 * Kullanicinin adlandirdigi bir "Ozel" yontem profili. Ayar dosyasinda liste olarak saklanir;
 * kullanici istedigi kadar profil olusturup aralarinda gecis yapar.
 */
@Serializable
data class CustomMethodProfile(
    val id: String = CustomIds.LEGACY,
    val name: String = DEFAULT_NAME,
    val config: DpiConfig = DpiConfig(),
) {
    /** Listede gosterilecek MethodPreset karsiligi; aciklama olarak tekniklerin ozeti. */
    fun toPreset(): MethodPreset {
        val cfg = config
        return MethodPreset(id, name, cfg.summary) { cfg }
    }

    companion object {
        const val DEFAULT_NAME = "Özel"

        /** Listeye eklenecek, kimligi ve adi benzersiz yeni bir profil uretir. */
        fun createNew(
            existing: List<CustomMethodProfile>,
            seed: DpiConfig? = null,
            name: String? = null,
        ): CustomMethodProfile =
            CustomMethodProfile(
                id = CustomIds.newId(existing.map { it.id }),
                name = CustomIds.newName(CustomIds.cleanName(name, DEFAULT_NAME), existing.map { it.name }),
                config = seed ?: DpiConfig(),
            )
    }
}

/** Kullanicinin adlandirdigi bir "Ozel" DNS sunucusu. Portlar ayri tutulur (Yandex 1253 gibi). */
@Serializable
data class CustomDnsEntry(
    val id: String = CustomIds.LEGACY,
    val name: String = DEFAULT_NAME,
    val v4: String = "",
    val v4Port: Int = 53,
    val v6: String = "",
    val v6Port: Int = 53,
) {
    /** Listede ve motorda kullanilan DnsProfile karsiligi. */
    fun toProfile(): DnsProfile = DnsProfile.createCustom(id, name, v4, v4Port, v6, v6Port)

    companion object {
        const val DEFAULT_NAME = "Özel DNS"

        fun createNew(existing: List<CustomDnsEntry>, seed: CustomDnsEntry? = null): CustomDnsEntry =
            CustomDnsEntry(
                id = CustomIds.newId(existing.map { it.id }),
                name = CustomIds.newName(CustomIds.cleanName(seed?.name, DEFAULT_NAME), existing.map { it.name }),
                v4 = seed?.v4 ?: "",
                v4Port = seed?.v4Port ?: 53,
                v6 = seed?.v6 ?: "",
                v6Port = seed?.v6Port ?: 53,
            )
    }
}
