package us.theworkmans.proseplow.security

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The temporary permission to take screenshots, on virtual time: when it starts, every way it
 * ends, and the rule that turns it into a secure or an open window.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreenshotAllowanceTest {

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val clock = FakeClock()
    private val watch = FakeScreenOffWatch()
    private val allowance = ScreenshotAllowance(clock, watch, dispatcher)
    private val duration = ScreenshotAllowance.DURATION_MILLIS

    /** An Android version on which an allowance can be honoured. */
    private val android13 = WindowSecurity.FIRST_SDK_HIDING_THE_PREVIEW_SEPARATELY

    /** Real time passes: on the clock and for the timer alike. */
    private fun TestScope.pass(millis: Long) {
        clock.advance(millis)
        advanceTimeBy(millis)
        runCurrent()
    }

    /** What the activity does whenever it applies the policy, here with the app unlocked. */
    private fun windowIsSecure() = WindowSecurity.mustBeSecure(unlocked = true, allowance.current(), clock.elapsed, android13)

    @Test
    fun nothingIsAllowedUntilTheUserAllowsIt() = runTest(dispatcher) {
        assertNull(allowance.allowed.value)
        assertNull(allowance.current())
        assertTrue(windowIsSecure())
        assertFalse("and nothing is registered with the system", watch.watching)
        pass(3_600_000)
        assertTrue(windowIsSecure())
    }

    @Test
    fun anAllowanceLastsTenMinutes() = runTest(dispatcher) {
        assertEquals(10, ScreenshotAllowance.DURATION_MINUTES)
        assertEquals(600_000L, ScreenshotAllowance.DURATION_MILLIS)

        assertTrue(allowance.grant())
        val expected = ScreenshotsAllowed(untilElapsedMillis = clock.elapsed + 600_000, untilWallMillis = clock.wall + 600_000)
        assertEquals(expected, allowance.allowed.value)
        assertEquals(expected, allowance.current())
        assertFalse(windowIsSecure())
        assertTrue("the screen is watched for as long as it runs", watch.watching)
    }

    @Test
    fun itEndsAtExactlyItsDeadline() = runTest(dispatcher) {
        allowance.grant()
        pass(duration - 1)
        assertTrue("a millisecond before", allowance.allowed.value != null)
        assertFalse(windowIsSecure())

        pass(1)
        assertNull("by itself, without anybody asking", allowance.allowed.value)
        assertTrue(windowIsSecure())
        assertFalse("and the screen is no longer watched", watch.watching)
        assertEquals(1, watch.stops)

        pass(duration) // Nothing is left running that could do anything later.
        assertNull(allowance.allowed.value)
        assertEquals(1, watch.stops)
    }

    @Test
    fun aTimerThatDidNotFireDoesNotKeepItAlive() = runTest(dispatcher) {
        allowance.grant()
        // The phone slept through the deadline: the clock moved on, the timer has not run.
        clock.advance(duration)
        assertTrue("nobody has looked yet", allowance.allowed.value != null)
        assertTrue("the policy already answers by the deadline", WindowSecurity.mustBeSecure(unlocked = true, allowance.allowed.value, clock.elapsed, android13))

        assertNull("and the first look ends it", allowance.current())
        assertNull(allowance.allowed.value)
        assertFalse(watch.watching)
        runCurrent()
        assertNull(allowance.allowed.value)
    }

    @Test
    fun theScreenTurningOffEndsIt() = runTest(dispatcher) {
        allowance.grant()
        pass(90_000)
        watch.screenTurnsOff()
        assertNull("at once", allowance.allowed.value)
        assertTrue(windowIsSecure())
        assertFalse(watch.watching)

        // The screen turning off later has nothing left to end, and the old timer nothing to do.
        watch.screenTurnsOff()
        pass(duration)
        assertNull(allowance.allowed.value)
        assertEquals(1, watch.stops)
    }

    @Test
    fun theUserCanEndItAtAnyTime() = runTest(dispatcher) {
        allowance.grant()
        pass(1)
        allowance.end()
        assertNull(allowance.allowed.value)
        assertTrue(windowIsSecure())
        assertFalse(watch.watching)

        allowance.end() // Ending what is not running does nothing.
        assertEquals(1, watch.stops)
    }

    @Test
    fun allowingAgainStartsTheTenMinutesAfresh() = runTest(dispatcher) {
        allowance.grant()
        val first = allowance.allowed.value!!
        pass(240_000)
        allowance.grant()
        val second = allowance.allowed.value!!
        assertEquals(first.untilElapsedMillis + 240_000, second.untilElapsedMillis)
        assertEquals(clock.elapsed + duration, second.untilElapsedMillis)
        assertEquals("the screen was watched all along, not registered twice", 1, watch.starts)
        assertEquals(0, watch.stops)

        pass(duration - 240_000) // The first deadline goes by.
        assertEquals(second, allowance.allowed.value)
        assertFalse(windowIsSecure())
        pass(240_000 - 1)
        assertEquals(second, allowance.allowed.value)
        pass(1)
        assertNull(allowance.allowed.value)
        assertTrue(windowIsSecure())
    }

    @Test
    fun anEndedAllowanceCanBeGivenAgain() = runTest(dispatcher) {
        allowance.grant()
        watch.screenTurnsOff()
        pass(1_000)
        assertTrue(allowance.grant())
        assertEquals(clock.elapsed + duration, allowance.allowed.value!!.untilElapsedMillis)
        assertTrue(watch.watching)
        pass(duration)
        assertNull(allowance.allowed.value)
        assertEquals(2, watch.starts)
        assertEquals(2, watch.stops)
    }

    @Test
    fun changingThePhonesClockNeitherStretchesNorShortensIt() = runTest(dispatcher) {
        allowance.grant()
        clock.wall -= 86_400_000 // Set back a day.
        pass(duration - 1)
        assertTrue(allowance.current() != null)
        pass(1)
        assertNull(allowance.current())

        allowance.grant()
        clock.wall += 86_400_000 // Set ahead a day.
        runCurrent()
        assertTrue(allowance.current() != null)
        pass(duration - 1)
        assertTrue(allowance.current() != null)
        pass(1)
        assertNull(allowance.current())
    }

    @Test
    fun anAllowanceThatCannotBeGuardedIsNotGiven() = runTest(dispatcher) {
        watch.refuses = true
        assertFalse(allowance.grant())
        assertNull(allowance.allowed.value)
        assertTrue(windowIsSecure())

        // Nor does a refusal leave an earlier allowance running unguarded.
        watch.refuses = false
        allowance.grant()
        watch.refuses = true
        assertFalse(allowance.grant())
        assertNull(allowance.allowed.value)
        assertFalse(watch.watching)
    }

    @Test
    fun aNewProcessStartsWithNothingAllowed() = runTest(dispatcher) {
        allowance.grant()
        // The allowance is an object in memory and nothing else: what a new process builds knows nothing of it.
        val afterRestart = ScreenshotAllowance(clock, FakeScreenOffWatch(), dispatcher)
        assertNull(afterRestart.allowed.value)
        assertNull(afterRestart.current())
        assertTrue(WindowSecurity.mustBeSecure(unlocked = true, afterRestart.current(), clock.elapsed, android13))
    }

    /**
     * The whole rule: secure unless the app is unlocked and an allowance is running at this
     * moment, on a version that can honour it. Every combination is listed: a screen on which a
     * passphrase is asked for (the app not unlocked) is secure in each of them.
     */
    @Test
    fun theWindowIsSecureUnlessTheUnlockedAppHasAnAllowanceRunningRightNow() {
        val now = 1_000_000L
        val running = ScreenshotsAllowed(untilElapsedMillis = now + 1, untilWallMillis = 0)
        val endingNow = ScreenshotsAllowed(untilElapsedMillis = now, untilWallMillis = 0)
        val over = ScreenshotsAllowed(untilElapsedMillis = now - 1, untilWallMillis = 0)
        val open = setOf(Triple(true, running, 33), Triple(true, running, 37))
        var rows = 0
        for (unlocked in listOf(true, false)) {
            for (allowed in listOf(null, running, endingNow, over)) {
                for (sdk in listOf(28, 32, 33, 37)) {
                    val secure = Triple(unlocked, allowed, sdk) !in open
                    assertEquals(
                        "unlocked $unlocked, allowance $allowed, Android API $sdk",
                        secure,
                        WindowSecurity.mustBeSecure(unlocked, allowed, now, sdk),
                    )
                    rows++
                }
            }
        }
        assertEquals(32, rows)
        // Said once more for what matters most: not unlocked is secure, with the longest allowance on the newest version.
        val forEver = ScreenshotsAllowed(untilElapsedMillis = Long.MAX_VALUE, untilWallMillis = Long.MAX_VALUE)
        assertTrue(WindowSecurity.mustBeSecure(unlocked = false, forEver, now, 37))
        assertFalse(WindowSecurity.mustBeSecure(unlocked = true, forEver, now, 37))
        // The wall clock time an allowance carries is for display and decides nothing.
        assertFalse(WindowSecurity.mustBeSecure(true, running.copy(untilWallMillis = Long.MIN_VALUE), now, 33))
        assertTrue(WindowSecurity.mustBeSecure(true, over.copy(untilWallMillis = Long.MAX_VALUE), now, 33))
    }

    /**
     * Dialogs and menus are built again exactly when the window turns secure under the unlocked
     * app: an allowance ended. Not when it opens, not while nothing changes, and not when the
     * app locks, which removes its screens anyway.
     */
    @Test
    fun openDialogsAreOnlyRenewedWhenTheWindowTurnsSecureUnderTheUnlockedApp() {
        for (wasSecure in listOf(true, false)) {
            for (secure in listOf(true, false)) {
                for (unlocked in listOf(true, false)) {
                    val expected = !wasSecure && secure && unlocked
                    assertEquals(
                        "was secure $wasSecure, is secure $secure, unlocked $unlocked",
                        expected,
                        WindowSecurity.mustRenewWindows(wasSecure = wasSecure, secure = secure, unlocked = unlocked),
                    )
                }
            }
        }
    }

    /** The two rules together, as the activity applies them while an allowance comes and goes. */
    @Test
    fun anAllowanceEndingUnderTheUnlockedAppRenewsItsWindowsOnce() = runTest(dispatcher) {
        var wasSecure = true
        var renewals = 0
        fun apply(unlocked: Boolean): Boolean {
            val secure = WindowSecurity.mustBeSecure(unlocked, allowance.current(), clock.elapsed, android13)
            if (WindowSecurity.mustRenewWindows(wasSecure, secure, unlocked)) renewals++
            wasSecure = secure
            return secure
        }
        assertTrue(apply(unlocked = true))
        allowance.grant()
        assertFalse(apply(unlocked = true))
        assertFalse(apply(unlocked = true))
        assertEquals(0, renewals)

        // The app locks during the allowance: secure at once, and nothing to renew.
        assertTrue(apply(unlocked = false))
        assertEquals(0, renewals)
        assertTrue("the allowance itself goes on", allowance.allowed.value != null)
        // Unlocked again within the ten minutes: open again.
        assertFalse(apply(unlocked = true))

        pass(duration)
        assertTrue(apply(unlocked = true))
        assertEquals("the dialogs that were open are built again", 1, renewals)
        assertTrue(apply(unlocked = true))
        assertEquals("once", 1, renewals)
    }

    @Test
    fun anAllowanceCanOnlyBeHonouredWhereThePreviewCanBeHiddenByItself() {
        assertEquals(33, WindowSecurity.FIRST_SDK_HIDING_THE_PREVIEW_SEPARATELY)
        for (sdk in 28..32) assertFalse("API $sdk", WindowSecurity.screenshotsCanBeAllowed(sdk))
        for (sdk in 33..37) assertTrue("API $sdk", WindowSecurity.screenshotsCanBeAllowed(sdk))
    }
}
