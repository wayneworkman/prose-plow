package us.theworkmans.proseplow.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplyAssemblerTest {

    private fun String.utf8() = toByteArray(Charsets.UTF_8)

    @Test
    fun `a new reply loses the whitespace in front of its first word, across tokens`() {
        val reply = ReplyAssembler(emptyList(), dropLeadingWhitespace = true)
        assertEquals("", reply.accept(" ".utf8()))
        assertEquals("", reply.accept("\n\u00A0".utf8()))
        assertEquals("World", reply.accept(" World".utf8()))
        // Only the start is trimmed: later spaces are text.
        assertEquals(" War II ", reply.accept(" War II ".utf8()))
        assertEquals("", reply.finish())
        assertEquals("World War II ", reply.text)
    }

    @Test
    fun `a continued message keeps the space that separates it from what was there`() {
        val reply = ReplyAssembler(emptyList(), dropLeadingWhitespace = false)
        assertEquals(" 42", reply.accept(" 42".utf8()))
        assertEquals(" 42", reply.text)
    }

    @Test
    fun `whitespace at the end is delivered as written`() {
        val reply = ReplyAssembler(emptyList(), dropLeadingWhitespace = true)
        assertEquals("Done.\n\n", reply.accept("Done.\n\n".utf8()))
        assertEquals("", reply.finish())
        assertEquals("Done.\n\n", reply.text)
    }

    @Test
    fun `a stop string is found in the text as written, before the start is trimmed`() {
        // The stop string begins with a newline; trimming first would have destroyed the match.
        val reply = ReplyAssembler(listOf("\nUser:"), dropLeadingWhitespace = true)
        assertEquals("", reply.accept(" \nUser: hi".utf8()))
        assertEquals("\nUser:", reply.matchedStop)
        assertEquals("", reply.finish())
        assertEquals("", reply.text)
    }

    @Test
    fun `a stop string split across tokens in the middle of a character boundary`() {
        val stop = "\u2192END" // an arrow of three bytes, then END
        val reply = ReplyAssembler(listOf(stop), dropLeadingWhitespace = true)
        val bytes = "ok \u2192END tail".utf8()
        val out = StringBuilder()
        for (byte in bytes) out.append(reply.accept(byteArrayOf(byte)))
        out.append(reply.finish())
        assertEquals("ok ", out.toString())
        assertEquals(stop, reply.matchedStop)
        assertEquals("ok ", reply.text)
    }

    @Test
    fun `text held back as a possible stop string is released when the reply ends`() {
        val reply = ReplyAssembler(listOf("<|im_end|>"), dropLeadingWhitespace = true)
        assertEquals("a ", reply.accept("a <|im".utf8()))
        assertEquals("<|im", reply.finish())
        assertNull(reply.matchedStop)
        assertEquals("a <|im", reply.text)
    }

    @Test
    fun `an unfinished character at the end is dropped`() {
        val reply = ReplyAssembler(emptyList(), dropLeadingWhitespace = true)
        val emoji = "\uD83D\uDE00".utf8()
        assertEquals("hi", reply.accept("hi".utf8() + emoji.copyOfRange(0, 3)))
        assertEquals("", reply.finish())
        assertEquals("hi", reply.text)
    }

    @Test
    fun `the emitted pieces joined are the text`() {
        val reply = ReplyAssembler(listOf("STOP"), dropLeadingWhitespace = true)
        val pieces = listOf("  He", "llo ", "\uD83D\uDE00", " S", "TO", "x ST", "OP never")
        val out = pieces.joinToString("") { reply.accept(it.utf8()) } + reply.finish()
        assertEquals("Hello \uD83D\uDE00 STOx ", out)
        assertEquals(out, reply.text)
        assertEquals("STOP", reply.matchedStop)
    }
}
