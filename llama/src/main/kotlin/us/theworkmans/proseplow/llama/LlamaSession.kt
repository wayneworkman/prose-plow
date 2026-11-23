package us.theworkmans.proseplow.llama

import us.theworkmans.proseplow.core.engine.EvaluationObserver
import us.theworkmans.proseplow.core.engine.MemoryCheckpoint
import us.theworkmans.proseplow.core.engine.MemorySink
import us.theworkmans.proseplow.core.engine.MemorySource
import us.theworkmans.proseplow.core.engine.NativeLlama
import us.theworkmans.proseplow.core.engine.NativeLlamaException
import us.theworkmans.proseplow.core.engine.SamplerConfig
import java.nio.ByteBuffer

/**
 * A loaded model: [NativeLlama] over one native session.
 *
 * Every method is a single native call; the contract of each is the one written on the
 * interface, and what the native side does to honour it is described in
 * `native/session/include/proseplow_session.h`. Two behaviours differ from the letter of the
 * interface and are worth knowing here:
 * - A model with recurrent state (Qwen3.5, Boris-2) is only asked whether to stop between
 *   chunks, never in the middle of one, because llama.cpp cannot take a half-computed chunk out
 *   of such a model's state again.
 * - [clearMemory] frees the context, the batch and the sampler and creates them again, the
 *   context at the size the session was opened with, after
 *   overwriting what the session itself holds of the conversation. It cannot overwrite the
 *   copies of the text and of the tokens that llama.cpp made and freed, and those stay readable
 *   in the process until the allocator reuses their memory (the header states what was
 *   measured). It throws [us.theworkmans.proseplow.core.engine.NativeLlamaException] when the
 *   new context could not be created; the session must then be closed.
 * - [resizeContext] does the same overwriting and freeing for another size. When the memory
 *   fits the budget beside a context it is carried over through one native block, which is
 *   overwritten before it is freed, and [memoryTokenCount] is then what it was; otherwise the
 *   memory comes back empty. A size the address space has no room for is reported, but an
 *   allocation that Android grants and cannot back ends the process: the budget, which the
 *   caller derives from the memory the system has free, is what protects against that.
 * - [exportMemory] and [importMemory] hand the native session the memory of the caller's direct
 *   buffer as it is: the state goes between llama.cpp's buffers and that memory and through
 *   nothing else, neither another array nor a copy in the binding, and the session overwrites
 *   the whole buffer before it returns. (On Android the direct buffer is a non-movable array of
 *   the runtime: it is never moved, so it is overwritten where it lies.) A buffer that is not direct, is read-only, is
 *   below the smallest piece or does not contain the offset is refused here, before native code
 *   is reached. An exception thrown by the sink or the source stops the native call; the
 *   binding keeps it while the session winds down (an import then leaves an empty memory) and
 *   throws it from here, as itself, afterwards.
 *
 * Invariant: [handle] is the native session until [close] and 0 afterwards. A call after
 * [close] is a bug in the caller and fails with [IllegalStateException] rather than reaching
 * native code with a freed pointer. Like the interface, the class is not thread-safe.
 */
internal class LlamaSession(private var handle: Long) : NativeLlama {

    private fun session(): Long {
        check(handle != 0L) { "The native session is closed" }
        return handle
    }

    override fun tokenize(utf8: ByteArray, parseSpecial: Boolean): IntArray = LlamaJni.tokenize(session(), utf8, parseSpecial)

    override fun tokenToBytes(token: Int): ByteArray = LlamaJni.tokenToBytes(session(), token)

    override fun isEndOfGeneration(token: Int): Boolean = LlamaJni.isEndOfGeneration(session(), token)

    override fun contextSize(): Int = LlamaJni.contextSize(session())

    override val maxContextSize: Int get() = LlamaJni.maxContextSize(session())

    override fun contextBytes(tokens: Int): Long = LlamaJni.contextBytes(session(), tokens)

    override fun resizeContext(tokens: Int, budgetBytes: Long) = LlamaJni.resizeContext(session(), tokens, budgetBytes)

    override fun memoryTokenCount(): Int = LlamaJni.memoryTokenCount(session())

    override fun evaluate(tokens: IntArray, chunkSize: Int, observer: EvaluationObserver): Int =
        LlamaJni.evaluate(session(), tokens, chunkSize, observer)

    override fun rewindTo(tokenCount: Int): Boolean = LlamaJni.rewindTo(session(), tokenCount)

    override fun saveCheckpoint(): Int = LlamaJni.saveCheckpoint(session())

    override fun restoreCheckpoint(checkpoint: Int): Boolean = LlamaJni.restoreCheckpoint(session(), checkpoint)

    override fun dropCheckpoint(checkpoint: Int) = LlamaJni.dropCheckpoint(session(), checkpoint)

    override fun clearMemory() = LlamaJni.clearMemory(session())

    override fun configureSampler(config: SamplerConfig) = LlamaJni.configureSampler(
        session(),
        config.temperature,
        config.topK,
        config.topP,
        config.minP,
        config.repetitionPenalty,
        config.presencePenalty,
        config.frequencyPenalty,
        config.penaltyWindow,
        config.replyStart,
        config.seed,
        config.bannedTokenIds.toIntArray(),
    )

    override fun sampleNext(): Int = LlamaJni.sampleNext(session())

    override fun memoryTokens(): IntArray = LlamaJni.memoryTokens(session())

    override fun memoryStateBytes(checkpoints: IntArray): Long {
        val session = session()
        checkCheckpointCount(checkpoints)
        return LlamaJni.memoryStateBytes(session, checkpoints)
    }

    override fun exportMemory(checkpoints: IntArray, buffer: ByteBuffer, firstPieceOffset: Int, sink: MemorySink): Long {
        val session = session()
        checkCheckpointCount(checkpoints)
        checkPieceBuffer(buffer, firstPieceOffset)
        return LlamaJni.exportMemory(session, checkpoints, buffer, firstPieceOffset, sink)
    }

    override fun importMemory(buffer: ByteBuffer, firstPieceOffset: Int, source: MemorySource): List<MemoryCheckpoint> {
        val session = session()
        checkPieceBuffer(buffer, firstPieceOffset)
        val pairs = IntArray(2 * NativeLlama.MAX_MEMORY_CHECKPOINTS)
        val count = LlamaJni.importMemory(session, buffer, firstPieceOffset, source, pairs)
        return List(count) { MemoryCheckpoint(handle = pairs[2 * it], tokenCount = pairs[2 * it + 1]) }
    }

    override fun close() {
        val session = handle
        handle = 0
        if (session != 0L) LlamaJni.close(session)
    }

    /**
     * How the evaluation threads were set up by the latest evaluation, for diagnostics and
     * measurements: before the first evaluation, the unpinned thread count.
     */
    fun threads(): ThreadSetup {
        val setup = LlamaJni.threadSetup(session())
        return ThreadSetup(count = if (setup < 0) -setup else setup, pinned = setup < 0)
    }

    /** @property pinned true when each of the [count] threads runs on a CPU of its own. */
    data class ThreadSetup(val count: Int, val pinned: Boolean)

    internal companion object {

        /**
         * Refuses, before native code is reached, a buffer the native side must not be handed:
         * one that is not direct has no native memory to give it; a read-only one would be
         * written to all the same (the session fills it and overwrites it), behind the back of
         * the Java object that promises otherwise; one below
         * [NativeLlama.MEMORY_PIECE_BYTES_MIN] bytes, and an offset outside 0 until its
         * capacity, the session would refuse anyway. The native side checks the last two again.
         *
         * @throws NativeLlamaException with [NativeLlamaException.INVALID_ARGUMENT].
         */
        fun checkPieceBuffer(buffer: ByteBuffer, firstPieceOffset: Int) {
            val problem = when {
                !buffer.isDirect -> "the memory is moved through a direct buffer only"
                buffer.isReadOnly -> "the buffer of a memory image is written to, and this one is read-only"
                buffer.capacity() < NativeLlama.MEMORY_PIECE_BYTES_MIN -> "a buffer of ${buffer.capacity()} bytes is below the smallest piece"
                firstPieceOffset < 0 || firstPieceOffset >= buffer.capacity() -> "an offset of $firstPieceOffset is outside a buffer of ${buffer.capacity()} bytes"
                else -> null
            }
            if (problem != null) throw NativeLlamaException(NativeLlamaException.INVALID_ARGUMENT, problem)
        }

        /**
         * Refuses more checkpoints than a session holds before the array is handed to native
         * code, which refuses them too.
         *
         * @throws NativeLlamaException with [NativeLlamaException.INVALID_ARGUMENT].
         */
        fun checkCheckpointCount(checkpoints: IntArray) {
            if (checkpoints.size > NativeLlama.MAX_MEMORY_CHECKPOINTS) {
                throw NativeLlamaException(NativeLlamaException.INVALID_ARGUMENT, "${checkpoints.size} checkpoints are more than a session holds")
            }
        }
    }
}
