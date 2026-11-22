package us.theworkmans.proseplow.core.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptWhitespaceTest {

    /** Every character Python's `str.strip()` removes, as code points. */
    private val pythonWhitespace: Set<Int> =
        ((0x09..0x0D) + (0x1C..0x1F) + listOf(0x20, 0x85, 0xA0, 0x1680) + (0x2000..0x200A) + listOf(0x2028, 0x2029, 0x202F, 0x205F, 0x3000)).toSet()

    @Test
    fun `whitespace is exactly Python's set over the whole basic plane`() {
        for (code in 0..0xFFFF) {
            assertEquals("U+%04X".format(code), code in pythonWhitespace, PromptWhitespace.isWhitespace(code.toChar()))
        }
        assertEquals(29, pythonWhitespace.size)
    }

    @Test
    fun `the characters on which whitespace definitions disagree are handled as Python handles them`() {
        // The next-line character and the no-break space are stripped.
        assertEquals("x", PromptWhitespace.strip("\u0085\u00A0x\u00A0\u0085"))
        // Invisible characters that are not whitespace are kept.
        for (invisible in listOf('\u200B', '\uFEFF', '\u2060', '\u180E', '\u00AD', '\u200D')) {
            assertFalse(PromptWhitespace.isWhitespace(invisible))
        }
    }

    @Test
    fun `strip removes both ends and keeps the inside`() {
        assertEquals("a \n b", PromptWhitespace.strip(" \t\na \n b\r\n\u3000"))
        assertEquals("", PromptWhitespace.strip(" \n\t "))
        assertEquals("", PromptWhitespace.strip(""))
        assertEquals("x", PromptWhitespace.strip("x"))
    }

    @Test
    fun `stripStart and stripEnd each touch one end only`() {
        assertEquals("a \n", PromptWhitespace.stripStart(" \n a \n"))
        assertEquals(" \n a", PromptWhitespace.stripEnd(" \n a \n"))
        assertEquals("", PromptWhitespace.stripStart("\u00A0\u2028"))
        assertEquals("", PromptWhitespace.stripEnd("\u00A0\u2028"))
    }

    @Test
    fun `blank means nothing would be left`() {
        assertTrue(PromptWhitespace.isBlank(""))
        assertTrue(PromptWhitespace.isBlank(" \n\u0085\u00A0"))
        assertFalse(PromptWhitespace.isBlank(" \u200B "))
        assertFalse(PromptWhitespace.isBlank("a"))
    }
}
