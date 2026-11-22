package us.theworkmans.proseplow.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class Utf8AssemblerTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    /** Feeds [pieces] one by one and returns what came out after each. */
    private fun feedAll(assembler: Utf8Assembler, pieces: List<ByteArray>): List<String> = pieces.map { assembler.feed(it) }

    @Test
    fun `ASCII passes straight through`() {
        val assembler = Utf8Assembler()
        assertEquals("Hello", assembler.feed("Hello".toByteArray()))
        assertEquals("", assembler.feed(ByteArray(0)))
    }

    @Test
    fun `an emoji split over four tokens comes out once, whole`() {
        val emoji = "\uD83D\uDE00" // U+1F600, four bytes
        val encoded = emoji.toByteArray(Charsets.UTF_8)
        assertEquals(4, encoded.size)
        val out = feedAll(Utf8Assembler(), encoded.map { byteArrayOf(it) })
        assertEquals(listOf("", "", "", emoji), out)
    }

    @Test
    fun `characters of two and three bytes split in every possible place`() {
        for (text in listOf("\u00E9", "\u20AC", "\u65E5\u672C", "a\u00E9b\u20ACc")) {
            val encoded = text.toByteArray(Charsets.UTF_8)
            for (cut in 1 until encoded.size) {
                val assembler = Utf8Assembler()
                val out = assembler.feed(encoded.copyOfRange(0, cut)) + assembler.feed(encoded.copyOfRange(cut, encoded.size))
                assertEquals("$text cut at $cut", text, out)
            }
        }
    }

    @Test
    fun `text before and after a split character is not held back`() {
        val assembler = Utf8Assembler()
        // "a" + first two bytes of the euro sign: the "a" is complete and must come out now.
        assertEquals("a", assembler.feed(bytes(0x61, 0xE2, 0x82)))
        assertEquals("\u20ACb", assembler.feed(bytes(0xAC, 0x62)))
    }

    @Test
    fun `any way of cutting a mixed text gives the same text`() {
        val text = "na\u00EFve \u2615 \uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67 \u65E5\u672C\u8A9E \uD83D\uDE00!"
        val encoded = text.toByteArray(Charsets.UTF_8)
        val random = Random(20261122)
        repeat(200) {
            val assembler = Utf8Assembler()
            val out = StringBuilder()
            var index = 0
            while (index < encoded.size) {
                val size = random.nextInt(1, 5).coerceAtMost(encoded.size - index)
                out.append(assembler.feed(encoded.copyOfRange(index, index + size)))
                index += size
            }
            assertEquals(text, out.toString())
        }
    }

    @Test
    fun `bytes that can never be a character become replacement characters`() {
        // A stray continuation byte, an overlong lead, a lead beyond Unicode, the padding byte.
        assertEquals("\uFFFD", Utf8Assembler().feed(bytes(0x80)))
        assertEquals("\uFFFD\uFFFD", Utf8Assembler().feed(bytes(0xC0, 0xAF)))
        assertEquals("\uFFFD", Utf8Assembler().feed(bytes(0xF5)))
        assertEquals("\uFFFD", Utf8Assembler().feed(bytes(0xFF)))
    }

    @Test
    fun `a sequence interrupted by another character is replaced and the stream carries on`() {
        val assembler = Utf8Assembler()
        // Two bytes of a three-byte character, then "A" instead of the third.
        assertEquals("", assembler.feed(bytes(0xE2, 0x82)))
        assertEquals("\uFFFDA", assembler.feed(bytes(0x41)))
        assertEquals("ok", assembler.feed("ok".toByteArray()))
    }

    @Test
    fun `surrogates and overlong forms are rejected`() {
        // ED A0 80 would be the surrogate U+D800; E0 80 80 an overlong NUL; F4 90 above U+10FFFF.
        assertEquals("\uFFFD\uFFFD\uFFFD", Utf8Assembler().feed(bytes(0xED, 0xA0, 0x80)))
        assertEquals("\uFFFD\uFFFD\uFFFD", Utf8Assembler().feed(bytes(0xE0, 0x80, 0x80)))
        assertEquals("\uFFFD\uFFFD\uFFFD\uFFFD", Utf8Assembler().feed(bytes(0xF4, 0x90, 0x80, 0x80)))
    }

    @Test
    fun `the largest and smallest characters of each length decode`() {
        for (codePoint in listOf(0x00, 0x7F, 0x80, 0x7FF, 0x800, 0xD7FF, 0xE000, 0xFFFF, 0x10000, 0x10FFFF)) {
            val text = String(Character.toChars(codePoint))
            assertEquals("U+%X".format(codePoint), text, Utf8Assembler().feed(text.toByteArray(Charsets.UTF_8)))
        }
    }

    @Test
    fun `a character that never completes is dropped at the end, not shown as garbage`() {
        val assembler = Utf8Assembler()
        assertEquals("a", assembler.feed(bytes(0x61, 0xF0, 0x9F)))
        assembler.finish()
        // Nothing of the unfinished character leaks into what follows.
        assertEquals("b", assembler.feed(bytes(0x62)))
    }
}
