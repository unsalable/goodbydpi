package io.github.unsalable.goodbyedpi.diag

import io.github.unsalable.goodbyedpi.engine.ByeDpiArgs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

// SOZLESME (wave 2): SiteResult ve ConnectionTester.run imzalari sabit.

data class SiteResult(
    val host: String,
    val ok: Boolean,
    /** TLS el sikismasi dahil sure; basarisizsa null */
    val millis: Long?,
    /** kisa Turkce hata ("Zaman aşımı", "Bağlantı sıfırlandı" ...); basariliysa null */
    val error: String?,
)

/**
 * Ayarlar > BAGLANTI TESTI. Her siteye paralel bir HTTPS istegi atar ve DNS + TCP + TLS +
 * ilk yanit suresini olcer. Sunucudan herhangi bir HTTP durum kodu gelmesi (403, 301 dahil)
 * "ulasildi" sayilir: DPI engeli kendini TLS el sikismasinda sifirlama/zaman asimi olarak
 * gosterir, sunucunun ne cevap verdigi onemli degil.
 */
object ConnectionTester {
    val DEFAULT_HOSTS = listOf("discord.com", "roblox.com", "example.com")

    /** Tek bir sitenin ust siniri (baglanti ve okuma ayri ayri). */
    const val TIMEOUT_MS = 8_000

    const val ERR_TIMEOUT = "Zaman aşımı"
    const val ERR_RESET = "Bağlantı sıfırlandı"
    const val ERR_CERT = "Sertifika hatası"
    const val ERR_DNS = "DNS çözülemedi"
    const val ERR_TLS = "TLS hatası"
    const val ERR_CONNECT = "Bağlantı kurulamadı"

    /**
     * Her siteye HTTPS istegi atar. [socksPort] verilirse istekler calisan byedpi
     * SOCKS5 vekili uzerinden gider (uygulamanin kendisi VPN'den haric tutuldugu icin
     * atlatmayi ancak boyle olcebiliriz); null ise dogrudan.
     */
    suspend fun run(socksPort: Int?, hosts: List<String> = DEFAULT_HOSTS): List<SiteResult> =
        run(socksPort, hosts, TIMEOUT_MS)

    internal suspend fun run(socksPort: Int?, hosts: List<String>, timeoutMs: Int): List<SiteResult> =
        coroutineScope {
            hosts.map { host -> async(Dispatchers.IO) { testHost(host, socksPort, timeoutMs) } }.awaitAll()
        }

    private suspend fun testHost(host: String, socksPort: Int?, timeoutMs: Int): SiteResult {
        val clean = host.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
        val closer = Closer()
        // DNS cozumlemesinin zaman asimi yok, HttpURLConnection birden cok IP'yi sirayla deniyor
        // (her biri ayri connectTimeout). Toplam sure bu yuzden disaridan sinirlanir; asilirsa
        // soket kapatilarak bloklanan is parcacigi da serbest birakilir. Bloklayan cagri yapisal
        // kapsamin disinda calisir: icinde olsaydi zaman asiminda bile run() o is parcacigi
        // bitene kadar donemezdi (soket G/C'si kesmeye tepki vermez).
        val pending = probeScope.async {
            if (socksPort != null) {
                probeViaSocks(clean, socksPort, timeoutMs, closer)
            } else {
                probeDirect(clean, timeoutMs, closer)
            }
        }
        val result = withTimeoutOrNull(timeoutMs + OVERALL_SLACK_MS) { pending.await() }
        if (result == null) {
            closer.close()
            pending.cancel()
            return SiteResult(clean, false, null, ERR_TIMEOUT)
        }
        return result
    }

    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Zaman asiminda baska is parcacigindan kapatilacak kaynak (baglanti ya da soket). */
    internal class Closer {
        @Volatile
        var target: (() -> Unit)? = null

        fun close() {
            runCatching { target?.invoke() }
        }
    }

    private fun probeDirect(host: String, timeoutMs: Int, closer: Closer): SiteResult {
        val start = System.nanoTime()
        var conn: HttpURLConnection? = null
        return try {
            val c = URL("https://$host/").openConnection() as HttpURLConnection
            conn = c
            closer.target = { c.disconnect() }
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.requestMethod = "GET"
            // Havuzdaki acik baglanti yeniden kullanilirsa ikinci test el sikismasini olcmez.
            c.setRequestProperty("Connection", "close")
            c.setRequestProperty("Accept-Encoding", "identity")
            c.setRequestProperty("User-Agent", USER_AGENT)
            val code = c.responseCode
            val ms = (System.nanoTime() - start) / 1_000_000
            if (code > 0) SiteResult(host, true, ms, null) else SiteResult(host, false, null, ERR_CONNECT)
        } catch (e: Throwable) {
            SiteResult(host, false, null, describeError(e))
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * SOCKS yolu elle (sozlesme C3). Vekile hicbir zaman ad (SOCKS ATYP=3) gitmez: byedpi -N ile
     * adli istekleri reddediyor (olay dongusunde bloklayan getaddrinfo olmasin diye) ve bizim
     * uid'imiz VPN disinda oldugu icin ad zaten ISS'in DNS'inde cozulurdu; DNS ile engellenen
     * siteler tun'da calisirken testte "Sertifika hatasi" gorunurdu. Onun yerine:
     *  1. Ad, tun'daki uygulamalarla ayni DNS'te cozulur: SOCKS CONNECT 198.18.0.53:53 (byedpi
     *     --redirect bunu secili DNS sunucusuna ve portuna iletir) + TCP uzerinden A/AAAA sorgusu.
     *     DNS "Kapali"ysa yonlendirme yoktur ve byedpi bu CONNECT'i reddeder; o zaman (ya da DNS
     *     adimi baska bir sebeple olmazsa) sistem cozucusune dusulur.
     *  2. Vekile IP ile CONNECT, ustune SNI = ad ve ad dogrulamali TLS, tek bir HTTP/1.1 istegi
     *     (herhangi bir durum kodu "ulasildi" demek).
     * Vekilin adresi 127.0.0.1 sabiti: ART'ta InetAddress.getLoopbackAddress() ::1 donduruyor,
     * byedpi ise yalnizca 127.0.0.1'i dinliyor (her baglanti ECONNREFUSED oluyordu).
     */
    private fun probeViaSocks(host: String, socksPort: Int, timeoutMs: Int, closer: Closer): SiteResult {
        val start = System.nanoTime()
        val deadline = start + timeoutMs * 1_000_000L
        val name = host.substringBefore(':')
        val port = host.substringAfter(':', "443").toIntOrNull() ?: 443
        val proxy = socksProxy(socksPort)
        return try {
            val target = resolveForSocks(name, proxy, timeoutMs, closer)
            val raw = Socket(proxy)
            closer.target = { raw.close() }
            try {
                val left = remainingMs(deadline)
                raw.soTimeout = left
                raw.connect(InetSocketAddress(target, port), left)
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                // createSocket(ad) SNI'yi ada gore doldurur (alttaki soket IP'ye bagli olsa da).
                val ssl = factory.createSocket(raw, name, port, true) as SSLSocket
                closer.target = { ssl.close() }
                ssl.soTimeout = remainingMs(deadline)
                // Ad dogrulamasi: JDK'da bu parametreyle el sikismasinda, Android'de asagida.
                ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                ssl.startHandshake()
                if (IS_ANDROID && !HttpsURLConnection.getDefaultHostnameVerifier().verify(name, ssl.session)) {
                    throw SSLPeerUnverifiedException("Hostname $name not verified")
                }
                val req = "GET / HTTP/1.1\r\nHost: $name\r\nUser-Agent: $USER_AGENT\r\n" +
                    "Accept-Encoding: identity\r\nConnection: close\r\n\r\n"
                ssl.outputStream.apply {
                    write(req.toByteArray(Charsets.US_ASCII))
                    flush()
                }
                val status = readStatusLine(ssl.inputStream)
                val ms = (System.nanoTime() - start) / 1_000_000
                if (STATUS_RE.containsMatchIn(status)) {
                    SiteResult(host, true, ms, null)
                } else {
                    SiteResult(host, false, null, ERR_CONNECT)
                }
            } finally {
                runCatching { raw.close() }
            }
        } catch (e: Throwable) {
            SiteResult(host, false, null, describeError(e))
        }
    }

    internal fun socksProxy(socksPort: Int): Proxy =
        Proxy(Proxy.Type.SOCKS, InetSocketAddress(InetAddress.getByAddress(LOOPBACK_V4), socksPort))

    private fun remainingMs(deadline: Long): Int =
        ((deadline - System.nanoTime()) / 1_000_000).coerceIn(MIN_STEP_MS.toLong(), Int.MAX_VALUE.toLong()).toInt()

    /**
     * SOCKS CONNECT'te kullanilacak adres. IP yaziliysa aynen; degilse once secili DNS'e vekil
     * uzerinden sorulur, olmazsa sistem cozucusu. Secili DNS "boyle bir ad yok" derse sistem
     * cozucusune gidilmez: tun'daki uygulamalar da ayni cevabi goruyor, test bunu gostermeli.
     */
    internal fun resolveForSocks(name: String, proxy: Proxy, timeoutMs: Int, closer: Closer): InetAddress {
        ipv4Literal(name)?.let { return it }
        val budget = (timeoutMs / 3).coerceIn(MIN_STEP_MS, DNS_BUDGET_MS)
        when (val r = resolveViaProxy(name, proxy, budget, closer)) {
            is ProxyDns.Found -> return r.addresses.first()
            is ProxyDns.NotFound -> throw UnknownHostException("$name: secili DNS kayit dondurmedi (rcode ${r.rcode})")
            ProxyDns.Unavailable -> Unit
        }
        val all = InetAddress.getAllByName(name)
        // Emulatorde ve cogu mobil agda IPv6 yok ya da yarim; byedpi de bizim uid'imizle cikiyor.
        return all.firstOrNull { it is Inet4Address } ?: all.first()
    }

    internal sealed interface ProxyDns {
        data class Found(val addresses: List<InetAddress>) : ProxyDns
        data class NotFound(val rcode: Int) : ProxyDns

        /** Yonlendirme yok (DNS kapali: byedpi reddeder) ya da DNS sunucusu TCP'de cevap vermedi. */
        data object Unavailable : ProxyDns
    }

    /** Sanal cozucuye (byedpi --redirect) SOCKS uzerinden DNS-over-TCP; once A, bos gelirse AAAA. */
    internal fun resolveViaProxy(name: String, proxy: Proxy, budgetMs: Int, closer: Closer): ProxyDns {
        val s = Socket(proxy)
        closer.target = { s.close() }
        try {
            try {
                s.soTimeout = budgetMs
                // Adres yazisi IP oldugu icin getByName DNS'e gitmez.
                s.connect(InetSocketAddress(InetAddress.getByName(ByeDpiArgs.VIRTUAL_DNS_V4), 53), budgetMs)
            } catch (e: IOException) {
                return ProxyDns.Unavailable
            }
            val input = s.getInputStream()
            val output = s.getOutputStream()
            for (type in intArrayOf(DnsWire.TYPE_A, DnsWire.TYPE_AAAA)) {
                val id = random.nextInt(0x10000)
                DnsWire.writeTcp(output, DnsWire.query(id, name, type))
                val answer = DnsWire.parse(DnsWire.readTcp(input), id, type)
                when (answer.rcode) {
                    DnsWire.RCODE_NOERROR -> if (answer.addresses.isNotEmpty()) return ProxyDns.Found(answer.addresses)
                    DnsWire.RCODE_NXDOMAIN -> return ProxyDns.NotFound(answer.rcode)
                    // SERVFAIL / REFUSED: sunucu cozemedi; karari sistem cozucusune birak.
                    else -> return ProxyDns.Unavailable
                }
            }
            return ProxyDns.NotFound(DnsWire.RCODE_NOERROR)
        } catch (e: IOException) {
            return ProxyDns.Unavailable
        } catch (e: IllegalArgumentException) {
            // Ad DNS'e yazilamiyor (bos etiket vb.); sistem cozucusu uygun hatayi versin.
            return ProxyDns.Unavailable
        } finally {
            runCatching { s.close() }
        }
    }

    /** "1.2.3.4" -> adres; ad ise null (ag erisimi yapmaz). */
    private fun ipv4Literal(name: String): InetAddress? {
        val parts = name.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for ((i, p) in parts.withIndex()) {
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
            val v = p.toInt()
            if (v > 255) return null
            bytes[i] = v.toByte()
        }
        return InetAddress.getByAddress(bytes)
    }

    /** Ilk satiri okur ("HTTP/1.1 200 OK"); satir gelmeden baglanti kapanirsa EOFException. */
    private fun readStatusLine(input: InputStream): String {
        val sb = StringBuilder()
        while (sb.length < 256) {
            val b = input.read()
            if (b < 0) {
                if (sb.isEmpty()) throw EOFException("status satiri gelmedi")
                break
            }
            if (b == '\n'.code) break
            sb.append(b.toChar())
        }
        return sb.toString().trim()
    }

    private val STATUS_RE = Regex("""^HTTP/\d(\.\d)? \d{3}""")
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android) GoodbyeDPI-Test"

    // Dalvik/ART'ta varsayilan HostnameVerifier gercek dogrulama yapar; JDK'da hep false doner
    // (orada dogrulamayi endpointIdentificationAlgorithm ustlenir).
    private val IS_ANDROID = System.getProperty("java.vm.name").orEmpty().contains("Dalvik", ignoreCase = true)

    /**
     * Istisnayi kisa bir Turkce mesaja cevirir. Android'de ayni kok neden farkli sarmalayicilarla
     * gelebiliyor (SSLHandshakeException icinde "Connection reset by peer" gibi); bu yuzden once
     * nedenler zincirindeki turlere, sonra mesajlara bakilir.
     */
    fun describeError(e: Throwable): String {
        val chain = generateSequence(e) { it.cause }.take(MAX_CAUSES).toList()
        val text = chain.mapNotNull { it.message?.lowercase() }

        if (chain.any { it is UnknownHostException }) return ERR_DNS
        if (chain.any { it is SocketTimeoutException }) return ERR_TIMEOUT
        if (chain.any { it is CertificateException || it is CertPathValidatorException || it is SSLPeerUnverifiedException }) {
            return ERR_CERT
        }
        if (chain.any { it is EOFException } || text.any { m -> RESET_HINTS.any { m.contains(it) } }) return ERR_RESET
        if (text.any { m -> TIMEOUT_HINTS.any { m.contains(it) } } ||
            chain.any { it is InterruptedIOException && it.message?.contains("timeout", true) == true }
        ) {
            return ERR_TIMEOUT
        }
        if (text.any { m -> DNS_HINTS.any { m.contains(it) } }) return ERR_DNS
        if (text.any { m -> CERT_HINTS.any { m.contains(it) } }) return ERR_CERT
        if (chain.any { it is ConnectException || it is NoRouteToHostException }) return ERR_CONNECT
        if (chain.any { it is SSLException }) return ERR_TLS
        return ERR_CONNECT
    }

    private const val OVERALL_SLACK_MS = 2_000L

    /** DNS adiminin ust siniri: yonlendirme yokken eski byedpi 198.18.0.53'e gercekten baglanmayi dener. */
    private const val DNS_BUDGET_MS = 3_000
    private const val MIN_STEP_MS = 500

    private val LOOPBACK_V4 = byteArrayOf(127, 0, 0, 1)
    private val random = java.security.SecureRandom()
    private const val MAX_CAUSES = 8

    private val RESET_HINTS = listOf(
        "reset", "econnreset", "broken pipe", "epipe", "connection closed", "closed by peer",
        "unexpected end of stream", "end of input", "connection abort", "software caused connection abort",
    )
    private val TIMEOUT_HINTS = listOf("timed out", "timeout", "etimedout")
    private val DNS_HINTS = listOf("unable to resolve host", "no address associated", "eai_", "name or service not known")
    private val CERT_HINTS = listOf("trust anchor", "certificate", "not verified")
}
