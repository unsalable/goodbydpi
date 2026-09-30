package io.github.unsalable.goodbyedpi.engine

import android.app.PendingIntent
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * VpnService.Builder ayarlari (SPEC 1.2). Adresler, yollar ve DNS burada; tun'u kullanan hev ve
 * byedpi DpiEngine'de.
 */
class VpnTunBuilder(
    private val service: VpnService,
    private val configureIntent: PendingIntent? = null,
) {
    /**
     * @param underlying alttaki (fiziksel) varsayilan ag; null = sistem kendisi secsin
     * @param underlyingDns DNS "Kapali" iken VPN'e verilecek, alttaki agin DNS sunuculari
     */
    fun establish(
        config: EngineConfig,
        underlying: Network?,
        underlyingDns: List<InetAddress>,
    ): ParcelFileDescriptor? {
        val b = service.Builder()
        b.setSession("GoodbyeDPI")
        configureIntent?.let { b.setConfigureIntent(it) }

        // hev dis fd'de mtu'yu okuma tamponu olarak kullaniyor; ikisi ayni olmali.
        b.setMtu(HevConfig.MTU)
        b.addAddress(HevConfig.TUN_IPV4, 32)
        // config.ipv6 burada ayar DEGIL, Ipv6Gate'ten gecmis hali (EngineConfig.withUnderlyingV6):
        // IPv6 adresi/yolu yoksa Android VPN tablosuna "unreachable default" koyar, getaddrinfo
        // AAAA dondurmez ve IPv6 baglantilari 1 ms'de reddedilir (lwIP once kabul etmez).
        if (config.ipv6) b.addAddress(HevConfig.TUN_IPV6, 128)

        VpnRoutes.ipv4(config.excludeLan).forEach { b.addRoute(it.host, it.prefix) }
        if (config.ipv6) VpnRoutes.ipv6(config.excludeLan).forEach { b.addRoute(it.host, it.prefix) }

        for (dns in dnsServers(config, underlyingDns)) {
            runCatching { b.addDnsServer(dns) }.onFailure { Log.w(TAG, "DNS eklenemedi: $dns", it) }
        }

        // Kendi paketimiz VPN disinda: byedpi'nin giden soketleri tun'a geri donmez (protect()
        // gerekmez); guncelleme denetimi ve baglanti testi de dogrudan gider.
        b.addDisallowedApplication(service.packageName)

        b.setUnderlyingNetworks(underlying?.let { arrayOf(it) })
        // Q+ hedefleyen uygulamalarin VPN'i varsayilan olarak "olculu" sayilir; false, olculu
        // olma durumunu alttaki agdan devralmak demek (Wi-Fi'de olculu gorunmesin).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) b.setMetered(false)
        // setBlocking varsayilan (false): hev fd'yi zaten FIONBIO ile bloklamaz yapiyor.

        return b.establish()
    }

    companion object {
        private const val TAG = "GdpiTun"

        /** DNS "Kapali" iken alttaki ag DNS bildirmiyorsa. */
        const val FALLBACK_DNS = "1.1.1.1"

        /**
         * VPN'e verilecek DNS sunuculari. Yonlendirme aciksa tun icindeki sanal cozuculer (byedpi
         * --redirect ile gercek sunucuya ve PORTA iletir). "Kapali"da alttaki agin kendi
         * sunuculari: sorgular yine tun'dan gecer ama hedefe dokunulmaz. IPv6 kapaliyken IPv6
         * sunucular atlanir (tun'da IPv6 yolu yok).
         */
        internal fun dnsServers(config: EngineConfig, underlyingDns: List<InetAddress>): List<InetAddress> {
            if (config.redirectsDns) {
                val out = arrayListOf(InetAddress.getByName(ByeDpiArgs.VIRTUAL_DNS_V4))
                if (config.dnsTargetV6 != null) out += InetAddress.getByName(ByeDpiArgs.VIRTUAL_DNS_V6)
                return out
            }
            val usable = underlyingDns.filter { it is Inet4Address || (config.ipv6 && it is Inet6Address) }
                .filterNot { it.isAnyLocalAddress || it.isLoopbackAddress }
                .distinct()
            return usable.ifEmpty { listOf(InetAddress.getByName(FALLBACK_DNS)) }
        }

        /**
         * Tun'un sistemde gorunen her seyi: adresler/yollar (excludeLan, ipv6) ve VPN'e verilen
         * DNS sunuculari. Bu degismedikce tun yeniden kurulmaz (yalnizca byedpi degisir). DNS
         * kume olarak: ayni sunucularin sirasi degisti diye VPN yeniden kurulmasin.
         */
        internal fun tunKey(config: EngineConfig, underlyingDns: List<InetAddress>): List<Any> = listOf(
            config.excludeLan,
            config.ipv6,
            dnsServers(config, underlyingDns).toSet(),
        )
    }
}
