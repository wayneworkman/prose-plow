package us.theworkmans.proseplow.settings

import us.theworkmans.proseplow.data.ModelOverrides
import java.math.BigDecimal

/**
 * The text of a temperature field, to a value and back.
 *
 * What is typed is read as people write decimal numbers: digits with at most one decimal mark,
 * which may always be a point and may also be the decimal mark of the user's locale (a comma in
 * much of the world). Digits of other scripts are read as the digits they are, because a
 * numeric keyboard produces them in some locales. Nothing else is a number here: no sign, no
 * exponent, no grouping, no spaces inside. That is deliberate. A field that guesses what "1,5"
 * or "1e-1" was meant to be stores a value the user did not type.
 */
object TemperatureInput {

    /** What a field's text means. */
    sealed interface Parsed {

        /** The field is empty: the model's own temperature applies. */
        data object Default : Parsed

        /** A temperature within its range. */
        data class Value(val temperature: Float) : Parsed

        /** Not a number as described above, or outside the range. Nothing is stored for it. */
        data object Invalid : Parsed
    }

    private val NUMBER = Regex("[0-9]+(\\.[0-9]*)?|\\.[0-9]+")

    /** Longer than any sensible entry; beyond it the text is not even looked at. */
    private const val MAX_LENGTH = 16

    private val MIN = BigDecimal(ModelOverrides.MIN_TEMPERATURE.toString())
    private val MAX = BigDecimal(ModelOverrides.MAX_TEMPERATURE.toString())

    /**
     * Reads [text] as typed. Blanks around it are ignored, and blank text is [Parsed.Default].
     *
     * @param decimalSeparator the decimal mark of the user's locale, accepted beside the point.
     */
    fun parse(text: String, decimalSeparator: Char): Parsed {
        val typed = text.trim()
        if (typed.isEmpty()) return Parsed.Default
        if (typed.length > MAX_LENGTH) return Parsed.Invalid
        val plain = buildString(typed.length) {
            for (character in typed) {
                append(
                    when {
                        character == '.' || character == decimalSeparator -> '.'
                        // Also the digits of other scripts; anything else stays and fails the pattern.
                        Character.isDigit(character) -> '0' + Character.digit(character, 10)
                        else -> character
                    },
                )
            }
        }
        if (!NUMBER.matches(plain)) return Parsed.Invalid
        val number = if (plain.startsWith('.')) "0$plain" else plain.removeSuffix(".")
        // Compared as the decimal number that was typed, so that 2.0000001 is not rounded into range.
        val exact = BigDecimal(number)
        if (exact < MIN || exact > MAX) return Parsed.Invalid
        return Parsed.Value(number.toFloat())
    }

    /**
     * [temperature] as a field shows it and as [parse] reads it back to the same value: plain
     * decimal digits without needless zeros, with [decimalSeparator] as the decimal mark.
     */
    fun format(temperature: Float, decimalSeparator: Char): String {
        val plain = BigDecimal(temperature.toString()).stripTrailingZeros().toPlainString()
        return if (decimalSeparator == '.') plain else plain.replace('.', decimalSeparator)
    }
}
