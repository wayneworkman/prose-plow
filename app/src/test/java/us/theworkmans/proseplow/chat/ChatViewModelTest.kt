package us.theworkmans.proseplow.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import us.theworkmans.proseplow.AppSession
import us.theworkmans.proseplow.SessionFixture
import us.theworkmans.proseplow.close
import us.theworkmans.proseplow.core.chat.ChatSession
import us.theworkmans.proseplow.core.engine.EngineError
import us.theworkmans.proseplow.core.engine.FakeInferenceEngine.Step
import us.theworkmans.proseplow.core.engine.FinishReason
import us.theworkmans.proseplow.core.engine.GenerationEvent
import us.theworkmans.proseplow.core.engine.GenerationStats
import us.theworkmans.proseplow.core.model.ModelCatalog
import us.theworkmans.proseplow.data.SettingsStore
import us.theworkmans.proseplow.openSession
import java.io.File

/**
 * The chat screen's ViewModel over the real session: what is in the input field and when, the
 * edit mode, the dialogs, and what the screen is told about the reply and the model.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()
    private val qwen = ModelCatalog.QWEN35_0_8B.id
    private val boris = ModelCatalog.BORIS_2_INSTRUCT.id

    private val app by lazy { SessionFixture(temp.root, scheduler) }
    private lateinit var session: AppSession
    private lateinit var viewModel: ChatViewModel

    @Before
    fun viewModelsRunOnTheTestDispatcher() = Dispatchers.setMain(StandardTestDispatcher(scheduler))

    @After
    fun restoreTheMainDispatcher() = Dispatchers.resetMain()

    /** The unlocked app with the chat screen in front. */
    private fun unlocked() {
        session = app.open()
        scheduler.runCurrent()
        viewModel = ChatViewModel(session.chat, session.settings, session.writeFailure, session::writeFailureShown)
        scheduler.runCurrent()
    }

    private val state: ChatUiState
        get() {
            scheduler.runCurrent()
            return viewModel.state.value
        }

    private fun exchange(text: String, reply: String) {
        app.engine.enqueueReply(listOf(reply))
        viewModel.onInputChange(text)
        viewModel.send()
        scheduler.runCurrent()
    }

    // --- The input field -----------------------------------------------------------------------

    @Test
    fun whatIsTypedIsTheChatsDraftAndSendingClearsIt() = runTest(StandardTestDispatcher(scheduler)) {
        unlocked()
        assertEquals("", viewModel.inputText)

        viewModel.onInputChange("What is a fur")
        viewModel.onInputChange("What is a furrow?")
        assertEquals("What is a furrow?", viewModel.inputText)
        assertEquals("every change goes to the chat, to be stored encrypted", "What is a furrow?", session.openSession.draft)

        app.engine.enqueueReply(listOf("A trench."), millisBetween = 100)
        viewModel.send()
        assertEquals("", viewModel.inputText)
        assertEquals("", session.openSession.draft)
        assertTrue(state.generating)
        advanceTimeBy(100)
        assertFalse(state.generating)
        assertEquals(listOf("What is a furrow?", "A trench."), state.session!!.messages.map { it.text })
    }

    /**
     * Send, an edit, and whether the button is offered all go by one definition of a blank
     * message: the prompt's own stripping rule. U+0085 is whitespace to the models but not to
     * Kotlin's `isBlank`, which offered a button that then did nothing.
     */
    @Test
    fun theButtonIsOfferedExactlyForWhatCanBeSentOrSavedAsAnEdit() = runTest(StandardTestDispatcher(scheduler)) {
        unlocked()
        for (blank in listOf("", "   ", "\u0085", "\u001F\n", "\u3000\u00A0")) {
            viewModel.onInputChange(blank)
            assertFalse("offered for ${blank.map { it.code }}", viewModel.inputSendable)
            viewModel.send()
            assertEquals(emptyList<Any>(), state.session!!.messages)
        }
        viewModel.onInputChange("\u200B") // Not whitespace to Python's strip: the model would read it.
        assertTrue(viewModel.inputSendable)

        exchange("A question", "An answer")
        viewModel.startEdit(state.session!!.messages[0].id)
        viewModel.onInputChange("\u0085 ")
        assertFalse(viewModel.inputSendable)
        viewModel.send()
        runCurrent()
        assertEquals(listOf("A question", "An answer"), state.session!!.messages.map { it.text })
        assertEquals("the edit is still open", state.session!!.messages[0].id, state.editingMessageId)
    }

    @Test
    fun aMessageThatCannotBeSentStaysInTheField() = runTest(StandardTestDispatcher(scheduler)) {
        unlocked()
        viewModel.onInputChange("   ")
        viewModel.send()
        assertEquals("   ", viewModel.inputText)
        assertEquals(emptyList<Any>(), state.session!!.messages)
    }

    @Test
    fun theFieldShowsTheDraftOfWhicheverChatIsOnScreen() = runTest(StandardTestDispatcher(scheduler)) {
        unlocked()
        exchange("The first chat", "Yes.")
        val first = state.chat.open.id
        viewModel.onInputChange("Draft of the first")

        viewModel.newChat()
        runCurrent()
        assertEquals("a new chat starts with an empty field", "", viewModel.inputText)
        viewModel.onInputChange("Draft of the second")
        val second = state.chat.open.id

        viewModel.openChat(first)
        runCurrent()
        assertEquals("Draft of the first", viewModel.inputText)
        viewModel.openChat(second)
        runCurrent()
        assertEquals("Draft of the second", viewModel.inputText)
    }

    @Test
    fun theDraftComesBackAfterALockFromTheEncryptedChatAndFromNowhereElse() = runTest(StandardTestDispatcher(scheduler)) {
        unlocked()
        viewModel.onInputChange("Not sent yet")
        session.clearScreens()
        session.close()
        assertFalse(app.diskContains("Not sent"))

        unlocked()
        assertEquals("Not sent yet", viewModel.inputText)
    }

    // --- Editing -------------------------------------------------------------------------------

    @Test
    fun editingPutsTheMessageInTheFieldAndSendingReplacesItAndAsksAgain() = runTest(StandardTestDispatcher(scheduler)) {
        unlocked()
        exchange("What is a furow?", "I do not know that word.")
        exchange("Never mind", "All right.")
        viewModel.onInputChange("An unsent draft")
        val question = state.session!!.messages[0]

        viewModel.startEdit(question.id)

        assertEquals("What is a furow?", viewModel.inputText)
        assertEquals(question.id, state.editingMessageId)
        viewModel.onInputChange("What is a furrow?")
        assertEquals("typing the edit does not touch the chat's own draft", "An unsent draft", session.openSession.draft)

        app.engine.enqueueReply(listOf("A trench."))
        viewModel.send()
        runCurrent()

        assertNull(state.editingMessageId)
        assertEquals(listOf("What is a furrow?", "A trench."), state.session!!.messages.map { it.text })
        assertEquals("the draft is back in the field", "An unsent draft", viewModel.inputText)
        assertEquals("An unsent draft", session.openSession.draft)
    }

    @Test
    fun anEditCanBeCancelledAndLeavesEverythingAsItWas() = runTest(StandardTestDispatcher(scheduler)) {
        unlocked()
        exchange("A question", "An answer")
        viewModel.onInputChange("An unsent draft")
        val before = session.openSession

        viewModel.startEdit(before.messages[0].id)
        viewModel.onInputChange("A question, changed")
        viewModel.cancelEdit()

        assertNull(state.editingMessageId)
        assertEquals("An unsent draft", viewModel.inputText)
        assertEquals(before, session.openSession)
    }

    @Test
    fun onlyTheUsersOwnMessagesCanBeEditedAndNotWhileAReplyIsBeingWritten() = runTest(StandardTestDispatcher(scheduler)) {
        unlocked()
        exchange("A question", "An answer")
        viewModel.startEdit(state.session!!.messages[1].id)
        assertNull(state.editingMessageId)
        viewModel.startEdit("no such message")
        assertNull(state.editingMessageId)

        app.engine.enqueue(listOf(Step.Hang))
        viewModel.onInputChange("Another")
        viewModel.send()
        runCurrent()
        viewModel.startEdit(state.session!!.messages[0].id)
        assertNull(state.editingMessageId)
        viewModel.stop()
        assertFalse(state.generating)
    }

    @Test
    fun switchingChatsEndsAnEdit() = runTest(StandardTestDispatcher(scheduler)) {
        unlocked()
        exchange("A question", "An answer")
        viewModel.startEdit(state.session!!.messages[0].id)
        viewModel.onInputChange("Half-edited")

        viewModel.newChat()
        runCurrent()

        assertNull(state.editingMessageId)
        assertEquals("", viewModel.inputText)
    }

    @Test
    fun aMessageThatIsTooLongIsFixedByEditingIt() = runTest(StandardTestDispatcher(scheduler)) {
        unlocked()
        app.engine.enqueueFailure(EngineError.PromptTooLong(promptTokens = 5_000, contextSize = 4_096))
        viewModel.onInputChange("A very long message")
        viewModel.send()
        runCurrent()
        val error = state.session!!.error!!
        assertEquals(5_000, error.promptTokens)

        viewModel.startEdit(error.userMessageId!!)
        viewModel.onInputChange("A shorter message")
        app.engine.enqueueReply(listOf("Fine."))
        viewModel.send()
        runCurrent()

        assertNull(state.session!!.error)
        assertEquals(listOf("A shorter message", "Fine."), state.session!!.messages.map { it.text })
    }

    // --- Actions on messages -------------------------------------------------------------------

    @Test
    fun retryRegenerateAndContinueActOnTheChatOnScreen() = runTest(StandardTestDispatcher(scheduler)) {
        unlocked()
        app.engine.enqueueFailure(EngineError.Native(1, "failed"))
        viewModel.onInputChange("Hello")
        viewModel.send()
        runCurrent()
        assertFalse("an error brings its own Retry", state.offerRetry)

        app.engine.enqueueReply(listOf("Once"), finishReason = FinishReason.LENGTH)
        viewModel.retry()
        runCurrent()
        assertEquals(listOf("Hello", "Once"), state.session!!.messages.map { it.text })

        app.engine.enqueueReply(listOf(" more."))
        viewModel.continueReply(state.session!!.messages[1].id)
        runCurrent()
        assertEquals("Once more.", state.session!!.messages[1].text)

        app.engine.enqueueReply(listOf("Differently."))
        viewModel.regenerate(state.session!!.messages[1].id)
        runCurrent()
        assertEquals(listOf("Hello", "Differently."), state.session!!.messages.map { it.text })
    }

    @Test
    fun aTurnWithoutAnAnswerOrAnErrorOffersToTryAgain() = runTest(StandardTestDispatcher(scheduler)) {
        unlocked()
        assertFalse("not in an empty chat", state.offerRetry)
        app.engine.enqueue(listOf(Step.Hang))
        viewModel.onInputChange("Hello")
        viewModel.send()
        runCurrent()
        assertFalse("not while the reply is being written", state.offerRetry)

        viewModel.stop()
        assertTrue("stopped before it said anything", state.offerRetry)

        app.engine.enqueueReply(listOf("Hi."))
        viewModel.retry()
        runCurrent()
        assertFalse(state.offerRetry)
    }

    // --- Deleting ------------------------------------------------------------------------------

    @Test
    fun deletingAsksFirstAndOnlyDeletesWhenConfirmed() = runTest(StandardTestDispatcher(scheduler)) {
        unlocked()
        exchange("A chat to delete", "Yes.")
        val id = state.chat.open.id

        viewModel.requestDelete(id)
        assertEquals(DeleteRequest(id, "A chat to delete"), state.deleteRequest)
        viewModel.dismissDelete()
        assertNull(state.deleteRequest)
        assertEquals(listOf(id), state.chat.chats.map { it.id })

        viewModel.requestDelete(id)
        viewModel.confirmDelete()
        runCurrent()
        assertNull(state.deleteRequest)
        assertEquals(emptyList<Any>(), state.chat.chats)
        assertEquals("a new chat is on screen", emptyList<Any>(), state.session!!.messages)
        assertNull(app.stored(id))

        viewModel.requestDelete("no such chat")
        assertNull(state.deleteRequest)
        viewModel.confirmDelete()
    }

    @Test
    fun aChatThatCannotBeReadCanBeDeletedFromItsOwnScreen() = runTest(StandardTestDispatcher(scheduler)) {
        unlocked()
        exchange("Soon unreadable", "Yes.")
        val id = state.chat.open.id
        session.close()
        File(app.layout.sessions, "$id.enc").writeBytes(byteArrayOf(1, 2, 3))

        unlocked()
        assertNull(state.session)
        assertFalse(state.canWrite)
        assertNull(state.modelBanner)

        viewModel.requestDelete(id)
        assertEquals(DeleteRequest(id, "Soon unreadable"), state.deleteRequest)
        viewModel.confirmDelete()
        runCurrent()
        assertEquals(emptyList<Any>(), state.session!!.messages)
        assertFalse(File(app.layout.sessions, "$id.enc").exists())
    }

    // --- What the screen is told about the reply and the model ---------------------------------

    @Test
    fun aReplySaysWhatItIsWaitingFor() = runTest(StandardTestDispatcher(scheduler)) {
        app.engine.loadMillis = 1_000
        unlocked()
        assertEquals("the model is loading for no reply: the strip under the top bar says so", ModelBanner.Loading(qwen, 0f), state.modelBanner)
        assertNull(state.replyStatus)

        app.engine.enqueue(
            listOf(
                Step.Emit(GenerationEvent.PromptProgress(10, 40)),
                Step.Wait(100),
                Step.Emit(GenerationEvent.TextDelta("Hi")),
                Step.Wait(100),
                Step.Emit(GenerationEvent.Done(FinishReason.END_OF_TURN, text = "Hi", stats = GenerationStats(40, 0, 1, 0, 0))),
            ),
        )
        viewModel.onInputChange("Hello")
        viewModel.send()
        assertEquals("now a reply is waiting for it, and says so itself", ReplyStatus.LoadingModel(qwen, 0f), state.replyStatus)
        assertNull(state.modelBanner)

        advanceTimeBy(1_000)
        assertEquals(ReplyStatus.Reading(0.25f), state.replyStatus)
        advanceTimeBy(100)
        assertEquals(ReplyStatus.Writing, state.replyStatus)
        advanceTimeBy(100)
        assertNull(state.replyStatus)
        assertNull(state.modelBanner)
    }

    @Test
    fun aModelThatFailedToLoadIsSaidOnceWhereverItMatters() = runTest(StandardTestDispatcher(scheduler)) {
        app.engine.loadFailure = EngineError.LoadFailed(qwen, "No native engine in this build")
        unlocked()
        assertEquals(ModelBanner.Failed(qwen, "No native engine in this build"), state.modelBanner)

        // A reply that fails for the same reason shows it as its own error; the strip does not repeat it.
        app.engine.loadFailure = EngineError.LoadFailed(qwen, "No native engine in this build")
        viewModel.onInputChange("Hello")
        viewModel.send()
        runCurrent()
        assertNull(state.modelBanner)
        assertEquals("No native engine in this build", state.session!!.error?.detail)

        // Trying again from the strip or the error loads it.
        viewModel.reloadModel()
        runCurrent()
        assertNull(state.modelBanner)
        app.engine.enqueueReply(listOf("Hi."))
        viewModel.retry()
        runCurrent()
        assertNull(state.session!!.error)
    }

    @Test
    fun aChatWhoseModelIsNotInThisBuildSaysSoUntilAnotherIsChosen() = runTest(StandardTestDispatcher(scheduler)) {
        app.plant(ChatSession(id = "old", createdAtMillis = 1, modelId = "retired-model").withUserMessage("From an older build", app.clock))
        unlocked()
        viewModel.openChat("old")
        runCurrent()

        assertEquals(ModelBanner.NotOffered("retired-model"), state.modelBanner)
        assertFalse(state.canWrite)
        assertFalse(state.offerRetry)

        viewModel.selectModel(boris)
        runCurrent()
        assertNull(state.modelBanner)
        assertTrue(state.canWrite)
        assertTrue("the question that never got an answer can now be asked again", state.offerRetry)
    }

    @Test
    fun aBuildWithoutAnyModelSaysSoAndTakesNoMessage() = runTest(StandardTestDispatcher(scheduler)) {
        app.models = emptyList()
        unlocked()

        assertEquals(ModelBanner.NoModels, state.modelBanner)
        assertFalse(state.canWrite)
        viewModel.onInputChange("Anyone there?")
        viewModel.send()
        assertEquals("the message stays in the field", "Anyone there?", viewModel.inputText)
        assertEquals(emptyList<Any>(), state.session!!.messages)
        assertEquals(emptyList<String>(), app.engine.loads)
    }

    @Test
    fun aReplyBeingWrittenInAnotherChatIsKnownHere() = runTest(StandardTestDispatcher(scheduler)) {
        unlocked()
        app.engine.enqueue(listOf(Step.Hang))
        viewModel.onInputChange("Take your time")
        viewModel.send()
        runCurrent()
        val asking = state.chat.open.id
        assertTrue(state.generating)

        viewModel.newChat()
        runCurrent()
        assertEquals("the list of chats can mark the chat that is being answered", asking, state.chat.turn?.sessionId)
        assertFalse(state.generating)
        assertNull(state.replyStatus)
        session.close()
    }

    @Test
    fun theOldestMessagesLeftOutOfAReplyAreCounted() = runTest(StandardTestDispatcher(scheduler)) {
        unlocked()
        app.engine.enqueue(
            listOf(
                Step.Emit(GenerationEvent.HistoryTrimmed(droppedMessages = 4, keptMessages = 2)),
                Step.Emit(GenerationEvent.TextDelta("Hi")),
                Step.Emit(GenerationEvent.Done(FinishReason.END_OF_TURN, text = "Hi", stats = GenerationStats(0, 0, 0, 0, 0))),
            ),
        )
        assertNull(state.trimmedMessages)
        viewModel.onInputChange("Hello")
        viewModel.send()
        runCurrent()
        assertEquals(4, state.trimmedMessages)
    }

    @Test
    fun whatBecameOfTheContextIsSaidForTheChatOnScreen() = runTest(StandardTestDispatcher(scheduler)) {
        unlocked()
        app.engine.enqueue(
            listOf(
                Step.Emit(GenerationEvent.ContextGrown(fromTokens = 4096, toTokens = 8192, rereading = false)),
                Step.Emit(GenerationEvent.TextDelta("Hi")),
                Step.Emit(GenerationEvent.Done(FinishReason.END_OF_TURN, text = "Hi", stats = GenerationStats(0, 0, 0, 0, 0))),
            ),
        )
        assertNull(state.contextNote)
        viewModel.onInputChange("Hello")
        viewModel.send()
        runCurrent()
        assertEquals(ContextNote(grownTo = 8192), state.contextNote)
        assertNull("nothing was left out", state.trimmedMessages)
    }

    // --- Things said once ----------------------------------------------------------------------

    @Test
    fun aLinkIsOnlyRememberedUntilTheUserHasDecided() = runTest(StandardTestDispatcher(scheduler)) {
        unlocked()
        viewModel.onLinkTapped("https://example.org/page")
        assertEquals("https://example.org/page", state.linkRequest)
        viewModel.dismissLink()
        assertNull(state.linkRequest)
    }

    @Test
    fun aFailedWriteAndUnreadableSettingsReachTheScreenUntilTheyAreAcknowledged() = runTest(StandardTestDispatcher(scheduler)) {
        app.layout.settings.writeBytes(byteArrayOf(1, 2, 3))
        unlocked()
        assertEquals(SettingsStore.LoadIssue.UNREADABLE, state.settingsIssue)
        viewModel.settingsIssueShown()
        assertNull(state.settingsIssue)

        assertNull(state.writeFailure)
        app.layout.sessions.writeText("in the way")
        exchange("Hello", "Hi.")
        assertEquals("IOException", state.writeFailure)
        viewModel.writeFailureShown()
        assertNull(state.writeFailure)
    }

    @Test
    fun aChatThatWasOnlyPartlyReadSaysSoUntilAcknowledged() = runTest(StandardTestDispatcher(scheduler)) {
        val stored = ChatSession(id = "c1", createdAtMillis = 1, modelId = qwen)
            .withUserMessage("A question", app.clock)
            .withAssistantPlaceholder(app.clock)
            .let { it.finished(it.streamingMessageId!!, FinishReason.END_OF_TURN, app.clock) }
        app.plant(stored) { json -> json.replace("\"role\":\"ASSISTANT\"", "\"role\":\"TOOL\"") }
        unlocked()
        viewModel.openChat("c1")
        runCurrent()

        assertEquals(1, (state.chat.open as OpenChat.Ready).incomplete?.skippedMessages)
        viewModel.incompleteNoticeShown()
        assertNull((state.chat.open as OpenChat.Ready).incomplete)
    }
}
