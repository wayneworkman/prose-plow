package us.theworkmans.proseplow.settings

import us.theworkmans.proseplow.core.model.ModelSpec
import us.theworkmans.proseplow.core.model.acceptsReplyLength

/**
 * The text of a "longest reply" field, to a number of tokens and back.
 *
 * What is typed is read as a whole number: digits and nothing else, no sign, no grouping, no
 * decimal mark, no spaces inside. Digits of other scripts are read as the digits they are,
 * because a numeric keyboard produces them in some locales. A field that guessed what "1,024"
 * or "1e3" was meant to be would store a value the user did not type. Which numbers are
 * accepted is the model's to say (see [us.theworkmans.proseplow.core.model.ReplyLength]).
 */
object ReplyLengthInput {

    /** What a field's text means. */
    sealed interface Parsed {
        /** The field is empty: the model's own longest reply applies. */
        data object Default : Parsed

        /** A number of tokens the model accepts. */
        data class Value(val tokens: Int) : Parsed

        /** Not a whole number as described above, or outside the model's limits. Nothing is stored for it. */
        data object Invalid : Parsed
    }

    /** More digits than this cannot be a number of tokens any model accepts, and would not fit an Int. */
    private const val MAX_DIGITS = 9

    /** Reads [text] as typed, for [model]. Blanks around it are ignored, and blank text is [Parsed.Default]. */
    fun parse(text: String, model: ModelSpec): Parsed {
        val typed = text.trim()
        if (typed.isEmpty()) return Parsed.Default
        if (typed.length > MAX_DIGITS || !typed.all(Character::isDigit)) return Parsed.Invalid
        val tokens = typed.fold(0) { number, digit -> number * 10 + Character.digit(digit, 10) }
        return if (model.acceptsReplyLength(tokens)) Parsed.Value(tokens) else Parsed.Invalid
    }

    /** [tokens] as a field shows it and as [parse] reads it back: plain digits. */
    fun format(tokens: Int): String = tokens.toString()
}
