package io.github.unsalable.goodbyedpi.service

import io.github.unsalable.goodbyedpi.service.RecoveryPolicy.Exit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Kullanici bilerek durdurdu mu" karari (runtime RT-1). */
class RecoveryPolicyTest {
    private val armed = 1_000_000L
    private val user = RecoveryPolicy.REASON_USER_REQUESTED

    // ApplicationExitInfo sabitleri (android.jar JVM testinde yok): SIGNALED=2, CRASH_NATIVE=5, LOW_MEMORY=3.
    private val signaled = 2
    private val nativeCrash = 5
    private val lowMemory = 3

    private fun stopped(exit: Exit?, handled: Long = 0L, lastUpdate: Long = 0L) =
        RecoveryPolicy.userStoppedAfterArm(exit, armedAtMs = armed, handledExitMs = handled, lastUpdateMs = lastUpdate)

    @Test
    fun forceStopAfterConnectIsUserStop() {
        assertTrue(stopped(Exit(user, armed + 60_000, "stop due to from pid 1")))
    }

    @Test
    fun crashesAndKillsAreRecovered() {
        // kill -9, kill -11 (yerel cokme), LMK: geri getirilmeli.
        for (r in listOf(signaled, nativeCrash, lowMemory)) assertFalse(stopped(Exit(r, armed + 60_000, null)))
        assertFalse(stopped(null))
    }

    @Test
    fun stopBeforeLatestConnectIsIgnored() {
        // Kullanici durdurdu, sonra yeniden baglandi (motor yeniden kuruldu): eski durdurma sayilmaz.
        assertFalse(stopped(Exit(user, armed - 1, null)))
        assertFalse(stopped(Exit(user, armed, null)))
    }

    @Test
    fun sameExitHandledOnlyOnce() {
        val e = Exit(user, armed + 5_000, null)
        assertTrue(stopped(e))
        assertFalse(stopped(e, handled = e.timestampMs))
    }

    @Test
    fun updateKillNearLastUpdateTimeIsNotUserStop() {
        // API 30-31: guncelleme oldurmesi USER_REQUESTED olarak kaydedilebiliyor.
        val t = armed + 600_000
        assertFalse(stopped(Exit(user, t, null), lastUpdate = t + 2_000))
        assertFalse(stopped(Exit(user, t, null), lastUpdate = t - 30_000))
        assertTrue(stopped(Exit(user, t, null), lastUpdate = t - 10 * 60_000))
    }

    @Test
    fun jobCancellationMeansForceStopOnlyWhenTrustworthy() {
        assertTrue(RecoveryPolicy.jobCancelledByForceStop(jobArmed = true, jobPending = false, sameBoot = true, updatedSinceArm = false))
        // Is hala duruyor: LMK / kill.
        assertFalse(RecoveryPolicy.jobCancelledByForceStop(jobArmed = true, jobPending = true, sameBoot = true, updatedSinceArm = false))
        // Hic kurulmamisti (eski surumden guncelleme): isaret yok.
        assertFalse(RecoveryPolicy.jobCancelledByForceStop(jobArmed = false, jobPending = false, sameBoot = true, updatedSinceArm = false))
        // Yeniden baslatma ya da guncelleme isi dusurmus olabilir: guvenilmez.
        assertFalse(RecoveryPolicy.jobCancelledByForceStop(jobArmed = true, jobPending = false, sameBoot = false, updatedSinceArm = false))
        assertFalse(RecoveryPolicy.jobCancelledByForceStop(jobArmed = true, jobPending = false, sameBoot = true, updatedSinceArm = true))
    }

    @Test
    fun disarmedOrOtherBootBlocksBackgroundRecovery() {
        // REC-1: kalici hata (fail -> disarm) sonrasi ayni acilista da arka plan kurtarmasi yok.
        assertTrue(RecoveryPolicy.canRecoverInBackground(bgArmed = true, sameBoot = true))
        assertFalse(RecoveryPolicy.canRecoverInBackground(bgArmed = false, sameBoot = true))
        assertFalse(RecoveryPolicy.canRecoverInBackground(bgArmed = true, sameBoot = false))
    }

    private val none = RecoveryPolicy.Streak(0, 0L, -1)
    private val min = 60_000L

    @Test
    fun crashLoopGivesUpAfterMaxStreak() {
        // E2E-F1: kurtarma -> cokme -> kontrol isi -> kurtarma ... arasi ustel beklemeyle.
        var s = none
        var now = 10_000_000L
        for (i in 1..RecoveryPolicy.MAX_STREAK) {
            s = RecoveryPolicy.nextStreak(s, now, 7)
            assertEquals(i, s.count)
            assertFalse(RecoveryPolicy.shouldGiveUp(s))
            now += RecoveryPolicy.checkDelayMs(s.count) + RecoveryPolicy.CHECK_DEADLINE_SLACK_MS
        }
        assertTrue(RecoveryPolicy.shouldGiveUp(RecoveryPolicy.nextStreak(s, now, 7)))
    }

    @Test
    fun heldRecoveryOrNewBootRestartsStreak() {
        val s = RecoveryPolicy.Streak(4, 1_000_000L, 7)
        // 15 dk tuttu: onceki kurtarmalar sayilmaz.
        assertEquals(1, RecoveryPolicy.nextStreak(s, 1_000_000L + RecoveryPolicy.STREAK_GAP_MS, 7).count)
        assertEquals(5, RecoveryPolicy.nextStreak(s, 1_000_000L + RecoveryPolicy.STREAK_GAP_MS - 1, 7).count)
        // Baska acilis ya da saat geri gitti (yeni acilis, BOOT_COUNT okunamadi).
        assertEquals(1, RecoveryPolicy.nextStreak(s, 1_100_000L, 8).count)
        assertEquals(1, RecoveryPolicy.nextStreak(s, 500_000L, -1).count)
    }

    @Test
    fun checkDelayIsExponentialAndCapped() {
        assertEquals(30_000L, RecoveryPolicy.checkDelayMs(1))
        assertEquals(60_000L, RecoveryPolicy.checkDelayMs(2))
        assertEquals(2 * min, RecoveryPolicy.checkDelayMs(3))
        assertEquals(4 * min, RecoveryPolicy.checkDelayMs(4))
        assertEquals(8 * min, RecoveryPolicy.checkDelayMs(5))
        assertEquals(8 * min, RecoveryPolicy.checkDelayMs(50))
        assertEquals(30_000L, RecoveryPolicy.checkDelayMs(0))
    }

    @Test
    fun watchContinuesOnlyShortlyAfterRecovery() {
        val s = RecoveryPolicy.Streak(1, 1_000_000L, 7)
        // Ilk kontrol motoru ayakta buldu: 78 sn sonraki bir cokme de yakalansin diye izleme surer.
        assertEquals(min, RecoveryPolicy.watchDelayMs(s, 1_030_000L, 7))
        assertEquals(4 * min, RecoveryPolicy.watchDelayMs(s.copy(count = 4), 1_030_000L, 7))
        assertNull(RecoveryPolicy.watchDelayMs(s, 1_000_000L + RecoveryPolicy.WATCH_MS, 7))
        assertNull(RecoveryPolicy.watchDelayMs(s, 1_030_000L, 8))
        assertNull(RecoveryPolicy.watchDelayMs(none, 1_030_000L, 7))
    }
}
