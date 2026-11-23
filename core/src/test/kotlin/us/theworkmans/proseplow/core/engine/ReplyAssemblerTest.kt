package us.theworkmans.proseplow.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class ReplyAssemblerTest {

    private fun String.utf8() = toByteArray(Charsets.UTF_8)

    /** Adds one token and returns the text appended for it, as the engine would emit it. */
    private fun ReplyAssembler.add(bytes: ByteArray): String = when (val change = accept(bytes)) {
        is ReplyAssembler.Change.Appended -> change.text
        ReplyAssembler.Change.None -> ""
        is ReplyAssembler.Change.Replaced -> error("Adding a token cannot take text back")
    }

    @Test
    fun `a new reply loses the whitespace in front of its first word, across tokens`() {
        val reply = ReplyAssembler(emptyList(), dropLeadingWhitespace = true)
        assertEquals("", reply.add(" ".utf8()))
        assertEquals("", reply.add("\n\u00A0".utf8()))
        assertEquals("World", reply.add(" World".utf8()))
        // Only the start is trimmed: later spaces are text.
        assertEquals(" War II ", reply.add(" War II ".utf8()))
        assertEquals("", reply.finish())
        assertEquals("World War II ", reply.text)
    }

    @Test
    fun `a continued message keeps the space that separates it from what was there`() {
        val reply = ReplyAssembler(emptyList(), dropLeadingWhitespace = false)
        assertEquals(" 42", reply.add(" 42".utf8()))
        assertEquals(" 42", reply.text)
    }

    @Test
    fun `whitespace at the end is delivered as written`() {
        val reply = ReplyAssembler(emptyList(), dropLeadingWhitespace = true)
        assertEquals("Done.\n\n", reply.add("Done.\n\n".utf8()))
        assertEquals("", reply.finish())
        assertEquals("Done.\n\n", reply.text)
    }

    @Test
    fun `a stop string is found in the text as written, before the start is trimmed`() {
        // The stop string begins with a newline; trimming first would have destroyed the match.
        val reply = ReplyAssembler(listOf("\nUser:"), dropLeadingWhitespace = true)
        assertEquals("", reply.add(" \nUser: hi".utf8()))
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
        for (byte in bytes) out.append(reply.add(byteArrayOf(byte)))
        out.append(reply.finish())
        assertEquals("ok ", out.toString())
        assertEquals(stop, reply.matchedStop)
        assertEquals("ok ", reply.text)
    }

    @Test
    fun `text held back as a possible stop string is released when the reply ends`() {
        val reply = ReplyAssembler(listOf("<|im_end|>"), dropLeadingWhitespace = true)
        assertEquals("a ", reply.add("a <|im".utf8()))
        assertEquals("<|im", reply.finish())
        assertNull(reply.matchedStop)
        assertEquals("a <|im", reply.text)
    }

    @Test
    fun `an unfinished character at the end is dropped`() {
        val reply = ReplyAssembler(emptyList(), dropLeadingWhitespace = true)
        val emoji = "\uD83D\uDE00".utf8()
        assertEquals("hi", reply.add("hi".utf8() + emoji.copyOfRange(0, 3)))
        assertEquals("", reply.finish())
        assertEquals("hi", reply.text)
    }

    @Test
    fun `the emitted pieces joined are the text`() {
        val reply = ReplyAssembler(listOf("STOP"), dropLeadingWhitespace = true)
        val pieces = listOf("  He", "llo ", "\uD83D\uDE00", " S", "TO", "x ST", "OP never")
        val out = pieces.joinToString("") { reply.add(it.utf8()) } + reply.finish()
        assertEquals("Hello \uD83D\uDE00 STOx ", out)
        assertEquals(out, reply.text)
        assertEquals("STOP", reply.matchedStop)
    }

    // --- Taking tokens back ---

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private val none = ReplyAssembler.Change.None

    private fun replaced(text: String, removed: Int) = ReplyAssembler.Change.Replaced(text, removed)

    @Test
    fun `taking back the last token shortens the text to what the tokens before it gave`() {
        val reply = ReplyAssembler(emptyList(), dropLeadingWhitespace = false)
        reply.add(" Nice.".utf8())
        reply.add(" 198".utf8())
        assertEquals(replaced(" Nice.", removed = 4), reply.deleteLast())
        assertEquals(" Nice.", reply.text)
        // What is added next goes after what is left.
        assertEquals(" development", reply.add(" development".utf8()))
        assertEquals(" Nice. development", reply.text)
    }

    @Test
    fun `deletions chain, one token each, down to nothing and no further`() {
        val reply = ReplyAssembler(emptyList(), dropLeadingWhitespace = false)
        reply.add("a".utf8())
        reply.add(" Russ".utf8())
        reply.add("ia".utf8())
        assertEquals(replaced("a Russ", removed = 2), reply.deleteLast())
        assertEquals(replaced("a", removed = 5), reply.deleteLast())
        assertEquals(replaced("", removed = 1), reply.deleteLast())
        // The start of the reply is as far as a deletion reaches.
        assertEquals(none, reply.deleteLast())
        assertEquals(none, reply.deleteLast())
        assertEquals("", reply.text)
        assertEquals("os Angeles", reply.add("os Angeles".utf8()))
    }

    @Test
    fun `a deletion before anything was written does nothing`() {
        val reply = ReplyAssembler(emptyList(), dropLeadingWhitespace = false)
        assertEquals(none, reply.deleteLast())
        assertEquals("Hi", reply.add("Hi".utf8()))
        assertEquals("Hi", reply.text)
    }

    @Test
    fun `a token without bytes is still a token to take back`() {
        val reply = ReplyAssembler(emptyList(), dropLeadingWhitespace = false)
        reply.add("a".utf8())
        assertEquals(none, reply.accept(ByteArray(0)))
        // The first deletion takes the empty token and changes nothing; the second takes the letter.
        assertEquals(none, reply.deleteLast())
        assertEquals("a", reply.text)
        assertEquals(replaced("", removed = 1), reply.deleteLast())
    }

    @Test
    fun `taking back the second half of a character takes the character back, and another can complete it`() {
        val reply = ReplyAssembler(emptyList(), dropLeadingWhitespace = false)
        reply.add("x".utf8())
        assertEquals("", reply.add(bytes(0xE2, 0x82)))
        assertEquals("\u20AC", reply.add(bytes(0xAC)))
        // The euro sign was shown; its last byte is taken back, so it is gone again.
        assertEquals(replaced("x", removed = 1), reply.deleteLast())
        // The two bytes that remain are completed differently: the won sign.
        assertEquals("\u20A9", reply.add(bytes(0xA9)))
        assertEquals("x\u20A9", reply.text)
    }

    @Test
    fun `taking back half a character that was never shown changes nothing the reader saw`() {
        val reply = ReplyAssembler(emptyList(), dropLeadingWhitespace = false)
        reply.add("x".utf8())
        reply.add(bytes(0xF0, 0x9F))
        assertEquals(none, reply.deleteLast())
        // No stray bytes are left behind to spoil the next character.
        assertEquals("\uD83D\uDE00", reply.add("\uD83D\uDE00".utf8()))
        assertEquals("x\uD83D\uDE00", reply.text)
    }

    @Test
    fun `taking back a whole emoji removes both of its halves`() {
        val reply = ReplyAssembler(emptyList(), dropLeadingWhitespace = false)
        reply.add("ok ".utf8())
        reply.add("\uD83D\uDE00".utf8())
        assertEquals(replaced("ok ", removed = 2), reply.deleteLast())
    }

    @Test
    fun `text held back as a possible stop string is taken back without the reader noticing`() {
        val reply = ReplyAssembler(listOf("<|im_end|>"), dropLeadingWhitespace = false)
        assertEquals("ab ", reply.add("ab <|im".utf8()))
        assertEquals("", reply.add("_en".utf8()))
        assertEquals(none, reply.deleteLast())
        // The matcher was rebuilt from what is left: "<|im" is held again, and a stop still stops.
        assertEquals("", reply.add("_end|>".utf8()))
        assertEquals("<|im_end|>", reply.matchedStop)
        assertEquals("ab ", reply.text)
    }

    @Test
    fun `a deletion can take back part of what a held token had let through`() {
        val reply = ReplyAssembler(listOf("STOP"), dropLeadingWhitespace = false)
        assertEquals("one ", reply.add("one S".utf8()))
        // "Sx" cannot become the stop string, so the held "S" comes out with the "x".
        assertEquals("Sx", reply.add("x".utf8()))
        assertEquals(replaced("one ", removed = 2), reply.deleteLast())
        assertEquals("", reply.add("TOP".utf8()))
        assertEquals("STOP", reply.matchedStop)
    }

    @Test
    fun `a chat reply that is taken back to its start drops leading whitespace again`() {
        val reply = ReplyAssembler(emptyList(), dropLeadingWhitespace = true)
        assertEquals("", reply.add(" ".utf8()))
        assertEquals("Hi", reply.add(" Hi".utf8()))
        assertEquals(replaced("", removed = 2), reply.deleteLast())
        assertEquals("Yo", reply.add("  Yo".utf8()))
        assertEquals("Yo", reply.text)
    }

    @Test
    fun `the text after any run of additions and deletions is the text of the tokens that remain`() {
        val random = Random(42)
        val pieces = listOf("a", " b", "\u00E9", "\uD83D\uDE00", " ", "\n", "ST", "OP", "x").map { it.utf8() } +
            listOf(bytes(0xE2), bytes(0x82), bytes(0xAC), bytes(0xFF))
        repeat(300) {
            val reply = ReplyAssembler(listOf("STOP"), dropLeadingWhitespace = it % 2 == 0)
            val kept = ArrayList<ByteArray>()
            val shown = StringBuilder()
            var steps = 0
            while (steps++ < 40 && reply.matchedStop == null) {
                if (random.nextInt(4) == 0) {
                    if (kept.isNotEmpty()) kept.removeAt(kept.lastIndex)
                    when (val change = reply.deleteLast()) {
                        is ReplyAssembler.Change.Replaced -> {
                            assertEquals("a deletion only ever shortens", shown.substring(0, shown.length - change.removed), change.text)
                            shown.setLength(shown.length - change.removed)
                        }
                        is ReplyAssembler.Change.Appended -> error("A deletion cannot add text")
                        ReplyAssembler.Change.None -> Unit
                    }
                } else {
                    val piece = pieces[random.nextInt(pieces.size)]
                    kept += piece
                    shown.append(reply.add(piece))
                }
                // What the reader has is what a fresh assembler makes of the tokens that remain.
                val fresh = ReplyAssembler(listOf("STOP"), dropLeadingWhitespace = it % 2 == 0)
                for (piece in kept) fresh.accept(piece)
                assertEquals(fresh.text, reply.text)
                assertEquals(reply.text, shown.toString())
            }
        }
    }
}
