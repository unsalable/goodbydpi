package io.github.unsalable.goodbyedpi.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SelectionTest {
    private val ev = CustomMethodProfile("custom:ev", "Ev", DpiConfig(ttl = 99, fakePayload = FakePayload.ZEROS))
    private val base = AppSettings(
        isp = "turktelekom",
        customProfiles = listOf(CustomMethodProfile(), ev),
        customDns = listOf(CustomDnsEntry(), CustomDnsEntry("custom:d", "Ev DNS", v4 = "9.9.9.9", v4Port = 9953)),
    )

    @Test
    fun methodChoicesIspThenCustom() {
        assertEquals(
            listOf("ttl4", "disorder", "ttl3", "default", "custom", "custom:ev"),
            base.methodChoices().map { it.id },
        )
        assertEquals("Ev", base.methodChoices().last().name)
        assertEquals(IspProfile.TurkTelekom, base.ispProfile())
    }

    @Test
    fun customSelected() {
        val s = base.copy(method = "CUSTOM:EV")
        assertEquals("custom:ev", s.selectedMethod().id)
        // Motora giden yapilandirma gecerli araliga cekilir.
        assertEquals(64, s.selectedConfig().ttl)
        assertEquals(FakePayload.ZEROS, s.selectedConfig().fakePayload)
        // Ozel profilde yedekler saglayicinin tum yontemleri.
        assertEquals(listOf("ttl4", "disorder", "ttl3", "default"), s.fallbackMethods().map { it.id })
    }

    @Test
    fun deletedCustomFallsBackToDefault() {
        val s = base.copy(method = "custom:gone", dns = "custom:gone")
        assertSame(MethodPreset.Default, s.selectedMethod())
        assertEquals(DpiConfig(), s.selectedConfig())
        assertSame(DnsProfile.Yandex, s.selectedDns())
        assertEquals(listOf("ttl4", "disorder", "ttl3"), s.fallbackMethods().map { it.id })
    }

    @Test
    fun presetSelected() {
        val s = base.copy(method = "ttl4")
        assertSame(MethodPreset.FakeTtl4, s.selectedMethod())
        assertEquals(listOf("disorder", "ttl3", "default"), s.fallbackMethods().map { it.id })
        // Saglayici listesinde olmayan yontem de gecerli; yedekler tum ISS listesi.
        val other = base.copy(method = "tlsrec")
        assertSame(MethodPreset.TlsRec, other.selectedMethod())
        assertEquals(listOf("ttl4", "disorder", "ttl3", "default"), other.fallbackMethods().map { it.id })
        assertSame(MethodPreset.Default, base.copy(method = "checksum").selectedMethod())
    }

    @Test
    fun dnsChoicesAndSelection() {
        assertEquals(
            listOf("cloudflare", "yandex", "off", "custom", "custom:d"),
            base.dnsChoices().map { it.id },
        )
        val d = base.copy(dns = "custom:d").selectedDns()
        assertEquals("Ev DNS", d.name)
        assertEquals("9.9.9.9", d.v4Addr)
        assertEquals(9953, d.v4Port)
        assertSame(DnsProfile.Yandex, base.copy(dns = "yandex").selectedDns())
        assertSame(DnsProfile.Yandex, base.copy(dns = "whatever").selectedDns())
        assertSame(DnsProfile.Cloudflare, base.copy(dns = "cloudflare").selectedDns())
        // Bos ozel DNS: yonlendirme kapali.
        assertEquals(false, base.copy(dns = "custom").selectedDns().isActive)
    }
}
