package us.theworkmans.proseplow

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import us.theworkmans.proseplow.background.AnswerEvents
import us.theworkmans.proseplow.chat.ChatController
import us.theworkmans.proseplow.chat.ChatState
import us.theworkmans.proseplow.chat.ChatUiState
import us.theworkmans.proseplow.chat.ReplyStatus
import us.theworkmans.proseplow.core.chat.MessageLifetime.Companion.DAY_MILLIS
import us.theworkmans.proseplow.core.engine.EngineState
import us.theworkmans.proseplow.core.engine.FakeInferenceEngine.Step
import us.theworkmans.proseplow.core.engine.MemoryRestoreResult
import us.theworkmans.proseplow.core.engine.MemoryRestoreState
import us.theworkmans.proseplow.core.engine.MemorySaveResult
import us.theworkmans.proseplow.core.engine.MemoryVault
import us.theworkmans.proseplow.core.engine.SaveTrigger
import us.theworkmans.proseplow.core.engine.SavedMemory
import us.theworkmans.proseplow.core.model.ModelCatalog
import us.theworkmans.proseplow.data.ModelMemoryPlatform
import us.theworkmans.proseplow.data.ModelMemorySupport
import us.theworkmans.proseplow.security.SessionClose
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The model's saved memory as the session drives it, through the real session, chats, stores
 * and writer on virtual time, with the engine scripted: when a save is asked for and when not,
 * the switch, the platforms on which nothing is kept, that a restore comes before the first
 * request and a request is never built before it returned, that a prune marks the engine before
 * the next request, and which conversation key a request carries. What the lock adds (the
 * order at each kind of lock, the bound) is in [AppSessionTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelMemorySessionTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var app: SessionFixture

    private val TestScope.fixture: SessionFixture
        get() {
            if (!::app.isInitialized) app = SessionFixture(temp.root, testScheduler)
            return app
        }

    private fun TestScope.unlocked(events: AnswerEvents = AnswerEvents.None): AppSession = fixture.open(answerEvents = events).also { runCurrent() }

    private val AppSession.id: String get() = chat.state.value.open.id

    /** What the session told about an answer being written, in order; see the test of T2. */
    private val told = ArrayList<Boolean>()

    private fun record(writing: Boolean) {
        told += writing
    }

    private val AppSession.state: ChatState get() = chat.state.value

    private fun TestScope.exchange(session: AppSession, text: String, reply: String = "An answer to $text") {
        app.engine.enqueueReply(listOf(reply))
        assertTrue(session.chat.send(session.id, text))
        runCurrent()
    }

    /** A memory file for the chat [id] as an earlier save would have left it; its content is not read here. */
    private fun plantMemory(id: String): File = File(app.layout.modelMemory, "$id.enc").apply {
        parentFile!!.mkdirs()
        writeText("a memory")
        setLastModified(app.clock.nowMillis())
    }

    private fun memoryFiles(): List<String> = app.layout.modelMemory.list().orEmpty().sorted()

    // --- The version check ---

    /** Catches the line drawn anywhere but between Android 11 and Android 12. */
    @Test
    fun theModelsMemoryIsKeptFromAndroid12On() {
        assertFalse("Android 9", ModelMemorySupport.supported(28))
        assertFalse("Android 10", ModelMemorySupport.supported(29))
        assertFalse("Android 11", ModelMemorySupport.supported(30))
        assertTrue("Android 12", ModelMemorySupport.supported(31))
        assertTrue("Android 17", ModelMemorySupport.supported(37))
    }

    /**
     * Below Android 12 the session is given no platform. Catches a session that keeps a memory
     * file found there, restores, saves on leaving the screen or at a lock by itself, or offers
     * the switch.
     */
    @Test
    fun belowAndroid12NothingIsSavedNothingRestoredAndAFileFoundIsDeleted() = runTest {
        fixture.memoryPlatform = null
        val first = unlocked()
        exchange(first, "Hello")
        val id = first.id
        first.close()
        plantMemory(id)
        app.engine.saveResult = MemorySaveResult.Saved(500, 1_000)

        val session = unlocked()
        assertEquals("the file found is deleted at the opening", emptyList<String>(), memoryFiles())
        assertFalse(session.modelMemorySupported)
        assertFalse(session.keepModelMemory)
        exchange(session, "Again")
        session.leftScreen()
        runCurrent()
        session.changeKeepModelMemory(true)
        session.close(SessionClose.UNATTENDED)

        assertEquals("no restore", emptyList<String>(), app.engine.restores)
        assertEquals("no save", emptyList<Pair<String, SaveTrigger>>(), app.engine.saves)
        assertFalse("the switch cannot be turned on", session.settings.settings.value.keepModelMemory && session.keepModelMemory)
    }

    // --- Leaving the screen (T1) and an answer ending out of sight (T2) ---

    /** Catches a save on leaving the screen that is not asked for, or asked for the wrong conversation or with the wrong trigger. */
    @Test
    fun leavingTheScreenUnlockedAndIdleAsksForASaveOfTheConversationTheEngineHolds() = runTest {
        val session = unlocked()
        exchange(session, "Hello")

        session.leftScreen()
        runCurrent()

        assertEquals(listOf(ChatController.conversationKeyOf(session.id) to SaveTrigger.LEFT_SCREEN), app.engine.saves)
        session.close()
    }

    /** Catches a save asked for while a reply is being written, and one asked for with the switch off. */
    @Test
    fun leavingTheScreenAsksForNothingWhileAnAnswerIsWrittenOrWithTheSwitchOff() = runTest {
        val session = unlocked()
        exchange(session, "Hello")
        app.engine.enqueueReply(listOf("Slow ", "answer"), millisBetween = 1_000)
        assertTrue(session.chat.send(session.id, "More"))
        runCurrent()

        session.leftScreen()
        runCurrent()
        assertEquals("an answer is being written", emptyList<Pair<String, SaveTrigger>>(), app.engine.saves.filter { it.second == SaveTrigger.LEFT_SCREEN })
        session.cameOnScreen()
        advanceTimeBy(2_500)
        runCurrent()
        assertNull(session.state.turn)

        session.changeKeepModelMemory(false)
        runCurrent()
        session.leftScreen()
        runCurrent()
        assertEquals("the switch is off", emptyList<Pair<String, SaveTrigger>>(), app.engine.saves)
        session.close()
    }

    /**
     * T2. Catches an answer that ends out of sight without a save, one that ends in sight with
     * one, and a keep-alive let go before the save has ended: the answer is reported as still
     * being written until then.
     */
    @Test
    fun anAnswerEndingOutOfSightAsksForASaveAndCountsAsWritingUntilItHasEnded() = runTest {
        val writing = told
        val events = object : AnswerEvents {
            override fun onWriting(writing: Boolean) = record(writing)

            override fun onReplyFinished() = Unit
        }
        val session = unlocked(events)
        exchange(session, "Hello")
        app.engine.onSave = { _, _, _ ->
            delay(2_000)
            MemorySaveResult.Saved(500, 1_000)
        }

        // In sight: the end of an answer asks for nothing.
        exchange(session, "In sight")
        assertEquals(emptyList<Pair<String, SaveTrigger>>(), app.engine.saves)

        app.engine.enqueueReply(listOf("Out ", "of sight"), millisBetween = 1_000)
        assertTrue(session.chat.send(session.id, "Then leave"))
        runCurrent()
        session.leftScreen()
        writing.clear()
        advanceTimeBy(2_500)
        runCurrent()
        assertNull(session.state.turn)
        assertEquals(listOf(ChatController.conversationKeyOf(session.id) to SaveTrigger.LEFT_SCREEN), app.engine.saves)
        assertEquals("still writing while it saves", emptyList<Boolean>(), writing)

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals("and no longer once the save has ended", listOf(false), writing)
        session.close()
    }

    /** Catches a save asked for when an answer finished behind the lock ends: that is the lock's save, not this one. */
    @Test
    fun anAnswerEndingBehindTheLockAsksForNoSaveOnLeaving() = runTest {
        val session = unlocked()
        exchange(session, "Hello")
        app.engine.enqueueReply(listOf("Behind ", "the lock"), millisBetween = 1_000)
        assertTrue(session.chat.send(session.id, "Long"))
        runCurrent()
        session.leftScreen()
        session.clearScreens()
        session.finishOnly()
        advanceTimeBy(2_500)
        runCurrent()

        assertNull(session.state.turn)
        assertEquals(emptyList<Pair<String, SaveTrigger>>(), app.engine.saves)
        session.close()
    }

    // --- The switch ---

    /**
     * Catches a switch that leaves the memory on disk, lets a running save finish, keeps saving
     * or restoring while off, or is not stored.
     */
    @Test
    fun theSwitchOffDeletesTheMemoryAtOnceStopsASaveAndNothingIsSavedOrRestoredWhileOff() = runTest {
        val session = unlocked()
        exchange(session, "Hello")
        val id = session.id
        plantMemory(id)
        var saveCancelled = false
        app.engine.onSave = { _, _, _ ->
            try {
                awaitCancellation()
            } finally {
                saveCancelled = true
            }
        }
        session.leftScreen()
        runCurrent()
        session.cameOnScreen()

        session.changeKeepModelMemory(false)
        runCurrent()

        assertTrue("the save in progress was stopped", saveCancelled)
        assertEquals("the saved memory is deleted at once", emptyList<String>(), memoryFiles())
        assertFalse(session.keepModelMemory)
        session.close()
        assertFalse("and the choice is stored", app.open().also { runCurrent() }.let { reopened -> reopened.keepModelMemory.also { reopened.close() } })

        app.engine.restores.clear()
        app.engine.saves.clear()
        plantMemory(id)
        val off = unlocked()
        assertEquals("a file left behind is deleted at the opening", emptyList<String>(), memoryFiles())
        exchange(off, "Off")
        off.leftScreen()
        runCurrent()
        off.close(SessionClose.UNATTENDED)
        assertEquals(emptyList<String>(), app.engine.restores)
        assertEquals(emptyList<Pair<String, SaveTrigger>>(), app.engine.saves)
    }

    /** Catches a switch turned back on that the engine is not told of, or that does not save again. */
    @Test
    fun theSwitchOnAgainSavesAgainThroughAVaultTheEngineHasNotSeen() = runTest {
        val session = unlocked()
        exchange(session, "Hello")
        val vaults = ArrayList<MemoryVault>()
        app.engine.onSave = { vault, _, _ ->
            vaults += vault
            MemorySaveResult.NotSaved(MemorySaveResult.NotSaved.Reason.NOT_WORTH_IT)
        }
        session.leftScreen()
        runCurrent()
        session.changeKeepModelMemory(false)
        session.changeKeepModelMemory(true)
        session.leftScreen()
        runCurrent()

        assertEquals(2, vaults.size)
        assertTrue("a new vault, so that nothing known of the deleted memory counts", vaults[0] !== vaults[1])
        session.close()
    }

    // --- Restoring ---

    /**
     * Catches a restore asked for before the model is loaded, without the user's context size,
     * for another chat than the one on screen, or after the first request: and a request built
     * while the restore is still running.
     */
    @Test
    fun theChatOnScreenIsRestoredAfterItsModelLoadsAndBeforeAnyRequestIsBuilt() = runTest {
        val first = unlocked()
        exchange(first, "Hello")
        val id = first.id
        first.settings.update { it.withContextTokens(ModelCatalog.default.id, 8_192) }
        first.close()

        app.engine.loadMillis = 1_000
        app.engine.unload()
        app.engine.restores.clear()
        app.engine.restoreContexts.clear()
        val released = CompletableDeferred<Unit>()
        var stateAtRestore: EngineState? = null
        var requestsAtRestore = -1
        app.engine.onRestore = { _, _, _ ->
            stateAtRestore = app.engine.state.value
            requestsAtRestore = app.engine.requests.size
            app.engine.reportRestore(MemoryRestoreState.Restoring(ModelCatalog.default.id))
            released.await()
            app.engine.reportRestore(MemoryRestoreState.Restored(ModelCatalog.default.id, 300))
            MemoryRestoreResult.Restored(300)
        }
        val requestsBefore = app.engine.requests.size
        val session = app.open()
        runCurrent()
        assertTrue(session.chat.send(id, "A second question"))
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(listOf(ChatController.conversationKeyOf(id)), app.engine.restores)
        assertEquals(EngineState.Ready(ModelCatalog.default.id), stateAtRestore)
        assertEquals(requestsBefore, requestsAtRestore)
        assertEquals("the context the user chose", listOf<Int?>(session.contextLimits.chosen(ModelCatalog.default, 8_192)), app.engine.restoreContexts)
        assertEquals("no request while the restore runs", requestsBefore, app.engine.requests.size)
        val ui = ChatUiState(session.state)
        assertEquals("the reply says what it waits for", ReplyStatus.Restoring, ui.replyStatus)

        released.complete(Unit)
        runCurrent()
        assertEquals(requestsBefore + 1, app.engine.requests.size)
        assertEquals("one restore, not a second before the request", 1, app.engine.restores.size)
        session.close()
    }

    /** Catches the first request in another chat built without asking for that chat's memory first. */
    @Test
    fun theFirstRequestOfAnotherChatAsksForItsMemoryFirst() = runTest {
        val session = unlocked()
        exchange(session, "In the first chat")
        val first = session.id
        session.chat.newChat()
        exchange(session, "In the second chat")
        val second = session.id
        assertNotEquals(first, second)

        assertEquals(
            listOf(ChatController.conversationKeyOf(first), ChatController.conversationKeyOf(first), ChatController.conversationKeyOf(second)),
            app.engine.restores,
        )
        session.close()
    }

    // --- Requests, prunes, keys ---

    /** Catches a request without its chat's key, a key two chats share, and a key that changes for the same chat across an unlock. */
    @Test
    fun everyRequestCarriesItsChatsOwnKeyAndAChatKeepsItAcrossAnUnlock() = runTest {
        val session = unlocked()
        exchange(session, "One")
        val first = session.id
        session.chat.newChat()
        exchange(session, "Two")
        val second = session.id
        session.close()
        val again = unlocked()
        exchange(again, "Three")

        val keys = app.engine.requests.map { it.conversationKey }
        assertEquals(listOf(ChatController.conversationKeyOf(first), ChatController.conversationKeyOf(second), ChatController.conversationKeyOf(second)), keys)
        assertNotEquals(keys[0], keys[1])
        assertEquals(second, ChatController.chatIdOf(keys[2]!!))
        assertNull("a key that is not a chat's", ChatController.chatIdOf("another:$first"))
        again.close()
    }

    /**
     * Catches a prune of the conversation the engine holds that does not mark it before the
     * next request is built (a save could then keep a message that expired), and a mark made
     * for a chat the engine does not hold (which would cost the held conversation its save).
     */
    @Test
    fun aPruneOfTheHeldConversationMarksTheEngineBeforeTheNextRequest() = runTest {
        val session = unlocked()
        exchange(session, "Old question")
        val held = session.id
        session.chat.newChat()
        exchange(session, "Another chat")
        app.clock.advance(7 * DAY_MILLIS + 1)
        // The engine holds the second chat: pruning the first marks nothing.
        session.chat.openChat(held)
        runCurrent()
        assertEquals(0, app.engine.notToBeSavedMarks)

        exchange(session, "A newer question in the old chat")
        val requests = app.engine.requests.size
        app.clock.advance(1)
        session.chat.expireMessages()

        assertEquals("marked at once, inside the call that pruned", 1, app.engine.notToBeSavedMarks)
        assertEquals("before any request", requests, app.engine.requests.size)
        runCurrent()
        session.close()
    }

    /**
     * Catches a save at a lock that comes after an edit and before the engine has read the
     * edited conversation: the store would keep text the user removed. The conversation is
     * "not known" for that time, and known again once the engine has read a request after it.
     */
    @Test
    fun aChatThatWasEditedHasNoConversationToSaveUntilTheEngineHasReadIt() = runTest {
        val session = unlocked()
        exchange(session, "First question")
        val id = session.id
        val key = ChatController.conversationKeyOf(id)
        assertNotNull(session.chat.conversation(key))

        app.engine.enqueue(listOf(Step.Hang))
        val question = session.openSession.messages.first()
        assertTrue(session.chat.edit(id, question.id, "First question, edited"))
        runCurrent()
        assertNull("the engine has not read the edited conversation", session.chat.conversation(key))
        session.chat.stop(id)
        runCurrent()
        assertNull("nor after the reply was stopped before it read it", session.chat.conversation(key))

        exchange(session, "And another")
        assertEquals("known again once a later request was read", "And another", session.chat.conversation(key)?.messages?.dropLast(1)?.last()?.text)
        session.close()
    }

    /** Catches a deleted chat whose conversation the store would still save. */
    @Test
    fun aDeletedChatHasNoConversation() = runTest {
        val session = unlocked()
        exchange(session, "Soon deleted")
        val id = session.id
        session.chat.deleteChat(id)
        assertNull(session.chat.conversation(ChatController.conversationKeyOf(id)))
        assertNull(session.chat.conversation("not a key"))
        session.close()
    }

    /** Catches a conversation of a chat not opened since the unlock that the store cannot find, or finds without its date. */
    @Test
    fun aChatNotOpenedSinceTheUnlockIsReadFromTheDisk() = runTest {
        val first = unlocked()
        exchange(first, "In a chat that is not on screen later")
        val id = first.id
        first.chat.newChat()
        exchange(first, "On screen")
        first.close()

        val session = unlocked()
        val conversation = session.chat.conversation(ChatController.conversationKeyOf(id))
        assertEquals(id, conversation?.chatId)
        assertEquals(2, conversation?.messages?.size)
        assertEquals(conversation?.messages?.minOf { it.createdAtMillis }, conversation?.oldestMessageMillis)
        session.close()
    }

    /** A chat that cannot be read says "cannot tell now", which the store does not answer with a deletion. */
    @Test
    fun aChatThatCannotBeReadCannotBeToldNow() = runTest {
        val first = unlocked()
        exchange(first, "Soon unreadable")
        val id = first.id
        first.chat.newChat()
        exchange(first, "On screen")
        first.close()
        File(app.layout.sessions, "$id.enc").writeText("not a sealed file")

        val session = unlocked()
        var thrown: Throwable? = null
        try {
            session.chat.conversation(ChatController.conversationKeyOf(id))
        } catch (e: IOException) {
            thrown = e
        }
        assertNotNull(thrown)
        session.close()
    }

    // --- The switch when the settings cannot say ---

    /**
     * A settings file that is missing or cannot be read, with
     * the lock's copy of the switch saying off, deletes the memory file at the opening, and
     * nothing is saved or restored. Catches a switch that falls back to on.
     */
    @Test
    fun settingsThatCannotBeReadWithACopyThatSaysOffKeepNoMemory() = runTest {
        for (case in listOf("missing", "unreadable")) {
            val first = unlocked()
            exchange(first, "Hello")
            val id = first.id
            first.close()
            when (case) {
                "missing" -> app.layout.settings.delete()
                "unreadable" -> app.layout.settings.writeBytes(byteArrayOf(1, 2, 3, 4))
            }
            plantMemory(id)
            app.engine.saves.clear()
            app.engine.restores.clear()
            app.engine.saveResult = MemorySaveResult.Saved(500, 1_000)
            fixture.lockedKeepModelMemory = false

            val session = unlocked()
            assertEquals("$case: the memory found is deleted at the opening", emptyList<String>(), memoryFiles())
            assertFalse(case, session.keepModelMemory)
            exchange(session, "Again")
            session.leftScreen()
            runCurrent()
            session.close(SessionClose.UNATTENDED)
            assertEquals(case, emptyList<String>(), app.engine.restores)
            assertEquals(case, emptyList<Pair<String, SaveTrigger>>(), app.engine.saves)
            fixture.lockedKeepModelMemory = null
            app.layout.kept.deleteRecursively()
        }
    }

    // --- "Lock now" and the keeper ---

    /**
     * "Lock now" stops a save under way before the chats are
     * shut down, so that their shutdown (which waits for a forgetting queued for the engine,
     * which a save stuck on the disk would hold) never waits behind it. Seen here by the order:
     * the save is let go before the reply being written is. Catches a close that stops the save
     * only after the chats.
     */
    @Test
    fun lockNowStopsASaveBeforeTheChatsAreShutDown() = runTest {
        val session = unlocked()
        exchange(session, "Hello")
        var generationsStoppedWhenTheSaveWas = -1
        app.engine.onSave = { _, _, _ ->
            try {
                awaitCancellation()
            } finally {
                generationsStoppedWhenTheSaveWas = app.engine.cancelledGenerations
            }
        }
        session.leftScreen()
        runCurrent()
        session.cameOnScreen()
        app.engine.enqueue(listOf(Step.Hang))
        assertTrue(session.chat.send(session.id, "Write for ever"))
        runCurrent()
        assertEquals(1, app.engine.activeGenerations)

        session.close(SessionClose.IMMEDIATE)
        assertEquals("the save was let go before the reply was", 0, generationsStoppedWhenTheSaveWas)
        assertEquals(1, app.engine.cancelledGenerations)
    }

    /**
     * Not written twice for nothing: a memory that a save on leaving put on disk, with no request
     * made since, is not saved again when the session closes by itself; after a request it is.
     * Catches a keeper that writes hundreds of megabytes again for nothing, and one that skips
     * the lock's save when something is new.
     */
    @Test
    fun aMemoryOnDiskAsItIsInTheEngineIsNotSavedAgainAtTheLockButOneWithSomethingNewIs() = runTest {
        fixture.engine.saveResult = MemorySaveResult.Saved(500, 1_000)
        val unchanged = unlocked()
        exchange(unchanged, "Hello")
        unchanged.leftScreen()
        runCurrent()
        unchanged.close(SessionClose.UNATTENDED)
        assertEquals("only the save on leaving", listOf(SaveTrigger.LEFT_SCREEN), app.engine.saves.map { it.second })

        app.engine.saves.clear()
        val changed = unlocked()
        exchange(changed, "Hello")
        changed.leftScreen()
        runCurrent()
        changed.cameOnScreen()
        exchange(changed, "Something new")
        changed.close(SessionClose.UNATTENDED)
        assertEquals("the lock saves what is new", listOf(SaveTrigger.LEFT_SCREEN, SaveTrigger.LOCK), app.engine.saves.map { it.second })

        // A save that did not leave the memory on disk does not count either.
        app.engine.saves.clear()
        app.engine.saveResult = MemorySaveResult.NotSaved(MemorySaveResult.NotSaved.Reason.LITTLE_NEW)
        val notSaved = unlocked()
        exchange(notSaved, "Hello")
        notSaved.leftScreen()
        runCurrent()
        notSaved.close(SessionClose.UNATTENDED)
        assertEquals(listOf(SaveTrigger.LEFT_SCREEN, SaveTrigger.LOCK), app.engine.saves.map { it.second })
    }

    /**
     * The vault the engine was given refuses a save begun after the session closed, or after the
     * switch was turned off, before the store starts anything for it. Catches a late save (an
     * engine thread that got to it only now) that would write after the close.
     */
    @Test
    fun theVaultRefusesASaveBegunAfterTheCloseOrTheSwitchOff() = runTest {
        for (case in listOf("closed", "switched off")) {
            val session = unlocked()
            exchange(session, "Hello")
            var given: MemoryVault? = null
            app.engine.onSave = { vault, _, _ ->
                given = vault
                MemorySaveResult.NotSaved(MemorySaveResult.NotSaved.Reason.NOT_WORTH_IT)
            }
            session.leftScreen()
            runCurrent()
            val vault = checkNotNull(given)
            when (case) {
                "closed" -> session.close(SessionClose.IMMEDIATE)
                "switched off" -> session.changeKeepModelMemory(false)
            }
            runCurrent()
            val memory = SavedMemory(ChatController.conversationKeyOf(session.id), ModelCatalog.default.id, 4_096, 500, 60.0, 1_000)
            assertThrows(case, IOException::class.java) { vault.beginSave(memory) }
            assertEquals(case, emptyList<String>(), memoryFiles())
            if (case != "closed") session.close()
            app.engine.onSave = null
        }
    }

    /**
     * A save that is being begun on the engine's thread when the session closes is waited for by
     * [AppSession.awaitStopped] until it has ended, so that a reset never deletes the files
     * beside it. Catches a keeper that counts only the writer threads already started.
     */
    @Test(timeout = 60_000)
    fun awaitStoppedWaitsForASaveThatIsBeingBegun() = runTest {
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val armed = AtomicBoolean(false)
        val engineThread = Executors.newSingleThreadExecutor()
        try {
            val standard = testMemoryPlatform()
            fixture.memoryPlatform = ModelMemoryPlatform(
                build = standard.build,
                modelFileLength = { id ->
                    if (armed.compareAndSet(true, false)) {
                        inside.countDown()
                        release.await(30, TimeUnit.SECONDS)
                    }
                    standard.modelFileLength(id)
                },
                freeSpace = standard.freeSpace,
            )
            val session = unlocked()
            exchange(session, "Hello")
            app.engine.onSave = { vault, key, _ ->
                // On a thread of its own, as the engine's native thread is.
                withContext(engineThread.asCoroutineDispatcher()) {
                    try {
                        vault.beginSave(SavedMemory(key, ModelCatalog.default.id, 4_096, 500, 60.0, 1_000)).close()
                    } catch (_: IOException) {
                        // Refused: the session closed while it was being begun.
                    }
                }
                MemorySaveResult.NotSaved(MemorySaveResult.NotSaved.Reason.FAILED)
            }
            armed.set(true)
            session.leftScreen()
            runCurrent()
            assertTrue("the save is being begun", inside.await(20, TimeUnit.SECONDS))

            session.close(SessionClose.IMMEDIATE)
            val stopped = launch { session.awaitStopped() }
            repeat(20) {
                advanceTimeBy(20)
                runCurrent()
            }
            assertFalse("not stopped while the save is still being begun", stopped.isCompleted)

            release.countDown()
            repeat(1_000) {
                if (!stopped.isCompleted) {
                    Thread.sleep(5)
                    advanceTimeBy(20)
                    runCurrent()
                }
            }
            assertTrue("stopped once it has ended", stopped.isCompleted)
            assertEquals("and it left nothing", emptyList<String>(), memoryFiles())
        } finally {
            release.countDown()
            engineThread.shutdownNow()
        }
    }

    /**
     * A prune of the chat whose saved memory is being read back marks the engine at once and has
     * it forget, as for the chat it holds. Catches a prune during a restore that leaves the
     * restored memory, with the expired text, in the engine.
     */
    @Test
    fun aPruneWhileTheChatsMemoryIsReadBackMarksAndForgets() = runTest {
        val first = unlocked()
        exchange(first, "Old question")
        val id = first.id
        first.close()

        val released = CompletableDeferred<Unit>()
        app.engine.onRestore = { _, _, _ ->
            released.await()
            MemoryRestoreResult.Restored(300)
        }
        val session = unlocked()
        assertEquals("the restore is under way", listOf(ChatController.conversationKeyOf(id)), app.engine.restores.takeLast(1))
        val marks = app.engine.notToBeSavedMarks
        val forgets = app.engine.forgetCount

        app.clock.advance(7 * DAY_MILLIS + 1)
        session.chat.expireMessages()
        assertEquals("marked at once", marks + 1, app.engine.notToBeSavedMarks)
        released.complete(Unit)
        runCurrent()
        assertEquals("and made to forget", forgets + 1, app.engine.forgetCount)
        session.close()
    }
}
