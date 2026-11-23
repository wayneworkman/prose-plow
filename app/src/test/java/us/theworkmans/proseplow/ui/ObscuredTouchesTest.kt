package us.theworkmans.proseplow.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the user is told that a touch was discarded because something covers the app. */
class ObscuredTouchesTest {

    private val interval = DiscardedTouchNotice.INTERVAL_MILLIS
    private val toast = DiscardedTouchNotice.OWN_TOAST_MILLIS

    @Test
    fun theFirstDiscardedTouchIsExplained() {
        assertTrue(DiscardedTouchNotice.isDue(nowMillis = 0, lastNoticeMillis = null, lastOwnToastMillis = null))
        assertTrue(DiscardedTouchNotice.isDue(nowMillis = 123_456, lastNoticeMillis = null, lastOwnToastMillis = null))
    }

    @Test
    fun theNoticeIsNotRepeatedForEveryTouch() {
        val told = 50_000L
        assertFalse(DiscardedTouchNotice.isDue(told + 1, told, null))
        assertFalse(DiscardedTouchNotice.isDue(told + interval - 1, told, null))
        assertTrue(DiscardedTouchNotice.isDue(told + interval, told, null))
        assertTrue(DiscardedTouchNotice.isDue(told + 10 * interval, told, null))
    }

    @Test
    fun aTouchUnderOneOfTheAppsOwnToastsIsNotBlamedOnAnotherApp() {
        val shown = 50_000L
        assertFalse(DiscardedTouchNotice.isDue(shown, null, shown))
        assertFalse(DiscardedTouchNotice.isDue(shown + toast - 1, null, shown))
        assertTrue("once the toast is certainly gone", DiscardedTouchNotice.isDue(shown + toast, null, shown))
        // The notice is itself a toast of the app: both rules apply after it, and the longer one decides.
        assertFalse(DiscardedTouchNotice.isDue(shown + toast, shown, shown))
        assertTrue(DiscardedTouchNotice.isDue(shown + interval, shown, shown))
    }

    @Test
    fun aLongToastFitsInTheTimeAllowedForIt() {
        // Toast.LENGTH_LONG is shown for 3.5 seconds; the notice itself is such a toast.
        assertTrue(toast >= 3_500)
        assertEquals("so a notice is never repeated while the one before it still shows", true, interval > toast)
    }
}
