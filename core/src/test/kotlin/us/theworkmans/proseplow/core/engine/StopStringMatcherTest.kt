package us.theworkmans.proseplow.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class StopStringMatcherTest {

    private val marker = "<|im_end|>"

    /** Feeds [pieces] and returns each piece's output; the matcher is left for further checks. */
    private fun feed(matcher: StopStringMatcher, vararg pieces: String): List<String> = pieces.map { matcher.feed(it) }

    @Test
    fun `without stop strings everything passes through at once`() {
        val matcher = StopStringMatcher(emptyList())
        assertEquals(listOf("Hello <", "|im_end|>"), feed(matcher, "Hello <", "|im_end|>"))
        assertNull(matcher.matched)
        assertEquals("", matcher.finish())
    }

    @Test
    fun `a stop string in one piece ends the text in front of it`() {
        val matcher = StopStringMatcher(listOf(marker))
        assertEquals("4.", matcher.feed("4.$marker\n<|im_start|>user"))
        assertEquals(marker, matcher.matched)
        assertEquals("", matcher.feed("more"))
        assertEquals("", matcher.finish())
    }

    @Test
    fun `a stop string split over three tokens is never emitted, in whole or in part`() {
        val matcher = StopStringMatcher(listOf(marker))
        // The first piece ends in what could be the start of the marker: only that is held back.
        assertEquals(listOf("The answer is 4.", "", ""), feed(matcher, "The answer is 4.<|", "im_", "end|>"))
        assertEquals(marker, matcher.matched)
    }

    @Test
    fun `only the possible start of a stop string is held back`() {
        val matcher = StopStringMatcher(listOf(marker))
        assertEquals("some text ", matcher.feed("some text <|im"))
        assertNull(matcher.matched)
    }

    @Test
    fun `a stop string that almost matches is emitted in full once it cannot match`() {
        val matcher = StopStringMatcher(listOf(marker))
        val out = feed(matcher, "a <|im", "_en", "x and on")
        assertEquals(listOf("a ", "", "<|im_enx and on"), out)
        assertNull(matcher.matched)
        assertEquals("", matcher.finish())
    }

    @Test
    fun `a near miss that is still pending at the end is released by finish`() {
        val matcher = StopStringMatcher(listOf(marker))
        assertEquals("reply ", matcher.feed("reply <|im_end|"))
        assertEquals("<|im_end|", matcher.finish())
        assertNull(matcher.matched)
    }

    @Test
    fun `a false start directly followed by the real stop string`() {
        val matcher = StopStringMatcher(listOf(marker))
        assertEquals(listOf("x", "<|im_", ""), feed(matcher, "x<|im_", "<|im_e", "nd|>"))
        assertEquals(marker, matcher.matched)
    }

    @Test
    fun `the stop string that completes first wins`() {
        val matcher = StopStringMatcher(listOf("STOP", "\n\n"))
        assertEquals("line", matcher.feed("line\n\nSTOP"))
        assertEquals("\n\n", matcher.matched)
    }

    @Test
    fun `of two stop strings completing together the one that began earlier wins`() {
        // "ab" was already being held back when "b" completed.
        val matcher = StopStringMatcher(listOf("b", "ab"))
        assertEquals(listOf("x", ""), feed(matcher, "xa", "b"))
        assertEquals("ab", matcher.matched)
        val atOnce = StopStringMatcher(listOf("b", "ab"))
        assertEquals("x", atOnce.feed("xab"))
        assertEquals("ab", atOnce.matched)
    }

    @Test
    fun `a stop string made of repeated characters`() {
        val matcher = StopStringMatcher(listOf("aaa"))
        assertEquals(listOf("b", "", ""), feed(matcher, "ba", "a", "a"))
        assertEquals("aaa", matcher.matched)
        val miss = StopStringMatcher(listOf("aab"))
        assertEquals(listOf("", "a", "aac"), feed(miss, "aa", "a", "c"))
        assertNull(miss.matched)
    }

    @Test
    fun `empty and duplicate stop strings are ignored`() {
        val matcher = StopStringMatcher(listOf("", "END", "END"))
        assertEquals("abc", matcher.feed("abc"))
        assertEquals("", matcher.feed("END"))
        assertEquals("END", matcher.matched)
    }

    @Test
    fun `a stop string that starts with an emoji does not split the pair`() {
        val matcher = StopStringMatcher(listOf("\uD83D\uDE00!"))
        // The emoji alone could be the start of the stop string: both of its halves are held.
        assertEquals("hi ", matcher.feed("hi \uD83D\uDE00"))
        assertEquals("\uD83D\uDE00?", matcher.feed("?"))
        assertNull(matcher.matched)
    }

    @Test
    fun `the result does not depend on how the text is cut into pieces`() {
        val random = Random(4711)
        val stops = listOf(marker, "\n\nUser:", "###")
        val texts = listOf(
            "A normal answer.$marker\n<|im_start|>user\nmore",
            "Almost <|im_end and <|im_en|> but never the marker, ## nor this # one",
            "Two\n\nUsers? No.\n\nUser: next question",
            "<|im_<|im_end|>tail",
            "trailing partial <|im_e",
        )
        for (text in texts) {
            val whole = StopStringMatcher(stops)
            val expected = whole.feed(text) + whole.finish()
            repeat(100) {
                val matcher = StopStringMatcher(stops)
                val out = StringBuilder()
                var index = 0
                while (index < text.length) {
                    val size = random.nextInt(1, 6).coerceAtMost(text.length - index)
                    out.append(matcher.feed(text.substring(index, index + size)))
                    index += size
                }
                out.append(matcher.finish())
                assertEquals(text, expected, out.toString())
                assertEquals(text, whole.matched, matcher.matched)
            }
        }
    }

    @Test
    fun `what is emitted plus what is held is always the text so far`() {
        // Nothing but a possible stop-string start is ever delayed: after each piece, the output
        // lags behind the input by less than the longest stop string.
        val matcher = StopStringMatcher(listOf(marker))
        val text = "one <two> |three| <|four|> <|im_start|> five"
        var fed = 0
        var emitted = 0
        for (char in text) {
            emitted += matcher.feed(char.toString()).length
            fed++
            val held = fed - emitted
            assertEquals("held text must be a start of the marker", true, marker.startsWith(text.substring(emitted, fed)))
            assertEquals(true, held < marker.length)
        }
        assertEquals(text.length, emitted + matcher.finish().length)
    }
}
