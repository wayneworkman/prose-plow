package us.theworkmans.proseplow.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Does the app's file writes in the background, one at a time, and never more of them than
 * needed.
 *
 * Every write belongs to a key (one key per file) and says everything about that file: "store
 * this session", "delete this session". A newer write for a key therefore replaces an older one
 * that has not run yet, and nothing is lost by it. Keys are served in the order in which they
 * first asked, so one busy file cannot starve the others.
 *
 * A write is either urgent, and runs as soon as the writer is free, or lazy, and waits up to
 * [lazyMillis] for a newer one to replace it. Lazy is for what changes many times a second (a
 * reply that is being streamed, a draft that is being typed): the file then follows at most that
 * far behind. [flush] makes everything lazy urgent; [closeAndJoin] does the same and returns when
 * all of it is on the disk. The lock waits for that before it destroys the data key, but only
 * for a bounded time: a write that is stuck in the disk cannot be interrupted, so whoever closes
 * the writer decides how long to wait and then cancels the scope. The write that is running
 * still ends when the disk lets it, and the scope's job only completes then.
 *
 * @param scope where the writes run: an I/O scope. Cancelling it abandons what was not written.
 * @param lazyMillis the longest a lazy write waits.
 * @param onFailure called on the writer's thread when a write throws, with its key; the other
 *   writes go on. The key's next write is a new attempt.
 */
class CoalescingWriter(
    private val scope: CoroutineScope,
    private val lazyMillis: Long,
    private val onFailure: (key: String, failure: Exception) -> Unit,
) {
    private val lock = Any()

    // Everything down to the timer is guarded by lock.
    private val queued = LinkedHashMap<String, () -> Unit>()
    private val lazy = LinkedHashMap<String, () -> Unit>()
    private var closed = false
    private var writing = false

    /** Runs out when the oldest lazy write has waited long enough; null while nothing is lazy. */
    private var timer: Job? = null

    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val worker: Job = scope.launch {
        while (wake.receiveCatching().isSuccess) drain()
        // Whatever was queued between the last signal and the close.
        drain()
    }

    /**
     * Asks for [write] to be run for [key], in place of any write for that key that has not
     * started yet.
     *
     * @param urgent false lets the write wait up to [lazyMillis] for a newer one.
     * @return false when the writer is closed; nothing will be written then.
     */
    fun submit(key: String, urgent: Boolean = true, write: () -> Unit): Boolean {
        var newTimer: Job? = null
        synchronized(lock) {
            if (closed) return false
            if (urgent || key in queued) {
                // Urgent, or already on its way to the disk: it goes with the newest content.
                lazy.remove(key)
                queued[key] = write
            } else {
                lazy[key] = write
                if (timer == null) {
                    newTimer = scope.launch(start = CoroutineStart.LAZY) {
                        delay(lazyMillis)
                        flush()
                    }
                    timer = newTimer
                }
            }
        }
        newTimer?.start()
        wake.trySend(Unit)
        return true
    }

    /** Makes every lazy write urgent. Returns at once; the writing happens in the background. */
    fun flush() {
        synchronized(lock) { promoteLazy() }
        wake.trySend(Unit)
    }

    /** True when no write is waiting or running. */
    val isIdle: Boolean get() = synchronized(lock) { queued.isEmpty() && lazy.isEmpty() && !writing }

    /**
     * Accepts nothing more, and returns when every write asked for so far has run. Cancelling
     * the caller (a timeout) stops the waiting, not the writes. Cancelling the writer's scope
     * abandons the writes that have not started; the one that is running cannot be interrupted
     * and finishes first.
     */
    suspend fun closeAndJoin() {
        synchronized(lock) {
            closed = true
            promoteLazy()
        }
        wake.close()
        worker.join()
    }

    /** Holding the lock: moves every lazy write into the queue, behind what is already there. */
    private fun promoteLazy() {
        for ((key, write) in lazy) queued[key] = write
        lazy.clear()
        // Cancelling is harmless when it is the timer itself that got here: it has nothing left to do.
        timer?.cancel()
        timer = null
    }

    private fun drain() {
        while (true) {
            // Nothing is started once the scope is cancelled: the write that was running when
            // that happened was the last one, and what is still queued is abandoned.
            if (!scope.isActive) return
            val next = synchronized(lock) {
                val first = queued.entries.firstOrNull()?.toPair()
                if (first != null) {
                    queued.remove(first.first)
                    writing = true
                }
                first
            } ?: return
            try {
                next.second()
            } catch (e: Exception) {
                onFailure(next.first, e)
            } finally {
                synchronized(lock) { writing = false }
            }
        }
    }
}
