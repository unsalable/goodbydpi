package io.github.unsalable.goodbyedpi.service

import android.net.Network
import android.os.SystemClock
import io.github.unsalable.goodbyedpi.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Alttaki agin IPv6'si gercekten internete cikiyor mu? Adres + varsayilan yol (Ipv6Gate) yetmez:
 * IPv6 tasimasi bozuk bir agda lwIP baglantiyi yine yerelde kabul eder ve her cift yiginli
 * uygulama RST alir. Bu yuzden tunelde IPv6 acilmadan once ALTTAKI AGA bagli bir soketle bilinen
 * iki anycast adrese TCP baglantisi denenir (yalnizca SYN/SYN-ACK; hicbir veri gitmez). Hata,
 * zaman asimi ya da istisna = kullanilamaz (kapali kalmak guvenli: dogrulanmis IPv4 davranisi).
 *
 * Bloklar: cagiran is parcacigini en cok TARGETS.size * TIMEOUT_MS bekletir; motor is parcaciginda
 * cagirilmaz (DpiVpnService IO havuzunda calistirir).
 */
object Ipv6Probe {
    /** Google ve Cloudflare genel DNS'i; 443 (853/53 bazi aglarda kesiliyor, 443 kesilmez). */
    val TARGETS: List<String> = listOf("2001:4860:4860::8888", "2606:4700:4700::1111")
    const val PORT = 443
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
        var last = "denenmedi"
        for (target in TARGETS) {
            val started = SystemClock.elapsedRealtime()
            try {
                Socket().use { s ->
                    // Soket alttaki aga bagli: VPN'den bagimsiz, tam o agin IPv6'si sinanir.
                    network.bindSocket(s)
                    s.connect(InetSocketAddress(InetAddress.getByName(target), PORT), TIMEOUT_MS)
                }
                return Result(true, "${SystemClock.elapsedRealtime() - started} ms")
            } catch (e: Exception) {
                last = describe(e)
            }
        }
        return Result(false, last)
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
}
