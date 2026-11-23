package us.theworkmans.proseplow.security

/**
 * The trusted time of this process ([TrustedTime]), read from the device's clocks: what new
 * messages are dated by, and what their expiry is measured on, so that a message's age is always
 * measured on the clock it was dated by.
 *
 * It lives as long as the process. The lock seeds it with the mark stored in the lock
 * configuration ([observe]) and stores its [mark] again at every unlock, every lock and every run
 * of the locked clean-up; in between, every reading advances the mark in memory, so the time it
 * gives never goes back within the process either, whatever the wall clock does.
 *
 * It is not the chats' clock itself (this package knows nothing of the chats); the application
 * hands [nowMillis] to them.
 *
 * Thread-safe: the chats read it on the main thread, the lock off it.
 */
class TrustedClock(private val clock: Clock) {

    private var latest: TimeMark? = null

    /**
     * Takes a stored mark into account: from now on the time given is at least what [stored]
     * says it is now. A mark that would give an earlier time changes nothing.
     */
    @Synchronized
    fun observe(stored: TimeMark?) {
        if (stored == null || !stored.isValid()) return
        val current = advance()
        val candidate = TrustedTime.mark(stored, wall(), elapsed(), boot())
        if (candidate.wallMillis > current.wallMillis) latest = candidate
    }

    /** The trusted time now, as a mark to store. */
    @Synchronized
    fun mark(): TimeMark = advance()

    /** The trusted time now. */
    @Synchronized
    fun nowMillis(): Long = advance().wallMillis

    private fun advance(): TimeMark = TrustedTime.mark(latest, wall(), elapsed(), boot()).also { latest = it }

    private fun wall(): Long = clock.wallMillis()

    private fun elapsed(): Long = clock.elapsedMillis().coerceAtLeast(0)

    /** As [LockManager] reads it: every failure, and a negative count, is "cannot be read". */
    private fun boot(): Int? = try {
        clock.bootCount()?.takeIf { it >= 0 }
    } catch (_: Exception) {
        null
    }
}
