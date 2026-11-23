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
 *   Deletion tokens and the tokens they took back are counted: the model wrote them.
 * @property promptMillis time spent bringing the prompt into memory, tokenising included.
 * @property generationMillis time from the first sampled token to the end of the reply.
 * @property droppedMessages how many of the oldest messages were left out because the
 *   conversation no longer fitted the context; 0 when all of it was sent.
 * @property deletions how many times the model emitted its deletion token, including the times
 *   there was nothing left for it to take back; 0 for a model without one.
 */
data class GenerationStats(
    val promptTokens: Int,
    val reusedPromptTokens: Int,
    val generatedTokens: Int,
    val promptMillis: Long,
    val generationMillis: Long,
    val droppedMessages: Int = 0,
    val deletions: Int = 0,
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
     * before any event but those about the size of the context.
     */
    data class HistoryTrimmed(val droppedMessages: Int, val keptMessages: Int) : GenerationEvent

    /**
     * The conversation outgrew the context, and the context was made larger: from [fromTokens]
     * to [toTokens]. When [rereading] is true the model's memory could not be carried over and
     * the whole conversation is read again, which the [PromptProgress] events that follow
     * report; otherwise the answer goes on at once. Emitted before any text.
     */
    data class ContextGrown(val fromTokens: Int, val toTokens: Int, val rereading: Boolean) : GenerationEvent

    /**
     * The context could not be made as large as the conversation needs, so from here on its
     * limit is [limitTokens] and the oldest messages are left out beyond it ([HistoryTrimmed]
     * says how many). Emitted once for each limit that is found, not for every answer after it.
     */
    data class ContextLimited(val limitTokens: Int, val reason: Reason) : GenerationEvent {
        enum class Reason {
            /** The device has too little memory available for a larger context now. */
            MEMORY,

            /** The larger context could not be created, although the memory seemed to be there. */
            FAILED,
        }
    }

    /**
     * The prompt is being brought into the model's memory: [evaluatedTokens] of [totalTokens]
     * are there. The first event of a generation already counts the tokens that were reused from
     * memory, so it may start well above zero; the last has both numbers equal.
     */
    data class PromptProgress(val evaluatedTokens: Int, val totalTokens: Int) : GenerationEvent

    /**
     * The next piece of the reply. Always whole characters, never part of a stop string, and
     * never empty. The pieces of one generation joined together are [Done.text], unless a
     * [TextReplaced] came in between: the pieces then count from the text it carried.
     */
    data class TextDelta(val text: String) : GenerationEvent

    /**
     * The model took back text that had already been emitted. [text] is everything this
     * generation has written that still stands: it replaces all earlier [TextDelta] pieces of
     * the generation, and later pieces are appended to it. It is always the beginning of what
     * had been emitted, shorter by its last [removed] characters, and may be empty.
     *
     * Like every text event it covers this generation only. For a continued message it does not
     * include the part that was there before, which a deletion can never reach. A consumer that
     * holds the whole message rather than this generation's text therefore applies the event by
     * taking [removed] characters off the end of what it has.
     *
     * Only a model with a deletion token ([us.theworkmans.proseplow.core.model.ModelSpec.deletionTokenId])
     * causes this event, and only when a deletion changed what the reader had been shown.
     *
     * @property removed how many characters (as `String.length` counts them) were taken back;
     *   always positive.
     */
    data class TextReplaced(val text: String, val removed: Int) : GenerationEvent

    /**
     * The reply is complete.
     *
     * @property finishReason why it ended.
     * @property stopString the stop string that ended it when [finishReason] is
     *   [FinishReason.STOP_STRING], otherwise null. The stop string itself is not part of the text.
     * @property text everything this generation wrote that a reader is to see: the text events
     *   put together, with what the model took back left out. For a continued message that is
     *   only the continuation, not the part that was already there. When a stop string ended the
     *   reply, this is exactly the text in front of it.
     * @property exactText true when the model continues text rather than answering in turns.
     *   The reply must then be stored character for character, because whatever is written next
     *   is appended to it. For a chat model it is false, and whoever stores the reply may drop
     *   the whitespace at its end, as the model's own template would when it renders the history.
     */
    data class Done(
        val finishReason: FinishReason,
        val stopString: String? = null,
        val text: String = "",
        val stats: GenerationStats,
        val exactText: Boolean = false,
    ) : GenerationEvent

    /** The generation failed. Text that was emitted before stays valid as a partial reply. */
    data class Error(val error: EngineError) : GenerationEvent
}
