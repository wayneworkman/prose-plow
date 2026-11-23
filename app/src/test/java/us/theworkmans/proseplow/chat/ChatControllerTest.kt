package us.theworkmans.proseplow.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import us.theworkmans.proseplow.AppSession
import us.theworkmans.proseplow.SessionFixture
import us.theworkmans.proseplow.core.chat.ChatErrorKind
import us.theworkmans.proseplow.core.chat.ChatSession
import us.theworkmans.proseplow.core.chat.MessageStatus
import us.theworkmans.proseplow.core.chat.Role
import us.theworkmans.proseplow.core.engine.EngineError
import us.theworkmans.proseplow.core.engine.EngineState
import us.theworkmans.proseplow.core.engine.FakeInferenceEngine.Step
import us.theworkmans.proseplow.core.engine.FinishReason
import us.theworkmans.proseplow.core.engine.GenerationEvent
import us.theworkmans.proseplow.core.engine.GenerationStats
import us.theworkmans.proseplow.core.model.ModelCatalog
import us.theworkmans.proseplow.core.prompt.PromptMessage
import us.theworkmans.proseplow.core.prompt.PromptRole
import us.theworkmans.proseplow.data.SessionStore
import us.theworkmans.proseplow.openSession
import java.io.File

/**
 * The chats and the one generation running in them, driven through the real session (stores,
 * writer and all) with a scripted engine on virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatControllerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val qwen = ModelCatalog.QWEN35_0_8B.id
    private val boris = ModelCatalog.BORIS_2_INSTRUCT.id
    private val peacebell = ModelCatalog.PEACEBELL_V1_291M.id
    private val noStats = GenerationStats(promptTokens = 0, reusedPromptTokens = 0, generatedTokens = 0, promptMillis = 0, generationMillis = 0)

    private lateinit var app: SessionFixture

    /** An unlocked app whose model has finished loading. */
    private fun TestScope.unlocked(): AppSession {
        if (!::app.isInitialized) app = SessionFixture(temp.root, testScheduler)
        return app.open().also { runCurrent() }
    }

    private val AppSession.state: ChatState get() = chat.state.value
    private val AppSession.id: String get() = state.open.id

    /** Sends [text] in the chat on screen and plays the scripted reply to its end. */
    private fun TestScope.exchange(session: AppSession, text: String, reply: String) {
        app.engine.enqueueReply(listOf(reply))
        assertTrue(session.chat.send(session.id, text))
        runCurrent()
        assertFalse(session.openSession.isGenerating)
    }

    private suspend fun AppSession.nextNotice(): ChatNotice? = withTimeoutOrNull(1) { chat.notices.first() }

    /**
     * The longest reply set for a model goes with every request to that model, whichever way the
     * reply was asked for: a new message, the same question again, an edited message, and a
     * reply that is continued. It is read when the reply is about to be written, so a change
     * applies from the next answer; and a chat that switches models gets the other model's.
     */
    @Test
    fun everyKindOfRequestIsMadeWithTheLongestReplySetForItsModel() = runTest {
        val session = unlocked()
        val id = session.id
        fun asked() = app.engine.requests.last().maxNewTokens
        fun limit(tokens: Int?) = session.settings.update(urgent = true) { it.withMaxNewTokens(qwen, tokens) }

        // Nothing set: the model's own, said in full and not left to the engine.
        exchange(session, "First question", "First answer")
        assertEquals(ModelCatalog.QWEN35_0_8B.defaultMaxNewTokens, asked())

        // A new message.
        limit(300)
        exchange(session, "Second question", "Second answer")
        assertEquals(300, asked())

        // The same question again.
        limit(310)
        val answer = session.openSession.messages.last()
        app.engine.enqueueReply(listOf("Another answer"))
        assertTrue(session.chat.regenerate(id, answer.id))
        runCurrent()
        assertEquals(310, asked())

        // An edited message.
        limit(320)
        val question = session.openSession.messages.last { it.role == Role.USER }
        app.engine.enqueueReply(listOf("An answer to the edited question"))
        assertTrue(session.chat.edit(id, question.id, "Second question, edited"))
        runCurrent()
        assertEquals(320, asked())

        // A reply that reached its limit and is continued.
        limit(330)
        app.engine.enqueueReply(listOf("Cut off at the"), finishReason = FinishReason.LENGTH)
        assertTrue(session.chat.send(id, "Third question"))
        runCurrent()
        assertEquals(330, asked())
        limit(340)
        app.engine.enqueueReply(listOf(" limit, and carried on."))
        assertTrue(session.chat.continueReply(id, session.openSession.messages.last().id))
        runCurrent()
        assertEquals(340, asked())
        assertEquals(6, app.engine.requests.size)

        // Back to the model's own.
        limit(null)
        exchange(session, "Fourth question", "Fourth answer")
        assertEquals(1024, asked())

        // Another model has its own limit: what is set for one is not used for the other.
        limit(340)
        session.chat.selectModel(id, boris)
        runCurrent()
        exchange(session, "A question for another model", "Its answer")
        assertEquals(boris, app.engine.requests.last().modelId)
        assertEquals(ModelCatalog.BORIS_2_INSTRUCT.defaultMaxNewTokens, asked())
        session.close()
    }

    // --- Starting ------------------------------------------------------------------------------

    @Test
    fun aFirstStartShowsAnEmptyChatLoadsItsModelAndStoresNothing() = runTest {
        app = SessionFixture(temp.root, testScheduler)
        val session = app.open()

        assertEquals(emptyList<Any>(), session.state.chats)
        assertEquals(emptyList<Any>(), session.openSession.messages)
        assertEquals(ModelCatalog.default.id, session.openSession.modelId)
        assertEquals(ModelCatalog.bundled, session.state.models)
        assertNull(session.state.turn)

        runCurrent()
        assertEquals(listOf(qwen), app.engine.loads)
        assertEquals(EngineState.Ready(qwen), session.state.engine)

        session.close()
        assertEquals("a chat with nothing in it is not stored", emptyList<String>(), app.fileNames())
        assertEquals(1, app.engine.forgetCount)
    }

    // --- A turn --------------------------------------------------------------------------------

    @Test
    fun sendingStreamsTheReplyIntoTheChat() = runTest {
        val session = unlocked()
        val id = session.id
        app.engine.enqueueReply(listOf("A trench ", "made by a plough.  "), millisBetween = 100)

        assertTrue(session.chat.send(id, "  What is a furrow?\n"))

        // At once, before the engine has done anything: the question and the place for the answer.
        var chat = session.openSession
        assertEquals(listOf(Role.USER, Role.ASSISTANT), chat.messages.map { it.role })
        assertEquals("What is a furrow?", chat.messages[0].text)
        assertEquals(MessageStatus.STREAMING, chat.messages[1].status)
        assertEquals("the reply is stamped with the model that writes it", qwen, chat.messages[1].modelId)
        assertEquals(Turn(id, chat.messages[1].id, TurnPhase.Preparing), session.state.turn)
        assertEquals("What is a furrow?", session.state.chats.single().title)

        runCurrent()
        val request = app.engine.requests.single()
        assertEquals(qwen, request.modelId)
        assertEquals(listOf(PromptMessage(PromptRole.USER, "What is a furrow?")), request.messages)
        assertNull(request.systemPrompt)
        assertFalse(request.continueFinalMessage)

        advanceTimeBy(100)
        runCurrent()
        assertEquals("A trench ", session.openSession.messages[1].text)
        assertEquals(TurnPhase.Writing, session.state.turn?.phase)

        advanceTimeBy(100)
        runCurrent()
        chat = session.openSession
        assertEquals("A trench made by a plough.", chat.messages[1].text)
        assertEquals(MessageStatus.COMPLETE, chat.messages[1].status)
        assertEquals(FinishReason.END_OF_TURN, chat.messages[1].finishReason)
        assertNull(session.state.turn)
        assertNull(chat.error)
        assertEquals(1, app.engine.completedGenerations)
    }

    @Test
    fun everyStepOfATurnIsStoredAndTheChatComesBackAfterALock() = runTest {
        val session = unlocked()
        val id = session.id
        app.engine.enqueueReply(listOf("one ", "two ", "three ", "four ", "five"), millisBetween = 400)
        session.chat.send(id, "Count")

        runCurrent()
        assertEquals("the question is on disk before the model has said a word", "Count", app.stored(id)!!.messages[0].text)
        assertEquals(MessageStatus.STREAMING, app.stored(id)!!.messages[1].status)

        assertEquals("and so is the list that names the chat", listOf("session-index.enc", "sessions/$id.enc"), app.fileNames())

        // Words arrive every 400 ms. The file follows lazily: at most a second behind, not once a word.
        advanceTimeBy(1_300)
        runCurrent()
        assertEquals("one two three ", session.openSession.messages[1].text)
        assertEquals("", app.stored(id)!!.messages[1].text)
        advanceTimeBy(100)
        runCurrent()
        assertEquals("one two three ", app.stored(id)!!.messages[1].text)

        advanceTimeBy(600)
        runCurrent()
        assertEquals("the end of the reply is stored at once", session.openSession, app.stored(id))
        assertEquals("one two three four five", app.stored(id)!!.messages[1].text)
        assertFalse(app.diskContains("three"))

        session.close()
        assertEquals(listOf("session-index.enc", "sessions/$id.enc"), app.fileNames())
        val again = unlocked()
        assertEquals("the app opens to the chat that was on screen", session.openSession, again.openSession)
        assertEquals(session.state.chats, again.state.chats)
        again.close()
    }

    @Test
    fun theStagesOfAReplyAreReported() = runTest {
        val session = unlocked()
        app.engine.enqueue(
            listOf(
                Step.Emit(GenerationEvent.HistoryTrimmed(droppedMessages = 2, keptMessages = 3)),
                Step.Emit(GenerationEvent.PromptProgress(evaluatedTokens = 10, totalTokens = 40)),
                Step.Wait(50),
                Step.Emit(GenerationEvent.PromptProgress(evaluatedTokens = 40, totalTokens = 40)),
                Step.Wait(50),
                Step.Emit(GenerationEvent.TextDelta("Done.")),
                Step.Emit(GenerationEvent.Done(FinishReason.END_OF_TURN, text = "Done.", stats = noStats)),
            ),
        )
        session.chat.send(session.id, "Long story")
        assertEquals(TurnPhase.Preparing, session.state.turn?.phase)

        runCurrent()
        assertEquals(TurnPhase.ReadingPrompt(10, 40), session.state.turn?.phase)
        assertEquals("the reply is being written without the two oldest messages", mapOf(session.id to 2), session.state.trimmedMessages)
        advanceTimeBy(50)
        runCurrent()
        assertEquals(TurnPhase.ReadingPrompt(40, 40), session.state.turn?.phase)
        advanceTimeBy(50)
        runCurrent()
        assertNull(session.state.turn)
        assertEquals("it stays said after the reply", mapOf(session.id to 2), session.state.trimmedMessages)

        // The next reply saw everything: the note goes.
        exchange(session, "Short", "Yes.")
        assertEquals(emptyMap<String, Int>(), session.state.trimmedMessages)
    }

    @Test
    fun stopKeepsWhatWasWrittenMarksItStoppedAndSavesItAtOnce() = runTest {
        val session = unlocked()
        val id = session.id
        app.engine.enqueueReply(listOf("One ", "two ", "three"), millisBetween = 100)
        session.chat.send(id, "Count")
        runCurrent()
        advanceTimeBy(100)
        runCurrent()

        session.chat.stop(id)

        // Before anything else runs: the screen shows the stopped reply.
        val reply = session.openSession.messages.last()
        assertEquals("One ", reply.text)
        assertEquals(MessageStatus.STOPPED, reply.status)
        assertEquals(FinishReason.CANCELLED, reply.finishReason)
        assertNull(session.state.turn)
        assertFalse(session.openSession.isGenerating)

        runCurrent()
        assertEquals("the engine was told to stop", 1, app.engine.cancelledGenerations)
        assertEquals(0, app.engine.activeGenerations)
        assertEquals(session.openSession, app.stored(id))

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("nothing arrives after the stop", "One ", session.openSession.messages.last().text)
        assertTrue("and the model can carry on from there", ChatController.canContinue(session.openSession))
    }

    @Test
    fun aReplyThatFailsShowsItsErrorAndRetryAsksAgain() = runTest {
        val session = unlocked()
        val id = session.id
        app.engine.enqueueFailure(EngineError.Native(code = 3, detail = "decode failed"))
        session.chat.send(id, "Hello")
        runCurrent()

        var chat = session.openSession
        assertEquals("the empty reply is gone, the question stays", listOf("Hello"), chat.messages.map { it.text })
        assertEquals(ChatErrorKind.NATIVE, chat.error?.kind)
        assertEquals(chat.messages[0].id, chat.error?.userMessageId)
        assertNull(session.state.turn)
        assertEquals("the error is stored with the chat", chat, app.stored(id))

        app.engine.enqueueReply(listOf("Hi."))
        assertTrue(session.chat.retry(id))
        assertNull("the error goes as soon as the retry starts", session.openSession.error)
        runCurrent()

        chat = session.openSession
        assertEquals(listOf("Hello", "Hi."), chat.messages.map { it.text })
        assertNull(chat.error)
        assertEquals("the same question was sent again", app.engine.requests[0].messages, app.engine.requests[1].messages)
        assertFalse("there is nothing left to retry", session.chat.retry(id))
    }

    @Test
    fun aReplyThatFailsHalfWayKeepsItsText() = runTest {
        val session = unlocked()
        app.engine.enqueueFailure(EngineError.ContextFull("no room"), pieces = listOf("Half an ans"))
        session.chat.send(session.id, "Hello")
        runCurrent()

        val chat = session.openSession
        assertEquals("Half an ans", chat.messages.last().text)
        assertEquals(MessageStatus.STOPPED, chat.messages.last().status)
        assertEquals(ChatErrorKind.CONTEXT_FULL, chat.error?.kind)
    }

    @Test
    fun aReplyStoppedBeforeItSaidAnythingCanBeAskedForAgain() = runTest {
        val session = unlocked()
        val id = session.id
        app.engine.enqueue(listOf(Step.Hang))
        session.chat.send(id, "Hello")
        runCurrent()
        session.chat.stop(id)
        runCurrent()
        assertEquals("", session.openSession.messages.last().text)
        assertEquals(MessageStatus.STOPPED, session.openSession.messages.last().status)
        assertFalse(ChatController.canContinue(session.openSession))

        app.engine.enqueueReply(listOf("Hi."))
        assertTrue(session.chat.retry(id))
        runCurrent()

        assertEquals(listOf("Hello", "Hi."), session.openSession.messages.map { it.text })
    }

    @Test
    fun regenerateReplacesAnAnswerAndEverythingAfterIt() = runTest {
        val session = unlocked()
        val id = session.id
        exchange(session, "First question", "First answer")
        exchange(session, "Second question", "Second answer")
        val firstAnswer = session.openSession.messages[1]

        app.engine.enqueueReply(listOf("A better first answer"))
        assertTrue(session.chat.regenerate(id, firstAnswer.id))
        assertEquals(listOf("First question", ""), session.openSession.messages.map { it.text })
        runCurrent()

        val chat = session.openSession
        assertEquals(listOf("First question", "A better first answer"), chat.messages.map { it.text })
        assertNotEquals("it is a new message", firstAnswer.id, chat.messages[1].id)
        assertEquals(listOf(PromptMessage(PromptRole.USER, "First question")), app.engine.requests.last().messages)
        assertEquals(chat, app.stored(id))
        assertFalse("a question cannot be regenerated", session.chat.regenerate(id, chat.messages[0].id))
    }

    @Test
    fun editingAMessageReplacesItCutsTheConversationThereAndAsksAgain() = runTest {
        val session = unlocked()
        val id = session.id
        exchange(session, "What is a furow?", "I do not know that word.")
        exchange(session, "Never mind", "All right.")
        val question = session.openSession.messages[0]

        app.engine.enqueueReply(listOf("A trench made by a plough."))
        assertTrue(session.chat.edit(id, question.id, "  What is a furrow? "))

        var chat = session.openSession
        assertEquals(listOf("What is a furrow?", ""), chat.messages.map { it.text })
        assertEquals("the message keeps its place and its id", question.id, chat.messages[0].id)
        assertEquals("the title follows the first message", "What is a furrow?", session.state.chats.single().title)
        runCurrent()

        chat = session.openSession
        assertEquals(listOf("What is a furrow?", "A trench made by a plough."), chat.messages.map { it.text })
        assertEquals(listOf(PromptMessage(PromptRole.USER, "What is a furrow?")), app.engine.requests.last().messages)
        assertEquals(chat, app.stored(id))

        assertFalse("an answer cannot be edited", session.chat.edit(id, chat.messages[1].id, "x"))
        assertFalse("nor can a message be emptied", session.chat.edit(id, question.id, "  "))
        assertEquals(chat, session.openSession)
    }

    @Test
    fun aReplyThatWasCutShortCanBeContinuedByTheSameModel() = runTest {
        val session = unlocked()
        val id = session.id
        app.engine.enqueueReply(listOf("Once upon"), finishReason = FinishReason.LENGTH)
        session.chat.send(id, "Tell me a story")
        runCurrent()
        val cut = session.openSession.messages.last()
        assertEquals(FinishReason.LENGTH, cut.finishReason)
        assertTrue(ChatController.canContinue(session.openSession))

        app.engine.enqueueReply(listOf(" a time."))
        assertTrue(session.chat.continueReply(id, cut.id))
        assertEquals("the same message is being written again", cut.id, session.state.turn?.assistantId)
        runCurrent()

        val request = app.engine.requests.last()
        assertTrue(request.continueFinalMessage)
        assertEquals(PromptMessage(PromptRole.ASSISTANT, "Once upon"), request.messages.last())
        val reply = session.openSession.messages.last()
        assertEquals(cut.id, reply.id)
        assertEquals("Once upon a time.", reply.text)
        assertEquals(FinishReason.END_OF_TURN, reply.finishReason)
        assertFalse(ChatController.canContinue(session.openSession))

        // Another model may answer again, but not carry on under the first one's name.
        session.chat.selectModel(id, boris)
        assertFalse(session.chat.continueReply(id, reply.id))
    }

    @Test
    fun textTheModelTakesBackGoesFromTheMessageWhileItIsBeingWritten() = runTest {
        val session = unlocked()
        val id = session.id
        app.engine.enqueue(
            listOf(
                Step.Emit(GenerationEvent.TextDelta("Nice.")),
                Step.Emit(GenerationEvent.TextDelta(" 198")),
                Step.Wait(100),
                Step.Emit(GenerationEvent.TextReplaced("Nice.", removed = 4)),
                Step.Wait(100),
                Step.Emit(GenerationEvent.TextDelta(" However,")),
                Step.Emit(GenerationEvent.Done(FinishReason.LENGTH, text = "Nice. However,", stats = noStats)),
            ),
        )
        session.chat.send(id, "The capital of France is")
        runCurrent()
        assertEquals("Nice. 198", session.openSession.messages[1].text)
        assertEquals(TurnPhase.Writing, session.state.turn?.phase)

        advanceTimeBy(100)
        runCurrent()
        assertEquals("what was taken back is gone from the message on screen", "Nice.", session.openSession.messages[1].text)
        assertTrue("and the reply is still being written", session.openSession.isGenerating)
        assertEquals(TurnPhase.Writing, session.state.turn?.phase)

        advanceTimeBy(100)
        runCurrent()
        val reply = session.openSession.messages[1]
        assertEquals("Nice. However,", reply.text)
        assertEquals(FinishReason.LENGTH, reply.finishReason)
        assertEquals("what is stored is the text that was left", session.openSession, app.stored(id))
    }

    @Test
    fun textTakenBackFromAContinuedReplyLeavesWhatWasThereBefore() = runTest {
        val session = unlocked()
        val id = session.id
        app.engine.enqueueReply(listOf("Once upon"), finishReason = FinishReason.LENGTH)
        session.chat.send(id, "Tell me a story")
        runCurrent()
        val cut = session.openSession.messages.last()

        app.engine.enqueue(
            listOf(
                Step.Emit(GenerationEvent.TextDelta(" a mattress")),
                Step.Emit(GenerationEvent.TextReplaced(" a", removed = 9)),
                Step.Emit(GenerationEvent.TextDelta(" time.")),
                Step.Emit(GenerationEvent.Done(FinishReason.END_OF_TURN, text = " a time.", stats = noStats)),
            ),
        )
        assertTrue(session.chat.continueReply(id, cut.id))
        runCurrent()
        assertEquals("Once upon a time.", session.openSession.messages.last().text)
    }

    @Test
    fun aModelThatContinuesTextIsGivenWhatWasTypedExactlyAndItsReplyIsKeptExactly() = runTest {
        // Shard-2 is known to the catalogue and not shipped, so the build that carries it is
        // spelled out here.
        app = SessionFixture(temp.root, testScheduler).apply { models = ModelCatalog.bundled + ModelCatalog.SHARD_2 }
        val session = unlocked()
        val id = session.id
        val shard = ModelCatalog.SHARD_2.id
        session.chat.selectModel(id, shard)
        runCurrent()

        app.engine.enqueue(
            listOf(
                Step.Emit(GenerationEvent.TextDelta(" there was \n")),
                Step.Emit(GenerationEvent.Done(FinishReason.END_OF_TURN, text = " there was \n", stats = noStats, exactText = true)),
            ),
        )
        assertTrue(session.chat.send(id, "  Once upon a time "))
        runCurrent()

        val request = app.engine.requests.last()
        assertEquals(shard, request.modelId)
        assertEquals("a space at the end decides what comes next", listOf(PromptMessage(PromptRole.USER, "  Once upon a time ")), request.messages)
        assertNull("it has no system prompt", request.systemPrompt)
        assertEquals(ModelCatalog.SHARD_2.sampling, request.sampling)
        val chat = session.openSession
        assertEquals("  Once upon a time ", chat.messages[0].text)
        assertEquals("the reply keeps its first and its last character", " there was \n", chat.messages[1].text)
        assertEquals(shard, chat.messages[1].modelId)
        assertEquals(chat, app.stored(id))

        // Editing keeps the text exactly as well.
        app.engine.enqueueReply(listOf(" again"), finishReason = FinishReason.LENGTH)
        assertTrue(session.chat.edit(id, chat.messages[0].id, "Once more \n"))
        runCurrent()
        assertEquals("Once more \n", session.openSession.messages[0].text)
        assertEquals(listOf(PromptMessage(PromptRole.USER, "Once more \n")), app.engine.requests.last().messages)

        // Text that is only blanks is still nothing to send.
        assertFalse(session.chat.send(id, "  \n"))

        // A chat model of the same build is sent its messages without the blanks around them, as before.
        session.chat.selectModel(id, qwen)
        runCurrent()
        app.engine.enqueueReply(listOf("Yes."))
        assertTrue(session.chat.send(id, "  And a question?  "))
        runCurrent()
        assertEquals("And a question?", session.openSession.messages[2].text)
        assertEquals(PromptMessage(PromptRole.USER, "And a question?"), app.engine.requests.last().messages.last())
        assertEquals(PromptMessage(PromptRole.USER, "Once more"), app.engine.requests.last().messages.first())
    }

    @Test
    fun theConversationCannotBeChangedWhileAReplyIsBeingWritten() = runTest {
        val session = unlocked()
        val id = session.id
        exchange(session, "First", "Answer")
        app.engine.enqueue(listOf(Step.Emit(GenerationEvent.TextDelta("So")), Step.Hang))
        session.chat.send(id, "Second")
        runCurrent()
        val before = session.openSession
        val messages = before.messages

        assertFalse(session.chat.send(id, "Third"))
        assertFalse(session.chat.retry(id))
        assertFalse(session.chat.regenerate(id, messages[1].id))
        assertFalse(session.chat.edit(id, messages[0].id, "Changed"))
        assertFalse(session.chat.continueReply(id, messages[3].id))
        session.chat.selectModel(id, boris)

        assertEquals(before, session.openSession)
        assertEquals(listOf(qwen), app.engine.loads)

        // A draft for the next message can be typed meanwhile.
        session.chat.setDraft(id, "Third")
        assertEquals("Third", session.openSession.draft)
        assertTrue(session.openSession.isGenerating)
        session.close()
    }

    @Test
    fun aBlankMessageIsNotSent() = runTest {
        val session = unlocked()
        assertFalse(session.chat.send(session.id, " \n\t"))
        assertFalse(session.chat.send("no such chat", "Hello"))
        assertEquals(emptyList<Any>(), session.openSession.messages)
        assertEquals(emptyList<Any>(), app.engine.requests)
    }

    @Test
    fun theSystemPromptOfTheModelGoesWithEveryRequest() = runTest {
        val session = unlocked()
        session.settings.update { it.withSystemPrompt(qwen, "Answer in one sentence.").withSystemPrompt(boris, "Be Boris.") }

        exchange(session, "Hello", "Hi.")
        assertEquals("Answer in one sentence.", app.engine.requests.last().systemPrompt)

        session.settings.update { it.withSystemPrompt(qwen, "") }
        exchange(session, "Again", "Hi.")
        assertNull("a prompt that was cleared is not sent", app.engine.requests.last().systemPrompt)

        session.chat.selectModel(session.id, boris)
        runCurrent()
        exchange(session, "And you?", "Hi.")
        assertEquals("each model has its own", "Be Boris.", app.engine.requests.last().systemPrompt)
    }

    // --- Models --------------------------------------------------------------------------------

    @Test
    fun switchingTheModelMidChatLoadsItAndLaterRepliesComeFromIt() = runTest {
        val session = unlocked()
        val id = session.id
        exchange(session, "Who are you?", "Qwen.")

        session.chat.selectModel(id, boris)

        assertEquals(boris, session.openSession.modelId)
        runCurrent()
        assertEquals("one model at a time: the engine swapped them", listOf(qwen, boris), app.engine.loads)
        assertEquals(EngineState.Ready(boris), session.state.engine)
        assertEquals("the choice is stored", boris, app.stored(id)!!.modelId)

        exchange(session, "And now?", "Boris.")
        val chat = session.openSession
        assertEquals(boris, app.engine.requests.last().modelId)
        assertEquals("every answer says who wrote it", listOf(null, qwen, null, boris), chat.messages.map { it.modelId })
        assertEquals("the new model is given the whole conversation", 3, app.engine.requests.last().messages.size)

        // Answering the first question again is now the new model's job.
        app.engine.enqueueReply(listOf("Boris, in fact."))
        session.chat.regenerate(id, chat.messages[1].id)
        runCurrent()
        assertEquals(listOf(null, boris), session.openSession.messages.map { it.modelId })
    }

    @Test
    fun aModelPickedWhileAnotherIsLoadingTakesItsPlace() = runTest {
        app = SessionFixture(temp.root, testScheduler)
        app.engine.loadMillis = 1_000
        val session = app.open()
        runCurrent()
        assertEquals(EngineState.Loading(qwen, 0f), session.state.engine)

        advanceTimeBy(500)
        session.chat.selectModel(session.id, boris)
        runCurrent()
        assertEquals("the first load was abandoned", EngineState.Loading(boris, 0f), session.state.engine)

        advanceTimeBy(600)
        runCurrent()
        assertEquals("and never finishes behind the second one's back", EngineState.Loading(boris, 0f), session.state.engine)
        advanceTimeBy(400)
        runCurrent()
        assertEquals(EngineState.Ready(boris), session.state.engine)
        assertEquals(listOf(qwen, boris), app.engine.loads)

        // Picking the model that is loaded, or loading, starts nothing.
        session.chat.selectModel(session.id, boris)
        runCurrent()
        assertEquals(listOf(qwen, boris), app.engine.loads)
    }

    @Test
    fun aMessageSentWhileTheModelLoadsWaitsForItAndThenGetsItsReply() = runTest {
        app = SessionFixture(temp.root, testScheduler)
        app.engine.loadMillis = 1_000
        val session = app.open()
        runCurrent()
        app.engine.enqueueReply(listOf("Hi."))

        assertTrue(session.chat.send(session.id, "Hello"))
        advanceTimeBy(600)
        runCurrent()
        assertEquals("the reply is waiting, and the screen can say what for", TurnPhase.Preparing, session.state.turn?.phase)
        assertEquals(EngineState.Loading(qwen, 0f), session.state.engine)
        assertEquals(emptyList<Any>(), app.engine.requests)

        advanceTimeBy(400)
        runCurrent()
        assertEquals(listOf("Hello", "Hi."), session.openSession.messages.map { it.text })
        assertEquals("it waited for the load that was under way instead of starting another", listOf(qwen), app.engine.loads)
    }

    @Test
    fun aMessageInAChatWhoseModelIsNotLoadedLoadsItFirst() = runTest {
        val session = unlocked()
        exchange(session, "Hello", "Hi from Qwen.")
        val first = session.id
        session.chat.newChat()
        session.chat.selectModel(session.id, boris)
        runCurrent()
        assertEquals(EngineState.Ready(boris), session.state.engine)

        // Looking at the other chat loads nothing; asking in it does.
        session.chat.openChat(first)
        runCurrent()
        assertEquals(listOf(qwen, boris), app.engine.loads)
        exchange(session, "Still there?", "Yes.")
        assertEquals(listOf(qwen, boris, qwen), app.engine.loads)
        assertEquals(qwen, session.openSession.messages.last().modelId)
    }

    @Test
    fun aModelThatCannotBeLoadedFailsTheReplyWithThatReasonAndCanBeTriedAgain() = runTest {
        app = SessionFixture(temp.root, testScheduler)
        app.engine.loadFailure = EngineError.LoadFailed(qwen, "No native engine in this build")
        val session = app.open()
        runCurrent()
        assertEquals(EngineState.Failed(qwen, EngineError.LoadFailed(qwen, "No native engine in this build")), session.state.engine)

        // Sending tries again, and says why there is no answer.
        app.engine.loadFailure = EngineError.LoadFailed(qwen, "No native engine in this build")
        assertTrue(session.chat.send(session.id, "Hello"))
        runCurrent()
        var chat = session.openSession
        assertEquals(listOf("Hello"), chat.messages.map { it.text })
        assertEquals(ChatErrorKind.MODEL_LOAD_FAILED, chat.error?.kind)
        assertEquals("No native engine in this build", chat.error?.detail)
        assertNull(session.state.turn)
        assertEquals(emptyList<Any>(), app.engine.requests)

        // The engine works now.
        session.chat.reloadModel()
        runCurrent()
        assertEquals(EngineState.Ready(qwen), session.state.engine)
        app.engine.enqueueReply(listOf("Hi."))
        assertTrue(session.chat.retry(session.id))
        runCurrent()
        chat = session.openSession
        assertEquals(listOf("Hello", "Hi."), chat.messages.map { it.text })
        assertNull(chat.error)
    }

    @Test
    fun aReplyThatWaitedForALoadThatFailsReportsThatFailureWithoutLoadingAgain() = runTest {
        app = SessionFixture(temp.root, testScheduler)
        app.engine.loadMillis = 1_000
        app.engine.loadFailure = EngineError.LoadFailed(qwen, "out of memory")
        val session = app.open()
        runCurrent()

        assertTrue(session.chat.send(session.id, "Hello"))
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(ChatErrorKind.MODEL_LOAD_FAILED, session.openSession.error?.kind)
        assertEquals("out of memory", session.openSession.error?.detail)
        assertEquals("the user waited for one load, not for two", listOf(qwen), app.engine.loads)
        assertNull(session.state.turn)
    }

    @Test
    fun stoppingAReplyThatIsLoadingItsModelAbandonsTheLoad() = runTest {
        app = SessionFixture(temp.root, testScheduler)
        app.engine.loadFailure = EngineError.LoadFailed(qwen, "first attempt")
        val session = app.open()
        runCurrent()
        app.engine.loadMillis = 1_000

        session.chat.send(session.id, "Hello")
        runCurrent()
        assertEquals(EngineState.Loading(qwen, 0f), session.state.engine)
        advanceTimeBy(300)
        session.chat.stop(session.id)
        runCurrent()

        assertEquals(EngineState.Unloaded, session.state.engine)
        assertEquals(MessageStatus.STOPPED, session.openSession.messages.last().status)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(EngineState.Unloaded, session.state.engine)
        assertEquals(emptyList<Any>(), app.engine.requests)
    }

    @Test
    fun aChatWhoseModelIsNotInThisBuildWaitsForAnotherToBeChosen() = runTest {
        // A build made with all the catalogue's models but one, as a build with a subset is.
        val leftOut = ModelCatalog.SHARD_2.id
        app = SessionFixture(temp.root, testScheduler).apply { models = ModelCatalog.bundled.filter { it.id != leftOut } }
        val old = ChatSession(id = "old", createdAtMillis = 1, modelId = "retired-model").withUserMessage("From an older build", app.clock)
        app.plant(old)
        val session = app.open()
        runCurrent()
        session.chat.openChat("old")
        runCurrent()

        assertEquals(old, session.openSession)
        assertFalse(session.state.offers("retired-model"))
        assertFalse("nothing is asked of a model that is not there", session.chat.send("old", "Hello?"))
        assertFalse(session.chat.retry("old"))
        assertEquals("and nothing was loaded for it", listOf(qwen), app.engine.loads)

        session.chat.selectModel("old", boris)
        runCurrent()
        app.engine.enqueueReply(listOf("Here."))
        assertTrue(session.chat.retry("old"))
        runCurrent()
        assertEquals(listOf("From an older build", "Here."), session.openSession.messages.map { it.text })
        assertEquals(boris, session.openSession.messages.last().modelId)

        // A model the catalogue knows but this build does not offer cannot be chosen either.
        assertFalse(session.state.offers(leftOut))
        session.chat.selectModel("old", leftOut)
        assertEquals(boris, session.openSession.modelId)
    }

    @Test
    fun aBuildWithoutAnyModelKeepsItsChatsReadableAndAsksNothingOfTheEngine() = runTest {
        var session = unlocked()
        exchange(session, "Written while there was a model", "Yes.")
        val id = session.id
        session.close()
        val loadsBefore = app.engine.loads.size

        app.models = emptyList()
        session = unlocked()

        assertEquals(emptyList<Any>(), session.state.models)
        assertEquals(listOf("Written while there was a model", "Yes."), session.openSession.messages.map { it.text })
        assertFalse(session.chat.send(id, "Anyone there?"))
        assertFalse(session.chat.regenerate(id, session.openSession.messages[1].id))
        session.chat.selectModel(id, boris)
        session.chat.reloadModel()
        runCurrent()
        assertEquals(qwen, session.openSession.modelId)
        assertEquals("nothing was loaded", loadsBefore, app.engine.loads.size)
        assertEquals(emptyList<Any>(), app.engine.requests.drop(1))

        // A draft can still be left for the build that has a model again.
        session.chat.setDraft(id, "For later")
        session.chat.newChat()
        assertEquals("a new chat still names a model, for the day one is there", ModelCatalog.default.id, session.openSession.modelId)
        session.close()
        assertEquals("For later", app.stored(id)!!.draft)
    }

    // --- Sampling: what the engine is asked to generate with ------------------------------------

    @Test
    fun withoutATemperatureOfTheUsersTheEngineGetsTheModelsOwnSampling() = runTest {
        val session = unlocked()

        exchange(session, "Hello", "Hi.")

        assertEquals(ModelCatalog.QWEN35_0_8B.sampling, app.engine.requests.single().sampling)
    }

    @Test
    fun aTemperatureSetForTheModelReplacesTheTemperatureAndNothingElse() = runTest {
        val session = unlocked()
        val own = ModelCatalog.QWEN35_0_8B.sampling
        session.settings.update { it.withTemperature(qwen, 0.3f) }

        exchange(session, "Hello", "Hi.")

        val sampling = app.engine.requests.single().sampling!!
        assertEquals(own.copy(temperature = 0.3f), sampling)
        assertEquals(0.3f, sampling.temperature)
        assertEquals(
            "everything else is the catalogue's",
            listOf<Any?>(own.topK, own.topP, own.minP, own.repetitionPenalty, own.presencePenalty, own.frequencyPenalty, own.seed),
            listOf<Any?>(sampling.topK, sampling.topP, sampling.minP, sampling.repetitionPenalty, sampling.presencePenalty, sampling.frequencyPenalty, sampling.seed),
        )
        assertFalse(sampling.isGreedy)
    }

    @Test
    fun aTemperatureOfZeroAsksTheEngineForItsGreedyPath() = runTest {
        val session = unlocked()
        session.settings.update { it.withTemperature(qwen, 0f) }

        exchange(session, "Hello", "Hi.")

        val sampling = app.engine.requests.single().sampling!!
        assertEquals(ModelCatalog.QWEN35_0_8B.sampling.copy(temperature = 0f), sampling)
        assertTrue(sampling.isGreedy)
    }

    @Test
    fun switchingTheModelMidChatSwitchesTheTemperatureWithIt() = runTest {
        val session = unlocked()
        val id = session.id
        session.settings.update { it.withTemperature(qwen, 0.3f).withTemperature(boris, 1.5f) }

        exchange(session, "Who are you?", "Qwen.")
        assertEquals(ModelCatalog.QWEN35_0_8B.sampling.copy(temperature = 0.3f), app.engine.requests.last().sampling)

        session.chat.selectModel(id, boris)
        runCurrent()
        exchange(session, "And now?", "Boris.")
        assertEquals(boris, app.engine.requests.last().modelId)
        assertEquals("the other model's own sampling with the other model's temperature", ModelCatalog.BORIS_2_INSTRUCT.sampling.copy(temperature = 1.5f), app.engine.requests.last().sampling)

        // A model for which nothing was set samples as the catalogue says.
        session.chat.selectModel(id, peacebell)
        runCurrent()
        exchange(session, "And you?", "Peacebell.")
        assertEquals(ModelCatalog.PEACEBELL_V1_291M.sampling, app.engine.requests.last().sampling)

        session.chat.selectModel(id, qwen)
        runCurrent()
        exchange(session, "Back again?", "Qwen.")
        assertEquals(ModelCatalog.QWEN35_0_8B.sampling.copy(temperature = 0.3f), app.engine.requests.last().sampling)
    }

    @Test
    fun aChangedTemperatureAppliesFromTheNextAnswerAlsoOneAskedForAgainOrContinued() = runTest {
        val session = unlocked()
        val id = session.id
        val own = ModelCatalog.QWEN35_0_8B.sampling
        exchange(session, "Tell me a story", "Once.")
        assertEquals(own, app.engine.requests[0].sampling)

        // "Answer again" after the value was changed in Settings.
        session.settings.update { it.withTemperature(qwen, 1.2f) }
        app.engine.enqueueReply(listOf("Once upon"), finishReason = FinishReason.LENGTH)
        assertTrue(session.chat.regenerate(id, session.openSession.messages[1].id))
        runCurrent()
        assertEquals(own.copy(temperature = 1.2f), app.engine.requests[1].sampling)

        // "Continue" after it was changed again.
        session.settings.update { it.withTemperature(qwen, 0f) }
        app.engine.enqueueReply(listOf(" a time."))
        assertTrue(session.chat.continueReply(id, session.openSession.messages[1].id))
        runCurrent()
        assertTrue(app.engine.requests[2].continueFinalMessage)
        assertEquals(own.copy(temperature = 0f), app.engine.requests[2].sampling)

        // Taken back: the model's own again, from the next message on.
        session.settings.update { it.withTemperature(qwen, null) }
        exchange(session, "Another", "Twice.")
        assertEquals(own, app.engine.requests[3].sampling)
        assertEquals("the answers already written are not touched", "Once upon a time.", session.openSession.messages[1].text)
    }

    @Test
    fun aTemperatureChangedWhileAReplyIsBeingWrittenLeavesThatReplyAlone() = runTest {
        val session = unlocked()
        app.engine.enqueueReply(listOf("Slow", "ly."), millisBetween = 500)
        session.chat.send(session.id, "Take your time")
        runCurrent()

        session.settings.update { it.withTemperature(qwen, 1.9f) }
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals("one request, made with what was set when it began", listOf(ModelCatalog.QWEN35_0_8B.sampling), app.engine.requests.map { it.sampling })
        assertEquals("Slowly.", session.openSession.messages.last().text)
    }

    // --- Chats ---------------------------------------------------------------------------------

    @Test
    fun aNewChatKeepsTheOldOneAndStartsWithItsModel() = runTest {
        val session = unlocked()
        session.chat.selectModel(session.id, boris)
        exchange(session, "The first chat", "Yes.")
        val first = session.id

        session.chat.newChat()

        assertNotEquals(first, session.id)
        assertEquals(emptyList<Any>(), session.openSession.messages)
        assertEquals(boris, session.openSession.modelId)
        assertEquals("the new chat is not listed while it is empty", listOf(first), session.state.chats.map { it.id })

        // Asking for a new chat when the one on screen is new changes nothing.
        val empty = session.id
        session.chat.newChat()
        assertEquals(empty, session.id)

        app.clock.advance(1_000)
        exchange(session, "The second chat", "Yes.")
        assertEquals("the most recently changed chat comes first", listOf(empty, first), session.state.chats.map { it.id })
        assertEquals(listOf("The second chat", "The first chat"), session.state.chats.map { it.title })
    }

    @Test
    fun switchingChatsReadsTheOtherOneFromDiskAndTheAppReopensOnTheLastOne() = runTest {
        var session = unlocked()
        exchange(session, "The first chat", "One.")
        val first = session.id
        session.chat.newChat()
        app.clock.advance(1_000)
        exchange(session, "The second chat", "Two.")
        val second = session.id
        session.close()

        session = unlocked()
        assertEquals(second, session.id)
        assertEquals(listOf(second, first), session.state.chats.map { it.id })

        session.chat.openChat(first)
        assertEquals("until it has been read, the chat on screen stays", second, session.id)
        runCurrent()
        assertEquals(first, session.id)
        assertEquals(listOf("The first chat", "One."), session.openSession.messages.map { it.text })

        // Going back needs no disk: it was read at the unlock.
        session.chat.openChat(second)
        assertEquals(second, session.id)
        session.chat.openChat(first)
        assertEquals(first, session.id)
        session.close()

        session = unlocked()
        assertEquals("the chat that was on screen last", first, session.id)
        session.close()
    }

    @Test
    fun anEmptyChatIsForgottenWhenTheUserMovesOn() = runTest {
        var session = unlocked()
        exchange(session, "Kept", "Yes.")
        val kept = session.id
        session.chat.newChat()
        val empty = session.id

        session.chat.openChat(kept)
        assertEquals(kept, session.id)
        session.close()

        assertEquals(listOf("session-index.enc", "sessions/$kept.enc"), app.fileNames())
        session = unlocked()
        assertEquals(listOf(kept), session.state.chats.map { it.id })
        assertFalse(session.state.chats.any { it.id == empty })
        session.close()
    }

    @Test
    fun aDraftIsStoredEncryptedWithItsChatAndComesBackAfterALock() = runTest {
        var session = unlocked()
        val id = session.id

        session.chat.setDraft(id, "A half-written thought")

        assertEquals("a chat with a draft is a chat to keep", listOf(id), session.state.chats.map { it.id })
        assertEquals("", session.state.chats.single().title)
        runCurrent()
        assertNull("typing is written lazily", app.stored(id))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("A half-written thought", app.stored(id)!!.draft)
        assertFalse(app.diskContains("half-written"))

        // Typed just before the app is left: leaving flushes it.
        session.chat.setDraft(id, "A half-written thought, continued")
        session.chat.flush()
        runCurrent()
        assertEquals("A half-written thought, continued", app.stored(id)!!.draft)

        session.close()
        session = unlocked()
        assertEquals(id, session.id)
        assertEquals("A half-written thought, continued", session.openSession.draft)

        // Each chat has its own.
        session.chat.newChat()
        assertEquals("", session.openSession.draft)
        session.chat.openChat(id)
        assertEquals("A half-written thought, continued", session.openSession.draft)

        // Sending uses the draft up.
        exchange(session, session.openSession.draft, "Noted.")
        assertEquals("", session.openSession.draft)
        assertEquals("", app.stored(id)!!.draft)
        session.close()
    }

    @Test
    fun aChatThatOnlyEverHeldADraftGoesWhenTheDraftIsClearedAndTheUserMovesOn() = runTest {
        var session = unlocked()
        exchange(session, "Kept", "Yes.")
        val kept = session.id
        session.chat.newChat()
        val drafted = session.id
        session.chat.setDraft(drafted, "Maybe")
        session.chat.flush()
        runCurrent()
        assertTrue(File(app.layout.sessions, "$drafted.enc").exists())

        session.chat.setDraft(drafted, "")
        session.chat.openChat(kept)
        runCurrent()

        assertEquals(listOf(kept), session.state.chats.map { it.id })
        assertFalse(File(app.layout.sessions, "$drafted.enc").exists())
        session.close()
        session = unlocked()
        assertEquals(listOf(kept), session.state.chats.map { it.id })
        session.close()
    }

    @Test
    fun aReplyKeepsBeingWrittenWhileAnotherChatIsOnScreen() = runTest {
        val session = unlocked()
        val asking = session.id
        app.engine.enqueueReply(listOf("Slow", "ly."), millisBetween = 500)
        session.chat.send(asking, "Take your time")
        runCurrent()

        session.chat.newChat()
        val other = session.id
        assertEquals("the turn belongs to its chat, not to the screen", asking, session.state.turn?.sessionId)
        advanceTimeBy(1_000)
        runCurrent()

        assertNull(session.state.turn)
        assertEquals(other, session.id)
        assertEquals("Slowly.", app.stored(asking)!!.messages.last().text)
        session.chat.openChat(asking)
        assertEquals(listOf("Take your time", "Slowly."), session.openSession.messages.map { it.text })
        assertNull("nothing had to be stopped", session.nextNotice())
    }

    @Test
    fun askingInAnotherChatStopsTheReplyThatWasBeingWritten() = runTest {
        val session = unlocked()
        val first = session.id
        app.engine.enqueueReply(listOf("Part one, ", "part two"), millisBetween = 500)
        session.chat.send(first, "A long question")
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        session.chat.newChat()
        val second = session.id
        app.engine.enqueueReply(listOf("Quick."))

        assertTrue(session.chat.send(second, "A quick one"))

        assertEquals(second, session.state.turn?.sessionId)
        runCurrent()
        assertEquals(ChatNotice.OTHER_REPLY_STOPPED, session.nextNotice())
        assertEquals(listOf("A quick one", "Quick."), session.openSession.messages.map { it.text })
        val stopped = app.stored(first)!!.messages.last()
        assertEquals("Part one, ", stopped.text)
        assertEquals(MessageStatus.STOPPED, stopped.status)
        assertEquals("one generation at a time", 0, app.engine.activeGenerations)
        assertEquals(1, app.engine.cancelledGenerations)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals("Part one, ", app.stored(first)!!.messages.last().text)
    }

    @Test
    fun deletingAChatRemovesItsFileAndItsPlaceInTheList() = runTest {
        var session = unlocked()
        exchange(session, "To be deleted", "Yes.")
        val doomed = session.id
        session.chat.newChat()
        app.clock.advance(1_000)
        exchange(session, "To be kept", "Yes.")
        val kept = session.id

        session.chat.deleteChat(doomed)

        assertEquals(listOf(kept), session.state.chats.map { it.id })
        assertEquals("the chat on screen is not disturbed", kept, session.id)
        runCurrent()
        assertFalse(File(app.layout.sessions, "$doomed.enc").exists())
        session.close()

        assertEquals(listOf("session-index.enc", "sessions/$kept.enc"), app.fileNames())
        session = unlocked()
        assertEquals(listOf(kept), session.state.chats.map { it.id })
        session.close()
    }

    @Test
    fun aChatDeletedWhileItIsStillBeingReadDoesNotComeBack() = runTest {
        var session = unlocked()
        exchange(session, "To be deleted", "Yes.")
        val doomed = session.id
        session.chat.newChat()
        app.clock.advance(1_000)
        exchange(session, "On screen", "Yes.")
        val onScreen = session.id
        session.close()
        session = unlocked()

        session.chat.openChat(doomed)
        session.chat.deleteChat(doomed)
        runCurrent()

        assertEquals(onScreen, session.id)
        assertEquals(listOf(onScreen), session.state.chats.map { it.id })
        assertFalse(File(app.layout.sessions, "$doomed.enc").exists())
        session.close()
        assertEquals(listOf("session-index.enc", "sessions/$onScreen.enc"), app.fileNames())
    }

    @Test
    fun deletingTheChatOnScreenLeavesANewEmptyOne() = runTest {
        var session = unlocked()
        session.chat.selectModel(session.id, boris)
        exchange(session, "To be deleted", "Yes.")
        val doomed = session.id

        session.chat.deleteChat(doomed)

        assertNotEquals(doomed, session.id)
        assertEquals(emptyList<Any>(), session.openSession.messages)
        assertEquals(boris, session.openSession.modelId)
        assertEquals(emptyList<Any>(), session.state.chats)
        session.close()
        assertEquals("only the index is left, and it is empty", listOf("session-index.enc"), app.fileNames())
        session = unlocked()
        assertEquals(emptyList<Any>(), session.state.chats)
        assertEquals(emptyList<Any>(), session.openSession.messages)
        session.close()
    }

    @Test
    fun deletingAChatWhileItIsBeingAnsweredEndsTheReplyAndNothingBringsTheChatBack() = runTest {
        val session = unlocked()
        val doomed = session.id
        app.engine.enqueueReply(listOf("One ", "two ", "three ", "four"), millisBetween = 300)
        session.chat.send(doomed, "Count")
        runCurrent()
        advanceTimeBy(700)
        runCurrent()
        assertTrue(File(app.layout.sessions, "$doomed.enc").exists())

        session.chat.deleteChat(doomed)

        assertNull(session.state.turn)
        assertNotEquals(doomed, session.id)
        runCurrent()
        assertEquals(1, app.engine.cancelledGenerations)
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse("no late word of the reply wrote the file again", File(app.layout.sessions, "$doomed.enc").exists())
        session.close()
        assertEquals(listOf("session-index.enc"), app.fileNames())
    }

    // --- Files that cannot be read -------------------------------------------------------------

    @Test
    fun aChatThatCannotBeReadIsShownAsSuchLeftAsItIsAndCanBeDeleted() = runTest {
        var session = unlocked()
        exchange(session, "Soon to be damaged", "Yes.")
        val id = session.id
        session.close()
        val file = File(app.layout.sessions, "$id.enc")
        val damaged = file.readBytes().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        file.writeBytes(damaged)

        session = unlocked()

        assertEquals("the list still names it", OpenChat.Unreadable(id, "Soon to be damaged"), session.state.open)
        assertEquals(listOf(id), session.state.chats.map { it.id })
        assertFalse(session.chat.send(id, "Hello?"))
        session.chat.setDraft(id, "Hello?")

        // A new chat does not make it go away, and neither does a lock.
        session.chat.newChat()
        exchange(session, "Another chat", "Yes.")
        session.close()
        assertArrayEquals(damaged, file.readBytes())
        assertFalse("no copy is needed of a file that is never written over", app.layout.kept.exists())

        session = unlocked()
        session.chat.openChat(id)
        runCurrent()
        assertEquals(OpenChat.Unreadable(id, "Soon to be damaged"), session.state.open)
        session.chat.deleteChat(id)
        runCurrent()
        assertFalse(file.exists())
        assertEquals(1, session.state.chats.size)
        session.close()
    }

    @Test
    fun aChatThatCanOnlyBeReadInPartSaysSoKeepsItsFileAndCanBeUsed() = runTest {
        app = SessionFixture(temp.root, testScheduler)
        val stored = ChatSession(id = "c1", createdAtMillis = 1, modelId = qwen)
            .withUserMessage("A question", app.clock)
            .withAssistantPlaceholder(app.clock)
            .let { it.applyEvent(it.streamingMessageId!!, GenerationEvent.TextDelta("An answer from a tool"), app.clock) }
            .let { it.finished(it.streamingMessageId!!, FinishReason.END_OF_TURN, app.clock) }
        app.plant(stored) { json -> json.replace("\"role\":\"ASSISTANT\"", "\"role\":\"TOOL\"") }
        val original = File(app.layout.sessions, "c1.enc").readBytes()
        var session = app.open()
        runCurrent()

        session.chat.openChat("c1")
        runCurrent()

        val open = session.state.open as OpenChat.Ready
        assertEquals(SessionStore.Incomplete(skippedMessages = 1, errorDropped = false, fromNewerVersion = false), open.incomplete)
        assertEquals(listOf("A question"), open.session.messages.map { it.text })
        assertArrayEquals(original, app.layout.kept.listFiles()!!.single().readBytes())

        // It is said until the user has read it, also after looking at another chat.
        session.chat.newChat()
        session.chat.openChat("c1")
        assertEquals(1, (session.state.open as OpenChat.Ready).incomplete?.skippedMessages)
        session.chat.incompleteNoticeShown("c1")
        assertNull((session.state.open as OpenChat.Ready).incomplete)

        app.engine.enqueueReply(listOf("A new answer"))
        assertTrue(session.chat.retry("c1"))
        runCurrent()
        session.close()
        assertArrayEquals("the file as it was is still there", original, app.layout.kept.listFiles()!!.single().readBytes())

        session = unlocked()
        assertEquals(listOf("A question", "A new answer"), session.openSession.messages.map { it.text })
        assertNull("what this version wrote, it reads completely", (session.state.open as OpenChat.Ready).incomplete)
        session.close()
    }

    @Test
    fun aChatWhoseFileHasGoneIsDroppedFromTheListWhenItIsOpened() = runTest {
        val session = unlocked()
        exchange(session, "Here today", "Yes.")
        val gone = session.id
        session.chat.newChat()
        app.clock.advance(1_000)
        exchange(session, "Still here", "Yes.")
        session.close()
        // Deleted behind the app's back, and not read since the unlock.
        val reopened = unlocked()
        assertTrue(File(app.layout.sessions, "$gone.enc").delete())

        reopened.chat.openChat(gone)
        runCurrent()

        assertEquals(ChatNotice.CHAT_GONE, reopened.nextNotice())
        assertEquals(1, reopened.state.chats.size)
        assertNotEquals(gone, reopened.id)
        reopened.close()
    }

    @Test
    fun aChatWrittenJustBeforeTheProcessDiedIsFoundAgain() = runTest {
        app = SessionFixture(temp.root, testScheduler)
        // Its file reached the disk; the index that would have listed it did not.
        val orphan = ChatSession(id = "orphan", createdAtMillis = 5, modelId = qwen).withUserMessage("Was I saved?", app.clock)
        app.plant(orphan)

        val session = app.open()
        runCurrent()

        assertEquals(listOf("orphan"), session.state.chats.map { it.id })
        assertEquals("Was I saved?", session.state.chats.single().title)
        assertEquals("the app still starts with a new chat: the orphan was not the one on screen", emptyList<Any>(), session.openSession.messages)
        session.close()
        assertEquals("and the index now lists it", listOf("session-index.enc", "sessions/orphan.enc"), app.fileNames())
    }

    // --- The end of the session ----------------------------------------------------------------

    @Test
    fun shuttingDownStopsTheReplyKeepsItAndLeavesNothingRunning() = runTest {
        var session = unlocked()
        val id = session.id
        app.engine.enqueueReply(listOf("Half ", "way"), millisBetween = 500)
        session.chat.send(id, "Say something long")
        runCurrent()
        advanceTimeBy(500)
        runCurrent()

        session.close()

        assertEquals(0, app.engine.activeGenerations)
        assertEquals(1, app.engine.cancelledGenerations)
        assertEquals(1, app.engine.forgetCount)
        val reply = app.stored(id)!!.messages.last()
        assertEquals("Half ", reply.text)
        assertEquals(MessageStatus.STOPPED, reply.status)

        // The closed session does nothing any more.
        assertFalse(session.chat.send(id, "Too late"))
        session.chat.newChat()
        session.chat.deleteChat(id)
        session.chat.setDraft(id, "Too late")
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, app.stored(id)!!.messages.size)
        assertEquals("", app.stored(id)!!.draft)

        session = unlocked()
        assertEquals("Half ", session.openSession.messages.last().text)
        assertTrue("it can be carried on after the unlock", ChatController.canContinue(session.openSession))
        session.close()
    }
}
