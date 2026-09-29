package io.github.unsalable.goodbyedpi.update

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionUtilTest {
    @Test
    fun parsesAllTagStyles() {
        val expected = intArrayOf(1, 2, 3, 0)
        for (tag in listOf("android-v1.2.3", "v1.2.3", "V1.2.3", "1.2.3", " 1.2.3 ", "android_v1.2.3", "android-1.2.3",
            "GoodbyeDPI Android 1.2.3", "releases/v1.2.3")) {
            assertArrayEquals(tag, expected, VersionUtil.parse(tag))
        }
    }

    @Test
    fun suffixesAreIgnored() {
        val expected = intArrayOf(1, 2, 3, 0)
        for (tag in listOf("android-v1.2.3-beta", "v1.2.3+build.7", "1.2.3_rc1", "v1.2.3 (hotfix)", "1.2.3-")) {
            assertArrayEquals(tag, expected, VersionUtil.parse(tag))
        }
    }

    @Test
    fun missingComponentsAreZero() {
        assertArrayEquals(intArrayOf(2, 0, 0, 0), VersionUtil.parse("v2"))
        assertArrayEquals(intArrayOf(1, 4, 0, 0), VersionUtil.parse("android-v1.4"))
        assertArrayEquals(intArrayOf(1, 2, 3, 4), VersionUtil.parse("1.2.3.4.5"))
    }

    @Test
    fun garbageIsRejected() {
        for (tag in listOf(null, "", "   ", "latest", "android", "vX.Y", "abc1.2.3", "v99999999999.0.0")) {
            assertNull(tag.toString(), VersionUtil.parse(tag))
        }
    }

    @Test
    fun comparisonIsNumericNotLexical() {
        assertTrue(VersionUtil.isNewer("1.10.0", "1.9.9"))
        assertTrue(VersionUtil.isNewer("android-v1.0.1", "1.0.0"))
        assertTrue(VersionUtil.isNewer("v2", "1.99.99"))
        assertTrue(VersionUtil.isNewer("1.0.0.1", "1.0.0"))
        assertFalse(VersionUtil.isNewer("1.0.0", "1.0.0"))
        assertFalse(VersionUtil.isNewer("v1.0", "1.0.0"))
        assertFalse(VersionUtil.isNewer("0.9.9", "1.0.0"))
        assertFalse(VersionUtil.isNewer("1.2.0-beta", "1.2.0"))
    }

    @Test
    fun unreadableNeverCountsAsNewer() {
        assertFalse(VersionUtil.isNewer("latest", "1.0.0"))
        assertFalse(VersionUtil.isNewer("9.9.9", "garbage"))
        assertFalse(VersionUtil.isNewer(null, "1.0.0"))
    }

    @Test
    fun normalizeAndSameVersion() {
        assertEquals("1.2.3", VersionUtil.normalize("android-v1.2.3-beta"))
        assertEquals("1.0.0", VersionUtil.normalize("v1"))
        assertEquals("1.0.0.7", VersionUtil.normalize("1.0.0.7"))
        assertNull(VersionUtil.normalize("latest"))
        assertTrue(VersionUtil.sameVersion("v1.0", "1.0.0"))
        assertFalse(VersionUtil.sameVersion("1.0.1", "1.0.0"))
        assertFalse(VersionUtil.sameVersion(null, "1.0.0"))
    }
}
