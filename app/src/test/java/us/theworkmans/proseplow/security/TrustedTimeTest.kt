package us.theworkmans.proseplow.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedTimeTest {

    private val start = TimeMark(wallMillis = 1_000_000, elapsedMillis = 50_000, bootCount = 7)

    @Test
    fun `without a mark the wall clock is the trusted time`() {
        assertEquals(123L, TrustedTime.now(null, wallNow = 123, elapsedNow = 5, bootNow = 1))
    }

    @Test
    fun `a wall clock set back does not take the time back, and the time since boot still counts`() {
        // Ten seconds later on the clock since boot, but the date was set back by a day.
        val now = TrustedTime.now(start, wallNow = 1_000_000 - 86_400_000, elapsedNow = 60_000, bootNow = 7)
        assertEquals("the last trusted time plus the time since boot that passed", 1_010_000L, now)
    }

    @Test
    fun `a wall clock set forward is believed`() {
        assertEquals(9_000_000L, TrustedTime.now(start, wallNow = 9_000_000, elapsedNow = 60_000, bootNow = 7))
    }

    @Test
    fun `the wall clock wins once it is ahead of the carried time`() {
        assertEquals(1_020_000L, TrustedTime.now(start, wallNow = 1_020_000, elapsedNow = 60_000, bootNow = 7))
    }

    @Test
    fun `after a reboot no time since boot is credited, but the last trusted time stays`() {
        // A new boot whose clock since boot happens to be far ahead of the recorded one.
        val now = TrustedTime.now(start, wallNow = 10, elapsedNow = 10_000_000, bootNow = 8)
        assertEquals(1_000_000L, now)
    }

    @Test
    fun `an unknown boot count, now or recorded, gives no credit for elapsed time`() {
        assertEquals(1_000_000L, TrustedTime.now(start, wallNow = 10, elapsedNow = 10_000_000, bootNow = null))
        assertEquals(1_000_000L, TrustedTime.now(start.copy(bootCount = null), wallNow = 10, elapsedNow = 10_000_000, bootNow = 7))
    }

    @Test
    fun `a time since boot below the recorded one is a reboot`() {
        assertEquals(1_000_000L, TrustedTime.now(start, wallNow = 10, elapsedNow = 1_000, bootNow = 7))
    }

    @Test
    fun `a mark records the trusted time with the clocks it was read at`() {
        val mark = TrustedTime.mark(start, wallNow = 10, elapsedNow = 60_000, bootNow = 7)
        assertEquals(TimeMark(1_010_000, 60_000, 7), mark)
        assertNull(TrustedTime.mark(null, 10, 60_000, bootNow = -1).bootCount)
    }

    @Test
    fun `marks are validated`() {
        assertTrue(start.isValid())
        assertTrue(start.copy(bootCount = null).isValid())
        assertFalse(start.copy(wallMillis = -1).isValid())
        assertFalse(start.copy(elapsedMillis = -1).isValid())
        assertFalse(start.copy(bootCount = -1).isValid())
        assertFalse(start.copy(wallMillis = TrustedTime.MAX_WALL_MILLIS + 1).isValid())
    }

    @Test
    fun `an invalid mark is not trusted`() {
        assertEquals(10L, TrustedTime.now(start.copy(wallMillis = -5), wallNow = 10, elapsedNow = 60_000, bootNow = 7))
    }
}
