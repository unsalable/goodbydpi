package io.github.unsalable.goodbyedpi.model

import io.github.unsalable.goodbyedpi.data.SettingsRepository
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {
    private val json = SettingsRepository.json

    private fun decode(text: String) = json.decodeFromString(AppSettings.serializer(), text)
    private fun encode(s: AppSettings) = json.encodeToString(AppSettings.serializer(), s)

    @Test
    fun defaults() {
        val s = AppSettings()
        assertEquals(ThemeMode.SYSTEM, s.themeMode)
        assertEquals("general", s.isp)
        assertEquals("default", s.method)
        assertEquals("cloudflare", s.dns)
        assertFalse(s.autoConnect)
        assertTrue(s.startOnBoot)
        assertTrue(s.autoUpdate)
        assertTrue(s.autoFallback)
        assertTrue(s.excludeLan)
        assertTrue(s.ipv6)
        assertFalse(s.wantRunning)
    }

    @Test
    fun roundTrip() {
        val s = AppSettings(
            themeMode = ThemeMode.DARK,
            isp = "superonline",
            method = "custom:abcdef01",
            customProfiles = listOf(
                CustomMethodProfile(),
                CustomMethodProfile("custom:abcdef01", "Ev", DpiConfig(ttl = 3, fakePayload = FakePayload.ZEROS)),
            ),
            dns = "custom",
            customDns = listOf(CustomDnsEntry(v4 = "9.9.9.9", v4Port = 9953, v6 = "2620:fe::fe")),
            autoConnect = true,
            wantRunning = true,
            lastUpdateCheck = 1234567890123L,
            pendingUpdate = "1.2.0",
        )
        val text = encode(s)
        assertEquals(s, decode(text))
        assertTrue(text.contains("\"themeMode\": \"dark\""))
        assertTrue(text.contains("\"fakePayload\": \"ZEROS\""))
        // explicitNulls = false: null alanlar yazilmaz.
        assertFalse(text.contains("dismissedUpdate"))
        // Hesaplanan ozellikler dosyaya girmez.
        assertFalse(text.contains("summary"))
    }

    @Test
    fun unknownKeysIgnoredAndMissingKeysDefault() {
        val s = decode(
            """
            {
              "darkMode": true,
              "engine": "Native",
              "nativeProfile": "disorder",
              "isp": "turknet",
              "customProfiles": [ { "id": "custom", "name": "Eski", "config": { "ttl": 4, "autoTtl": true, "seqOverlap": 1 } } ],
              "futureField": { "a": [1, 2, 3] }
            }
            """.trimIndent(),
        )
        assertEquals("turknet", s.isp)
        assertEquals("default", s.method)
        assertEquals(ThemeMode.SYSTEM, s.themeMode)
        assertEquals(4, s.customProfiles.single().config.ttl)
        assertTrue(s.customProfiles.single().config.splitTls)
        assertTrue(s.customDns.isEmpty())
    }

    @Test
    fun badValuesCoerced() {
        val s = decode("""{ "themeMode": "neon", "ttl": 3, "autoConnect": null, "customProfiles": [ { "config": { "fakePayload": "???" } } ] }""")
        assertEquals(ThemeMode.SYSTEM, s.themeMode)
        assertFalse(s.autoConnect)
        assertEquals(FakePayload.TLS, s.customProfiles.single().config.fakePayload)
        assertEquals("custom", s.customProfiles.single().id)
    }

    @Test(expected = SerializationException::class)
    fun truncatedJsonThrows() {
        decode("""{ "isp": "turknet", "customProfiles": [ { "id": """)
    }

    @Test
    fun migrateGuaranteesListsAndIsIdempotent() {
        val m = AppSettings().migrate()
        assertEquals(listOf(CustomMethodProfile("custom", "Özel", DpiConfig())), m.customProfiles)
        assertEquals(listOf(CustomDnsEntry("custom", "Özel DNS")), m.customDns)
        assertEquals(m, m.migrate())
        assertEquals(m, m.migrate().migrate())
    }

    @Test
    fun migrateFixesBrokenData() {
        val broken = AppSettings(
            isp = "TurkNet",
            method = "checksum",
            dns = "custom:gone",
            customProfiles = listOf(
                CustomMethodProfile("custom", "  ", DpiConfig(ttl = 500)),
                CustomMethodProfile("CUSTOM", "Kopya"),
                CustomMethodProfile("default", "Cakisan"),
            ),
            customDns = listOf(CustomDnsEntry("custom:1", "Ev", v4 = " 1.1.1.1 ")),
        )
        val m = broken.migrate()
        assertEquals("turknet", m.isp)
        assertEquals("default", m.method)
        assertEquals("cloudflare", m.dns)
        assertEquals("Özel", m.customProfiles[0].name)
        assertEquals(64, m.customProfiles[0].config.ttl)
        assertEquals("custom", m.customProfiles[0].id)
        val ids = m.customProfiles.map { it.id.lowercase() }
        assertEquals(3, ids.toSet().size)
        assertTrue(ids.all { CustomIds.isCustom(it) })
        assertEquals("1.1.1.1", m.customDns.single().v4)
        assertEquals(m, m.migrate())
    }

    @Test
    fun migrateKeepsValidSelections() {
        val s = AppSettings(
            method = "Custom:ab",
            customProfiles = listOf(CustomMethodProfile(), CustomMethodProfile("custom:AB", "Ev")),
            dns = "YANDEX",
        ).migrate()
        assertEquals("custom:AB", s.method)
        assertEquals("yandex", s.dns)
        assertNull(s.pendingUpdate)
    }
}
