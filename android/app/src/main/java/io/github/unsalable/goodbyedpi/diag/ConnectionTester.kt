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
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

// SOZLESME (wave 2): SiteResult ve ConnectionTester.run imzalari sabit. 1.0.2: SiteResult'a
// yalnizca varsayilanli alanlar eklendi (eski cagiranlar derlenmeye devam eder).

/**
 * Tek adres ailesinin (IPv4 ya da IPv6) sonucu. Test, ad icin A ve AAAA kayitlarini ayri ayri
 * cozer ve her aileden bir adresi ayri dener: tun'daki Chromium tabanli uygulamalar (Chrome,
 * Google, YouTube) tun IPv6 sunuyorsa IPv6'yi secer, digerleri IPv4'u. 1.0.1'de IPv4 calisip
 * IPv6 calismadigi icin yalnizca Google/YouTube acilmiyordu; tek satirlik sonuc bunu gizliyordu.
 */
data class FamilyResult(
    val ok: Boolean,
    /** Baglanti + TLS + ilk yanit (DNS haric); basarisizsa null */
    val millis: Long?,
    /** Kisa Turkce hata; basariliysa null */
    val error: String? = null,
    /** Tani raporu icin istisna zinciri ("SSLHandshakeException/SocketException ECONNRESET"); adres icermez */
    val errorDetail: String? = null,
    /** Sunucunun HTTP durum kodu; yanit gelmediyse null */
    val httpStatus: Int? = null,
    /** Google robot dogrulamasi istedi (429 ya da /sorry/ yonlendirmesi): ulasildi ama IP isaretli */
    val captcha: Boolean = false,
)

/** Adin nereden cozuldugu. */
enum class DnsSource { SELECTED, SYSTEM, LITERAL }

/** A / AAAA kayit sayilari. IPv6 satirinda "kayit yok" ile "baglanamadi"yi ayirmak icin. */
data class DnsInfo(val a: Int, val aaaa: Int, val source: DnsSource)

data class SiteResult(
    val host: String,
    /** Birincil ailenin (IPv4 varsa IPv4, yoksa IPv6) sonucu. */
    val ok: Boolean,
    /** TLS el sikismasi dahil sure; basarisizsa null */
    val millis: Long?,
    /** kisa Turkce hata ("Zaman aşımı", "Bağlantı sıfırlandı" ...); basariliysa null */
    val error: String?,
    /** IPv4 denemesi; A kaydi yoksa (ya da ad cozulemediyse) null */
    val v4: FamilyResult? = null,
    /** IPv6 denemesi; AAAA kaydi yoksa null */
    val v6: FamilyResult? = null,
    /** Cozumleme ozeti; ad hic cozulemediyse null */
    val dns: DnsInfo? = null,
    /** Birincil hatanin istisna zinciri (rapor icin) */
    val errorDetail: String? = null,
) {
    /** Herhangi bir ailede Google robot dogrulamasi. */
    val captcha: Boolean get() = v4?.captcha == true || v6?.captcha == true
}

/**
 * Ayarlar > BAGLANTI TESTI. Her siteye paralel HTTPS istegi atar; her sitede A ve AAAA ayri
 * cozulur ve birer IPv4 / IPv6 adresi ayri ayri denenir (baglanti + TLS + ilk yanit). Sunucudan
 * herhangi bir HTTP durum kodu gelmesi (403, 301 dahil) "ulasildi" sayilir: DPI engeli kendini
 * TLS el sikismasinda sifirlama/zaman asimi olarak gosterir. Iki istisna: Google'da arama
 * sayfasi istenir (robot dogrulamasi 429 ya da /sorry/ ile gorunur), YouTube'da /generate_204
 * (204 beklenir).
 */
object ConnectionTester {
    /**
     * Google ve YouTube cift yiginli (AAAA var) ve 1.0.1'de mobil veride acilmayanlar bunlardi;
     * discord.com ve roblox.com'un AAAA kaydi yok (bu yuzden etkilenmediler); instagram cift
     * yiginli ama uygulamasi IPv4'u one aliyor; example.com engelsiz kontrol.
     */
    val DEFAULT_HOSTS = listOf(
        "www.google.com",
        "www.youtube.com",
        "discord.com",
        "roblox.com",
        "www.instagram.com",
        "example.com",
    )

    /** Tek bir sitenin ust siniri (DNS + baglanti + okuma, aileler paralel). */
    const val TIMEOUT_MS = 8_000

    const val ERR_TIMEOUT = "Zaman aşımı"
    const val ERR_RESET = "Bağlantı sıfırlandı"
    const val ERR_CERT = "Sertifika hatası"
    const val ERR_DNS = "DNS çözülemedi"
    const val ERR_TLS = "TLS hatası"
    const val ERR_CONNECT = "Bağlantı kurulamadı"

    const val CAPTCHA_NOTE = "Google robot doğrulaması istiyor (operatör IP'si işaretli; uygulama kaynaklı değil)"

    /**
     * Google cevap verdi ve robot dogrulamasi istemedi. Bu, tarayicida da cikmayacaginin kaniti
     * degil: test cerezsiz tek bir istek; ayni IP'den ayni anda Chrome /sorry/ alirken test 200
     * aldi (emulatorde goruldu). Raporda "Google calisiyor" diye okunup tani kacmasin.
     */
    const val NO_CAPTCHA_NOTE = "Google yanıt verdi; tarayıcıda robot doğrulaması yine de çıkabilir (test tek ve çerezsiz istek)"

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
        // Sistem cozucusunun zaman asimi yok ve soket G/C'si kesmeye tepki vermez. Toplam sure bu
        // yuzden disaridan sinirlanir; asilirsa soketler kapatilarak bloklanan is parcaciklari da
        // serbest birakilir. Bloklayan is yapisal kapsamin disinda: icinde olsaydi zaman asiminda
        // bile run() o is parcacigi bitene kadar donemezdi.
        val pending = probeScope.async { probe(clean, socksPort, timeoutMs, closer) }
        val result = withTimeoutOrNull(timeoutMs + OVERALL_SLACK_MS) { pending.await() }
        if (result == null) {
            closer.close()
            pending.cancel()
            return SiteResult(clean, false, null, ERR_TIMEOUT)
        }
        return result
    }

    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Zaman asiminda baska is parcacigindan kapatilacak kaynaklar (soketler). */
    internal class Closer {
        private val targets = ArrayList<() -> Unit>()
        private var closed = false

        /** Kaydeder; zaten kapatildiysa hemen kapatir (gec acilan soket de sizmasin). */
        fun add(target: () -> Unit) {
            val now = synchronized(this) {
                if (!closed) targets += target
                closed
            }
            if (now) runCatching { target() }
        }

        fun close() {
            val all = synchronized(this) {
                closed = true
                targets.toList().also { targets.clear() }
            }
            all.forEach { runCatching { it() } }
        }
    }

    /**
     * Adi cozer, sonra IPv4 ve IPv6'yi paralel dener. SOCKS yolu elle (sozlesme C3): vekile
     * hicbir zaman ad (SOCKS ATYP=3) gitmez; byedpi -N ile adli istekleri reddediyor ve bizim
     * uid'imiz VPN disinda oldugu icin ad zaten ISS'in DNS'inde cozulurdu. Ad, tun'daki
     * uygulamalarla ayni DNS'te (198.18.0.53 -> secili DNS) cozulur, vekile IP ile CONNECT edilir,
     * ustune SNI = ad ve ad dogrulamali TLS, tek bir HTTP/1.1 istegi.
     * Vekilin adresi 127.0.0.1 sabiti: ART'ta InetAddress.getLoopbackAddress() ::1 donduruyor,
     * byedpi ise yalnizca 127.0.0.1'i dinliyor.
     */
    private suspend fun probe(host: String, socksPort: Int?, timeoutMs: Int, closer: Closer): SiteResult {
        val start = System.nanoTime()
        val deadline = start + timeoutMs * 1_000_000L
        val name = host.substringBefore(':')
        val port = host.substringAfter(':', "443").toIntOrNull() ?: 443
        val proxy = socksPort?.let(::socksProxy)
        val resolved = try {
            resolveFamilies(name, proxy, timeoutMs, closer)
        } catch (e: Throwable) {
            return SiteResult(host, false, null, describeError(e), errorDetail = errorDetail(e))
        }
        val check = checkFor(name)
        val (v4, v6) = coroutineScope {
            val a = resolved.v4.firstOrNull()?.let { addr ->
                async(Dispatchers.IO) { tryFamily(name, addr, port, proxy, check, deadline, closer) }
            }
            val b = resolved.v6.firstOrNull()?.let { addr ->
                async(Dispatchers.IO) { tryFamily(name, addr, port, proxy, check, deadline, closer) }
            }
            a?.await() to b?.await()
        }
        val dns = DnsInfo(resolved.v4.size, resolved.v6.size, resolved.source)
        val primary = v4 ?: v6 ?: return SiteResult(host, false, null, ERR_DNS, dns = dns)
        return SiteResult(host, primary.ok, primary.millis, primary.error, v4, v6, dns, primary.errorDetail)
    }

    /** Bir adrese (vekil uzerinden ya da dogrudan) TLS + tek HTTP istegi. */
    private fun tryFamily(
        name: String,
        target: InetAddress,
        port: Int,
        proxy: Proxy?,
        check: HttpCheck,
        deadline: Long,
        closer: Closer,
    ): FamilyResult {
        val start = System.nanoTime()
        val raw = if (proxy != null) Socket(proxy) else Socket()
        closer.add { raw.close() }
        return try {
            raw.soTimeout = remainingMs(deadline)
            raw.connect(InetSocketAddress(target, port), remainingMs(deadline))
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            // createSocket(ad) SNI'yi ada gore doldurur (alttaki soket IP'ye bagli olsa da).
            val ssl = factory.createSocket(raw, name, port, true) as SSLSocket
            closer.add { ssl.close() }
            ssl.soTimeout = remainingMs(deadline)
            // Ad dogrulamasi: JDK'da bu parametreyle el sikismasinda, Android'de asagida.
            ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            ssl.startHandshake()
            if (IS_ANDROID && !HttpsURLConnection.getDefaultHostnameVerifier().verify(name, ssl.session)) {
                throw SSLPeerUnverifiedException("Hostname $name not verified")
            }
            val req = "GET ${check.path} HTTP/1.1\r\nHost: $name\r\nUser-Agent: $USER_AGENT\r\n" +
                "Accept: text/html,*/*\r\nAccept-Language: tr-TR,tr;q=0.9,en;q=0.8\r\n" +
                "Accept-Encoding: identity\r\nConnection: close\r\n\r\n"
            ssl.outputStream.apply {
                write(req.toByteArray(Charsets.US_ASCII))
                flush()
            }
            val head = readHead(ssl.inputStream)
            val ms = (System.nanoTime() - start) / 1_000_000
            evaluate(check, head, ms)
        } catch (e: Throwable) {
            FamilyResult(false, null, describeError(e), errorDetail(e))
        } finally {
            runCatching { raw.close() }
        }
    }

    /** Site turune gore istenen yol ve basari kurali. */
    internal enum class HttpCheck(val path: String) {
        /** Herhangi bir HTTP durum kodu = ulasildi. */
        ANY("/"),

        /** Arama sayfasi: IP'si isaretli agda 429 ya da /sorry/ yonlendirmesi gelir. */
        GOOGLE_SEARCH("/search?q=test"),

        /** Yalnizca 204 dogru: YouTube'un kendi baglanti denetimi. */
        YOUTUBE_204("/generate_204"),
    }

    internal fun checkFor(name: String): HttpCheck {
        val n = name.lowercase().trimEnd('.')
        return when {
            n == "google.com" || n == "www.google.com" || n == "google.com.tr" || n == "www.google.com.tr" ->
                HttpCheck.GOOGLE_SEARCH
            n == "youtube.com" || n.endsWith(".youtube.com") -> HttpCheck.YOUTUBE_204
            else -> HttpCheck.ANY
        }
    }

    /** Yanitin ilk satiri ve Location basligi. */
    internal data class Head(val status: Int, val location: String?)

    internal fun evaluate(check: HttpCheck, head: Head, ms: Long): FamilyResult = when (check) {
        HttpCheck.ANY -> FamilyResult(true, ms, httpStatus = head.status)
        HttpCheck.GOOGLE_SEARCH -> {
            val sorry = head.status == 429 ||
                (head.status in 300..399 && head.location?.contains("/sorry/") == true)
            FamilyResult(true, ms, httpStatus = head.status, captcha = sorry)
        }
        HttpCheck.YOUTUBE_204 ->
            if (head.status == 204) {
                FamilyResult(true, ms, httpStatus = 204)
            } else {
                FamilyResult(false, null, "Beklenmeyen yanıt (HTTP ${head.status})", httpStatus = head.status)
            }
    }

    internal fun socksProxy(socksPort: Int): Proxy =
        Proxy(Proxy.Type.SOCKS, InetSocketAddress(InetAddress.getByAddress(LOOPBACK_V4), socksPort))

    private fun remainingMs(deadline: Long): Int =
        ((deadline - System.nanoTime()) / 1_000_000).coerceIn(MIN_STEP_MS.toLong(), Int.MAX_VALUE.toLong()).toInt()

    internal data class Resolved(val v4: List<InetAddress>, val v6: List<InetAddress>, val source: DnsSource)

    /**
     * Denenecek adresler, aileye gore. IP yaziliysa aynen; vekil varsa once secili DNS'e vekil
     * uzerinden sorulur, olmazsa (ya da vekil yoksa) sistem cozucusu. Secili DNS "boyle bir ad
     * yok" derse sistem cozucusune gidilmez: tun'daki uygulamalar da ayni cevabi goruyor.
     */
    internal fun resolveFamilies(name: String, proxy: Proxy?, timeoutMs: Int, closer: Closer): Resolved {
        ipv4Literal(name)?.let { return Resolved(listOf(it), emptyList(), DnsSource.LITERAL) }
        if (proxy != null) {
            val budget = (timeoutMs / 3).coerceIn(MIN_STEP_MS, DNS_BUDGET_MS)
            when (val r = resolveViaProxy(name, proxy, budget, closer)) {
                is ProxyDns.Found -> return Resolved(
                    r.addresses.filterIsInstance<Inet4Address>(),
                    r.addresses.filterIsInstance<Inet6Address>(),
                    DnsSource.SELECTED,
                )
                is ProxyDns.NotFound -> throw UnknownHostException("$name: secili DNS kayit dondurmedi (rcode ${r.rcode})")
                ProxyDns.Unavailable -> Unit
            }
        }
        val all = InetAddress.getAllByName(name).toList()
        return Resolved(all.filterIsInstance<Inet4Address>(), all.filterIsInstance<Inet6Address>(), DnsSource.SYSTEM)
    }

    internal sealed interface ProxyDns {
        /** A ve AAAA kayitlari birlikte (once IPv4'ler). */
        data class Found(val addresses: List<InetAddress>) : ProxyDns
        data class NotFound(val rcode: Int) : ProxyDns

        /** Yonlendirme yok (DNS kapali: byedpi reddeder) ya da DNS sunucusu TCP'de cevap vermedi. */
        data object Unavailable : ProxyDns
    }

    /**
     * Sanal cozucuye (byedpi --redirect) SOCKS uzerinden DNS-over-TCP; ayni baglantida A sonra
     * AAAA. A NXDOMAIN ise AAAA sorulmaz. AAAA sorgusu basarisiz olursa (SERVFAIL, bozuk yanit)
     * A kayitlariyla yetinilir: IPv4 sonucu bundan etkilenmesin.
     */
    internal fun resolveViaProxy(name: String, proxy: Proxy, budgetMs: Int, closer: Closer): ProxyDns {
        val s = Socket(proxy)
        closer.add { s.close() }
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

            fun ask(type: Int): DnsWire.Answer {
                val id = random.nextInt(0x10000)
                DnsWire.writeTcp(output, DnsWire.query(id, name, type))
                return DnsWire.parse(DnsWire.readTcp(input), id, type)
            }

            val a = ask(DnsWire.TYPE_A)
            when (a.rcode) {
                DnsWire.RCODE_NOERROR -> Unit
                DnsWire.RCODE_NXDOMAIN -> return ProxyDns.NotFound(a.rcode)
                // SERVFAIL / REFUSED: sunucu cozemedi; karari sistem cozucusune birak.
                else -> return ProxyDns.Unavailable
            }
            val aaaa = try {
                ask(DnsWire.TYPE_AAAA)
            } catch (e: IOException) {
                if (a.addresses.isEmpty()) return ProxyDns.Unavailable
                null
            }
            val v6 = if (aaaa?.rcode == DnsWire.RCODE_NOERROR) aaaa.addresses else emptyList()
            if (a.addresses.isEmpty() && aaaa != null && aaaa.rcode != DnsWire.RCODE_NOERROR) {
                return if (aaaa.rcode == DnsWire.RCODE_NXDOMAIN) ProxyDns.NotFound(aaaa.rcode) else ProxyDns.Unavailable
            }
            val all = a.addresses + v6
            return if (all.isEmpty()) ProxyDns.NotFound(DnsWire.RCODE_NOERROR) else ProxyDns.Found(all)
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

    /**
     * Durum satiri ve basliklar (bos satira kadar, en cok 16 KB). Durum satiri gelmeden baglanti
     * kapanirsa EOFException; HTTP degilse IOException (ERR_CONNECT).
     */
    internal fun readHead(input: InputStream): Head {
        val lines = ArrayList<String>()
        val sb = StringBuilder()
        var total = 0
        while (total < MAX_HEAD_BYTES) {
            val b = input.read()
            if (b < 0) {
                if (lines.isEmpty() && sb.isEmpty()) throw EOFException("status satiri gelmedi")
                break
            }
            total++
            if (b == '\n'.code) {
                val line = sb.toString().trimEnd('\r')
                sb.setLength(0)
                if (line.isEmpty()) break
                lines += line
                continue
            }
            if (sb.length < MAX_LINE) sb.append(b.toChar())
        }
        if (sb.isNotEmpty()) lines += sb.toString().trimEnd('\r')
        val m = STATUS_RE.find(lines.firstOrNull().orEmpty()) ?: throw IOException("HTTP yaniti degil")
        val location = lines.drop(1).firstOrNull { it.startsWith("location:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
        return Head(m.groupValues[2].toInt(), location)
    }

    private val STATUS_RE = Regex("""^HTTP/\d(\.\d)? (\d{3})""")

    // Google tarayici olmayan istemcilere daha kolay robot sayfasi gosteriyor; test gercek
    // kullanicinin gordugunu olcsun diye tarayici kimligi.
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36"
    private const val MAX_HEAD_BYTES = 16 * 1024
    private const val MAX_LINE = 2048

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

    /**
     * Tani raporu icin hatanin teknik ozeti: istisna siniflari, errno adi (ECONNRESET ...) ve
     * vekilin SOCKS cevabi ("SOCKS: ağa ulaşılamıyor / network unreachable"). Mesajin geri kalani
     * yazilmaz: IP adresi icerebilir ve rapor paylasiliyor.
     *
     * SOCKS cevaplari JDK'nin Ingilizce metinleri; rapor Turkce oldugu icin bilinenler Turkceye
     * cevrilir, Ingilizce anahtar sozcuk arama/kiyaslama icin yaninda kalir. Sinif adlari ve errno
     * bilerek cevrilmez (teknik ayrinti, gelistirici icin).
     */
    fun errorDetail(e: Throwable): String {
        val chain = generateSequence(e) { it.cause }.take(MAX_CAUSES).toList()
        val names = chain.map { it.javaClass.simpleName.ifEmpty { it.javaClass.name } }.distinct().take(3)
        val messages = chain.mapNotNull { it.message }
        val errno = messages.firstNotNullOfOrNull { ERRNO_RE.find(it)?.value }
        // Vekilin cevabi ya da rakamsiz kisa bir mesaj ("Malformed reply from SOCKS server"):
        // rakam iceren mesajlar adres/port tasiyabilir, yazilmaz.
        val hint = messages.firstNotNullOfOrNull(::socksReply)
            ?: messages.firstOrNull { m -> m.isNotBlank() && m.length <= 80 && m.none { it.isDigit() } }?.trim()
        return buildString {
            append(names.joinToString("/"))
            if (errno != null) append(' ').append(errno)
            if (hint != null) append(" (").append(hint).append(')')
        }
    }

    private val ERRNO_RE = Regex("""\bE[A-Z]{3,}\b""")
    private val SOCKS_RE = Regex("""SOCKS\s*:\s*([A-Za-z ]{3,40})""")

    /** Vekilin cevabi, Turkce + Ingilizce anahtar; SOCKS'la ilgisi yoksa null. */
    internal fun socksReply(message: String): String? {
        val m = message.lowercase()
        if (!m.contains("socks")) return null
        SOCKS_REPLIES.firstOrNull { (en, _) -> m.contains(en) }?.let { (en, tr) -> return "SOCKS: $tr / $en" }
        // Bilinmeyen cevap: eskisi gibi ozgun metin (rakamsiz kisa kalip).
        return SOCKS_RE.find(message)?.let { "SOCKS: " + it.groupValues[1].trim() }
    }

    // JDK SocksSocketImpl'in RFC 1928 cevap metinleri (+ bozuk cevap). Sira onemli: ozelden genele.
    private val SOCKS_REPLIES = listOf(
        "general failure" to "vekil hedefe bağlanamadı",
        "not allowed" to "vekil izin vermedi",
        "network unreachable" to "ağa ulaşılamıyor",
        "host unreachable" to "hedefe ulaşılamıyor",
        "connection refused" to "bağlantı reddedildi",
        "ttl expired" to "süre (TTL) doldu",
        "address type not supported" to "adres türü desteklenmiyor",
        "command not supported" to "komut desteklenmiyor",
        "malformed reply" to "vekilden bozuk cevap",
    )

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
