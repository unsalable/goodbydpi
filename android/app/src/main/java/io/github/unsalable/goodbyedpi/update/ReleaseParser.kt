package io.github.unsalable.goodbyedpi.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * GitHub "releases" yanitindan kurulabilir Android surumunu secer. Saf Kotlin, ag yok:
 * JVM testleri gercek yanit ornekleriyle calistirir.
 *
 * Depo masaustu surumlerini de barindiriyor (v2.x.y, *setup.exe). Android surumleri
 * "android-vX.Y.Z" etiketiyle ve --latest=false ile yayimlaniyor ki masaustu guncelleyicisi
 * (/releases/latest) onlari hic gormesin. Burada da ters yonu koruyoruz: yalnizca "android-v"
 * etiketli ve GoodbyeDPI-Android(-surum).apk dosyasi olan surumler aday; masaustunun v2.x'i
 * (yanlislikla bir APK eklense bile) hic secilmez.
 */
object ReleaseParser {
    /** Surumlerde aranan sabit ad; dist gorevi APK'yi bu adla da uretir. */
    const val PREFERRED_ASSET = "GoodbyeDPI-Android.apk"
    private const val VERSIONED_PREFIX = "GoodbyeDPI-Android-"
    const val ANDROID_TAG_PREFIX = "android-v"
    private val VERSIONED_ASSET = Regex("""(?i)GoodbyeDPI-Android-\d+(\.\d+){0,3}\.apk""")

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val HEX64 = Regex("""(?i)(?<![0-9a-f])[0-9a-f]{64}(?![0-9a-f])""")

    /**
     * Yanit metninden en yeni uygun surumu dondurur; uygun surum yoksa null.
     * JSON bozuksa [IllegalArgumentException] firlatir (cagiran "gecersiz yanit" der).
     */
    fun pickLatest(body: String): ReleaseInfo? {
        val root = try {
            json.parseToJsonElement(body)
        } catch (e: Exception) {
            throw IllegalArgumentException("GitHub yaniti JSON degil", e)
        }
        // /releases dizi, /releases/latest ve /releases/tags/x tek nesne dondurur; ikisini de kabul et.
        val releases = when (root) {
            is JsonArray -> root.mapNotNull { it as? JsonObject }
            is JsonObject -> if (root["tag_name"] != null) listOf(root) else
                throw IllegalArgumentException("GitHub yaniti surum listesi degil")
            else -> throw IllegalArgumentException("GitHub yaniti surum listesi degil")
        }
        return pickLatest(releases)
    }

    fun pickLatest(releases: List<JsonObject>): ReleaseInfo? {
        var best: ReleaseInfo? = null
        var bestVersion: IntArray? = null
        // GitHub listeyi olusturulma zamanina gore (yeniden eskiye) veriyor ama taslaktan
        // yayima cevrilen bir surum sirayi bozabilir; en yuksek surum numarasini seciyoruz.
        // Esitlikte listede once gelen (daha yeni olan) kalir.
        for (release in releases) {
            val info = toReleaseInfo(release) ?: continue
            val version = VersionUtil.parse(info.version) ?: continue
            if (bestVersion == null || VersionUtil.compare(version, bestVersion) > 0) {
                best = info
                bestVersion = version
            }
        }
        return best
    }

    /** Tek bir surum nesnesini kurulabilir bir ReleaseInfo'ya cevirir; uygun degilse null. */
    fun toReleaseInfo(release: JsonObject): ReleaseInfo? {
        if (release.bool("draft") == true || release.bool("prerelease") == true) return null
        val tag = release.str("tag_name")?.trim().orEmpty()
        // Yalnizca Android etiketleri (UPD-5): masaustu v2.x'e yanlislikla bir APK eklenirse o
        // surum numarasi her android-v1.x'ten buyuk oldugu icin butun Android surumlerini
        // golgeler, istemciler de o APK'yi her denetimde indirip reddederdi.
        if (!isAndroidTag(tag)) return null
        val version = VersionUtil.normalize(tag) ?: return null

        val assets = (release["assets"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val asset = pickAsset(assets) ?: return null
        val assetName = asset.str("name") ?: return null
        // https zorunlulugu indiricide; burada yalnizca adres gibi gorunmeyenleri eliyoruz.
        val url = asset.str("browser_download_url")
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return null

        val notes = release.str("body").orEmpty()
        val sha = digestSha256(asset.str("digest")) ?: extractSha256(notes, assetName)

        return ReleaseInfo(
            version = version,
            tag = tag,
            assetName = assetName,
            downloadUrl = url,
            sizeBytes = asset.long("size")?.coerceAtLeast(0) ?: 0L,
            sha256 = sha,
            notes = notes,
            htmlUrl = release.str("html_url").orEmpty(),
        )
    }

    /** release.ps1'in yayimladigi etiket bicimi: "android-v1.2.3". */
    fun isAndroidTag(tag: String?): Boolean = tag?.trim()?.startsWith(ANDROID_TAG_PREFIX, ignoreCase = true) == true

    /**
     * Once sabit ad (GoodbyeDPI-Android.apk), sonra surumlu ad (GoodbyeDPI-Android-1.2.3.apk).
     * Baska .apk'lar (probe, hata ayiklama derlemesi) hic secilmez. Yuklemesi yarim kalmis
     * dosyalar ("state" != "uploaded") atlanir.
     */
    internal fun pickAsset(assets: List<JsonObject>): JsonObject? {
        val apks = assets.filter { a ->
            val state = a.str("state")
            state == null || state == "uploaded"
        }
        return apks.firstOrNull { it.str("name").equals(PREFERRED_ASSET, ignoreCase = true) }
            ?: apks.firstOrNull { VERSIONED_ASSET.matches(it.str("name").orEmpty()) }
    }

    /** GitHub'in asset "digest" alani: "sha256:<64 hex>". Baska algoritmalar yok sayilir. */
    fun digestSha256(digest: String?): String? {
        val d = digest?.trim() ?: return null
        if (!d.startsWith("sha256:", ignoreCase = true)) return null
        val hex = d.substring("sha256:".length).trim()
        return hex.takeIf { it.length == 64 && HEX64.matches(it) }?.lowercase()
    }

    /**
     * Surum notunda APK'nin ozetini arar. Oncelik: APK adinin gectigi satir, sonra bir
     * sonraki ve bir onceki satir ("sha256sum" ciktisi ya da "SHA-256: ..." alt satiri).
     * APK adi hic gecmiyorsa ve notta tek bir farkli 64 haneli ozet varsa o kabul edilir.
     * Belirsizse (birden cok ozet, hicbiri adin yaninda degil) null: yanlis ozetle dogrulayip
     * saglam dosyayi reddetmektense yalnizca paket imzasina guveniriz.
     */
    fun extractSha256(body: String?, assetName: String): String? {
        if (body.isNullOrBlank()) return null
        val lines = body.lines()
        val names = buildList {
            add(assetName)
            // Notlar surumlu adla yazilip sabit ad yuklenmis olabilir (ya da tersi).
            if (assetName.equals(PREFERRED_ASSET, ignoreCase = true)) add(VERSIONED_PREFIX)
        }
        for (name in names) {
            for ((i, line) in lines.withIndex()) {
                if (!line.contains(name, ignoreCase = true)) continue
                for (j in listOf(i, i + 1, i - 1)) {
                    val hash = lines.getOrNull(j)?.let { HEX64.find(it)?.value } ?: continue
                    return hash.lowercase()
                }
            }
        }
        val all = HEX64.findAll(body).map { it.value.lowercase() }.toSet()
        return all.singleOrNull()
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
}
