package io.github.unsalable.goodbyedpi.update

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Bir denetimin sonucu. Hicbiri istisna degil: cagiran her durumu duz bir when ile ele alir. */
sealed interface CheckResult {
    /** Sunucu yanit verdi; daha yeni uygun surum yok. */
    data object NoUpdate : CheckResult

    /** Calisan surumden yeni bir Android surumu var. */
    data class Found(val info: ReleaseInfo) : CheckResult

    /**
     * Denetim yapilamadi. [message] kullaniciya gosterilebilir (Turkce).
     * [serverAnswered] sunucunun yanit verdigini (hiz siniri, 5xx) soyler: o durumda da
     * 6 saatlik bekleme baslar ki sinira takilmisken GitHub'i doldurmayalim.
     */
    data class Error(val message: String, val serverAnswered: Boolean) : CheckResult
}

/**
 * GitHub releases API'sini sorgular. Tek is: ag + ayristirma; durum ve ayarlar UpdateManager'da.
 *
 * OkHttp yerine HttpURLConnection: bir GET ve bir indirme icin ek kutuphane APK'yi buyuturdu.
 * Uygulamanin kendi paketi VPN'den haric tutuldugu icin bu istekler tun'a girmez.
 */
class UpdateChecker(
    private val apiUrl: String = DEFAULT_API_URL,
    private val userAgent: String = "GoodbyeDPI-Android",
) {
    /**
     * Bloklar; ana is parcacigindan cagrilmamali (UpdateManager IO'da cagirir).
     *
     * Liste yeniden eskiye geliyor ve depo masaustu surumleriyle ortak (13 gunde 7 masaustu
     * surumu cikti). Ilk sayfada hic Android surumu yoksa `Link: rel="next"` ile en fazla
     * [MAX_PAGES] sayfa geriye gidilir; bir sayfada Android surumu bulununca daha eskilerine
     * bakilmaz (UPD-5). Kimliksiz sinir saatte 60 istek: sayfa sayisi bu yuzden kucuk.
     */
    fun check(currentVersion: String): CheckResult {
        var url: String? = apiUrl
        var found: ReleaseInfo? = null
        var pages = 0
        while (url != null && pages < MAX_PAGES) {
            val page = fetchPage(url, first = pages == 0)
            pages++
            when (page) {
                is Page.Done -> return page.result
                is Page.Failed -> return page.error
                is Page.Ok -> {
                    // Sayfa icindeki en yuksek Android surumu; daha eski sayfalar ondan yeni olamaz.
                    found = page.info
                    if (found != null) break
                    url = page.next
                }
            }
        }
        return if (found != null && VersionUtil.isNewer(found.version, currentVersion)) {
            CheckResult.Found(found)
        } else {
            CheckResult.NoUpdate
        }
    }

    private sealed interface Page {
        data class Ok(val info: ReleaseInfo?, val next: String?) : Page

        /** Sayfalamayi bitiren kesin sonuc (ornek: 404 = depo/surum yok). */
        data class Done(val result: CheckResult) : Page
        data class Failed(val error: CheckResult.Error) : Page
    }

    private fun fetchPage(pageUrl: String, first: Boolean): Page {
        val conn = try {
            (URL(pageUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                useCaches = false
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", userAgent)
            }
        } catch (e: Exception) {
            return Page.Failed(CheckResult.Error(MSG_NETWORK, serverAnswered = false))
        }

        return try {
            val code = conn.responseCode
            when {
                code == HttpURLConnection.HTTP_OK -> {
                    val body = conn.inputStream.use { readCapped(it, MAX_BODY_BYTES) }
                    val info = try {
                        ReleaseParser.pickLatest(body)
                    } catch (e: IllegalArgumentException) {
                        return Page.Failed(CheckResult.Error(MSG_BAD_RESPONSE, serverAnswered = true))
                    }
                    Page.Ok(info, nextLink(conn.getHeaderField("Link"), pageUrl))
                }
                isRateLimited(code, conn.getHeaderField("X-RateLimit-Remaining")) ->
                    Page.Failed(CheckResult.Error(MSG_RATE_LIMIT, serverAnswered = true))
                // Depo yoksa ya da henuz hic surum yayimlanmadiysa: guncelleme yok say.
                code == HttpURLConnection.HTTP_NOT_FOUND && first -> Page.Done(CheckResult.NoUpdate)
                else -> Page.Failed(CheckResult.Error("$MSG_SERVER (HTTP $code)", serverAnswered = true))
            }
        } catch (e: Exception) {
            // Hata akisini da kapat: acik kalan baglanti havuzda soket tutar.
            runCatching { conn.errorStream?.close() }
            Page.Failed(CheckResult.Error(networkMessage(e), serverAnswered = false))
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val DEFAULT_API_URL = "https://api.github.com/repos/unsalable/goodbydpi/releases?per_page=100"

        /** Android surumu aranirken en fazla bu kadar sayfa (100'er surum) okunur. */
        const val MAX_PAGES = 3

        private val LINK_NEXT = Regex("""<([^>]+)>\s*;\s*rel="?next"?""", RegexOption.IGNORE_CASE)

        /**
         * GitHub `Link` basligindaki sonraki sayfa. Yalnizca ayni sema ve sunucu: yanit baska bir
         * adrese yonlendirip istegi oraya tasiyamasin.
         */
        internal fun nextLink(header: String?, current: String): String? {
            val next = header?.let { LINK_NEXT.find(it)?.groupValues?.get(1)?.trim() } ?: return null
            return try {
                val a = URL(current)
                val b = URL(next)
                next.takeIf { a.protocol == b.protocol && a.host.equals(b.host, ignoreCase = true) && a.port == b.port }
            } catch (e: Exception) {
                null
            }
        }

        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000

        /** 100 surumluk sayfa ~0,3-1 MB; bundan buyugu bozuk/zararli yanit sayilir. */
        private const val MAX_BODY_BYTES = 4 * 1024 * 1024

        const val MSG_NETWORK = "Sunucuya ulaşılamadı"
        const val MSG_OFFLINE = "İnternet bağlantısı yok"
        const val MSG_TIMEOUT = "Sunucu yanıt vermedi"
        const val MSG_RATE_LIMIT = "GitHub istek sınırına takıldı, daha sonra denenecek"
        const val MSG_BAD_RESPONSE = "Sunucudan geçersiz yanıt geldi"
        const val MSG_SERVER = "Sunucu hatası"

        /**
         * GitHub kimliksiz isteklere saatte 60 hak tanir. Sinir asiminda 403 (+ Remaining: 0)
         * ya da ikincil sinirda 429 doner. Baska sebepli 403'ler de (engelli ag) ayni sekilde
         * ele alinir: ikisinde de yapilacak sey beklemektir.
         */
        internal fun isRateLimited(code: Int, remaining: String?): Boolean =
            code == 429 || (code == HttpURLConnection.HTTP_FORBIDDEN && (remaining == null || remaining.trim() == "0"))

        internal fun networkMessage(e: Throwable): String = when (e) {
            is UnknownHostException -> MSG_OFFLINE
            is SocketTimeoutException -> MSG_TIMEOUT
            is SSLException -> MSG_NETWORK
            is IOException -> MSG_NETWORK
            else -> MSG_NETWORK
        }

        internal fun readCapped(input: InputStream, max: Int): String {
            val out = java.io.ByteArrayOutputStream(64 * 1024)
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > max) throw IOException("yanit cok buyuk")
                out.write(buf, 0, n)
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }
}
