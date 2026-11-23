package us.theworkmans.proseplow.background

/**
 * Remembers for a while that Android has said memory is short, so that the model's context is
 * not made larger just then. Plain Kotlin; the application object feeds it the system's
 * callbacks, and the memory gauge adds it to what the system says when asked.
 *
 * It is a supplement. Since Android 14 a running app is no longer told about most of these
 * levels, so what really guards a growth step is the reading of the device's memory taken at
 * that moment; this only widens it by what the system volunteered shortly before.
 *
 * @param now a clock that counts while the phone sleeps, in milliseconds.
 */
class MemoryPressure(private val now: () -> Long) {
    @Volatile private var signalledAt: Long? = null

    /** `ComponentCallbacks2.onTrimMemory`: the levels that speak of memory being short count, the ones about the app's own place do not. */
    fun onTrimLevel(level: Int) {
        if (level == RUNNING_LOW || level == RUNNING_CRITICAL || level >= MODERATE) signalledAt = now()
    }

    /** `onLowMemory`: the whole system is short. */
    fun onLowMemory() {
        signalledAt = now()
    }

    /** True for [HOLD_MILLIS] after the last signal. */
    val isSet: Boolean
        get() {
            val at = signalledAt ?: return false
            val since = now() - at
            return since in 0 until HOLD_MILLIS
        }

    companion object {
        /** How long one signal holds growth back. */
        const val HOLD_MILLIS: Long = 60_000

        // The levels of android.content.ComponentCallbacks2, by value, so that this file needs no Android.
        const val RUNNING_LOW: Int = 10
        const val RUNNING_CRITICAL: Int = 15
        const val UI_HIDDEN: Int = 20
        const val BACKGROUND: Int = 40
        const val MODERATE: Int = 60
        const val COMPLETE: Int = 80
    }
}
