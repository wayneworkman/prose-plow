package us.theworkmans.proseplow.background

import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * When the process is kept alive out of sight, and when "Your answer is ready." is said: the
 * rules on their own, with what Android would do replaced by counters.
 */
class AnswerWorkTest {

    private var starts = 0
    private var startWorks = true
    private var shown = 0
    private var cleared = 0

    /** What Android says at the moment of asking. */
    private var restricted = false
    private var inForeground = true

    /** How often the keep-alive's end was told onward (to the lock, in the app). */
    private var endsTold = 0

    private val work = AnswerWork(
        keepAlive = {
            starts++
            startWorks
        },
        readyNotice = object : ReadyNotice {
            override fun show() {
                shown++
            }

            override fun clear() {
                cleared++
            }
        },
        probe = object : KeepAliveProbe {
            override fun backgroundRestricted() = restricted

            override fun serviceInForeground() = inForeground
        },
    ).apply { whenKeepAliveEnds { endsTold++ } }

    private val wanted get() = work.keepAliveWanted.value

    @Test
    fun nothingIsKeptAliveUntilAnAnswerIsWritten() {
        assertFalse(wanted)
        work.onInFront(true)
        work.onSessionEnding(true)
        work.onSessionEnding(false)
        work.onInFront(false)
        assertFalse(wanted)
        assertEquals(0, starts)
    }

    @Test
    fun itIsStartedWhenAnAnswerBeginsInFrontAndEndsWithTheAnswer() {
        work.onInFront(true)
        work.onWriting(true)
        assertTrue(wanted)
        assertEquals(1, starts)
        work.onWriting(true) // Said twice: nothing is started twice.
        assertEquals(1, starts)

        // Leaving the screen, and the screen turning off, change nothing: that is what it is for.
        work.onInFront(false)
        assertTrue(wanted)

        work.onWriting(false)
        assertFalse(wanted)
        work.onWriting(false)
        assertFalse(wanted)
        assertEquals(1, starts)
    }

    @Test
    fun itStaysUntilASessionThatIsEndingBehindTheLockHasEnded() {
        work.onInFront(true)
        work.onWriting(true)
        work.onInFront(false)
        // The app locks by itself: the session is left alive to finish the answer.
        work.onSessionEnding(true)
        assertTrue(wanted)
        // The answer is finished; the session is still being saved and closed, its key still in memory.
        work.onWriting(false)
        assertTrue("not let go before the key is gone", wanted)
        work.onSessionEnding(false)
        assertFalse(wanted)
        assertEquals(1, starts)
    }

    @Test
    fun aSessionEndingWithoutAnAnswerStartsNothing() {
        work.onInFront(true)
        work.onSessionEnding(true)
        assertFalse("an ordinary lock is no reason to start anything", wanted)
        work.onSessionEnding(false)
        assertEquals(0, starts)
    }

    @Test
    fun nothingIsStartedFromTheBackground() {
        // Android does not allow it. An answer can only begin with the app in front; if it ever
        // did otherwise, the answer is written without the service.
        work.onWriting(true)
        assertFalse(wanted)
        assertEquals(0, starts)
        work.onWriting(false)
        assertFalse(wanted)
    }

    @Test
    fun aStartThatAndroidRefusesLeavesNothingWanted() {
        startWorks = false
        work.onInFront(true)
        work.onWriting(true)
        assertEquals(1, starts)
        assertFalse(wanted)
        work.onWriting(false)

        // The next answer tries again.
        startWorks = true
        work.onWriting(true)
        assertEquals(2, starts)
        assertTrue(wanted)
    }

    /** The answer service's own entry, wired to [work] as the application wires it. */
    private val source = object : KeepAliveSource {
        override val keepAliveWanted: StateFlow<Boolean> get() = work.keepAliveWanted

        override fun onKeepAlive(running: Boolean) = work.onKeepAlive(running)

        override fun onKeepAliveRefused() = work.onKeepAliveRefused()
    }

    /**
     * The security review's D5: `startForeground` throwing when the service is created. Nothing
     * may go on believing that a keep-alive exists: it is neither running nor wanted, and the
     * service stops at once.
     */
    @Test
    fun aForegroundThatAndroidRefusesIsToldAndNothingBelievesAKeepAliveExists() {
        work.onInFront(true)
        work.onWriting(true)
        assertTrue(wanted)
        assertFalse("not running before the service says so", work.keepAliveRunning)

        var asked = 0
        val watched = KeepAliveEntry.enter(source, { true }) {
            asked++
            throw IllegalStateException("Android refused")
        }
        assertEquals(1, asked)
        assertNull("the service stops at once", watched)
        assertFalse(work.keepAliveRunning)
        assertFalse(wanted)

        // The next answer asks again, and this time the service reaches the foreground.
        work.onWriting(false)
        work.onWriting(true)
        assertEquals(2, starts)
        assertSame(work.keepAliveWanted, KeepAliveEntry.enter(source, { true }) { })
        assertTrue(work.keepAliveRunning)
        work.onKeepAlive(false)
        assertFalse("ended with the service", work.keepAliveRunning)
    }

    /**
     * The third security review's finding 1: Android may return from `startForeground` without
     * putting the service in the foreground (its app-op set to ignore, or the app's background
     * use restricted and the app not on top). That is a refusal like a thrown one.
     */
    @Test
    fun aStartForegroundThatReturnsWithoutEffectIsARefusal() {
        work.onInFront(true)
        work.onWriting(true)
        assertTrue(wanted)
        var asked = 0
        val watched = KeepAliveEntry.enter(source, { false }) { asked++ }
        assertEquals("startForeground was called and returned", 1, asked)
        assertNull("the service stops at once", watched)
        assertFalse(work.keepAliveRunning)
        assertFalse(wanted)
        assertEquals("the end is told onward", 1, endsTold)
        assertFalse(work.canFinishOutOfSight())
    }

    /** Finding 1: the lock's question is answered by asking Android at that moment, not by the flag alone. */
    @Test
    fun whetherAnAnswerCanBeFinishedOutOfSightIsAskedOfAndroidAtThatMoment() {
        work.onInFront(true)
        work.onWriting(true)
        assertFalse("not before the service says that it runs", work.canFinishOutOfSight())
        KeepAliveEntry.enter(source, { true }) { }
        assertTrue(work.keepAliveRunning)
        assertTrue(work.canFinishOutOfSight())

        restricted = true
        assertFalse("the user has restricted the app's background use", work.canFinishOutOfSight())
        restricted = false
        inForeground = false
        assertFalse("Android took the service out of the foreground without a word", work.canFinishOutOfSight())
        assertTrue("the flag alone still says it runs", work.keepAliveRunning)
        inForeground = true
        assertTrue(work.canFinishOutOfSight())
    }

    /**
     * Finding 1's net: the service ended while it was still wanted (stopped from outside). The
     * end is told onward, nothing is kept wanted, and coming back to the front asks again.
     */
    @Test
    fun aKeepAliveStoppedFromOutsideIsToldOnwardAndAskedForAgainInFront() {
        work.onInFront(true)
        work.onWriting(true)
        KeepAliveEntry.enter(source, { true }) { }
        work.onInFront(false)
        assertEquals(1, starts)

        work.onKeepAlive(false) // As the service says when it is destroyed.
        assertFalse(work.keepAliveRunning)
        assertFalse(wanted)
        assertEquals(1, endsTold)
        assertFalse(work.canFinishOutOfSight())

        work.onInFront(true)
        assertEquals("asked for again, the answer still being written", 2, starts)
        assertTrue(wanted)
    }

    /** The note under "Suspicions": a refused keep-alive is asked for again when the app comes back to the front. */
    @Test
    fun aRefusedKeepAliveIsAskedForAgainWhenTheAppComesBackToTheFront() {
        startWorks = false
        work.onInFront(true)
        work.onWriting(true)
        assertFalse(wanted)
        work.onInFront(false)
        startWorks = true
        work.onInFront(false)
        assertEquals("never from the background", 1, starts)
        work.onInFront(true)
        assertEquals(2, starts)
        assertTrue(wanted)
        work.onInFront(true) // Wanted already: nothing is started twice.
        assertEquals(2, starts)

        // Without an answer being written, coming to the front starts nothing.
        work.onWriting(false)
        work.onInFront(false)
        work.onInFront(true)
        assertEquals(2, starts)
    }

    /** Finding 4: the service says that the keep-alive ends before it leaves the foreground, and its end is told onward once. */
    @Test
    fun theNormalEndOfTheServiceIsToldOnwardAndLeavesNothingWanted() {
        work.onInFront(true)
        work.onWriting(true)
        KeepAliveEntry.enter(source, { true }) { }
        work.onWriting(false)
        assertFalse(wanted)
        assertTrue("until the service says so", work.keepAliveRunning)
        work.onKeepAlive(false)
        assertFalse(work.keepAliveRunning)
        assertEquals(1, endsTold)
    }

    @Test
    fun aServiceInAProcessThatNeverWroteAnAnswerStopsAtOnce() {
        var asked = 0
        assertNull(KeepAliveEntry.enter(null, { true }) { asked++ })
        assertEquals("in the foreground first, whatever follows", 1, asked)
        val empty = object : KeepAliveSource {
            override val keepAliveWanted: StateFlow<Boolean>? = null

            override fun onKeepAlive(running: Boolean) = fail("nothing to tell")

            override fun onKeepAliveRefused() = fail("nothing to tell")
        }
        assertNull(KeepAliveEntry.enter(empty, { true }) { })
    }

    @Test
    fun anAnswerBegunWhileTheLastSessionIsStillEndingNeedsNoSecondStart() {
        work.onInFront(true)
        work.onWriting(true)
        work.onSessionEnding(true)
        work.onWriting(false)
        assertTrue(wanted)
        work.onWriting(true)
        assertEquals("it was never let go", 1, starts)
        work.onSessionEnding(false)
        assertTrue(wanted)
        work.onWriting(false)
        assertFalse(wanted)
    }

    @Test
    fun readyIsSaidOnlyToSomeoneWhoIsNotLooking() {
        work.onInFront(true)
        assertEquals("coming to the front takes an old notification away", 1, cleared)
        work.onWriting(true)
        work.onReplyFinished()
        work.onWriting(false)
        assertEquals("the answer is on the screen in front of them", 0, shown)

        work.onWriting(true)
        work.onInFront(false)
        work.onReplyFinished()
        work.onWriting(false)
        assertEquals(1, shown)
        assertEquals(1, cleared)

        work.onInFront(true)
        assertEquals(2, cleared)
        assertEquals(1, shown)
    }

    @Test
    fun anAnswerThatOnlyStoppedIsNotAnnounced() {
        work.onInFront(true)
        work.onWriting(true)
        work.onInFront(false)
        // Stopped by the user, by the lock or by its ten minutes, or failed: writing ends, nothing was finished.
        work.onWriting(false)
        assertEquals(0, shown)
    }

    @Test
    fun theSessionThatListensToNobodyDoesNothing() {
        AnswerEvents.None.onWriting(true)
        AnswerEvents.None.onReplyFinished()
        AnswerEvents.None.onWriting(false)
    }

    @Test
    fun thePermissionToNotifyIsAskedForOnceAndOnlyWhereItExists() {
        assertEquals(33, NotificationPermission.FIRST_SDK_ASKING)
        for (sdk in 28..32) {
            assertFalse("API $sdk has no such permission", NotificationPermission.shouldAsk(sdk, granted = false, askedBefore = false))
        }
        for (sdk in 33..37) {
            assertTrue("API $sdk", NotificationPermission.shouldAsk(sdk, granted = false, askedBefore = false))
            assertFalse("never a second time, whatever was answered", NotificationPermission.shouldAsk(sdk, granted = false, askedBefore = true))
            assertFalse("nor when it is already given", NotificationPermission.shouldAsk(sdk, granted = true, askedBefore = false))
            assertFalse(NotificationPermission.shouldAsk(sdk, granted = true, askedBefore = true))
        }
    }
}
