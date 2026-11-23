package us.theworkmans.proseplow.security

/**
 * Everything that holds or reads decrypted data while the app is unlocked: the stores, their
 * writers, whatever works on their content, and the state of the screens. [AppLock] has one made
 * at every unlock, handing it the [BlobCipher] of that unlock, and ends it when the app locks.
 * Nothing outside a session may keep decrypted data or the cipher.
 *
 * The lock only knows this interface, not what a session contains. The order at a lock is fixed:
 * [clearScreens] at once, then [close], then the lock itself destroys the data key. The cipher
 * works until [close] has returned, so writes that are queued can still be sealed; after that
 * every use of it fails ([KeyDestroyedException]), whatever [close] left running. A reset, which
 * deletes the files, also waits for [awaitStopped] before it deletes anything.
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
     * Stops all work of the session, gives the writes that are queued their time to reach the
     * disk, and lets go of everything decrypted. Called once, off the main thread, after
     * [clearScreens].
     *
     * It must return within a bounded time, whatever the session is stuck in: the data key is
     * destroyed when this returns, and a write that hangs must not keep it alive. What is left
     * running then can seal nothing more. At most a write whose bytes were already sealed can
     * still reach the disk, which is what [awaitStopped] is for.
     */
    suspend fun close()

    /**
     * Returns only when nothing of the session can touch a file any more, however long that
     * takes. Called after [close], and only where a late write would do harm: before a reset
     * deletes the files. A session whose [close] already waits for everything has nothing to add
     * here, which is the default.
     */
    suspend fun awaitStopped() {}
}
