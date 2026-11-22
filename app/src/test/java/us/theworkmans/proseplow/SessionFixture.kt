package us.theworkmans.proseplow

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import us.theworkmans.proseplow.chat.OpenChat
import us.theworkmans.proseplow.core.chat.ChatSession
import us.theworkmans.proseplow.core.chat.FakeClock
import us.theworkmans.proseplow.core.chat.SequentialIds
import us.theworkmans.proseplow.core.chat.SessionCodec
import us.theworkmans.proseplow.core.engine.FakeInferenceEngine
import us.theworkmans.proseplow.core.engine.InferenceEngine
import us.theworkmans.proseplow.core.model.ModelCatalog
import us.theworkmans.proseplow.core.model.ModelSpec
import us.theworkmans.proseplow.security.AppFiles
import us.theworkmans.proseplow.security.BlobCipher
import us.theworkmans.proseplow.security.DataCrypto
import us.theworkmans.proseplow.security.TestKeys
import java.io.File

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

    private var unlocks = 0

    /**
     * A session as the lock opens one. Ids are `u<unlock>-<n>`, so a test can name a chat or a
     * message and ids of different unlocks never collide.
     */
    fun open(cipher: BlobCipher = this.cipher, engine: InferenceEngine = this.engine): AppSession =
        AppSession(cipher, layout, engine, models, dispatcher, dispatcher, clock, SequentialIds("u${++unlocks}-"))

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
