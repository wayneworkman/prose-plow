package us.theworkmans.proseplow.ui

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.Toast

/**
 * The root of the activity's content: a plain frame that discards every touch which arrives
 * while another app's window covers the place that was touched (`filterTouchesWhenObscured`).
 *
 * An app that may draw over others can put a harmless-looking picture over this one and let the
 * taps through, so that the user presses a button of this app without seeing it ("Allow
 * screenshots", "Lock after", a confirmation). Android 12 and later stop most of this by
 * themselves; the versions before do not, and partly transparent overlays are let through on
 * every version. With this, a tap only counts when nothing of another app is over it.
 *
 * The system's own windows do not count as covering: the status and navigation bars, the gesture
 * area and the keyboard take the touches that land on them and leave the rest alone. What does
 * count is any other app's overlay that covers the whole screen (a screen dimmer, a colour
 * filter): while one is active, no tap reaches this app. [onTouchDiscarded] is called then,
 * once for each finger that goes down, so that the app can say why nothing happens.
 */
class ObscuredTouchFilterLayout(context: Context) : FrameLayout(context) {

    var onTouchDiscarded: (() -> Unit)? = null

    init {
        filterTouchesWhenObscured = true
    }

    override fun onFilterTouchEventForSecurity(event: MotionEvent): Boolean {
        val allowed = super.onFilterTouchEventForSecurity(event)
        if (!allowed && event.actionMasked == MotionEvent.ACTION_DOWN) onTouchDiscarded?.invoke()
        return allowed
    }
}

/**
 * Every toast of the app is shown through here, so that the app knows when one of its own may
 * be on screen.
 *
 * A toast is a window over the app that lets touches through, which is exactly what the touch
 * filter looks for; from Android 11 it is drawn by the system, not by the app. A tap on what a
 * toast covers is therefore discarded like any other covered tap. That is harmless (the toast
 * is gone in a moment), but it must not be reported as another app drawing over this one.
 */
object AppToasts {
    /** [SystemClock.uptimeMillis] when the last toast was shown; null before the first. Main thread only. */
    var lastShownAt: Long? = null
        private set

    fun show(context: Context, text: CharSequence, long: Boolean = false) {
        lastShownAt = SystemClock.uptimeMillis()
        Toast.makeText(context, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }
}

/** Decides whether the user is told that a touch was discarded; see [ObscuredTouchFilterLayout]. */
object DiscardedTouchNotice {
    /** The notice is not repeated more often than this, however many touches are discarded. */
    const val INTERVAL_MILLIS = 10_000L

    /** How long one of the app's own toasts may be on screen: a long toast shows for 3.5 seconds. */
    const val OWN_TOAST_MILLIS = 4_000L

    /**
     * True when a touch that was discarded at [nowMillis] should be explained: no notice was
     * given in the last [INTERVAL_MILLIS], and the cover cannot be a toast of the app itself
     * (the notice is one of them). All three times are on the same clock; null means never.
     */
    fun isDue(nowMillis: Long, lastNoticeMillis: Long?, lastOwnToastMillis: Long?): Boolean {
        if (lastOwnToastMillis != null && nowMillis - lastOwnToastMillis < OWN_TOAST_MILLIS) return false
        return lastNoticeMillis == null || nowMillis - lastNoticeMillis >= INTERVAL_MILLIS
    }
}
