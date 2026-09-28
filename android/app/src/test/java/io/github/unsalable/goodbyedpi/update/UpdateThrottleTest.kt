package io.github.unsalable.goodbyedpi.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateThrottleTest {
    private val h = 60L * 60 * 1000
    private val now = 1_800_000_000_000L

    @Test
    fun sixHourThrottle() {
        assertTrue("hic denetlenmedi", UpdateManager.isDue(0L, now))
        assertFalse(UpdateManager.isDue(now - 1 * h, now))
        assertFalse(UpdateManager.isDue(now - 6 * h + 1, now))
        assertTrue(UpdateManager.isDue(now - 6 * h, now))
        assertTrue(UpdateManager.isDue(now - 48 * h, now))
        // Saat geri alindiysa beklemeyelim.
        assertTrue(UpdateManager.isDue(now + 1 * h, now))
    }
}
