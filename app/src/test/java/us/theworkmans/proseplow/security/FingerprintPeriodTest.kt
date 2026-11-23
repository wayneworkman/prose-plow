package us.theworkmans.proseplow.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision of [FingerprintPeriod] on its own: the recorded start, the boot count read now,
 * the time since boot now and the chosen period give "the fingerprint may open the app" or not.
 * Every comment says which wrong decision the test would catch.
 */
class FingerprintPeriodTest {

    private val hour = LockConfig.DEFAULT_FINGERPRINT_PERIOD_SECONDS
    private val hourMillis = hour * 1000L
    private val start = FingerprintPeriodStart(elapsedMillis = 7_200_000L, bootCount = 12)

    private fun allows(nowElapsed: Long, bootCount: Int? = 12, periodSeconds: Int = hour, start: FingerprintPeriodStart? = this.start) =
        FingerprintPeriod.allows(start, bootCount, nowElapsed, periodSeconds)

    /** Catches a decision that refuses inside the period (from its first millisecond to its last). */
    @Test
    fun insideThePeriodTheFingerprintWorks() {
        assertTrue("at the moment it starts", allows(start.elapsedMillis))
        assertTrue(allows(start.elapsedMillis + 1))
        assertTrue(allows(start.elapsedMillis + hourMillis / 2))
        assertTrue("its last millisecond", allows(start.elapsedMillis + hourMillis - 1))
    }

    /** Catches an end that comes a millisecond late (`<=` for `<`) or not at all. */
    @Test
    fun atItsEndAndAfterItThePeriodHasRunOut() {
        assertFalse("exactly at the end", allows(start.elapsedMillis + hourMillis))
        assertFalse(allows(start.elapsedMillis + hourMillis + 1))
        assertFalse("days later", allows(start.elapsedMillis + 30 * 24 * hourMillis))
    }

    /** Catches a period measured in the wrong unit, or one that ignores the chosen length. */
    @Test
    fun everyChoiceEndsExactlyAtItsOwnLength() {
        assertEquals(listOf(900, 3_600, 28_800, 86_400), LockConfig.FINGERPRINT_PERIOD_CHOICES_SECONDS)
        assertEquals("one hour by default", 3_600, LockConfig.DEFAULT_FINGERPRINT_PERIOD_SECONDS)
        for (seconds in LockConfig.FINGERPRINT_PERIOD_CHOICES_SECONDS) {
            assertTrue("$seconds s, last millisecond", allows(start.elapsedMillis + seconds * 1000L - 1, periodSeconds = seconds))
            assertFalse("$seconds s, at the end", allows(start.elapsedMillis + seconds * 1000L, periodSeconds = seconds))
        }
    }

    /**
     * Catches the [Backoff] rule ("what is left on the new clock") or a rule that only looks at
     * the time: after a restart the fingerprint is refused whatever the time since boot says,
     * just after the start as much as long before it.
     */
    @Test
    fun aRestartEndsThePeriodWhateverTheTimeSinceBootSays() {
        // The new boot has been up for longer than the old one had been: the time looks inside the period.
        assertFalse(allows(start.elapsedMillis + 1, bootCount = 13))
        assertFalse(allows(start.elapsedMillis + hourMillis - 1, bootCount = 13))
        // The new boot is young: the time since boot is below the recorded start.
        assertFalse(allows(5_000, bootCount = 13))
        assertFalse(allows(0, bootCount = 13))
        // A count that went down (a reset of the system's settings) is a different boot too.
        assertFalse(allows(start.elapsedMillis + 1, bootCount = 11))
    }

    /** Catches a decision that takes a time below the start (no single boot gives one) as inside the period. */
    @Test
    fun aTimeSinceBootBelowTheStartHasRunOutEvenOnTheSameBootCount() {
        assertFalse(allows(start.elapsedMillis - 1))
        assertFalse(allows(0))
    }

    /** Catches a boot count that cannot be read being taken as "no restart". */
    @Test
    fun aBootCountThatCannotBeReadHasRunOut() {
        assertFalse(allows(start.elapsedMillis + 1, bootCount = null))
        assertFalse("no phone gives a negative count", allows(start.elapsedMillis + 1, bootCount = -1))
    }

    /** Catches a missing start (an old configuration, or one written without a boot count) being taken as running. */
    @Test
    fun withoutARecordedStartThePeriodHasRunOut() {
        assertFalse(allows(start.elapsedMillis + 1, start = null))
        assertFalse(allows(0, start = null))
    }

    /** Catches values that only a damaged file can hold being taken as a running period. */
    @Test
    fun valuesNoPhoneWritesNeverOpen() {
        assertFalse(allows(10, start = FingerprintPeriodStart(-1, 12)))
        assertFalse(allows(10, bootCount = -1, start = FingerprintPeriodStart(5, -1)))
        assertFalse("no period at all", allows(start.elapsedMillis, periodSeconds = 0))
        assertFalse(allows(start.elapsedMillis, periodSeconds = -60))
    }
}
