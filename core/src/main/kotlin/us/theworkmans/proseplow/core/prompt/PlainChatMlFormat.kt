package us.theworkmans.proseplow.core.prompt

/**
 * ChatML written as ordinary text, for models whose vocabulary has no ChatML tokens at all:
 * Boris-2-Instruct and the models that share its template.
 *
 * This reproduces the Jinja template in the model's `tokenizer_config.json` character for
 * character:
 *
 * ```
 * <|im_start|>{role}\n{content, trimmed}<|im_end|>\n     for every message
 * <|im_start|>assistant\n                               to open the new assistant turn
 * ```
 *
 * Content is trimmed with Python's `strip()` ([PromptWhitespace]) and every `<|im_end|>` is
 * followed by a newline.
 *
 * The whole conversation is one [PromptSegment.Text] segment. The markers are plain characters
 * that the tokenizer spells with several ordinary tokens, and it may merge a marker's first
 * character with the text before it, so the string has to be tokenised in one call, as it was in
 * training. Two consequences:
 * - Message starts are [PromptBoundary] values with a character offset inside that one segment.
 * - The format cannot tell a marker typed by the user from its own. That is a property of how
 *   these models were trained, not something a renderer can repair; the only control token of
 *   this vocabulary, `<|endoftext|>`, is still kept out because the segment is plain text.
 *
 * Because `<|im_end|>` is text, a reply does not end with a token: the catalogue gives these
 * models `<|im_end|>` as a stop string.
 *
 * Continuing a message: the last message is rendered without `<|im_end|>` and no new turn is
 * opened. Its leading whitespace is trimmed as the template would; its end is kept exactly.
 */
object PlainChatMlFormat : PromptFormat {

    private const val IM_START = "<|im_start|>"

    /** The text that ends every message; without the newline it is the model's stop string. */
    const val IM_END: String = "<|im_end|>"

    override fun render(input: PromptInput): Prompt {
        val messages = input.checkedMessages()
        val text = StringBuilder()
        val boundaries = ArrayList<PromptBoundary>()

        input.systemText()?.let { system ->
            boundaries += PromptBoundary(segment = 0, offset = text.length)
            text.closedTurn("system", system)
        }
        messages.forEachIndexed { index, message ->
            val open = input.continueFinalMessage && index == messages.lastIndex
            boundaries += PromptBoundary(segment = 0, offset = text.length)
            // A tool result will be rendered here as a message with a role of its own.
            val role = when (message.role) {
                PromptRole.USER -> "user"
                PromptRole.ASSISTANT -> "assistant"
            }
            if (open) {
                text.append(IM_START).append(role).append('\n').append(PromptWhitespace.stripStart(message.content))
            } else {
                text.closedTurn(role, message.content)
            }
        }
        if (!input.continueFinalMessage) {
            boundaries += PromptBoundary(segment = 0, offset = text.length)
            text.append(IM_START).append("assistant\n")
        }
        return Prompt(listOf(PromptSegment.Text(text.toString())), boundaries)
    }

    private fun StringBuilder.closedTurn(role: String, content: String) {
        append(IM_START).append(role).append('\n').append(PromptWhitespace.strip(content)).append(IM_END).append('\n')
    }
}
