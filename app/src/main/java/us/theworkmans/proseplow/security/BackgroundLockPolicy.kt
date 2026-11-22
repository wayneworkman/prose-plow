package us.theworkmans.proseplow.security

/**
 * When the app locks after its activity went to the background: after the configured timeout,
 * and nothing else. Plain Kotlin, so it is unit tested; times are milliseconds since boot
 * ([Clock.elapsedMillis]). Not thread-safe (main thread).
 *
 * The timeout is counted from the moment the activity went to the background. A timer does the
 * locking, but a timer may not run while the process is frozen or the phone sleeps, so
 * [onForeground] checks again before anything is shown. Nothing here is stored: after process
 * death the app always starts locked.
 */
class BackgroundLockPolicy {
    private var backgroundedAt: Long? = null
    private var limitMillis = 0L

    /** The activity went to the background: milliseconds until the lock should engage (0: now). */
    fun onBackground(now: Long, timeoutMillis: Long): Long {
        backgroundedAt = now
        limitMillis = timeoutMillis
        return limitMillis
    }

    /**
     * Milliseconds until the lock is due while the activity is in the background (0: due now);
     * null while it is in the foreground. For a session that only opens once the activity has
     * already left (an unlock that finished late): its timer starts from what is left.
     */
    fun remainingMillis(now: Long): Long? = backgroundedAt?.let { (it + limitMillis - now).coerceIn(0, limitMillis) }

    /** The activity is visible again: true if the lock is due (its timer may not have run while frozen). */
    fun onForeground(now: Long): Boolean {
        val since = backgroundedAt ?: return false
        backgroundedAt = null
        return now - since >= limitMillis
    }

    /** The app locked: nothing pending applies any more. */
    fun reset() {
        backgroundedAt = null
        limitMillis = 0
    }
}
