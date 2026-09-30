package io.github.unsalable.goodbyedpi.engine

import java.net.Inet6Address
import java.net.InetAddress

/**
 * Tunelde IPv6 sunulsun mu? Saf mantik (LinkProperties'e dokunmaz): JVM'de sinanir, servis
 * girdileri LinkProperties'ten kendisi cikarir.
 *
 * Neden kapi: tun IPv6 sundugunda uygulamalar (ozellikle Chrome/Cronet: Chrome, Google,
 * YouTube) cift yiginli adreslere IPv6 ile baglaniyor. hev/lwIP TCP el sikismasini yerelde
 * hemen tamamliyor, Happy Eyeballs bunu basari sayiyor; byedpi alttaki agdan IPv6 ile
 * cikamayinca uygulama RST aliyor ve IPv4'e hic donmuyor (ERR_CONNECTION_RESET /
 * ERR_QUIC_PROTOCOL_ERROR). Turkiye'de mobil hatlarin cogunda IPv6 yok. Bu yuzden tun IPv6'yi
 * yalnizca alttaki ag gercekten IPv6 ile internete cikabiliyorsa sunar:
 * ayar && [hasGlobalV6] && erisim denemesi (DpiVpnService / Ipv6Probe).
 */
object Ipv6Gate {
    /**
     * Kuresel tek noktaya yayin (2000::/3). ULA (fc00::/7), fe80 ve emulatorun fec0'i disarida:
     * bunlarla internete IPv6 ile cikilamaz.
     */
    fun isGlobal(a: InetAddress): Boolean =
        a is Inet6Address && (a.address[0].toInt() and 0xE0) == 0x20

    /** Alttaki agin kuresel IPv6 adresleri (erisim denemesi onbelleginin anahtari). */
    fun globalAddresses(addrs: Collection<InetAddress>): Set<InetAddress> =
        addrs.filterTo(LinkedHashSet(), ::isGlobal)

    /**
     * Alttaki ag IPv6 ile internete cikabilir gorunuyor mu: kuresel bir adres VE IPv6 varsayilan
     * yol. Yalnizca adres (yol yok) ya da yalnizca yol (link-local adres) yetmez. 464XLAT'li
     * salt IPv6 hatlarda da true: IPv4 CLAT'tan gecer, byedpi'nin soketleri alttaki agda.
     */
    fun hasGlobalV6(addrs: Collection<InetAddress>, v6DefaultRoute: Boolean): Boolean =
        v6DefaultRoute && addrs.any(::isGlobal)

    /**
     * Alttaki agin IPv6'si kullanilabilir mi (ayardan bagimsiz)?
     *
     * @param gate [hasGlobalV6]
     * @param probeOk bu ag + adres kumesi icin erisim denemesinin sonucu; null = sonuc henuz yok
     * @param sameNetwork son degerlendirmeyle ayni ag mi
     * @param current su anki deger
     *
     * Deneme surerken ayni agda eski deger korunur: gizlilik adresi donunce (anahtar degisir)
     * calisan IPv6 bir an kapanip acilmasin, tun iki kez yenilenmesin. Yeni agda deneme bitene
     * kadar false: bozuk IPv6'yi bir an bile sunmak (lwIP once kabul ediyor) zararli.
     */
    fun decide(gate: Boolean, probeOk: Boolean?, sameNetwork: Boolean, current: Boolean): Boolean = when {
        !gate -> false
        probeOk != null -> probeOk
        else -> sameNetwork && current
    }

    /** Tunelde IPv6: ayar acik ve alttaki agin IPv6'si kullanilabilir. */
    fun effective(setting: Boolean, underlyingUsable: Boolean): Boolean = setting && underlyingUsable
}
