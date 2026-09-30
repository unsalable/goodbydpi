package io.github.unsalable.goodbyedpi.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// SOZLESME (1.0.2): alanlar sabit. Servis (DpiVpnService) doldurur; arayuz ve tani raporu okur.

/**
 * Tunelde IPv6'nin neden acik/kapali oldugu. Tunel IPv6'yi yalnizca bagli ag (mobil veri /
 * Wi-Fi) gercekten IPv6 tasiyabiliyorsa sunar: aksi halde lwIP baglantiyi yerelde kabul edip
 * byedpi disari cikamayinca sifirliyor ve Chrome / Google / YouTube IPv4'e dusmuyordu.
 */
data class Ipv6Status(
    /** Kullanici ayari (Ayarlar > IPv6). Kapaliysa digerleri ne olursa olsun tunelde IPv6 yok. */
    val setting: Boolean,
    /** Bagli agda 2000::/3 icinde (kuresel) bir IPv6 adresi var mi? Ag bilinmiyorsa false. */
    val underlyingGlobal: Boolean,
    /** Bagli agda IPv6 varsayilan yolu var mi? */
    val underlyingDefaultRoute: Boolean,
    /** Erisim denemesinin sonucu: null = denenmedi; true/false = basarili/basarisiz. */
    val probeOk: Boolean?,
    /** Deneme suresi (ms) ya da hata metni; yalnizca tani icin, kullaniciya ham gosterilebilir. */
    val probeDetail: String?,
    /** Sonuc: tunel su an IPv6 sunuyor mu (kuresel kapsamli adresle)? */
    val tunV6: Boolean,
) {
    companion object {
        val UNKNOWN = Ipv6Status(
            setting = true,
            underlyingGlobal = false,
            underlyingDefaultRoute = false,
            probeOk = null,
            probeDetail = null,
            tunV6 = false,
        )
    }
}

object Ipv6StatusHolder {
    private val _status = MutableStateFlow(Ipv6Status.UNKNOWN)
    val status: StateFlow<Ipv6Status> = _status.asStateFlow()

    internal fun set(status: Ipv6Status) {
        _status.value = status
    }
}
