package us.theworkmans.proseplow.core.chat

import kotlinx.serialization.Serializable
import us.theworkmans.proseplow.core.engine.EngineError
import us.theworkmans.proseplow.core.engine.FinishReason
import us.theworkmans.proseplow.core.engine.GenerationEvent
import us.theworkmans.proseplow.core.prompt.PromptWhitespace

/**
 * One chat. Immutable: every operation returns a new instance and leaves the messages it did not
 * change as the very same objects, so a list on screen can skip what stayed the same.
 *
 * A chat is a single line of messages. Editing an earlier message or regenerating an answer cuts
 * the line at that point; nothing is kept as an alternative branch.
 *
 * The operations do not check who may call them when. The one rule they enforce themselves is
 * that the conversation is not rewritten while a reply is being generated: the operations that
 * cut or reopen the line ([forRetry], [editAndTruncate], [regenerateFrom], [forContinuation],
 * [discardFailedTurn]) return null or change nothing while [isGenerating].
 *
 * @property id unique among sessions; also names the session's file.
 * @property title shown in the list of chats. It follows the first user message until the user
 *   gives the chat a title of their own (see [withTitle]); empty for a chat without messages.
 * @property updatedAtMillis when the conversation last changed. Typing a draft or switching the
 *   model does not count: the list of chats is ordered by this and must not reshuffle for those.
 * @property modelId the model the next reply will come from. Earlier replies keep the model that
 *   wrote them ([ChatMessage.modelId]); the whole history is rendered afresh in the current
 *   model's format for every request.
 * @property draft text the user has typed but not sent.
 * @property error the failure of the last request, if it failed.
 */
@Serializable
data class ChatSession(
    val id: String,
    val title: String = "",
    val createdAtMillis: Long,
    val updatedAtMillis: Long = createdAtMillis,
    val modelId: String,
    val draft: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val error: ChatError? = null,
) {
    /** True while a reply is being generated. */
    val isGenerating: Boolean get() = messages.any { it.status == MessageStatus.STREAMING }

    /** The reply that is being generated, or null. */
    val streamingMessageId: String? get() = messages.lastOrNull { it.status == MessageStatus.STREAMING }?.id

    // --- Things that are not the conversation ---

    fun withDraft(text: String): ChatSession = copy(draft = text)

    /**
     * Gives the chat a title chosen by the user. A blank title hands the title back to the first
     * user message.
     */
    fun withTitle(title: String): ChatSession = copy(title = title.trim().ifEmpty { derivedTitle() })

    /**
     * Makes [modelId] the model of the replies to come. Allowed at any time: a reply in flight
     * keeps the model it was started with, because that is stamped on its message.
     */
    fun withModel(modelId: String): ChatSession = copy(modelId = modelId)

    // --- A turn ---

    /** Adds what the user sent. Clears the draft and any error left from the turn before. */
    fun withUserMessage(text: String, clock: Clock, ids: IdSource = RandomIds): ChatSession {
        val now = clock.nowMillis()
        val message = ChatMessage(id = ids.newId(), role = Role.USER, text = text, createdAtMillis = now)
        return copy(messages = messages + message, error = null, draft = "", updatedAtMillis = now).retitled(from = this)
    }

    /**
     * Adds the empty reply that generation events will fill, stamped with the current model.
     * Its id is [streamingMessageId] of the result.
     */
    fun withAssistantPlaceholder(clock: Clock, ids: IdSource = RandomIds): ChatSession {
        val now = clock.nowMillis()
        val message = ChatMessage(
            id = ids.newId(), role = Role.ASSISTANT, status = MessageStatus.STREAMING, modelId = modelId, createdAtMillis = now,
        )
        return copy(messages = messages + message, updatedAtMillis = now)
    }

    /** [withUserMessage] followed by [withAssistantPlaceholder]: the start of an ordinary turn. */
    fun withUserTurn(text: String, clock: Clock, ids: IdSource = RandomIds): ChatSession =
        withUserMessage(text, clock, ids).withAssistantPlaceholder(clock, ids)

    /**
     * Applies one event of the generation that fills the reply [assistantId]: text is appended,
     * the end of the generation finishes the message ([finished]) and an error fails it
     * ([failed]). Progress events change nothing here. Events for a reply that is no longer
     * streaming (because it was stopped meanwhile) are ignored.
     */
    fun applyEvent(assistantId: String, event: GenerationEvent, clock: Clock): ChatSession = when (event) {
        is GenerationEvent.TextDelta -> updateMessage(assistantId) {
            if (it.status == MessageStatus.STREAMING) it.copy(text = it.text + event.text) else it
        }
        is GenerationEvent.Done -> finished(assistantId, event.finishReason, clock)
        is GenerationEvent.Error -> failed(assistantId, event.error, clock)
        is GenerationEvent.PromptProgress, is GenerationEvent.HistoryTrimmed -> this
    }

    /**
     * Ends the streaming reply [assistantId] with [reason].
     * - The model ended its turn, or a stop string did: the reply is complete, and whitespace at
     *   its end is removed (the models' own templates would strip it from the history anyway).
     * - The token limit was reached: complete as far as it goes, text kept exactly as written so
     *   that it can be continued from the same point.
     * - Cancelled: stopped, with the partial text kept exactly as written.
     */
    fun finished(assistantId: String, reason: FinishReason, clock: Clock): ChatSession = updateStreaming(assistantId, clock) {
        when (reason) {
            FinishReason.END_OF_TURN, FinishReason.STOP_STRING ->
                it.copy(text = PromptWhitespace.stripEnd(it.text), status = MessageStatus.COMPLETE, finishReason = reason)
            FinishReason.LENGTH -> it.copy(status = MessageStatus.COMPLETE, finishReason = reason)
            FinishReason.CANCELLED -> it.copy(status = MessageStatus.STOPPED, finishReason = reason)
        }
    }

    /** The user pressed Stop: the partial reply is kept and marked as stopped. */
    fun stopped(assistantId: String, clock: Clock): ChatSession = finished(assistantId, FinishReason.CANCELLED, clock)

    /**
     * The request for the reply [assistantId] failed. A reply that had received some text is
     * kept as a stopped message; an empty placeholder is removed. The error is recorded against
     * the user message of that turn, so that Retry knows what to send again.
     */
    fun failed(assistantId: String?, error: EngineError, clock: Clock): ChatSession {
        val index = messages.indexOfFirst { it.id == assistantId }
        val before = if (index >= 0) messages.subList(0, index) else messages
        val userMessageId = before.lastOrNull { it.role == Role.USER }?.id
        val kept = messages.mapNotNull { message ->
            when {
                message.id != assistantId -> message
                PromptWhitespace.isBlank(message.text) -> null
                else -> message.copy(status = MessageStatus.STOPPED, finishReason = null)
            }
        }
        return copy(messages = kept, error = error.toChatError(userMessageId), updatedAtMillis = clock.nowMillis())
    }

    // --- Changing the past ---

    /**
     * The user message Retry would send again: the turn the error belongs to; otherwise the last
     * turn if it has no usable answer, which is a user message without a reply (the process died
     * before one started) or a reply that was stopped or came out empty. Null when there is
     * nothing to retry, and while a reply is being generated. A finished answer is not retried
     * but regenerated ([regenerateFrom]).
     */
    fun retryTargetId(): String? {
        if (isGenerating) return null
        error?.userMessageId?.let { failed ->
            if (messages.any { it.id == failed && it.role == Role.USER }) return failed
        }
        val last = messages.lastOrNull() ?: return null
        return when {
            last.role == Role.USER -> last.id
            last.status == MessageStatus.STOPPED || PromptWhitespace.isBlank(last.text) ->
                messages.getOrNull(messages.size - 2)?.takeIf { it.role == Role.USER }?.id
            else -> null
        }
    }

    /** For Retry: drops the error and everything after the turn to retry. Null if nothing to retry. */
    fun forRetry(clock: Clock): ChatSession? {
        val target = retryTargetId() ?: return null
        return truncatedAfter(messages.indexOfFirst { it.id == target }, clock)
    }

    /** For Discard: removes the failed user message and everything after it, with the error. */
    fun discardFailedTurn(clock: Clock): ChatSession {
        if (isGenerating) return this
        val failed = error?.userMessageId ?: return copy(error = null)
        val index = messages.indexOfFirst { it.id == failed }
        if (index < 0) return copy(error = null)
        return copy(messages = messages.subList(0, index).toList(), error = null, updatedAtMillis = clock.nowMillis())
            .retitled(from = this)
    }

    /**
     * Replaces the text of the user message [messageId] and removes everything after it, ready
     * for a new reply. The message keeps its id and its place. Null when there is no such user
     * message or a reply is being generated.
     */
    fun editAndTruncate(messageId: String, newText: String, clock: Clock): ChatSession? {
        if (isGenerating) return null
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0 || messages[index].role != Role.USER) return null
        val edited = messages[index].copy(text = newText)
        return copy(messages = messages.subList(0, index) + edited, error = null, updatedAtMillis = clock.nowMillis())
            .retitled(from = this)
    }

    /**
     * Removes the answer [assistantMessageId] and everything after it, ready for a new reply to
     * the same question. Null when there is no such assistant message, when no user message
     * precedes it, or when a reply is being generated.
     */
    fun regenerateFrom(assistantMessageId: String, clock: Clock): ChatSession? {
        if (isGenerating) return null
        val index = messages.indexOfFirst { it.id == assistantMessageId }
        if (index < 0 || messages[index].role != Role.ASSISTANT) return null
        if (messages.subList(0, index).none { it.role == Role.USER }) return null
        return truncatedAfter(index - 1, clock)
    }

    /**
     * Reopens the last answer so that the model carries on writing it: the message
     * [assistantMessageId] becomes the streaming reply again, with its text kept, and further
     * events append to it.
     *
     * Null unless it is the last message, an assistant message with some text, written by the
     * session's current model, and nothing is being generated. The model check keeps the record
     * honest: a message names one model, so another model may regenerate it but not continue it.
     */
    fun forContinuation(assistantMessageId: String, clock: Clock): ChatSession? {
        if (isGenerating) return null
        val last = messages.lastOrNull() ?: return null
        if (last.id != assistantMessageId || last.role != Role.ASSISTANT) return null
        if (PromptWhitespace.isBlank(last.text) || last.modelId != modelId) return null
        val reopened = last.copy(status = MessageStatus.STREAMING, finishReason = null)
        return copy(messages = messages.dropLast(1) + reopened, error = null, updatedAtMillis = clock.nowMillis())
    }

    // --- Loading and listing ---

    /**
     * A session as it must look after being loaded from disk: a reply that was still streaming
     * when the process died can never finish, so it becomes a stopped one. Its text is kept.
     */
    fun restored(): ChatSession = copy(
        messages = messages.map {
            if (it.status == MessageStatus.STREAMING) it.copy(status = MessageStatus.STOPPED) else it
        },
    )

    /** What the list of chats shows of this session. */
    fun summary(): SessionSummary = SessionSummary(
        id = id,
        title = title,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
        modelId = modelId,
        messageCount = messages.size,
    )

    /** The title the first user message gives ([deriveTitle]); empty without one. */
    fun derivedTitle(): String = deriveTitle(messages.firstOrNull { it.role == Role.USER }?.text.orEmpty())

    /**
     * Keeps the title in step with the first user message for as long as the title is still the
     * derived one. A title the user chose differs from it and is left alone.
     */
    private fun retitled(from: ChatSession): ChatSession =
        if (from.title.isEmpty() || from.title == from.derivedTitle()) copy(title = derivedTitle()) else this

    private fun truncatedAfter(index: Int, clock: Clock): ChatSession? {
        if (index < 0) return null
        return copy(messages = messages.subList(0, index + 1).toList(), error = null, updatedAtMillis = clock.nowMillis())
    }

    private inline fun updateStreaming(id: String, clock: Clock, transform: (ChatMessage) -> ChatMessage): ChatSession {
        val index = messages.indexOfFirst { it.id == id }
        if (index < 0 || messages[index].status != MessageStatus.STREAMING) return this
        val updated = messages.toMutableList()
        updated[index] = transform(updated[index])
        return copy(messages = updated, updatedAtMillis = clock.nowMillis())
    }

    private inline fun updateMessage(id: String, transform: (ChatMessage) -> ChatMessage): ChatSession {
        val index = messages.indexOfFirst { it.id == id }
        if (index < 0) return this
        val changed = transform(messages[index])
        if (changed === messages[index]) return this
        val updated = messages.toMutableList()
        updated[index] = changed
        return copy(messages = updated)
    }

    companion object {
        /** The longest derived title, in characters. Long enough to recognise a chat by, short enough for one line. */
        const val MAX_TITLE_LENGTH: Int = 48

        /** A new, empty chat that will use [modelId]. */
        fun create(modelId: String, clock: Clock, ids: IdSource = RandomIds): ChatSession =
            ChatSession(id = ids.newId(), createdAtMillis = clock.nowMillis(), modelId = modelId)

        /**
         * A title for a chat that starts with [text]: its first line that has any content, with
         * runs of whitespace collapsed, cut at a word boundary and marked with an ellipsis when it
         * is longer than [MAX_TITLE_LENGTH]. Empty for blank text; the app then shows its own
         * placeholder.
         */
        fun deriveTitle(text: String): String {
            val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
            val collapsed = line.split(WHITESPACE_RUN).joinToString(" ")
            if (collapsed.length <= MAX_TITLE_LENGTH) return collapsed
            var cut = MAX_TITLE_LENGTH - 1
            val space = collapsed.lastIndexOf(' ', cut)
            // Prefer the end of a word, unless that would throw away more than half of the room.
            if (space >= MAX_TITLE_LENGTH / 2) cut = space
            // Never cut between the two halves of a character outside the basic plane.
            if (cut > 0 && collapsed[cut - 1].isHighSurrogate()) cut--
            return collapsed.substring(0, cut).trimEnd() + ELLIPSIS
        }

        /** Runs of whitespace, including the Unicode spaces that `\s` alone does not cover. */
        private val WHITESPACE_RUN = Regex("[\\s\\p{Z}]+")
        private const val ELLIPSIS = "\u2026"
    }
}
