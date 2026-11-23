package us.theworkmans.proseplow.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import us.theworkmans.proseplow.core.engine.FakeNativeLlama.Call
import us.theworkmans.proseplow.core.prompt.Prompt
import us.theworkmans.proseplow.core.prompt.PromptBoundary
import us.theworkmans.proseplow.core.prompt.PromptSegment

class PromptTokeniserTest {

    private val fake = FakeNativeLlama()
    private val marker = fake.addControl("<|x|>")
    private val tokeniser = PromptTokeniser(fake)

    @Test
    fun `segments are tokenised one by one and joined in order`() {
        val prompt = Prompt(
            listOf(
                PromptSegment.Special("<|x|>a"),
                PromptSegment.Text("<|x|>b"),
                PromptSegment.TokenId(7),
                PromptSegment.Text(""),
                PromptSegment.Text("c"),
            ),
        )
        val tokenised = tokeniser.tokenise(prompt)
        assertEquals(
            listOf(marker) + fake.textTokens("a") + fake.textTokens("<|x|>b") + listOf(7) + fake.textTokens("c"),
            tokenised.tokens.toList(),
        )
        assertEquals(listOf(0, 2, 8, 9, 9, 10), tokenised.segmentStarts.toList())
        // An injected id and an empty text need no tokenizer call.
        assertEquals(
            listOf(Call.Tokenize("<|x|>a", parseSpecial = true), Call.Tokenize("<|x|>b", parseSpecial = false), Call.Tokenize("c", parseSpecial = false)),
            fake.calls,
        )
    }

    @Test
    fun `a segment that was tokenised before is not tokenised again`() {
        val prompt = Prompt(listOf(PromptSegment.Text("same"), PromptSegment.Special("same"), PromptSegment.Text("same")))
        tokeniser.tokenise(prompt)
        tokeniser.tokenise(prompt)
        // Once as text and once with special parsing: the two are different questions.
        assertEquals(2, fake.calls.size)
    }

    @Test
    fun `text reaches the tokenizer as standard UTF-8`() {
        // The four bytes F0 9F 98 80 of one emoji, not the six bytes a JNI string would carry.
        val emoji = String(Character.toChars(0x1F600))
        val tokenised = tokeniser.tokenise(Prompt(listOf(PromptSegment.Text(emoji))))
        assertEquals(listOf(0xF0, 0x9F, 0x98, 0x80).map { fake.byteToken(it) }, tokenised.tokens.toList())
    }

    @Test
    fun `boundaries at segment starts are token positions`() {
        val prompt = Prompt(
            segments = listOf(PromptSegment.Text("abc"), PromptSegment.TokenId(7), PromptSegment.Text("de"), PromptSegment.Text("f")),
            boundaries = listOf(PromptBoundary(0), PromptBoundary(1), PromptBoundary(2), PromptBoundary(3)),
        )
        val tokenised = tokeniser.tokenise(prompt)
        // Position 0 is never returned: there is nothing to go back to.
        assertEquals(listOf(3, 4, 6), tokeniser.boundaryPositions(tokenised, after = 0, limit = 10))
        assertEquals("only the latest ones", listOf(4, 6), tokeniser.boundaryPositions(tokenised, after = 0, limit = 2))
        assertEquals("only those not yet in memory", listOf(6), tokeniser.boundaryPositions(tokenised, after = 4, limit = 10))
        assertEquals(emptyList<Int>(), tokeniser.boundaryPositions(tokenised, after = 6, limit = 10))
        assertEquals(emptyList<Int>(), tokeniser.boundaryPositions(tokenised, after = 0, limit = 0))
    }

    @Test
    fun `a boundary inside a text segment is the number of tokens in front of it`() {
        val text = "one two three"
        val prompt = Prompt(
            segments = listOf(PromptSegment.TokenId(7), PromptSegment.Text(text)),
            boundaries = listOf(PromptBoundary(1, 0), PromptBoundary(1, 4), PromptBoundary(1, 8)),
        )
        val tokenised = tokeniser.tokenise(prompt)
        assertEquals(listOf(1, 5, 9), tokeniser.boundaryPositions(tokenised, after = 0, limit = 10))
    }

    @Test
    fun `a boundary that does not fall between two tokens is skipped`() {
        // In the whole segment the marker is one token; the text up to the middle of the marker
        // tokenises to something else, so that offset is not a token position.
        val prompt = Prompt(
            segments = listOf(PromptSegment.Special("ab<|x|>cd")),
            boundaries = listOf(PromptBoundary(0, 2), PromptBoundary(0, 4), PromptBoundary(0, 7)),
        )
        val tokenised = tokeniser.tokenise(prompt)
        assertEquals(5, tokenised.tokens.size)
        // Offset 2 is in front of the marker (position 2), offset 7 behind it (position 3).
        assertEquals(listOf(2, 3), tokeniser.boundaryPositions(tokenised, after = 0, limit = 10))
    }

    @Test
    fun `boundaries that point nowhere are ignored`() {
        val prompt = Prompt(
            segments = listOf(PromptSegment.Text("abc"), PromptSegment.TokenId(7)),
            boundaries = listOf(
                PromptBoundary(0, 1),
                PromptBoundary(0, 3), // the end of the segment is not inside it
                PromptBoundary(0, 99),
                PromptBoundary(1, 1), // an injected id has no inside
                PromptBoundary(5, 0),
                PromptBoundary(-1, 0),
            ),
        )
        val tokenised = tokeniser.tokenise(prompt)
        assertEquals(listOf(1), tokeniser.boundaryPositions(tokenised, after = 0, limit = 10))
    }

    @Test
    fun `a boundary at the very end of the prompt is not returned`() {
        // Nothing would be left to evaluate after going back to it, and sampling needs an evaluation.
        val prompt = Prompt(
            segments = listOf(PromptSegment.Text("ab"), PromptSegment.Text("")),
            boundaries = listOf(PromptBoundary(0), PromptBoundary(1)),
        )
        val tokenised = tokeniser.tokenise(prompt)
        assertTrue(tokeniser.boundaryPositions(tokenised, after = 0, limit = 10).isEmpty())
    }

    @Test
    fun `the token list keeps what it is told to and wipes the rest`() {
        val list = TokenList()
        list.addAll(IntArray(600) { it + 1 })
        assertEquals(600, list.size)
        assertEquals(600, list[599])
        assertEquals(3, list.commonPrefixWith(intArrayOf(1, 2, 3, 99, 5)))
        assertEquals(2, list.commonPrefixWith(intArrayOf(1, 2)))
        assertEquals(0, list.commonPrefixWith(intArrayOf()))
        list.truncate(2)
        assertEquals(2, list.size)
        list.add(42)
        list.addAll(intArrayOf(7, 8, 9), count = 2)
        assertEquals(listOf(1, 2, 42, 7, 8), (0 until list.size).map { list[it] })
        list.wipe()
        assertEquals(0, list.size)
        assertEquals(0, list.commonPrefixWith(intArrayOf(1)))
    }

    /** The security review's C3: an array the list has outgrown is wiped before it is let go of. */
    @Test
    fun `every array the token list outgrows is wiped`() {
        val dropped = ArrayList<IntArray>()
        val list = TokenList(onDropped = { dropped += it })
        list.addAll(IntArray(256) { it + 1 })
        assertEquals("nothing outgrown yet", 0, dropped.size)
        list.addAll(IntArray(2_000) { it + 1_000 })
        assertEquals("256 to 512 to 1024 to 2048 to 4096", 4, dropped.size)
        assertEquals(listOf(256, 512, 1024, 2048), dropped.map { it.size })
        for (array in dropped) assertTrue("a dropped array still holds token ids", array.all { it == 0 })
        assertEquals(2_256, list.size)
        assertEquals(1, list[0])
        assertEquals(2_999, list[2_255])
    }

    @Test
    fun `a prompt is within the tokenizer's limit when every text is, counted in UTF-8`() {
        assertEquals(0, PromptTokeniser.utf8Length(""))
        assertEquals(1 + 2 + 3 + 4, PromptTokeniser.utf8Length("a\u00e9\u20ac\uD83D\uDE00"))
        assertEquals("an unpaired surrogate is encoded as a question mark", 1, PromptTokeniser.utf8Length("\uD83D"))
        for (text in listOf("plain", "\u00e9t\u00e9", "\uD83D\uDE00 x", "a\uDE00b", "\uD83D", "x\uD83Dy\uD83D")) {
            assertEquals(text, text.toByteArray(Charsets.UTF_8).size, PromptTokeniser.utf8Length(text))
        }

        val most = NativeLlama.MAX_TOKENIZE_BYTES
        val atTheLimit = "\u00e9".repeat(most / 2)
        assertTrue(PromptTokeniser.withinLimit(Prompt(listOf(PromptSegment.Text(atTheLimit), PromptSegment.Special(atTheLimit), PromptSegment.TokenId(1)))))
        assertFalse(PromptTokeniser.withinLimit(Prompt(listOf(PromptSegment.Text("a"), PromptSegment.Text(atTheLimit + "a")))))
        assertFalse(PromptTokeniser.withinLimit(Prompt(listOf(PromptSegment.Special("x".repeat(most + 1))))))
    }
}
