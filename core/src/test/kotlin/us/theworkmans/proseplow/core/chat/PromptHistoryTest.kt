package us.theworkmans.proseplow.core.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import us.theworkmans.proseplow.core.engine.EngineError
import us.theworkmans.proseplow.core.engine.FinishReason
import us.theworkmans.proseplow.core.engine.GenerationEvent
import us.theworkmans.proseplow.core.engine.GenerationStats
import us.theworkmans.proseplow.core.model.ModelCatalog
import us.theworkmans.proseplow.core.model.SamplingParams
import us.theworkmans.proseplow.core.prompt.PromptMessage
import us.theworkmans.proseplow.core.prompt.PromptRole

class PromptHistoryTest {

    private val clock = FakeClock()
    private val ids = SequentialIds()
    private val stats = GenerationStats(promptTokens = 1, reusedPromptTokens = 0, generatedTokens = 1, promptMillis = 1, generationMillis = 1)

    private fun user(text: String) = PromptMessage(PromptRole.USER, text)
    private fun assistant(text: String) = PromptMessage(PromptRole.ASSISTANT, text)

    private fun newSession() = ChatSession.create("model-a", clock, SequentialIds("s"))

    private fun ChatSession.answered(text: String): ChatSession {
        val id = checkNotNull(streamingMessageId)
        return applyEvent(id, GenerationEvent.TextDelta(text), clock)
            .applyEvent(id, GenerationEvent.Done(FinishReason.END_OF_TURN, text = text, stats = stats), clock)
    }

    @Test
    fun `the history ends with the question and leaves out the empty placeholder`() {
        val session = newSession().withUserTurn("Hello", clock, ids).answered("Hi").withUserTurn("How are you?", clock, ids)
        val history = session.promptHistory()
        assertEquals(listOf(user("Hello"), assistant("Hi"), user("How are you?")), history.messages)
        assertFalse(history.continueFinalMessage)
    }

    @Test
    fun `a reply stopped before any text is not sent`() {
        val session = newSession().withUserTurn("First", clock, ids)
        val stopped = session.stopped(checkNotNull(session.streamingMessageId), clock).withUserTurn("Second", clock, ids)
        // Two user messages in a row: every bundled model's format accepts that.
        assertEquals(listOf(user("First"), user("Second")), stopped.promptHistory().messages)
    }

    @Test
    fun `a reply that failed before any text is not sent, one that failed with text is`() {
        val failedEmpty = newSession().withUserTurn("First", clock, ids).let { it.failed(it.streamingMessageId, EngineError.NotLoaded, clock) }
        assertEquals(listOf(user("First"), user("Second")), failedEmpty.withUserTurn("Second", clock, ids).promptHistory().messages)

        val started = newSession().withUserTurn("First", clock, SequentialIds("x"))
        val failedPartial = started.applyEvent("x2", GenerationEvent.TextDelta("Partial ans"), clock)
            .failed("x2", EngineError.Native(5, "decode"), clock)
            .withUserTurn("Second", clock, SequentialIds("y"))
        assertEquals(listOf(user("First"), assistant("Partial ans"), user("Second")), failedPartial.promptHistory().messages)
    }

    @Test
    fun `an answer of only whitespace counts as no answer`() {
        val session = newSession().withUserTurn("First", clock, ids).answered(" \n\u00A0").withUserTurn("Second", clock, ids)
        assertEquals(listOf(user("First"), user("Second")), session.promptHistory().messages)
    }

    @Test
    fun `a stopped reply with text is part of the conversation`() {
        val started = newSession().withUserTurn("First", clock, ids)
        val stopped = started.applyEvent("m2", GenerationEvent.TextDelta("Half an ans"), clock).stopped("m2", clock)
        assertEquals(
            listOf(user("First"), assistant("Half an ans"), user("Second")),
            stopped.withUserTurn("Second", clock, ids).promptHistory().messages,
        )
    }

    @Test
    fun `text is trimmed as the models' templates trim it`() {
        // A reply cut off by the token limit keeps the whitespace it ended with in the session.
        val session = newSession()
            .withUserTurn("\n  padded question \u0085", clock, ids)
            .applyEvent("m2", GenerationEvent.TextDelta("padded answer \n"), clock)
            .finished("m2", FinishReason.LENGTH, clock)
        assertEquals("padded answer \n", session.messages[1].text)
        assertEquals(
            listOf(user("padded question"), assistant("padded answer"), user("next")),
            session.withUserTurn("next ", clock, ids).promptHistory().messages,
        )
    }

    @Test
    fun `a reopened answer is sent last as the message to continue, with its end untouched`() {
        val started = newSession().withUserTurn("Count to five.", clock, ids)
        val stopped = started.applyEvent("m2", GenerationEvent.TextDelta("  One, two, \n"), clock).stopped("m2", clock)
        val reopened = checkNotNull(stopped.forContinuation("m2", clock))
        val history = reopened.promptHistory()
        assertTrue(history.continueFinalMessage)
        assertEquals(listOf(user("Count to five."), assistant("One, two, \n")), history.messages)
    }

    @Test
    fun `for a model that continues text nothing is trimmed from any message`() {
        val session = newSession()
            .withUserTurn("  Once upon a time \n", clock, ids)
            .applyEvent("m2", GenerationEvent.TextDelta(" there was \n"), clock)
            .finished("m2", FinishReason.LENGTH, clock)
            .withUserTurn("Dear Sir, ", clock, ids)
        assertEquals(
            listOf(user("  Once upon a time \n"), assistant(" there was \n"), user("Dear Sir, ")),
            session.promptHistory(exactText = true).messages,
        )
        // The same chat as a chat model is to see it.
        assertEquals(
            listOf(user("Once upon a time"), assistant("there was"), user("Dear Sir,")),
            session.promptHistory().messages,
        )
    }

    @Test
    fun `a continued message of a model that continues text keeps its leading whitespace too`() {
        val stopped = newSession().withUserTurn("The capital of France is", clock, ids)
            .applyEvent("m2", GenerationEvent.TextDelta(" Nice. "), clock)
            .stopped("m2", clock)
        val reopened = checkNotNull(stopped.forContinuation("m2", clock))
        val exact = reopened.promptHistory(exactText = true)
        assertTrue(exact.continueFinalMessage)
        assertEquals(listOf(user("The capital of France is"), assistant(" Nice. ")), exact.messages)
        // A chat model's continued answer loses the whitespace at its start, as before.
        assertEquals(assistant("Nice. "), reopened.promptHistory().messages.last())
    }

    @Test
    fun `the request takes the text rules from the model it is for`() {
        val session = newSession().withUserTurn(" A trailing space matters ", clock, ids)
        assertEquals(
            listOf(user(" A trailing space matters ")),
            session.generationRequest(ModelCatalog.SHARD_2, systemPrompt = null).messages,
        )
        for (chatModel in listOf(ModelCatalog.QWEN35_0_8B, ModelCatalog.PEACEBELL_V1_291M, ModelCatalog.BORIS_2_INSTRUCT)) {
            assertEquals(chatModel.id, listOf(user("A trailing space matters")), session.generationRequest(chatModel, systemPrompt = null).messages)
        }
    }

    @Test
    fun `an empty conversation has an empty history`() {
        val history = newSession().promptHistory()
        assertTrue(history.messages.isEmpty())
        assertFalse(history.continueFinalMessage)
    }

    @Test
    fun `the request is addressed to the model stamped on the reply and carries the settings`() {
        val sampling = SamplingParams(temperature = 0.5f)
        val session = newSession().withUserTurn("Hello", clock, ids)
        val request = session.withModel("model-b")
            .generationRequest(ModelCatalog.QWEN35_0_8B, systemPrompt = "Be brief.", sampling = sampling, maxNewTokens = 99)
        assertEquals("the reply was started for model-a", "model-a", request.modelId)
        assertEquals(listOf(user("Hello")), request.messages)
        assertEquals("Be brief.", request.systemPrompt)
        assertNull(request.extraSystemText)
        assertFalse(request.continueFinalMessage)
        assertEquals(sampling, request.sampling)
        assertEquals(99, request.maxNewTokens)
        assertTrue(request.stopStrings.isEmpty())
    }

    @Test
    fun `the request for a continuation says so`() {
        val started = newSession().withUserTurn("Count", clock, ids)
        val stopped = started.applyEvent("m2", GenerationEvent.TextDelta("One"), clock).stopped("m2", clock)
        val request = checkNotNull(stopped.forContinuation("m2", clock)).generationRequest(ModelCatalog.QWEN35_0_8B, systemPrompt = null)
        assertTrue(request.continueFinalMessage)
        assertEquals(listOf(user("Count"), assistant("One")), request.messages)
        assertEquals("model-a", request.modelId)
    }
}
