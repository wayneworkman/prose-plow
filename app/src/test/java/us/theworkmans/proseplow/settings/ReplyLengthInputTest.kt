package us.theworkmans.proseplow.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import us.theworkmans.proseplow.core.model.ModelCatalog
import us.theworkmans.proseplow.settings.ReplyLengthInput.Parsed

/** What the text of a "longest reply" field means, for a model with a small context and for one with a large. */
class ReplyLengthInputTest {

    private val boris = ModelCatalog.BORIS_2_INSTRUCT // 16 to 1,024
    private val qwen = ModelCatalog.QWEN35_0_8B // 16 to 2,048

    @Test
    fun anEmptyFieldIsTheModelsOwn() {
        assertEquals(Parsed.Default, ReplyLengthInput.parse("", qwen))
        assertEquals(Parsed.Default, ReplyLengthInput.parse("   ", qwen))
    }

    @Test
    fun aWholeNumberWithinTheModelsLimitsIsAValue() {
        assertEquals(Parsed.Value(16), ReplyLengthInput.parse("16", boris))
        assertEquals(Parsed.Value(512), ReplyLengthInput.parse("512", boris))
        assertEquals(Parsed.Value(1024), ReplyLengthInput.parse("1024", boris))
        assertEquals(Parsed.Value(2048), ReplyLengthInput.parse("2048", qwen))
        assertEquals("blanks around it are ignored", Parsed.Value(300), ReplyLengthInput.parse(" 300 ", qwen))
        assertEquals("zeros in front change nothing", Parsed.Value(64), ReplyLengthInput.parse("0064", qwen))
        // Digits of another script, as some numeric keyboards produce them.
        assertEquals(Parsed.Value(512), ReplyLengthInput.parse("\u0665\u0661\u0662", qwen))
    }

    @Test
    fun theLimitsAreTheModelsAndNotEveryModelsAreTheSame() {
        assertEquals(Parsed.Invalid, ReplyLengthInput.parse("15", boris))
        assertEquals(Parsed.Invalid, ReplyLengthInput.parse("0", boris))
        assertEquals(Parsed.Invalid, ReplyLengthInput.parse("1025", boris))
        assertEquals("the same number is fine for a model with a larger context", Parsed.Value(1025), ReplyLengthInput.parse("1025", qwen))
        assertEquals(Parsed.Invalid, ReplyLengthInput.parse("2049", qwen))
    }

    @Test
    fun nothingButDigitsIsANumberHere() {
        val notNumbers = listOf("1,024", "1.024", "1 024", "512.0", "5e2", "-512", "+512", "0x200", "five hundred", "512 tokens", "1024\n", "\u00BD")
        for (text in notNumbers.filter { it.trim() == it }) {
            assertEquals("\"$text\"", Parsed.Invalid, ReplyLengthInput.parse(text, qwen))
        }
        // More digits than any length has: refused without being read as a number at all.
        assertEquals(Parsed.Invalid, ReplyLengthInput.parse("9".repeat(10), qwen))
        assertEquals(Parsed.Invalid, ReplyLengthInput.parse("4294967808", qwen)) // 512 if it wrapped around
        assertEquals(Parsed.Invalid, ReplyLengthInput.parse("999999999", qwen))
    }

    @Test
    fun whatIsShownIsReadBackAsTheSameValue() {
        for (tokens in listOf(16, 200, 512, 1024, 2048)) {
            assertEquals(Parsed.Value(tokens), ReplyLengthInput.parse(ReplyLengthInput.format(tokens), qwen))
        }
        assertEquals("plain digits, without grouping", "1024", ReplyLengthInput.format(1024))
    }
}
