package us.theworkmans.proseplow.core.chat

import kotlinx.serialization.Serializable
import us.theworkmans.proseplow.core.engine.EngineError
import us.theworkmans.proseplow.core.engine.FinishReason

/**
 * Who wrote a message.
 *
 * The system prompt is not a message: there is one per model, it lives in the settings and is
 * added when a request is built. A role for tool results will join this enum when tools exist.
 * Stored sessions are read so that this is possible without a migration: a message whose role
 * this version does not know is left out when a session is loaded, and the session itself still
 * loads (see [SessionCodec]).
 */
@Serializable
enum class Role { USER, ASSISTANT }

@Serializable
enum class MessageStatus {
    /** The reply is being generated. Never kept as such when loaded from disk (see [ChatSession.restored]). */
    STREAMING,

    /** The message is finished. For a reply, [ChatMessage.finishReason] says how it ended. */
    COMPLETE,

    /** The reply ended early: the user pressed Stop, the generation failed, or the process died. */
    STOPPED,
}

/**
 * One message of a chat. Immutable.
 *
 * New fields are added with a default value, so sessions stored by an older version still load
 * and an older version ignores what it does not know; that is how structured tool calls and tool
 * results will be added.
 *
 * @property id unique within the app; stable for the life of the message, also across edits.
 * @property text what the user typed, or what the model has written so far.
 * @property finishReason for an assistant message, how its generation ended; null while it is
 *   streaming, for user messages, and when the generation failed or the process died.
 * @property modelId for an assistant message, the model that wrote it. Stamped when the reply is
 *   started and never changed afterwards, so a chat that switches models keeps a true record.
 *   Null for user messages.
 * @property createdAtMillis when the message was added to the chat.
 */
@Serializable
data class ChatMessage(
    val id: String,
    val role: Role,
    val text: String = "",
    val status: MessageStatus = MessageStatus.COMPLETE,
    val finishReason: FinishReason? = null,
    val modelId: String? = null,
    val createdAtMillis: Long = 0,
)

/**
 * The kinds of failure a chat can show. A coarser list than [EngineError] on purpose: it is
 * stored, so it has to stay small and stable, and the screen needs one message per kind.
 */
@Serializable
enum class ChatErrorKind {
    /** No model, or not the session's model, was loaded when the reply was requested. */
    MODEL_NOT_LOADED,

    /** The model could not be loaded. */
    MODEL_LOAD_FAILED,

    /** The conversation could not be turned into a prompt. */
    INVALID_REQUEST,

    /** The latest message alone is too long for this model's context. */
    PROMPT_TOO_LONG,

    /** The model's memory ran out in the middle of a reply. */
    CONTEXT_FULL,

    /** The native inference layer reported a failure. */
    NATIVE,

    /** Anything else, including a kind stored by a newer version that this one does not know. */
    UNEXPECTED,
}

/**
 * A failed request, shown after the turn it belongs to.
 *
 * @property detail technical detail for a "more" line; never the message shown to the user,
 *   which the app words itself from [kind].
 * @property userMessageId the user message whose reply failed, or null when the failure belongs
 *   to no turn.
 * @property promptTokens for [ChatErrorKind.PROMPT_TOO_LONG]: the size of the shortest possible
 *   prompt in tokens.
 * @property contextSize for [ChatErrorKind.PROMPT_TOO_LONG]: the model's context in tokens.
 */
@Serializable
data class ChatError(
    val kind: ChatErrorKind = ChatErrorKind.UNEXPECTED,
    val detail: String = "",
    val userMessageId: String? = null,
    val promptTokens: Int? = null,
    val contextSize: Int? = null,
)

/** The stored form of an engine failure in the turn of [userMessageId]. */
fun EngineError.toChatError(userMessageId: String?): ChatError = when (this) {
    EngineError.NotLoaded -> ChatError(ChatErrorKind.MODEL_NOT_LOADED, userMessageId = userMessageId)
    is EngineError.WrongModel ->
        ChatError(ChatErrorKind.MODEL_NOT_LOADED, "requested $requested, loaded $loaded", userMessageId)
    is EngineError.LoadFailed -> ChatError(ChatErrorKind.MODEL_LOAD_FAILED, detail, userMessageId)
    is EngineError.InvalidRequest -> ChatError(ChatErrorKind.INVALID_REQUEST, detail, userMessageId)
    is EngineError.PromptTooLong -> ChatError(
        ChatErrorKind.PROMPT_TOO_LONG, userMessageId = userMessageId, promptTokens = promptTokens, contextSize = contextSize,
    )
    is EngineError.ContextFull -> ChatError(ChatErrorKind.CONTEXT_FULL, detail, userMessageId)
    is EngineError.Native -> ChatError(ChatErrorKind.NATIVE, "code $code: $detail", userMessageId)
    is EngineError.Unexpected -> ChatError(ChatErrorKind.UNEXPECTED, detail, userMessageId)
}
