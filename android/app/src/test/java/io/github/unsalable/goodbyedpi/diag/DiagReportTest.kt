package io.github.unsalable.goodbyedpi.diag

import io.github.unsalable.goodbyedpi.diag.ConnTestText.Tone
import io.github.unsalable.goodbyedpi.model.AppSettings
import io.github.unsalable.goodbyedpi.service.Ipv6Status
import io.github.unsalable.goodbyedpi.ui.MainViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class DiagReportTest {
    private fun ip(s: String) = InetAddress.getByName(s)

    @Test
    fun addressKinds() {
        assertEquals("IPv4 CGNAT (100.64/10)", AddressKinds.kind(ip("100.72.1.2")))
        assertEquals("IPv4 özel (LAN)", AddressKinds.kind(ip("192.168.1.5")))
        assertEquals("IPv4 özel (LAN)", AddressKinds.kind(ip("10.0.2.16")))
        assertEquals("IPv4 464XLAT (192.0.0/29)", AddressKinds.kind(ip("192.0.0.4")))
        assertEquals("IPv4 sanal (198.18/15)", AddressKinds.kind(ip("198.18.0.1")))
        assertEquals("IPv4 genel", AddressKinds.kind(ip("88.1.2.3")))
        assertEquals("IPv6 küresel", AddressKinds.kind(ip("2a02:ec80::1")))
        assertEquals("IPv6 ULA (fc00::/7)", AddressKinds.kind(ip("fd00:6764:7069::1")))
        assertEquals("IPv6 link-local", AddressKinds.kind(ip("fe80::1")))
        assertEquals("IPv6 site-local (fec0::/10)", AddressKinds.kind(ip("fec0::15")))
        assertEquals("IPv6 belgeleme (2001:db8::/32)", AddressKinds.kind(ip("2001:db8:6764:7069::1")))
        assertEquals(
            "IPv4 CGNAT (100.64/10), IPv6 küresel ×2, IPv6 link-local",
            AddressKinds.summarize(listOf(ip("100.64.0.9"), ip("2a02::1"), ip("2a02::2"), ip("fe80::2"))),
        )
        assertEquals("adres yok", AddressKinds.summarize(emptyList()))
    }

    @Test
    fun redactRemovesAddresses() {
        val t = AddressKinds.redact("connect to /2001:4860:4860::8888 (port 443) from /100.64.1.2 failed: ENETUNREACH, 812 ms")
        assertFalse(t, t.contains("4860") || t.contains("100.64"))
        assertTrue(t, t.contains("ENETUNREACH") && t.contains("812 ms"))
        assertEquals("[<adres>]:443", AddressKinds.redact("[fe80::1]:443"))
    }

    private val google = SiteResult(
        "www.google.com", true, 312, null,
        v4 = FamilyResult(true, 312, httpStatus = 200),
        v6 = FamilyResult(false, null, ConnectionTester.ERR_CONNECT, "SocketException (SOCKS: Network unreachable)"),
        dns = DnsInfo(8, 8, DnsSource.SELECTED),
    )

    private val noV6 = Ipv6Status(setting = true, underlyingGlobal = false, underlyingDefaultRoute = false, probeOk = null, probeDetail = null, tunV6 = false)
    private val v6On = noV6.copy(underlyingGlobal = true, underlyingDefaultRoute = true, probeOk = true, probeDetail = "41 ms", tunV6 = true)

    @Test
    fun ipv6FailureIsRedOnlyWhenTheTunOffersIpv6() {
        // Tun IPv6 sunmuyor: uygulamalar IPv6 denemez, hata gri ve nedeniyle.
        val muted = ConnTestText.parts(google, noV6, viaProxy = true)
        assertEquals(
            "IPv4: ✓ 312 ms · IPv6: kullanılmıyor (ağda IPv6 yok)",
            muted.joinToString("") { it.text },
        )
        assertEquals(Tone.MUTED, muted.last().tone)
        assertTrue(ConnTestText.rowOk(google, noV6, true))
        // Tunel IPv6 sunmuyorken ✗ yok (kullanici bozuk sanmasin); teknik hata raporda kalir.
        assertFalse(muted.joinToString("") { it.text }.contains("✗"))
        assertTrue(ConnTestText.reportLines(google, noV6, true).any { it.startsWith("    IPv6 hata: SocketException") })

        // Tun IPv6 sunuyor (ya da bilinmiyor): Chromium IPv6'yi secer, ayni hatayi alir.
        assertEquals(Tone.BAD, ConnTestText.parts(google, v6On, true).last().tone)
        assertFalse(ConnTestText.rowOk(google, v6On, true))
        assertFalse(ConnTestText.rowOk(google, null, true))

        // Dogrudan (VPN kapali): sistem IPv4'e duser.
        assertTrue(ConnTestText.rowOk(google, null, false))
        assertTrue(ConnTestText.parts(google, null, false).joinToString("") { it.text }.contains("IPv6: ✗ Bağlantı kurulamadı"))

        assertEquals(Tone.MUTED to "IPv6 ayarı kapalı", ConnTestText.v6FailureContext(noV6.copy(setting = false), true))
        assertEquals(
            Tone.MUTED to "ağın IPv6'sı çalışmıyor",
            ConnTestText.v6FailureContext(noV6.copy(underlyingGlobal = true, underlyingDefaultRoute = true, probeOk = false), true),
        )
    }

    @Test
    fun missingRecordsAndDnsFailures() {
        val discord = SiteResult("discord.com", true, 90, null, v4 = FamilyResult(true, 90), dns = DnsInfo(1, 0, DnsSource.SELECTED))
        assertEquals("IPv4: ✓ 90 ms · IPv6: kayıt yok", ConnTestText.parts(discord, null, true).joinToString("") { it.text })
        assertTrue(ConnTestText.rowOk(discord, null, true))

        val dnsFail = SiteResult("x.invalid", false, null, ConnectionTester.ERR_DNS, errorDetail = "UnknownHostException")
        assertTrue(ConnTestText.parts(dnsFail, null, true).isEmpty())
        assertEquals(listOf("✗ x.invalid — DNS çözülemedi", "    hata: UnknownHostException"), ConnTestText.reportLines(dnsFail, null, true))
    }

    @Test
    fun reportLinesIncludeDnsHttpDetailAndCaptcha() {
        val g = google.copy(v4 = FamilyResult(true, 312, httpStatus = 429, captcha = true))
        val lines = ConnTestText.reportLines(g, noV6, true)
        assertEquals("✓ www.google.com — IPv4: ✓ 312 ms · IPv6: kullanılmıyor (ağda IPv6 yok)", lines[0])
        assertEquals("    DNS: 8 A, 8 AAAA (seçili DNS) · IPv4 HTTP 429", lines[1])
        assertEquals("    IPv6 hata: SocketException (SOCKS: Network unreachable)", lines[2])
        assertEquals("    " + ConnectionTester.CAPTCHA_NOTE, lines[3])
    }

    private val device = DeviceInfo(
        appVersion = "1.0.2 (7)",
        androidRelease = "15",
        sdk = 35,
        model = "samsung SM-A546E",
        transport = "Mobil veri",
        operator = "TR TURKCELL",
        underlyingAddresses = "IPv4 CGNAT (100.64/10)",
        underlyingV6DefaultRoute = false,
        privateDns = "otomatik (etkin)",
        tunAddresses = "IPv4 sanal (198.18/15)",
    )

    @Test
    fun fullReport() {
        val s = AppSettings().migrate()
        val text = DiagReport.build(
            device = device,
            running = true,
            ipv6 = noV6.copy(probeDetail = "failed: /2001:4860:4860::8888 ENETUNREACH"),
            settingsLine = MainViewModel.settingsLine(s),
            engineText = "Çalışan komut:\nciadpi -i 127.0.0.1 -p 1080\n\nYöntem: X\nDNS: Yandex (1253)\nSağlayıcı: Turkcell Mobil",
            tests = listOf(google),
            testsViaProxy = true,
            testRunning = false,
        )
        val lines = text.lines()
        assertEquals("GoodbyeDPI 1.0.2 (7) · Android 15 (API 35) · samsung SM-A546E", lines[0])
        assertEquals("Ağ: Mobil veri · operatör: TR TURKCELL", lines[1])
        assertTrue(text, text.contains("Ağ adresleri: IPv4 CGNAT (100.64/10)"))
        assertTrue(text, text.contains("Ağda IPv6 varsayılan yol: yok"))
        assertTrue(text, text.contains("Özel DNS: otomatik (etkin)"))
        assertTrue(text, text.contains("Tünel adresleri: IPv4 sanal (198.18/15)"))
        assertTrue(
            text,
            text.contains(
                "IPv6 (tünel): ayar açık · ağda küresel IPv6 yok · IPv6 varsayılan yol yok · " +
                    "erişim denemesi yapılmadı (failed: /<adres> ENETUNREACH) · tünelde IPv6 kapalı",
            ),
        )
        assertTrue(text, text.contains("Ayarlar: akıllı mod açık · otomatik yedek yöntem açık"))
        assertTrue(text, text.contains("ciadpi -i 127.0.0.1 -p 1080"))
        assertTrue(text, text.contains("Bağlantı testi (motor üzerinden):"))
        assertTrue(text, text.contains("  ✓ www.google.com — IPv4: ✓ 312 ms"))
        // HTTP 200 robot dogrulamasi olmadiginin kaniti sayilmaz.
        assertTrue(text, text.contains("      " + ConnectionTester.NO_CAPTCHA_NOTE))
        assertFalse(text, text.contains("4860"))
    }

    @Test
    fun reportWhenDisconnectedOrUntested() {
        val text = DiagReport.build(
            device.copy(transport = null, operator = null, underlyingAddresses = null, underlyingV6DefaultRoute = null, tunAddresses = null),
            running = false,
            ipv6 = null,
            settingsLine = "Ayarlar: x",
            engineText = "Bağlı değil; bağlanınca çalışacak komut:\nciadpi",
            tests = emptyList(),
            testsViaProxy = false,
            testRunning = false,
        )
        assertTrue(text, text.contains("Ağ: bağlı ağ yok"))
        assertTrue(text, text.contains("Tünel adresleri: tünel yok"))
        assertTrue(text, text.contains("IPv6 (tünel): bağlı değil"))
        assertTrue(text, text.endsWith("Bağlantı testi: yapılmadı (Ayarlar → BAĞLANTI TESTİ)"))
        assertEquals("IPv6 (tünel): servis durum bildirmedi", DiagReport.ipv6Line(true, null))
        assertTrue(DiagReport.ipv6Line(true, v6On).endsWith("erişim denemesi başarılı (41 ms) · tünelde IPv6 açık"))
    }

    @Test
    fun directTestWithoutAaaaFromTheSystemResolverIsNotNoRecord() {
        // IPv6'siz mobil veride, VPN kapali: Android'in cozucusu AAAA hic sormuyor (Google icin
        // bile 0 AAAA). "kayıt yok" sitenin IPv6'si yok sanilmasina yol aciyordu.
        val g = SiteResult(
            "www.google.com", true, 549, null,
            v4 = FamilyResult(true, 549, httpStatus = 200),
            dns = DnsInfo(8, 0, DnsSource.SYSTEM),
        )
        assertEquals(
            "IPv4: ✓ 549 ms · IPv6: " + ConnTestText.V6_NOT_ASKED,
            ConnTestText.parts(g, null, viaProxy = false).joinToString("") { it.text },
        )
        assertEquals(
            "    DNS: 8 A, 0 AAAA (sistem çözücüsü; ağda IPv6 yoksa AAAA sorulmaz) · IPv4 HTTP 200",
            ConnTestText.reportLines(g, null, false)[1],
        )
        // Motor uzerinden (secili DNS) 0 AAAA gercekten kayit yok demek.
        val viaEngine = g.copy(dns = DnsInfo(8, 0, DnsSource.SELECTED))
        assertTrue(ConnTestText.parts(viaEngine, null, true).joinToString("") { it.text }.endsWith("IPv6: kayıt yok"))
        // Aciklama satirlarda degil listede bir kez; motor uzerinden testte yok.
        assertEquals(ConnTestText.V6_NOT_ASKED_NOTE, ConnTestText.notAskedNote(listOf(g, g), viaProxy = false))
        assertNull(ConnTestText.notAskedNote(listOf(viaEngine), viaProxy = true))
        assertNull(ConnTestText.notAskedNote(listOf(g.copy(v6 = FamilyResult(true, 30))), viaProxy = false))
    }

    @Test
    fun googleNoCaptchaNoteOnlyForGoogleWithAnAnswer() {
        assertTrue(ConnTestText.reportLines(google, noV6, true).contains("    " + ConnectionTester.NO_CAPTCHA_NOTE))
        val discord = SiteResult("discord.com", true, 90, null, v4 = FamilyResult(true, 90, httpStatus = 200), dns = DnsInfo(1, 0, DnsSource.SELECTED))
        assertFalse(ConnTestText.reportLines(discord, null, true).any { it.contains("robot") })
        val down = google.copy(ok = false, v4 = FamilyResult(false, null, ConnectionTester.ERR_RESET))
        assertFalse(ConnTestText.reportLines(down, noV6, true).any { it.contains("robot") })
    }

    @Test
    fun operatorIsLabelledAsSimWhenNotOnMobileData() {
        fun netLine(d: DeviceInfo) = DiagReport.build(d, false, null, "Ayarlar: x", "x", emptyList(), false, false).lines()[1]
        assertEquals("Ağ: Mobil veri · operatör: TR TURKCELL", netLine(device))
        assertEquals("Ağ: Wi-Fi · SIM operatörü: TR TURKCELL (etkin ağ değil)", netLine(device.copy(transport = "Wi-Fi")))
        assertEquals("Ağ: Wi-Fi", netLine(device.copy(transport = "Wi-Fi", operator = null)))
    }

    @Test
    fun privateDnsHostnameHidesProfileIds() {
        assertEquals("*.dns.nextdns.io", AddressKinds.redactHostname("abc123.dns.nextdns.io"))
        assertEquals("*.dns.nextdns.io", AddressKinds.redactHostname("abcdef.dns.nextdns.io"))
        assertEquals("*.dns.controld.com", AddressKinds.redactHostname("x7k2p9q.dns.controld.com"))
        // Genel adlar oldugu gibi kalir.
        assertEquals("dns.google", AddressKinds.redactHostname("dns.google"))
        assertEquals("one.one.one.one", AddressKinds.redactHostname("one.one.one.one"))
        assertEquals("dns.adguard-dns.com", AddressKinds.redactHostname("dns.adguard-dns.com"))
        assertEquals("family.adguard-dns.com", AddressKinds.redactHostname("family.adguard-dns.com"))
        assertEquals("dns.quad9.net", AddressKinds.redactHostname("DNS.Quad9.net."))
        assertEquals("p2.freedns.controld.com", AddressKinds.redactHostname("p2.freedns.controld.com"))
        assertEquals("*.*.adguard-dns.com", AddressKinds.redactHostname("a1b2c3.d.adguard-dns.com"))
    }

    @Test
    fun privateDnsHostnameHidesUnknownServersEntirely() {
        // Kendi DoT sunucusu: ilk etiket genel ("dns") olsa da alan adi kisinin kendisi.
        assertEquals(AddressKinds.HIDDEN_HOST, AddressKinds.redactHostname("dns.ahmetyilmaz.dev"))
        assertEquals(AddressKinds.HIDDEN_HOST, AddressKinds.redactHostname("ahmetyilmaz.duckdns.org"))
        assertEquals(AddressKinds.HIDDEN_HOST, AddressKinds.redactHostname("ahmetyilmaz.net"))
        assertEquals(AddressKinds.HIDDEN_HOST, AddressKinds.redactHostname("localhost"))
        // Saglayici adi baska bir alanin icinde gecse de (sonek degil) gizlenir.
        assertEquals(AddressKinds.HIDDEN_HOST, AddressKinds.redactHostname("dns.google.ahmet.dev"))
    }
}
