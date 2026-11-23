package us.theworkmans.proseplow.core.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import us.theworkmans.proseplow.core.engine.EngineError
import us.theworkmans.proseplow.core.engine.FinishReason
import us.theworkmans.proseplow.core.engine.GenerationEvent
import us.theworkmans.proseplow.core.engine.GenerationStats

class ChatSessionTest {

    private val clock = FakeClock(now = 1_000)
    private val ids = SequentialIds()

    private val stats = GenerationStats(promptTokens = 10, reusedPromptTokens = 0, generatedTokens = 3, promptMillis = 5, generationMillis = 7)

    private fun newSession(modelId: String = "model-a") = ChatSession.create(modelId, clock, SequentialIds("s"))

    /** A session with one finished exchange: user m1, assistant m2. */
    private fun oneExchange(): ChatSession = newSession().withUserTurn("Hello", clock, ids).answered("m2", "Hi there")

    private fun ChatSession.answered(assistantId: String, text: String): ChatSession =
        applyEvent(assistantId, GenerationEvent.TextDelta(text), clock)
            .applyEvent(assistantId, GenerationEvent.Done(FinishReason.END_OF_TURN, text = text, stats = stats), clock)

    // --- Creating and sending ---

    @Test
    fun `a new session is empty and carries the injected time and id`() {
        val session = newSession()
        assertEquals("s1", session.id)
        assertEquals(1_000L, session.createdAtMillis)
        assertEquals(1_000L, session.updatedAtMillis)
        assertEquals("model-a", session.modelId)
        assertEquals("", session.title)
        assertTrue(session.messages.isEmpty())
        assertFalse(session.isGenerating)
        assertNull(session.streamingMessageId)
    }

    @Test
    fun `a user turn adds the message and a streaming placeholder stamped with the model`() {
        val created = newSession()
        clock.advance(500)
        val session = created.withDraft("Hello").withUserTurn("Hello", clock, ids)
        assertEquals(2, session.messages.size)
        val (user, placeholder) = session.messages
        assertEquals(ChatMessage(id = "m1", role = Role.USER, text = "Hello", createdAtMillis = 1_500), user)
        assertEquals(
            ChatMessage(id = "m2", role = Role.ASSISTANT, status = MessageStatus.STREAMING, modelId = "model-a", createdAtMillis = 1_500),
            placeholder,
        )
        assertEquals("the draft was sent", "", session.draft)
        assertEquals(1_500L, session.updatedAtMillis)
        assertEquals(1_000L, session.createdAtMillis)
        assertTrue(session.isGenerating)
        assertEquals("m2", session.streamingMessageId)
    }

    @Test
    fun `sending clears the error of the turn before`() {
        val failed = newSession().withUserTurn("first", clock, ids).failed("m2", EngineError.NotLoaded, clock)
        assertEquals(ChatErrorKind.MODEL_NOT_LOADED, failed.error?.kind)
        assertNull(failed.withUserMessage("second", clock, ids).error)
    }

    @Test
    fun `typing a draft or switching the model does not count as a change of the conversation`() {
        val session = oneExchange()
        clock.advance(10_000)
        assertEquals(session.updatedAtMillis, session.withDraft("typing").updatedAtMillis)
        assertEquals(session.updatedAtMillis, session.withModel("model-b").updatedAtMillis)
        assertEquals("typing", session.withDraft("typing").draft)
    }

    // --- Generation events ---

    @Test
    fun `text deltas append to the streaming reply and leave other messages untouched`() {
        val started = newSession().withUserTurn("Hello", clock, ids)
        val streamed = started
            .applyEvent("m2", GenerationEvent.TextDelta("Hi"), clock)
            .applyEvent("m2", GenerationEvent.TextDelta(" there"), clock)
        assertEquals("Hi there", streamed.messages[1].text)
        assertEquals(MessageStatus.STREAMING, streamed.messages[1].status)
        assertSame("unchanged messages keep their identity", started.messages[0], streamed.messages[0])
    }

    @Test
    fun `progress events change nothing`() {
        val started = newSession().withUserTurn("Hello", clock, ids)
        assertSame(started, started.applyEvent("m2", GenerationEvent.PromptProgress(3, 10), clock))
        assertSame(started, started.applyEvent("m2", GenerationEvent.HistoryTrimmed(2, 5), clock))
    }

    @Test
    fun `the end of turn completes the reply and trims the whitespace at its end`() {
        val started = newSession().withUserTurn("Hello", clock, ids)
        clock.advance(1_000)
        val done = started
            .applyEvent("m2", GenerationEvent.TextDelta("Hi there.\n\n"), clock)
            .applyEvent("m2", GenerationEvent.Done(FinishReason.END_OF_TURN, text = "Hi there.\n\n", stats = stats), clock)
        val reply = done.messages[1]
        assertEquals("Hi there.", reply.text)
        assertEquals(MessageStatus.COMPLETE, reply.status)
        assertEquals(FinishReason.END_OF_TURN, reply.finishReason)
        assertEquals("model-a", reply.modelId)
        assertFalse(done.isGenerating)
        assertEquals(2_000L, done.updatedAtMillis)
    }

    @Test
    fun `a stop string completes the reply too`() {
        val done = newSession().withUserTurn("Hello", clock, ids)
            .applyEvent("m2", GenerationEvent.TextDelta("4. "), clock)
            .applyEvent("m2", GenerationEvent.Done(FinishReason.STOP_STRING, stopString = "<|im_end|>", text = "4. ", stats = stats), clock)
        assertEquals("4.", done.messages[1].text)
        assertEquals(MessageStatus.COMPLETE, done.messages[1].status)
        assertEquals(FinishReason.STOP_STRING, done.messages[1].finishReason)
    }

    @Test
    fun `a reply cut off by the token limit is complete but keeps its text exactly`() {
        val done = newSession().withUserTurn("Hello", clock, ids)
            .applyEvent("m2", GenerationEvent.TextDelta("and then \n"), clock)
            .applyEvent("m2", GenerationEvent.Done(FinishReason.LENGTH, text = "and then \n", stats = stats), clock)
        assertEquals("and then \n", done.messages[1].text)
        assertEquals(MessageStatus.COMPLETE, done.messages[1].status)
        assertEquals(FinishReason.LENGTH, done.messages[1].finishReason)
    }

    @Test
    fun `stopping keeps the partial text and marks the reply as stopped`() {
        val stopped = newSession().withUserTurn("Hello", clock, ids)
            .applyEvent("m2", GenerationEvent.TextDelta("Hi the "), clock)
            .stopped("m2", clock)
        assertEquals("Hi the ", stopped.messages[1].text)
        assertEquals(MessageStatus.STOPPED, stopped.messages[1].status)
        assertEquals(FinishReason.CANCELLED, stopped.messages[1].finishReason)
        assertFalse(stopped.isGenerating)
    }

    @Test
    fun `a generation the engine cancelled is a stopped reply as well`() {
        val stopped = newSession().withUserTurn("Hello", clock, ids)
            .applyEvent("m2", GenerationEvent.TextDelta("Hi"), clock)
            .applyEvent("m2", GenerationEvent.Done(FinishReason.CANCELLED, text = "Hi", stats = stats), clock)
        assertEquals(MessageStatus.STOPPED, stopped.messages[1].status)
    }

    @Test
    fun `events that arrive after a reply was stopped are ignored`() {
        val stopped = newSession().withUserTurn("Hello", clock, ids)
            .applyEvent("m2", GenerationEvent.TextDelta("Hi"), clock)
            .stopped("m2", clock)
        assertSame(stopped, stopped.applyEvent("m2", GenerationEvent.TextDelta(" late"), clock))
        assertSame(stopped, stopped.applyEvent("m2", GenerationEvent.Done(FinishReason.END_OF_TURN, stats = stats), clock))
        assertSame(stopped, stopped.applyEvent("no-such-message", GenerationEvent.TextDelta("x"), clock))
    }

    // --- Text the model takes back ---

    @Test
    fun `text the model takes back disappears from the streaming reply and later text follows what is left`() {
        val started = newSession().withUserTurn("The capital of France is", clock, ids)
        val written = started
            .applyEvent("m2", GenerationEvent.TextDelta(" Nice."), clock)
            .applyEvent("m2", GenerationEvent.TextDelta(" 198"), clock)
        assertEquals(" Nice. 198", written.messages[1].text)

        val retracted = written.applyEvent("m2", GenerationEvent.TextReplaced(" Nice.", removed = 4), clock)
        assertEquals(" Nice.", retracted.messages[1].text)
        assertEquals(MessageStatus.STREAMING, retracted.messages[1].status)
        assertSame("the question is untouched", started.messages[0], retracted.messages[0])

        val carriedOn = retracted.applyEvent("m2", GenerationEvent.TextDelta(" However,"), clock)
        assertEquals(" Nice. However,", carriedOn.messages[1].text)
    }

    @Test
    fun `a reply can be taken back to nothing`() {
        val emptied = newSession().withUserTurn("Hello", clock, ids)
            .applyEvent("m2", GenerationEvent.TextDelta("Hm"), clock)
            .applyEvent("m2", GenerationEvent.TextReplaced("", removed = 2), clock)
        assertEquals("", emptied.messages[1].text)
        assertEquals(MessageStatus.STREAMING, emptied.messages[1].status)
    }

    @Test
    fun `text taken back from a continued message never reaches what was there before`() {
        // The answer had " Nice." when it was stopped. The continuation writes " 198" and takes
        // it back: the event speaks of the continuation only, and the earlier text stays.
        val stopped = newSession().withUserTurn("The capital of France is", clock, ids)
            .applyEvent("m2", GenerationEvent.TextDelta(" Nice."), clock)
            .stopped("m2", clock)
        val continued = checkNotNull(stopped.forContinuation("m2", clock))
            .applyEvent("m2", GenerationEvent.TextDelta(" 198"), clock)
            .applyEvent("m2", GenerationEvent.TextReplaced("", removed = 4), clock)
        assertEquals(" Nice.", continued.messages[1].text)
        val done = continued
            .applyEvent("m2", GenerationEvent.TextDelta(" However,"), clock)
            .applyEvent("m2", GenerationEvent.Done(FinishReason.LENGTH, text = " However,", stats = stats, exactText = true), clock)
        assertEquals(" Nice. However,", done.messages[1].text)
    }

    @Test
    fun `taking text back does not count as a change of the conversation and is ignored once the reply has ended`() {
        val written = newSession().withUserTurn("Hello", clock, ids).applyEvent("m2", GenerationEvent.TextDelta("Hi there"), clock)
        clock.advance(10_000)
        val retracted = written.applyEvent("m2", GenerationEvent.TextReplaced("Hi", removed = 6), clock)
        assertEquals("like appended text, it leaves the time of the last change alone", written.updatedAtMillis, retracted.updatedAtMillis)

        val stopped = written.stopped("m2", clock)
        assertSame(stopped, stopped.applyEvent("m2", GenerationEvent.TextReplaced("Hi", removed = 6), clock))
        assertSame(written, written.applyEvent("no-such-message", GenerationEvent.TextReplaced("Hi", removed = 6), clock))
        assertSame("an event that takes nothing back changes nothing", written, written.applyEvent("m2", GenerationEvent.TextReplaced("Hi there", removed = 0), clock))
    }

    @Test
    fun `the reply of a model that continues text is kept exactly, however it ended`() {
        for (reason in listOf(FinishReason.END_OF_TURN, FinishReason.STOP_STRING, FinishReason.LENGTH, FinishReason.CANCELLED)) {
            val done = newSession().withUserTurn("Dear Sir,", clock, SequentialIds("x"))
                .applyEvent("x2", GenerationEvent.TextDelta("\n\n I write \n"), clock)
                .applyEvent("x2", GenerationEvent.Done(reason, text = "\n\n I write \n", stats = stats, exactText = true), clock)
            assertEquals(reason.name, "\n\n I write \n", done.messages[1].text)
            assertEquals(reason, done.messages[1].finishReason)
        }
        // The same ending without the mark is a chat model's, and loses the whitespace at its end.
        val chat = newSession().withUserTurn("Hello", clock, ids)
            .applyEvent("m2", GenerationEvent.TextDelta("Hi there \n"), clock)
            .applyEvent("m2", GenerationEvent.Done(FinishReason.END_OF_TURN, text = "Hi there \n", stats = stats), clock)
        assertEquals("Hi there", chat.messages[1].text)
    }

    // --- Failing ---

    @Test
    fun `a failure before any text removes the placeholder and records the error against the user message`() {
        val failed = newSession().withUserTurn("Hello", clock, ids)
            .applyEvent("m2", GenerationEvent.Error(EngineError.PromptTooLong(promptTokens = 5000, contextSize = 4096)), clock)
        assertEquals(listOf("m1"), failed.messages.map { it.id })
        assertEquals(
            ChatError(ChatErrorKind.PROMPT_TOO_LONG, userMessageId = "m1", promptTokens = 5000, contextSize = 4096),
            failed.error,
        )
        assertFalse(failed.isGenerating)
    }

    @Test
    fun `a failure after some text keeps the partial reply as stopped`() {
        val failed = newSession().withUserTurn("Hello", clock, ids)
            .applyEvent("m2", GenerationEvent.TextDelta("Hi th"), clock)
            .failed("m2", EngineError.Native(code = 5, detail = "decode failed"), clock)
        assertEquals("Hi th", failed.messages[1].text)
        assertEquals(MessageStatus.STOPPED, failed.messages[1].status)
        assertNull(failed.messages[1].finishReason)
        assertEquals(ChatError(ChatErrorKind.NATIVE, "code 5: decode failed", userMessageId = "m1"), failed.error)
    }

    @Test
    fun `every engine error has a stored kind`() {
        val errors = mapOf(
            EngineError.NotLoaded to ChatErrorKind.MODEL_NOT_LOADED,
            EngineError.WrongModel("a", "b") to ChatErrorKind.MODEL_NOT_LOADED,
            EngineError.LoadFailed("a", "missing file") to ChatErrorKind.MODEL_LOAD_FAILED,
            EngineError.InvalidRequest("no user message") to ChatErrorKind.INVALID_REQUEST,
            EngineError.PromptTooLong(10, 5) to ChatErrorKind.PROMPT_TOO_LONG,
            EngineError.ContextFull("no room") to ChatErrorKind.CONTEXT_FULL,
            EngineError.Native(5, "x") to ChatErrorKind.NATIVE,
            EngineError.Unexpected("IllegalStateException") to ChatErrorKind.UNEXPECTED,
        )
        for ((error, kind) in errors) {
            val stored = error.toChatError("m1")
            assertEquals(error.toString(), kind, stored.kind)
            assertEquals("m1", stored.userMessageId)
        }
    }

    // --- Retry ---

    @Test
    fun `retry sends the failed turn again without the error`() {
        val failed = newSession().withUserTurn("Hello", clock, ids).failed("m2", EngineError.NotLoaded, clock)
        assertEquals("m1", failed.retryTargetId())
        val retry = checkNotNull(failed.forRetry(clock))
        assertEquals(listOf("m1"), retry.messages.map { it.id })
        assertNull(retry.error)
    }

    @Test
    fun `a stopped reply can be retried even when it has text`() {
        val stopped = oneExchange().withUserTurn("More", clock, ids)
            .applyEvent("m4", GenerationEvent.TextDelta("partial"), clock)
            .stopped("m4", clock)
        assertEquals("m3", stopped.retryTargetId())
        assertEquals(listOf("m1", "m2", "m3"), checkNotNull(stopped.forRetry(clock)).messages.map { it.id })
    }

    @Test
    fun `a user message left without a reply can be retried`() {
        // The process died between saving the user message and starting the reply.
        val orphan = oneExchange().withUserMessage("More", clock, ids)
        assertEquals("m3", orphan.retryTargetId())
        assertEquals(orphan.messages, checkNotNull(orphan.forRetry(clock)).messages)
    }

    @Test
    fun `a reply that came out empty can be retried`() {
        val empty = newSession().withUserTurn("Hello", clock, ids)
            .applyEvent("m2", GenerationEvent.Done(FinishReason.END_OF_TURN, stats = stats), clock)
        assertEquals("m1", empty.retryTargetId())
    }

    @Test
    fun `there is nothing to retry after a finished answer, in an empty chat, or while generating`() {
        val answered = oneExchange()
        assertNull(answered.retryTargetId())
        assertNull(answered.forRetry(clock))
        assertNull(newSession().retryTargetId())
        assertNull(newSession().withUserTurn("Hello", clock, ids).retryTargetId())
    }

    @Test
    fun `discarding a failed turn removes its user message and the error`() {
        val answered = oneExchange()
        val failed = answered.withUserTurn("Second", clock, ids).failed("m4", EngineError.NotLoaded, clock)
        val discarded = failed.discardFailedTurn(clock)
        assertEquals(listOf("m1", "m2"), discarded.messages.map { it.id })
        assertNull(discarded.error)
        // Without an error there is nothing to discard.
        assertEquals(answered, answered.discardFailedTurn(clock))
    }

    // --- Edit ---

    @Test
    fun `editing a user message replaces its text and cuts everything after it`() {
        val session = oneExchange().withUserTurn("Second question", clock, ids).answered("m4", "Second answer")
        clock.advance(5_000)
        val edited = checkNotNull(session.editAndTruncate("m1", "Hello again", clock))
        assertEquals(1, edited.messages.size)
        assertEquals("Hello again", edited.messages[0].text)
        assertEquals("the message keeps its id", "m1", edited.messages[0].id)
        assertEquals("and its creation time", session.messages[0].createdAtMillis, edited.messages[0].createdAtMillis)
        assertEquals(clock.now, edited.updatedAtMillis)
        assertNull(edited.error)
    }

    @Test
    fun `editing a later message keeps what came before it as the same objects`() {
        val session = oneExchange().withUserTurn("Second question", clock, ids).answered("m4", "Second answer")
        val edited = checkNotNull(session.editAndTruncate("m3", "Another question", clock))
        assertEquals(listOf("m1", "m2", "m3"), edited.messages.map { it.id })
        assertSame(session.messages[0], edited.messages[0])
        assertSame(session.messages[1], edited.messages[1])
        assertEquals("Another question", edited.messages[2].text)
    }

    @Test
    fun `an edit is refused for an assistant message, an unknown id, and while generating`() {
        val session = oneExchange()
        assertNull(session.editAndTruncate("m2", "not mine to edit", clock))
        assertNull(session.editAndTruncate("m99", "nothing there", clock))
        val generating = session.withUserTurn("Second", clock, ids)
        assertNull(generating.editAndTruncate("m1", "too early", clock))
    }

    @Test
    fun `an edit clears a pending error`() {
        val failed = oneExchange().withUserTurn("Second", clock, ids).failed("m4", EngineError.NotLoaded, clock)
        assertNull(checkNotNull(failed.editAndTruncate("m3", "Second, reworded", clock)).error)
    }

    // --- Regenerate ---

    @Test
    fun `regenerating the last answer removes it and leaves the question`() {
        val session = oneExchange()
        val regenerate = checkNotNull(session.regenerateFrom("m2", clock))
        assertEquals(listOf("m1"), regenerate.messages.map { it.id })
        assertSame(session.messages[0], regenerate.messages[0])
    }

    @Test
    fun `regenerating an earlier answer cuts the conversation there`() {
        val session = oneExchange().withUserTurn("Second question", clock, ids).answered("m4", "Second answer")
        assertEquals(listOf("m1"), checkNotNull(session.regenerateFrom("m2", clock)).messages.map { it.id })
        assertEquals(listOf("m1", "m2", "m3"), checkNotNull(session.regenerateFrom("m4", clock)).messages.map { it.id })
    }

    @Test
    fun `regeneration is refused for a user message, an unknown id, and while generating`() {
        val session = oneExchange()
        assertNull(session.regenerateFrom("m1", clock))
        assertNull(session.regenerateFrom("m99", clock))
        assertNull(session.withUserTurn("Second", clock, ids).regenerateFrom("m2", clock))
    }

    @Test
    fun `an answer with no question before it cannot be regenerated`() {
        val orphan = newSession().copy(messages = listOf(ChatMessage(id = "a", role = Role.ASSISTANT, text = "hello")))
        assertNull(orphan.regenerateFrom("a", clock))
    }

    // --- Continue ---

    @Test
    fun `a stopped answer can be reopened and further text appends to it`() {
        val stopped = newSession().withUserTurn("Count", clock, ids)
            .applyEvent("m2", GenerationEvent.TextDelta("One, two,"), clock)
            .stopped("m2", clock)
        val reopened = checkNotNull(stopped.forContinuation("m2", clock))
        assertEquals(MessageStatus.STREAMING, reopened.messages[1].status)
        assertNull(reopened.messages[1].finishReason)
        assertEquals("One, two,", reopened.messages[1].text)
        assertEquals("m2", reopened.streamingMessageId)
        val continued = reopened
            .applyEvent("m2", GenerationEvent.TextDelta(" three."), clock)
            .applyEvent("m2", GenerationEvent.Done(FinishReason.END_OF_TURN, text = " three.", stats = stats), clock)
        assertEquals("One, two, three.", continued.messages[1].text)
        assertEquals(MessageStatus.COMPLETE, continued.messages[1].status)
    }

    @Test
    fun `a reply cut off by the token limit can be continued`() {
        val cut = newSession().withUserTurn("Tell", clock, ids)
            .applyEvent("m2", GenerationEvent.TextDelta("Once upon"), clock)
            .applyEvent("m2", GenerationEvent.Done(FinishReason.LENGTH, text = "Once upon", stats = stats), clock)
        assertEquals("m2", checkNotNull(cut.forContinuation("m2", clock)).streamingMessageId)
    }

    @Test
    fun `continuation is refused when it would misattribute or rewrite`() {
        val stopped = oneExchange().withUserTurn("Count", clock, ids)
            .applyEvent("m4", GenerationEvent.TextDelta("One"), clock)
            .stopped("m4", clock)
        assertNull("not the last message", stopped.forContinuation("m2", clock))
        assertNull("a user message", stopped.forContinuation("m3", clock))
        assertNull("another model would be writing under the first one's name", stopped.withModel("model-b").forContinuation("m4", clock))
        val empty = newSession().withUserTurn("Count", clock, SequentialIds("x")).stopped("x2", clock)
        assertNull("nothing to continue", empty.forContinuation("x2", clock))
        val generating = newSession().withUserTurn("Count", clock, SequentialIds("y"))
        assertNull("already streaming", generating.forContinuation("y2", clock))
    }

    // --- Model switch ---

    @Test
    fun `switching the model stamps later replies with the new model and keeps earlier ones`() {
        val switched = oneExchange().withModel("model-b").withUserTurn("And now?", clock, ids).answered("m4", "Now me")
        assertEquals("model-b", switched.modelId)
        assertEquals(listOf(null, "model-a", null, "model-b"), switched.messages.map { it.modelId })
    }

    @Test
    fun `a reply in flight keeps the model it was started with`() {
        val started = newSession().withUserTurn("Hello", clock, ids).withModel("model-b")
        assertEquals("model-a", started.messages[1].modelId)
        assertEquals("model-a", started.answered("m2", "Hi").messages[1].modelId)
    }

    // --- Title ---

    @Test
    fun `the title is derived from the first user message`() {
        assertEquals("Hello", oneExchange().title)
        val long = newSession().withUserMessage("What were the main causes of the fall of the Western Roman Empire?", clock, ids)
        assertEquals("What were the main causes of the fall of the\u2026", long.title)
        assertTrue(long.title.length <= ChatSession.MAX_TITLE_LENGTH)
    }

    @Test
    fun `the derived title is the first line with content, on one line`() {
        assertEquals("Summarise this:", ChatSession.deriveTitle("\n  \nSummarise   this:\nA very long pasted text follows here"))
        assertEquals("tabs and spaces", ChatSession.deriveTitle("tabs \t and\u00A0 spaces"))
        assertEquals("", ChatSession.deriveTitle("   \n\t"))
        assertEquals("", ChatSession.deriveTitle(""))
    }

    @Test
    fun `a long title is cut at a word, or hard when the words are too long, and never inside a character`() {
        val oneWord = "x".repeat(100)
        assertEquals("x".repeat(47) + "\u2026", ChatSession.deriveTitle(oneWord))
        val emoji = "\uD83D\uDE00".repeat(40)
        val title = ChatSession.deriveTitle(emoji)
        assertEquals("\uD83D\uDE00".repeat(23) + "\u2026", title)
        val exact = "a".repeat(48)
        assertEquals(exact, ChatSession.deriveTitle(exact))
    }

    @Test
    fun `the title follows an edit of the first message until the user chooses one`() {
        val session = oneExchange()
        assertEquals("Bonjour", checkNotNull(session.editAndTruncate("m1", "Bonjour", clock)).title)
        val named = session.withTitle("  My chat  ")
        assertEquals("My chat", named.title)
        assertEquals("My chat", checkNotNull(named.editAndTruncate("m1", "Bonjour", clock)).title)
        assertEquals("a blank title hands it back to the first message", "Hello", named.withTitle(" ").title)
    }

    @Test
    fun `a later message does not change the title`() {
        assertEquals("Hello", oneExchange().withUserMessage("Something else entirely", clock, ids).title)
    }

    // --- Restore and summary ---

    @Test
    fun `a reply that was streaming when the process died is stopped when loaded`() {
        val streaming = newSession().withUserTurn("Hello", clock, ids).applyEvent("m2", GenerationEvent.TextDelta("Hi th"), clock)
        val restored = streaming.restored()
        assertEquals(MessageStatus.STOPPED, restored.messages[1].status)
        assertEquals("Hi th", restored.messages[1].text)
        assertFalse(restored.isGenerating)
        assertEquals("the turn can be retried", "m1", restored.retryTargetId())
        assertSame(streaming.messages[0], restored.messages[0])
    }

    @Test
    fun `the summary is what the list of chats needs`() {
        val session = oneExchange()
        assertEquals(
            SessionSummary(id = "s1", title = "Hello", createdAtMillis = 1_000, updatedAtMillis = session.updatedAtMillis, modelId = "model-a", messageCount = 2),
            session.summary(),
        )
    }

    @Test
    fun `operations return new sessions and never change the one they were called on`() {
        val session = oneExchange()
        val copy = session.copy()
        session.withUserTurn("x", clock, ids)
        session.editAndTruncate("m1", "y", clock)
        session.regenerateFrom("m2", clock)
        session.withModel("model-b")
        assertEquals(copy, session)
        assertNotEquals(session, session.withModel("model-b"))
    }
}
