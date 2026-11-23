package us.theworkmans.proseplow.core.engine

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import us.theworkmans.proseplow.core.engine.FakeNativeLlama.Call
import us.theworkmans.proseplow.core.model.ModelFamily
import us.theworkmans.proseplow.core.model.ReplyLength
import us.theworkmans.proseplow.core.model.replyLengthRange
import us.theworkmans.proseplow.core.prompt.PlainChatMlFormat
import us.theworkmans.proseplow.core.prompt.PromptInput
import us.theworkmans.proseplow.core.prompt.PromptMessage
import us.theworkmans.proseplow.core.prompt.PromptRole

/**
 * The context budget as the engine applies it. The arithmetic itself is tested in
 * [ContextBudgetTest]; here the point is what reaches the model and what is reported.
 */
class LlamaEngineBudgetTest {

    private val system = "You answer in one word."

    private fun prompt(messages: List<PromptMessage>, systemPrompt: String? = system): String =
        PlainChatMlFormat.render(PromptInput(messages, systemPrompt = systemPrompt)).text()

    /** Six finished turns and a seventh question, about forty characters a message. */
    private val conversation: List<PromptMessage> = (1..6).flatMap { turn ->
        listOf(user("Question number $turn, padded to some length."), assistant("Answer number $turn, also of some length."))
    } + user("The latest question, which must always be sent.")

    @Test
    fun `a conversation that fits is sent whole and nothing is reported`() = runTest {
        val test = loadedEngine(testModel(contextLength = 2000, maxNewTokens = 50))
        val events = test.engine.generate(request(*conversation.toTypedArray(), systemPrompt = system)).toList()
        assertTrue(events.none { it is GenerationEvent.HistoryTrimmed })
        assertEquals(0, events.done().stats.droppedMessages)
        assertEquals(prompt(conversation), test.fake.memoryText)
    }

    @Test
    fun `the oldest whole turns are dropped when the conversation does not fit, and that is reported`() = runTest {
        val contextLength = 600
        val reserve = 50
        val test = loadedEngine(testModel(contextLength = contextLength, maxNewTokens = reserve))
        test.fake.enqueueReply("Yes.")
        val events = test.engine.generate(request(*conversation.toTypedArray(), systemPrompt = system)).toList()

        // What the rule demands, worked out the slow way: the earliest start at a user message
        // from which the prompt and the reserve fit.
        val expectedStart = conversation.indices.first { start ->
            (start == 0 || conversation[start].role == PromptRole.USER) &&
                prompt(conversation.drop(start)).length + reserve <= contextLength
        }
        assertTrue("the test must actually drop something", expectedStart > 0)
        assertTrue("and keep more than the last turn", expectedStart < conversation.lastIndex)

        assertEquals(
            GenerationEvent.HistoryTrimmed(droppedMessages = expectedStart, keptMessages = conversation.size - expectedStart),
            events.first(),
        )
        assertEquals(expectedStart, events.done().stats.droppedMessages)
        assertEquals("Yes.", events.done().text)

        val sent = test.fake.memoryText
        assertEquals(prompt(conversation.drop(expectedStart)) + "Yes.", sent)
        assertTrue("the system prompt is always kept", sent.startsWith("<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n"))
        assertTrue("the latest user message is always kept", "The latest question, which must always be sent." in sent)
        assertFalse("the first turn is gone", "Question number 1," in sent)
        assertTrue("the memory never exceeded the context", test.fake.memory.size <= contextLength)
    }

    @Test
    fun `the request's reply length decides how much history has to go`() = runTest {
        val test = loadedEngine(testModel(contextLength = 600, maxNewTokens = 50))
        val modest = test.engine.generate(request(*conversation.toTypedArray(), systemPrompt = system, maxNewTokens = 20)).toList()
        val generous = test.engine.generate(request(*conversation.toTypedArray(), systemPrompt = system, maxNewTokens = 250)).toList()
        assertTrue(generous.done().stats.droppedMessages > modest.done().stats.droppedMessages)
    }

    @Test
    fun `the reserve for a reply never claims more than half of the context`() = runTest {
        // A reply length as large as the context would otherwise leave no room for any prompt.
        val test = loadedEngine(testModel(contextLength = 600, maxNewTokens = 600))
        val events = test.engine.generate(request(*conversation.toTypedArray(), systemPrompt = system)).toList()
        val kept = conversation.size - events.done().stats.droppedMessages
        assertTrue("some history survives", kept > 1)
        assertTrue(events.done().stats.promptTokens <= 300)
    }

    /**
     * The longest reply a user can ask for is half of the context ([ReplyLength]). The engine
     * keeps exactly that much free, so the conversation still has the other half: the latest
     * question is sent, with as much before it as fits, and the reply can really become as long
     * as its limit instead of running into the end of the context.
     */
    @Test
    fun `a reply limit at the model's maximum leaves a workable prompt and can be reached`() = runTest {
        val model = testModel(contextLength = 600)
        val limit = model.replyLengthRange.last
        assertEquals(300, limit)
        val test = loadedEngine(model)
        test.fake.enqueueReply("y".repeat(1_000))

        val events = test.engine.generate(request(*conversation.toTypedArray(), systemPrompt = system, maxNewTokens = limit)).toList()

        val done = events.done()
        assertTrue("the latest question is sent", test.fake.memoryText.contains("The latest question, which must always be sent."))
        assertTrue("with the system prompt", test.fake.memoryText.contains(system))
        assertTrue("in the half that is the conversation's: ${done.stats.promptTokens}", done.stats.promptTokens in 1..(600 - limit))
        assertEquals("the reply ends at its limit, not at the end of the context", FinishReason.LENGTH, done.finishReason)
        assertEquals(limit, done.stats.generatedTokens)
        assertTrue(test.fake.memory.size <= 600)
    }

    @Test
    fun `with only the last turn left the reply gets whatever room remains`() = runTest {
        val question = "A single question that is long enough to take up most of a very small context window by itself."
        val alone = prompt(listOf(user(question)), systemPrompt = null)
        // The prompt and the wanted reply do not fit together; the prompt and a short reply do.
        val contextLength = alone.length + 20
        val test = loadedEngine(testModel(contextLength = contextLength, maxNewTokens = 60))
        test.fake.enqueueReply("x".repeat(100))
        val events = test.engine.generate(request(user("Earlier question"), assistant("Earlier answer"), user(question))).toList()

        assertEquals(GenerationEvent.HistoryTrimmed(droppedMessages = 2, keptMessages = 1), events.first())
        val done = events.done()
        assertEquals(FinishReason.LENGTH, done.finishReason)
        assertEquals("the reply stops where the context ends", 20, done.stats.generatedTokens)
        assertTrue(test.fake.memory.size < contextLength)
    }

    @Test
    fun `a last message that leaves no room for a reply is too long`() = runTest {
        val question = "A question that cannot fit. ".repeat(20)
        val test = loadedEngine(testModel(contextLength = 300, maxNewTokens = 50))
        val events = test.engine.generate(request(user("Earlier"), assistant("Answer"), user(question), systemPrompt = system)).toList()

        val shortest = prompt(listOf(user(question))).length
        assertEquals(EngineError.PromptTooLong(promptTokens = shortest, contextSize = 300), events.error())
        assertTrue("nothing was evaluated", test.fake.callsOf<Call.Evaluate>().isEmpty())
        assertEquals(1, events.size)
    }

    @Test
    fun `a partial answer being continued stays with the last user message`() = runTest {
        val messages = conversation + assistant("The partial answer")
        val test = loadedEngine(testModel(contextLength = 400, maxNewTokens = 50))
        test.fake.enqueueReply(" goes on.")
        val events = test.engine.generate(request(*messages.toTypedArray(), systemPrompt = system, continueFinalMessage = true)).toList()
        assertTrue(events.done().stats.droppedMessages > 0)
        assertTrue(test.fake.memoryText.endsWith("<|im_start|>assistant\nThe partial answer goes on."))
        assertTrue("The latest question, which must always be sent." in test.fake.memoryText)
    }

    @Test
    fun `dropping turns costs few renderings even for a long conversation`() = runTest {
        val long = (1..200).flatMap { turn -> listOf(user("Question $turn"), assistant("Answer $turn")) } + user("Last")
        val test = loadedEngine(testModel(contextLength = 800, maxNewTokens = 50))
        val events = test.engine.generate(request(*long.toTypedArray())).toList()
        assertTrue(events.done().stats.droppedMessages > 300)
        // One tokenizer call per rendering (the prompt is a single segment), plus one for each
        // checkpoint boundary inside it.
        val renderings = test.fake.callsOf<Call.Tokenize>().size
        assertTrue("tokenised $renderings times", renderings <= 16)
    }

    @Test
    fun `the budget counts tokens, not characters`() = runTest {
        // In the Qwen layout the markers are single tokens, so the same text needs fewer tokens
        // than in the plain layout, and more history fits.
        val plain = loadedEngine(testModel(family = ModelFamily.PLAIN_CHATML, contextLength = 500, maxNewTokens = 50))
        val qwen = loadedEngine(testModel(family = ModelFamily.QWEN35_CHATML, contextLength = 500, maxNewTokens = 50))
        val plainDropped = plain.engine.generate(request(*conversation.toTypedArray())).toList().done().stats.droppedMessages
        val qwenDropped = qwen.engine.generate(request(*conversation.toTypedArray())).toList().done().stats.droppedMessages
        assertTrue("plain dropped $plainDropped, Qwen $qwenDropped", qwenDropped < plainDropped)
    }
}
