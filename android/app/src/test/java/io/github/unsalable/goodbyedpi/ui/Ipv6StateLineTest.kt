package io.github.unsalable.goodbyedpi.ui

import io.github.unsalable.goodbyedpi.service.Ipv6Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Ayarlar > GENEL > IPv6 satirinin altindaki "Şu an: ..." metni. */
class Ipv6StateLineTest {
    private fun st(global: Boolean = false, route: Boolean = false, probe: Boolean? = null, tun: Boolean = false) =
        Ipv6Status(
            setting = true,
            underlyingGlobal = global,
            underlyingDefaultRoute = route,
            probeOk = probe,
            probeDetail = null,
            tunV6 = tun,
        )

    @Test
    fun settingOffIsAlwaysOff() {
        assertEquals("Şu an: kapalı", ipv6StateLine(false, st(tun = true), connected = true))
        assertEquals("Şu an: kapalı", ipv6StateLine(false, st(), connected = false))
    }

    @Test
    fun hiddenWhileDisconnected() {
        assertNull(ipv6StateLine(true, st(global = true, route = true, probe = true, tun = true), connected = false))
    }

    @Test
    fun liveStates() {
        assertEquals("Şu an: IPv6 etkin", ipv6StateLine(true, st(true, true, true, tun = true), true))
        assertEquals("Şu an: ağda IPv6 yok, yalnızca IPv4 kullanılıyor", ipv6StateLine(true, st(), true))
        // Adres var, yol yok (ya da tersi): IPv6 yok sayilir.
        assertEquals("Şu an: ağda IPv6 yok, yalnızca IPv4 kullanılıyor", ipv6StateLine(true, st(global = true), true))
        assertEquals("Şu an: ağda IPv6 yok, yalnızca IPv4 kullanılıyor", ipv6StateLine(true, st(route = true), true))
        assertEquals(
            "Şu an: ağda IPv6 var ama çalışmıyor, IPv4 kullanılıyor",
            ipv6StateLine(true, st(true, true, probe = false), true),
        )
        assertEquals(
            "Şu an: IPv6 deneniyor, şimdilik IPv4 kullanılıyor",
            ipv6StateLine(true, st(true, true, probe = null), true),
        )
    }
}
