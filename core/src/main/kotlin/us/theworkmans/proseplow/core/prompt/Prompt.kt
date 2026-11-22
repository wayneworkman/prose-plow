package us.theworkmans.proseplow.core.prompt

/**
 * One piece of a rendered prompt. Every segment is tokenised by its own call to the native
 * tokenizer and the results are concatenated in order, so a segment boundary is also a boundary
 * between tokens. Formats rely on that: it is how a model's training-time encoding is reproduced
 * exactly, and how text typed by the user is kept apart from the markers that structure the turns.
 */
sealed interface PromptSegment {

    /**
     * Ordinary text, tokenised with special-token parsing off. Message content always travels in
     * a segment of this kind, so text that merely looks like a control token (`<|im_end|>` typed
     * by the user, or quoted by the model) is encoded as the characters it is and can never close
     * a turn or open one.
     */
    data class Text(val text: String) : PromptSegment

    /**
     * Text written by the format itself, tokenised with special-token parsing on, so the control
     * tokens spelled in it become the single tokens the model was trained on. Never holds message
     * content.
     */
    data class Special(val text: String) : PromptSegment

    /** One token given by its id and passed to the model as is, without any tokenisation. */
    data class TokenId(val id: Int) : PromptSegment
}

/**
 * A place in a [Prompt] where a message (or the turn the model is about to write) begins: the
 * character [offset] inside the segment at index [segment]. The engine saves its memory
 * checkpoints at these places, because a conversation that is edited, regenerated or continued
 * always stays unchanged up to one of them.
 *
 * An offset of 0 is the start of the segment and always falls between two tokens. A larger
 * offset is only meaningful in a text segment and only usable when the tokenizer happens to put a
 * token boundary there; the engine checks that and skips the boundary otherwise.
 */
data class PromptBoundary(val segment: Int, val offset: Int = 0)

/**
 * A conversation rendered for one model: the [segments] in order, and where each message begins.
 *
 * [boundaries] are in ascending order, one per rendered message (including the system message)
 * and one for the open assistant turn at the end.
 */
data class Prompt(
    val segments: List<PromptSegment>,
    val boundaries: List<PromptBoundary> = emptyList(),
) {
    /**
     * The text of all [PromptSegment.Text] and [PromptSegment.Special] segments joined together:
     * what the model's own chat template would have produced as one string. Injected token ids
     * have no text and are left out.
     */
    fun text(): String = buildString {
        for (segment in segments) {
            when (segment) {
                is PromptSegment.Text -> append(segment.text)
                is PromptSegment.Special -> append(segment.text)
                is PromptSegment.TokenId -> Unit
            }
        }
    }
}
