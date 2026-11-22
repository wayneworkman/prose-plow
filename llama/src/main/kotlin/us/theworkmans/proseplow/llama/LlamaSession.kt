package us.theworkmans.proseplow.llama

import us.theworkmans.proseplow.core.engine.EvaluationObserver
import us.theworkmans.proseplow.core.engine.NativeLlama
import us.theworkmans.proseplow.core.engine.SamplerConfig

/**
 * A loaded model: [NativeLlama] over one native session.
 *
 * Every method is a single native call; the contract of each is the one written on the
 * interface, and what the native side does to honour it is described in
 * `native/session/include/proseplow_session.h`. One behaviour differs from the letter of the
 * interface and is worth knowing here: a model with recurrent state (Qwen3.5, Boris-2) is only
 * asked whether to stop between chunks, never in the middle of one, because llama.cpp cannot
 * take a half-computed chunk out of such a model's state again.
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
        config.seed,
        config.bannedTokenIds.toIntArray(),
    )

    override fun sampleNext(): Int = LlamaJni.sampleNext(session())

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
}
