package io.github.unsalable.goodbyedpi.diag

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import io.github.unsalable.goodbyedpi.BuildConfig
import java.net.Inet6Address

/**
 * Tanilama raporu icin cihaz ve ag bilgisini toplar. Izin gerektirmeyen API'ler:
 * ACCESS_NETWORK_STATE (manifestte var) ve TelephonyManager.networkOperatorName.
 *
 * Uygulamanin kendi uid'i VPN'den haric: activeNetwork bu yuzden VPN acikken de alttaki agi
 * (mobil veri / Wi-Fi) verir; tun'u ayrica VPN tasiyicili agdan okuruz.
 */
internal object DeviceInfoCollector {
    fun collect(context: Context): DeviceInfo {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val active = runCatching { cm?.activeNetwork }.getOrNull()
        val caps = active?.let { runCatching { cm?.getNetworkCapabilities(it) }.getOrNull() }
        val lp = active?.let { runCatching { cm?.getLinkProperties(it) }.getOrNull() }
        val tunLp = runCatching {
            @Suppress("DEPRECATION")
            cm?.allNetworks?.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
                ?.let { cm.getLinkProperties(it) }
        }.getOrNull()

        val operator = runCatching {
            context.getSystemService(TelephonyManager::class.java)?.networkOperatorName
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

        return DeviceInfo(
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" + if (BuildConfig.DEBUG) " hata ayıklama" else "",
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdk = Build.VERSION.SDK_INT,
            model = listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }.joinToString(" "),
            transport = caps?.let(::transportName),
            operator = operator,
            underlyingAddresses = lp?.let { AddressKinds.summarize(it.linkAddresses.map { la -> la.address }) },
            underlyingV6DefaultRoute = lp?.let(::hasV6DefaultRoute),
            privateDns = privateDns(lp),
            tunAddresses = tunLp?.let { AddressKinds.summarize(it.linkAddresses.map { la -> la.address }) },
        )
    }

    private fun transportName(caps: NetworkCapabilities): String = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> DeviceInfo.TRANSPORT_CELLULAR
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        else -> "Diğer"
    }

    private fun hasV6DefaultRoute(lp: LinkProperties): Boolean =
        lp.routes.any { it.isDefaultRoute && it.destination.address is Inet6Address }

    private fun privateDns(lp: LinkProperties?): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return "desteklenmiyor (Android 9 öncesi)"
        if (lp == null) return "bilinmiyor (bağlı ağ yok)"
        // Kisisel profil adresleri hesap kimligi tasir; rapor paylasiliyor (AddressKinds.redactHostname).
        val name = lp.privateDnsServerName?.let(AddressKinds::redactHostname)
        return when {
            lp.isPrivateDnsActive && name != null -> "katı mod: $name"
            lp.isPrivateDnsActive -> "otomatik (etkin)"
            name != null -> "katı mod: $name (etkin değil)"
            else -> "kapalı ya da kullanılmıyor"
        }
    }
}
