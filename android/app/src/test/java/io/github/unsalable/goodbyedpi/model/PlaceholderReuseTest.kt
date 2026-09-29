package io.github.unsalable.goodbyedpi.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "+ Yeni DNS" / "+ Yeni ozel ayar": migrate()'in koydugu el degmemis yer tutucu yeniden
 * kullanilir, yanina "Ozel DNS 2" / "Ozel 2" eklenmez (instrumented F6).
 */
class PlaceholderReuseTest {
    private val fresh = AppSettings().migrate()

    @Test
    fun freshInstallHasOnlyPlaceholders() {
        assertTrue(fresh.customDns.single().isUntouchedPlaceholder)
        assertTrue(fresh.customProfiles.single().isUntouchedPlaceholder)
    }

    @Test
    fun newDnsReusesEmptyPlaceholder() {
        val s = fresh.withNewCustomDns().migrate()
        assertEquals(1, s.customDns.size)
        assertEquals(CustomIds.LEGACY, s.dns)
        assertEquals(CustomDnsEntry.DEFAULT_NAME, s.customDns.single().name)
        // Ikinci dokunus da yeni bos giris uretmez.
        assertEquals(s, s.withNewCustomDns().migrate())
    }

    @Test
    fun duplicatingRealDnsFillsPlaceholderSlot() {
        val ev = CustomDnsEntry(id = "custom:0000000a", name = "Ev", v4 = "9.9.9.9", v4Port = 9953)
        val s = fresh.copy(customDns = fresh.customDns + ev, dns = ev.id).withNewCustomDns().migrate()
        assertEquals(2, s.customDns.size)
        val copy = s.customDns.first { it.id == CustomIds.LEGACY }
        assertEquals(CustomIds.LEGACY, s.dns)
        assertEquals("Ev 2", copy.name)
        assertEquals("9.9.9.9", copy.v4)
        assertEquals(9953, copy.v4Port)
        assertEquals(ev, s.customDns.first { it.id == ev.id })
    }

    @Test
    fun touchedDnsIsNotReused() {
        val named = fresh.copy(customDns = listOf(CustomDnsEntry(name = "Okul")))
        assertFalse(named.customDns.single().isUntouchedPlaceholder)
        val s1 = named.withNewCustomDns().migrate()
        assertEquals(2, s1.customDns.size)
        assertEquals(s1.customDns.last().id, s1.dns)

        val withAddress = fresh.copy(customDns = listOf(CustomDnsEntry(v4 = "1.1.1.1")), dns = "cloudflare")
        val s2 = withAddress.withNewCustomDns().migrate()
        assertEquals(2, s2.customDns.size)
        assertEquals("Özel DNS 2", s2.customDns.last().name)
    }

    @Test
    fun newProfileFromPresetReusesPlaceholder() {
        val preset = MethodPreset.fromId(MethodPreset.DEFAULT_ID)
        val seed = preset.build().copy(ttl = 3).sanitized()
        val s = fresh.withNewCustomProfile(seed, "${preset.name} (özel)").migrate()
        val only = s.customProfiles.single()
        assertEquals(CustomIds.LEGACY, only.id)
        assertEquals(only.id, s.method)
        assertEquals("${preset.name} (özel)", only.name)
        assertEquals(seed, only.config)
        assertFalse(only.isUntouchedPlaceholder)

        // Artik yer tutucu yok: sonraki profil yeni giris olur.
        val s2 = s.withNewCustomProfile(seed, "${preset.name} (özel)").migrate()
        assertEquals(2, s2.customProfiles.size)
        assertEquals("${preset.name} (özel) 2", s2.customProfiles.last().name)
        assertEquals(s2.customProfiles.last().id, s2.method)
    }

    @Test
    fun selectedPlaceholderDuplicateIsNoOp() {
        val selected = fresh.copy(method = CustomIds.LEGACY)
        assertEquals(selected, selected.withNewCustomProfile(DpiConfig(), CustomMethodProfile.DEFAULT_NAME).migrate())
    }

    @Test
    fun editedProfileIsNotReused() {
        val edited = fresh.copy(customProfiles = listOf(CustomMethodProfile(config = DpiConfig(ttl = 4))))
        val s = edited.withNewCustomProfile(DpiConfig(), "Yeni").migrate()
        assertEquals(2, s.customProfiles.size)
        assertEquals(4, s.customProfiles.first().config.ttl)
        assertEquals("Yeni", s.customProfiles.last().name)
    }
}
