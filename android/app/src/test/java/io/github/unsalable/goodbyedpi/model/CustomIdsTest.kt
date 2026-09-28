package io.github.unsalable.goodbyedpi.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomIdsTest {
    @Test
    fun isCustom() {
        assertTrue(CustomIds.isCustom("custom"))
        assertTrue(CustomIds.isCustom("CUSTOM"))
        assertTrue(CustomIds.isCustom("custom:0a1b2c3d"))
        assertTrue(CustomIds.isCustom("Custom:x"))
        assertFalse(CustomIds.isCustom(null))
        assertFalse(CustomIds.isCustom(""))
        assertFalse(CustomIds.isCustom("default"))
        assertFalse(CustomIds.isCustom("customx"))
    }

    @Test
    fun newIdLegacyFirst() {
        assertEquals("custom", CustomIds.newId(emptyList()))
        assertEquals("custom", CustomIds.newId(listOf("custom:12345678")))
    }

    @Test
    fun newIdPrefixAndHex() {
        val id = CustomIds.newId(listOf("custom"))
        assertTrue(id, Regex("custom:[0-9a-f]{8}").matches(id))
    }

    @Test
    fun newIdNeverCollides() {
        val seq = ArrayDeque(listOf("aaaaaaaa", "AAAAAAAA", "bbbbbbbb"))
        val id = CustomIds.newId(listOf("CUSTOM", "custom:aaaaaaaa")) { seq.removeFirst() }
        assertEquals("custom:bbbbbbbb", id)

        val taken = mutableListOf<String>()
        repeat(500) { taken += CustomIds.newId(taken) }
        assertEquals(500, taken.map { it.lowercase() }.toSet().size)
    }

    @Test
    fun newName() {
        assertEquals("Özel", CustomIds.newName("Özel", emptyList()))
        assertEquals("Özel 2", CustomIds.newName("Özel", listOf("Özel")))
        assertEquals("Özel 3", CustomIds.newName("Özel", listOf("Özel", "Özel 2")))
        // Cogaltma: "Özel 2" -> "Özel 3", "Özel 2 2" degil.
        assertEquals("Özel 3", CustomIds.newName("Özel 2", listOf("Özel", "Özel 2")))
        assertEquals("Özel 4", CustomIds.newName("Özel 2", listOf("Özel", "Özel 2", "Özel 3")))
        // Sayac 1 ya da sayi degilse ada sayac eklenir.
        assertEquals("Profil 1 2", CustomIds.newName("Profil 1", listOf("Profil 1")))
        assertEquals("Ev ağı 2", CustomIds.newName("Ev ağı", listOf("Ev ağı")))
    }

    @Test
    fun newNameCaseInsensitiveTurkish() {
        assertEquals("Özel 2", CustomIds.newName("Özel", listOf("ÖZEL")))
        assertEquals("özel 2", CustomIds.newName("özel", listOf("Özel")))
        assertEquals("Dış 2", CustomIds.newName("Dış", listOf("DIŞ")))
        assertEquals("İnternet 2", CustomIds.newName("İnternet", listOf("internet")))
        assertEquals("Internet 2", CustomIds.newName("Internet", listOf("INTERNET")))
        assertEquals("Özel 3", CustomIds.newName("Özel", listOf("özel", "ÖZEL 2")))
    }

    @Test
    fun cleanName() {
        assertEquals("Özel", CustomIds.cleanName(null, "Özel"))
        assertEquals("Özel", CustomIds.cleanName("", "Özel"))
        assertEquals("Özel", CustomIds.cleanName("   ", "Özel"))
        assertEquals("Ev", CustomIds.cleanName("  Ev ", "Özel"))
    }

    @Test
    fun createNewProfiles() {
        val first = CustomMethodProfile.createNew(emptyList())
        assertEquals("custom", first.id)
        assertEquals("Özel", first.name)
        assertEquals(DpiConfig(), first.config)

        val second = CustomMethodProfile.createNew(listOf(first), seed = DpiConfig(ttl = 3))
        assertTrue(second.id.startsWith("custom:"))
        assertEquals("Özel 2", second.name)
        assertEquals(3, second.config.ttl)

        val named = CustomMethodProfile.createNew(listOf(first, second), name = "  Ev  ")
        assertEquals("Ev", named.name)

        val preset = second.toPreset()
        assertEquals(second.id, preset.id)
        assertEquals(second.name, preset.name)
        assertEquals(second.config.summary, preset.description)
        assertEquals(second.config, preset.build())

        val dns1 = CustomDnsEntry.createNew(emptyList())
        assertEquals("custom", dns1.id)
        assertEquals("Özel DNS", dns1.name)
        val dns2 = CustomDnsEntry.createNew(listOf(dns1), seed = dns1.copy(v4 = "9.9.9.9", v4Port = 9953))
        assertEquals("Özel DNS 2", dns2.name)
        assertEquals("9.9.9.9", dns2.v4)
        assertEquals(9953, dns2.v4Port)
        assertTrue(dns2.id.startsWith("custom:"))
    }
}
