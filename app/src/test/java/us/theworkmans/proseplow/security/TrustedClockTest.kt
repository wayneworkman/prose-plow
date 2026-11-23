package us.theworkmans.proseplow.security

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The trusted time of a process: what messages are dated by. Catches a clock that follows the
 * wall clock backwards within the process, a stored mark that is ignored, and one that takes
 * the time back.
 */
class TrustedClockTest {

    private val clock = FakeClock(wall = 1_000_000, elapsed = 50_000)

    @Test
    fun withinTheProcessTheTimeNeverGoesBack() {
        val trusted = TrustedClock(clock)
        assertEquals(1_000_000L, trusted.nowMillis())
        clock.wall = 10
        clock.elapsed += 2_000
        assertEquals("the time since boot is credited", 1_002_000L, trusted.nowMillis())
        clock.wall = 5_000_000
        assertEquals("a clock set forward is believed", 5_000_000L, trusted.nowMillis())
    }

    @Test
    fun aStoredMarkThatIsLaterIsTakenAndOneThatIsEarlierIsNot() {
        val trusted = TrustedClock(clock)
        trusted.observe(TimeMark(wallMillis = 3_000_000, elapsedMillis = 40_000, bootCount = 1))
        assertEquals("the stored time plus the time since boot since it", 3_010_000L, trusted.nowMillis())
        trusted.observe(TimeMark(wallMillis = 2, elapsedMillis = 0, bootCount = 1))
        assertEquals(3_010_000L, trusted.nowMillis())
        assertEquals(TimeMark(3_010_000, 50_000, 1), trusted.mark())
    }

    @Test
    fun aBootCountThatCannotBeReadGivesNoCredit() {
        val trusted = TrustedClock(clock)
        trusted.observe(TimeMark(wallMillis = 3_000_000, elapsedMillis = 40_000, bootCount = 1))
        clock.bootCountFailure = IllegalStateException("unreadable")
        clock.elapsed += 1_000_000
        assertEquals(3_010_000L, trusted.nowMillis())
    }
}
