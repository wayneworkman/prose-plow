package us.theworkmans.proseplow.security

/**
 * Everything that holds or reads decrypted data while the app is unlocked: the stores, their
 * writers, whatever works on their content, and the state of the screens. [AppLock] has one made
 * at every unlock, handing it the [DataCipher] of that unlock, and ends it when the app locks.
 * Nothing outside a session may keep decrypted data or the cipher.
 *
 * The lock only knows this interface, not what a session contains. The order at a lock is fixed:
 * [clearScreens] at once, then [close], then the lock itself destroys the data key. The cipher
 * works until [close] has returned, so writes that are queued can still be sealed; after that
 * every use of it fails ([KeyDestroyedException]), whatever [close] left running. A reset, which
 * deletes the files, also waits for [awaitStopped] before it deletes anything.
 *
 * Between [clearScreens] and [close] the lock may leave a session alive for a while, out of
 * sight, so that work the user asked for can be finished ("finish the answer after locking").
 * The lock knows nothing of what that work is: it asks [hasUnfinishedWork], tells the session
 * with [finishOnly] that nothing new may begin, waits for [awaitWorkFinished] (not for ever),
 * and either closes the session or, after the user has unlocked again, hands the same session
 * back with [resume].
 */
interface UnlockedSession {
    /**
     * Main thread, at the moment the screens go away (the app locks, or its last activity is
     * finished for good): ends what the screens were doing with decrypted data, queues their last
     * writes, and drops their state. Returns without waiting. The session can still be used
     * afterwards (the activity may come back) until [close] is called.
     */
    fun clearScreens()

    /**
     * Main thread. True while the session is doing something the user asked for and is waiting
     * for (an answer that is being written), which could be finished without a screen.
     */
    fun hasUnfinishedWork(): Boolean = false

    /** Returns when the work [hasUnfinishedWork] speaks of has ended, one way or the other. */
    suspend fun awaitWorkFinished() {}

    /**
     * Main thread, when the app has locked but this session is left alive to finish its work:
     * from now on it starts nothing new. What is running goes on, and is saved as it goes.
     */
    fun finishOnly() {}

    /**
     * Main thread, after the user has unlocked again while the session was left alive: it is
     * the session on screen once more, and takes new work as before.
     */
    fun resume() {}

    /**
     * How long messages are kept, in days as [LockConfig.messageLifetimeDays] counts them, as the
     * session's own (encrypted) settings say. Read under the lock's mutex at every unlock, which
     * writes the readable copy in the lock configuration from it, so that what the locked app
     * deletes follows the real setting. Null for a session that has no such setting, whose
     * unlock then leaves the copy as it is.
     */
    val messageLifetimeDays: Int? get() = null

    /**
     * Stops all work of the session, gives the writes that are queued their time to reach the
     * disk, and lets go of everything decrypted. Called once, off the main thread, after
     * [clearScreens]. [how] says what kind of end it is: whether the session may take one more
     * bounded wait to keep something for the next unlock ([SessionClose.UNATTENDED]) or must
     * wait for nothing it can drop ([SessionClose.IMMEDIATE]).
     *
     * It must return within a bounded time, whatever the session is stuck in: the data key is
     * destroyed when this returns, and a write that hangs must not keep it alive. What is left
     * running then can seal nothing more. At most a write whose bytes were already sealed can
     * still reach the disk, which is what [awaitStopped] is for.
     */
    suspend fun close(how: SessionClose)

    /**
     * Returns only when nothing of the session can touch a file any more, however long that
     * takes. Called after [close], and only where a late write would do harm: before a reset
     * deletes the files. A session whose [close] already waits for everything has nothing to add
     * here, which is the default.
     */
    suspend fun awaitStopped() {}
}

/**
 * What kind of end [UnlockedSession.close] is closing a session for. The lock decides it in one
 * place, from why the session ended; the session decides what it does with it.
 */
enum class SessionClose {
    /**
     * The user's own "Lock now" (also when it ends an answer being finished behind the lock), a
     * reset, or a session that was never shown: the strongest end. Nothing is waited for that can
     * be dropped, and nothing new is begun.
     */
    IMMEDIATE,

    /**
     * The app locked by itself, or an answer that was being finished behind the lock ended (by
     * itself, at its time limit, with the keep-alive, or by "Stop the answer"). Nobody is waiting
     * at that moment, and the session may take one more bounded wait to keep for the next unlock
     * what it can, for at most [KEEPING_WAIT_SECONDS].
     */
    UNATTENDED,
    ;

    companion object {
        /**
         * How long a close as [UNATTENDED] may wait, beside the waits every close has, to keep
         * something for the next unlock (the model's memory): ten seconds. The setting that
         * keeps the model's memory states it, so it is taken from here.
         */
        const val KEEPING_WAIT_SECONDS: Int = 10
    }
}
