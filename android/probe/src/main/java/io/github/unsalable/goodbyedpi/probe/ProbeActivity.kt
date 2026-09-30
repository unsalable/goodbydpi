package io.github.unsalable.goodbyedpi.probe

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Emulator testlerinde VPN'in ICINDEN trafik ureten yardimci. Ana uygulama VPN'den haric
 * tutuldugu icin olcum ayri bir paketten yapilmali; bu paket tun'a girer. Dagitilmaz.
 *
 *   adb shell am start -W -n io.github.unsalable.goodbyedpi.probe/.ProbeActivity \
 *       --es urls https://a,https://b  [--ei parallel N] [--ei repeat R] [--ei timeout MS]
 *       [--es dns host1,host2]
 *       [--es udp 198.18.0.53:53,77.88.8.8:1253] [--es qname example.com]
 *       [--es quic 1.1.1.1:443,www.google.com:443]
 *       [--es tcp www.google.com:443,discord.com:443]
 *       [--es web https://www.google.com/search?q=test,https://m.youtube.com]
 *       [--es tag ADIM]
 *
 * Her istek/sorgu icin logcat'e (etiket GDPI_PROBE) tek satirlik JSON, sonunda
 * "GDPI_PROBE DONE {...}" ozeti yazar; ayni sonuclar files/probe.json'a gider.
 *
 *   http : {"kind":"http","url":..,"ok":true,"code":200,"ms":123,"bytes":1256}
 *   dns  : {"kind":"dns","host":..,"ok":true,"ms":12,"addrs":["1.2.3.4"]}
 *   udp  : {"kind":"udp","target":"198.18.0.53:53","qname":..,"ok":true,"ms":20,"rcode":0,"answers":1,"addrs":[..]}
 *          (yanit gelmezse ok=false,"error":"timeout")
 *   quic : {"kind":"quic","target":"1.1.1.1:443","ok":true,"versionNegotiation":true,"ms":30}
 *          (--drop-udp 443 etkinken ok=false,"error":"timeout" beklenir)
 *   tcp  : {"kind":"tcp","target":"www.google.com:443","ok":true,"order":["142.250.1.1",..,"2a00::1"],
 *           "family":"v4","connected":..,"attempts":[{"addr":..,"ok":true,"local":"198.18.0.1","ms":3}],"ms":40}
 *          getAllByName sirasi (netd RFC 6724) ve HttpURLConnection gibi sirayla baglanma.
 *   web  : {"kind":"web","url":..,"ok":false,"errorCode":-6,"error":"net::ERR_CONNECTION_RESET","ms":812}
 *          {"kind":"web","url":..,"ok":true,"title":"test - Google Search","httpStatus":null,"ms":1400}
 *          android.webkit.WebView = Chromium ag yigini (Chrome / Cronet ile ayni adres siralamasi ve
 *          Happy Eyeballs). HttpURLConnection IPv4'u one aliyor, Chromium ise tun IPv6 sunuyorsa
 *          IPv6'yi; 1.0.1'de Google/YouTube hatasi yalnizca bu yolla gorundu. ok = ana belgede ag
 *          hatasi yok (HTTP 4xx/5xx, orn. Google'in robot sayfasi 429, ag hatasi sayilmaz).
 *          Istekler sirayla ve ana is parcaciginda; her URL icin yeni WebView, onbellek kapali.
 */
class ProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        start(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        start(intent)
    }

    private fun start(intent: Intent?) {
        val spec = ProbeSpec.from(intent)
        val out = File(filesDir, "probe.json")
        // Istekler uzun surebilir; etkinlik is bitene kadar acik kalir, sonra kendini kapatir.
        Thread({
            try {
                runProbe(this, spec, out)
            } catch (t: Throwable) {
                Log.e(TAG, "DONE {\"ok\":false,\"error\":${JSONObject.quote(t.toString())}}")
            } finally {
                runOnUiThread { finish() }
            }
        }, "probe-main").start()
    }

    companion object {
        const val TAG = "GDPI_PROBE"
    }
}

internal data class ProbeSpec(
    val urls: List<String>,
    val dns: List<String>,
    val udp: List<String>,
    val quic: List<String>,
    val tcp: List<String>,
    val web: List<String>,
    val qname: String,
    val parallel: Int,
    val repeat: Int,
    val timeoutMs: Int,
    val tag: String,
) {
    companion object {
        fun from(intent: Intent?): ProbeSpec {
            fun list(key: String) = intent?.getStringExtra(key).orEmpty()
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }
            return ProbeSpec(
                urls = list("urls"),
                dns = list("dns"),
                udp = list("udp"),
                quic = list("quic"),
                tcp = list("tcp"),
                web = list("web"),
                qname = intent?.getStringExtra("qname")?.trim()?.ifEmpty { null } ?: "example.com",
                parallel = (intent?.getIntExtra("parallel", 4) ?: 4).coerceIn(1, 256),
                repeat = (intent?.getIntExtra("repeat", 1) ?: 1).coerceIn(1, 1000),
                timeoutMs = (intent?.getIntExtra("timeout", 10_000) ?: 10_000).coerceIn(500, 60_000),
                tag = intent?.getStringExtra("tag").orEmpty(),
            )
        }
    }
}

private fun runProbe(activity: Activity, spec: ProbeSpec, out: File) {
    val started = System.nanoTime()
    val tasks = ArrayList<Callable<JSONObject>>()
    repeat(spec.repeat) {
        spec.urls.forEach { u -> tasks += Callable { httpProbe(u, spec.timeoutMs) } }
        spec.dns.forEach { h -> tasks += Callable { dnsProbe(h) } }
        spec.udp.forEach { t -> tasks += Callable { udpDnsProbe(t, spec.qname, spec.timeoutMs.coerceAtMost(5_000)) } }
        spec.quic.forEach { t -> tasks += Callable { quicProbe(t, spec.timeoutMs.coerceAtMost(5_000)) } }
        spec.tcp.forEach { t -> tasks += Callable { tcpProbe(t, spec.timeoutMs) } }
    }
    val webCount = spec.web.size * spec.repeat
    Log.i(ProbeActivity.TAG, "START {\"tasks\":${tasks.size + webCount},\"parallel\":${spec.parallel},\"tag\":${JSONObject.quote(spec.tag)}}")

    val pool = Executors.newFixedThreadPool(spec.parallel.coerceAtMost(tasks.size.coerceAtLeast(1)))
    val pooled = try {
        pool.invokeAll(tasks).map { f ->
            try {
                f.get()
            } catch (e: Exception) {
                JSONObject().put("kind", "error").put("ok", false).put("error", e.toString())
            }
        }
    } finally {
        pool.shutdown()
        pool.awaitTermination(5, TimeUnit.SECONDS)
    }
    // WebView ana is parcacigina bagli; paralel yuklemeler birbirinin baglanti havuzunu ve DNS
    // onbellegini paylasirdi, sirayla calistirilir.
    val web = ArrayList<JSONObject>()
    repeat(spec.repeat) { spec.web.forEach { u -> web += webProbe(activity, u, spec.timeoutMs) } }
    val results = pooled + web

    val arr = JSONArray()
    results.forEach { r ->
        // Ayni anda birden cok calisma varsa satirlar etiketle ayrilabilsin.
        if (spec.tag.isNotEmpty()) r.put("tag", spec.tag)
        Log.i(ProbeActivity.TAG, r.toString())
        arr.put(r)
    }
    val ok = results.count { it.optBoolean("ok") }
    val byKind = JSONObject()
    for (kind in listOf("http", "dns", "udp", "quic", "tcp", "web", "error")) {
        val of = results.filter { it.optString("kind") == kind }
        if (of.isEmpty()) continue
        byKind.put(kind, JSONObject().put("total", of.size).put("ok", of.count { it.optBoolean("ok") }))
    }
    val summary = JSONObject()
        .put("tag", spec.tag)
        .put("total", results.size)
        .put("ok", ok)
        .put("fail", results.size - ok)
        .put("ms", (System.nanoTime() - started) / 1_000_000)
        .put("kinds", byKind)
    runCatching {
        out.writeText(JSONObject().put("summary", summary).put("results", arr).toString(2))
    }.onFailure { Log.w(ProbeActivity.TAG, "probe.json yazilamadi: $it") }
    Log.i(ProbeActivity.TAG, "DONE $summary")
}

private fun httpProbe(url: String, timeoutMs: Int): JSONObject {
    val r = JSONObject().put("kind", "http").put("url", url)
    val start = System.nanoTime()
    var conn: HttpURLConnection? = null
    try {
        conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.instanceFollowRedirects = false
        conn.useCaches = false
        conn.setRequestProperty("User-Agent", "GDPI-Probe/1.0")
        conn.setRequestProperty("Accept-Encoding", "identity")
        val code = conn.responseCode
        // Govdenin bir kismini okumak veri yolunun gercekten calistigini gosterir (yalnizca
        // basliklar degil); 64 KB yeter, buyuk sayfalarda testi uzatmayalim.
        var bytes = 0L
        runCatching {
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            stream?.use { s ->
                val buf = ByteArray(16 * 1024)
                while (bytes < 64 * 1024) {
                    val n = s.read(buf)
                    if (n < 0) break
                    bytes += n
                }
            }
        }
        r.put("ok", code > 0).put("code", code).put("bytes", bytes)
    } catch (e: Exception) {
        r.put("ok", false).put("error", e.javaClass.simpleName + ": " + (e.message ?: ""))
    } finally {
        runCatching { conn?.disconnect() }
    }
    return r.put("ms", (System.nanoTime() - start) / 1_000_000)
}

private fun dnsProbe(host: String): JSONObject {
    val r = JSONObject().put("kind", "dns").put("host", host)
    val start = System.nanoTime()
    try {
        val addrs = InetAddress.getAllByName(host).map { it.hostAddress }
        r.put("ok", addrs.isNotEmpty()).put("addrs", JSONArray(addrs))
    } catch (e: Exception) {
        r.put("ok", false).put("error", e.javaClass.simpleName + ": " + (e.message ?: ""))
    }
    return r.put("ms", (System.nanoTime() - start) / 1_000_000)
}

/**
 * Ham UDP DNS sorgusu (A kaydi). DNS yonlendirmesini (198.18.0.53:53 -> secili sunucu) ve
 * QUIC/UDP 443 dusurmesini (1.1.1.1:443'e yanit GELMEMELI) tunelin icinden kanitlamak icin.
 */
private fun udpDnsProbe(target: String, qname: String, timeoutMs: Int): JSONObject {
    val r = JSONObject().put("kind", "udp").put("target", target).put("qname", qname)
    val start = System.nanoTime()
    try {
        val host = target.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
        val port = target.substringAfterLast(':').toInt()
        val id = Random.nextInt(0, 0x10000)
        val query = buildDnsQuery(id, qname)
        DatagramSocket().use { sock ->
            sock.soTimeout = timeoutMs
            sock.send(DatagramPacket(query, query.size, InetSocketAddress(InetAddress.getByName(host), port)))
            val buf = ByteArray(1500)
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            while (true) {
                val pkt = DatagramPacket(buf, buf.size)
                sock.receive(pkt)
                val resp = buf.copyOf(pkt.length)
                val parsed = parseDnsResponse(resp) ?: continue
                if (parsed.id != id) {
                    if (System.nanoTime() > deadline) throw SocketTimeoutException()
                    continue
                }
                r.put("ok", true)
                    .put("from", "${pkt.address.hostAddress}:${pkt.port}")
                    .put("rcode", parsed.rcode)
                    .put("answers", parsed.answers)
                    .put("addrs", JSONArray(parsed.addrs))
                break
            }
        }
    } catch (e: SocketTimeoutException) {
        r.put("ok", false).put("error", "timeout")
    } catch (e: Exception) {
        r.put("ok", false).put("error", e.javaClass.simpleName + ": " + (e.message ?: ""))
    }
    return r.put("ms", (System.nanoTime() - start) / 1_000_000)
}

/**
 * QUIC surum pazarligi denemesi. UDP 443'e DNS sorgusu gondermek dusurme testini kanitlamaz:
 * orada zaten DNS sunucusu yok, VPN'siz de yanit gelmez. Bunun yerine taninmayan (GREASE)
 * surumlu, 1200 bayta doldurulmus bir QUIC Initial gonderilir; RFC 9000 6. bolum geregi QUIC
 * sunucusu "Version Negotiation" ile cevap vermek zorunda. VPN'siz ok=true, --drop-udp 443
 * etkinken ok=false ("timeout") beklenir.
 */
private fun quicProbe(target: String, timeoutMs: Int): JSONObject {
    val r = JSONObject().put("kind", "quic").put("target", target)
    val start = System.nanoTime()
    try {
        val host = target.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
        val port = target.substringAfterLast(':').toInt()
        val packet = ByteArray(1200)
        Random.nextBytes(packet)
        packet[0] = (0xC0 or (packet[0].toInt() and 0x0f)).toByte() // uzun baslik + sabit bit
        // Surum 0x?a?a?a?a: RFC 9000 15. bolumdeki pazarlik tetikleyen ayrilmis desen.
        packet[1] = 0x1a; packet[2] = 0x2a; packet[3] = 0x3a; packet[4] = 0x4a
        packet[5] = 8 // DCID uzunlugu (8 rastgele bayt)
        packet[14] = 8 // SCID uzunlugu (8 rastgele bayt); gerisi dolgu
        DatagramSocket().use { sock ->
            sock.soTimeout = timeoutMs
            sock.send(DatagramPacket(packet, packet.size, InetSocketAddress(InetAddress.getByName(host), port)))
            val buf = ByteArray(1500)
            val pkt = DatagramPacket(buf, buf.size)
            sock.receive(pkt)
            val isVn = pkt.length >= 7 && (buf[0].toInt() and 0x80) != 0 &&
                buf[1].toInt() == 0 && buf[2].toInt() == 0 && buf[3].toInt() == 0 && buf[4].toInt() == 0
            r.put("ok", true).put("versionNegotiation", isVn).put("bytes", pkt.length)
                .put("from", "${pkt.address.hostAddress}:${pkt.port}")
        }
    } catch (e: SocketTimeoutException) {
        r.put("ok", false).put("error", "timeout")
    } catch (e: Exception) {
        r.put("ok", false).put("error", e.javaClass.simpleName + ": " + (e.message ?: ""))
    }
    return r.put("ms", (System.nanoTime() - start) / 1_000_000)
}

/**
 * host:port -> getAllByName sirasi (netd'nin RFC 6724 siralamasi) ve platform HttpURLConnection
 * gibi sirayla baglanma: ilk basarili adres, her denemenin suresi/hatasi ve yerel (kaynak) adres.
 * Tun'un IPv6 sunup sunmadigini ve Java uygulamalarinin hangi aileyi sectigini gosterir.
 */
private fun tcpProbe(target: String, timeoutMs: Int): JSONObject {
    val r = JSONObject().put("kind", "tcp").put("target", target)
    val start = System.nanoTime()
    try {
        val host = target.substringBeforeLast(':')
        val port = target.substringAfterLast(':').toInt()
        val t0 = System.nanoTime()
        val addrs = InetAddress.getAllByName(host)
        r.put("dnsMs", (System.nanoTime() - t0) / 1_000_000)
        r.put("order", JSONArray(addrs.map { it.hostAddress }))
        val attempts = JSONArray()
        for (a in addrs) {
            val t1 = System.nanoTime()
            val s = Socket()
            try {
                s.connect(InetSocketAddress(a, port), timeoutMs)
                attempts.put(
                    JSONObject().put("addr", a.hostAddress).put("ok", true)
                        .put("local", s.localAddress.hostAddress).put("ms", (System.nanoTime() - t1) / 1_000_000),
                )
                r.put("ok", true).put("connected", a.hostAddress).put("family", if (a is Inet6Address) "v6" else "v4")
                break
            } catch (e: Exception) {
                attempts.put(
                    JSONObject().put("addr", a.hostAddress).put("ok", false)
                        .put("error", e.javaClass.simpleName + ": " + (e.message ?: ""))
                        .put("ms", (System.nanoTime() - t1) / 1_000_000),
                )
            } finally {
                runCatching { s.close() }
            }
        }
        r.put("attempts", attempts)
        if (!r.has("ok")) r.put("ok", false)
    } catch (e: Exception) {
        r.put("ok", false).put("error", e.javaClass.simpleName + ": " + (e.message ?: ""))
    }
    return r.put("ms", (System.nanoTime() - start) / 1_000_000)
}

/**
 * Tek bir sayfayi android.webkit.WebView ile yukler. WebView, Chrome ile ayni Chromium ag
 * yiginini (adres siralamasi, Happy Eyeballs, HTTP/2) kullanir; 1.0.1'deki "tun IPv6 sunuyor,
 * ag IPv6 tasimiyor" hatasi HttpURLConnection'da gorunmedi (o IPv4'u one aliyor), Chrome'da
 * ERR_CONNECTION_RESET / ERR_QUIC_PROTOCOL_ERROR olarak gorundu. Bu tur ayni yolu otomatik
 * olcer, Chrome'un ilk acilis sozlesmesi ekranina takilmadan.
 *
 * Sonuc: ana belgede ag hatasi (onReceivedError, isForMainFrame) varsa ok=false + errorCode +
 * aciklama ("net::ERR_..."); yoksa ok=true + sayfa basligi. HTTP hata kodlari (ornegin
 * Google'in robot dogrulamasi 429) ag hatasi degildir, yalnizca httpStatus olarak yazilir.
 */
private fun webProbe(activity: Activity, url: String, timeoutMs: Int): JSONObject {
    val r = JSONObject().put("kind", "web").put("url", url)
    val main = Handler(Looper.getMainLooper())
    val done = CountDownLatch(1)
    val start = System.nanoTime()
    val holder = arrayOfNulls<WebView>(1)

    // Geri cagrilar ana is parcaciginda gelir, zaman asimi bu is parcaciginda; r'ye yazma ve
    // okuma r uzerinde kilitli, ilk gelen sonuc kazanir.
    fun finish(fill: JSONObject.() -> Unit) {
        synchronized(r) {
            if (done.count == 0L) return
            r.fill()
            r.put("ms", (System.nanoTime() - start) / 1_000_000)
            done.countDown()
        }
    }

    main.post {
        try {
            val w = WebView(activity)
            holder[0] = w
            w.settings.javaScriptEnabled = true
            w.settings.domStorageEnabled = true
            // Onceki yuklemenin onbellegi hatayi gizlemesin: her sayfa agdan gelsin.
            w.settings.cacheMode = WebSettings.LOAD_NO_CACHE
            w.clearCache(true)
            var netError: Pair<Int, String>? = null
            var httpStatus: Int? = null
            w.webViewClient = object : WebViewClient() {
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame && netError == null) {
                        netError = error.errorCode to error.description.toString()
                    }
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame) httpStatus = response.statusCode
                }

                override fun onPageFinished(view: WebView, finishedUrl: String) {
                    val err = netError
                    val status = httpStatus
                    val title = view.title.orEmpty()
                    finish {
                        put("finalUrl", finishedUrl)
                        if (status != null) put("httpStatus", status)
                        if (err != null) {
                            put("ok", false).put("errorCode", err.first).put("error", err.second)
                        } else {
                            put("ok", true).put("title", title)
                        }
                    }
                }
            }
            activity.addContentView(
                w,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            w.loadUrl(url)
        } catch (t: Throwable) {
            finish { put("ok", false).put("error", t.javaClass.simpleName + ": " + (t.message ?: "")) }
        }
    }

    if (!done.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)) {
        finish { put("ok", false).put("error", "timeout") }
    }
    // WebView ana is parcaciginda birakilir; sonraki yukleme temiz bir gorunumle baslasin.
    val released = CountDownLatch(1)
    main.post {
        holder[0]?.let { w ->
            runCatching { w.stopLoading() }
            (w.parent as? ViewGroup)?.removeView(w)
            runCatching { w.destroy() }
        }
        released.countDown()
    }
    released.await(5, TimeUnit.SECONDS)
    return synchronized(r) { JSONObject(r.toString()) }
}

internal fun buildDnsQuery(id: Int, qname: String): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    out.write(id ushr 8); out.write(id and 0xff)
    out.write(0x01); out.write(0x00) // RD
    out.write(0); out.write(1) // QDCOUNT
    repeat(6) { out.write(0) } // AN/NS/AR
    for (label in qname.trimEnd('.').split('.')) {
        val b = label.toByteArray(Charsets.US_ASCII)
        out.write(b.size); out.write(b)
    }
    out.write(0)
    out.write(0); out.write(1) // QTYPE A
    out.write(0); out.write(1) // QCLASS IN
    return out.toByteArray()
}

internal class DnsAnswer(val id: Int, val rcode: Int, val answers: Int, val addrs: List<String>)

internal fun parseDnsResponse(b: ByteArray): DnsAnswer? {
    if (b.size < 12) return null
    fun u16(i: Int) = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
    val id = u16(0)
    if (b[2].toInt() and 0x80 == 0) return null // QR=0: yanit degil
    val rcode = b[3].toInt() and 0x0f
    val qd = u16(4)
    val an = u16(6)
    var p = 12
    fun skipName() {
        while (p < b.size) {
            val len = b[p].toInt() and 0xff
            if (len == 0) { p += 1; return }
            if (len and 0xc0 == 0xc0) { p += 2; return }
            p += 1 + len
        }
    }
    repeat(qd) { skipName(); p += 4 }
    val addrs = ArrayList<String>()
    repeat(an) {
        if (p >= b.size) return@repeat
        skipName()
        if (p + 10 > b.size) return@repeat
        val type = u16(p)
        val rdlen = u16(p + 8)
        p += 10
        if (type == 1 && rdlen == 4 && p + 4 <= b.size) {
            addrs += (0 until 4).joinToString(".") { (b[p + it].toInt() and 0xff).toString() }
        }
        p += rdlen
    }
    return DnsAnswer(id, rcode, an, addrs)
}
