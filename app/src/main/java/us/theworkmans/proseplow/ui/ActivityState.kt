package us.theworkmans.proseplow.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import java.security.SecureRandom

/**
 * Activity-scoped state that outlives the lock: a ViewModel in the activity's own store (not in
 * the unlocked session's, which a lock clears). It holds only the key that the unlocked screens'
 * saved state is filed under, never anything decrypted.
 */
class ActivityState(private val savedState: SavedStateHandle) : ViewModel() {

    /**
     * The last session generation seen (the lock's `sessionGeneration`). Saved with the process
     * it was seen in: an activity recreated in the same process (no lock in between) continues
     * from it, while after process death it counts as never seen.
     */
    private var seenGeneration: Int? = ContentKeys.seenAfterRestore(
        savedSeen = savedState.get<Int>(KEY_SEEN_GENERATION),
        savedProcess = savedState.get<Long>(KEY_PROCESS),
        process = ContentKeys.processToken,
    )

    /**
     * The key of the unlocked screens' composition for session [generation] (main thread). It is
     * kept in the saved state so that a rotation, and an activity recreated in the same process,
     * find the screens' saved state (the back stack, scroll positions) again; see [ContentKeys].
     */
    fun contentKey(generation: Int): Long {
        val key = ContentKeys.next(savedState.get<Long>(KEY_CONTENT), seenGeneration, generation, ContentKeys::random)
        seenGeneration = generation
        savedState[KEY_CONTENT] = key
        savedState[KEY_SEEN_GENERATION] = generation
        savedState[KEY_PROCESS] = ContentKeys.processToken
        return key
    }

    /** After "Reset app data": nothing from before may come back. */
    fun clearAll() {
        for (key in savedState.keys()) savedState.remove<Any>(key)
        seenGeneration = null
    }

    private companion object {
        const val KEY_CONTENT = "content_key"
        const val KEY_SEEN_GENERATION = "content_generation"
        const val KEY_PROCESS = "content_process"
    }
}

/**
 * Picks the key that the unlocked screens' saved Compose state (`rememberSaveable`, the
 * navigation back stack) is stored under. Saved state only comes back under the same key, so a
 * new key makes everything saved before unreachable. The rule: saved state never survives a
 * lock.
 *
 * - The same generation as last seen in this process (a rotation, the composition rebuilt, or
 *   the activity recreated in the same process): the same key.
 * - Anything else: a new key. That covers a lock, an unlock and a reset (each changes the
 *   generation), and a restore after process death (never seen in this process), because the
 *   app always comes back locked from that.
 */
object ContentKeys {
    /** Random per process: tells an activity recreated in this process from one restored after process death. */
    val processToken: Long = random()

    fun next(savedKey: Long?, seenGeneration: Int?, generation: Int, newKey: () -> Long): Long =
        if (savedKey != null && seenGeneration == generation) savedKey else newKey()

    /** The generation a restored state saw, only if it was saved by this very process. */
    fun seenAfterRestore(savedSeen: Int?, savedProcess: Long?, process: Long): Int? = if (savedProcess == process) savedSeen else null

    fun random(): Long = SecureRandom().nextLong()
}
