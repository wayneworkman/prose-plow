package us.theworkmans.proseplow.llama

import us.theworkmans.proseplow.core.engine.EvaluationObserver
import us.theworkmans.proseplow.core.engine.LoadProgress

/**
 * The native methods, one for each function of the session's C interface
 * (`native/session/include/proseplow_session.h`). [LlamaSession] and [NativeRuntime] are the only
 * callers; nothing else in the app touches native code.
 *
 * The methods are registered by the library's `JNI_OnLoad` from a table of names and signatures
 * in `src/main/cpp/proseplow_jni.cpp`. A declaration here and its table entry must change
 * together: when they disagree, loading the library fails, which is better than a crash at the
 * first call. `consumer-rules.pro` keeps the names from being changed by a minifier.
 *
 * Conventions shared by all of them:
 * - `handle` is the native session returned by [openFd]; 0 is never a session.
 * - Text and tokens cross as primitive arrays that the native side copies. Text is real UTF-8 in
 *   a byte array, never a string (see `NativeLlama.tokenize` for the reason).
 * - A failure is thrown as `NativeLlamaException` with the native status as its code.
 * - Nothing is called from another thread: the callbacks [LoadProgress] and
 *   [EvaluationObserver] run on the calling thread before the call returns.
 */
internal object LlamaJni {

    /**
     * `pp_runtime_init`: the status, 0 for success. [backendLibraries] holds the file names of
     * the CPU backend variants in UTF-8, one per line; the best one for this processor is loaded.
     */
    @JvmStatic
    external fun runtimeInit(backendLibraries: ByteArray): Int

    /** `pp_runtime_description`, in UTF-8 (in practice ASCII). */
    @JvmStatic
    external fun runtimeDescription(): ByteArray

    /**
     * `pp_session_open_fd`: loads the model that occupies [length] bytes at [offset] of the open
     * file [fd] and creates its context. The native side duplicates the descriptor, so the
     * caller closes its own as soon as this returns.
     *
     * @param pinnedCpus the CPUs to run one pinned thread each; empty to never pin.
     * @param threads the number of threads when they are not pinned.
     * @param map true to map the model's bytes from the file, false to read them into memory.
     */
    @JvmStatic
    external fun openFd(
        fd: Int,
        offset: Long,
        length: Long,
        contextTokens: Int,
        batchTokens: Int,
        threads: Int,
        pinnedCpus: IntArray,
        map: Boolean,
        progress: LoadProgress?,
    ): Long

    /** `pp_session_close`. The handle must not be used afterwards. */
    @JvmStatic
    external fun close(handle: Long)

    @JvmStatic
    external fun tokenize(handle: Long, utf8: ByteArray, parseSpecial: Boolean): IntArray

    @JvmStatic
    external fun tokenToBytes(handle: Long, token: Int): ByteArray

    @JvmStatic
    external fun isEndOfGeneration(handle: Long, token: Int): Boolean

    @JvmStatic
    external fun contextSize(handle: Long): Int

    @JvmStatic
    external fun memoryTokenCount(handle: Long): Int

    @JvmStatic
    external fun evaluate(handle: Long, tokens: IntArray, chunkSize: Int, observer: EvaluationObserver): Int

    @JvmStatic
    external fun rewindTo(handle: Long, tokenCount: Int): Boolean

    @JvmStatic
    external fun saveCheckpoint(handle: Long): Int

    @JvmStatic
    external fun restoreCheckpoint(handle: Long, checkpoint: Int): Boolean

    @JvmStatic
    external fun dropCheckpoint(handle: Long, checkpoint: Int)

    @JvmStatic
    external fun clearMemory(handle: Long)

    /** `pp_session_configure_sampler`; the arguments are the fields of `SamplerConfig` in order. */
    @JvmStatic
    external fun configureSampler(
        handle: Long,
        temperature: Float,
        topK: Int,
        topP: Float,
        minP: Float,
        repetitionPenalty: Float,
        presencePenalty: Float,
        frequencyPenalty: Float,
        penaltyWindow: Int,
        seed: Int,
        bannedTokenIds: IntArray,
    )

    @JvmStatic
    external fun sampleNext(handle: Long): Int

    /**
     * `pp_session_thread_setup`: the number of evaluation threads as of the latest evaluation,
     * negative when each of them is pinned to a CPU of its own.
     */
    @JvmStatic
    external fun threadSetup(handle: Long): Int
}
