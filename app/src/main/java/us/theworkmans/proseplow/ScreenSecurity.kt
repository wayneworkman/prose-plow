package us.theworkmans.proseplow

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.WindowManager
import androidx.core.content.ContextCompat
import us.theworkmans.proseplow.security.ScreenOffWatch

/**
 * The Android side of the window policy, and the only code that touches it: the secure flag of
 * the window, the app switcher's preview, and the broadcast that says the screen turned off.
 * What the policy is, and when a screenshot allowance ends, is decided in plain Kotlin
 * ([us.theworkmans.proseplow.security.WindowSecurity],
 * [us.theworkmans.proseplow.security.ScreenshotAllowance]); this class only carries it out.
 *
 * One instance for the process. The receiver is registered with the application, not with an
 * activity, so an allowance is ended by the screen turning off even while no activity exists.
 */
class ScreenSecurity(context: Context) : ScreenOffWatch {
    private val app = context.applicationContext

    /** Main thread only. Not null exactly while [receiver] is registered. */
    private var onScreenOff: (() -> Unit)? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) onScreenOff?.invoke()
        }
    }

    /**
     * Makes [activity]'s window secure or not, as the policy has decided. A secure window
     * (`FLAG_SECURE`) is left out of screenshots, screen recordings and screen sharing. Compose
     * dialogs, menus and popups take the flag over from this window (SecureFlagPolicy.Inherit,
     * their default) when they open.
     *
     * The app switcher's preview is turned off here as well, always and whatever [secure] says:
     * that picture of the app is shown without the user doing anything, so no allowance covers
     * it. Android can only do this by itself from version 13; before that the policy keeps the
     * window secure instead, which hides the preview too.
     */
    fun apply(activity: Activity, secure: Boolean) {
        val window = activity.window
        if (secure) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) activity.setRecentsScreenshotEnabled(false)
    }

    /**
     * Registers the receiver for `ACTION_SCREEN_OFF`, which the system only delivers to
     * receivers registered at run time. It is not exported: no other app can send it anything,
     * while the system's own broadcast still arrives.
     */
    override fun start(onScreenOff: () -> Unit) {
        val watching = this.onScreenOff != null
        this.onScreenOff = onScreenOff
        if (!watching) {
            try {
                ContextCompat.registerReceiver(app, receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
            } catch (e: RuntimeException) {
                this.onScreenOff = null
                throw e
            }
        }
    }

    override fun stop() {
        if (onScreenOff == null) return
        onScreenOff = null
        app.unregisterReceiver(receiver)
    }
}
