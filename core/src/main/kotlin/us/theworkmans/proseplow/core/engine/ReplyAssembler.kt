package us.theworkmans.proseplow.core.engine

import us.theworkmans.proseplow.core.prompt.PromptWhitespace

/**
 * Turns the tokens of one reply into the text that is shown, in three steps that must happen in
 * this order:
 *
 * 1. Bytes become whole characters ([Utf8Assembler]).
 * 2. Stop strings are looked for in the text exactly as the model wrote it ([StopStringMatcher]).
 * 3. Whitespace at the very start of a new reply is dropped. Some tokenizers put a space in front
 *    of the first word of every reply, and every model's reference code strips it; a stop string
 *    may itself begin with whitespace, which is why this comes after step 2.
 *
 * Whitespace at the end is not touched: when a reply is interrupted or is to be continued, the
 * model must be able to carry on from exactly what it wrote.
 *
 * The reply is kept as the bytes of each token that still stands, and the text is always what
 * the three steps make of those bytes. A token is normally added to the end, which only ever
 * adds text. A model with a deletion token can also take its last token back ([deleteLast]);
 * the steps are then run again over the tokens that remain, from the first one. Nothing cleverer
 * would be right: the token taken back may have been half of a character, the end of a possible
 * stop string, or the space that was dropped from the start.
 *
 * @param dropLeadingWhitespace true for a new reply of a chat model; false when a message that
 *   already has text is being continued, where a leading space is a real space between two
 *   words, and for a model that continues text, whose every character counts.
 */
internal class ReplyAssembler(private val stopStrings: List<String>, private val dropLeadingWhitespace: Boolean) {

    /** What one token did to the text the reader has been given. */
    sealed interface Change {

        /** Nothing the reader can see. */
        data object None : Change

        /** [text] was added to the end. Never empty. */
        data class Appended(val text: String) : Change

        /** Text was taken back: [text] is the whole reply as it reads now, [removed] characters shorter. */
        data class Replaced(val text: String, val removed: Int) : Change
    }

    /** The three steps and what they have let through so far. */
    private inner class Pipeline {
        val utf8 = Utf8Assembler()
        val stops = StopStringMatcher(stopStrings)
        val emitted = StringBuilder()
        var atStart = dropLeadingWhitespace

        fun feed(bytes: ByteArray): String = emit(stops.feed(utf8.feed(bytes)))

        fun finish(): String {
            utf8.finish()
            return emit(stops.finish())
        }

        private fun emit(text: String): String {
            var out = text
            if (atStart) {
                out = PromptWhitespace.stripStart(out)
                if (out.isNotEmpty()) atStart = false
            }
            emitted.append(out)
            return out
        }
    }

    /** The bytes of every token of the reply that has not been taken back, in order. */
    private val visible = ArrayList<ByteArray>()
    private var pipeline = Pipeline()

    /** Everything emitted so far that still stands. */
    val text: String get() = pipeline.emitted.toString()

    /** The stop string that ended the reply, or null. */
    val matchedStop: String? get() = pipeline.stops.matched

    /** Adds one token's bytes to the end of the reply. A token without bytes still counts as one. */
    fun accept(bytes: ByteArray): Change {
        visible += bytes
        val added = pipeline.feed(bytes)
        return if (added.isEmpty()) Change.None else Change.Appended(added)
    }

    /**
     * Takes back the last token of the reply. With no token left there is nothing to take back
     * and nothing happens: a deletion never reaches beyond the start of the reply, into the
     * prompt or into the text a continued message already had.
     *
     * The text that results is always a beginning of the text there was, because taking a token
     * away can only take text away; [Change.Replaced] is returned when it is in fact shorter.
     */
    fun deleteLast(): Change {
        if (visible.isEmpty()) return Change.None
        visible.removeAt(visible.lastIndex)
        val before = text
        pipeline = Pipeline()
        for (bytes in visible) pipeline.feed(bytes)
        val now = text
        return if (now.length == before.length) Change.None else Change.Replaced(now, removed = before.length - now.length)
    }

    /**
     * Ends the reply and returns the text that was held back as a possible stop string and never
     * became one. Bytes of an unfinished character are dropped.
     */
    fun finish(): String = pipeline.finish()
}
