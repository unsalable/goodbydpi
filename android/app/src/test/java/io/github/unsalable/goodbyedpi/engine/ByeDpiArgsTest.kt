package io.github.unsalable.goodbyedpi.engine

import io.github.unsalable.goodbyedpi.model.AppSettings
import io.github.unsalable.goodbyedpi.model.CustomDnsEntry
import io.github.unsalable.goodbyedpi.model.CustomMethodProfile
import io.github.unsalable.goodbyedpi.model.DnsProfile
import io.github.unsalable.goodbyedpi.model.DpiConfig
import io.github.unsalable.goodbyedpi.model.FakePayload
import io.github.unsalable.goodbyedpi.model.IspProfile
import io.github.unsalable.goodbyedpi.model.MethodPreset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ByeDpiArgsTest {
    private val base = listOf("-i", "127.0.0.1", "-p", "10808", "-c", "2048", "-b", "16384", "-N")
    private val deny = listOf("--deny-net", "198.18.0.0/15", "--deny-net", "fd00:6764:7069::/48")
    private val sni = listOf("--fake-sni", "www.w3.org")
    private val realMd5 = ByeDpiArgs.md5SigSupport

    // Varsayilan: MD5'i destekleyen cekirdek (BYEDPI_NOTES 7.2 tablosu --md5sig'i gosterir).
    @Before
    fun md5Supported() {
        ByeDpiArgs.md5SigSupport = { true }
    }

    @After
    fun restoreMd5() {
        ByeDpiArgs.md5SigSupport = realMd5
    }

    private fun cfg(
        primary: DpiConfig,
        fallbacks: List<DpiConfig> = emptyList(),
        dns: DnsProfile = DnsProfile.Off,
        ipv6: Boolean = true,
        smart: Boolean = false,
    ) = EngineConfig("x", primary, fallbacks, dns, excludeLan = true, ipv6 = ipv6, socksPort = 10808, smartMode = smart)

    /** Yalnizca TCP grubu: QUIC/ses kapali, DNS yok, yedek yok. */
    private fun group(p: DpiConfig): List<String> =
        ByeDpiArgs.build(cfg(p.copy(blockQuic = false, voiceFake = false))).drop(base.size + deny.size)

    // BYEDPI_NOTES 7.2 tablosu, birebir (fragmentHttp acik).
    private val expectedPresetGroups = mapOf(
        "default" to listOf("--proto=tls,http", "--disorder", "2", "--split", "0+hm", "--fake", "-1", "--ttl", "5") + sni,
        "fixedttl" to listOf("--proto=tls,http", "--fake", "-1", "--ttl", "5") + sni,
        "disorder" to listOf("--proto=tls,http", "--disorder", "2"),
        "ttl4" to listOf("--proto=tls,http", "--fake", "-1", "--ttl", "4") + sni,
        "ttl3" to listOf("--proto=tls,http", "--fake", "-1", "--ttl", "3") + sni,
        "md5sig" to listOf("--proto=tls,http", "--fake", "-1", "--ttl", "5", "--md5sig") + sni,
        "md5ttl3" to listOf("--proto=tls,http", "--fake", "-1", "--ttl", "3", "--md5sig") + sni,
        "fakesplit5" to listOf("--proto=tls,http", "--fake", "2", "--fake", "-1", "--ttl", "5") + sni,
        "zerofake" to listOf("--proto=tls,http", "--fake", "-1", "--ttl", "5", "--fake-data", ":\\x00\\x00\\x00\\x00"),
        "split2" to listOf("--proto=tls,http", "--split", "2"),
        "split" to listOf("--proto=tls,http", "--split", "2", "--split", "0+hm"),
        "tlsrec" to listOf("--proto=tls,http", "--tlsrec", "3+s"),
    )

    @Test
    fun everyPresetMapsToNotesTable() {
        assertEquals(MethodPreset.all.map { it.id }.toSet(), expectedPresetGroups.keys)
        for (p in MethodPreset.all) {
            assertEquals(p.id, expectedPresetGroups.getValue(p.id), group(p.build().sanitized()))
        }
    }

    @Test
    fun zeroFakeIsLiteralBackslashes() {
        // Tek argv ogesi, kabuk yok: byedpi parse_cform "\x00" kacisini kendisi cozer.
        assertEquals(":\\x00\\x00\\x00\\x00", ByeDpiArgs.ZERO_FAKE_DATA)
        assertEquals(17, ByeDpiArgs.ZERO_FAKE_DATA.length)
    }

    @Test
    fun fragmentHttpOffScopesTlsOnly() {
        val g = group(MethodPreset.Disorder.build().copy(fragmentHttp = false))
        assertEquals(listOf("--proto=tls", "--disorder", "2"), g)
    }

    @Test
    fun baseArgsAlwaysFirst() {
        val a = ByeDpiArgs.build(cfg(DpiConfig()))
        assertEquals(base, a.take(base.size))
        // Genel secenekler bir daha tekrarlanmaz.
        assertEquals(1, a.count { it == "-i" })
        assertEquals(1, a.count { it == "-p" })
    }

    @Test
    fun noDomainAndVirtualNetsDeniedAlways() {
        // Sozlesme C2: hev hep IP gonderir; ad cozumleme kapali, sanal aglar (FROM disinda) reddedilir.
        for (dns in listOf(DnsProfile.Off, DnsProfile.Yandex)) for (v6 in listOf(true, false)) {
            val a = ByeDpiArgs.build(cfg(DpiConfig(), dns = dns, ipv6 = v6))
            assertEquals(1, a.count { it == "-N" })
            assertTrue(a.containsSeq(deny))
            // Genel secenekler: ilk --proto'dan once gelir (grup secenegi sayilmasin diye degil,
            // okunurluk icin; byedpi icin sira onemsiz).
            assertTrue(a.indexOf("--deny-net") < a.indexOfFirst { it.startsWith("--proto=") })
        }
        // Yonlendirilen sanal cozucu adresi reddedilen agin icinde: FROM eslesmesi reddi yener.
        val r = ByeDpiArgs.build(cfg(DpiConfig(), dns = DnsProfile.Yandex))
        assertTrue(r.containsSeq(listOf("--redirect", "198.18.0.53:53=77.88.8.8:1253")))
    }

    @Test
    fun md5DroppedWhenKernelLacksIt() {
        ByeDpiArgs.md5SigSupport = { false }
        // GKI: byedpi zaten yalnizca TTL kullanir; argv de oyle ve kopyalar birlesir.
        assertEquals(expectedPresetGroups.getValue("fixedttl"), group(MethodPreset.Md5Sig.build().sanitized()))
        assertEquals(expectedPresetGroups.getValue("ttl3"), group(MethodPreset.Md5Ttl3.build().sanitized()))
        val so = IspProfile.Superonline.methods.map { it.build().sanitized() }
        val a = ByeDpiArgs.build(cfg(so.first(), so.drop(1)))
        assertFalse("--md5sig" in a)
        // ttl3 birincil; md5ttl3 (== ttl3) atlanir, md5sig (TTL 5) ve disorder kalir.
        assertEquals(2, a.count { it.startsWith("--auto=torst") })

        // Bilinmiyor (null): eskisi gibi --md5sig verilir, byedpi gerekirse TTL'e duser.
        ByeDpiArgs.md5SigSupport = { null }
        assertTrue("--md5sig" in group(MethodPreset.Md5Sig.build().sanitized()))
        ByeDpiArgs.md5SigSupport = { true }
        assertEquals(3, ByeDpiArgs.build(cfg(so.first(), so.drop(1))).count { it.startsWith("--auto=torst") })
    }

    @Test
    fun blockQuicAddsDropUdp() {
        val on = ByeDpiArgs.build(cfg(DpiConfig(voiceFake = false, blockQuic = true)))
        val off = ByeDpiArgs.build(cfg(DpiConfig(voiceFake = false, blockQuic = false)))
        assertTrue(on.containsSeq(listOf("--drop-udp", "443")))
        assertFalse("--drop-udp" in off)
    }

    @Test
    fun voiceFakeGroupsComeBeforePrimary() {
        val c = DpiConfig(blockQuic = false, voiceFake = true, voiceFakeRepeats = 9)
        val a = ByeDpiArgs.build(cfg(c)).drop(base.size + deny.size)
        val voice = ByeDpiArgs.VOICE_PORT_RANGES.flatMap {
            listOf("--proto=udp", "--pf=$it", "--udp-fake", "9", "--ttl", "64", "--auto=none")
        }
        assertEquals(voice, a.take(voice.size))
        assertEquals("--proto=tls,http", a[voice.size])
        // --pf ondalik, bastaki sifir yok (strtol base 0 sekizlik okurdu).
        a.filter { it.startsWith("--pf=") }.forEach { pf ->
            pf.removePrefix("--pf=").split('-').forEach { assertFalse(pf, it.length > 1 && it.startsWith("0")) }
        }
    }

    @Test
    fun voiceFakeOffHasNoUdpGroups() {
        val a = ByeDpiArgs.build(cfg(DpiConfig(voiceFake = false)))
        assertFalse(a.any { it.startsWith("--pf=") || it == "--udp-fake" || it == "--proto=udp" })
        assertFalse(a.any { it.startsWith("--auto") })
    }

    @Test
    fun autoFallbackFollowsPrimaryDirectly() {
        val primary = MethodPreset.Default.build()
        val fbs = listOf(MethodPreset.Disorder.build(), MethodPreset.SplitOnly.build(), MethodPreset.TlsRec.build())
        val a = ByeDpiArgs.build(cfg(primary, fbs, DnsProfile.Yandex))
        // BYEDPI_NOTES 7.3 ornegi (tools/native/smoke.py FULL_LAYOUT), port 10808.
        val expected = base +
            listOf("--redirect", "198.18.0.53:53=77.88.8.8:1253") +
            listOf("--redirect", "[fd00:6764:7069::53]:53=[2a02:6b8::feed:0ff]:1253") +
            deny +
            listOf("--drop-udp", "443") +
            ByeDpiArgs.VOICE_PORT_RANGES.flatMap {
                listOf("--proto=udp", "--pf=$it", "--udp-fake", "6", "--ttl", "64", "--auto=none")
            } +
            expectedPresetGroups.getValue("default") +
            listOf("--auto=torst,ssl_err", "--proto=tls,http", "--disorder", "2", "--cache-ttl", "3600") +
            listOf("--auto=torst,ssl_err", "--proto=tls,http", "--split", "2", "--split", "0+hm", "--cache-ttl", "3600") +
            listOf("--auto=torst,ssl_err", "--proto=tls,http", "--tlsrec", "3+s", "--cache-ttl", "3600") +
            listOf("--timeout", "4:0:0:1")
        assertEquals(expected, a)
    }

    @Test
    fun smartModeStartsDirectAndUsesMethodAsFirstFallback() {
        val primary = MethodPreset.Default.build()
        val fbs = listOf(MethodPreset.Disorder.build(), MethodPreset.SplitOnly.build(), MethodPreset.TlsRec.build())
        val a = ByeDpiArgs.build(cfg(primary, fbs, DnsProfile.Yandex, smart = true))
        // BYEDPI_NOTES 7.3 akilli mod ornegi (tools/native/smoke.py SMART_LAYOUT), port 10808.
        val expected = base +
            listOf("--redirect", "198.18.0.53:53=77.88.8.8:1253") +
            listOf("--redirect", "[fd00:6764:7069::53]:53=[2a02:6b8::feed:0ff]:1253") +
            deny +
            listOf("--drop-udp", "443") +
            ByeDpiArgs.VOICE_PORT_RANGES.flatMap {
                listOf("--proto=udp", "--pf=$it", "--udp-fake", "6", "--ttl", "64", "--auto=none")
            } +
            // Dogrudan grup: yalnizca kapsam, parca yok; statik oldugu icin yeni baglantilar buraya.
            listOf("--proto=tls,http") +
            listOf("--auto=torst,ssl_err") + expectedPresetGroups.getValue("default") + listOf("--cache-ttl", "3600") +
            listOf("--auto=torst,ssl_err", "--proto=tls,http", "--disorder", "2", "--cache-ttl", "3600") +
            listOf("--auto=torst,ssl_err", "--proto=tls,http", "--split", "2", "--split", "0+hm", "--cache-ttl", "3600") +
            listOf("--auto=torst,ssl_err", "--proto=tls,http", "--tlsrec", "3+s", "--cache-ttl", "3600") +
            listOf("--timeout", "4:0:0:1")
        assertEquals(expected, a)
        assertWellFormed(a)
    }

    @Test
    fun smartModeGroupOrderKeepsFallbackChain() {
        // Her ISS listesi ve her hazir yontem icin: dogrudan grup son statik TCP grubu, hemen
        // ardindan yedekler gelir ve aralarinda baska statik grup (--auto=none) yok (2.1.4).
        for (isp in IspProfile.all) for (preset in isp.methods) for (fb in listOf(true, false)) {
            val s = AppSettings(isp = isp.id, method = preset.id, autoFallback = fb, dns = DnsProfile.YANDEX_ID).migrate()
            val c = EngineConfig.from(s).copy(socksPort = 10808)
            assertTrue(c.smartMode)
            val a = ByeDpiArgs.build(c)
            assertWellFormed(a)
            val direct = a.indexOfLast { it == "--auto=none" } + 1
            assertEquals(ByeDpiArgs.scopeOf(c.primary), a[direct])
            assertEquals("--auto=torst,ssl_err", a[direct + 1])
            assertEquals(listOf(ByeDpiArgs.scopeOf(c.primary)) + ByeDpiArgs.tcpGroup(c.primary), a.subList(direct + 2, direct + 2 + 1 + ByeDpiArgs.tcpGroup(c.primary).size))
            assertTrue(a.subList(direct, a.size).none { it == "--auto=none" })
            // Otomatik yedek kapaliyken bile secili yontem tek yedek olarak kalir.
            val autos = a.count { it.startsWith("--auto=torst") }
            if (!fb) assertEquals(1, autos) else assertTrue(autos >= 2)
            assertTrue(a.containsSeq(listOf("--timeout", "4:0:0:1")))
        }
    }

    @Test
    fun smartModeWithEmptyMethodHasNoFallback() {
        // Hic parca uretmeyen ozel profil dogrudan grupla ayni: tekrar eklenmez, zaman asimi yok.
        val empty = DpiConfig(fakePacket = false, splitTls = false, tlsRecordSplit = false, voiceFake = false, blockQuic = false)
        val a = ByeDpiArgs.build(cfg(empty, smart = true))
        assertEquals(base + deny + listOf("--proto=tls,http"), a)
        // Yedek varsa dogrudan gruptan sonra o gelir.
        val b = ByeDpiArgs.build(cfg(empty, listOf(MethodPreset.TlsRec.build()), smart = true))
        assertEquals(1, b.count { it.startsWith("--auto=torst") })
        assertTrue(b.containsSeq(listOf("--proto=tls,http", "--auto=torst,ssl_err", "--proto=tls,http", "--tlsrec", "3+s")))
    }

    @Test
    fun smartModeFollowsSettings() {
        val on = EngineConfig.from(AppSettings().migrate())
        assertTrue(on.smartMode)
        val off = EngineConfig.from(AppSettings(smartMode = false).migrate())
        assertFalse(off.smartMode)
        // Kapaliyken 1.0.0 argv'si: secili yontem ilk (statik) grup.
        val a = ByeDpiArgs.build(off.copy(socksPort = 10808))
        val first = a.indexOfLast { it == "--auto=none" } + 1
        assertEquals(expectedPresetGroups.getValue("default"), a.subList(first, first + expectedPresetGroups.getValue("default").size))
        // Mod degisimi motoru yeniden kurdurur (argv anahtarin parcasi).
        assertFalse(on.sameEngineAs(off))
    }

    @Test
    fun fallbackDuplicatesSkippedAndTimeoutOnlyWithFallbacks() {
        val p = MethodPreset.Disorder.build()
        // Birincille ayni grup ve tekrar eden yedek atlanir.
        val a = ByeDpiArgs.build(cfg(p, listOf(p, MethodPreset.PlainSplit.build(), MethodPreset.PlainSplit.build())))
        assertEquals(1, a.count { it.startsWith("--auto=torst") })
        assertTrue(a.containsSeq(listOf("--timeout", "4:0:0:1")))

        val none = ByeDpiArgs.build(cfg(p, listOf(p)))
        assertFalse(none.any { it.startsWith("--auto=torst") })
        assertFalse("--timeout" in none)
    }

    @Test
    fun fallbackGroupsUsePrimaryScope() {
        val p = MethodPreset.Disorder.build().copy(fragmentHttp = false)
        val a = ByeDpiArgs.build(cfg(p, listOf(MethodPreset.TlsRec.build())))
        val i = a.indexOf("--auto=torst,ssl_err")
        assertEquals("--proto=tls", a[i + 1])
    }

    @Test
    fun dnsRedirects() {
        val cf = cfg(DpiConfig(), dns = DnsProfile.Cloudflare)
        assertEquals("1.1.1.1:53", cf.dnsTargetV4)
        assertEquals("[2606:4700:4700::1111]:53", cf.dnsTargetV6)
        val a = ByeDpiArgs.build(cf)
        assertTrue(a.containsSeq(listOf("--redirect", "198.18.0.53:53=1.1.1.1:53")))
        assertTrue(a.containsSeq(listOf("--redirect", "[fd00:6764:7069::53]:53=[2606:4700:4700::1111]:53")))

        // IPv6 kapali: yalnizca v4 sanal cozucu.
        val v4only = ByeDpiArgs.build(cfg(DpiConfig(), dns = DnsProfile.Yandex, ipv6 = false))
        assertEquals(1, v4only.count { it == "--redirect" })
        assertTrue(v4only.containsSeq(listOf("--redirect", "198.18.0.53:53=77.88.8.8:1253")))

        // Kapali: hic yonlendirme yok.
        val off = cfg(DpiConfig(), dns = DnsProfile.Off)
        assertFalse(off.redirectsDns)
        assertFalse("--redirect" in ByeDpiArgs.build(off))
    }

    @Test
    fun customDnsVariants() {
        val v4only = CustomDnsEntry(v4 = "9.9.9.9", v4Port = 5353).toProfile()
        val a = cfg(DpiConfig(), dns = v4only)
        assertEquals("9.9.9.9:5353", a.dnsTargetV4)
        assertNull(a.dnsTargetV6)

        // Yalnizca IPv6 girilmis: v4 sanal cozucu aileler arasi v6'ya yonlenir.
        val v6only = CustomDnsEntry(v6 = "2620:fe::fe", v6Port = 0).toProfile()
        val b = cfg(DpiConfig(), dns = v6only)
        assertEquals("[2620:fe::fe]:53", b.dnsTargetV4)
        assertEquals("[2620:fe::fe]:53", b.dnsTargetV6)
        assertEquals("[2620:fe::fe]:53", cfg(DpiConfig(), dns = v6only, ipv6 = false).dnsTargetV4)
        assertNull(cfg(DpiConfig(), dns = v6only, ipv6 = false).dnsTargetV6)

        // Adres girilmemis ozel giris = yonlendirme yok.
        assertFalse(cfg(DpiConfig(), dns = CustomDnsEntry().toProfile()).redirectsDns)
    }

    @Test
    fun customConfigsMapFieldByField() {
        // splitTls kapaliyken splitSni/reverseSplit/splitPosition etkisiz.
        assertEquals(
            listOf("--proto=tls,http"),
            group(DpiConfig(fakePacket = false, splitTls = false, splitSni = true, tlsRecordSplit = false)),
        )
        // Duz bolme + SNI, konum 7.
        assertEquals(
            listOf("--proto=tls,http", "--split", "7", "--split", "0+hm"),
            group(DpiConfig(fakePacket = false, reverseSplit = false, splitPosition = 7)),
        )
        // Korumasiz sahte (TTL de MD5 de kapali) yine --ttl alir.
        assertEquals(
            listOf("--proto=tls,http", "--fake", "-1", "--ttl", "9") + sni,
            group(DpiConfig(fakeTtl = false, fakeMd5Sig = false, ttl = 9, splitTls = false)),
        )
        // Bolme varken sahte ikiye bolunmez (iptal olurdu).
        assertEquals(
            listOf("--proto=tls,http", "--disorder", "2", "--fake", "-1", "--ttl", "5") + sni,
            group(DpiConfig(splitFake = true, splitSni = false)),
        )
        // Ozel SNI ve bos sahte + tlsrec birlikte, sirayla.
        assertEquals(
            listOf("--proto=tls", "--split", "3", "--fake", "-1", "--ttl", "6", "--md5sig", "--fake-data", ByeDpiArgs.ZERO_FAKE_DATA, "--tlsrec", "3+s"),
            group(
                DpiConfig(
                    ttl = 6, fakeMd5Sig = true, fakePayload = FakePayload.ZEROS, reverseSplit = false,
                    splitPosition = 3, splitSni = false, tlsRecordSplit = true, fragmentHttp = false,
                ),
            ),
        )
        assertEquals(
            listOf("--proto=tls,http", "--fake", "-1", "--ttl", "5", "--fake-sni", "cdn.example.org"),
            group(DpiConfig(fakeSni = "cdn.example.org", splitTls = false)),
        )
    }

    @Test
    fun matrixProducesWellFormedArgv() {
        val dnsList = listOf(DnsProfile.Off, DnsProfile.Cloudflare, DnsProfile.Yandex)
        var cases = 0
        for (preset in MethodPreset.all) for (frag in listOf(true, false)) for (quic in listOf(true, false))
            for (voice in listOf(true, false)) for (fb in listOf(true, false)) for (dns in dnsList)
                for (v6 in listOf(true, false)) for (smart in listOf(true, false)) {
                    val p = preset.build().copy(fragmentHttp = frag, blockQuic = quic, voiceFake = voice).sanitized()
                    val settings = AppSettings(method = preset.id, isp = IspProfile.TurkTelekom.id, autoFallback = fb)
                    val fallbacks = EngineConfig.from(settings).fallbacks
                    val c = cfg(p, fallbacks, dns, v6, smart)
                    val a = ByeDpiArgs.build(c)
                    cases++
                    assertWellFormed(a)
                    assertEquals(quic, a.containsSeq(listOf("--drop-udp", "443")))
                    assertEquals(voice, "--udp-fake" in a)
                    assertEquals(if (frag) true else false, "--proto=tls,http" in a)
                    assertEquals(dns.isActive, "--redirect" in a)
                    assertEquals(dns.isActive && v6, a.any { it.startsWith("[fd00:6764:7069::53]:53=") })
                    // Akilli modda secili yontem her zaman yedek: zaman asimi da her zaman.
                    assertEquals(smart || (fb && fallbacks.isNotEmpty()), "--timeout" in a)
                    // Tum kullanici gruplari + ekli catch-all <= 64.
                    assertTrue(a.count { it.startsWith("--auto") } + 2 <= 64)
                    // describe kararli ve argv'yi aynen tasiyor.
                    assertEquals(ByeDpiArgs.describe(c), ByeDpiArgs.describe(c))
                    assertEquals("ciadpi " + a.joinToString(" "), ByeDpiArgs.describe(c))
                }
        assertEquals(12 * 2 * 2 * 2 * 2 * 3 * 2 * 2, cases)
    }

    @Test
    fun customProfileFallsBackToIspRecommendations() {
        val custom = CustomMethodProfile(config = DpiConfig(fakePacket = false, splitTls = false, tlsRecordSplit = true))
        val s = AppSettings(isp = IspProfile.Vodafone.id, method = custom.id, customProfiles = listOf(custom)).migrate()
        val c = EngineConfig.from(s)
        // Ozel profilde saglayicinin TUM yontemleri (onerilen dahil) yedek.
        assertEquals(IspProfile.Vodafone.methodIds.size, c.fallbacks.size)
        assertTrue(c.smartMode)
        // Akilli mod (varsayilan): once ozel profilin kendisi, sonra saglayicinin yontemleri.
        val a = ByeDpiArgs.build(c.copy(socksPort = 1))
        assertEquals(IspProfile.Vodafone.methodIds.size + 1, a.count { it.startsWith("--auto=torst") })
        assertWellFormed(a)
        val legacy = ByeDpiArgs.build(c.copy(socksPort = 1, smartMode = false))
        assertEquals(IspProfile.Vodafone.methodIds.size, legacy.count { it.startsWith("--auto=torst") })
        assertWellFormed(a)
    }

    @Test
    fun describeQuotesOnlyForDisplay() {
        val c = cfg(DpiConfig(splitTls = false, fakePayload = FakePayload.ZEROS, voiceFake = false, blockQuic = false))
        assertEquals(
            "ciadpi -i 127.0.0.1 -p 10808 -c 2048 -b 16384 -N --deny-net 198.18.0.0/15 --deny-net fd00:6764:7069::/48 " +
                "--proto=tls,http --fake -1 --ttl 5 --fake-data :\\x00\\x00\\x00\\x00",
            ByeDpiArgs.describe(c),
        )
    }

    @Test
    fun runtimeKeyIgnoresPortButSeesDnsAddress() {
        val a = cfg(DpiConfig(), dns = CustomDnsEntry(v4 = "9.9.9.9").toProfile())
        val b = a.copy(socksPort = 4444)
        assertEquals(a.runtimeKey(), b.runtimeKey())
        // DnsProfile esitligi yalnizca kimlik; adres degisince anahtar degismeli.
        val c = cfg(DpiConfig(), dns = CustomDnsEntry(v4 = "8.8.8.8").toProfile())
        assertEquals(a.dns, c.dns)
        assertFalse(a.runtimeKey() == c.runtimeKey())
        assertFalse(a.runtimeKey() == a.copy(excludeLan = false).runtimeKey())
    }

    private fun assertWellFormed(a: List<String>) {
        assertTrue(a.none { it.isEmpty() })
        assertTrue(a.none { it.any(Char::isWhitespace) })
        assertTrue(a.size <= 1024)
        // Her deger alan secenegin bir degeri var.
        val needsValue = setOf(
            "-i", "-p", "-c", "-b", "--redirect", "--deny-net", "--drop-udp", "--udp-fake", "--ttl", "--split", "--disorder",
            "--fake", "--fake-sni", "--fake-data", "--tlsrec", "--cache-ttl", "--timeout",
        )
        a.forEachIndexed { i, t ->
            if (t in needsValue) {
                assertTrue("$t sonda", i + 1 < a.size)
                assertFalse("$t degeri yok", a[i + 1].startsWith("--"))
            }
        }
        // --auto'dan sonra hemen bir --proto: catch-all her zaman eklenir (BYEDPI_NOTES 2).
        a.forEachIndexed { i, t -> if (t.startsWith("--auto=")) assertTrue(a.getOrNull(i + 1)?.startsWith("--proto=") == true) }
    }

    private fun List<String>.containsSeq(seq: List<String>): Boolean =
        indices.any { i -> i + seq.size <= size && subList(i, i + seq.size) == seq }
}
