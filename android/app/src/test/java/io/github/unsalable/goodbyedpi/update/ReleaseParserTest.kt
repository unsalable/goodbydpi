package io.github.unsalable.goodbyedpi.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ReleaseParserTest {
    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/update/$name")) { "fixture yok: $name" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test
    fun mixedRepoPicksNewestStableAndroidRelease() {
        // Taslak (1.2.0) ve on surum (1.3.0-beta1) atlanir; masaustu v2.3.1 / v2.3.0 APK
        // tasimadigi icin surum numarasi daha buyuk olsa da secilmez.
        val info = ReleaseParser.pickLatest(fixture("releases_mixed.json"))!!
        assertEquals("1.0.1", info.version)
        assertEquals("android-v1.0.1", info.tag)
        // Sabit ad surumlu addan once tercih edilir.
        assertEquals("GoodbyeDPI-Android.apk", info.assetName)
        assertEquals(
            "https://github.com/unsalable/goodbydpi/releases/download/android-v1.0.1/GoodbyeDPI-Android.apk",
            info.downloadUrl,
        )
        assertEquals(9050000L, info.sizeBytes)
        // digest alani buyuk harfli olsa da kucuk harfe cevrilir.
        assertEquals("abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", info.sha256)
        assertEquals("https://github.com/unsalable/goodbydpi/releases/tag/android-v1.0.1", info.htmlUrl)
        assertEquals("Hata düzeltmeleri.", info.notes)
    }

    @Test
    fun mixedRepoIsUpdateOnlyForOlderApp() {
        val info = ReleaseParser.pickLatest(fixture("releases_mixed.json"))!!
        assertEquals(true, VersionUtil.isNewer(info.version, "1.0.0"))
        assertEquals(false, VersionUtil.isNewer(info.version, "1.0.1"))
        // Masaustunun 2.3.1'i hicbir zaman Android icin "yeni surum" sayilmamali.
        assertEquals(false, VersionUtil.isNewer(info.version, "2.0.0"))
    }

    @Test
    fun desktopOnlyRepoHasNoAndroidRelease() {
        val body = """
            [{"tag_name":"v2.3.1","draft":false,"prerelease":false,"html_url":"x","body":"",
              "assets":[{"name":"GoodbyeDPI-UI-2.3.1-setup.exe","state":"uploaded","size":1,
                         "browser_download_url":"https://example.com/setup.exe"}]}]
        """.trimIndent()
        assertNull(ReleaseParser.pickLatest(body))
        assertNull(ReleaseParser.pickLatest("[]"))
    }

    @Test
    fun singleObjectResponseIsAccepted() {
        val body = """
            {"tag_name":"android-v1.4.0","draft":false,"prerelease":false,"html_url":"h","body":"",
             "assets":[{"name":"GoodbyeDPI-Android-1.4.0.apk","size":5,"browser_download_url":"https://e/a.apk"}]}
        """.trimIndent()
        val info = ReleaseParser.pickLatest(body)!!
        assertEquals("1.4.0", info.version)
        assertEquals("GoodbyeDPI-Android-1.4.0.apk", info.assetName)
        assertNull(info.sha256)
    }

    @Test
    fun foreignApkNamesAreNeverPicked() {
        // Probe, hata ayiklama derlemesi ya da baska bir APK surum sayilmaz (UPD-5).
        for (name in listOf("app-release.apk", "GoodbyeDPI-Android-1.4.0-debug.apk", "GoodbyeDPI-Probe.apk", "GoodbyeDPI-Android.apk.sha256")) {
            val body = """
                [{"tag_name":"android-v1.4.0","draft":false,"prerelease":false,"body":"",
                  "assets":[{"name":"$name","browser_download_url":"https://e/a.apk"}]}]
            """.trimIndent()
            assertNull(name, ReleaseParser.pickLatest(body))
        }
    }

    @Test
    fun desktopTagWithApkDoesNotMaskAndroidReleases() {
        // Masaustu v2.4.0'a yanlislikla APK eklenmis: 2.4.0 > 1.1.0 olsa da Android surumu kazanir.
        val body = """
            [{"tag_name":"v2.4.0","draft":false,"prerelease":false,"body":"",
              "assets":[{"name":"GoodbyeDPI-Android.apk","browser_download_url":"https://e/desk.apk"},
                        {"name":"GoodbyeDPI-UI-Setup.exe","browser_download_url":"https://e/s.exe"}]},
             {"tag_name":"2.5.0","draft":false,"prerelease":false,"body":"",
              "assets":[{"name":"GoodbyeDPI-Android.apk","browser_download_url":"https://e/bare.apk"}]},
             {"tag_name":"ANDROID-V1.1.0","draft":false,"prerelease":false,"body":"",
              "assets":[{"name":"GoodbyeDPI-Android.apk","browser_download_url":"https://e/and.apk"}]}]
        """.trimIndent()
        val info = ReleaseParser.pickLatest(body)!!
        assertEquals("1.1.0", info.version)
        assertEquals("https://e/and.apk", info.downloadUrl)
        assertEquals(true, ReleaseParser.isAndroidTag("android-v1.0.0"))
        assertEquals(false, ReleaseParser.isAndroidTag("v1.0.0"))
        assertEquals(false, ReleaseParser.isAndroidTag(null))
    }

    @Test
    fun versionedAssetPreferredOverRandomApk() {
        val body = """
            [{"tag_name":"android-v1.1.0","draft":false,"prerelease":false,"body":"",
              "assets":[
                {"name":"other.apk","browser_download_url":"https://e/o.apk"},
                {"name":"GoodbyeDPI-Android-1.1.0.apk","browser_download_url":"https://e/v.apk"}]}]
        """.trimIndent()
        assertEquals("GoodbyeDPI-Android-1.1.0.apk", ReleaseParser.pickLatest(body)!!.assetName)
    }

    @Test
    fun notUploadedAssetsAndNonHttpUrlsAreSkipped() {
        val body = """
            [{"tag_name":"android-v1.1.0","draft":false,"prerelease":false,"body":"",
              "assets":[{"name":"GoodbyeDPI-Android.apk","state":"new","browser_download_url":"https://e/a.apk"}]},
             {"tag_name":"android-v1.0.5","draft":false,"prerelease":false,"body":"",
              "assets":[{"name":"GoodbyeDPI-Android.apk","browser_download_url":"ftp://e/a.apk"}]},
             {"tag_name":"android-v1.0.4","draft":false,"prerelease":false,"body":"",
              "assets":[{"name":"GoodbyeDPI-Android.apk","browser_download_url":"https://e/ok.apk"}]}]
        """.trimIndent()
        assertEquals("1.0.4", ReleaseParser.pickLatest(body)!!.version)
    }

    @Test
    fun highestVersionWinsEvenIfListedLater() {
        val body = """
            [{"tag_name":"android-v1.0.9","draft":false,"prerelease":false,"body":"",
              "assets":[{"name":"GoodbyeDPI-Android.apk","browser_download_url":"https://e/9.apk"}]},
             {"tag_name":"android-v1.0.10","draft":false,"prerelease":false,"body":"",
              "assets":[{"name":"GoodbyeDPI-Android.apk","browser_download_url":"https://e/10.apk"}]},
             {"tag_name":"latest","draft":false,"prerelease":false,"body":"",
              "assets":[{"name":"GoodbyeDPI-Android.apk","browser_download_url":"https://e/x.apk"}]}]
        """.trimIndent()
        assertEquals("1.0.10", ReleaseParser.pickLatest(body)!!.version)
    }

    @Test
    fun malformedJsonThrowsIllegalArgument() {
        for (bad in listOf("", "not json", "{\"message\":\"Not Found\"}", "42", "[1,2", "<html>rate limit</html>")) {
            assertThrows(bad, IllegalArgumentException::class.java) { ReleaseParser.pickLatest(bad) }
        }
    }

    @Test
    fun wrongFieldTypesDoNotCrash() {
        val body = """
            [{"tag_name":123,"draft":"no","assets":"none"},
             {"tag_name":"android-v1.0.2","draft":null,"prerelease":false,"body":null,
              "assets":[{"name":"GoodbyeDPI-Android.apk","size":"big","digest":42,
                         "browser_download_url":"https://e/a.apk"}]}]
        """.trimIndent()
        val info = ReleaseParser.pickLatest(body)!!
        assertEquals("1.0.2", info.version)
        assertEquals(0L, info.sizeBytes)
        assertNull(info.sha256)
    }

    @Test
    fun shaFromBodyWhenDigestMissing() {
        // Fixture'daki 1.0.0 surumunde digest yok; sha256sum ciktisi notta.
        val release = ReleaseParser.pickLatest(
            fixture("releases_mixed.json").replace("android-v1.0.1", "android-v0.0.1"),
        )!!
        assertEquals("1.0.0", release.version)
        assertEquals("b".repeat(64), release.sha256)
    }

    // ----------------------------------------------------------------- SHA-256

    private val h1 = "0123456789abcdef".repeat(4)
    private val h2 = "fedcba9876543210".repeat(4)

    @Test
    fun digestField() {
        assertEquals(h1, ReleaseParser.digestSha256("sha256:$h1"))
        assertEquals(h1, ReleaseParser.digestSha256("SHA256:${h1.uppercase()}"))
        assertNull(ReleaseParser.digestSha256("sha512:$h1"))
        assertNull(ReleaseParser.digestSha256("sha256:abc"))
        assertNull(ReleaseParser.digestSha256("sha256:${h1}00"))
        assertNull(ReleaseParser.digestSha256(null))
    }

    @Test
    fun shaOnSameLineAsAssetName() {
        val body = "Degisiklikler\n\n$h2  GoodbyeDPI-Android-1.2.0.apk\n$h1  GoodbyeDPI-Android.apk\n"
        assertEquals(h1, ReleaseParser.extractSha256(body, "GoodbyeDPI-Android.apk"))
        assertEquals(h2, ReleaseParser.extractSha256(body, "GoodbyeDPI-Android-1.2.0.apk"))
    }

    @Test
    fun shaOnLineAfterOrBeforeAssetName() {
        val after = "**GoodbyeDPI-Android.apk**\nSHA-256: `${h1.uppercase()}`\n\nBaska: $h2"
        assertEquals(h1, ReleaseParser.extractSha256(after, "GoodbyeDPI-Android.apk"))
        val before = "SHA-256: $h1\nDosya: GoodbyeDPI-Android.apk\n\nBaska: $h2"
        assertEquals(h1, ReleaseParser.extractSha256(before, "GoodbyeDPI-Android.apk"))
    }

    @Test
    fun releasePs1NotesFormat() {
        // tools/release.ps1'in urettigi not: ad once hash'siz bir cumlede geciyor.
        val body = "GoodbyeDPI Android 1.0.1\r\n\r\nKurulum: GoodbyeDPI-Android.apk dosyasını indirip açın.\r\n\r\n" +
            "SHA-256:\r\n```\r\n$h1  GoodbyeDPI-Android.apk\r\n$h1  GoodbyeDPI-Android-1.0.1.apk\r\n```\r\n"
        assertEquals(h1, ReleaseParser.extractSha256(body, "GoodbyeDPI-Android.apk"))
        assertEquals(h1, ReleaseParser.extractSha256(body, "GoodbyeDPI-Android-1.0.1.apk"))
    }

    @Test
    fun fixedAssetNameFallsBackToVersionedLine() {
        val body = "$h1  GoodbyeDPI-Android-1.2.0.apk\n$h2  GoodbyeDPI-UI-2.3.1-setup.exe"
        assertEquals(h1, ReleaseParser.extractSha256(body, "GoodbyeDPI-Android.apk"))
    }

    @Test
    fun singleUnlabelledHashIsAcceptedButAmbiguityIsNot() {
        assertEquals(h1, ReleaseParser.extractSha256("SHA-256: $h1", "GoodbyeDPI-Android.apk"))
        assertEquals(h1, ReleaseParser.extractSha256("a: $h1\nb: $h1", "x.apk"))
        assertNull(ReleaseParser.extractSha256("a: $h1\nb: $h2", "GoodbyeDPI-Android.apk"))
        assertNull(ReleaseParser.extractSha256("", "GoodbyeDPI-Android.apk"))
        assertNull(ReleaseParser.extractSha256(null, "GoodbyeDPI-Android.apk"))
        // 64'ten uzun hex dizisi (sha512) ozet sayilmaz.
        assertNull(ReleaseParser.extractSha256("sha512: ${h1}${h2}", "GoodbyeDPI-Android.apk"))
    }
}
