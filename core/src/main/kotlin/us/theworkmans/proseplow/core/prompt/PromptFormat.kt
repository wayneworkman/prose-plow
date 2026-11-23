package us.theworkmans.proseplow.core.prompt

/**
 * Who wrote a message of the conversation given to a [PromptFormat].
 *
 * The system prompt is not a role here: there is at most one, it always comes first, and it
 * arrives as its own field of [PromptInput]. A role for tool results will be added to this enum
 * when tools exist; every format decides the rendering in a `when` over the role, so the compiler
 * then points at the one place in each format that has to learn it.
 */
enum class PromptRole { USER, ASSISTANT }

/** One message of the conversation, already reduced to what the model is to see of it. */
data class PromptMessage(val role: PromptRole, val content: String)

/**
 * Everything a [PromptFormat] renders.
 *
 * @property messages the conversation, oldest first, with at least one message.
 * @property systemPrompt the model's system prompt from the settings, or null for none. A
 *   non-null value is rendered even when it is empty, exactly as the model's template would; the
 *   caller decides whether a blank prompt is sent at all.
 * @property extraSystemText further text for the system message, or null. It exists for the
 *   instructions that describe tools to the model; nothing produces it yet. See [systemText].
 * @property continueFinalMessage when true, the last message must be an assistant message and it
 *   is rendered as an open turn: no end marker after it and no new assistant turn. The model then
 *   continues that message from its last character. When false, the conversation is followed by
 *   the opening of a new assistant turn.
 */
data class PromptInput(
    val messages: List<PromptMessage>,
    val systemPrompt: String? = null,
    val extraSystemText: String? = null,
    val continueFinalMessage: Boolean = false,
) {
    /**
     * The content of the one system message: the system prompt, then a blank line, then the extra
     * system text; either part alone when the other is null; null when both are. Every format
     * renders the system message from this, so there is a single rule for where extra text goes.
     */
    fun systemText(): String? = when {
        systemPrompt == null -> extraSystemText
        extraSystemText == null -> systemPrompt
        else -> systemPrompt + "\n\n" + extraSystemText
    }
}

/** A conversation that a format cannot render, for example one without any message. */
class PromptFormatException(message: String) : IllegalArgumentException(message)

/**
 * Turns a conversation into the exact token-level layout one family of models was trained on.
 *
 * A format is pure: the same input always gives the same [Prompt], and it never looks at the
 * tokenizer. That is what lets the engine render a conversation again after every turn and reuse
 * whatever the model's memory already holds of it.
 */
interface PromptFormat {

    /**
     * Renders [input].
     *
     * @throws PromptFormatException when the conversation is empty, when
     *   [PromptInput.continueFinalMessage] is set and the last message is not an assistant
     *   message, or when the model's own template would reject the conversation.
     */
    fun render(input: PromptInput): Prompt
}

/** The checks every format shares; returns the messages for convenience. */
internal fun PromptInput.checkedMessages(): List<PromptMessage> {
    if (messages.isEmpty()) throw PromptFormatException("No messages provided.")
    if (continueFinalMessage && messages.last().role != PromptRole.ASSISTANT) {
        throw PromptFormatException("Only an assistant message can be continued.")
    }
    return messages
}

/** Collects segments and the boundary in front of each message while a format renders. */
internal class PromptBuilder {
    private val segments = ArrayList<PromptSegment>()
    private val boundaries = ArrayList<PromptBoundary>()
    private var replyContentStart: PromptBoundary? = null

    /** Marks the start of a message at the next segment to be added. */
    fun boundary() {
        boundaries += PromptBoundary(segments.size)
    }

    /**
     * Marks where the content of the open assistant message begins: [offset] characters into the
     * next segment to be added. See [Prompt.replyContentStart].
     */
    fun replyContentStart(offset: Int = 0) {
        replyContentStart = PromptBoundary(segments.size, offset)
    }

    fun text(text: String) {
        segments += PromptSegment.Text(text)
    }

    fun special(text: String) {
        segments += PromptSegment.Special(text)
    }

    fun token(id: Int) {
        segments += PromptSegment.TokenId(id)
    }

    fun build(): Prompt = Prompt(segments.toList(), boundaries.toList(), replyContentStart)
}
