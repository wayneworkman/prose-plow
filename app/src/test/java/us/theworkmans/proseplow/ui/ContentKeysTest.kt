package us.theworkmans.proseplow.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Which saved screen state may come back (the back stack, scroll positions): see ContentKeys. */
class ContentKeysTest {
    private var counter = 100L
    private val newKey = { ++counter }

    /** What ActivityState keeps in its SavedStateHandle. */
    private data class Saved(val key: Long?, val seen: Int?, val process: Long?)

    /**
     * One ActivityState in process [process], created from [restored] (null: a fresh start),
     * mirroring ActivityState.contentKey.
     */
    private inner class Instance(private val process: Long, restored: Saved? = null) {
        var saved: Saved = restored ?: Saved(null, null, null)
        private var seen: Int? = ContentKeys.seenAfterRestore(saved.seen, saved.process, process)

        fun key(generation: Int): Long = ContentKeys.next(saved.key, seen, generation, newKey).also {
            seen = generation
            saved = Saved(it, generation, process)
        }

        /** ActivityState.clearAll (the reset). */
        fun clearAll() {
            saved = Saved(null, null, null)
            seen = null
        }
    }

    private val processA = 1L
    private val processB = 2L

    @Test
    fun rotationKeepsTheKey() {
        val instance = Instance(processA)
        val first = instance.key(1)
        assertEquals(first, instance.key(1))
        assertEquals(first, instance.key(1))
    }

    @Test
    fun anActivityRecreatedInTheSameProcessRestoresTheSameSession() {
        val before = Instance(processA)
        before.key(1)
        val unlocked = before.key(3) // locked and unlocked earlier in this process
        // The activity (not the process) is destroyed and recreated: a new ActivityState from its saved state.
        assertEquals(unlocked, Instance(processA, before.saved).key(3))
    }

    @Test
    fun aRecreatedActivityStillGetsANewKeyIfTheAppLockedMeanwhile() {
        val before = Instance(processA)
        val unlocked = before.key(3)
        assertNotEquals(unlocked, Instance(processA, before.saved).key(5))
    }

    @Test
    fun processDeathNeverRestores() {
        val before = Instance(processA)
        val unlocked = before.key(1) // the first unlock after a cold start
        // The new process comes up locked; its first unlock is generation 1 too.
        assertNotEquals(unlocked, Instance(processB, before.saved).key(1))
        // Whatever the generation: the lock is mandatory, so there is no start without an unlock.
        assertNotEquals(unlocked, Instance(processB, before.saved).key(0))
    }

    @Test
    fun aLockOrUnlockInTheSameProcessChangesTheKey() {
        val instance = Instance(processA)
        val unlocked = instance.key(1)
        val afterUnlock = instance.key(3)
        assertNotEquals(unlocked, afterUnlock)
        assertEquals(afterUnlock, instance.key(3))
    }

    @Test
    fun aResetNeverRestores() {
        val instance = Instance(processA)
        val before = instance.key(1)
        instance.clearAll()
        val afterReset = instance.key(1)
        assertNotEquals(before, afterReset)
        // Nor does an activity recreated after the reset bring back the key from before it.
        assertNotEquals(before, Instance(processA, instance.saved).key(1))
        assertEquals(afterReset, Instance(processA, instance.saved).key(1))
    }

    @Test
    fun theProcessTokenAndTheKeysAreRandom() {
        assertNotEquals(ContentKeys.random(), ContentKeys.random())
        assertEquals(ContentKeys.processToken, ContentKeys.processToken)
        assertEquals(7, ContentKeys.seenAfterRestore(savedSeen = 7, savedProcess = ContentKeys.processToken, process = ContentKeys.processToken))
        assertEquals(null, ContentKeys.seenAfterRestore(savedSeen = 7, savedProcess = ContentKeys.processToken + 1, process = ContentKeys.processToken))
        assertEquals(null, ContentKeys.seenAfterRestore(savedSeen = 7, savedProcess = null, process = ContentKeys.processToken))
    }
}
