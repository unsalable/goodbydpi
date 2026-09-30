package io.github.unsalable.goodbyedpi.service

import android.net.Network
import io.github.unsalable.goodbyedpi.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Alttaki agin IPv6'si gercekten internete cikiyor mu? Adres + varsayilan yol (Ipv6Gate) yetmez:
 * IPv6 tasimasi bozuk bir agda lwIP baglantiyi yine yerelde kabul eder ve her cift yiginli
 * uygulama RST alir. Bu yuzden tunelde IPv6 acilmadan once ALTTAKI AGA bagli bir soketle bilinen
 * iki anycast adrese TLS el sikismasi denenir. Hata, zaman asimi ya da istisna = kullanilamaz
 * (kapali kalmak guvenli: dogrulanmis IPv4 davranisi).
 *
 * Neden TLS, yalnizca TCP degil: SYN/SYN-ACK kucuk paketler. IPv4'te MSS'i kisip IPv6'da kismayan
 * ya da ICMPv6 "Packet Too Big"i yutan modemlerde (PPPoE, yaygin ev modemi hatasi) el sikisma
 * gecer ama tam boy paketler (sertifika ucusu) hic gelmez: IPv6 acilir, Chrome/YouTube takilir,
 * lwIP baglantiyi zaten kabul ettigi icin IPv4'e de dusulmez. El sikismanin bitmesi icin
 * sunucunun birkac KB'lik ilk ucusunun tamami gelmeli; bu yol MTU kara deligini da yakalar.
 * SNI gitmez (adres IP): DPI'nin ada bakan engeli denemeyi bozmaz. Zincir dogrulanir (araya
 * giren bir portal/kutu "gecti" sayilmasin), ad dogrulamasi yapilmaz (hedef IP, ad yok).
 *
 * Bloklar: cagiran is parcacigini hedef basina en cok ~TIMEOUT_MS bekletir; motor is
 * parcaciginda cagirilmaz (DpiVpnService IO havuzunda calistirir).
 */
object Ipv6Probe {
    /** Google ve Cloudflare genel DNS'i; 443 (853/53 bazi aglarda kesiliyor, 443 kesilmez). */
    val TARGETS: List<String> = listOf("2001:4860:4860::8888", "2606:4700:4700::1111")
    const val PORT = 443

    /** Hedef basina toplam sure (baglanti + el sikisma). */
    const val TIMEOUT_MS = 2_500

    /** @param detail "123 ms" ya da kisa hata metni (tani ekrani ve gunluk icin). */
    data class Result(val ok: Boolean, val detail: String)

    private val _debugOverride = MutableStateFlow<Boolean?>(null)

    /**
     * YALNIZCA HATA AYIKLAMA: dolu ise deneme aga cikmadan bu sonucu verir (emulatorde IPv6
     * cikisi yok; "gecti" yolunu sinamak icin). src/debug'daki alici yazar; surum derlemesinde
     * [BuildConfig.DEBUG] sabit false oldugu icin hic okunmaz.
     */
    val debugOverride: StateFlow<Boolean?> = _debugOverride.asStateFlow()

    internal fun setDebugOverride(value: Boolean?) {
        if (BuildConfig.DEBUG) _debugOverride.value = value
    }

    fun run(network: Network): Result {
        if (BuildConfig.DEBUG) {
            _debugOverride.value?.let { return Result(it, if (it) "hata ayıklama: geçti sayıldı" else "hata ayıklama: başarısız sayıldı") }
        }
        // Soket alttaki aga bagli: VPN'den bagimsiz, tam o agin IPv6'si sinanir.
        return runWith(TARGETS, PORT, TIMEOUT_MS) { network.bindSocket(it) }
    }

    /** Hedefleri sirayla dener, ilk basarili el sikismada doner. JVM testleri icin ayri. */
    internal fun runWith(targets: List<String>, port: Int, timeoutMs: Int, bind: (Socket) -> Unit): Result {
        var last = "denenmedi"
        for (target in targets) {
            val started = System.nanoTime()
            try {
                handshake(target, port, timeoutMs, bind)
                return Result(true, "${(System.nanoTime() - started) / 1_000_000} ms")
            } catch (e: Exception) {
                last = describe(e)
            }
        }
        return Result(false, last)
    }

    private fun handshake(target: String, port: Int, timeoutMs: Int, bind: (Socket) -> Unit) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        Socket().use { raw ->
            bind(raw)
            raw.connect(InetSocketAddress(InetAddress.getByName(target), port), timeoutMs)
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            // Ad yerine IP yazisi: SNI gonderilmez. autoClose: ssl kapaninca raw da kapanir.
            (factory.createSocket(raw, target, port, true) as SSLSocket).use { ssl ->
                // Kalan sure okuma zaman asimi: sessizce dusurulen buyuk paketlerde el sikisma
                // burada takilir ve zaman asimiyla biter.
                ssl.soTimeout = ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(MIN_READ_MS).toInt()
                ssl.startHandshake()
            }
        }
    }

    /**
     * Kisa hata: sinif adi + errno (ECONNREFUSED, ENETUNREACH...). Mesajin kendisi yazilmaz:
     * icinde cihazin kaynak IPv6 adresi var, tani raporu ise adres icermemeli.
     */
    internal fun describe(e: Throwable): String {
        val errno = ERRNO.find(e.message.orEmpty())?.value
        return if (errno != null) "${e.javaClass.simpleName} ($errno)" else e.javaClass.simpleName
    }

    private val ERRNO = Regex("\\bE[A-Z]{3,}\\b")
    private const val MIN_READ_MS = 200L
}
