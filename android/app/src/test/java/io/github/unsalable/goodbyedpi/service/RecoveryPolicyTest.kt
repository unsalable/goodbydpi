package io.github.unsalable.goodbyedpi.service

import android.app.ApplicationExitInfo
import io.github.unsalable.goodbyedpi.service.RecoveryDedupe.Decision
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
    fun checkDelayIsShortAndCapped() {
        // E2E-V7-1/2: olu surec (ve asili "Bagli" bildirimi) en fazla ~1 dk + pay beklesin.
        assertEquals(30_000L, RecoveryPolicy.checkDelayMs(1))
        assertEquals(min, RecoveryPolicy.checkDelayMs(2))
        assertEquals(min, RecoveryPolicy.checkDelayMs(3))
        assertEquals(min, RecoveryPolicy.checkDelayMs(4))
        assertEquals(30_000L, RecoveryPolicy.checkDelayMs(0))
    }

    @Test
    fun checkAfterLastAllowedRecoveryIsShort() {
        // MAX_STREAK'inci kurtarmadan sonraki kontrol yalnizca olumu gorup vazgecer: 8 dk degil.
        assertEquals(30_000L, RecoveryPolicy.checkDelayMs(RecoveryPolicy.MAX_STREAK))
        assertEquals(30_000L, RecoveryPolicy.checkDelayMs(50))
        // Cokme dongusunde kontrol islerinin toplam bekleyisi ~6,5 dk (eskiden ~18 dk).
        val total = (1..RecoveryPolicy.MAX_STREAK).sumOf { RecoveryPolicy.checkDelayMs(it) + RecoveryPolicy.CHECK_DEADLINE_SLACK_MS }
        assertTrue(total <= 7 * min)
    }

    @Test
    fun watchContinuesOnlyShortlyAfterRecovery() {
        val s = RecoveryPolicy.Streak(1, 1_000_000L, 7)
        // Ilk kontrol motoru ayakta buldu: 78 sn sonraki bir cokme de yakalansin diye izleme surer.
        assertEquals(min, RecoveryPolicy.watchDelayMs(s, 1_030_000L, 7))
        assertEquals(min, RecoveryPolicy.watchDelayMs(s.copy(count = 4), 1_030_000L, 7))
        assertEquals(min, RecoveryPolicy.watchDelayMs(s.copy(count = RecoveryPolicy.MAX_STREAK), 1_030_000L, 7))
        assertNull(RecoveryPolicy.watchDelayMs(s, 1_000_000L + RecoveryPolicy.WATCH_MS, 7))
        assertNull(RecoveryPolicy.watchDelayMs(s, 1_030_000L, 8))
        assertNull(RecoveryPolicy.watchDelayMs(none, 1_030_000L, 7))
    }

    @Test
    fun reasonConstantsMatchSdk() {
        // Derleme zamani sabitleri (javac satir ici yazar): JVM testinde Android sinifi yuklenmez.
        assertEquals(ApplicationExitInfo.REASON_UNKNOWN, RecoveryPolicy.REASON_UNKNOWN)
        assertEquals(ApplicationExitInfo.REASON_EXIT_SELF, RecoveryPolicy.REASON_EXIT_SELF)
        assertEquals(ApplicationExitInfo.REASON_SIGNALED, RecoveryPolicy.REASON_SIGNALED)
        assertEquals(ApplicationExitInfo.REASON_LOW_MEMORY, RecoveryPolicy.REASON_LOW_MEMORY)
        assertEquals(ApplicationExitInfo.REASON_CRASH, RecoveryPolicy.REASON_CRASH)
        assertEquals(ApplicationExitInfo.REASON_CRASH_NATIVE, RecoveryPolicy.REASON_CRASH_NATIVE)
        assertEquals(ApplicationExitInfo.REASON_ANR, RecoveryPolicy.REASON_ANR)
        assertEquals(ApplicationExitInfo.REASON_INITIALIZATION_FAILURE, RecoveryPolicy.REASON_INITIALIZATION_FAILURE)
        assertEquals(ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, RecoveryPolicy.REASON_EXCESSIVE_RESOURCE_USAGE)
        assertEquals(ApplicationExitInfo.REASON_USER_REQUESTED, RecoveryPolicy.REASON_USER_REQUESTED)
        assertEquals(ApplicationExitInfo.REASON_OTHER, RecoveryPolicy.REASON_OTHER)
    }

    @Test
    fun onlyCrashLikeExitsCountTowardGiveUp() {
        // REC-STREAK-NONCRASH: LMK / OEM / kill -9 oldurmeleri vazgecmeye itmez.
        assertFalse(RecoveryPolicy.countsTowardGiveUp(RecoveryPolicy.REASON_LOW_MEMORY))
        assertFalse(RecoveryPolicy.countsTowardGiveUp(RecoveryPolicy.REASON_SIGNALED))
        assertFalse(RecoveryPolicy.countsTowardGiveUp(RecoveryPolicy.REASON_OTHER))
        assertFalse(RecoveryPolicy.countsTowardGiveUp(14)) // FREEZER
        // Yerel cokme dongusu (e2e kill -11 -> CRASH_NATIVE) sayilir.
        assertTrue(RecoveryPolicy.countsTowardGiveUp(RecoveryPolicy.REASON_CRASH_NATIVE))
        assertTrue(RecoveryPolicy.countsTowardGiveUp(RecoveryPolicy.REASON_CRASH))
        assertTrue(RecoveryPolicy.countsTowardGiveUp(RecoveryPolicy.REASON_ANR))
        assertTrue(RecoveryPolicy.countsTowardGiveUp(RecoveryPolicy.REASON_EXIT_SELF))
        // Bilinmiyor (API 30 alti / kayit yok): eski davranis, temkinli sayilir.
        assertTrue(RecoveryPolicy.countsTowardGiveUp(null))
        assertTrue(RecoveryPolicy.countsTowardGiveUp(RecoveryPolicy.REASON_UNKNOWN))
    }

    @Test
    fun signaledCountsOnlyForCrashSignals() {
        val sig = RecoveryPolicy.REASON_SIGNALED
        // REC-SIGNALED-CRASH: cokme raporu gelmeyen yerel cokme SIGNALED + sinyal olarak yazilir.
        assertTrue(RecoveryPolicy.countsTowardGiveUp(sig, 11)) // SIGSEGV
        assertTrue(RecoveryPolicy.countsTowardGiveUp(sig, 6)) // SIGABRT
        assertTrue(RecoveryPolicy.countsTowardGiveUp(sig, 7)) // SIGBUS
        assertTrue(RecoveryPolicy.countsTowardGiveUp(sig, 4)) // SIGILL
        assertTrue(RecoveryPolicy.countsTowardGiveUp(sig, 8)) // SIGFPE
        assertTrue(RecoveryPolicy.countsTowardGiveUp(sig, 31)) // SIGSYS
        // Disaridan oldurme: kill -9, SIGTERM; sinyal bilinmiyorsa da sayilmaz.
        assertFalse(RecoveryPolicy.countsTowardGiveUp(sig, 9))
        assertFalse(RecoveryPolicy.countsTowardGiveUp(sig, 15))
        assertFalse(RecoveryPolicy.countsTowardGiveUp(sig, 0))
        // Diger nedenlerde status (cikis kodu) karari degistirmez.
        assertFalse(RecoveryPolicy.countsTowardGiveUp(RecoveryPolicy.REASON_LOW_MEMORY, 11))
        assertTrue(RecoveryPolicy.countsTowardGiveUp(RecoveryPolicy.REASON_CRASH_NATIVE, 0))
        assertTrue(RecoveryPolicy.countsTowardGiveUp(null, 9))
    }

    @Test
    fun recoveryHeldAfterWatchWindow() {
        assertFalse(RecoveryPolicy.recoveryHeld(0L))
        assertFalse(RecoveryPolicy.recoveryHeld(RecoveryPolicy.WATCH_MS - 1))
        assertTrue(RecoveryPolicy.recoveryHeld(RecoveryPolicy.WATCH_MS))
    }

    @Test
    fun deadlineSlackStaysShort() {
        // E2E-V6-1: is pratikte son tarihte calisiyor; hicbir kontrol 1,5 dk'dan gec kurulmaz.
        assertTrue(RecoveryPolicy.CHECK_DEADLINE_SLACK_MS <= 30_000L)
        for (n in 0..60) {
            assertTrue(RecoveryPolicy.checkDelayMs(n) + RecoveryPolicy.CHECK_DEADLINE_SLACK_MS <= min + 30_000L)
        }
    }

    @Test
    fun dedupeCollapsesBurstButNotAfterRefusal() {
        val d = RecoveryDedupe(5_000L)
        var admitted = 0
        // REC-DEDUPE-GIVEUP: arka plan yolu vazgecti -> damga yok, hemen ardindan arayuz baglanir.
        assertEquals(Decision.REFUSED, d.tryBegin(1_000L) { false })
        assertEquals(Decision.BEGIN, d.tryBegin(1_500L) { admitted++; true })
        // Ayni surecte bekci/karo 5 sn icinde: tek baslatma, admit (sayac) hic cagrilmaz.
        assertEquals(Decision.DUPLICATE, d.tryBegin(3_000L) { admitted++; true })
        assertEquals(Decision.DUPLICATE, d.tryBegin(6_499L) { admitted++; true })
        assertEquals(1, admitted)
        assertEquals(Decision.BEGIN, d.tryBegin(6_500L) { true })
    }
}
