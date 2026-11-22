package us.theworkmans.proseplow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import us.theworkmans.proseplow.core.chat.FakeClock
import us.theworkmans.proseplow.core.chat.MessageStatus
import us.theworkmans.proseplow.core.chat.SequentialIds
import us.theworkmans.proseplow.core.engine.EngineState
import us.theworkmans.proseplow.core.engine.FakeInferenceEngine
import us.theworkmans.proseplow.core.engine.FakeInferenceEngine.Step
import us.theworkmans.proseplow.core.engine.GenerationEvent
import us.theworkmans.proseplow.core.engine.GenerationRequest
import us.theworkmans.proseplow.core.engine.InferenceEngine
import us.theworkmans.proseplow.core.model.ModelCatalog
import us.theworkmans.proseplow.core.model.ModelSpec
import us.theworkmans.proseplow.security.AppFiles
import us.theworkmans.proseplow.security.AppLock
import us.theworkmans.proseplow.security.BiometricStatus
import us.theworkmans.proseplow.security.BlobCipher
import us.theworkmans.proseplow.security.KeyDestroyedException
import us.theworkmans.proseplow.security.LockConfigStore
import us.theworkmans.proseplow.security.LockManager
import us.theworkmans.proseplow.security.LockState
import us.theworkmans.proseplow.security.SoftwareKeyVault
import us.theworkmans.proseplow.security.UnlockedSession
import java.io.File
import java.io.IOException
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

        override suspend fun close() {
            real.close()
            val usable = try {
                cipher.seal("probe", byteArrayOf(1))
                true
            } catch (_: KeyDestroyedException) {
                false
            }
            log += if (usable) "closed, the key still works" else "closed, the key is gone"
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

        /** Called when the engine is told to forget, before it does. */
        var onForget: () -> Unit = {}

        private val engine = object : InferenceEngine by fake {
            override suspend fun forget() {
                onForget()
                log += "forget, with ${fake.activeGenerations} generations running"
                fake.forget()
            }
        }

        /** The cipher the lock made for the latest unlock. */
        lateinit var cipher: BlobCipher
        private var unlocks = 0
        private val lockClock = LockClock()

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
            openSession = { unlocked ->
                cipher = unlocked
                val recording = object : BlobCipher {
                    override fun seal(purpose: String, plaintext: ByteArray): ByteArray {
                        val sealed = unlocked.seal(purpose, plaintext)
                        log += "sealed $purpose"
                        return sealed
                    }

                    override fun open(purpose: String, blob: ByteArray): ByteArray = unlocked.open(purpose, blob)
                }
                val session = AppSession(recording, layout, engine, ModelCatalog.bundled, dispatcher, dispatcher, FakeClock(), SequentialIds("u${++unlocks}-"))
                WatchedSession(session, unlocked, log)
            },
            biometricStatus = { BiometricStatus.NONE_ENROLLED },
            mainDispatcher = dispatcher,
            ioDispatcher = dispatcher,
        )

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
        assertEquals(1, app.fake.forgetCount)
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
        assertEquals(1, app.fake.forgetCount)
        assertEquals(0, app.fake.unloadCount)
        assertEquals(EngineState.Ready(ModelCatalog.default.id), app.fake.state.value)

        unlock(app)
        assertEquals("the unlock found the model ready and loaded nothing", listOf(ModelCatalog.default.id), app.fake.loads)
    }
}
