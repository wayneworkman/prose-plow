package us.theworkmans.proseplow.core.prompt

/**
 * The prompt layout of Qwen3.5: ChatML whose markers are real control tokens.
 *
 * This reproduces, character for character, what the model's own `chat_template.jinja` emits for
 * a text-only conversation without tools and with thinking off (the template's default, and the
 * only mode this app uses):
 *
 * ```
 * <|im_start|>system\n{system}<|im_end|>\n
 * <|im_start|>user\n{content}<|im_end|>\n
 * <|im_start|>assistant\n{answer}<|im_end|>\n
 * <|im_start|>assistant\n<think>\n\n</think>\n\n
 * ```
 *
 * The details that are easy to get wrong, all taken from the template:
 * - Every content is trimmed with Python's `strip()` ([PromptWhitespace]).
 * - An assistant message in the history is rendered as its answer only. If its text contains
 *   `</think>`, everything up to the last `</think>` is reasoning and is dropped, and the newlines
 *   that follow the tag are removed (only newlines: spaces stay).
 * - An assistant message that comes after the last user query is rendered differently: with a
 *   `<think>` block holding its reasoning (empty for this app) before the answer. The opening of
 *   a new assistant turn is that same layout with nothing after the empty block, which is how
 *   the template switches thinking off.
 * - A user message that is wrapped in `<tool_response>` tags is not a query; the template raises
 *   when no message is one, and so does this format.
 *
 * Segments. Markers go in [PromptSegment.Special] segments and everything between two markers is
 * one single [PromptSegment.Text] segment, never two. The reference tokenizer splits the rendered
 * string at its marker tokens and encodes each stretch between them on its own, so cutting at
 * exactly those places gives the same tokens, while a cut anywhere else could separate
 * characters the tokenizer would have merged (`\n\n` is one token, two `\n` are not).
 *
 * `<think>` and `</think>` are ordinary added tokens of this tokenizer, not control tokens. They
 * are matched in any text, as in the reference tokenizer, so a user can type them; what a text
 * segment keeps out is the control tokens `<|im_start|>`, `<|im_end|>` and `<|endoftext|>`.
 *
 * Continuing a message: the open turn is the after-the-last-query layout without its end marker.
 * Its leading whitespace is trimmed as the template would, and its end is kept exactly, because
 * the model continues from the last character it wrote. With an empty partial answer this is the
 * opening of a new turn, token for token.
 */
object Qwen35ChatMlFormat : PromptFormat {

    private const val IM_START = "<|im_start|>"
    private const val IM_END_LINE = "<|im_end|>\n"
    private const val THINK_OPEN = "<think>"
    private const val THINK_CLOSE = "</think>"
    private const val TOOL_RESPONSE_OPEN = "<tool_response>"
    private const val TOOL_RESPONSE_CLOSE = "</tool_response>"

    /** The opening of an assistant turn after the last query, up to and including `<think>`. */
    private const val ASSISTANT_THINK_OPEN = "$IM_START" + "assistant\n" + THINK_OPEN

    /** The opening of a new assistant turn with thinking off. */
    const val GENERATION_PROMPT: String = "$ASSISTANT_THINK_OPEN\n\n$THINK_CLOSE\n\n"

    override fun render(input: PromptInput): Prompt {
        val messages = input.checkedMessages()
        val lastQuery = lastQueryIndex(messages)
            ?: throw PromptFormatException("No user query found in messages.")
        val builder = PromptBuilder()

        input.systemText()?.let { system ->
            builder.boundary()
            builder.closedTurn("system", PromptWhitespace.strip(system))
        }
        messages.forEachIndexed { index, message ->
            val open = input.continueFinalMessage && index == messages.lastIndex
            builder.boundary()
            // A tool result will be rendered here as well: the template wraps it in
            // <tool_response> tags inside a user turn.
            when (message.role) {
                PromptRole.USER -> builder.closedTurn("user", PromptWhitespace.strip(message.content))
                PromptRole.ASSISTANT -> builder.assistantTurn(message.content, afterLastQuery = index > lastQuery, open = open)
            }
        }
        if (!input.continueFinalMessage) {
            builder.boundary()
            builder.special(GENERATION_PROMPT)
        }
        return builder.build()
    }

    /**
     * The index of the last user message that is a real query, as the template finds it by
     * walking backwards. Null when there is none.
     */
    private fun lastQueryIndex(messages: List<PromptMessage>): Int? {
        for (index in messages.indices.reversed()) {
            val message = messages[index]
            if (message.role != PromptRole.USER) continue
            val content = PromptWhitespace.strip(message.content)
            if (!(content.startsWith(TOOL_RESPONSE_OPEN) && content.endsWith(TOOL_RESPONSE_CLOSE))) return index
        }
        return null
    }

    private fun PromptBuilder.closedTurn(role: String, content: String) {
        special(IM_START)
        text(role + "\n" + content)
        special(IM_END_LINE)
    }

    private fun PromptBuilder.assistantTurn(rawContent: String, afterLastQuery: Boolean, open: Boolean) {
        // The template trims both ends; an open turn keeps its end, which the model continues.
        var content = if (open) PromptWhitespace.stripStart(rawContent) else PromptWhitespace.strip(rawContent)
        var reasoning = ""
        if (THINK_CLOSE in content) {
            reasoning = content.substringBefore(THINK_CLOSE).trimEnd('\n').substringAfterLast(THINK_OPEN).trimStart('\n')
            content = content.substringAfterLast(THINK_CLOSE).trimStart('\n')
        }
        if (afterLastQuery) {
            special(ASSISTANT_THINK_OPEN)
            text("\n" + PromptWhitespace.strip(reasoning) + "\n")
            special(THINK_CLOSE)
            text("\n\n" + content)
        } else {
            special(IM_START)
            text("assistant\n" + content)
        }
        if (!open) special(IM_END_LINE)
    }
}
