package us.theworkmans.proseplow.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import us.theworkmans.proseplow.settings.TemperatureInput.Parsed

/** What the text of a temperature field is read as, with a decimal point and with a decimal comma. */
class TemperatureInputTest {

    private fun point(text: String) = TemperatureInput.parse(text, '.')

    private fun comma(text: String) = TemperatureInput.parse(text, ',')

    @Test
    fun anEmptyFieldIsTheModelsOwnTemperature() {
        for (text in listOf("", " ", "\t ")) {
            assertSame(Parsed.Default, point(text))
            assertSame(Parsed.Default, comma(text))
        }
    }

    @Test
    fun numbersFromZeroToTwoAreTemperaturesWithBothEnds() {
        val expected = mapOf(
            "0" to 0f, "0.0" to 0f, "0." to 0f, ".0" to 0f, "000" to 0f,
            "0.7" to 0.7f, ".7" to 0.7f, "0.70" to 0.7f, " 0.7 " to 0.7f,
            "1" to 1f, "1.25" to 1.25f, "0.05" to 0.05f,
            "2" to 2f, "2." to 2f, "2.0" to 2f, "2.000" to 2f, "02" to 2f,
        )
        for ((text, value) in expected) assertEquals(text, Parsed.Value(value), point(text))
    }

    @Test
    fun whatLiesOutsideTheRangeIsNotATemperatureHoweverClose() {
        for (text in listOf("2.01", "2.0000001", "3", "20", "100", "9999999999999999")) assertSame(text, Parsed.Invalid, point(text))
    }

    @Test
    fun onlyPlainDecimalNumbersAreRead() {
        for (text in listOf("-1", "-0", "+1", "1e0", "1E-1", "0x1", "1f", "NaN", "Infinity", "abc", "0.7x", "1 0", "1.2.3", "..", ".", "1/2", "0.7.")) {
            assertSame(text, Parsed.Invalid, point(text))
        }
        assertSame("far too long to be a temperature", Parsed.Invalid, point("0." + "0".repeat(40)))
    }

    @Test
    fun theLocalesDecimalCommaIsReadAndSoIsThePoint() {
        assertEquals(Parsed.Value(0.7f), comma("0,7"))
        assertEquals(Parsed.Value(0.7f), comma("0.7"))
        assertEquals(Parsed.Value(0.5f), comma(",5"))
        assertEquals(Parsed.Value(2f), comma("2,0"))
        assertEquals(Parsed.Value(1f), comma("1,"))
        assertSame(Parsed.Invalid, comma("2,01"))
        assertSame("one decimal mark, not two", Parsed.Invalid, comma("1.234,5"))
        assertSame(Parsed.Invalid, comma("1,2,3"))
    }

    @Test
    fun aCommaIsNotADecimalMarkWhereTheLocaleWritesAPoint() {
        // There it separates thousands, and guessing would store a value that was not typed.
        assertSame(Parsed.Invalid, point("0,7"))
        assertSame(Parsed.Invalid, point("1,5"))
    }

    @Test
    fun digitsAndDecimalMarksOfOtherScriptsAreRead() {
        // Arabic-Indic digits zero and seven around the Arabic decimal separator.
        assertEquals(Parsed.Value(0.7f), TemperatureInput.parse("\u0660\u066B\u0667", '\u066B'))
        // Devanagari digits one and five around a point.
        assertEquals(Parsed.Value(1.5f), point("\u0967.\u096B"))
    }

    @Test
    fun aValueIsShownWithoutNeedlessZerosInTheLocalesNotation() {
        val shown = mapOf(0f to "0", 0.7f to "0.7", 1f to "1", 1.25f to "1.25", 0.05f to "0.05", 2f to "2", 0.0001f to "0.0001")
        for ((value, text) in shown) {
            assertEquals(text, TemperatureInput.format(value, '.'))
            assertEquals(text.replace('.', ','), TemperatureInput.format(value, ','))
        }
    }

    @Test
    fun whatIsShownIsReadBackAsTheSameValue() {
        for (value in listOf(0f, 0.1f, 0.3f, 0.35f, 0.7f, 1f, 1.1f, 1.999f, 2f)) {
            assertEquals(Parsed.Value(value), point(TemperatureInput.format(value, '.')))
            assertEquals(Parsed.Value(value), comma(TemperatureInput.format(value, ',')))
        }
    }
}
