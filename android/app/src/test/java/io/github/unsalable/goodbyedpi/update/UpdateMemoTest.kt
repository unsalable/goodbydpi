package io.github.unsalable.goodbyedpi.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateMemoTest {
    private val h = 60L * 60 * 1000
    private val now = 1_800_000_000_000L

    @Test
    fun laterIsASnoozeNotAPermanentSkip() {
        // UPD-4: "Daha sonra" bir gun gecerli, sonra otomatik akis yeniden dener.
        val m = UpdateMemo(snoozedVersion = "1.0.1", snoozedAt = now)
        assertTrue(m.isSnoozed("1.0.1", now + 1 * h))
        assertTrue(m.isSnoozed("v1.0.1", now + 23 * h))
        assertFalse(m.isSnoozed("1.0.1", now + 24 * h))
        // Daha yeni bir surum ertelemeden etkilenmez.
        assertFalse(m.isSnoozed("1.0.2", now + 1 * h))
        // Saat geri alinmis: erteleme bitmis sayilir (sonsuza kadar susmasin).
        assertFalse(m.isSnoozed("1.0.1", now - 1 * h))
        // Eski ayar dosyasindan kalan zamansiz kayit hic ertelemez.
        assertFalse(UpdateMemo(snoozedVersion = "1.0.1").isSnoozed("1.0.1", now))
    }

    @Test
    fun badVersionBlocksOnlyThatVersion() {
        val m = UpdateMemo(badVersion = "1.0.1")
        assertTrue(m.blocksAuto("1.0.1", now))
        assertFalse(m.blocksAuto("1.0.2", now))
        assertFalse(UpdateMemo().blocksAuto("1.0.1", now))
    }

    @Test
    fun rememberedReleaseSurvivesUntilInstalled() {
        // UPD-3: surec olse de hatirlanan surum 6 saat beklemeden yeniden denenir.
        val m = UpdateMemo(available = "1.0.1")
        assertEquals("1.0.1", m.pendingFor("1.0.0", now))
        assertNull(m.pendingFor("1.0.1", now)) // kuruldu
        assertNull(m.pendingFor("1.2.0", now)) // elle daha yenisi kuruldu
        assertNull(m.copy(snoozedVersion = "1.0.1", snoozedAt = now).pendingFor("1.0.0", now + h))
        assertNull(m.copy(badVersion = "1.0.1").pendingFor("1.0.0", now))
        assertNull(UpdateMemo().pendingFor("1.0.0", now))
    }
}
