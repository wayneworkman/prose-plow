package us.theworkmans.proseplow.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import us.theworkmans.proseplow.core.prompt.Prompt
import us.theworkmans.proseplow.core.prompt.PromptMessage
import us.theworkmans.proseplow.core.prompt.PromptRole

class ContextBudgetTest {

    private fun user(text: String = "u") = PromptMessage(PromptRole.USER, text)
    private fun assistant(text: String = "a") = PromptMessage(PromptRole.ASSISTANT, text)

    /** A stand-in prompt of [size] tokens. */
    private fun prompt(size: Int) = TokenisedPrompt(Prompt(emptyList()), IntArray(size), IntArray(1))

    /**
     * Runs the budget over a conversation in which every message costs [perMessage] tokens and
     * the fixed parts (system prompt, generation prompt) cost [overhead].
     */
    private fun fit(
        messages: List<PromptMessage>,
        contextSize: Int,
        reserve: Int,
        minimum: Int = 1,
        perMessage: Int = 10,
        overhead: Int = 5,
        rendered: MutableList<Int> = ArrayList(),
    ): ContextBudget.Result = ContextBudget.fit(
        messageCount = messages.size,
        starts = ContextBudget.startCandidates(messages),
        contextSize = contextSize,
        reserve = reserve,
        minimum = minimum,
    ) { start ->
        rendered += start
        prompt(overhead + (messages.size - start) * perMessage)
    }

    private val sixTurns = listOf(user(), assistant(), user(), assistant(), user(), assistant(), user(), assistant(), user(), assistant(), user())

    @Test
    fun `a conversation may begin at its first message or at any later user message`() {
        assertEquals(listOf(0, 2, 4), ContextBudget.startCandidates(listOf(user(), assistant(), user(), assistant(), user())))
        // Two user messages in a row are two turns; a leading assistant message belongs to the first.
        assertEquals(listOf(0, 1, 2), ContextBudget.startCandidates(listOf(assistant(), user(), user())))
        assertEquals(listOf(0), ContextBudget.startCandidates(listOf(user())))
        // The partial answer being continued stays with the last user message.
        assertEquals(listOf(0, 2), ContextBudget.startCandidates(listOf(user(), assistant(), user(), assistant())))
    }

    @Test
    fun `everything is sent when it fits, with a single rendering`() {
        val rendered = ArrayList<Int>()
        val result = fit(sixTurns, contextSize = 200, reserve = 50, rendered = rendered) as ContextBudget.Result.Fits
        assertEquals(0, result.droppedMessages)
        assertEquals(115, result.prompt.tokens.size)
        assertEquals(listOf(0), rendered)
    }

    @Test
    fun `exactly filling the context still fits`() {
        val result = fit(sixTurns, contextSize = 165, reserve = 50) as ContextBudget.Result.Fits
        assertEquals(0, result.droppedMessages)
    }

    @Test
    fun `the oldest whole turns are dropped, and no more than needed`() {
        // 11 messages cost 115; with 50 reserved a context of 140 has room for 90: eight
        // messages (85). Eight would start at an assistant message, so one more goes: start at 4.
        val result = fit(sixTurns, contextSize = 140, reserve = 50) as ContextBudget.Result.Fits
        assertEquals(4, result.droppedMessages)
        assertEquals(75, result.prompt.tokens.size)
    }

    @Test
    fun `one token over the limit drops exactly one turn`() {
        val result = fit(sixTurns, contextSize = 164, reserve = 50) as ContextBudget.Result.Fits
        assertEquals(2, result.droppedMessages)
    }

    @Test
    fun `the latest user message is always kept`() {
        // Room for one message only.
        val result = fit(sixTurns, contextSize = 70, reserve = 50) as ContextBudget.Result.Fits
        assertEquals(10, result.droppedMessages)
        assertEquals(15, result.prompt.tokens.size)
    }

    @Test
    fun `when the last turn alone exceeds the reserve the reply gets what room is left`() {
        // The last turn costs 15; 15 + 50 does not fit in 40, but a reply of at least 8 does.
        val result = fit(sixTurns, contextSize = 40, reserve = 50, minimum = 8) as ContextBudget.Result.Fits
        assertEquals(10, result.droppedMessages)
        assertEquals(15, result.prompt.tokens.size)
    }

    @Test
    fun `a last turn that leaves no room for a reply is too long`() {
        val result = fit(sixTurns, contextSize = 22, reserve = 50, minimum = 8) as ContextBudget.Result.TooLong
        assertEquals(15, result.promptTokens)
        val single = fit(listOf(user()), contextSize = 15, reserve = 4, minimum = 1) as ContextBudget.Result.TooLong
        assertEquals(15, single.promptTokens)
    }

    @Test
    fun `a single message is sent as long as the minimum fits`() {
        val result = fit(listOf(user()), contextSize = 20, reserve = 10, minimum = 5) as ContextBudget.Result.Fits
        assertEquals(0, result.droppedMessages)
    }

    @Test
    fun `a long conversation is rendered a logarithmic number of times`() {
        val long = (0 until 400).flatMap { listOf(user(), assistant()) } + user()
        val rendered = ArrayList<Int>()
        val result = fit(long, contextSize = 500, reserve = 100, rendered = rendered) as ContextBudget.Result.Fits
        // 39 messages fit (395 tokens); the first start at or after 801 - 39 = 762 is 762.
        assertEquals(762, result.droppedMessages)
        assertTrue("rendered ${rendered.size} times", rendered.size <= 11)
    }

    @Test
    fun `the result is the same as trying every start in order`() {
        val conversation = (0 until 30).flatMap { listOf(user(), assistant()) } + user()
        val starts = ContextBudget.startCandidates(conversation)
        for (contextSize in 30..700 step 7) {
            val reserve = 20
            val expected = starts.firstOrNull { start -> 5 + (conversation.size - start) * 10 + reserve <= contextSize }
            when (val result = fit(conversation, contextSize = contextSize, reserve = reserve, minimum = 1)) {
                is ContextBudget.Result.Fits -> assertEquals("context $contextSize", expected ?: starts.last(), result.droppedMessages)
                is ContextBudget.Result.TooLong -> assertEquals("context $contextSize", null, expected)
            }
        }
    }

    @Test
    fun `start candidates that are not a start list are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ContextBudget.fit(3, listOf(1, 2), 100, 10, 1) { prompt(1) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ContextBudget.fit(3, emptyList(), 100, 10, 1) { prompt(1) }
        }
    }
}
