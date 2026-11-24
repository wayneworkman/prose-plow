package us.theworkmans.proseplow.core.prompt

/**
 * The prompt layout of peacebell-v1: ChatML whose two markers are injected as token ids.
 *
 * The model ships no chat template. Its training-time encoding is the method `build_chatml_ids`
 * of its own tokenizer class, and this format is that method as segments:
 *
 * ```
 * per message:        [3] + encode(role + "\n") + encode(content) + [4]
 * new assistant turn: [3] + encode("assistant\n")
 * ```
 *
 * Three things about it must not be "tidied":
 * - `<|im_start|>` and `<|im_end|>` are the ids 3 and 4, injected directly ([PromptSegment.TokenId]).
 *   Encoding their text instead would put a stray word-start piece in front of them.
 * - The role line and the content are two separate `encode` calls, hence two text segments. The
 *   tokenizer starts every call with a word-start piece, so one call over the joined text gives
 *   different tokens from the ones the model was trained on.
 * - Nothing follows id 4: no newline between messages.
 *
 * Content is passed as it is given; the method does not trim. An empty content is still its own
 * (empty) segment, mirroring the call that encodes nothing.
 *
 * Continuing a message: the last message is left without id 4 and no new turn is opened, so the
 * model continues the content. The answer begins at the start of the content's own segment
 * ([Prompt.replyContentStart]).
 *
 * The model was trained without a system role; the catalogue marks it as not supporting a system
 * prompt and the engine then never passes one. If one is given all the same, it is rendered as a
 * message with the role `system`, which is what `build_chatml_ids` does with any role.
 */
object PeacebellChatMlFormat : PromptFormat {

    /** `im_start_id` in the model's `config.json`. */
    const val IM_START_ID: Int = 3

    /** `im_end_id` in the model's `config.json`; also the token that ends a reply. */
    const val IM_END_ID: Int = 4

    override fun render(input: PromptInput): Prompt {
        val messages = input.checkedMessages()
        val builder = PromptBuilder()

        input.systemText()?.let { system ->
            builder.boundary()
            builder.turn("system", system, open = false)
        }
        messages.forEachIndexed { index, message ->
            val open = input.continueFinalMessage && index == messages.lastIndex
            builder.boundary()
            // A tool result will be rendered here as a message with a role of its own.
            val role = when (message.role) {
                PromptRole.USER -> "user"
                PromptRole.ASSISTANT -> "assistant"
            }
            builder.turn(role, message.content, open)
        }
        if (!input.continueFinalMessage) {
            builder.boundary()
            builder.token(IM_START_ID)
            builder.text("assistant\n")
        }
        return builder.build()
    }

    private fun PromptBuilder.turn(role: String, content: String, open: Boolean) {
        token(IM_START_ID)
        text(role + "\n")
        if (open) replyContentStart()
        if (role == "assistant") answer(offset = 0, length = content.length)
        text(content)
        if (!open) token(IM_END_ID)
    }
}
