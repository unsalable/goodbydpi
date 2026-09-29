package io.github.unsalable.goodbyedpi.ui

import io.github.unsalable.goodbyedpi.model.AppSettings
import io.github.unsalable.goodbyedpi.model.CustomMethodProfile
import io.github.unsalable.goodbyedpi.model.DpiConfig
import io.github.unsalable.goodbyedpi.service.EngineState
import io.github.unsalable.goodbyedpi.ui.components.formatBytes
import io.github.unsalable.goodbyedpi.ui.components.formatRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Arayuzun saf mantigi: durum eslemesi, DNS uyarilari, bicimleme. */
class UiLogicTest {

    @Test
    fun dnsWarning_matchesDesktopMessages() {
        assertNull(dnsWarning("", "53", "", "53"))
        assertNull(dnsWarning("8.8.8.8", "53", "", ""))
        assertNull(dnsWarning("77.88.8.8", "1253", "2a02:6b8::feed:0ff", "1253"))
        assertEquals("Geçersiz IPv4 adresi.", dnsWarning("1.2.3", "53", "", "53"))
        assertEquals("Geçersiz IPv6 adresi.", dnsWarning("", "53", "2001:db8::zz", "53"))
        assertEquals("Port 0-65535 aralığında olmalı.", dnsWarning("1.1.1.1", "70000", "", "53"))
        // Bos port 53 sayilir (masaustu varsayilani).
        assertNull(dnsWarning("1.1.1.1", "", "", ""))
    }

    @Test
    fun hostNameRule_matchesSanitizedConfig() {
        assertTrue(isValidHostName("www.w3.org"))
        assertFalse(isValidHostName("kötü.com"))
        assertFalse(isValidHostName("a b.com"))
        assertFalse(isValidHostName(".example.com"))
        // Arayuzun kabul ettigi ad sanitized() tarafindan degistirilmez.
        assertEquals("cdn.example-1.net", DpiConfig(fakeSni = "cdn.example-1.net").sanitized().fakeSni)
    }

    @Test
    fun connectionUi_mapsEveryEngineState() {
        val s = AppSettings().migrate()
        assertEquals(PowerPhase.Off, ConnectionUi.from(EngineState.Stopped, s).phase)
        assertEquals("Bağlanıyor…", ConnectionUi.from(EngineState.Starting, s).title)
        assertEquals(PowerPhase.Connecting, ConnectionUi.from(EngineState.Stopping, s).phase)

        val failed = ConnectionUi.from(EngineState.Failed("VPN izni yok"), s)
        assertEquals(PowerPhase.Failed, failed.phase)
        assertEquals("Bağlantı kurulamadı", failed.title)
        assertEquals("VPN izni yok", failed.detail)

        val running = ConnectionUi.from(EngineState.Running(0, "Ters sıra", "Yandex (1253)", 1080), s)
        assertEquals("Bağlı", running.title)
        assertEquals("Ters sıra · DNS: Yandex (1253)", running.detail)
        assertEquals(1080, running.socksPort)

        // Genel disindaki saglayici ayrintinin basina eklenir.
        val tt = s.copy(isp = "turktelekom")
        assertEquals(
            "Türk Telekom · Ters sıra · DNS: Yandex (1253)",
            ConnectionUi.from(EngineState.Running(0, "Ters sıra", "Yandex (1253)", 0), tt).detail,
        )
        assertNull(ConnectionUi.from(EngineState.Running(0, "a", "b", 0), s).socksPort)
    }

    @Test
    fun settingsUi_exposesCustomEditorsOnlyWhenSelected() {
        val base = AppSettings().migrate()
        val ui = SettingsUi.from(base)
        assertNull(ui.customProfile)
        assertNull(ui.customDns)
        assertEquals(5, ui.methods.items.size - base.customProfiles.size)
        assertEquals(9, ui.isps.size)

        val profile = base.customProfiles.first()
        val custom = SettingsUi.from(base.copy(method = profile.id, dns = base.customDns.first().id))
        assertEquals(profile, custom.customProfile)
        assertEquals(base.customDns.first(), custom.customDns)
        // Ozel profilin ozeti cumle gibi buyuk harfle baslar.
        assertTrue(custom.method.description.first().isUpperCase())
    }

    @Test
    fun settingsUi_methodListFollowsIsp() {
        val tt = SettingsUi.from(AppSettings(isp = "turktelekom").migrate())
        val ids = tt.methods.items.map { it.id }
        assertEquals(listOf("disorder", "ttl4", "ttl3", "default", CustomMethodProfile().id), ids)
    }

    @Test
    fun connectionUi_stoppingIsLabelledAndDisabled() {
        val s = AppSettings().migrate()
        val stopping = ConnectionUi.from(EngineState.Stopping, s)
        assertTrue(stopping.stopping)
        assertEquals("Durduruluyor", stopping.stateLabel)
        assertEquals("Bağlanıyor", ConnectionUi.from(EngineState.Starting, s).stateLabel)
        assertFalse(ConnectionUi.from(EngineState.Starting, s).stopping)
        assertEquals("Bağlı", ConnectionUi.from(EngineState.Running(0, "a", "b", 1), s).stateLabel)
    }

    @Test
    fun dnsRowWarning_marksTheOffendingField() {
        assertNull(dnsRowWarning("", "53", v6 = false))
        assertNull(dnsRowWarning("8.8.8.8", "", v6 = false))
        assertEquals(DnsRowWarning("Geçersiz IPv4 adresi.", address = true, port = false), dnsRowWarning("1.2.3", "53", v6 = false))
        assertEquals(DnsRowWarning("Port 0-65535 aralığında olmalı.", address = false, port = true), dnsRowWarning("1.2.3.4", "70000", v6 = false))
        assertEquals("Geçersiz IPv6 adresi.", dnsRowWarning("2001:db8::zz", "53", v6 = true)?.message)
        assertNull(dnsRowWarning("2001:db8::1", "853", v6 = true))
        assertEquals(DnsRowWarning("Geçersiz IPv4 adresi.", address = true, port = true), dnsRowWarning("1.2", "99999", v6 = false))
    }

    @Test
    fun draft_commitsOnlyValidChangedTextAndIgnoresOwnEcho() {
        val commits = mutableListOf<String>()
        val d = Draft("Özel", "Özel", null).apply {
            accept = { it.isNotBlank() }
            commit = { commits += it }
        }
        d.flush()
        assertTrue("degismeyen ad yazilmaz", commits.isEmpty())

        d.text = "Oyun "
        d.flush()
        d.flush()
        assertEquals(listOf("Oyun"), commits)

        // Kaydin yankisi gelmeden kullanici yazmaya devam etti: yanki kutuyu ezmemeli.
        d.text = "Oyun 2"
        d.onPersisted("Oyun")
        assertEquals("Oyun 2", d.text)

        // Bos ad kaydedilmez; odak kaybinda kayitli ad geri gelir.
        d.text = "   "
        d.flush(restoreIfRejected = true)
        assertEquals("Oyun", d.text)
        assertEquals(listOf("Oyun"), commits)

        // Disaridan gelen degisiklik (Onerilene don) kutuya yansir; ayni degeri yeniden yazmak mumkun.
        d.onPersisted("www.w3.org")
        assertEquals("www.w3.org", d.text)
        d.text = "Oyun"
        d.flush()
        assertEquals(listOf("Oyun", "Oyun"), commits)
    }

    @Test
    fun diagnostics_prefersTheRunningArgv() {
        val s = AppSettings().migrate()
        val running = EngineState.Running(0, "Ters sıra", "Yandex (1253)", 39889, listOf("-i", "127.0.0.1", "-p", "39889", "--fake-data", "a b"))
        val text = MainViewModel.diagnosticsText(running, s)
        assertTrue(text, text.contains("ciadpi -i 127.0.0.1 -p 39889 --fake-data 'a b'"))
        assertTrue(text.contains("Yöntem: Ters sıra"))

        // Calismiyorsa ayarlardan kurulur ve bunu soyler.
        val off = MainViewModel.diagnosticsText(EngineState.Stopped, s)
        assertTrue(off, off.startsWith("Bağlı değil"))
        assertTrue(off.contains("ciadpi -i 127.0.0.1"))
        assertEquals("ciadpi -p 1", MainViewModel.formatArgv(listOf("ciadpi", "-p", "1")))
    }

    @Test
    fun reflowLicense_joinsHardWrappedParagraphs() {
        val mit = "MIT License\n\nCopyright (c) 2021 a\nCopyright (c) 2022 b\n\n" +
            "Permission is hereby granted, free of charge, to any person\nobtaining a copy of this software.\n"
        assertEquals(
            "MIT License\n\nCopyright (c) 2021 a\nCopyright (c) 2022 b\n\n" +
                "Permission is hereby granted, free of charge, to any person obtaining a copy of this software.",
            reflowLicense(mit),
        )
        val list = "   4. Redistribution. You may\n      reproduce:\n\n      (a) You must give\n          a copy; and\n      (b) You must cause\n"
        assertEquals("4. Redistribution. You may reproduce:\n\n(a) You must give a copy; and\n(b) You must cause", reflowLicense(list))
        assertEquals("- a\n- b", reflowLicense("- a\n- b"))
        // CRLF ve bosluklu bos satir da paragraf ayiricidir.
        assertEquals("a b\n\nc", reflowLicense("a\r\nb\r\n  \r\nc"))
    }

    @Test
    fun byteFormatting_isTurkish() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("1023 B", formatBytes(1023))
        assertEquals("1,5 KB", formatBytes(1536))
        assertEquals("2,0 MB/s", formatRate(2L * 1024 * 1024))
        assertEquals("150 MB", formatBytes(150L * 1024 * 1024))
        assertEquals("1,00 GB", formatBytes(1024L * 1024 * 1024))
    }
}
