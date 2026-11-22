package us.theworkmans.proseplow.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the app locks in the background: after the configured timeout, and nothing else. */
class BackgroundLockPolicyTest {
    private val policy = BackgroundLockPolicy()
    private var now = 1_000_000L

    @Test
    fun theConfiguredTimeoutApplies() {
        assertEquals("immediately", 0L, policy.onBackground(now, 0))
        for (timeout in listOf(60_000L, 5 * 60_000L, 15 * 60_000L)) assertEquals(timeout, policy.onBackground(now, timeout))

        policy.onBackground(now, 60_000)
        now += 59_999
        assertFalse(policy.onForeground(now))
        policy.onBackground(now, 60_000)
        now += 60_000
        assertTrue("due, even if the timer could not run", policy.onForeground(now))
        assertFalse("nothing pending afterwards", policy.onForeground(now + 1))
    }

    @Test
    fun withImmediatelyEveryTripToTheBackgroundIsDue() {
        policy.onBackground(now, 0)
        assertTrue(policy.onForeground(now))
    }

    @Test
    fun nothingExtendsTheTimeout() {
        // However short the trip and whatever was in front meanwhile, the configured time is all there is.
        policy.onBackground(now, 0)
        assertEquals(0L, policy.remainingMillis(now))
        assertEquals(60_000L, policy.onBackground(now, 60_000))
        assertEquals(60_000L, policy.remainingMillis(now))
    }

    @Test
    fun whatIsLeftCountsDownFromTheMomentTheActivityLeft() {
        assertNull("in the foreground nothing is counting", policy.remainingMillis(now))
        policy.onBackground(now, 60_000)
        assertEquals(60_000L, policy.remainingMillis(now))
        assertEquals(20_000L, policy.remainingMillis(now + 40_000))
        assertEquals(0L, policy.remainingMillis(now + 60_000))
        assertEquals("never negative", 0L, policy.remainingMillis(now + 3_600_000))
        assertEquals("a clock that went backwards cannot lengthen it", 60_000L, policy.remainingMillis(now - 5_000))
        policy.onForeground(now + 1_000)
        assertNull(policy.remainingMillis(now + 2_000))
    }

    @Test
    fun lockingClearsWhatWasPending() {
        policy.onBackground(now, 60_000)
        policy.reset()
        assertNull(policy.remainingMillis(now))
        assertFalse("already locked: nothing is due on coming back", policy.onForeground(now + 3_600_000))
    }

    @Test
    fun aNewPolicyKnowsNothingOfAnEarlierProcess() {
        // Nothing is stored: a new process (a cold start, always locked) starts clean.
        assertNull(BackgroundLockPolicy().remainingMillis(now))
        assertFalse(BackgroundLockPolicy().onForeground(now))
    }
}
