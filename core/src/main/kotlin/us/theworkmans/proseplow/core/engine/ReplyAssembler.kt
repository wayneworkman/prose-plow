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
 * @param dropLeadingWhitespace true for a new reply; false when a message that already has text
 *   is being continued, where a leading space is a real space between two words.
 */
internal class ReplyAssembler(stopStrings: List<String>, dropLeadingWhitespace: Boolean) {

    private val utf8 = Utf8Assembler()
    private val stops = StopStringMatcher(stopStrings)
    private val emitted = StringBuilder()
    private var atStart = dropLeadingWhitespace

    /** Everything emitted so far. */
    val text: String get() = emitted.toString()

    /** The stop string that ended the reply, or null. */
    val matchedStop: String? get() = stops.matched

    /** Adds one token's bytes and returns the text to emit for it, possibly empty. */
    fun accept(bytes: ByteArray): String = emit(stops.feed(utf8.feed(bytes)))

    /**
     * Ends the reply and returns the text that was held back as a possible stop string and never
     * became one. Bytes of an unfinished character are dropped.
     */
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
