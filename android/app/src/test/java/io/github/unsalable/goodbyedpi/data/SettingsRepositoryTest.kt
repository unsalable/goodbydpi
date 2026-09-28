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
        assertEquals("cloudflare", repo.current.dns)
        assertEquals(1, repo.current.customProfiles.size)
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
