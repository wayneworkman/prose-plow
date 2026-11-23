package us.theworkmans.proseplow.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import us.theworkmans.proseplow.AppSession
import us.theworkmans.proseplow.SessionFixture
import us.theworkmans.proseplow.close
import us.theworkmans.proseplow.core.chat.ChatMessage
import us.theworkmans.proseplow.core.chat.ChatSession
import us.theworkmans.proseplow.core.chat.MessageLifetime.Companion.DAY_MILLIS
import us.theworkmans.proseplow.core.chat.MessageStatus
import us.theworkmans.proseplow.core.chat.Role
import us.theworkmans.proseplow.core.engine.FakeInferenceEngine.Step
import us.theworkmans.proseplow.core.engine.FinishReason
import us.theworkmans.proseplow.openSession
import java.io.File

/**
 * Where the chats meet the model's saved memory: every change of a chat that removes or alters
 * a stored message deletes the chat's memory before the chat is written, through the real
 * session, stores and writer on virtual time; a change that only adds keeps it; and the unlock
 * sweeps what has expired of it before anything else.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SavedMemoryChatTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var app: SessionFixture

    private fun TestScope.unlocked(): AppSession {
        if (!::app.isInitialized) app = SessionFixture(temp.root, testScheduler)
        return app.open().also { runCurrent() }
    }

    private val AppSession.id: String get() = chat.state.value.open.id

    private fun TestScope.exchange(session: AppSession, text: String, reply: String = "An answer to $text", finishReason: FinishReason = FinishReason.END_OF_TURN) {
        app.engine.enqueueReply(listOf(reply), finishReason = finishReason)
        assertTrue(session.chat.send(session.id, text))
        runCurrent()
    }

    /** A memory file for the chat [id], as a save would have left it; its content is not read here. */
    private fun plantMemory(id: String): File = File(app.layout.modelMemory, "$id.enc").apply {
        parentFile!!.mkdirs()
        writeText("a memory")
        setLastModified(app.clock.nowMillis())
    }

    private fun memoryOf(id: String) = File(app.layout.modelMemory, "$id.enc")

    /** Catches a memory deleted by what only adds to a chat (which would make the feature useless), or kept by an edit. */
    @Test
    fun anAnswerAddedOrCarriedOnKeepsTheMemoryAndAnEditDeletesIt() = runTest {
        val session = unlocked()
        exchange(session, "First question")
        val id = session.id
        plantMemory(id)

        exchange(session, "Second question")
        assertTrue("an answer added", memoryOf(id).exists())
        exchange(session, "Third question", "Cut off at the", FinishReason.LENGTH)
        app.engine.enqueueReply(listOf(" limit, and carried on."))
        assertTrue(session.chat.continueReply(id, session.openSession.messages.last().id))
        runCurrent()
        assertTrue("an answer carried on", memoryOf(id).exists())

        val first = session.openSession.messages.first { it.role == Role.USER }
        app.engine.enqueueReply(listOf("A new answer"))
        assertTrue(session.chat.edit(id, first.id, "First question, edited"))
        runCurrent()
        assertFalse("an edit", memoryOf(id).exists())
        assertEquals("First question, edited", app.stored(id)!!.messages.first().text)
        session.close()
    }

    /** Catches an answer asked for again, or a chat deleted, leaving the memory of what was removed. */
    @Test
    fun anAnswerAskedForAgainAndADeletedChatDeleteTheMemory() = runTest {
        val session = unlocked()
        exchange(session, "A question")
        val id = session.id
        plantMemory(id)
        app.engine.enqueueReply(listOf("Another answer"))
        assertTrue(session.chat.regenerate(id, session.openSession.messages.last().id))
        runCurrent()
        assertFalse("an answer asked for again", memoryOf(id).exists())

        plantMemory(id)
        session.chat.deleteChat(id)
        runCurrent()
        assertFalse("a deleted chat", memoryOf(id).exists())
        assertFalse(File(app.layout.sessions, "$id.enc").exists())
        session.close()
    }

    /** Catches a prune while unlocked that leaves the memory of the turns it removed. */
    @Test
    fun aPruneWhileUnlockedDeletesTheMemory() = runTest {
        val session = unlocked()
        exchange(session, "Old question")
        app.clock.advance(2 * DAY_MILLIS)
        exchange(session, "Newer question")
        val id = session.id
        plantMemory(id)
        app.clock.advance(5 * DAY_MILLIS + 1)
        session.chat.expireMessages()
        runCurrent()
        assertEquals(2, app.stored(id)!!.messages.size)
        assertFalse(memoryOf(id).exists())
        session.close()
    }

    /**
     * Catches the deletion being lost when a newer write of the same chat takes the place of the
     * one that was to carry it out before it ran (the writer keeps only the newest write of a file).
     */
    @Test
    fun theDeletionSurvivesANewerWriteOfTheSameChat() = runTest {
        val session = unlocked()
        exchange(session, "A question")
        val id = session.id
        plantMemory(id)
        val question = session.openSession.messages.first()
        app.engine.enqueueReply(listOf("An answer to the edit"))
        assertTrue(session.chat.edit(id, question.id, "An edited question"))
        session.chat.setDraft(id, "Something being typed")
        runCurrent()
        assertFalse(memoryOf(id).exists())
        session.close()
    }

    /**
     * A system prompt that is changed or cleared stays neither in the saved memory nor in what
     * the store may save next: the memory file goes at once, and the chat the engine holds has no
     * conversation to save until the engine has read a request with the new prompt. Catches a
     * replaced system prompt kept in the memory file.
     */
    @Test
    fun aChangedOrClearedSystemPromptDeletesTheMemoryAndHidesTheHeldChat() = runTest {
        val session = unlocked()
        val model = session.openSession.modelId
        session.settings.update(urgent = true) { it.withSystemPrompt(model, "First prompt") }
        exchange(session, "A question")
        val id = session.id
        val key = ChatController.conversationKeyOf(id)
        plantMemory(id)
        assertNotNull(session.chat.conversation(key))

        session.settings.update { it.withSystemPrompt(model, "Second prompt") }
        assertNull("hidden at once, inside the change", session.chat.conversation(key))
        runCurrent()
        assertFalse("deleted at once, not with the next answer", memoryOf(id).exists())

        exchange(session, "Another question")
        assertNotNull("known again once the engine has read a request with the new prompt", session.chat.conversation(key))
        assertEquals("Second prompt", app.engine.requests.last().systemPrompt)

        plantMemory(id)
        session.settings.update { it.withSystemPrompt(model, "") }
        assertNull("cleared counts as changed", session.chat.conversation(key))
        runCurrent()
        assertFalse(memoryOf(id).exists())

        // Anything else of the settings leaves both alone.
        exchange(session, "And a third")
        plantMemory(id)
        session.settings.update(urgent = true) { it.withTemperature(model, 0.3f) }
        runCurrent()
        assertTrue(memoryOf(id).exists())
        assertNotNull(session.chat.conversation(key))
        session.close()
    }

    /**
     * A deleted chat does not stay in the model's memory: deleting the chat the engine holds
     * makes the engine forget, and deleting another does not. Catches a deletion
     * that left the deleted chat's text in the engine until the lock.
     */
    @Test
    fun deletingTheChatTheEngineHoldsMakesTheEngineForgetIt() = runTest {
        val session = unlocked()
        exchange(session, "Not held for long")
        val other = session.id
        session.chat.newChat()
        exchange(session, "Held")
        val held = session.id

        session.chat.deleteChat(other)
        runCurrent()
        assertEquals("another chat: nothing to forget", 0, app.engine.forgetCount)

        session.chat.deleteChat(held)
        assertEquals("marked at once", 1, app.engine.notToBeSavedMarks)
        runCurrent()
        assertEquals(1, app.engine.forgetCount)
        session.close()
    }

    /**
     * A request that is given up before the engine has read any of it leaves the engine holding
     * what it held: a prune of that chat still makes the engine forget. Catches a chat
     * the engine was last given that is taken for what the engine holds.
     */
    @Test
    fun aRequestGivenUpBeforeTheEngineReadItLeavesThePreviousChatForgettable() = runTest {
        val session = unlocked()
        exchange(session, "Old question")
        val held = session.id
        app.clock.advance(2 * DAY_MILLIS)
        session.chat.newChat()
        // Given up before the engine shows that it read anything of it.
        app.engine.enqueue(listOf(Step.Hang))
        assertTrue(session.chat.send(session.id, "Stopped at once"))
        runCurrent()
        session.chat.stop(session.id)
        runCurrent()

        app.clock.advance(5 * DAY_MILLIS + 1)
        session.chat.expireMessages()
        assertEquals("the chat the engine really holds is marked", 1, app.engine.notToBeSavedMarks)
        runCurrent()
        assertEquals("and forgotten", 1, app.engine.forgetCount)
        assertNull(app.stored(held))
        session.close()
    }

    /** Catches an unlock that restores before what has expired of the memory is gone. */
    @Test
    fun theUnlockSweepsTheMemoryBeforeAnythingElse() = runTest {
        val first = unlocked()
        exchange(first, "A question")
        val id = first.id
        first.close()
        runCurrent()

        plantMemory(id)
        plantMemory("no-such-chat")
        File(app.layout.modelMemory, "$id.enc.tmp").writeText("an interrupted save")
        unlocked().close()
        assertEquals("a memory without its chat and a temporary file go; one within the limit stays", listOf("$id.enc"), app.layout.modelMemory.list()!!.sorted())

        memoryOf(id).setLastModified(app.clock.nowMillis())
        app.clock.advance(7 * DAY_MILLIS + 1_000)
        unlocked().close()
        assertFalse("a memory whose date is past the limit goes", memoryOf(id).exists())
    }

    /** The rule itself, case by case: only what leaves every stored message as it was keeps the memory. */
    @Test
    fun whatKeepsASavedMemory() {
        fun message(id: String, role: Role, text: String, at: Long = 1_000, status: MessageStatus = MessageStatus.COMPLETE) =
            ChatMessage(id = id, role = role, text = text, status = status, createdAtMillis = at)
        val before = ChatSession(
            id = "c", createdAtMillis = 1_000, modelId = "m",
            messages = listOf(message("u1", Role.USER, "Question"), message("a1", Role.ASSISTANT, "Answer", status = MessageStatus.STOPPED)),
        )
        fun after(vararg messages: ChatMessage) = before.copy(messages = messages.toList())
        val (question, answer) = before.messages
        val keeps = ChatController::keepsSavedMemory

        assertTrue("the same", keeps(before, before.copy(draft = "typing")))
        assertTrue("a message added", keeps(before, after(question, answer, message("u2", Role.USER, "More"))))
        assertTrue("an answer carried on", keeps(before, after(question, answer.copy(text = "Answer, carried on", status = MessageStatus.STREAMING))))
        assertFalse("a message removed", keeps(before, after(question)))
        assertFalse("a text changed", keeps(before, after(question.copy(text = "Questio"), answer)))
        assertFalse("a text cut at its end", keeps(before, after(question, answer.copy(text = "Answe"))))
        assertFalse("a date changed", keeps(before, after(question.copy(createdAtMillis = 2_000), answer)))
        assertFalse("another message in its place", keeps(before, after(question, answer.copy(id = "a2"))))

        val writing = after(question, message("a2", Role.ASSISTANT, "Partial", status = MessageStatus.STREAMING))
        assertTrue("the message being written may change", keeps(writing, after(question, message("a2", Role.ASSISTANT, "Part"))))
        assertTrue("and go, when it failed empty", keeps(writing, after(question)))
    }
}
