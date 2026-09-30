package io.github.unsalable.goodbyedpi.data

import io.github.unsalable.goodbyedpi.model.AppSettings
import io.github.unsalable.goodbyedpi.model.CustomMethodProfile
import io.github.unsalable.goodbyedpi.model.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val file: File get() = File(tmp.root, "settings.json")

    @Test
    fun missingFileGivesMigratedDefaults() {
        val repo = SettingsRepository(file)
        assertEquals(AppSettings().migrate(), repo.current)
        assertEquals(1, repo.current.customProfiles.size)
        assertEquals(1, repo.current.customDns.size)
        assertFalse(file.exists()) // okumak dosya olusturmaz
    }

    @Test
    fun updatePersistsAtomically() = runBlocking {
        val repo = SettingsRepository(file)
        val result = repo.update { it.copy(themeMode = ThemeMode.DARK, isp = "vodafone") }
        assertEquals(ThemeMode.DARK, result.themeMode)
        assertEquals(result, repo.settings.value)
        assertTrue(file.exists())
        assertFalse(File(file.path + ".tmp").exists())

        val reloaded = SettingsRepository(file)
        assertEquals(result, reloaded.current)
    }

    @Test
    fun updateMigratesResult() = runBlocking {
        val repo = SettingsRepository(file)
        // Son ozel profili silmek: en az bir profil kalir.
        val s = repo.update { it.copy(customProfiles = emptyList(), method = "custom") }
        assertEquals(listOf(CustomMethodProfile()), s.customProfiles)
    }

    @Test
    fun equalUpdateDoesNotWrite() = runBlocking {
        val repo = SettingsRepository(file)
        val before = repo.current
        val after = repo.update { it }
        assertSame(before, after)
        assertFalse(file.exists())
    }

    @Test
    fun corruptFileBackedUpAndDefaultsUsed() = runBlocking {
        file.writeText("{ \"isp\": \"turknet\", \"customProfiles\": [ { \"id\": ")
        val repo = SettingsRepository(file)
        assertEquals(AppSettings().migrate(), repo.current)
        val bak = File(file.path + ".bak")
        assertTrue(bak.exists())
        assertEquals("{ \"isp\": \"turknet\", \"customProfiles\": [ { \"id\": ", bak.readText())

        // Sonraki yazma bozuk dosyanin yerine gecerli olani koyar.
        repo.update { it.copy(isp = "kablonet") }
        assertEquals("kablonet", SettingsRepository(file).current.isp)
    }

    @Test
    fun emptyFileTreatedAsCorrupt() {
        file.writeText("   ")
        val repo = SettingsRepository(file)
        assertEquals(AppSettings().migrate(), repo.current)
        assertTrue(File(file.path + ".bak").exists())
    }

    @Test
    fun partialJsonFillsDefaults() {
        file.writeText("""{ "isp": "superonline", "unknown": 1 }""")
        val repo = SettingsRepository(file)
        assertEquals("superonline", repo.current.isp)
        assertEquals("yandex", repo.current.dns)
        assertTrue(repo.current.smartMode)
        assertEquals(1, repo.current.customProfiles.size)
        assertFalse(File(file.path + ".bak").exists())
    }

    // 1.0.0 dosyasi: settingsVersion ve smartMode yok, DNS alani her zaman yazili.
    private fun v100(isp: String, dns: String) =
        """{ "isp": "$isp", "method": "ttl4", "dns": "$dns", "autoFallback": true, "wantRunning": true }"""

    @Test
    fun legacyUntouchedGeneralCloudflareMovesToYandexOnce() = runBlocking {
        file.writeText(v100("general", "cloudflare"))
        val repo = SettingsRepository(file)
        assertEquals("yandex", repo.current.dns)
        assertTrue(repo.current.smartMode)
        assertEquals(AppSettings.CURRENT_VERSION, repo.current.settingsVersion)
        // Diger alanlar korunur.
        assertEquals("ttl4", repo.current.method)
        assertTrue(repo.current.wantRunning)

        // Kullanici Cloudflare'i bilerek yeniden secerse bir daha degismez (dosya guncel surumlu).
        repo.update { it.copy(dns = "cloudflare") }
        assertEquals("cloudflare", SettingsRepository(file).current.dns)
    }

    @Test
    fun legacyExplicitChoicesKept() {
        // Saglayici profili secilmis (Genel degil): Cloudflare orada bilerek secilmis olmali.
        for ((isp, dns) in listOf("turktelekom" to "cloudflare", "general" to "off", "general" to "yandex", "vodafone" to "yandex")) {
            file.writeText(v100(isp, dns))
            val s = SettingsRepository(file).current
            assertEquals("$isp/$dns", dns, s.dns)
            assertTrue(s.smartMode)
        }
        // Ozel DNS girisi de korunur.
        file.writeText(
            """{ "isp": "general", "dns": "custom:ab12cd34", "customDns": [ { "id": "custom:ab12cd34", "name": "Ev", "v4": "9.9.9.9", "v4Port": 9953 } ] }""",
        )
        assertEquals("custom:ab12cd34", SettingsRepository(file).current.dns)
    }

    @Test
    fun currentVersionFileNotUpgraded() {
        file.writeText("""{ "isp": "general", "dns": "cloudflare", "smartMode": false, "settingsVersion": 2 }""")
        val s = SettingsRepository(file).current
        assertEquals("cloudflare", s.dns)
        assertFalse(s.smartMode)
        // Acikca eski surum yazilmissa da yukseltilir.
        file.writeText("""{ "isp": "general", "dns": "cloudflare", "settingsVersion": 1 }""")
        assertEquals("yandex", SettingsRepository(file).current.dns)
        assertFalse(File(file.path + ".bak").exists())
    }

    @Test
    fun leftoverTmpRecoveredWhenMainMissing() = runBlocking {
        val repo = SettingsRepository(file)
        repo.update { it.copy(isp = "turknet") }
        file.renameTo(File(file.path + ".tmp"))
        assertEquals("turknet", SettingsRepository(file).current.isp)
        assertFalse(File(file.path + ".tmp").exists())
    }

    @Test
    fun concurrentUpdatesAreSerialized() = runBlocking {
        val repo = SettingsRepository(file)
        val jobs = (1..100).map {
            async(Dispatchers.Default) {
                repo.update { s -> s.copy(lastUpdateCheck = s.lastUpdateCheck + 1) }
            }
        }
        jobs.awaitAll()
        assertEquals(100L, repo.current.lastUpdateCheck)
        assertEquals(100L, SettingsRepository(file).current.lastUpdateCheck)
        assertFalse(File(file.path + ".tmp").exists())
    }

    @Test
    fun updateNowChangesMemoryAtOnceAndPersistsLatest() = runBlocking {
        val repo = SettingsRepository(file)
        val s = repo.updateNow { it.copy(wantRunning = true) }
        // Askiya almadan: donuste bellek zaten yeni degerde.
        assertTrue(s.wantRunning)
        assertTrue(repo.current.wantRunning)
        repo.updateNow { it.copy(wantRunning = false) }
        repo.updateNow { it.copy(wantRunning = true, isp = "turknet") }
        repo.awaitPersisted()
        val reloaded = SettingsRepository(file).current
        assertTrue(reloaded.wantRunning)
        assertEquals("turknet", reloaded.isp)
        assertFalse(File(file.path + ".tmp").exists())
    }

    @Test
    fun updateNowEqualValueDoesNotWrite() = runBlocking {
        val repo = SettingsRepository(file)
        val before = repo.current
        assertSame(before, repo.updateNow { it })
        repo.awaitPersisted()
        assertFalse(file.exists())
    }

    @Test
    fun mixedUpdateAndUpdateNowLoseNothing() = runBlocking {
        val repo = SettingsRepository(file)
        val jobs = (1..200).map { i ->
            async(Dispatchers.Default) {
                if (i % 2 == 0) {
                    repo.update { s -> s.copy(lastUpdateCheck = s.lastUpdateCheck + 1) }
                } else {
                    repo.updateNow { s -> s.copy(lastUpdateCheck = s.lastUpdateCheck + 1) }
                }
            }
        }
        jobs.awaitAll()
        repo.awaitPersisted()
        assertEquals(200L, repo.current.lastUpdateCheck)
        assertEquals(200L, SettingsRepository(file).current.lastUpdateCheck)
    }

    @Test
    fun writeFailureKeepsMemoryState() = runBlocking {
        // Hedef bir klasorse yazma basarisiz olur; cagirana hata firlatilmamali.
        val dir = File(tmp.root, "blocked").apply { mkdirs() }
        val target = File(dir, "settings.json").apply { mkdirs() }
        File(target, "dolu").writeText("x") // bos olmayan klasor silinemez
        val repo = SettingsRepository(target)
        val s = repo.update { it.copy(isp = "vodafone") }
        assertEquals("vodafone", s.isp)
        assertEquals("vodafone", repo.current.isp)
        assertTrue(target.isDirectory)
        assertFalse(File(target.path + ".tmp").exists())
    }
}
