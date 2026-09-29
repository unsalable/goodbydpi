package io.github.unsalable.goodbyedpi.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RestartPolicyTest {
    @Test
    fun backoffThenGiveUp() {
        val p = RestartPolicy()
        var t = 0L
        val delays = (1..5).map { p.next(t).also { t += 1_000 } }
        assertEquals(listOf(1_000L, 3_000L, 10_000L, 10_000L, 10_000L), delays)
        // 5 dakika icinde 6. deneme yok.
        assertNull(p.next(t))
        assertNull(p.next(4 * 60_000L))
    }

    @Test
    fun slidingWindowForgetsOldAttempts() {
        val p = RestartPolicy()
        repeat(5) { p.next(it * 1_000L) }
        assertNull(p.next(10_000L))
        // Ilk deneme (t=0) pencereden cikinca bir hak acilir; sayac 4 oldugu icin 10 sn.
        assertEquals(10_000L, p.next(5 * 60_000L))
        assertNull(p.next(5 * 60_000L + 500))
        // Hepsi pencereden cikinca en bastan.
        assertEquals(1_000L, p.next(20 * 60_000L))
    }

    @Test
    fun occasionalCrashesNeverExhaust() {
        val p = RestartPolicy()
        // Her 2 dakikada bir dusme: pencerede en fazla 3 deneme kalir.
        for (i in 0 until 50) {
            val d = p.next(i * 120_000L)
            assertEquals(true, d != null)
        }
        assertEquals(3, p.attemptsIn(49 * 120_000L))
    }

    @Test
    fun resetClearsBudget() {
        val p = RestartPolicy()
        repeat(5) { p.next(0) }
        assertNull(p.next(1))
        p.reset()
        assertEquals(1_000L, p.next(2))
        assertEquals(1, p.attemptsIn(2))
    }

    @Test
    fun giveUpMessageSeparatesSentences() {
        val suffix = RestartPolicy.GIVE_UP_SUFFIX
        // E2E-V6-2: neden noktasizsa iki cumle yapisik kalmasin.
        assertEquals("VPN izni yok. $suffix", RestartPolicy.giveUpMessage("VPN izni yok"))
        assertEquals("Tünel başlatılamadı. $suffix", RestartPolicy.giveUpMessage("Tünel başlatılamadı."))
        assertEquals("Neden? $suffix", RestartPolicy.giveUpMessage("Neden? "))
        assertEquals(suffix, RestartPolicy.giveUpMessage(""))
        assertEquals(suffix, RestartPolicy.giveUpMessage("   "))
    }
}
