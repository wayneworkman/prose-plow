package us.theworkmans.proseplow

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import us.theworkmans.proseplow.background.AnswerEvents
import us.theworkmans.proseplow.chat.OpenChat
import us.theworkmans.proseplow.core.chat.ChatSession
import us.theworkmans.proseplow.core.chat.FakeClock
import us.theworkmans.proseplow.core.chat.SequentialIds
import us.theworkmans.proseplow.core.chat.SessionCodec
import us.theworkmans.proseplow.core.engine.DeviceContextLimits
import us.theworkmans.proseplow.core.engine.FakeInferenceEngine
import us.theworkmans.proseplow.core.engine.InferenceEngine
import us.theworkmans.proseplow.core.model.ModelCatalog
import us.theworkmans.proseplow.core.model.ModelSpec
import us.theworkmans.proseplow.data.BuildIdentity
import us.theworkmans.proseplow.data.FreeSpace
import us.theworkmans.proseplow.data.ModelMemoryPlatform
import us.theworkmans.proseplow.security.AppFiles
import us.theworkmans.proseplow.security.DataCipher
import us.theworkmans.proseplow.security.DataCrypto
import us.theworkmans.proseplow.security.SessionClose
import us.theworkmans.proseplow.security.TestKeys
import java.io.File
import java.nio.ByteBuffer

/**
 * What a test needs to open the real [AppSession] again and again over one directory, as the
 * lock does at every unlock: the files, one data key, a scripted engine, and virtual time. One
 * test dispatcher stands for both the main thread and the I/O threads.
 */
class SessionFixture(root: File, scheduler: TestCoroutineScheduler) {
    val dispatcher = StandardTestDispatcher(scheduler)
    val files = AppFiles(File(root, "files").apply { mkdirs() }, File(root, "cache").apply { mkdirs() })
    val layout = DataLayout(files)
    val cipher: DataCrypto = TestKeys.cipher()
    val engine = FakeInferenceEngine()
    val clock = FakeClock()

    /** The models the next session offers. */
    var models: List<ModelSpec> = ModelCatalog.bundled

    /** What the device of the next session allows a model's context; a device with plenty of memory unless a test says otherwise. */
    var contextLimits = DeviceContextLimits()

    /**
     * What the next session's store of the model's memory knows of the platform; null stands for
     * a version of Android on which no memory is kept. A platform that keeps one unless a test
     * says otherwise: every model of the catalogue 1 GB long, plenty of space.
     */
    var memoryPlatform: ModelMemoryPlatform? = testMemoryPlatform()

    private var unlocks = 0

    /**
     * A session as the lock opens one. Ids are `u<unlock>-<n>`, so a test can name a chat or a
     * message and ids of different unlocks never collide.
     */
    fun open(cipher: DataCipher = this.cipher, engine: InferenceEngine = this.engine, answerEvents: AnswerEvents = AnswerEvents.None): AppSession =
        AppSession(
            cipher, layout, engine, models, dispatcher, dispatcher, clock, SequentialIds("u${++unlocks}-"),
            answerEvents = answerEvents, contextLimits = contextLimits, memoryPlatform = memoryPlatform,
        )

    /** The session [id] as it is on disk right now, or null when it has no file. */
    fun stored(id: String): ChatSession? {
        val file = File(layout.sessions, "$id.enc")
        if (!file.exists()) return null
        val text = cipher.open(DataLayout.sessionPurpose(id), file.readBytes()).toString(Charsets.UTF_8)
        return (SessionCodec.decode(text) as SessionCodec.Decoded.Readable).session
    }

    /** Puts [session] on disk as a session before would have stored it, without listing it. */
    fun plant(session: ChatSession, transform: (String) -> String = { it }) {
        layout.sessions.mkdirs()
        val text = transform(SessionCodec.encode(session))
        File(layout.sessions, "${session.id}.enc").writeBytes(cipher.seal(DataLayout.sessionPurpose(session.id), text.toByteArray()))
    }

    /** Every file under the app's files directory, as paths relative to it. */
    fun fileNames(): List<String> =
        files.filesDir.walkTopDown().filter { it.isFile }.map { it.relativeTo(files.filesDir).path }.sorted().toList()

    /** True if [text] can be found in any stored file as it is. */
    fun diskContains(text: String): Boolean =
        files.filesDir.walkTopDown().filter { it.isFile }.any { String(it.readBytes(), Charsets.ISO_8859_1).contains(text) }
}

/** The chat on screen, which the test expects to be a readable one. */
val AppSession.openSession: ChatSession get() = (chat.state.value.open as OpenChat.Ready).session

/**
 * Closes the session as "Lock now" and a reset do ([SessionClose.IMMEDIATE]), for the tests that
 * are not about which kind of close it is.
 */
suspend fun AppSession.close() = close(SessionClose.IMMEDIATE)

/**
 * A platform on which the model's memory is kept, for tests: one build, every model of the
 * catalogue 1 GB long, plenty of free space. [beforeAppend] stands for the disk of a save.
 */
fun testMemoryPlatform(beforeAppend: (ByteBuffer) -> Unit = {}): ModelMemoryPlatform = ModelMemoryPlatform(
    build = BuildIdentity(versionCode = 1, lastUpdateMillis = 1_000),
    modelFileLength = { id -> if (ModelCatalog.all.any { it.id == id }) 1L shl 30 else null },
    freeSpace = { FreeSpace(usableBytes = 1L shl 40, totalBytes = 1L shl 40) },
    beforeAppend = beforeAppend,
)
