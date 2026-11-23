package us.theworkmans.proseplow.core.engine

import us.theworkmans.proseplow.core.prompt.Prompt
import us.theworkmans.proseplow.core.prompt.PromptBoundary
import us.theworkmans.proseplow.core.prompt.PromptSegment

/**
 * A growable list of token ids: the engine's own copy of what the model's memory holds. The ids
 * spell out the conversation, so no array of them is ever let go of unwiped: not on [truncate]
 * or [wipe], and not when the list grows into a larger array.
 *
 * @param onDropped told of every array the list has outgrown, after it was wiped. For the tests.
 */
internal class TokenList(private val onDropped: (IntArray) -> Unit = {}) {
    private var items = IntArray(256)

    var size: Int = 0
        private set

    operator fun get(index: Int): Int = items[index]

    fun add(token: Int) {
        if (size == items.size) grow()
        items[size++] = token
    }

    /** Doubles the array; the old one is overwritten before it is dropped, since nothing else would. */
    private fun grow() {
        val old = items
        items = old.copyOf(old.size * 2)
        old.fill(0)
        onDropped(old)
    }

    fun addAll(tokens: IntArray, count: Int = tokens.size) {
        for (index in 0 until count) add(tokens[index])
    }

    /** Keeps the first [newSize] tokens and overwrites the rest. */
    fun truncate(newSize: Int) {
        items.fill(0, newSize, size)
        size = newSize
    }

    /** Empties the list and overwrites what it held: the ids spell out the conversation. */
    fun wipe() {
        items.fill(0)
        size = 0
    }

    /** How many leading tokens this list and [other] have in common. */
    fun commonPrefixWith(other: IntArray): Int {
        val limit = minOf(size, other.size)
        var index = 0
        while (index < limit && items[index] == other[index]) index++
        return index
    }
}

/**
 * A prompt as tokens.
 *
 * @property tokens all segments' tokens in order.
 * @property segmentStarts for each segment the index of its first token, plus one final entry
 *   equal to the total, so that segment `i` is `tokens[segmentStarts[i] until segmentStarts[i + 1]]`.
 */
internal class TokenisedPrompt(
    val prompt: Prompt,
    val tokens: IntArray,
    val segmentStarts: IntArray,
)

/**
 * Turns [Prompt]s into tokens with the native tokenizer: one call per segment, results joined.
 * Lives for one generation. It remembers what it has tokenised, because the context budget may
 * render the conversation several times and most segments are the same every time.
 */
internal class PromptTokeniser(private val native: NativeLlama) {

    private val cache = HashMap<PromptSegment, IntArray>()

    fun tokenise(prompt: Prompt): TokenisedPrompt {
        val starts = IntArray(prompt.segments.size + 1)
        val parts = ArrayList<IntArray>(prompt.segments.size)
        var total = 0
        prompt.segments.forEachIndexed { index, segment ->
            starts[index] = total
            val part = tokens(segment)
            parts += part
            total += part.size
        }
        starts[prompt.segments.size] = total
        val all = IntArray(total)
        parts.forEachIndexed { index, part -> part.copyInto(all, starts[index]) }
        return TokenisedPrompt(prompt, all, starts)
    }

    /**
     * Token positions of the prompt's last message boundaries: at most [limit] of them, in
     * ascending order, each strictly after [after] and strictly before the end of the prompt.
     *
     * A boundary inside a text segment is only usable when the tokens of the text in front of it
     * are the first tokens of the whole segment, that is, when the tokenizer really does put a
     * token boundary there. That is checked, not assumed; a boundary that fails the check is
     * skipped.
     */
    fun boundaryPositions(tokenised: TokenisedPrompt, after: Int, limit: Int): List<Int> {
        val found = ArrayList<Int>(limit)
        for (boundary in tokenised.prompt.boundaries.asReversed()) {
            if (found.size >= limit) break
            val position = positionOf(tokenised, boundary) ?: continue
            if (position <= after) break // Boundaries ascend: every earlier one is before this too.
            if (position >= tokenised.tokens.size || position == found.lastOrNull()) continue
            found += position
        }
        return found.asReversed()
    }

    /**
     * The token position at which the content of the open assistant message begins
     * ([Prompt.replyContentStart]), or null when the prompt has no such marker or the tokenizer
     * does not put a token boundary there. The check is the one [boundaryPositions] makes: a
     * position that is not really between two tokens would make the penalties count part of a
     * token that also spells the text in front of the answer, so it is not used at all.
     *
     * The position is an index into [TokenisedPrompt.tokens], which is the position in the
     * model's memory once that prompt has been evaluated.
     */
    fun replyContentPosition(tokenised: TokenisedPrompt): Int? {
        val marker = tokenised.prompt.replyContentStart ?: return null
        return positionOf(tokenised, marker)
    }

    private fun positionOf(tokenised: TokenisedPrompt, boundary: PromptBoundary): Int? {
        val segments = tokenised.prompt.segments
        if (boundary.segment !in segments.indices) return null
        val start = tokenised.segmentStarts[boundary.segment]
        if (boundary.offset == 0) return start
        val segment = segments[boundary.segment]
        val text = when (segment) {
            is PromptSegment.Text -> segment.text
            is PromptSegment.Special -> segment.text
            is PromptSegment.TokenId -> return null
        }
        if (boundary.offset !in 1 until text.length) return null
        val head = text.substring(0, boundary.offset)
        val headTokens = tokens(if (segment is PromptSegment.Text) PromptSegment.Text(head) else PromptSegment.Special(head))
        val end = tokenised.segmentStarts[boundary.segment + 1]
        if (start + headTokens.size > end) return null
        for (index in headTokens.indices) {
            if (tokenised.tokens[start + index] != headTokens[index]) return null
        }
        return start + headTokens.size
    }

    private fun tokens(segment: PromptSegment): IntArray = when (segment) {
        is PromptSegment.TokenId -> intArrayOf(segment.id)
        is PromptSegment.Text -> tokenise(segment, segment.text, parseSpecial = false)
        is PromptSegment.Special -> tokenise(segment, segment.text, parseSpecial = true)
    }

    private fun tokenise(segment: PromptSegment, text: String, parseSpecial: Boolean): IntArray {
        // Nothing encodes to nothing; the native call is spared.
        if (text.isEmpty()) return EMPTY
        return cache.getOrPut(segment) { native.tokenize(text.toByteArray(Charsets.UTF_8), parseSpecial) }
    }

    companion object {
        private val EMPTY = IntArray(0)

        /**
         * Whether every text of [prompt] is within what the native tokenizer takes
         * ([NativeLlama.MAX_TOKENIZE_BYTES] bytes of UTF-8 each). A prompt that is not would be
         * refused, whatever the size of the context; this is asked before anything is tokenised.
         */
        fun withinLimit(prompt: Prompt): Boolean = prompt.segments.all { segment ->
            when (segment) {
                is PromptSegment.TokenId -> true
                is PromptSegment.Text -> utf8Length(segment.text) <= NativeLlama.MAX_TOKENIZE_BYTES
                is PromptSegment.Special -> utf8Length(segment.text) <= NativeLlama.MAX_TOKENIZE_BYTES
            }
        }

        /**
         * The length of [text] as [tokenise] hands it over (`toByteArray(UTF_8)`), counted
         * without encoding it. That encoder writes an unpaired surrogate as one `?`.
         */
        internal fun utf8Length(text: String): Int {
            var bytes = 0
            var index = 0
            while (index < text.length) {
                val char = text[index]
                when {
                    char.code < 0x80 -> bytes += 1
                    char.code < 0x800 -> bytes += 2
                    Character.isHighSurrogate(char) && index + 1 < text.length && Character.isLowSurrogate(text[index + 1]) -> {
                        bytes += 4
                        index++
                    }
                    Character.isSurrogate(char) -> bytes += 1
                    else -> bytes += 3
                }
                index++
            }
            return bytes
        }
    }
}
