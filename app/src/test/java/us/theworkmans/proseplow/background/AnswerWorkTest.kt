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
    )

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
        val watched = KeepAliveEntry.enter(source) {
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
        assertSame(work.keepAliveWanted, KeepAliveEntry.enter(source) { })
        assertTrue(work.keepAliveRunning)
        work.onKeepAlive(false)
        assertFalse("ended with the service", work.keepAliveRunning)
    }

    @Test
    fun aServiceInAProcessThatNeverWroteAnAnswerStopsAtOnce() {
        var asked = 0
        assertNull(KeepAliveEntry.enter(null) { asked++ })
        assertEquals("in the foreground first, whatever follows", 1, asked)
        val empty = object : KeepAliveSource {
            override val keepAliveWanted: StateFlow<Boolean>? = null

            override fun onKeepAlive(running: Boolean) = fail("nothing to tell")

            override fun onKeepAliveRefused() = fail("nothing to tell")
        }
        assertNull(KeepAliveEntry.enter(empty) { })
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
