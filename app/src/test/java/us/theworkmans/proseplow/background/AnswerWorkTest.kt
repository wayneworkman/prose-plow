package us.theworkmans.proseplow.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
