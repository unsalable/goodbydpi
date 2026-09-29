package io.github.unsalable.goodbyedpi.service

import io.github.unsalable.goodbyedpi.service.RecoveryPolicy.Exit
import org.junit.Assert.assertFalse
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
}
