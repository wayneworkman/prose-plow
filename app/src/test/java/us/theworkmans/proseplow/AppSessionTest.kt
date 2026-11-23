package us.theworkmans.proseplow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import us.theworkmans.proseplow.chat.ChatController
import us.theworkmans.proseplow.core.engine.MemorySaveResult
import us.theworkmans.proseplow.core.engine.SavedMemory
import us.theworkmans.proseplow.security.SealedStream
import java.nio.ByteBuffer
import us.theworkmans.proseplow.background.AnswerEvents
import us.theworkmans.proseplow.background.AnswerWork
import us.theworkmans.proseplow.background.KeepAliveEntry
import us.theworkmans.proseplow.background.KeepAliveNotice
import us.theworkmans.proseplow.background.KeepAliveProbe
import us.theworkmans.proseplow.background.KeepAliveSource
import us.theworkmans.proseplow.background.ReadyNotice
import us.theworkmans.proseplow.core.chat.FakeClock
import us.theworkmans.proseplow.core.chat.MessageStatus
import us.theworkmans.proseplow.core.chat.SequentialIds
import us.theworkmans.proseplow.core.engine.EngineState
import us.theworkmans.proseplow.core.engine.FakeInferenceEngine
import us.theworkmans.proseplow.core.engine.FakeInferenceEngine.Step
import us.theworkmans.proseplow.core.engine.GenerationEvent
import us.theworkmans.proseplow.core.engine.GenerationRequest
import us.theworkmans.proseplow.core.engine.InferenceEngine
import us.theworkmans.proseplow.core.engine.MemoryRestoreResult
import us.theworkmans.proseplow.core.engine.MemoryRestoreState
import us.theworkmans.proseplow.core.engine.MemoryVault
import us.theworkmans.proseplow.core.engine.SaveTrigger
import us.theworkmans.proseplow.core.model.ModelCatalog
import us.theworkmans.proseplow.core.model.ModelSpec
import us.theworkmans.proseplow.security.AppFiles
import us.theworkmans.proseplow.security.AppLock
import us.theworkmans.proseplow.security.BiometricStatus
import us.theworkmans.proseplow.security.BlobCipher
import us.theworkmans.proseplow.security.DataCipher
import us.theworkmans.proseplow.security.FakeScreenOffWatch
import us.theworkmans.proseplow.security.KeyDestroyedException
import us.theworkmans.proseplow.security.LockConfigStore
import us.theworkmans.proseplow.security.LockManager
import us.theworkmans.proseplow.security.LockState
import us.theworkmans.proseplow.security.ScreenshotAllowance
import us.theworkmans.proseplow.security.SessionClose
import us.theworkmans.proseplow.security.SoftwareKeyVault
import us.theworkmans.proseplow.security.UnlockedSession
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import us.theworkmans.proseplow.security.FakeClock as LockClock

/**
 * The unlocked session: where its files go, and what it promises the lock when it ends. The
 * tests that are about the lock drive the real one (software keys, virtual time), so the order
 * asserted here is the order the app really takes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppSessionTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class ScreenModel : ViewModel() {
        var cleared = false

        override fun onCleared() {
            cleared = true
        }
    }

    @Test
    fun theStoresFilesAreOnesTheLockRecognisesAsEncryptedData() {
        val files = AppFiles(temp.newFolder("files"), temp.newFolder("cache"))
        val layout = DataLayout(files)
        assertEquals(File(files.filesDir, "settings.enc"), layout.settings)
        assertEquals(File(files.filesDir, "sessions"), layout.sessions)
        assertEquals(File(files.filesDir, "session-index.enc"), layout.sessionIndex)
        assertEquals(File(files.filesDir, "kept"), layout.kept)
        assertEquals("settings", DataLayout.SETTINGS_PURPOSE)
        assertEquals("session-index", DataLayout.SESSION_INDEX_PURPOSE)
        assertEquals("session:3f2a", DataLayout.sessionPurpose("3f2a"))

        assertFalse(files.encryptedDataExists())
        for (stored in listOf(layout.settings, layout.sessionIndex, File(layout.sessions, "3f2a.enc"), File(layout.kept, "session-3f2a.0011223344556677.enc"))) {
            stored.parentFile!!.mkdirs()
            stored.writeBytes(byteArrayOf(1))
            assertTrue(stored.name, files.encryptedDataExists())
            assertTrue(stored.delete())
            assertFalse(files.encryptedDataExists())
        }
    }

    // --- The session on its own ----------------------------------------------------------------

    @Test
    fun clearingTheScreensClearsTheirViewModelsAndHurriesTheWriter() = runTest {
        val app = SessionFixture(temp.root, testScheduler)
        val session = app.open()
        runCurrent()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") // The only class this factory is asked for.
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ScreenModel() as T
        }
        val first = ViewModelProvider(session.viewModelStore, factory)[ScreenModel::class.java]
        val id = session.openSession.id
        session.chat.setDraft(id, "Typed a moment ago")
        runCurrent()
        assertNull("a draft is written lazily", app.stored(id))

        session.clearScreens()
        runCurrent()

        assertTrue(first.cleared)
        assertEquals("Typed a moment ago", app.stored(id)!!.draft)

        // The store can be used again: the activity may come back before the lock engages.
        val second = ViewModelProvider(session.viewModelStore, factory)[ScreenModel::class.java]
        assertFalse(second === first)
        assertFalse(second.cleared)
        session.close()
    }

    @Test
    fun leavingTheScreenWritesWhatWasWaiting() = runTest {
        val app = SessionFixture(temp.root, testScheduler)
        val session = app.open()
        runCurrent()
        session.settings.update { it.withSystemPrompt(ModelCatalog.default.id, "Be brief.") }
        runCurrent()
        assertFalse(app.layout.settings.exists())

        session.flushWrites()
        runCurrent()

        assertTrue(app.layout.settings.exists())
        assertFalse(app.diskContains("brief"))
        session.close()
    }

    @Test
    fun aWriteThatFailsIsShownAndTheSessionGoesOn() = runTest {
        val app = SessionFixture(temp.root, testScheduler)
        val session = app.open()
        runCurrent()
        assertNull(session.writeFailure.value)
        // Where the sessions would go there is a file: nothing can be written there.
        app.layout.sessions.writeText("in the way")
        app.engine.enqueueReply(listOf("Hi."))

        assertTrue(session.chat.send(session.openSession.id, "Hello"))
        runCurrent()

        assertEquals("IOException", session.writeFailure.value)
        assertEquals("the chat itself carries on in memory", listOf("Hello", "Hi."), session.openSession.messages.map { it.text })
        session.settings.update(urgent = true) { it.withSystemPrompt(ModelCatalog.default.id, "Still saved") }
        runCurrent()
        assertTrue("and the other files are still written", app.layout.settings.exists())

        session.writeFailureShown()
        assertNull(session.writeFailure.value)
        session.close()
    }

    @Test
    fun aSessionThatCannotLookAtWhatIsStoredDoesNotOpen() = runTest {
        val app = SessionFixture(temp.root, testScheduler)
        app.layout.sessions.writeText("a file where the directory should be")

        assertThrows(IOException::class.java) { app.open() }
    }

    @Test
    fun closingWritesWhatWasWaitingAndThenNothingMoreIsWritten() = runTest {
        val app = SessionFixture(temp.root, testScheduler)
        val session = app.open()
        runCurrent()
        val id = session.openSession.id
        session.chat.setDraft(id, "The last thing typed")
        session.settings.update { it.withSystemPrompt(ModelCatalog.default.id, "The last thing set") }

        session.close()

        assertEquals("The last thing typed", app.stored(id)!!.draft)
        assertEquals(listOf("session-index.enc", "sessions/$id.enc", "settings.enc"), app.fileNames())
        val after = app.files.filesDir.walkTopDown().filter { it.isFile }.associate { it.name to it.readBytes().toList() }

        session.settings.update(urgent = true) { it.withSystemPrompt(ModelCatalog.default.id, "Too late") }
        session.chat.setDraft(id, "Too late")
        session.flushWrites()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(after, app.files.filesDir.walkTopDown().filter { it.isFile }.associate { it.name to it.readBytes().toList() })
    }

    /** An engine that never comes back from being told to forget. */
    private class HangingEngine(private val delegate: InferenceEngine) : InferenceEngine {
        override val state: StateFlow<EngineState> get() = delegate.state

        override suspend fun load(model: ModelSpec) = delegate.load(model)

        override suspend fun unload() = delegate.unload()

        override fun generate(request: GenerationRequest): Flow<GenerationEvent> = delegate.generate(request)

        override suspend fun forget(): Unit = awaitCancellation()

        override suspend fun forgetKeepingSavedMemory(): Unit = awaitCancellation()

        override val memoryRestore: StateFlow<MemoryRestoreState> get() = delegate.memoryRestore

        override suspend fun saveMemory(vault: MemoryVault, conversationKey: String, trigger: SaveTrigger) = delegate.saveMemory(vault, conversationKey, trigger)

        override suspend fun restoreMemory(vault: MemoryVault, conversationKey: String, maxContextTokens: Int?) =
            delegate.restoreMemory(vault, conversationKey, maxContextTokens)

        override fun markNotToBeSaved() = delegate.markNotToBeSaved()
    }

    @Test
    fun anEngineThatHangsDoesNotKeepTheKeyAliveForEver() = runTest {
        val app = SessionFixture(temp.root, testScheduler)
        val session = app.open(engine = HangingEngine(app.engine))
        runCurrent()
        val id = session.openSession.id
        session.chat.setDraft(id, "Still saved")

        val closing = launch { session.close() }
        advanceTimeBy(9_999)
        runCurrent()
        assertFalse("still waiting for the engine", closing.isCompleted)

        advanceTimeBy(1)
        runCurrent()
        assertTrue("after ten seconds the close goes on without it", closing.isCompleted)
        assertEquals("Still saved", app.stored(id)!!.draft)
    }

    // --- With the lock -------------------------------------------------------------------------

    /** The session the lock holds: the real one, with a note of when the lock cleared and closed it. */
    private class WatchedSession(val real: AppSession, private val cipher: BlobCipher, private val log: MutableList<String>) : UnlockedSession {
        override fun clearScreens() {
            log += "screens cleared"
            real.clearScreens()
        }

        override suspend fun close(how: SessionClose) {
            log += "close $how"
            real.close(how)
            val usable = try {
                cipher.seal("probe", byteArrayOf(1))
                true
            } catch (_: KeyDestroyedException) {
                false
            }
            log += if (usable) "closed, the key still works" else "closed, the key is gone"
        }

        override suspend fun awaitStopped() = real.awaitStopped()

        override fun hasUnfinishedWork(): Boolean = real.hasUnfinishedWork()

        override suspend fun awaitWorkFinished() = real.awaitWorkFinished()

        override fun finishOnly() {
            log += "finish only"
            real.finishOnly()
        }

        override fun resume() {
            log += "resumed"
            real.resume()
        }

        override fun unattendedCloseKeeps(): Boolean = real.unattendedCloseKeeps()

        override fun hurryClose() {
            log += "hurried"
            real.hurryClose()
        }
    }

    /** The app from the lock down to the files, on virtual time; only the engine is scripted. */
    private class LockedApp(root: File, scheduler: TestCoroutineScheduler) {
        val dispatcher = StandardTestDispatcher(scheduler)
        val files = AppFiles(File(root, "files").apply { mkdirs() }, File(root, "cache").apply { mkdirs() })
        val layout = DataLayout(files)
        val fake = FakeInferenceEngine()

        /** What the session, the engine and the cipher did, in order. */
        val log = ArrayList<String>()

        /** Called when the engine is told to forget at a lock, before it does. */
        var onForget: () -> Unit = {}

        /**
         * A lock makes the engine forget and keep what was saved of its memory; the forgetting
         * that deletes it is for removed messages only, and is noted apart so that a lock that
         * used it would show.
         */
        private val engine = object : InferenceEngine by fake {
            override suspend fun forgetKeepingSavedMemory() {
                onForget()
                log += "forget, with ${fake.activeGenerations} generations running"
                fake.forgetKeepingSavedMemory()
            }

            override suspend fun forget() {
                log += "forget and delete what was saved"
                fake.forget()
            }
        }

        /** The cipher the lock made for the latest unlock. */
        lateinit var cipher: BlobCipher
        private var unlocks = 0

        /** For every unlock after the first: whether the key of the unlock before still worked when this one's session was opened. */
        val previousKeyAliveAtOpen = ArrayList<Boolean>()

        /** Runs on a save's writer thread before each sealed chunk of the model's memory is written: the disk of a save. */
        @Volatile var beforeAppend: (ByteBuffer) -> Unit = {}
        private val lockClock = LockClock()

        /** How often the keep-alive was started and the "ready" notice shown and cleared, wired as the app wires them. */
        var keepAliveStarts = 0
        var readyShown = 0
        var readyCleared = 0

        /** Whether Android puts the answer service in the foreground when it is created (false: `startForeground` throws). */
        var foregroundAllowed = true

        /** How long after `startService` the instance is created; normally at once, on the next turn of the main thread. */
        var serviceCreationDelayMillis = 0L

        /** Whether a `startForeground` that returns has any effect (false: it returns, and the service is not in the foreground). */
        var foregroundTakesEffect = true

        /** Whether the user has restricted the app's background use. */
        var backgroundRestricted = false

        /** Whether Android has the answer service in the foreground now, as its own record says. */
        var serviceInForeground = false

        /** How many instances of the answer service were created. */
        var serviceCreations = 0

        /** The instance that lives, while one does. */
        private var serviceInstance: Job? = null

        /** The instance has said that it runs and has not yet said that it ended: the service's `told`. */
        private var serviceTold = false

        /** The answer service's link to the app, as the application object is. */
        private val service = object : KeepAliveSource {
            override val keepAliveWanted: StateFlow<Boolean> get() = answers.keepAliveWanted

            override val keepAliveNotice: StateFlow<KeepAliveNotice> get() = answers.notice

            override fun onKeepAlive(running: Boolean) {
                answers.onKeepAlive(running)
                noteKeepAlive()
            }

            override fun onKeepAliveRefused() {
                answers.onKeepAliveRefused()
                noteKeepAlive()
            }
        }

        /**
         * `startService`: an instance is created a moment later, as Android does, unless one
         * lives (which then only gets the start command). The instance does what AnswerService
         * does, through the same entry: into the foreground, confirmed or refused; then it waits
         * until it is no longer wanted, says that it ends, and leaves the foreground.
         */
        private fun startService(): Boolean {
            keepAliveStarts++
            if (serviceInstance?.isActive == true) return true
            serviceInstance = CoroutineScope(dispatcher).launch {
                delay(serviceCreationDelayMillis)
                serviceCreations++
                val wanted = KeepAliveEntry.enter(service, { serviceInForeground }) {
                    if (!foregroundAllowed) throw IllegalStateException("not allowed from the background")
                    serviceInForeground = foregroundTakesEffect
                }
                if (wanted == null) {
                    serviceInForeground = false
                    serviceInstance = null
                    return@launch
                }
                serviceTold = true
                wanted.first { !it }
                serviceTold = false
                service.onKeepAlive(false)
                serviceInForeground = false
                serviceInstance = null
            }
            return true
        }

        /** Android stops the service from outside ("app idle", the user in the task manager): its onDestroy says that it ended. */
        fun destroyServiceFromOutside() {
            serviceInstance?.cancel()
            serviceInstance = null
            serviceInForeground = false
            if (serviceTold) {
                serviceTold = false
                service.onKeepAlive(false)
            }
        }

        val answers: AnswerWork = AnswerWork(
            keepAlive = { startService() },
            readyNotice = object : ReadyNotice {
                override fun show() {
                    readyShown++
                }

                override fun clear() {
                    readyCleared++
                }
            },
            probe = object : KeepAliveProbe {
                override fun backgroundRestricted() = this@LockedApp.backgroundRestricted

                override fun serviceInForeground() = this@LockedApp.serviceInForeground
            },
        )
        var processEnds = 0

        /** The last thing in [log] when the lock ended the process for the first time. */
        var lastBeforeProcessEnd: String? = null

        /** True while the keep-alive is wanted, as last seen by [noteKeepAlive]. */
        private var keepAliveWasWanted = false

        /**
         * Called right after everything that can let the keep-alive go: notes in [log] the
         * moment it was let go, and whether the data key of the latest unlock was still alive
         * then. Synchronous, so the note is made at that very moment and not later.
         */
        fun noteKeepAlive() {
            val wanted = answers.keepAliveWanted.value
            if (keepAliveWasWanted && !wanted) {
                val keyAlive = try {
                    cipher.seal("probe", byteArrayOf(1))
                    true
                } catch (_: KeyDestroyedException) {
                    false
                }
                log += if (keyAlive) "keep-alive let go, the key still alive" else "keep-alive let go, the key destroyed"
            }
            keepAliveWasWanted = wanted
        }

        /** What the session tells, passed on to [answers] as the app passes it, with [noteKeepAlive] after each. */
        private val events = object : AnswerEvents {
            override fun onWriting(writing: Boolean) {
                answers.onWriting(writing)
                noteKeepAlive()
            }

            override fun onReplyFinished() = answers.onReplyFinished()
        }

        val lock: AppLock<WatchedSession> = AppLock(
            manager = LockManager(
                LockConfigStore(files.lockConfig),
                SoftwareKeyVault(),
                lockClock,
                iterations = 1_000,
                minIterations = 1,
                encryptedDataExists = files::encryptedDataExists,
            ),
            files = files,
            clock = lockClock,
            openSession = { unlocked, _ ->
                if (unlocks > 0) previousKeyAliveAtOpen += keyAlive(cipher)
                cipher = unlocked
                val recording = object : DataCipher by unlocked {
                    override fun seal(purpose: String, plaintext: ByteArray): ByteArray {
                        val sealed = unlocked.seal(purpose, plaintext)
                        log += "sealed $purpose"
                        return sealed
                    }
                }
                val session = AppSession(
                    recording, layout, engine, ModelCatalog.bundled, dispatcher, dispatcher, FakeClock(), SequentialIds("u${++unlocks}-"),
                    answerEvents = events,
                    memoryPlatform = testMemoryPlatform { beforeAppend(it) },
                )
                WatchedSession(session, unlocked, log)
            },
            biometricStatus = { BiometricStatus.NONE_ENROLLED },
            screenshots = ScreenshotAllowance(lockClock, FakeScreenOffWatch(), dispatcher),
            mainDispatcher = dispatcher,
            ioDispatcher = dispatcher,
            processEnder = {
                if (processEnds++ == 0) lastBeforeProcessEnd = log.lastOrNull()
            },
            canFinishOutOfSight = answers::canFinishOutOfSight,
        )

        init {
            // As the application does: the keep-alive stays while a session is ending behind the lock.
            CoroutineScope(dispatcher).launch {
                lock.sessionEnding.collect {
                    answers.onSessionEnding(it)
                    noteKeepAlive()
                }
            }
            // And while the lock is pending out of sight.
            CoroutineScope(dispatcher).launch {
                lock.lockPending.collect {
                    answers.onLockPending(it)
                    noteKeepAlive()
                }
            }
            // And for the close of a lock by itself at the moment of leaving ("Immediately").
            CoroutineScope(dispatcher).launch {
                lock.keepAliveForClose.collect {
                    answers.onKeepAliveForClose(it)
                    noteKeepAlive()
                }
            }
            // And the end of the keep-alive ends a lease; its end and its start answer such a close.
            answers.whenKeepAliveEnds(lock::onKeepAliveEnded)
            answers.whenKeepAliveStarts(lock::onKeepAliveStarted)
        }

        /** The app's one activity reaches the screen. */
        fun comeToFront() {
            lock.onForeground()
            answers.onInFront(true)
        }

        /** It leaves the screen. */
        fun leave() {
            answers.onInFront(false)
            lock.onBackground()
        }

        /** It leaves the screen as the activity does: an unlocked session is told first ([AppSession.leftScreen]). */
        fun leaveAsTheActivityDoes() {
            answers.onInFront(false)
            (lock.state.value as? LockState.Unlocked)?.session?.real?.leftScreen()
            lock.onBackground()
        }

        fun keyAlive(cipher: BlobCipher): Boolean = try {
            cipher.seal("probe", byteArrayOf(1))
            true
        } catch (_: KeyDestroyedException) {
            false
        }

        val session: AppSession get() = (lock.state.value as LockState.Unlocked).session.real

        fun encryptedFiles(): List<String> =
            files.filesDir.walkTopDown().filter { it.isFile && it.name.endsWith(".enc") }.map { it.relativeTo(files.filesDir).path }.sorted().toList()
    }

    private val passphrase = "correct horse battery"

    private fun TestScope.setUp(app: LockedApp): AppSession {
        runCurrent()
        app.lock.setUp(passphrase.toCharArray(), passphrase.toCharArray())
        runCurrent()
        return app.session
    }

    private fun TestScope.unlock(app: LockedApp): AppSession {
        app.lock.unlockWithPassphrase(passphrase.toCharArray())
        runCurrent()
        return app.session
    }

    @Test
    fun lockingWhileAReplyIsBeingWrittenStopsItSavesItForgetsItAndOnlyThenTheKeyGoes() = runTest {
        val app = LockedApp(temp.root, testScheduler)
        var session = setUp(app)
        val id = session.openSession.id
        app.fake.enqueueReply(listOf("Half ", "way ", "there"), millisBetween = 500)
        session.chat.send(id, "Say something long")
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertEquals("Half ", session.openSession.messages.last().text)
        assertEquals(1, app.fake.activeGenerations)
        var replyWhenForgetting: MessageStatus? = null
        app.onForget = { replyWhenForgetting = session.openSession.messages.last().status }
        app.log.clear()

        app.lock.lock()
        assertTrue("the screens are gone before anything else happens", app.lock.state.value is LockState.Locked)
        assertEquals(listOf("screens cleared"), app.log)
        runCurrent()

        // 1. The generation was stopped and its reply marked as stopped before the engine was told to forget.
        assertEquals(listOf("forget, with 0 generations running"), app.log.filter { it.startsWith("forget") })
        assertEquals(MessageStatus.STOPPED, replyWhenForgetting)
        assertEquals(1, app.fake.cancelledGenerations)
        // 2. The stopped reply was written, and every write came before the close returned.
        assertTrue(app.log.contains("sealed session:$id"))
        assertEquals("closed, the key still works", app.log.last())
        assertTrue(app.log.indexOf("forget, with 0 generations running") < app.log.lastIndex)
        // 3. Only then did the lock destroy the key.
        assertThrows(KeyDestroyedException::class.java) { app.cipher.seal("probe", byteArrayOf(1)) }
        val logAtLock = app.log.toList()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("and nothing of the session did anything afterwards", logAtLock, app.log)

        session = unlock(app)
        val reply = session.openSession.messages.last()
        assertEquals("the chat comes back with what had been written, marked as stopped", "Half ", reply.text)
        assertEquals(MessageStatus.STOPPED, reply.status)
        assertEquals(id, session.openSession.id)
    }

    @Test
    fun aDraftAndTheSettingsSurviveALockAndAreOnlyEverOnDiskEncrypted() = runTest {
        val app = LockedApp(temp.root, testScheduler)
        var session = setUp(app)
        val id = session.openSession.id
        session.chat.setDraft(id, "An unsent thought")
        session.settings.update { it.withSystemPrompt(ModelCatalog.default.id, "Answer plainly.") }

        // Locked a moment after typing, before the lazy write was due.
        app.lock.lock()
        runCurrent()

        assertEquals(listOf("session-index.enc", "sessions/$id.enc", "settings.enc"), app.encryptedFiles())
        val disk = app.files.filesDir.walkTopDown().filter { it.isFile }.joinToString("") { String(it.readBytes(), Charsets.ISO_8859_1) }
        assertFalse(disk.contains("unsent"))
        assertFalse(disk.contains("plainly"))

        session = unlock(app)
        assertEquals(id, session.openSession.id)
        assertEquals("An unsent thought", session.openSession.draft)
        assertEquals("Answer plainly.", session.settings.settings.value.systemPrompt(ModelCatalog.default.id))
    }

    @Test
    fun aResetEndsTheSessionDeletesEverythingAndNothingIsWrittenAfterwards() = runTest {
        val app = LockedApp(temp.root, testScheduler)
        val session = setUp(app)
        val id = session.openSession.id
        app.fake.enqueueReply(listOf("Hi."))
        session.chat.send(id, "Hello")
        runCurrent()
        session.settings.update(urgent = true) { it.withSystemPrompt(ModelCatalog.default.id, "A prompt") }
        // And a reply that is still being written, with a draft typed beside it.
        app.fake.enqueue(listOf(Step.Emit(GenerationEvent.TextDelta("Still wri")), Step.Hang))
        session.chat.send(id, "Tell me more")
        session.chat.setDraft(id, "A draft")
        runCurrent()
        assertTrue(app.encryptedFiles().containsAll(listOf("sessions/$id.enc", "settings.enc")))

        app.lock.reset()
        runCurrent()

        assertEquals(LockState.SetupRequired, app.lock.state.value)
        assertEquals(0, app.fake.activeGenerations)
        assertEquals(1, app.fake.forgetKeepingSavedCount)
        assertEquals("every file of the app is gone", emptyList<File>(), app.files.filesDir.walkTopDown().filter { it.isFile }.toList())
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("and no late write puts one back", emptyList<File>(), app.files.filesDir.walkTopDown().filter { it.isFile }.toList())

        // First-time set-up again: an empty app.
        val fresh = setUp(app)
        assertEquals(emptyList<Any>(), fresh.chat.state.value.chats)
        assertEquals(emptyList<Any>(), fresh.openSession.messages)
        assertEquals("", fresh.settings.settings.value.systemPrompt(ModelCatalog.default.id))
    }

    @Test
    fun theModelStaysLoadedAcrossALockButTheConversationDoesNot() = runTest {
        val app = LockedApp(temp.root, testScheduler)
        val session = setUp(app)
        assertEquals(listOf(ModelCatalog.default.id), app.fake.loads)
        app.fake.enqueueReply(listOf("Hi."))
        session.chat.send(session.openSession.id, "Hello")
        runCurrent()

        app.lock.lock()
        runCurrent()
        assertEquals(1, app.fake.forgetKeepingSavedCount)
        assertEquals(0, app.fake.unloadCount)
        assertEquals(EngineState.Ready(ModelCatalog.default.id), app.fake.state.value)

        unlock(app)
        assertEquals("the unlock found the model ready and loaded nothing", listOf(ModelCatalog.default.id), app.fake.loads)
    }

    // --- The model's saved memory at a lock ----------------------------------------------------

    /**
     * What the real engine does with the vault it is given at a save: begins one, hands over
     * [pieces] pieces of the vault's buffer (all full but the last), and commits. [before] runs
     * before each piece and may suspend, or hang.
     */
    private suspend fun writeImage(vault: MemoryVault, key: String, pieces: Int = 1, before: suspend (piece: Int) -> Unit = {}): MemorySaveResult {
        val capacity = SealedStream.CHUNK_SIZE
        val memory = SavedMemory(key, ModelCatalog.default.id, contextTokens = 4_096, tokenCount = 500, readingSeconds = 60.0, imageBytes = (pieces - 1L) * capacity + 1_000)
        vault.beginSave(memory).use { writer ->
            val total = writer.firstPieceOffset + memory.imageBytes
            var written = 0L
            for (piece in 0 until pieces) {
                before(piece)
                val length = minOf(capacity.toLong(), total - written).toInt()
                val last = written + length == total
                if (!writer.write(length, last)) return MemorySaveResult.NotSaved(MemorySaveResult.NotSaved.Reason.STOPPED)
                written += length
            }
            writer.commit()
        }
        return MemorySaveResult.Saved(memory.tokenCount, memory.imageBytes)
    }

    private fun memoryFile(app: LockedApp, id: String) = File(app.layout.modelMemory, "$id.enc")

    /**
     * The order at a lock by itself, step by step: the reply is stopped, then the memory is saved
     * (the lock's own save, of the conversation the engine holds, with the key alive and nothing
     * generating), then the engine forgets without deleting it, then the close returns and only
     * then is the key destroyed. Catches a save before the reply has stopped, after forgetting,
     * or after the key; a forgetting that deletes the image; and a lock by itself without a save.
     */
    @Test
    fun aLockByItselfStopsTheReplySavesTheMemoryForgetsKeepingItAndOnlyThenTheKeyGoes() = runTest {
        val app = LockedApp(temp.root, testScheduler)
        val session = setUp(app)
        app.lock.setFinishAnswerAfterLocking(false)
        app.lock.setLockTimeout(0)
        val id = session.openSession.id
        app.fake.enqueueReply(listOf("Half ", "way ", "there"), millisBetween = 500)
        session.chat.send(id, "Say something long")
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        app.fake.onSave = { vault, key, trigger ->
            app.log += "save $trigger of ${ChatController.chatIdOf(key)}, with ${app.fake.activeGenerations} generations running, " +
                "the reply ${session.openSession.messages.last().status}, the key ${if (app.keyAlive(app.cipher)) "alive" else "gone"}"
            writeImage(vault, key)
        }
        app.log.clear()

        app.leave() // "Immediately": the app locks by itself now, and nothing finishes behind it.
        runCurrent()

        val steps = app.log.filter { it.startsWith("close") || it.startsWith("save") || it.startsWith("forget") || it.startsWith("closed") }
        assertEquals(
            listOf(
                "close UNATTENDED",
                "save LOCK of $id, with 0 generations running, the reply STOPPED, the key alive",
                "forget, with 0 generations running",
                "closed, the key still works",
            ),
            steps,
        )
        assertTrue("the memory is on disk for the next unlock", memoryFile(app, id).exists())
        assertEquals("the forgetting that deletes was never used", 0, app.fake.forgetCount)
        assertThrows(KeyDestroyedException::class.java) { app.cipher.seal("probe", byteArrayOf(1)) }
    }

    /**
     * The bound. The save hangs, and the disk under it as well: the key is still alive one
     * millisecond before the ten seconds and destroyed at them, the engine's side of the save is
     * stopped, and the process is not ended while the save's writer thread is still in the disk,
     * only once it has returned. Catches a lock that waits for the save without a bound, a bound
     * other than ten seconds, a close that waits for the writer thread, and a process ended under
     * a writer that has not returned (END-01).
     */
    @Test
    fun aSaveThatHangsLosesTheKeyAtTheBoundAndTheProcessOutlivesItsWriter() = runTest {
        val app = LockedApp(temp.root, testScheduler)
        val session = setUp(app)
        app.lock.setLockTimeout(0)
        app.fake.enqueueReply(listOf("Hi."))
        session.chat.send(session.openSession.id, "Hello")
        runCurrent()
        val disk = CountDownLatch(1)
        val inTheDisk = CountDownLatch(1)
        app.beforeAppend = {
            inTheDisk.countDown()
            // Never longer than this, so that a failing test cannot leave the thread behind for ever.
            disk.await(30, TimeUnit.SECONDS)
        }
        var engineSideStopped = false
        app.fake.onSave = { vault, key, _ ->
            try {
                writeImage(vault, key, pieces = 3) { piece -> if (piece == 1) awaitCancellation() }
            } finally {
                engineSideStopped = true
            }
        }

        app.leave()
        runCurrent()
        assertTrue("the first chunk reached the disk, which does not answer", inTheDisk.await(20, TimeUnit.SECONDS))
        advanceTimeBy(9_999)
        runCurrent()
        assertTrue("the key lives while the save may still finish", app.keyAlive(app.cipher))

        advanceTimeBy(1)
        runCurrent()
        assertFalse("at ten seconds the key is gone", app.keyAlive(app.cipher))
        assertTrue("the engine's side of the save was stopped", engineSideStopped)
        assertEquals(1, app.fake.forgetKeepingSavedCount)

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("not ended while the writer thread is in the disk", 0, app.processEnds)

        disk.countDown()
        repeat(500) {
            if (app.processEnds == 0) {
                Thread.sleep(10)
                advanceTimeBy(20)
                runCurrent()
            }
        }
        assertEquals("ended once the writer has returned", 1, app.processEnds)
        assertEquals("and the abandoned save left nothing", emptyList<String>(), app.layout.modelMemory.list().orEmpty().toList())
    }

    /**
     * "Lock now" is strict. Catches a "Lock now" that starts a save, waits for one, lets one in
     * progress go on, or deletes the memory an earlier save committed.
     */
    @Test
    fun lockNowStartsNoSaveStopsOneInProgressAndKeepsTheImageAlreadyCommitted() = runTest {
        val app = LockedApp(temp.root, testScheduler)
        val session = setUp(app)
        app.fake.enqueueReply(listOf("Hi."))
        session.chat.send(session.openSession.id, "Hello")
        runCurrent()
        val id = session.openSession.id
        app.fake.onSave = { vault, key, trigger ->
            app.log += "save $trigger"
            writeImage(vault, key)
        }
        session.leftScreen()
        runCurrent()
        session.cameOnScreen()
        assertTrue("an earlier save committed a memory", memoryFile(app, id).exists())
        val committed = memoryFile(app, id).readBytes()

        var inProgressStopped = false
        app.fake.onSave = { vault, key, trigger ->
            app.log += "save $trigger"
            try {
                writeImage(vault, key, pieces = 3) { piece -> if (piece == 1) awaitCancellation() }
            } finally {
                inProgressStopped = true
            }
        }
        session.leftScreen()
        runCurrent()
        session.cameOnScreen()
        app.log.clear()
        val startedAt = currentTime

        app.lock.lock()
        runCurrent()

        assertEquals("no keep-alive is asked for", 0, app.keepAliveStarts)
        assertEquals("no time was spent waiting", startedAt, currentTime)
        assertFalse("the key is gone at once", app.keyAlive(app.cipher))
        assertTrue(app.log.contains("close IMMEDIATE"))
        assertEquals("no save was started", emptyList<String>(), app.log.filter { it.startsWith("save") })
        assertTrue("the save in progress was stopped", inProgressStopped)
        assertArrayEquals("the memory committed before is on disk as it was", committed, memoryFile(app, id).readBytes())
        assertEquals(0, app.fake.forgetCount)
    }

    /**
     * The end of a lease saves the memory of the finished answer. Catches a lease end that does
     * not save, saves before the answer is finished, or saves on leaving behind the lock (only
     * the lock's own save belongs there).
     */
    @Test
    fun theEndOfALeaseSavesTheMemoryOfTheFinishedAnswer() = runTest {
        val app = LockedApp(temp.root, testScheduler)
        val session = inTheMiddleOfAnAnswer(app)
        val id = session.openSession.id
        app.fake.onSave = { vault, key, trigger ->
            val last = session.openSession.messages.last()
            app.log += "save $trigger, the answer \"${last.text}\" ${last.status}"
            writeImage(vault, key)
        }

        app.leave()
        runCurrent()
        assertEquals("nothing is saved while the answer is finished", emptyList<String>(), app.log.filter { it.startsWith("save") })
        advanceTimeBy(5_000)
        runCurrent()

        val steps = app.log.filter { it.startsWith("close") || it.startsWith("save") || it.startsWith("forget") || it.startsWith("closed") }
        assertEquals(
            listOf("close UNATTENDED", "save LOCK, the answer \"Half way there\" COMPLETE", "forget, with 0 generations running", "closed, the key still works"),
            steps,
        )
        assertTrue(memoryFile(app, id).exists())
    }

    /**
     * An unlock while the session before is still saving at its lock: the lock's mutex makes the
     * unlock wait until that close has ended and its key is destroyed, within the bound, and the
     * new session opens only then. Catches a new session opened beside a close that still holds
     * a key, and an unlock that waits for more than the close's bounds.
     */
    @Test
    fun anUnlockWhileTheSessionBeforeIsStillSavingWaitsForItsCloseWithinTheBound() = runTest {
        val app = LockedApp(temp.root, testScheduler)
        val session = setUp(app)
        app.lock.setLockTimeout(0)
        app.fake.enqueueReply(listOf("Hi."))
        session.chat.send(session.openSession.id, "Hello")
        runCurrent()
        app.fake.onSave = { _, _, _ -> awaitCancellation() }
        val first = app.cipher

        app.leave()
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(app.keyAlive(first))
        app.fake.onSave = null

        app.lock.unlockWithPassphrase(passphrase.toCharArray())
        runCurrent()
        assertTrue("still locked: the close before holds the lock", app.lock.state.value is LockState.Locked)

        advanceTimeBy(8_000)
        runCurrent()
        assertTrue("unlocked as soon as the close before has ended", app.lock.state.value is LockState.Unlocked)
        assertFalse(app.keyAlive(first))
        assertEquals("the new session was opened after the old key was gone", listOf(false), app.previousKeyAliveAtOpen)
    }

    // --- Finishing an answer behind the lock ---------------------------------------------------

    /** Set-up in front, the lock set to "immediately", and an answer of three words begun: "Half " is written. */
    private suspend fun TestScope.inTheMiddleOfAnAnswer(app: LockedApp): AppSession {
        app.lock.onActivityCreated()
        app.comeToFront()
        val session = setUp(app)
        app.lock.setLockTimeout(0)
        app.fake.enqueueReply(listOf("Half ", "way ", "there"), millisBetween = 500)
        assertTrue(session.chat.send(session.openSession.id, "Say something long"))
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertEquals("Half ", session.openSession.messages.last().text)
        app.log.clear()
        return session
    }

    @Test
    fun anAnswerIsFinishedBehindTheAppsOwnLockThenSavedForgottenAndOnlyThenTheKeyGoes() = runTest {
        val app = LockedApp(temp.root, testScheduler)
        val session = inTheMiddleOfAnAnswer(app)
        val id = session.openSession.id
        assertEquals(1, app.keepAliveStarts)
        assertTrue("kept alive from the moment the answer began", app.answers.keepAliveWanted.value)

        app.leave() // "Immediately": the app locks by itself now.
        assertTrue("locked for whoever looks", app.lock.state.value is LockState.Locked)
        assertEquals(listOf("screens cleared", "finish only"), app.log)
        runCurrent()
        assertEquals("the answer goes on", 1, app.fake.activeGenerations)
        assertEquals(0, app.fake.forgetKeepingSavedCount)
        assertTrue(app.lock.sessionEnding.value)
        assertEquals(0, app.processEnds)

        assertFalse(session.chat.send(id, "Something else"))
        runCurrent()
        assertEquals(1, app.fake.startedGenerations)
        assertEquals(1, app.fake.activeGenerations)

        advanceTimeBy(5_000)
        runCurrent()
        // The answer was finished, by the model itself; then the session ended as at any lock, in the same order.
        assertEquals(1, app.fake.completedGenerations)
        assertEquals(0, app.fake.cancelledGenerations)
        assertEquals(listOf("forget, with 0 generations running"), app.log.filter { it.startsWith("forget") })
        assertTrue(app.log.contains("sealed session:$id"))
        val closed = app.log.indexOf("closed, the key still works")
        assertTrue("every write came before the close returned: ${app.log}", app.log.lastIndexOf("sealed session:$id") < closed)
        assertTrue(app.log.indexOf("forget, with 0 generations running") < closed)
        assertThrows(KeyDestroyedException::class.java) { app.cipher.seal("probe", byteArrayOf(1)) }
        assertFalse(app.lock.sessionEnding.value)

        // Kept alive until the key was gone and no longer; told once that the answer is ready; then the process ended.
        assertFalse(app.answers.keepAliveWanted.value)
        assertEquals(1, app.keepAliveStarts)
        assertEquals(1, app.readyShown)
        assertEquals(1, app.processEnds)
        assertEquals("the end of the process came after the close, and nothing after it", "closed, the key still works", app.lastBeforeProcessEnd)
        // The security review's D2: the keep-alive was let go once, and only after the key was destroyed.
        assertEquals(listOf("closed, the key still works", "keep-alive let go, the key destroyed"), app.log.takeLast(2))
        assertEquals(listOf("keep-alive let go, the key destroyed"), app.log.filter { it.startsWith("keep-alive") })

        // A cold unlock finds the whole answer, finished; coming to the front took the notification away.
        val cleared = app.readyCleared
        app.comeToFront()
        assertEquals(cleared + 1, app.readyCleared)
        val again = unlock(app)
        assertFalse(again === session)
        val reply = again.openSession.messages.last()
        assertEquals("Half way there", reply.text)
        assertEquals(MessageStatus.COMPLETE, reply.status)
    }

    /**
     * The security review's A3 and D5: Android refuses to put the answer service in the
     * foreground (its `startForeground` throws when it is created). The keep-alive is then known
     * not to exist: nothing is wanted any more, and when the app locks by itself the answer is
     * stopped and the key destroyed as with the setting off, instead of a lease that no timer
     * might ever end.
     */
    @Test
    fun aKeepAliveThatAndroidRefusesLeavesNoLeaseBehindTheLock() = runTest {
        val app = LockedApp(temp.root, testScheduler)
        app.foregroundAllowed = false
        val session = inTheMiddleOfAnAnswer(app)
        val id = session.openSession.id
        assertEquals("the start was asked for", 1, app.keepAliveStarts)
        assertFalse("and is known to have failed", app.answers.keepAliveRunning)
        assertFalse(app.answers.keepAliveWanted.value)

        app.leave() // "Immediately": the app locks by itself now.
        runCurrent()
        assertTrue(app.lock.state.value is LockState.Locked)
        assertFalse("no lease", app.log.contains("finish only"))
        assertEquals(0, app.fake.activeGenerations)
        assertEquals(1, app.fake.cancelledGenerations)
        assertThrows(KeyDestroyedException::class.java) { app.cipher.seal("probe", byteArrayOf(1)) }
        assertFalse(app.lock.sessionEnding.value)
        assertEquals(1, app.processEnds)

        app.comeToFront()
        val reply = unlock(app).openSession.messages.last()
        assertEquals(id, app.session.openSession.id)
        assertEquals("Half ", reply.text)
        assertEquals(MessageStatus.STOPPED, reply.status)
    }

    @Test
    fun theKeepAliveIsKnownToRunOnlyFromWhenTheServiceIsInTheForegroundUntilItEnds() = runTest {
        val app = LockedApp(temp.root, testScheduler)
        app.lock.onActivityCreated()
        app.comeToFront()
        val session = setUp(app)
        assertFalse(app.answers.keepAliveRunning)
        app.fake.enqueueReply(listOf("One ", "two"), millisBetween = 500)
        session.chat.send(session.openSession.id, "Count")
        runCurrent()
        assertTrue(app.answers.keepAliveWanted.value)
        assertTrue("the service was created and reached the foreground", app.answers.keepAliveRunning)
        assertTrue(app.serviceInForeground)
        // The answer ends; the service, no longer wanted, says so and leaves the foreground by itself.
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse(app.answers.keepAliveWanted.value)
        assertFalse("told by the service itself", app.answers.keepAliveRunning)
        assertFalse(app.serviceInForeground)
        session.close()
    }

    /**
     * The third review's gap: two answers in a row, with the service really ending between them.
     * The second answer gets a new instance that reaches the foreground again, and the app's own
     * lock during it still leaves the answer to be finished.
     */
    @Test
    fun twoAnswersInARowEachGetAKeepAliveOfTheirOwn() = runTest {
        val app = LockedApp(temp.root, testScheduler)
        app.lock.onActivityCreated()
        app.comeToFront()
        val session = setUp(app)
        app.lock.setLockTimeout(0)
        val id = session.openSession.id
        app.fake.enqueueReply(listOf("One ", "two"), millisBetween = 500)
        session.chat.send(id, "Count")
        runCurrent()
        assertEquals(1, app.serviceCreations)
        assertTrue(app.answers.keepAliveRunning)
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse("the first instance has ended", app.answers.keepAliveRunning)
        assertFalse(app.serviceInForeground)

        app.fake.enqueueReply(listOf("Three ", "four ", "five"), millisBetween = 500)
        session.chat.send(id, "Go on")
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertEquals("a new instance", 2, app.serviceCreations)
        assertTrue(app.answers.keepAliveRunning)
        assertTrue(app.serviceInForeground)
        app.log.clear()

        app.leave()
        assertEquals(listOf("screens cleared", "finish only"), app.log)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, app.fake.completedGenerations)
        assertThrows(KeyDestroyedException::class.java) { app.cipher.seal("probe", byteArrayOf(1)) }
        assertFalse(app.answers.keepAliveRunning)
        assertFalse(app.serviceInForeground)
        assertEquals(1, app.processEnds)
    }

    /**
     * The third review's finding 1: a `startForeground` that returns without effect (an app-op
     * set to ignore, or background use restricted and the app not on top) is a refusal. Without
     * the check after it, the keep-alive would be believed to run and a lease would start.
     */
    @Test
    fun aStartForegroundThatReturnsWithoutEffectLeavesNoLeaseBehindTheLock() = runTest {
        val app = LockedApp(temp.root, testScheduler)
        app.foregroundTakesEffect = false
        val session = inTheMiddleOfAnAnswer(app)
        assertEquals(1, app.serviceCreations)
        assertFalse(app.answers.keepAliveRunning)
        assertFalse(app.answers.keepAliveWanted.value)

        app.leave()
        runCurrent()
        assertFalse("no lease", app.log.contains("finish only"))
        assertEquals(1, app.fake.cancelledGenerations)
        assertThrows(KeyDestroyedException::class.java) { app.cipher.seal("probe", byteArrayOf(1)) }
        assertEquals(1, app.processEnds)
        assertEquals(MessageStatus.STOPPED, session.openSession.messages.last().status)
    }

    /**
     * Finding 1: the user has restricted the app's background use. Android takes the service out
     * of the foreground as the app leaves the top, without a word, so the lock asks and starts
     * no lease, although the service did reach the foreground while the app was on top.
     */
    @Test
    fun backgroundUseRestrictedAtTheLockLeavesNoLease() = runTest {
        val app = LockedApp(temp.root, testScheduler)
        inTheMiddleOfAnAnswer(app)
        assertTrue(app.answers.keepAliveRunning)
        assertTrue(app.serviceInForeground)
        app.backgroundRestricted = true

        app.leave()
        runCurrent()
        assertFalse("no lease", app.log.contains("finish only"))
        assertEquals(0, app.fake.activeGenerations)
        assertEquals(1, app.fake.cancelledGenerations)
        assertThrows(KeyDestroyedException::class.java) { app.cipher.seal("probe", byteArrayOf(1)) }
        assertFalse(app.lock.sessionEnding.value)
        assertEquals(1, app.processEnds)
    }

    /** Finding 1: Android has already taken the service out of the foreground when the app locks, and nothing told it. */
    @Test
    fun aServiceNoLongerInTheForegroundAtTheLockLeavesNoLease() = runTest {
        val app = LockedApp(temp.root, testScheduler)
        inTheMiddleOfAnAnswer(app)
        app.serviceInForeground = false
        assertTrue("the flag alone still says it runs", app.answers.keepAliveRunning)

        app.leave()
        runCurrent()
        assertFalse("no lease", app.log.contains("finish only"))
        assertEquals(1, app.fake.cancelledGenerations)
        assertThrows(KeyDestroyedException::class.java) { app.cipher.seal("probe", byteArrayOf(1)) }
        assertEquals(1, app.processEnds)
    }

    /**
     * Finding 1's net: while a lease runs, Android stops the answer service from outside (as
     * "Stopping service due to app idle" does). The lease ends at once, by the ceiling's path:
     * the answer is stopped and saved as stopped, the session closed, the key destroyed and the
     * process ended, long before the ten minutes.
     */
    @Test
    fun aKeepAliveStoppedFromOutsideDuringALeaseEndsTheLeaseAtOnce() = runTest {
        val app = LockedApp(temp.root, testScheduler)
        val session = inTheMiddleOfAnAnswer(app)
        val id = session.openSession.id
        app.leave()
        runCurrent()
        assertEquals(listOf("screens cleared", "finish only"), app.log.filterNot { it.startsWith("sealed") })
        assertTrue(app.lock.sessionEnding.value)
        assertEquals(1, app.fake.activeGenerations)

        app.destroyServiceFromOutside()
        runCurrent()
        assertEquals("stopped, not finished", 0, app.fake.completedGenerations)
        assertEquals(1, app.fake.cancelledGenerations)
        assertTrue("nothing keeps the phone awake now: no save, no wait", app.log.contains("close IMMEDIATE"))
        assertEquals(emptyList<Pair<String, SaveTrigger>>(), app.fake.saves)
        assertThrows(KeyDestroyedException::class.java) { app.cipher.seal("probe", byteArrayOf(1)) }
        assertFalse(app.lock.sessionEnding.value)
        assertEquals(1, app.processEnds)
        assertEquals("an answer that was cut off is not announced", 0, app.readyShown)

        app.comeToFront()
        val reply = unlock(app).openSession.messages.last()
        assertEquals(id, app.session.openSession.id)
        assertEquals("Half ", reply.text)
        assertEquals(MessageStatus.STOPPED, reply.status)
    }

    /**
     * "Stop the answer" on the lock screen during a lease: the answer written so far is kept as
     * a stopped answer, the session is closed and its key destroyed, the keep-alive is let go
     * only after that, and the process ends once the lock screen is left.
     */
    @Test
    fun stoppingTheAnswerFromTheLockScreenKeepsItAsStoppedAndDestroysTheKey() = runTest {
        val app = LockedApp(temp.root, testScheduler)
        val session = inTheMiddleOfAnAnswer(app)
        val id = session.openSession.id
        app.leave()
        runCurrent()
        app.comeToFront() // The lock screen.
        assertTrue(app.lock.leaseRunning.value)
        assertEquals(1, app.fake.activeGenerations)

        app.lock.stopAnswerBehindLock()
        runCurrent()
        assertFalse(app.lock.leaseRunning.value)
        assertTrue("closed as every other end of a lease, not as \"Lock now\"", app.log.contains("close UNATTENDED"))
        assertEquals("stopped, not finished", 0, app.fake.completedGenerations)
        assertEquals(1, app.fake.cancelledGenerations)
        assertThrows(KeyDestroyedException::class.java) { app.cipher.seal("probe", byteArrayOf(1)) }
        assertEquals(listOf("keep-alive let go, the key destroyed"), app.log.filter { it.startsWith("keep-alive") })
        assertEquals("not while the lock screen is shown", 0, app.processEnds)
        assertEquals("a stopped answer is not announced", 0, app.readyShown)

        app.leave()
        runCurrent()
        assertEquals(1, app.processEnds)
        app.comeToFront()
        val reply = unlock(app).openSession.messages.last()
        assertEquals(id, app.session.openSession.id)
        assertEquals("Half ", reply.text)
        assertEquals(MessageStatus.STOPPED, reply.status)
    }

    @Test
    fun unlockingDuringALeaseFindsTheSameSessionAndTheAnswerUnbroken() = runTest {
        val app = LockedApp(temp.root, testScheduler)
        val session = inTheMiddleOfAnAnswer(app)
        val id = session.openSession.id
        app.leave()
        runCurrent()
        assertTrue(app.lock.state.value is LockState.Locked)

        app.comeToFront()
        val back = unlock(app) // The passphrase, checked by the real lock manager.
        assertTrue("the very same session", back === session)
        assertEquals(listOf("screens cleared", "finish only", "resumed"), app.log.filterNot { it.startsWith("sealed") })
        assertEquals(1, app.fake.activeGenerations)
        assertTrue(app.answers.keepAliveWanted.value)

        advanceTimeBy(5_000)
        runCurrent()
        val reply = session.openSession.messages.last()
        assertEquals("written in one piece, by one generation", "Half way there", reply.text)
        assertEquals(MessageStatus.COMPLETE, reply.status)
        assertEquals(1, app.fake.startedGenerations)
        assertEquals(0, app.fake.cancelledGenerations)
        assertEquals(0, app.fake.forgetKeepingSavedCount)
        assertFalse(app.answers.keepAliveWanted.value)
        assertEquals("the user is looking: no notification", 0, app.readyShown)
        assertEquals(0, app.processEnds)

        // The session takes new work again.
        app.fake.enqueueReply(listOf("More."))
        assertTrue(session.chat.send(id, "And then?"))
        runCurrent()
        assertEquals("More.", session.openSession.messages.last().text)
        session.close()
    }

    @Test
    fun aSessionToldToFinishOnlyStartsNothingNewUntilItIsResumed() = runTest {
        val app = SessionFixture(temp.root, testScheduler)
        val session = app.open()
        runCurrent()
        val id = session.openSession.id
        assertFalse(session.hasUnfinishedWork())
        session.awaitWorkFinished() // Nothing to wait for: returns at once.

        session.finishOnly()
        app.engine.enqueueReply(listOf("No."))
        assertFalse("no answer is begun behind the lock", session.chat.send(id, "Hello"))
        assertFalse(session.chat.retry(id))
        session.chat.reloadModel()
        runCurrent()
        assertEquals(0, app.engine.startedGenerations)
        assertEquals(emptyList<Any>(), session.openSession.messages)
        assertFalse(session.hasUnfinishedWork())

        session.resume()
        assertTrue(session.chat.send(id, "Hello"))
        assertTrue(session.hasUnfinishedWork())
        runCurrent()
        assertEquals("No.", session.openSession.messages.last().text)
        assertFalse(session.hasUnfinishedWork())
        session.close()
    }

    @Test
    fun theCeilingStopsAnAnswerThatDoesNotEndAndKeepsWhatWasWritten() = runTest {
        val app = LockedApp(temp.root, testScheduler)
        app.lock.onActivityCreated()
        app.comeToFront()
        val session = setUp(app)
        app.lock.setLockTimeout(0)
        app.fake.enqueue(listOf(Step.Emit(GenerationEvent.TextDelta("Still wri")), Step.Hang))
        session.chat.send(session.openSession.id, "Tell me everything")
        runCurrent()
        app.leave()
        runCurrent()

        advanceTimeBy(AppLock.LEASE_MILLIS - 1)
        runCurrent()
        assertEquals(1, app.fake.activeGenerations)
        assertEquals(0, app.processEnds)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(0, app.fake.activeGenerations)
        assertEquals(1, app.fake.cancelledGenerations)
        assertThrows(KeyDestroyedException::class.java) { app.cipher.seal("probe", byteArrayOf(1)) }
        assertFalse(app.answers.keepAliveWanted.value)
        assertEquals("an answer that was cut off is not announced as ready", 0, app.readyShown)
        assertEquals(1, app.processEnds)

        app.comeToFront()
        val reply = unlock(app).openSession.messages.last()
        assertEquals("Still wri", reply.text)
        assertEquals(MessageStatus.STOPPED, reply.status)
    }

    @Test
    fun theKeepAliveRunsExactlyWhileAnAnswerIsWrittenAndReadyIsOnlySaidToSomeoneNotLooking() = runTest {
        val app = LockedApp(temp.root, testScheduler)
        app.lock.onActivityCreated()
        app.comeToFront()
        val session = setUp(app)
        val id = session.openSession.id
        assertFalse("nothing is kept alive for an idle app", app.answers.keepAliveWanted.value)
        assertEquals(0, app.keepAliveStarts)

        // An answer written while the user watches.
        app.fake.enqueueReply(listOf("One ", "two"), millisBetween = 500)
        session.chat.send(id, "Count")
        runCurrent()
        assertTrue(app.answers.keepAliveWanted.value)
        assertEquals(1, app.keepAliveStarts)
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse("over with the answer", app.answers.keepAliveWanted.value)
        assertEquals(0, app.readyShown)

        // One that finishes while the app is in the background, unlocked (the lock's minute has not passed).
        app.fake.enqueueReply(listOf("Three ", "four"), millisBetween = 500)
        session.chat.send(id, "Go on")
        runCurrent()
        assertEquals(2, app.keepAliveStarts)
        app.leave()
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(app.lock.state.value is LockState.Unlocked)
        assertTrue("the answer is over, but the lock is still pending", app.answers.keepAliveWanted.value)
        assertEquals(1, app.readyShown)

        // Coming back takes the notification away.
        val cleared = app.readyCleared
        app.comeToFront()
        assertEquals(cleared + 1, app.readyCleared)

        // One the user stops is not "ready".
        app.fake.enqueue(listOf(Step.Emit(GenerationEvent.TextDelta("Five")), Step.Hang))
        session.chat.send(id, "More")
        runCurrent()
        assertTrue(app.answers.keepAliveWanted.value)
        app.leave()
        session.chat.stop(id)
        runCurrent()
        assertTrue("stopped, and kept for the lock that is pending", app.answers.keepAliveWanted.value)
        assertEquals(1, app.readyShown)
        assertEquals(3, app.keepAliveStarts)
        app.comeToFront()
        runCurrent()
        assertFalse("nothing to write and nothing to wait for", app.answers.keepAliveWanted.value)
        session.close()
    }

    // --- The timed lock on time -------------------------------------------------------------------

    /** Set-up in front with the lock's default minute, and nothing being written. */
    private fun TestScope.unlockedInFront(app: LockedApp): AppSession {
        app.lock.onActivityCreated()
        app.comeToFront()
        val session = setUp(app)
        assertEquals(60, app.lock.status.value.lockTimeoutSeconds)
        app.log.clear()
        return session
    }

    /**
     * Leaving the screen unlocked with a timed lock pending starts the keep-alive at that moment,
     * with the notification that the app will lock itself; it runs until the lock has fired and
     * its session is closed, the key destroyed, and is let go then and not before; the process
     * ends once, after that.
     */
    @Test
    fun leavingUnlockedKeepsTheAppAliveUntilItsLockHasFiredAndTheKeyIsGone() = runTest {
        val app = LockedApp(temp.root, testScheduler)
        unlockedInFront(app)
        runCurrent()
        assertEquals("nothing is kept alive while the app is in front", 0, app.keepAliveStarts)

        app.leave()
        runCurrent()
        assertEquals("started at the moment of leaving", 1, app.keepAliveStarts)
        assertTrue(app.answers.keepAliveWanted.value)
        assertTrue("in the foreground, as Android confirms", app.answers.keepAliveRunning)
        assertEquals(KeepAliveNotice.LOCK_PENDING, app.answers.notice.value)

        advanceTimeBy(59_999)
        runCurrent()
        assertTrue(app.lock.state.value is LockState.Unlocked)
        assertTrue(app.answers.keepAliveWanted.value)
        assertEquals(0, app.processEnds)

        advanceTimeBy(1)
        runCurrent()
        assertTrue(app.lock.state.value is LockState.Locked)
        assertThrows(KeyDestroyedException::class.java) { app.cipher.seal("probe", byteArrayOf(1)) }
        assertFalse(app.answers.keepAliveWanted.value)
        assertFalse(app.answers.keepAliveRunning)
        assertEquals("let go once, and only once the key was gone", listOf("keep-alive let go, the key destroyed"), app.log.filter { it.startsWith("keep-alive") })
        assertEquals(1, app.processEnds)
        assertEquals("closed, the key still works", app.lastBeforeProcessEnd)
        assertEquals(1, app.keepAliveStarts)

        advanceTimeBy(AppLock.LEASE_MILLIS)
        runCurrent()
        assertEquals("the process ends once", 1, app.processEnds)
        assertEquals(1, app.serviceCreations)
    }

    @Test
    fun comingBackBeforeTheLockLetsTheKeepAliveGoAndLeavingAgainStartsItAgain() = runTest {
        val app = LockedApp(temp.root, testScheduler)
        val session = unlockedInFront(app)
        app.leave()
        runCurrent()
        assertTrue(app.answers.keepAliveRunning)

        advanceTimeBy(30_000)
        app.comeToFront()
        runCurrent()
        assertTrue(app.lock.state.value is LockState.Unlocked)
        assertFalse("back on screen: nothing to wait for", app.answers.keepAliveWanted.value)
        assertFalse(app.answers.keepAliveRunning)
        assertEquals(0, app.processEnds)

        // The timer of the first absence does not fire later either.
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(app.lock.state.value is LockState.Unlocked)

        app.leave()
        runCurrent()
        assertEquals(2, app.keepAliveStarts)
        assertEquals(2, app.serviceCreations)
        assertTrue(app.answers.keepAliveRunning)
        app.comeToFront()
        runCurrent()
        assertFalse(app.answers.keepAliveWanted.value)
        session.close()
    }

    /**
     * With "Immediately" the app locks inside the call that says it left the screen, and the
     * close of the session, which saves the model's memory, waits to hear whether the keep-alive
     * runs: it is started at that moment, its notification says that the app is locking, the
     * save is made while it runs, and it is let go only after the key is gone. Refused, the close
     * saves nothing and the key goes at once; with no answer within the bound, the same at the
     * bound. Catches a lock by itself that saves with nothing keeping the phone awake, a
     * keep-alive let go before the key, and a close that waits
     * for ever for a service that never answers.
     */
    @Test
    fun withImmediatelyTheKeepAliveRunsForTheLocksSaveUntilTheKeyIsGone() = runTest {
        for (case in listOf("started", "refused", "no answer")) {
            val app = LockedApp(temp.newFolder(case), testScheduler)
            val session = unlockedInFront(app)
            app.lock.setLockTimeout(0)
            app.fake.enqueueReply(listOf("Hi."))
            session.chat.send(session.openSession.id, "Hello")
            runCurrent()
            val id = session.openSession.id
            when (case) {
                "refused" -> app.foregroundAllowed = false
                "no answer" -> app.serviceCreationDelayMillis = AppLock.KEEP_ALIVE_ANSWER_MILLIS + 1_000
            }
            app.fake.onSave = { vault, key, trigger ->
                app.log += "save $trigger, the keep-alive ${if (app.answers.canFinishOutOfSight()) "running" else "not running"}, saying ${app.answers.notice.value}"
                writeImage(vault, key)
            }
            assertFalse("the answer's own keep-alive has ended", app.answers.keepAliveWanted.value)
            val startsBefore = app.keepAliveStarts
            val creationsBefore = app.serviceCreations
            app.log.clear()
            val leftAt = currentTime

            app.leave()
            assertTrue("$case: locked for whoever looks", app.lock.state.value is LockState.Locked)
            runCurrent()
            assertEquals("$case: asked for at the moment of leaving", startsBefore + 1, app.keepAliveStarts)
            val steps = app.log.filter { it.startsWith("close") || it.startsWith("save") || it.startsWith("keep-alive") || it.startsWith("closed") }
            when (case) {
                "started" -> {
                    assertEquals(
                        listOf(
                            "close UNATTENDED",
                            "save LOCK, the keep-alive running, saying LOCKING",
                            "closed, the key still works",
                            "keep-alive let go, the key destroyed",
                        ),
                        steps,
                    )
                    assertTrue("the memory is on disk for the next unlock", memoryFile(app, id).exists())
                    assertEquals(creationsBefore + 1, app.serviceCreations)
                }
                "refused" -> {
                    // Refused when the service was created, which was before the close began.
                    assertEquals(listOf("keep-alive let go, the key still alive", "close IMMEDIATE", "closed, the key still works"), steps)
                    assertFalse("nothing is saved without the keep-alive", memoryFile(app, id).exists())
                    assertEquals("no time was spent waiting", leftAt, currentTime)
                }
                "no answer" -> {
                    assertEquals("$case: the close waits for the answer", emptyList<String>(), steps)
                    assertTrue(app.keyAlive(app.cipher))
                    advanceTimeBy(AppLock.KEEP_ALIVE_ANSWER_MILLIS - 1)
                    runCurrent()
                    assertTrue("$case: within the bound", app.keyAlive(app.cipher))
                    advanceTimeBy(1)
                    runCurrent()
                    assertEquals(listOf("close IMMEDIATE", "closed, the key still works"), app.log.filter { it.startsWith("close") })
                    assertFalse(memoryFile(app, id).exists())
                    // The service that comes late finds nothing wanted and ends.
                    advanceTimeBy(2_000)
                    runCurrent()
                    assertFalse(app.answers.keepAliveRunning)
                }
            }
            assertFalse(case, app.keyAlive(app.cipher))
            assertFalse(case, app.answers.keepAliveWanted.value)
            assertFalse(case, app.lock.keepAliveForClose.value)
            assertEquals(case, 1, app.processEnds)
        }
    }

    /** One message and its answer in the chat on screen, so that the engine holds a conversation. */
    private fun TestScope.exchange(app: LockedApp, session: AppSession) {
        app.fake.enqueueReply(listOf("Hi."))
        session.chat.send(session.openSession.id, "Hello")
        runCurrent()
    }

    /**
     * With "Immediately", a close that would keep nothing (the switch off, no conversation held,
     * or the memory on disk already as it is in the engine) asks for no keep-alive and waits for
     * nothing: the key goes at once. Catches the service, its "locking" notification and up to
     * five seconds of waiting with the key in memory, for a close with nothing to keep.
     */
    @Test
    fun withImmediatelyACloseThatWouldKeepNothingStartsNoKeepAliveAndWaitsForNothing() = runTest {
        for (case in listOf("switch off", "nothing held", "already on disk")) {
            val app = LockedApp(temp.newFolder(case), testScheduler)
            val session = unlockedInFront(app)
            app.lock.setLockTimeout(0)
            app.fake.onSave = { vault, key, _ -> writeImage(vault, key) }
            when (case) {
                "switch off" -> {
                    exchange(app, session)
                    session.changeKeepModelMemory(false)
                    runCurrent()
                }
                "nothing held" -> Unit
                "already on disk" -> {
                    exchange(app, session)
                    session.leftScreen()
                    runCurrent()
                    session.cameOnScreen()
                    assertTrue(memoryFile(app, session.openSession.id).exists())
                }
            }
            assertFalse(case, session.unattendedCloseKeeps())
            assertFalse("$case: the answer's own keep-alive has ended", app.answers.keepAliveWanted.value)
            val startsBefore = app.keepAliveStarts
            app.log.clear()
            val leftAt = currentTime

            app.leave()
            runCurrent()
            assertEquals("$case: no keep-alive is asked for", startsBefore, app.keepAliveStarts)
            assertEquals(case, listOf("close IMMEDIATE", "closed, the key still works"), app.log.filter { it.startsWith("close") })
            assertEquals("$case: nothing was waited for", leftAt, currentTime)
            assertFalse(case, app.keyAlive(app.cipher))
            assertFalse(case, app.lock.keepAliveForClose.value)
            assertEquals(case, 1, app.processEnds)
        }
    }

    /**
     * The keep-alive is lost (the service stopped from outside) while the close of "Immediately"
     * is saving the model's memory: the save is stopped at that moment, the close goes on as an
     * immediate one, and the key goes without waiting for the save's bound. Catches a save that
     * goes on, with the key in memory, after nothing keeps the phone awake any more.
     */
    @Test
    fun aKeepAliveLostDuringTheLocksSaveStopsTheSaveAndTheKeyGoesAtOnce() = runTest {
        val app = LockedApp(temp.root, testScheduler)
        val session = setUp(app)
        app.lock.setLockTimeout(0)
        exchange(app, session)
        val id = session.openSession.id
        var saveStopped = false
        app.fake.onSave = { vault, key, trigger ->
            app.log += "save $trigger"
            try {
                writeImage(vault, key, pieces = 3) { piece -> if (piece == 1) awaitCancellation() }
            } finally {
                saveStopped = true
            }
        }

        app.leave()
        runCurrent()
        assertTrue(app.answers.keepAliveRunning)
        assertEquals(listOf("close UNATTENDED", "save LOCK"), app.log.filter { it.startsWith("close") || it.startsWith("save") })
        assertTrue("the key lives while the save runs", app.keyAlive(app.cipher))
        val lostAt = currentTime

        app.destroyServiceFromOutside()
        runCurrent()
        assertTrue(app.log.contains("hurried"))
        assertTrue("the save was stopped", saveStopped)
        assertFalse("the key is gone", app.keyAlive(app.cipher))
        assertEquals("without waiting for the save's bound", lostAt, currentTime)
        assertEquals(1, app.fake.forgetKeepingSavedCount)
        repeat(500) {
            if (app.processEnds == 0) {
                Thread.sleep(10)
                advanceTimeBy(20)
                runCurrent()
            }
        }
        assertEquals(1, app.processEnds)
        assertFalse("nothing of the stopped save was kept", memoryFile(app, id).exists())
    }

    /**
     * A memory made with one system prompt, whose deletion when the prompt was replaced did not
     * happen (here it is put back afterwards), is refused and deleted by the next unlock's
     * restore, because it is bound to the prompt it was made with. Catches the old prompt read
     * back into the engine at every unlock.
     */
    @Test
    fun aMemoryMadeWithAReplacedSystemPromptIsRefusedEvenWhereItsDeletionNeverHappened() = runTest {
        val app = LockedApp(temp.root, testScheduler)
        var session = setUp(app)
        app.lock.setLockTimeout(0)
        val model = session.openSession.modelId
        session.settings.update(urgent = true) { it.withSystemPrompt(model, "First prompt") }
        exchange(app, session)
        val id = session.openSession.id
        app.fake.onSave = { vault, key, _ -> writeImage(vault, key) }
        app.leave()
        runCurrent()
        val file = memoryFile(app, id)
        assertTrue(file.exists())
        val bytes = file.readBytes()
        val date = file.lastModified()
        app.fake.onRestore = { vault, _, _ ->
            app.log += "kept: ${vault.saved() != null}"
            MemoryRestoreResult.NothingSaved
        }

        app.comeToFront()
        session = unlock(app)
        runCurrent()
        assertEquals("with the same prompt the memory is usable", listOf("kept: true"), app.log.filter { it.startsWith("kept") })
        session.settings.update { it.withSystemPrompt(model, "Second prompt") }
        runCurrent()
        assertFalse("deleted at the change", file.exists())
        app.lock.lock()
        runCurrent()

        // As if that deletion had never happened.
        file.parentFile!!.mkdirs()
        file.writeBytes(bytes)
        file.setLastModified(date)
        app.log.clear()
        unlock(app)
        runCurrent()
        assertEquals(listOf("kept: false"), app.log.filter { it.startsWith("kept") })
        assertFalse("refused and deleted", file.exists())
    }

    /**
     * An answer that ends out of sight while the app is still unlocked no longer lets the
     * keep-alive go: it stays until the lock has fired, and its notification then says that the
     * app will lock itself instead of that an answer is being written.
     */
    @Test
    fun anAnswerThatEndsOutOfSightKeepsTheKeepAliveUntilTheLockHasFired() = runTest {
        val app = LockedApp(temp.root, testScheduler)
        val session = unlockedInFront(app)
        app.fake.enqueueReply(listOf("One ", "two"), millisBetween = 500)
        session.chat.send(session.openSession.id, "Count")
        runCurrent()
        assertEquals(1, app.keepAliveStarts)
        assertEquals(KeepAliveNotice.WRITING, app.answers.notice.value)

        app.leave()
        runCurrent()
        assertEquals("the same keep-alive, not a second start", 1, app.keepAliveStarts)
        assertEquals("an answer is still being written", KeepAliveNotice.WRITING, app.answers.notice.value)

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(MessageStatus.COMPLETE, session.openSession.messages.last().status)
        assertEquals(1, app.readyShown)
        assertTrue(app.lock.state.value is LockState.Unlocked)
        assertTrue("kept until the lock has fired", app.answers.keepAliveWanted.value)
        assertTrue(app.answers.keepAliveRunning)
        assertEquals(KeepAliveNotice.LOCK_PENDING, app.answers.notice.value)
        assertEquals(1, app.serviceCreations)

        advanceTimeBy(55_000)
        runCurrent()
        assertTrue(app.lock.state.value is LockState.Locked)
        assertFalse("nothing was left to finish", app.log.contains("finish only"))
        assertThrows(KeyDestroyedException::class.java) { app.cipher.seal("probe", byteArrayOf(1)) }
        assertFalse(app.answers.keepAliveWanted.value)
        assertEquals(listOf("keep-alive let go, the key destroyed"), app.log.filter { it.startsWith("keep-alive") })
        assertEquals(1, app.processEnds)
    }

    /**
     * The lock fires while an answer is being written and the keep-alive (started in front)
     * runs: a lease, as always, and the notification says an answer is being written. With the
     * app's background use restricted, or the service taken out of the foreground without a
     * word, the same lock starts no lease: the grounds are the existing ones, asked at that moment.
     */
    @Test
    fun theLockFiringDuringAnAnswerStartsALeaseOnlyOnTheExistingGrounds() = runTest {
        for (case in listOf("running", "restricted", "demoted")) {
            val app = LockedApp(temp.newFolder(case), testScheduler)
            val session = unlockedInFront(app)
            app.fake.enqueue(listOf(Step.Emit(GenerationEvent.TextDelta("Still wri")), Step.Hang))
            session.chat.send(session.openSession.id, "Tell me everything")
            runCurrent()
            app.leave()
            runCurrent()
            when (case) {
                "restricted" -> app.backgroundRestricted = true
                "demoted" -> app.serviceInForeground = false
            }
            advanceTimeBy(60_000)
            runCurrent()
            assertTrue(case, app.lock.state.value is LockState.Locked)
            if (case == "running") {
                assertTrue("a lease", app.log.contains("finish only"))
                assertEquals(1, app.fake.activeGenerations)
                assertTrue(app.lock.sessionEnding.value)
                assertTrue(app.answers.keepAliveWanted.value)
                assertEquals(KeepAliveNotice.WRITING, app.answers.notice.value)
                assertEquals(0, app.processEnds)
                app.lock.lock()
                runCurrent()
            } else {
                assertFalse("$case: no lease", app.log.contains("finish only"))
                assertEquals(case, 1, app.fake.cancelledGenerations)
                assertThrows(KeyDestroyedException::class.java) { app.cipher.seal("probe", byteArrayOf(1)) }
                assertEquals(case, MessageStatus.STOPPED, session.openSession.messages.last().status)
            }
            assertEquals(case, 1, app.processEnds)
            assertEquals(case, 1, app.keepAliveStarts)
        }
    }

    /**
     * Android refuses the keep-alive at the moment of leaving (`startForeground` throws, or
     * returns without effect). Nothing else changes: the app stays unlocked with its lock
     * pending, nothing is asked for again out of sight, and the lock is taken as today, when the
     * timer can run or when the app comes back; then the process ends once.
     */
    @Test
    fun aRefusedKeepAliveForThePendingLockChangesNothingElse() = runTest {
        for (case in listOf("thrown", "without effect")) {
            val app = LockedApp(temp.newFolder(case), testScheduler)
            unlockedInFront(app)
            if (case == "thrown") app.foregroundAllowed = false else app.foregroundTakesEffect = false
            app.leave()
            runCurrent()
            assertEquals(case, 1, app.keepAliveStarts)
            assertFalse(case, app.answers.keepAliveRunning)
            assertFalse(case, app.answers.keepAliveWanted.value)
            assertTrue("$case: still unlocked, the lock still pending", app.lock.lockPending.value)
            assertTrue(case, app.lock.state.value is LockState.Unlocked)

            advanceTimeBy(30_000)
            runCurrent()
            assertEquals("$case: not asked for again out of sight", 1, app.keepAliveStarts)

            // Where the process may run, the timer locks as it did before; where it was frozen, the
            // lock is made up for at the return (AppLockTest). Either way the process ends once.
            advanceTimeBy(30_000)
            runCurrent()
            assertTrue(case, app.lock.state.value is LockState.Locked)
            assertFalse(case, app.lock.lockPending.value)
            assertThrows(KeyDestroyedException::class.java) { app.cipher.seal("probe", byteArrayOf(1)) }
            assertEquals(case, 1, app.processEnds)
            assertEquals(case, 1, app.keepAliveStarts)
            app.comeToFront()
            runCurrent()
            assertTrue(case, app.lock.state.value is LockState.Locked)
            assertEquals(case, 1, app.processEnds)
        }
    }

    /**
     * The keep-alive is lost while the lock is pending (Android stops the service from
     * outside). Nothing else changes and nothing starts it again out of sight; when the lock
     * fires during an answer it starts no lease, because nothing keeps the process alive.
     */
    @Test
    fun aKeepAliveLostWhileTheLockIsPendingStartsNoLeaseAndChangesNothingElse() = runTest {
        val app = LockedApp(temp.root, testScheduler)
        val session = unlockedInFront(app)
        app.fake.enqueue(listOf(Step.Emit(GenerationEvent.TextDelta("Still wri")), Step.Hang))
        session.chat.send(session.openSession.id, "Tell me everything")
        runCurrent()
        app.leave()
        runCurrent()
        assertTrue(app.answers.keepAliveRunning)

        app.destroyServiceFromOutside()
        runCurrent()
        assertFalse(app.answers.keepAliveWanted.value)
        assertTrue("still unlocked, the lock still pending", app.lock.lockPending.value)
        assertTrue(app.lock.state.value is LockState.Unlocked)
        assertEquals(1, app.fake.activeGenerations)

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("never from the background", 1, app.keepAliveStarts)
        assertTrue(app.lock.state.value is LockState.Locked)
        assertFalse("no lease", app.log.contains("finish only"))
        assertEquals(1, app.fake.cancelledGenerations)
        assertThrows(KeyDestroyedException::class.java) { app.cipher.seal("probe", byteArrayOf(1)) }
        assertEquals(1, app.processEnds)
    }

    // --- A disk that hangs ---------------------------------------------------------------------

    /**
     * The lock and the real session on real threads and real time, over a disk on which one
     * write of the settings can be made to hang, as an fsync on failing storage hangs: the
     * bytes are sealed, and the thread does not come back. Virtual time cannot show this, since
     * a blocked thread is exactly what a test dispatcher does not have.
     *
     * Every wait in here is bounded, the hanging write included, so that a failure ends the
     * test and leaves no thread behind; and the session's own bound is cut from ten seconds to
     * [WRITE_BOUND_MILLIS].
     */
    private class HangingDiskApp(root: File) : AutoCloseable {
        private val mainExecutor = Executors.newSingleThreadExecutor()
        private val ioExecutor = Executors.newFixedThreadPool(4)
        private val main = mainExecutor.asCoroutineDispatcher()
        private val io = ioExecutor.asCoroutineDispatcher()
        val files = AppFiles(File(root, "files").apply { mkdirs() }, File(root, "cache").apply { mkdirs() })
        private val layout = DataLayout(files)
        private val lockClock = LockClock()

        /** Makes the next write of the settings hang until [release] is counted down. */
        val hangNextWrite = AtomicBoolean(false)
        val writeEntered = CountDownLatch(1)
        val release = CountDownLatch(1)

        /** True once the write that hung has come back from the disk. */
        val writeReturned = AtomicBoolean(false)

        /** The cipher the lock made for each unlock, oldest first. */
        val ciphers = CopyOnWriteArrayList<BlobCipher>()

        val lock: AppLock<AppSession> = AppLock(
            manager = LockManager(
                LockConfigStore(files.lockConfig),
                SoftwareKeyVault(),
                lockClock,
                iterations = 1_000,
                minIterations = 1,
                encryptedDataExists = files::encryptedDataExists,
            ),
            files = files,
            clock = lockClock,
            openSession = { unlocked, _ ->
                ciphers += unlocked
                // The cipher itself is untouched. The write "hangs" after its bytes were sealed,
                // which is where the real code writes, syncs and renames.
                val slowDisk = object : DataCipher by unlocked {
                    override fun seal(purpose: String, plaintext: ByteArray): ByteArray {
                        val sealed = unlocked.seal(purpose, plaintext)
                        if (purpose == DataLayout.SETTINGS_PURPOSE && hangNextWrite.compareAndSet(true, false)) {
                            writeEntered.countDown()
                            release.await(HANG_LIMIT_SECONDS, TimeUnit.SECONDS)
                            writeReturned.set(true)
                        }
                        return sealed
                    }
                }
                AppSession(slowDisk, layout, FakeInferenceEngine(), ModelCatalog.bundled, main, io, FakeClock(), SequentialIds("u${ciphers.size}-"), WRITE_BOUND_MILLIS)
            },
            biometricStatus = { BiometricStatus.NONE_ENROLLED },
            screenshots = ScreenshotAllowance(lockClock, FakeScreenOffWatch(), main),
            mainDispatcher = main,
            ioDispatcher = io,
        )

        /** Runs [block] on the thread that stands for the main thread, and waits for it. */
        fun onMain(block: () -> Unit) {
            mainExecutor.submit(Runnable { block() }).get(WAIT_SECONDS, TimeUnit.SECONDS)
        }

        /** Polls until [test] holds; fails when it does not within [WAIT_SECONDS]. */
        fun await(what: String, test: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
            while (System.nanoTime() < deadline) {
                if (test()) return
                Thread.sleep(10)
            }
            throw AssertionError("Never happened within $WAIT_SECONDS s: $what (the lock is ${lock.state.value})")
        }

        fun keyAlive(cipher: BlobCipher): Boolean = try {
            cipher.seal("probe", byteArrayOf(1))
            true
        } catch (_: KeyDestroyedException) {
            false
        }

        fun everyFile(): List<String> =
            files.filesDir.walkTopDown().filter { it.isFile }.map { it.relativeTo(files.filesDir).path }.sorted().toList()

        /** Set-up with [passphrase], then one write of the settings that hangs. Returns the session. */
        fun setUpAndHangAWrite(passphrase: String): AppSession {
            await("ready for set-up") { lock.state.value == LockState.SetupRequired }
            onMain { lock.setUp(passphrase.toCharArray(), passphrase.toCharArray()) }
            await("unlocked") { lock.state.value is LockState.Unlocked }
            val session = (lock.state.value as LockState.Unlocked).session
            hangNextWrite.set(true)
            session.settings.update(urgent = true) { it.withSystemPrompt(ModelCatalog.default.id, "a system prompt") }
            assertTrue("the write started", writeEntered.await(WAIT_SECONDS, TimeUnit.SECONDS))
            return session
        }

        override fun close() {
            release.countDown()
            mainExecutor.shutdownNow()
            ioExecutor.shutdownNow()
            mainExecutor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)
            ioExecutor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)
        }

        companion object {
            const val WRITE_BOUND_MILLIS = 300L
            const val WAIT_SECONDS = 20L
            const val HANG_LIMIT_SECONDS = 45L
        }
    }

    /**
     * The independent review's finding: with a write stuck in the disk, the app showed its lock
     * screen but the data key stayed in memory, the lock manager stayed held, and the right
     * passphrase could not even be checked, for as long as the disk did not answer. Now the
     * session's close gives up when its time is over, the key goes, and the owner can come back.
     */
    @Test(timeout = 90_000)
    fun aWriteThatHangsDoesNotKeepTheKeyAliveForEver() {
        HangingDiskApp(temp.root).use { app ->
            app.setUpAndHangAWrite(passphrase)
            val firstCipher = app.ciphers.single()

            app.onMain { app.lock.lock() }
            assertTrue("the screen is locked at once", app.lock.state.value is LockState.Locked)

            app.await("the data key destroyed although the write has not returned") { !app.keyAlive(firstCipher) }
            app.await("the lock manager free again") { !app.lock.managerInUse }
            assertFalse("the write is still stuck", app.writeReturned.get())

            // The owner comes back and types the right passphrase while that write still hangs.
            app.onMain { app.lock.unlockWithPassphrase(passphrase.toCharArray()) }
            app.await("unlocked again") { app.lock.state.value is LockState.Unlocked }
            assertFalse(app.writeReturned.get())
            assertEquals(2, app.ciphers.size)
            assertTrue("the new unlock has a working key", app.keyAlive(app.ciphers[1]))
            assertFalse("and the old cipher stays dead", app.keyAlive(firstCipher))

            // Letting the write go changes nothing about that.
            app.release.countDown()
            app.await("the write returned") { app.writeReturned.get() }
            assertFalse(app.keyAlive(firstCipher))
            assertTrue(app.lock.state.value is LockState.Unlocked)

            // And a lock without a hanging write is as it always was.
            app.onMain { app.lock.lock() }
            app.await("the second key destroyed") { !app.keyAlive(app.ciphers[1]) }
            app.await("the lock manager free again") { !app.lock.managerInUse }
        }
    }

    /**
     * A reset is the one thing that does wait for such a write, however long it hangs: it
     * deletes every file, and a write that came back afterwards would put one back, encrypted
     * with a key that no longer exists, where the next set-up would find "data" it cannot read.
     */
    @Test(timeout = 90_000)
    fun aResetWaitsForAWriteThatHangsSoThatNoFileComesBack() {
        HangingDiskApp(temp.root).use { app ->
            app.setUpAndHangAWrite(passphrase)
            val firstCipher = app.ciphers.single()

            app.onMain { app.lock.reset() }
            assertEquals("the screens are gone at once", LockState.Starting, app.lock.state.value)
            app.await("the data key destroyed although the write has not returned") { !app.keyAlive(firstCipher) }

            // Well past the session's own bound nothing has been deleted: the write could still land.
            Thread.sleep(HangingDiskApp.WRITE_BOUND_MILLIS * 4)
            assertEquals(LockState.Starting, app.lock.state.value)
            assertTrue(app.files.lockConfig.exists())
            assertFalse(app.writeReturned.get())

            app.release.countDown()
            app.await("first-run set-up after the reset") { app.lock.state.value == LockState.SetupRequired }
            assertTrue("the write had returned before anything was deleted", app.writeReturned.get())
            assertEquals("every file is gone, the one that landed late included", emptyList<String>(), app.everyFile())
            assertNull(app.lock.resetFailure.value)
            Thread.sleep(HangingDiskApp.WRITE_BOUND_MILLIS)
            assertEquals("and none comes back", emptyList<String>(), app.everyFile())
        }
    }
}
