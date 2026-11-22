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
 * every use of it fails ([KeyDestroyedException]), whatever [close] left running.
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
     * Stops all work of the session, waits (for a bounded time) until queued writes are on disk,
     * and lets go of everything decrypted. Called once, off the main thread, after
     * [clearScreens]. Nothing may write a file of the app once this has returned: a reset relies
     * on that when it deletes them.
     */
    suspend fun close()
}
