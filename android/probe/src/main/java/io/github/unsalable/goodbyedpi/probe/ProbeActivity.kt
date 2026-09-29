package io.github.unsalable.goodbyedpi.probe

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Callable
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
                runProbe(spec, out)
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
                qname = intent?.getStringExtra("qname")?.trim()?.ifEmpty { null } ?: "example.com",
                parallel = (intent?.getIntExtra("parallel", 4) ?: 4).coerceIn(1, 256),
                repeat = (intent?.getIntExtra("repeat", 1) ?: 1).coerceIn(1, 1000),
                timeoutMs = (intent?.getIntExtra("timeout", 10_000) ?: 10_000).coerceIn(500, 60_000),
                tag = intent?.getStringExtra("tag").orEmpty(),
            )
        }
    }
}

private fun runProbe(spec: ProbeSpec, out: File) {
    val started = System.nanoTime()
    val tasks = ArrayList<Callable<JSONObject>>()
    repeat(spec.repeat) {
        spec.urls.forEach { u -> tasks += Callable { httpProbe(u, spec.timeoutMs) } }
        spec.dns.forEach { h -> tasks += Callable { dnsProbe(h) } }
        spec.udp.forEach { t -> tasks += Callable { udpDnsProbe(t, spec.qname, spec.timeoutMs.coerceAtMost(5_000)) } }
        spec.quic.forEach { t -> tasks += Callable { quicProbe(t, spec.timeoutMs.coerceAtMost(5_000)) } }
    }
    Log.i(ProbeActivity.TAG, "START {\"tasks\":${tasks.size},\"parallel\":${spec.parallel},\"tag\":${JSONObject.quote(spec.tag)}}")

    val pool = Executors.newFixedThreadPool(spec.parallel.coerceAtMost(tasks.size.coerceAtLeast(1)))
    val results = try {
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

    val arr = JSONArray()
    results.forEach { r ->
        // Ayni anda birden cok calisma varsa satirlar etiketle ayrilabilsin.
        if (spec.tag.isNotEmpty()) r.put("tag", spec.tag)
        Log.i(ProbeActivity.TAG, r.toString())
        arr.put(r)
    }
    val ok = results.count { it.optBoolean("ok") }
    val byKind = JSONObject()
    for (kind in listOf("http", "dns", "udp", "quic", "error")) {
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
