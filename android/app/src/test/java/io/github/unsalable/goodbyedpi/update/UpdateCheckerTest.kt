package io.github.unsalable.goodbyedpi.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.ServerSocket
import java.security.MessageDigest

/** Ag katmani: yerel bir HTTP sunucusuna karsi gercek HttpURLConnection ile. */
class UpdateCheckerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: TinyHttpServer

    private val base get() = server.base

    @Before
    fun start() {
        server = TinyHttpServer()
    }

    @After
    fun stop() = server.close()

    private fun route(path: String, code: Int, body: String, headers: Map<String, String> = emptyMap()) {
        server.routes[path] = TinyHttpServer.Response(code, body.toByteArray(), headers)
    }

    private fun route(path: String, body: ByteArray) {
        server.routes[path] = TinyHttpServer.Response(200, body)
    }

    private fun fixture(): String =
        javaClass.getResourceAsStream("/update/releases_mixed.json")!!.bufferedReader().use { it.readText() }

    @Test
    fun foundWhenNewerAndSendsGithubHeaders() {
        route("/releases", 200, fixture())
        val r = UpdateChecker("$base/releases", userAgent = "GoodbyeDPI-Android/test").check("1.0.0")
        assertTrue(r.toString(), r is CheckResult.Found)
        assertEquals("1.0.1", (r as CheckResult.Found).info.version)
        val h = server.lastHeaders
        assertEquals("application/vnd.github+json", h["accept"])
        assertEquals("2022-11-28", h["x-github-api-version"])
        assertEquals("GoodbyeDPI-Android/test", h["user-agent"])
    }

    @Test
    fun noUpdateWhenCurrentIsSameOrNewer() {
        route("/releases", 200, fixture())
        assertEquals(CheckResult.NoUpdate, UpdateChecker("$base/releases").check("1.0.1"))
        assertEquals(CheckResult.NoUpdate, UpdateChecker("$base/releases").check("1.5.0"))
    }

    @Test
    fun emptyListAndMissingRepoAreNoUpdate() {
        route("/releases", 200, "[]")
        assertEquals(CheckResult.NoUpdate, UpdateChecker("$base/releases").check("1.0.0"))
        assertEquals(CheckResult.NoUpdate, UpdateChecker("$base/missing").check("1.0.0"))
    }

    @Test
    fun rateLimitIsReportedAndCountsAsAnswered() {
        route("/rl403", 403, """{"message":"API rate limit exceeded"}""", mapOf("X-RateLimit-Remaining" to "0"))
        route("/rl429", 429, "slow down")
        for (p in listOf("/rl403", "/rl429")) {
            val r = UpdateChecker("$base$p").check("1.0.0")
            assertEquals(p, CheckResult.Error(UpdateChecker.MSG_RATE_LIMIT, serverAnswered = true), r)
        }
    }

    @Test
    fun serverErrorAndMalformedJson() {
        route("/boom", 502, "bad gateway")
        val boom = UpdateChecker("$base/boom").check("1.0.0") as CheckResult.Error
        assertTrue(boom.message.contains("502"))
        assertTrue(boom.serverAnswered)

        route("/html", 200, "<html>captive portal</html>")
        assertEquals(
            CheckResult.Error(UpdateChecker.MSG_BAD_RESPONSE, serverAnswered = true),
            UpdateChecker("$base/html").check("1.0.0"),
        )
    }

    @Test
    fun offlineIsErrorNotAnswered() {
        val port = ServerSocket(0).use { it.localPort } // kapali port: baglanti reddedilir
        val r = UpdateChecker("http://127.0.0.1:$port/releases").check("1.0.0")
        assertTrue(r.toString(), r is CheckResult.Error && !r.serverAnswered)
        val dns = UpdateChecker("http://no-such-host.invalid/releases").check("1.0.0")
        assertEquals(CheckResult.Error(UpdateChecker.MSG_OFFLINE, serverAnswered = false), dns)
    }

    private fun desktopPage(n: Int) = (1..n).joinToString(",", "[", "]") { i ->
        """{"tag_name":"v2.$i.0","draft":false,"prerelease":false,"body":"",
            "assets":[{"name":"GoodbyeDPI-UI-Setup.exe","browser_download_url":"https://e/s.exe"}]}"""
    }

    @Test
    fun followsNextPageUntilAnAndroidReleaseIsFound() {
        // 1. sayfa yalnizca masaustu; Android surumu 2. sayfada (UPD-5). 3. sayfaya gidilmez.
        route("/releases", 200, desktopPage(100), mapOf("Link" to """<$base/p2?per_page=100&page=2>; rel="next", <$base/p9>; rel="last""""))
        route("/p2", 200, fixture(), mapOf("Link" to """<$base/p3?page=3>; rel="next""""))
        route("/p3", 500, "olmamali")
        val r = UpdateChecker("$base/releases").check("1.0.0")
        assertEquals("1.0.1", (r as CheckResult.Found).info.version)
    }

    @Test
    fun paginationIsCappedAndForeignNextLinksIgnored() {
        route("/releases", 200, desktopPage(3), mapOf("Link" to """<$base/p2>; rel="next""""))
        route("/p2", 200, desktopPage(3), mapOf("Link" to """<$base/p3>; rel="next""""))
        route("/p3", 200, desktopPage(3), mapOf("Link" to """<$base/p4>; rel="next""""))
        route("/p4", 200, fixture())
        assertEquals(CheckResult.NoUpdate, UpdateChecker("$base/releases").check("1.0.0"))

        assertEquals(null, UpdateChecker.nextLink("""<https://evil.example/x>; rel="next"""", "$base/releases"))
        assertEquals("$base/p2", UpdateChecker.nextLink("""<$base/p1>; rel="prev", <$base/p2>; rel="next"""", "$base/releases"))
        assertEquals(null, UpdateChecker.nextLink(null, "$base/releases"))
        assertTrue(UpdateChecker.DEFAULT_API_URL.endsWith("per_page=100"))
    }

    @Test
    fun laterPageFailureIsAnError() {
        route("/releases", 200, desktopPage(2), mapOf("Link" to """<$base/p2>; rel="next""""))
        route("/p2", 403, "{}", mapOf("X-RateLimit-Remaining" to "0"))
        assertEquals(
            CheckResult.Error(UpdateChecker.MSG_RATE_LIMIT, serverAnswered = true),
            UpdateChecker("$base/releases").check("1.0.0"),
        )
    }

    @Test
    fun rateLimitHeuristic() {
        assertTrue(UpdateChecker.isRateLimited(429, null))
        assertTrue(UpdateChecker.isRateLimited(403, "0"))
        assertTrue(UpdateChecker.isRateLimited(403, null))
        assertFalse(UpdateChecker.isRateLimited(403, "12"))
        assertFalse(UpdateChecker.isRateLimited(500, "0"))
    }

    // ----------------------------------------------------------------- indirme

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun info(path: String, sha: String?, size: Long = 0) = ReleaseInfo(
        version = "1.0.1", tag = "android-v1.0.1", assetName = "GoodbyeDPI-Android.apk",
        downloadUrl = "$base$path", sizeBytes = size, sha256 = sha, notes = "", htmlUrl = "",
    )

    @Test
    fun downloadVerifiesAndCleansOldFiles() = runBlocking {
        val payload = ByteArray(300_000) { (it * 31).toByte() }
        route("/a.apk", payload)
        val dir = tmp.newFolder("updates")
        dir.resolve("old.apk").writeText("eski")
        dir.resolve("junk.part").writeText("yarim")

        val progress = ArrayList<Pair<Long, Long>>()
        val file = UpdateDownloader(dir, allowHttp = true).download(info("/a.apk", sha(payload))) { d, t ->
            progress += d to t
        }
        assertEquals(listOf("GoodbyeDPI-Android.apk"), dir.list()!!.toList())
        assertEquals(sha(payload), UpdateDownloader.sha256(file))
        assertEquals(0L to payload.size.toLong(), progress.first())
        assertEquals(payload.size.toLong() to payload.size.toLong(), progress.last())
        // Ilerleme seyrek bildirilir (%1 / 64 KB adimlari), her blokta degil.
        assertTrue(progress.size.toString(), progress.size in 3..110)
    }

    @Test
    fun shaMismatchDeletesFile() = runBlocking {
        route("/a.apk", ByteArray(1000) { 1 })
        val dir = tmp.newFolder("updates")
        try {
            UpdateDownloader(dir, allowHttp = true).download(info("/a.apk", "0".repeat(64)))
            fail("dogrulama gecmemeliydi")
        } catch (e: UpdateException) {
            assertEquals(UpdateDownloader.MSG_VERIFY, e.message)
        }
        assertEquals(0, dir.list()!!.size)
    }

    @Test
    fun missingShaStillDownloads() = runBlocking {
        route("/a.apk", ByteArray(10) { 7 })
        val dir = tmp.newFolder("updates")
        val f = UpdateDownloader(dir, allowHttp = true).download(info("/a.apk", null))
        assertEquals(10L, f.length())
    }

    @Test
    fun httpRefusedUnlessAllowedAndHttpErrorsMapped() = runBlocking {
        val dir = tmp.newFolder("updates")
        try {
            UpdateDownloader(dir).download(info("/a.apk", null))
            fail("http reddedilmeliydi")
        } catch (e: UpdateException) {
            assertEquals(UpdateDownloader.MSG_BAD_URL, e.message)
        }
        try {
            UpdateDownloader(dir, allowHttp = true).download(info("/yok.apk", null))
            fail("404 hata olmaliydi")
        } catch (e: UpdateException) {
            assertTrue(e.message!!.startsWith(UpdateDownloader.MSG_DOWNLOAD))
        }
        assertEquals(0, dir.list()!!.size)
    }

    @Test
    fun cancellationDeletesPartialFile() = runBlocking {
        route("/big.apk", ByteArray(2_000_000))
        val dir = tmp.newFolder("updates")
        val job = Job()
        try {
            withContext(job) {
                UpdateDownloader(dir, allowHttp = true).download(info("/big.apk", null)) { done, _ ->
                    if (done > 0) job.cancel()
                }
            }
            fail("iptal edilmeliydi")
        } catch (e: CancellationException) {
            // beklenen
        }
        assertEquals(0, dir.list()!!.size)
    }
}
