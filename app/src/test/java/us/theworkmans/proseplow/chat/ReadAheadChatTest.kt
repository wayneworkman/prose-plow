package us.theworkmans.proseplow.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import us.theworkmans.proseplow.AppSession
import us.theworkmans.proseplow.SessionFixture
import us.theworkmans.proseplow.close
import us.theworkmans.proseplow.core.chat.MessageLifetime
import us.theworkmans.proseplow.core.chat.Role
import us.theworkmans.proseplow.core.engine.EngineError
import us.theworkmans.proseplow.core.engine.FakeInferenceEngine.Step
import us.theworkmans.proseplow.core.engine.FinishReason
import us.theworkmans.proseplow.core.engine.GenerationEvent
import us.theworkmans.proseplow.core.engine.MemorySaveResult
import us.theworkmans.proseplow.core.engine.ReadAheadResult
import us.theworkmans.proseplow.core.engine.SaveTrigger
import us.theworkmans.proseplow.core.model.ModelCatalog
import us.theworkmans.proseplow.core.prompt.PromptMessage
import us.theworkmans.proseplow.core.prompt.PromptRole
import us.theworkmans.proseplow.openSession
import us.theworkmans.proseplow.security.SessionClose

/**
 * When the chats ask the engine to read a conversation ahead ([ChatController]'s rule "the next
 * message finds the conversation read"), and what goes before it. Driven through the real
 * session with a scripted engine on virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadAheadChatTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var app: SessionFixture

    private fun TestScope.unlocked(): AppSession {
        if (!::app.isInitialized) app = SessionFixture(temp.root, testScheduler)
        return app.open().also { runCurrent() }
    }

    private val AppSession.id: String get() = chat.state.value.open.id

    /** Sends [text] and plays [pieces] to their end with [finishReason]. */
    private fun TestScope.exchange(session: AppSession, text: String, pieces: List<String> = listOf("An answer."), finishReason: FinishReason = FinishReason.END_OF_TURN) {
        app.engine.enqueueReply(pieces, finishReason = finishReason)
        assertTrue(session.chat.send(session.id, text))
        runCurrent()
        assertFalse(session.openSession.isGenerating)
    }

    /** Sends [text] and stops the answer after its first piece. */
    private fun TestScope.stoppedExchange(session: AppSession, text: String) {
        app.engine.enqueue(listOf(Step.Emit(GenerationEvent.TextDelta("Half an answer")), Step.Hang))
        assertTrue(session.chat.send(session.id, text))
        runCurrent()
        session.chat.stop(session.id)
        runCurrent()
        assertFalse(session.openSession.isGenerating)
    }

    // --- When ---

    /**
     * Catches a read-ahead that does not follow an answer that ended by itself, one made for the
     * wrong conversation, and one that asks the engine for anything but the conversation as the
     * next message will carry it.
     */
    @Test
    fun `an answer that ended by itself is read ahead at once, as the next message will carry it`() = runTest {
        val session = unlocked()
        exchange(session, "First question", listOf(" First answer. "))
        assertEquals(1, app.engine.readAheads.size)
        val asked = app.engine.readAheads.single()
        assertEquals(ChatController.conversationKeyOf(session.id), asked.conversationKey)
        assertEquals(listOf(PromptMessage(PromptRole.USER, "First question"), PromptMessage(PromptRole.ASSISTANT, "First answer.")), asked.messages)
        assertFalse(asked.continueFinalMessage)
        assertEquals(app.engine.requests.last().copy(messages = asked.messages), asked)

        exchange(session, "Second question", listOf("Ends at a stop string"), FinishReason.STOP_STRING)
        assertEquals(2, app.engine.readAheads.size)
        session.close()
    }

    /**
     * Catches an answer that can be continued being read ahead before the user shows that a new
     * message is coming, which would make "Continue" read it again; and a read-ahead started
     * again for every keystroke.
     */
    @Test
    fun `an answer that can be continued is read ahead only once a new message is typed`() = runTest {
        val session = unlocked()
        val id = session.id
        exchange(session, "A question", listOf("Cut off at the"), FinishReason.LENGTH)
        assertEquals(0, app.engine.readAheads.size)
        session.chat.setDraft(id, "  ")
        runCurrent()
        assertEquals("a blank draft is no message", 0, app.engine.readAheads.size)
        session.chat.setDraft(id, "N")
        runCurrent()
        assertEquals(1, app.engine.readAheads.size)
        session.chat.setDraft(id, "Ne")
        session.chat.setDraft(id, "New")
        runCurrent()
        assertEquals("not again for every keystroke", 1, app.engine.readAheads.size)
        session.close()

        val other = unlockedAgainWithStoppedAnswer()
        assertEquals(0, app.engine.readAheads.size)
        other.chat.setDraft(other.id, "Another")
        runCurrent()
        assertEquals(1, app.engine.readAheads.size)
        other.close()
    }

    /** A second session in which an answer is stopped by the user; the engine's record is cleared first. */
    private fun TestScope.unlockedAgainWithStoppedAnswer(): AppSession {
        app.engine.readAheads.clear()
        val session = app.open().also { runCurrent() }
        session.chat.newChat()
        runCurrent()
        stoppedExchange(session, "Tell me")
        return session
    }

    /** Catches a continuable answer that waits for typing when the next message was being typed all along. */
    @Test
    fun `an answer that can be continued is read ahead at once when a message is already being typed`() = runTest {
        val session = unlocked()
        val id = session.id
        app.engine.enqueueReply(listOf("Cut off"), millisBetween = 1_000, finishReason = FinishReason.LENGTH)
        assertTrue(session.chat.send(id, "A question"))
        runCurrent()
        session.chat.setDraft(id, "The next one")
        runCurrent()
        assertEquals("nothing while the answer is written", 0, app.engine.readAheads.size)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(1, app.engine.readAheads.size)
        session.close()
    }

    /**
     * Catches a read-ahead started out of sight, on coming back, on opening a chat, or behind
     * the lock; and one that typing does not start for an answer that ended out of sight.
     */
    @Test
    fun `never out of sight, on coming back, on opening a chat or behind the lock`() = runTest {
        val session = unlocked()
        val id = session.id
        app.engine.enqueueReply(listOf("Written", " out of sight"), millisBetween = 1_000)
        assertTrue(session.chat.send(id, "A question"))
        runCurrent()
        session.leftScreen()
        advanceTimeBy(3_000)
        runCurrent()
        assertFalse(session.openSession.isGenerating)
        assertEquals("out of sight", 0, app.engine.readAheads.size)
        session.cameOnScreen()
        session.chat.newChat()
        session.chat.openChat(id)
        runCurrent()
        assertEquals("coming back and opening a chat", 0, app.engine.readAheads.size)
        session.chat.setDraft(id, "Next")
        runCurrent()
        assertEquals("typing", 1, app.engine.readAheads.size)

        // Behind the lock: an answer that finishes there is not read ahead.
        session.chat.setDraft(id, "")
        app.engine.enqueueReply(listOf("Finished", " behind the lock"), millisBetween = 1_000)
        assertTrue(session.chat.send(id, "Another question"))
        runCurrent()
        session.finishOnly()
        advanceTimeBy(3_000)
        runCurrent()
        assertFalse(session.openSession.isGenerating)
        session.chat.setDraft(id, "Typed behind the lock")
        runCurrent()
        assertEquals(1, app.engine.readAheads.size)
        session.close()
    }

    /** Catches a read-ahead of an answer that failed, or of a chat whose last message is not an answer. */
    @Test
    fun `nothing is read ahead after a failure`() = runTest {
        val session = unlocked()
        app.engine.enqueueFailure(EngineError.Unexpected("boom"))
        assertTrue(session.chat.send(session.id, "A question"))
        runCurrent()
        session.chat.setDraft(session.id, "Next")
        runCurrent()
        assertEquals(0, app.engine.readAheads.size)
        session.close()
    }

    // --- What goes before it ---

    /**
     * A session with one answer read ahead by a read-ahead that runs until its caller stops it:
     * the engine here does not stop it for anything, so what stops it is the chats.
     */
    private fun TestScope.readingAhead(): AppSession {
        val session = unlocked()
        app.engine.readAheadGivesWay = false
        app.engine.readAheadMillis = null
        exchange(session, "First question", listOf("First answer."))
        assertEquals(1, app.engine.activeReadAheads)
        return session
    }

    /**
     * Catches a read-ahead that any kind of reply, in this chat or another, has to wait for, and
     * a reply's request that is not made at once.
     */
    @Test
    fun `every kind of reply stops it at once`() = runTest {
        for (kind in listOf("send", "again", "edit", "other chat")) {
            val session = readingAhead()
            val id = session.id
            val before = app.engine.requests.size
            val stoppedBefore = app.engine.stoppedReadAheads
            app.engine.enqueueReply(listOf("Another answer."))
            val messages = session.openSession.messages
            val started = when (kind) {
                "send" -> session.chat.send(id, "Second question")
                "again" -> session.chat.regenerate(id, messages.last().id)
                "edit" -> session.chat.edit(id, messages.first { it.role == Role.USER }.id, "Edited question")
                else -> {
                    session.chat.newChat()
                    session.chat.send(session.id, "A question elsewhere")
                }
            }
            assertTrue(kind, started)
            runCurrent()
            assertEquals("$kind: stopped", stoppedBefore + 1, app.engine.stoppedReadAheads)
            assertEquals("$kind: the reply was asked for", before + 1, app.engine.requests.size)
            app.engine.readAheadMillis = 0
            session.close()
            app.engine.readAheadMillis = null
        }
    }

    /** Catches "Continue" waiting for a read-ahead that typing started. */
    @Test
    fun `continue stops a read-ahead that typing started`() = runTest {
        val session = unlocked()
        val id = session.id
        app.engine.readAheadGivesWay = false
        app.engine.readAheadMillis = null
        exchange(session, "A question", listOf("Cut off at the"), FinishReason.LENGTH)
        session.chat.setDraft(id, "Something new")
        runCurrent()
        assertEquals(1, app.engine.activeReadAheads)
        app.engine.enqueueReply(listOf(" limit."))
        assertTrue(session.chat.continueReply(id, session.openSession.messages.last().id))
        runCurrent()
        assertEquals(1, app.engine.stoppedReadAheads)
        assertTrue(app.engine.requests.last().continueFinalMessage)
        app.engine.readAheadMillis = 0
        session.close()
    }

    /**
     * Catches a read-ahead left running by a prune of its chat, a deletion, another model, a
     * changed system prompt, leaving the screen and the lock.
     */
    @Test
    fun `a prune, a deletion, a load, a new system prompt, leaving the screen and the lock stop it`() = runTest {
        for (what in listOf("prune", "delete", "model", "system prompt", "leave", "lock behind", "lock")) {
            if (what == "prune") {
                val session = unlocked()
                exchange(session, "An old question", listOf("An old answer."))
                app.clock.advance(2L * 24 * 60 * 60 * 1000)
                app.engine.readAheadGivesWay = false
                app.engine.readAheadMillis = null
                exchange(session, "A new question", listOf("A new answer."))
                assertEquals(1, app.engine.activeReadAheads)
                val forgets = app.engine.forgetCount
                session.changeMessageLifetime(MessageLifetime.ONE_DAY)
                runCurrent()
                assertEquals(what, 0, app.engine.activeReadAheads)
                assertEquals(what, forgets + 1, app.engine.forgetCount)
                app.engine.readAheadMillis = 0
                session.changeMessageLifetime(MessageLifetime.NEVER)
                session.close()
                continue
            }
            val session = readingAhead()
            when (what) {
                "delete" -> session.chat.deleteChat(session.id)
                "model" -> session.chat.selectModel(session.id, ModelCatalog.BORIS_2_INSTRUCT.id)
                "system prompt" -> session.settings.update(urgent = true) { it.withSystemPrompt(ModelCatalog.QWEN35_0_8B.id, "Be brief.") }
                "leave" -> session.leftScreen()
                "lock behind" -> session.finishOnly()
                else -> Unit
            }
            if (what == "lock") {
                session.close()
                runCurrent()
                assertEquals(what, 0, app.engine.activeReadAheads)
                continue
            }
            runCurrent()
            assertEquals(what, 0, app.engine.activeReadAheads)
            app.engine.readAheadMillis = 0
            session.close()
        }
    }

    /**
     * Catches a read-ahead that shows on screen or takes the input away: while it runs nothing
     * is being written, the chat can be typed in and sent from, and the state is the one it
     * would be without it.
     */
    @Test
    fun `nothing on screen changes for it and the input stays usable`() = runTest {
        val session = unlocked()
        app.engine.readAheadResult = ReadAheadResult.Read(keptTokens = 10, readTokens = 20)
        exchange(session, "A question", listOf("An answer."))
        val without = session.chat.state.value

        val other = SessionFixture(temp.newFolder(), testScheduler)
        val reading = other.open().also { runCurrent() }
        other.engine.readAheadMillis = null
        other.engine.enqueueReply(listOf("An answer."))
        assertTrue(reading.chat.send(reading.id, "A question"))
        runCurrent()
        assertEquals(1, other.engine.activeReadAheads)
        val state = reading.chat.state.value
        assertNull(state.turn)
        assertEquals(without.turn, state.turn)
        assertEquals(without.engine, state.engine)
        assertEquals(without.contextNotes, state.contextNotes)
        assertEquals(without.trimmedMessages, state.trimmedMessages)
        assertEquals(without.restoringMemory, state.restoringMemory)
        assertFalse(reading.openSession.isGenerating)
        reading.chat.setDraft(reading.id, "Typed while it reads")
        runCurrent()
        assertEquals("Typed while it reads", reading.openSession.draft)
        other.engine.enqueueReply(listOf("Sent while it read."))
        assertTrue(reading.chat.send(reading.id, "Typed while it reads"))
        runCurrent()
        assertEquals("Sent while it read.", reading.openSession.messages.last().text)
        other.engine.readAheadMillis = 0
        reading.close()
        session.close()
    }

    /**
     * Catches a memory that a read-ahead changed being taken at the lock for the one already on
     * disk: a read-ahead that changed the memory, or may have (it was stopped, it emptied the
     * memory after a failure, or it gave no answer at all), counts as a request for the saves,
     * and one that did nothing does not.
     */
    @Test
    fun `a read-ahead that changed the memory counts for the saves, and one that did nothing does not`() = runTest {
        val session = unlocked()
        app.engine.readAheadResult = ReadAheadResult.Skipped(ReadAheadResult.Skipped.Reason.NOTHING_TO_READ)
        exchange(session, "A question", listOf("An answer."))
        val afterNothing = session.chat.requestsBuilt
        assertEquals(1L, afterNothing)
        app.engine.readAheadResult = ReadAheadResult.Read(keptTokens = 10, readTokens = 20)
        exchange(session, "Another question", listOf("Another answer."))
        assertEquals(3L, session.chat.requestsBuilt)
        app.engine.readAheadResult = ReadAheadResult.Stopped(keptTokens = 10, readTokens = 0)
        exchange(session, "A third question", listOf("A third answer."))
        assertEquals(5L, session.chat.requestsBuilt)
        app.engine.readAheadResult = ReadAheadResult.Failed
        exchange(session, "A fourth question", listOf("A fourth answer."))
        assertEquals("a failure that emptied the memory", 7L, session.chat.requestsBuilt)
        app.engine.readAheadResult = ReadAheadResult.Skipped(ReadAheadResult.Skipped.Reason.FAILED)
        exchange(session, "A fifth question", listOf("A fifth answer."))
        assertEquals("a failure that changed nothing", 8L, session.chat.requestsBuilt)
        app.engine.readAheadThrows = IllegalStateException("broken")
        exchange(session, "A sixth question", listOf("A sixth answer."))
        assertEquals("a read-ahead that gave no answer at all", 10L, session.chat.requestsBuilt)
        app.engine.readAheadThrows = null
        session.close()
    }

    // --- Taken up again ---

    /**
     * Catches an answer that ended out of sight with a message already being typed, whose
     * read-ahead nothing starts once the app is back (typing went on, but the draft did not go
     * from blank to not blank); for an answer that ended by itself and for one that can be
     * continued. Coming back alone starts nothing.
     */
    @Test
    fun `an answer that ended out of sight while a message was being typed is read ahead when typing goes on`() = runTest {
        for (finishReason in listOf(FinishReason.END_OF_TURN, FinishReason.LENGTH)) {
            val session = unlocked()
            val id = session.id
            app.engine.readAheads.clear()
            app.engine.enqueueReply(listOf("Written", " out of sight"), millisBetween = 1_000, finishReason = finishReason)
            assertTrue(session.chat.send(id, "A question"))
            runCurrent()
            session.chat.setDraft(id, "The next")
            session.leftScreen()
            advanceTimeBy(3_000)
            runCurrent()
            assertFalse(session.openSession.isGenerating)
            session.cameOnScreen()
            runCurrent()
            assertEquals("$finishReason: coming back", 0, app.engine.readAheads.size)
            session.chat.setDraft(id, "The next question")
            runCurrent()
            assertEquals("$finishReason: typing on", 1, app.engine.readAheads.size)
            session.chat.setDraft(id, "The next question, longer")
            runCurrent()
            assertEquals("$finishReason: once done, not again", 1, app.engine.readAheads.size)
            session.close()
        }
    }

    /**
     * Catches a read-ahead stopped by leaving the screen that is never asked for again: the
     * next change of the draft back in sight asks again (the engine then reads on from where it
     * stopped), and coming back alone does not.
     */
    @Test
    fun `a read-ahead stopped by leaving the screen is asked for again when the user types in that chat`() = runTest {
        val session = unlocked()
        val id = session.id
        app.engine.readAheadMillis = null
        app.engine.enqueueReply(listOf("An answer", " in sight."), millisBetween = 1_000)
        assertTrue(session.chat.send(id, "A question"))
        runCurrent()
        session.chat.setDraft(id, "The next")
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(1, app.engine.activeReadAheads)
        session.leftScreen()
        runCurrent()
        assertEquals(0, app.engine.activeReadAheads)
        session.cameOnScreen()
        runCurrent()
        assertEquals("coming back", 1, app.engine.readAheads.size)
        app.engine.readAheadMillis = 0
        app.engine.readAheadResult = ReadAheadResult.Read(keptTokens = 10, readTokens = 20)
        session.chat.setDraft(id, "The next question")
        runCurrent()
        assertEquals("typing on", 2, app.engine.readAheads.size)
        session.chat.setDraft(id, "The next question, longer")
        runCurrent()
        assertEquals("once it has read, not again", 2, app.engine.readAheads.size)
        session.close()
    }

    /**
     * Catches a read-ahead that the engine stopped itself (the gate closed for a passphrase,
     * say) taken as done: the next change of the draft must ask again, and one that found the
     * engine busy likewise; once it has read, no key asks again.
     */
    @Test
    fun `a read-ahead the engine stopped or found busy is asked for again at the next change of the draft`() = runTest {
        val session = unlocked()
        val id = session.id
        app.engine.readAheadResult = ReadAheadResult.Stopped(keptTokens = 10, readTokens = 5)
        exchange(session, "A question", listOf("An answer."))
        assertEquals(1, app.engine.readAheads.size)
        app.engine.readAheadResult = ReadAheadResult.Skipped(ReadAheadResult.Skipped.Reason.BUSY)
        session.chat.setDraft(id, "N")
        runCurrent()
        assertEquals("after a stop", 2, app.engine.readAheads.size)
        app.engine.readAheadResult = ReadAheadResult.Read(keptTokens = 15, readTokens = 5)
        session.chat.setDraft(id, "Ne")
        runCurrent()
        assertEquals("after busy", 3, app.engine.readAheads.size)
        session.chat.setDraft(id, "New")
        runCurrent()
        assertEquals("not after it has read", 3, app.engine.readAheads.size)
        session.close()
    }

    /**
     * Catches a read-ahead started for a chat whose stored text the engine no longer holds as it
     * is (a system prompt changed since), and for a chat whose conversation the engine is not
     * known to hold (another chat's reply came after it).
     */
    @Test
    fun `typing starts nothing for a chat that changed or that the engine does not hold`() = runTest {
        val session = unlocked()
        val id = session.id
        exchange(session, "A question", listOf("Cut off at the"), FinishReason.LENGTH)
        session.settings.update(urgent = true) { it.withSystemPrompt(ModelCatalog.QWEN35_0_8B.id, "Be brief.") }
        runCurrent()
        session.chat.setDraft(id, "Next")
        runCurrent()
        assertEquals("changed", 0, app.engine.readAheads.size)
        session.close()

        val other = unlocked()
        val first = other.id
        app.engine.readAheads.clear()
        other.chat.newChat()
        runCurrent()
        exchange(other, "A question", listOf("Cut off at the"), FinishReason.LENGTH)
        val cut = other.id
        other.chat.openChat(first)
        runCurrent()
        exchange(other, "Elsewhere", listOf("Cut off too"), FinishReason.LENGTH)
        other.chat.openChat(cut)
        runCurrent()
        other.chat.setDraft(cut, "Next")
        runCurrent()
        assertEquals("not held", 0, app.engine.readAheads.size)
        other.close()
    }

    // --- The saves and the lock ---

    /**
     * Catches a read-ahead stopped by leaving the screen that is counted for the saves only once
     * it has ended, after the save on leaving read the count: the lock by itself then writes the
     * same memory a second time, and the session asks for a keep-alive with nothing new to save.
     * It is counted once.
     */
    @Test
    fun `a read-ahead stopped by leaving the screen is counted before the save on leaving, once`() = runTest {
        val session = unlocked()
        app.engine.saveResult = MemorySaveResult.Saved(500, 1_000)
        app.engine.readAheadMillis = null
        app.engine.readAheadLetGoMillis = 500
        exchange(session, "A question", listOf("An answer."))
        assertEquals(1, app.engine.activeReadAheads)
        val before = session.chat.requestsBuilt
        session.leftScreen()
        assertEquals("counted as it is stopped", before + 1, session.chat.requestsBuilt)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(0, app.engine.activeReadAheads)
        assertEquals("and only once", before + 1, session.chat.requestsBuilt)
        assertEquals(listOf(SaveTrigger.LEFT_SCREEN), app.engine.saves.map { it.second })
        assertFalse("nothing new to save at a lock", session.unattendedCloseKeeps())
        session.close(SessionClose.UNATTENDED)
        assertEquals(listOf(SaveTrigger.LEFT_SCREEN), app.engine.saves.map { it.second })
    }

    /**
     * Catches a close that does not stop the read-ahead itself (it would wait for the bound of
     * the chats' shutdown) or does not wait for it to let go before the lock's save: the
     * keeper's decision at the lock assumes that nothing changes the memory any more.
     */
    @Test
    fun `the close stops the read-ahead and waits for it to let go before the lock's save`() = runTest {
        val session = unlocked()
        app.engine.readAheadGivesWay = false
        app.engine.readAheadMillis = null
        app.engine.readAheadLetGoMillis = 500
        var activeAtSave = -1
        app.engine.onSave = { _, _, _ ->
            activeAtSave = app.engine.activeReadAheads
            MemorySaveResult.Saved(500, 1_000)
        }
        exchange(session, "A question", listOf("An answer."))
        assertEquals(1, app.engine.activeReadAheads)
        val started = testScheduler.currentTime
        session.close(SessionClose.UNATTENDED)
        assertEquals(listOf(SaveTrigger.LOCK), app.engine.saves.map { it.second })
        assertEquals("the read-ahead had let go before the save", 0, activeAtSave)
        assertTrue("without waiting for the bound", testScheduler.currentTime - started < ChatController.ENGINE_TIMEOUT_MILLIS)
    }
}
