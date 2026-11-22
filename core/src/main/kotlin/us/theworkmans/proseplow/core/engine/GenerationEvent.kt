package us.theworkmans.proseplow.core.engine

import kotlinx.serialization.Serializable

/**
 * Why a reply ended. Stored with the assistant message it belongs to.
 *
 * More reasons will come (a reply that ends in a tool call, for one). Code that branches on this
 * should say what it does for each known value and let the compiler point at it when a value is
 * added; stored sessions with a value this version does not know still load, with the reason
 * read as absent.
 */
@Serializable
enum class FinishReason {
    /** The model ended its turn with its end-of-turn token. */
    END_OF_TURN,

    /** The reply reached a stop string; [GenerationEvent.Done.stopString] says which. */
    STOP_STRING,

    /** The reply reached the token limit of the request or the end of the context. */
    LENGTH,

    /**
     * The reply was cut short on purpose: by the user pressing Stop, or by the engine being told
     * to forget or unload while it was generating.
     */
    CANCELLED,
}

/**
 * Counts and timings of one generation.
 *
 * @property promptTokens the size of the rendered prompt in tokens.
 * @property reusedPromptTokens how many of those were already in the model's memory and were
 *   not evaluated again.
 * @property generatedTokens tokens sampled for the reply, not counting the token that ended it.
 * @property promptMillis time spent bringing the prompt into memory, tokenising included.
 * @property generationMillis time from the first sampled token to the end of the reply.
 * @property droppedMessages how many of the oldest messages were left out because the
 *   conversation no longer fitted the context; 0 when all of it was sent.
 */
data class GenerationStats(
    val promptTokens: Int,
    val reusedPromptTokens: Int,
    val generatedTokens: Int,
    val promptMillis: Long,
    val generationMillis: Long,
    val droppedMessages: Int = 0,
) {
    /** Prompt tokens that had to be evaluated for this generation. */
    val evaluatedPromptTokens: Int get() = promptTokens - reusedPromptTokens

    /** Prompt evaluation speed, or null when nothing was evaluated or no time was measured. */
    val promptTokensPerSecond: Double? get() = perSecond(evaluatedPromptTokens, promptMillis)

    /** Generation speed, or null when nothing was generated or no time was measured. */
    val generatedTokensPerSecond: Double? get() = perSecond(generatedTokens, generationMillis)

    private fun perSecond(tokens: Int, millis: Long): Double? =
        if (tokens > 0 && millis > 0) tokens * 1000.0 / millis else null
}

/**
 * What [InferenceEngine.generate] emits. A generation is a run of progress and text events
 * closed by exactly one [Done] or [Error]; nothing follows either.
 */
sealed interface GenerationEvent {

    /**
     * The conversation did not fit the model's context together with room for the reply, so the
     * oldest [droppedMessages] messages were left out of the prompt; [keptMessages] were sent.
     * The system prompt and the latest user message are always kept. Emitted at most once,
     * before any other event.
     */
    data class HistoryTrimmed(val droppedMessages: Int, val keptMessages: Int) : GenerationEvent

    /**
     * The prompt is being brought into the model's memory: [evaluatedTokens] of [totalTokens]
     * are there. The first event of a generation already counts the tokens that were reused from
     * memory, so it may start well above zero; the last has both numbers equal.
     */
    data class PromptProgress(val evaluatedTokens: Int, val totalTokens: Int) : GenerationEvent

    /**
     * The next piece of the reply. Always whole characters, never part of a stop string, and
     * never empty. The pieces of one generation joined together are [Done.text].
     */
    data class TextDelta(val text: String) : GenerationEvent

    /**
     * The reply is complete.
     *
     * @property finishReason why it ended.
     * @property stopString the stop string that ended it when [finishReason] is
     *   [FinishReason.STOP_STRING], otherwise null. The stop string itself is not part of the text.
     * @property text everything this generation wrote: all [TextDelta] pieces joined. For a
     *   continued message that is only the continuation, not the part that was already there.
     *   When a stop string ended the reply, this is exactly the text in front of it.
     */
    data class Done(
        val finishReason: FinishReason,
        val stopString: String? = null,
        val text: String = "",
        val stats: GenerationStats,
    ) : GenerationEvent

    /** The generation failed. Text that was emitted before stays valid as a partial reply. */
    data class Error(val error: EngineError) : GenerationEvent
}
