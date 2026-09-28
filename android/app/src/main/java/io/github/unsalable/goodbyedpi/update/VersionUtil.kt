package io.github.unsalable.goodbyedpi.update

/**
 * Surum etiketlerini okuyup karsilastirir. Saf Kotlin: JVM testleri Android'siz calistirir.
 *
 * Ayni GitHub deposunda masaustu (v2.3.1) ve Android (android-v1.0.1) surumleri yan yana
 * duruyor; etiket bicimi ileride degisse bile (v1.2.3, 1.2.3, android-v1.2.3-beta) guncelleyici
 * sessizce yanlis surumu "yeni" saymasin diye ayristirma bilincli olarak dar tutuldu.
 */
object VersionUtil {
    /** En fazla dort bilesen (1.2.3.4) kiyaslanir; fazlasi yok sayilir. */
    private const val MAX_PARTS = 4

    // Surum ya etiketin basinda ya da bir ayiricidan sonra baslamali: "android-v1.2.3",
    // "v1.2.3", "1.2.3", "GoodbyeDPI Android 1.2.3". Rakamla bitisik bir kelimenin icinden
    // (ornek "abc1.2") surum cikarmayiz. Sonek (-beta, +build, _rc1) atlanir; Int'e sigmayan
    // bilesen etiketi gecersiz kilar.
    private val VERSION_RE = Regex("""(?:^|[\s\-_/])[vV]?(\d+(?:\.\d+)*)""")

    /**
     * "android-v1.2.3" -> [1, 2, 3, 0]. Eksik bilesenler 0 olur ("1.2" == "1.2.0").
     * Taninmayan etiket icin null: boyle bir surum hicbir zaman "daha yeni" sayilmaz.
     */
    fun parse(tag: String?): IntArray? {
        val text = tag?.trim().orEmpty()
        if (text.isEmpty()) return null
        val match = VERSION_RE.find(text) ?: return null
        val parts = match.groupValues[1].split('.')
        val out = IntArray(MAX_PARTS)
        for (i in 0 until minOf(parts.size, MAX_PARTS)) {
            out[i] = parts[i].toIntOrNull() ?: return null
        }
        return out
    }

    /** Gosterim icin "1.2.3" (dorduncu bilesen yalnizca sifir degilse yazilir). */
    fun normalize(tag: String?): String? {
        val v = parse(tag) ?: return null
        return if (v[3] != 0) v.joinToString(".") else "${v[0]}.${v[1]}.${v[2]}"
    }

    /** a < b ise negatif, esitse 0, a > b ise pozitif. */
    fun compare(a: IntArray, b: IntArray): Int {
        for (i in 0 until MAX_PARTS) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /**
     * [candidate] etiketi [current] surumunden kesin olarak yeni mi? Ikisinden biri
     * okunamazsa false: emin olmadigimiz bir dosyayi indirmektense guncellememek iyidir.
     */
    fun isNewer(candidate: String?, current: String?): Boolean {
        val c = parse(candidate) ?: return false
        val cur = parse(current) ?: return false
        return compare(c, cur) > 0
    }

    /** Ayni surumu farkli yazimlarla (v1.0 / 1.0.0) esit sayar. */
    fun sameVersion(a: String?, b: String?): Boolean {
        val x = parse(a) ?: return false
        val y = parse(b) ?: return false
        return compare(x, y) == 0
    }
}
