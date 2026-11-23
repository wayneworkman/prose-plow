package us.theworkmans.proseplow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import us.theworkmans.proseplow.core.engine.InferenceEngine
import us.theworkmans.proseplow.core.engine.MemoryReader
import us.theworkmans.proseplow.core.engine.MemorySaveResult
import us.theworkmans.proseplow.core.engine.MemoryVault
import us.theworkmans.proseplow.core.engine.MemoryWriter
import us.theworkmans.proseplow.core.engine.SaveTrigger
import us.theworkmans.proseplow.core.engine.SavedMemory
import us.theworkmans.proseplow.data.MemoryStore
import us.theworkmans.proseplow.security.SessionClose
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * The saves of the model's memory for one session, over the session's one [MemoryStore]: when
 * one is asked of the engine, how long a lock waits for it, and that nothing of it outlives the
 * session.
 *
 * - **When.** On leaving the screen and when an answer ends out of sight ([saveOnLeaving], with
 *   [SaveTrigger.LEFT_SCREEN]; the session decides that the app is unlocked and idle), and when
 *   the session closes by itself ([close] with [SessionClose.UNATTENDED]): a save that is
 *   running is joined, and when that did not leave the memory on disk one is started with
 *   [SaveTrigger.LOCK], all within [saveBoundMillis]. "Lock now" ([SessionClose.IMMEDIATE])
 *   waits for nothing and starts nothing.
 * - **The bound.** When it is reached, or at once for "Lock now", the save is stopped both ways:
 *   its coroutine is cancelled (the engine stops at its next piece) and [MemoryStore.cancelSave]
 *   lets the engine's thread go within one chunk, whatever it waits for. Neither is waited for:
 *   the session goes on to forget and the lock destroys the key, after which no further chunk
 *   can be sealed. The writer thread of the save may still be writing ciphertext; [awaitStopped]
 *   waits for it.
 * - **Nothing new after the close.** The engine is handed [vault], never the store itself. Once
 *   the session has closed, a save that the engine begins late (its thread was stuck and got to
 *   it only now) is refused before the store starts a writer thread for it, so that
 *   [awaitStopped] knows every thread that can touch a file.
 * - **The switch.** [setEnabled] off deletes the saved memory at once and stops a save that is
 *   running; while it is off [vault] is null and nothing is saved or restored. Each time it is
 *   turned on the engine gets a vault object it has not seen, so that nothing it knew of the
 *   deleted memory is taken for what is on disk.
 * - **Not twice for nothing.** A memory that is on disk as it is in the engine (saved or
 *   restored, and no request made since, as [requestsBuilt] counts them) is not written again
 *   at the lock.
 *
 * Main thread for [saveOnLeaving] and [setEnabled]; [close] and [awaitStopped] from the lock's
 * thread.
 *
 * @param heldConversation the key of the conversation the engine last worked on, or null.
 * @param requestsBuilt how many requests the chats have handed the engine so far.
 * @param enabled whether the setting is on now.
 * @param saveBoundMillis how long a lock by itself may wait for a save: the fourth bounded wait
 *   of a close, beside the reply, forgetting and the writes.
 */
internal class ModelMemoryKeeper(
    private val engine: InferenceEngine,
    private val store: MemoryStore,
    ioDispatcher: CoroutineDispatcher,
    private val heldConversation: () -> String?,
    private val requestsBuilt: () -> Long,
    enabled: Boolean,
    private val saveBoundMillis: Long,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /** The vault of the switch's current "on", or null while it is off or once the session has closed. */
    @Volatile private var current: Vault? = if (enabled) Vault() else null

    /** Set when the session closes: no save is begun from then on. */
    @Volatile private var closed = false

    /** Calls of [MemoryVault.beginSave] that are under way; see [awaitStopped]. */
    private val beginning = AtomicInteger(0)

    /** The latest save that was asked for, running or ended. */
    @Volatile private var saving: Deferred<MemorySaveResult>? = null

    /**
     * The count of requests ([requestsBuilt]) at which the memory on disk was known to be the
     * memory in the engine; -1 when it is not known to be.
     */
    @Volatile private var onDiskAt = -1L

    private val _savingOutOfSight = MutableStateFlow(false)

    /**
     * True while a save that an answer ending out of sight asked for runs: the session reports
     * an answer as still being written until it has ended, so that the keep-alive which was
     * already running covers it.
     */
    val savingOutOfSight: StateFlow<Boolean> = _savingOutOfSight.asStateFlow()

    /** The vault the engine is to save into and restore from now; null while nothing may be saved or restored. */
    fun vault(): MemoryVault? = current

    /**
     * Asks the engine for a save with [SaveTrigger.LEFT_SCREEN], unless the switch is off, the
     * session has closed, a save is running or the engine holds no conversation. [outOfSight]:
     * an answer ended out of sight ([savingOutOfSight]); otherwise the app has just left the
     * screen. The engine's own rules decide whether it really writes (enough is new, the memory
     * is worth keeping, it is not busy).
     */
    fun saveOnLeaving(outOfSight: Boolean) {
        val vault = current ?: return
        if (closed || saving?.isActive == true) return
        val key = heldConversation() ?: return
        start(vault, key, SaveTrigger.LEFT_SCREEN, outOfSight)
    }

    /** The memory in the engine was just read back from the one on disk ([InferenceEngine.restoreMemory]). */
    fun noteRestored() {
        onDiskAt = requestsBuilt()
    }

    /**
     * The switch: on gives the engine a new vault; off stops a save that is running and deletes
     * the saved memory at once (on the I/O dispatcher, without the key).
     */
    fun setEnabled(on: Boolean) {
        if (closed) return
        onDiskAt = -1
        if (on) {
            if (current == null) current = Vault()
            return
        }
        current = null
        stopSaving()
        scope.launch {
            try {
                store.delete()
            } catch (_: IOException) {
                // What is left is deleted at the next unlock, which finds the switch off.
            }
        }
    }

    private fun start(vault: Vault, key: String, trigger: SaveTrigger, outOfSight: Boolean): Deferred<MemorySaveResult> {
        val at = requestsBuilt()
        if (outOfSight) _savingOutOfSight.value = true
        val result = scope.async {
            try {
                engine.saveMemory(vault, key, trigger).also { if (it is MemorySaveResult.Saved && current === vault) onDiskAt = at }
            } catch (e: CancellationException) {
                throw e
            } catch (_: RuntimeException) {
                // The engine promises not to throw; a save that did is a save that failed.
                MemorySaveResult.NotSaved(MemorySaveResult.NotSaved.Reason.FAILED)
            } finally {
                if (outOfSight) _savingOutOfSight.value = false
            }
        }
        saving = result
        return result
    }

    /** Stops the save that is running, both ways (see the class comment), without waiting for it. */
    private fun stopSaving() {
        saving?.cancel()
        store.cancelSave()
    }

    /**
     * The session closes; see the class comment. With [SessionClose.UNATTENDED] and the switch on,
     * waits at most [saveBoundMillis] for a save of the conversation the engine holds: the one
     * running, then, unless that left the memory on disk as it is, one with [SaveTrigger.LOCK].
     * Then, and at once for [SessionClose.IMMEDIATE], nothing more may be begun and whatever is
     * still saving is stopped. Returns within the bound whatever the engine or the disk do.
     */
    suspend fun close(how: SessionClose) {
        try {
            val vault = current
            if (how == SessionClose.UNATTENDED && vault != null) {
                withTimeoutOrNull(saveBoundMillis) { saveForLock(vault) }
            }
        } finally {
            closed = true
            current = null
            stopSaving()
            _savingOutOfSight.value = false
        }
    }

    private suspend fun saveForLock(vault: Vault) {
        val running = saving?.takeIf { it.isActive }
        if (running != null) {
            try {
                running.await()
            } catch (e: CancellationException) {
                // The running save was cancelled, not this wait: the lock's own save follows.
                if (!running.isCancelled) throw e
            }
        }
        if (onDiskAt >= 0 && onDiskAt == requestsBuilt()) return
        val key = heldConversation() ?: return
        start(vault, key, SaveTrigger.LOCK, outOfSight = false).await()
    }

    /**
     * Returns once nothing of this keeper can touch a file any more: no save is being begun, and
     * every writer thread of the store has returned, however long that takes. Polls, so that it
     * never blocks a thread while it waits. Called after [close].
     */
    suspend fun awaitStopped() {
        while (beginning.get() != 0 || !store.awaitStopped(0)) delay(STOPPED_POLL_MILLIS)
    }

    /**
     * What the engine is given: the store, but no save is begun once the session has closed. The
     * count of [beginning] is raised before [closed] is looked at, so a call that got past the
     * look is waited for by [awaitStopped].
     */
    private inner class Vault : MemoryVault {
        override fun saved(): SavedMemory? = store.saved()

        override fun beginSave(memory: SavedMemory): MemoryWriter {
            beginning.incrementAndGet()
            try {
                if (closed || current !== this) throw IOException("The session has closed, or saving was switched off")
                val writer = store.beginSave(memory)
                if (closed || current !== this) {
                    store.cancelSave()
                    writer.close()
                    throw IOException("The session has closed, or saving was switched off")
                }
                return writer
            } finally {
                beginning.decrementAndGet()
            }
        }

        override fun beginRestore(): MemoryReader = store.beginRestore()

        override fun delete() = store.delete()
    }

    private companion object {
        const val STOPPED_POLL_MILLIS = 20L
    }
}
