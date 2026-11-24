package us.theworkmans.proseplow.core.engine

import us.theworkmans.proseplow.core.prompt.Prompt
import us.theworkmans.proseplow.core.prompt.PromptBoundary
import us.theworkmans.proseplow.core.prompt.PromptSegment
import us.theworkmans.proseplow.core.prompt.PromptSpan

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

    /** A copy of the tokens; whoever takes it is responsible for overwriting it. */
    fun toIntArray(): IntArray = items.copyOf(size)
}

/**
 * The tokens a model wrote for one answer, kept so that a later prompt can spell that answer
 * with them instead of encoding its text again. A tokenizer of the BPE kind has more than one
 * encoding of most texts, and a model may sample any of them; the text encoded again is then not
 * what the memory holds, and a model that cannot cut its memory back reads the answer again from
 * a checkpoint before the difference.
 *
 * [ids] spell [content] from its first byte: token `i` ends at byte [ends]`[i]` of it, and its
 * bytes are exactly the bytes of [content] up to there. [content] is the UTF-8 of the answer's
 * text as the engine gave it out (the text a continued answer already had, followed by what was
 * written); it may go on beyond the last token, where the answer's text ends inside a token or
 * a token was sampled that is not part of the text (a stop string). All three arrays spell out
 * the conversation, so they are overwritten by [wipe] when the engine lets go of the record.
 */
internal class WrittenReply(val ids: IntArray, val ends: IntArray, val content: ByteArray) {
    init {
        require(ids.isNotEmpty() && ids.size == ends.size) { "A written reply has a token and an end for each" }
    }

    fun wipe() {
        ids.fill(0)
        ends.fill(0)
        content.fill(0)
    }
}

/**
 * A prompt as tokens.
 *
 * @property tokens all segments' tokens in order.
 * @property segmentStarts for each segment the index of its first token, plus one final entry
 *   equal to the total, so that segment `i` is `tokens[segmentStarts[i] until segmentStarts[i + 1]]`.
 * @property replies the written replies whose tokens spell answers of this prompt, in the order
 *   the tokeniser was given them; empty when every text was encoded.
 */
internal class TokenisedPrompt(
    val prompt: Prompt,
    val tokens: IntArray,
    val segmentStarts: IntArray,
    val replies: List<WrittenReply> = emptyList(),
)

/**
 * Turns [Prompt]s into tokens with the native tokenizer: one call per segment, results joined.
 * Lives for one generation. It remembers what it has tokenised, because the context budget may
 * render the conversation several times and most segments are the same every time.
 *
 * The token ids spell out the conversation, so this class keeps hold of every array of them it
 * makes or is given, the cached ones and the joined prompts it hands out alike, until [wipe]
 * overwrites them all. Whoever creates a tokeniser calls [wipe] when the generation ends, however
 * it ends, and before it lets go of the tokeniser for another one. A cached entry is never
 * replaced: a segment is tokenised once and its array kept until then. The bytes of each text are
 * overwritten as soon as the native tokenizer has returned; the text itself is a [String], which
 * cannot be overwritten.
 *
 * **Answers the model wrote.** Given [replies], the text of an assistant message
 * ([Prompt.answers]) that the model wrote is spelled with the model's own tokens rather than
 * encoded: the message's text must be a beginning of a reply's [WrittenReply.content] (the whole
 * of it when it is given back as it was, less its trailing whitespace once the format trims it as
 * history, less what its reader never received when it was stopped), and the longest run of the
 * reply's tokens that spells a beginning of that text and ends between two characters is put in
 * its place. Whatever stands before the run in its segment, and the rest of the text after it,
 * are encoded as usual, each as one text. The text of the prompt is the same either way; only how
 * it is spelled changes, and only into a spelling the model itself produced. An answer that is
 * not a beginning of any reply (edited, or another one) is encoded. When [memory] is given and
 * the prompt encoded throughout has a longer beginning in common with it than the one with the
 * model's tokens, the encoded one is used: putting back what the model wrote never makes a
 * prompt agree less with the memory.
 *
 * @param replies the written replies of this conversation, oldest first; the latest that fits an
 *   answer is the one used. They belong to the engine, which overwrites them; this class only
 *   reads them, and never after the engine has let go of them.
 * @param memory what the model's memory holds, to choose between the two spellings; null to
 *   take the model's tokens wherever they fit.
 */
internal class PromptTokeniser(
    private val native: NativeLlama,
    private val replies: List<WrittenReply> = emptyList(),
    private val memory: TokenList? = null,
) {

    private val cache = HashMap<PromptSegment, IntArray>()

    /** Every array handed out by [tokenise] that is not in [cache], so that [wipe] can reach it. */
    private val issued = ArrayList<IntArray>()

    /** The text of each reply in [replies], decoded once when it is first compared. */
    private val replyTexts = HashMap<WrittenReply, String>()

    /** True once [wipe] has run; the tokeniser is not used again after that. */
    private var wiped = false

    /**
     * The tokens of [prompt], with the model's own tokens for its answers where they fit (see
     * the class). The arrays of the result belong to this tokeniser and are overwritten by
     * [wipe]; the caller must not use them after that.
     */
    fun tokenise(prompt: Prompt): TokenisedPrompt {
        check(!wiped) { "The tokeniser was wiped" }
        val encoded = join(prompt, candidates = emptyList())
        if (replies.isEmpty() || prompt.answers.isEmpty()) return encoded
        val written = join(prompt, candidates = replies)
        if (written.replies.isEmpty()) return encoded
        val memory = memory
        if (memory != null && memory.commonPrefixWith(encoded.tokens) > memory.commonPrefixWith(written.tokens)) return encoded
        return written
    }

    /** The tokens of [prompt], with the answers spelled by the replies among [candidates] that fit them. */
    private fun join(prompt: Prompt, candidates: List<WrittenReply>): TokenisedPrompt {
        val starts = IntArray(prompt.segments.size + 1)
        val parts = ArrayList<IntArray>(prompt.segments.size)
        val used = HashSet<WrittenReply>()
        var total = 0
        prompt.segments.forEachIndexed { index, segment ->
            starts[index] = total
            val part = if (segment is PromptSegment.Text && candidates.isNotEmpty()) {
                spelled(segment.text, answersIn(prompt, index, segment.text.length), candidates, used)
            } else {
                tokens(segment)
            }
            parts += part
            total += part.size
        }
        starts[prompt.segments.size] = total
        val all = IntArray(total)
        parts.forEachIndexed { index, part -> part.copyInto(all, starts[index]) }
        issued += all
        issued += starts
        return TokenisedPrompt(prompt, all, starts, candidates.filter { it in used })
    }

    /** The answers inside the text segment [segment] that end at or before character [end], in order. */
    private fun answersIn(prompt: Prompt, segment: Int, end: Int): List<PromptSpan> =
        prompt.answers.filter { it.segment == segment && it.start < it.end && it.end <= end }.sortedBy { it.start }

    /**
     * The tokens of [text], a text segment or the beginning of one, with each of [answers] whose
     * text a reply among [candidates] fits spelled with that reply's tokens; adds the replies it
     * used to [used]. Without any, the text's own encoding.
     */
    private fun spelled(text: String, answers: List<PromptSpan>, candidates: List<WrittenReply>, used: MutableSet<WrittenReply>): IntArray {
        val out = TokenList()
        try {
            var from = 0
            var any = false
            for (answer in answers) {
                if (answer.start < from) continue
                val fit = fit(text.substring(answer.start, answer.end), candidates) ?: continue
                out.addAll(textTokens(text.substring(from, answer.start)))
                out.addAll(fit.reply.ids, fit.tokens)
                from = answer.start + fit.chars
                used += fit.reply
                any = true
            }
            if (!any) return tokens(PromptSegment.Text(text))
            out.addAll(textTokens(text.substring(from)))
            return out.toIntArray().also { issued += it }
        } finally {
            out.wipe()
        }
    }

    /**
     * How the latest of [candidates] whose text [answer] begins spells it: the reply, how many
     * of its tokens, and how many characters of [answer] they spell. Null when none fits, or
     * when not even the first token ends between two characters within the answer.
     */
    private fun fit(answer: String, candidates: List<WrittenReply>): Fit? {
        val answerBytes = utf8Length(answer)
        for (reply in candidates.asReversed()) {
            val text = replyTexts.getOrPut(reply) { String(reply.content, Charsets.UTF_8) }
            if (!text.startsWith(answer)) continue
            var count = 0
            while (count < reply.ends.size && reply.ends[count] <= answerBytes) count++
            while (count > 0 && !isCharacterStart(reply.content, reply.ends[count - 1])) count--
            if (count == 0) continue
            return Fit(reply, count, charsIn(reply.content, reply.ends[count - 1]))
        }
        return null
    }

    private class Fit(val reply: WrittenReply, val tokens: Int, val chars: Int)

    private fun textTokens(text: String): IntArray = tokens(PromptSegment.Text(text))

    /**
     * Overwrites every array of token ids this tokeniser has made, cached or handed out, and
     * forgets them. The positions of the segments are overwritten too, since they give the
     * lengths of the messages. Safe to call more than once; nothing may be tokenised afterwards.
     */
    fun wipe() {
        wiped = true
        for (tokens in cache.values) tokens.fill(0)
        cache.clear()
        for (tokens in issued) tokens.fill(0)
        issued.clear()
        // Texts cannot be overwritten; they are let go of. The replies themselves are the engine's.
        replyTexts.clear()
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
     * The token position of the prompt's message boundary number [index] (in the order of
     * [Prompt.boundaries]), or null when there is no such boundary or the tokenizer puts no token
     * boundary there; checked as [boundaryPositions] checks every boundary.
     */
    fun boundaryPosition(tokenised: TokenisedPrompt, index: Int): Int? {
        val boundary = tokenised.prompt.boundaries.getOrNull(index) ?: return null
        return positionOf(tokenised, boundary)
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
        // The head is spelled as the segment was: with the same replies for the answers before it.
        val headTokens = when {
            segment !is PromptSegment.Text -> tokens(PromptSegment.Special(head))
            tokenised.replies.isEmpty() -> tokens(PromptSegment.Text(head))
            else -> spelled(head, answersIn(tokenised.prompt, boundary.segment, boundary.offset), tokenised.replies, HashSet())
        }
        val end = tokenised.segmentStarts[boundary.segment + 1]
        if (start + headTokens.size > end) return null
        for (index in headTokens.indices) {
            if (tokenised.tokens[start + index] != headTokens[index]) return null
        }
        return start + headTokens.size
    }

    private fun tokens(segment: PromptSegment): IntArray = when (segment) {
        is PromptSegment.TokenId -> intArrayOf(segment.id).also { issued += it }
        is PromptSegment.Text -> tokenise(segment, segment.text, parseSpecial = false)
        is PromptSegment.Special -> tokenise(segment, segment.text, parseSpecial = true)
    }

    private fun tokenise(segment: PromptSegment, text: String, parseSpecial: Boolean): IntArray {
        // Nothing encodes to nothing; the native call is spared.
        if (text.isEmpty()) return EMPTY
        return cache.getOrPut(segment) {
            val utf8 = text.toByteArray(Charsets.UTF_8)
            try {
                native.tokenize(utf8, parseSpecial)
            } finally {
                // The native side has made its own copy; this one is not needed any more.
                utf8.fill(0)
            }
        }
    }

    companion object {
        private val EMPTY = IntArray(0)

        /** Whether byte [at] of the UTF-8 [bytes] begins a character, or is their end. */
        internal fun isCharacterStart(bytes: ByteArray, at: Int): Boolean =
            at == bytes.size || (at in 0 until bytes.size && (bytes[at].toInt() and 0xC0) != 0x80)

        /**
         * How many UTF-16 characters of a [String] the first [end] bytes of the well-formed
         * UTF-8 [bytes] decode to, [end] being the start of a character: one for every leading
         * byte, and two for the leading byte of a four-byte sequence, which is a surrogate pair.
         */
        internal fun charsIn(bytes: ByteArray, end: Int): Int {
            var chars = 0
            for (index in 0 until end) {
                val byte = bytes[index].toInt() and 0xFF
                when {
                    byte and 0xC0 == 0x80 -> Unit
                    byte >= 0xF0 -> chars += 2
                    else -> chars += 1
                }
            }
            return chars
        }

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
