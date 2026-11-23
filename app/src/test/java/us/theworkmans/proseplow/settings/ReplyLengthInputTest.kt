package us.theworkmans.proseplow.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import us.theworkmans.proseplow.core.model.ReplyLength
import us.theworkmans.proseplow.settings.ReplyLengthInput.Parsed

/** What the text of a "longest reply" field means, under the limits of a small context and of a larger one. */
class ReplyLengthInputTest {

    private val small = ReplyLength.range(2048) // 16 to 1,024: a context of 2,048 tokens
    private val large = ReplyLength.range(4096) // 16 to 2,048

    @Test
    fun anEmptyFieldIsTheModelsOwn() {
        assertEquals(Parsed.Default, ReplyLengthInput.parse("", large))
        assertEquals(Parsed.Default, ReplyLengthInput.parse("   ", large))
    }

    @Test
    fun aWholeNumberWithinTheLimitsIsAValue() {
        assertEquals(Parsed.Value(16), ReplyLengthInput.parse("16", small))
        assertEquals(Parsed.Value(512), ReplyLengthInput.parse("512", small))
        assertEquals(Parsed.Value(1024), ReplyLengthInput.parse("1024", small))
        assertEquals(Parsed.Value(2048), ReplyLengthInput.parse("2048", large))
        assertEquals("blanks around it are ignored", Parsed.Value(300), ReplyLengthInput.parse(" 300 ", large))
        assertEquals("zeros in front change nothing", Parsed.Value(64), ReplyLengthInput.parse("0064", large))
        // Digits of another script, as some numeric keyboards produce them.
        assertEquals(Parsed.Value(512), ReplyLengthInput.parse("\u0665\u0661\u0662", large))
    }

    @Test
    fun theLimitsAreThoseOfTheRangeAndNotEveryRangeIsTheSame() {
        assertEquals(Parsed.Invalid, ReplyLengthInput.parse("15", small))
        assertEquals(Parsed.Invalid, ReplyLengthInput.parse("0", small))
        assertEquals(Parsed.Invalid, ReplyLengthInput.parse("1025", small))
        assertEquals("the same number is fine where the context is larger", Parsed.Value(1025), ReplyLengthInput.parse("1025", large))
        assertEquals(Parsed.Invalid, ReplyLengthInput.parse("2049", large))
        // However long the context may become, no reply is set longer than 4,096.
        val largest = ReplyLength.range(262_144)
        assertEquals(Parsed.Value(4096), ReplyLengthInput.parse("4096", largest))
        assertEquals(Parsed.Invalid, ReplyLengthInput.parse("4097", largest))
    }

    @Test
    fun nothingButDigitsIsANumberHere() {
        val notNumbers = listOf("1,024", "1.024", "1 024", "512.0", "5e2", "-512", "+512", "0x200", "five hundred", "512 tokens", "1024\n", "\u00BD")
        for (text in notNumbers.filter { it.trim() == it }) {
            assertEquals("\"$text\"", Parsed.Invalid, ReplyLengthInput.parse(text, large))
        }
        // More digits than any length has: refused without being read as a number at all.
        assertEquals(Parsed.Invalid, ReplyLengthInput.parse("9".repeat(10), large))
        assertEquals(Parsed.Invalid, ReplyLengthInput.parse("4294967808", large)) // 512 if it wrapped around
        assertEquals(Parsed.Invalid, ReplyLengthInput.parse("999999999", large))
    }

    @Test
    fun whatIsShownIsReadBackAsTheSameValue() {
        for (tokens in listOf(16, 200, 512, 1024, 2048)) {
            assertEquals(Parsed.Value(tokens), ReplyLengthInput.parse(ReplyLengthInput.format(tokens), large))
        }
        assertEquals("plain digits, without grouping", "1024", ReplyLengthInput.format(1024))
    }
}
