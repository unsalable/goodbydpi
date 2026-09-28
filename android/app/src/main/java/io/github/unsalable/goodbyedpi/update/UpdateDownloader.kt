package io.github.unsalable.goodbyedpi.update

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest

/** Guncelleme adimlarindan birinin kullaniciya gosterilecek (Turkce) hatasi. */
class UpdateException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * APK'yi cacheDir/updates altina indirir ve SHA-256'sini dogrular. Android'e bagli degil:
 * JVM testleri yerel bir HTTP sunucusuyla calistirir.
 *
 * Kaldigi yerden devam yok (APK birkac MB): her indirme klasoru bosaltip bastan baslar, boylece
 * yarim ya da eski bir dosya hicbir zaman kuruluma gitmez.
 */
class UpdateDownloader(
    private val dir: File,
    private val userAgent: String = "GoodbyeDPI-Android",
    /** Yalnizca hata ayiklama kancasi (yerel test sunucusu) icin; GitHub her zaman https verir. */
    private val allowHttp: Boolean = false,
) {
    /**
     * [info] dosyasini indirir, ozet varsa dogrular ve dosyayi dondurur. Iptal edilirse ya da
     * herhangi bir adim basarisiz olursa yarim dosyayi siler ve [UpdateException] firlatir
     * (iptalde CancellationException aynen yukari gider).
     *
     * @param onProgress (inen, toplam) ile en fazla ~%1'de bir cagrilir; toplam bilinmiyorsa 0.
     */
    suspend fun download(info: ReleaseInfo, onProgress: (Long, Long) -> Unit = { _, _ -> }): File {
        val url = info.downloadUrl
        if (!url.startsWith("https://") && !(allowHttp && url.startsWith("http://"))) {
            throw UpdateException(MSG_BAD_URL)
        }
        prepareDir()
        val safeName = info.assetName.replace(Regex("""[^A-Za-z0-9._-]"""), "_").ifEmpty { "update.apk" }
        val part = File(dir, "$safeName.part")
        val target = File(dir, safeName)

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                useCaches = false
                // GitHub asset adresi objects.githubusercontent.com'a yonlenir (https -> https).
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/octet-stream")
            }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw UpdateException("$MSG_DOWNLOAD (HTTP $code)")

            val headerLength = conn.contentLengthLong
            val total = if (headerLength > 0) headerLength else info.sizeBytes
            if (total > MAX_APK_BYTES) throw UpdateException(MSG_TOO_BIG)

            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var reported = -1L
            val step = if (total > 0) maxOf(total / 100, MIN_PROGRESS_STEP) else PROGRESS_STEP_UNKNOWN
            onProgress(0, total)
            conn.inputStream.use { input ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        // Iptal (kullanici afisi kapatti, uygulama kapaniyor) her blokta yoklanir.
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (done > MAX_APK_BYTES) throw UpdateException(MSG_TOO_BIG)
                        if (done - reported >= step) {
                            reported = done
                            onProgress(done, total)
                        }
                    }
                    out.flush()
                    out.fd.sync()
                }
            }
            if (total > 0 && done != total) throw UpdateException(MSG_INCOMPLETE)
            onProgress(done, if (total > 0) total else done)

            val actual = digest.digest().toHex()
            val expected = info.sha256?.lowercase()
            if (expected != null && expected != actual) throw UpdateException(MSG_VERIFY)

            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw UpdateException(MSG_DISK)
            return target
        } catch (e: UpdateException) {
            part.delete()
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            part.delete()
            throw e
        } catch (e: Exception) {
            part.delete()
            throw UpdateException(networkMessage(e), e)
        } finally {
            conn?.disconnect()
        }
    }

    /** Onceki indirmelerden kalan her seyi siler; klasor yalnizca guncelleyiciye ait. */
    fun prepareDir() {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.deleteRecursively() }
        if (!dir.isDirectory) throw UpdateException(MSG_DISK)
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000

        /** Bugunku evrensel APK ~10 MB; bunun cok ustu yanlis dosya demektir. */
        const val MAX_APK_BYTES = 200L * 1024 * 1024
        private const val MIN_PROGRESS_STEP = 64L * 1024
        private const val PROGRESS_STEP_UNKNOWN = 256L * 1024

        const val MSG_VERIFY = "Doğrulama başarısız"
        const val MSG_DOWNLOAD = "İndirme başarısız"
        const val MSG_INCOMPLETE = "İndirme yarım kaldı"
        const val MSG_TOO_BIG = "Dosya beklenenden büyük"
        const val MSG_BAD_URL = "Geçersiz indirme adresi"
        const val MSG_DISK = "Dosya kaydedilemedi"

        internal fun networkMessage(e: Throwable): String = when (e) {
            is UnknownHostException -> UpdateChecker.MSG_OFFLINE
            is SocketTimeoutException -> UpdateChecker.MSG_TIMEOUT
            is IOException -> MSG_DOWNLOAD
            else -> MSG_DOWNLOAD
        }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().toHex()
        }

        private fun ByteArray.toHex(): String {
            val hex = "0123456789abcdef"
            val sb = StringBuilder(size * 2)
            for (b in this) {
                val v = b.toInt() and 0xff
                sb.append(hex[v ushr 4]).append(hex[v and 0x0f])
            }
            return sb.toString()
        }
    }
}
