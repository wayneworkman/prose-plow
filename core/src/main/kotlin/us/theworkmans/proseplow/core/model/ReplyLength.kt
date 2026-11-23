package us.theworkmans.proseplow.core.model

/**
 * How long a reply may be allowed to get: the limits of the "longest reply" a user can set for a
 * model, in tokens. They are not chosen for the settings screen; they are the two numbers the
 * engine itself goes by when it fits a conversation into the model's context, defined here once
 * and used by both.
 *
 * - **At least [MIN_TOKENS].** The engine does not begin a reply for which less room than this is
 *   left once everything but the latest turn has been left out; a limit below it would ask for
 *   replies the engine considers no reply at all.
 * - **At most half of the context** ([maxTokens]). The room for a reply is taken from the room
 *   for the conversation. While the engine can still leave older turns out, it keeps the reply's
 *   limit free, but never more than half of the context: the other half is the conversation's. A
 *   limit above half would therefore not be kept free, and a reply could run into the end of the
 *   context before it reached its limit. At half, a question and an answer of full length fit
 *   into the context together.
 */
object ReplyLength {
    /** The shortest "longest reply". */
    const val MIN_TOKENS: Int = 16

    /** The longest reply a model with a context of [contextTokens] can be set to. */
    fun maxTokens(contextTokens: Int): Int = contextTokens / 2
}

/** The limits a user may choose from for this model's longest reply, in tokens; see [ReplyLength]. */
val ModelSpec.replyLengthRange: IntRange get() = ReplyLength.MIN_TOKENS..ReplyLength.maxTokens(contextLength)

/** True when [tokens] can be this model's longest reply. */
fun ModelSpec.acceptsReplyLength(tokens: Int): Boolean = tokens in replyLengthRange
