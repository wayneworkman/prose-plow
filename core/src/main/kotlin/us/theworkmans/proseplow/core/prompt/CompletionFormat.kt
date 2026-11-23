package us.theworkmans.proseplow.core.prompt

/**
 * The prompt of a model that only continues text: a base language model with no chat template,
 * no roles and no system prompt, such as Shard-2.
 *
 * There is nothing to lay out. The prompt is the text of the latest user message, exactly as it
 * was written, in one [PromptSegment.Text] segment: nothing in front of it, nothing after it and
 * nothing trimmed from it, because for a model that continues text a space at the end of the
 * prompt decides what comes next. That is also what the model's reference code does: it
 * tokenises the prompt as it is, adds no token of its own, and prints the prompt followed
 * directly by what the model wrote.
 *
 * What follows from having no roles:
 * - Earlier turns are not part of the prompt. Each message is continued on its own; a model
 *   that was never shown a conversation cannot be given one.
 * - A system prompt and extra system text are ignored, not rejected: there is no place for them.
 * - Text that spells a special token of the vocabulary is plain characters here, like all text
 *   of a message (the reference tokenizer would turn it into that token).
 *
 * Continuing a message: the prompt is the user's text immediately followed by the partial
 * answer, with nothing between them and nothing trimmed from either, so the model carries on
 * from the last character of what it wrote.
 */
object CompletionFormat : PromptFormat {

    override fun render(input: PromptInput): Prompt {
        val messages = input.checkedMessages()
        // A tool result has no place in this format: a text continuer has no roles to put it in.
        val start = messages.lastOrNull { it.role == PromptRole.USER }?.content
            ?: throw PromptFormatException("No user message to continue.")
        val text = if (input.continueFinalMessage) start + messages.last().content else start
        // The message begins at the start of the segment and the model's own text right after it.
        return Prompt(
            segments = listOf(PromptSegment.Text(text)),
            boundaries = listOf(PromptBoundary(segment = 0), PromptBoundary(segment = 0, offset = start.length)),
        )
    }
}
