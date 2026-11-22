package us.theworkmans.proseplow

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import us.theworkmans.proseplow.chat.ChatController
import us.theworkmans.proseplow.chat.ModelRequestSettings
import us.theworkmans.proseplow.core.chat.Clock
import us.theworkmans.proseplow.core.chat.IdSource
import us.theworkmans.proseplow.core.chat.RandomIds
import us.theworkmans.proseplow.core.chat.SystemClock
import us.theworkmans.proseplow.core.engine.InferenceEngine
import us.theworkmans.proseplow.core.model.ModelSpec
import us.theworkmans.proseplow.data.CoalescingWriter
import us.theworkmans.proseplow.data.SealedDirectory
import us.theworkmans.proseplow.data.SealedFile
import us.theworkmans.proseplow.data.SessionStore
import us.theworkmans.proseplow.data.SettingsStore
import us.theworkmans.proseplow.security.AppFiles
import us.theworkmans.proseplow.security.BlobCipher
import us.theworkmans.proseplow.security.Purposes
import us.theworkmans.proseplow.security.UnlockedSession
import java.io.File
import java.io.IOException

/**
 * Where the stores of an unlocked session keep their files, and the purposes they seal them
 * with. Every encrypted file's name ends in `.enc` (see [AppFiles]): that is how the lock knows
 * that data exists, and never makes a new key over it.
 */
class DataLayout(files: AppFiles) {
    /** The settings, one blob sealed with [SETTINGS_PURPOSE]. */
    val settings = File(files.filesDir, "settings.enc")

    /** The chat sessions, one `<id>.enc` file each, sealed with [sessionPurpose]. */
    val sessions = File(files.filesDir, "sessions")

    /** The list of chat sessions, one blob sealed with [SESSION_INDEX_PURPOSE]. */
    val sessionIndex = File(files.filesDir, "session-index.enc")

    /**
     * Exact copies of files that could not be read completely, made before anything was written
     * over them. Each is still sealed for the purpose of the file it is a copy of.
     */
    val kept = File(files.filesDir, "kept")

    companion object {
        val SETTINGS_PURPOSE = Purposes.of("settings")
        val SESSION_INDEX_PURPOSE = Purposes.of("session-index")

        fun sessionPurpose(id: String) = Purposes.of("session", id)
    }
}

/**
 * Everything that holds or reads decrypted data, created when the app is unlocked and ended when
 * it locks: the encrypted stores and their writer, the chats with the generation that may be
 * running in them ([chat]), and the ViewModels of the screens ([viewModelStore], which outlives
 * a rotation because it lives here and not in the activity).
 *
 * The [engine] is not made here. It belongs to the process, because its model takes seconds to
 * load and holds nothing private once it has forgotten the conversation; the session is its only
 * user while the app is unlocked, and makes it forget when the session ends.
 *
 * Constructing a session reads the settings, the list of chats and the chat that was on screen
 * last, so it is done off the main thread, where the lock opens it.
 *
 * @param cipher the cipher of this unlock. It works until [close] has returned.
 * @param models the models this build offers.
 * @param mainDispatcher where [chat] keeps its state; the screens' thread.
 * @param ioDispatcher where files are read and written.
 * @throws IOException from the constructor when what is stored cannot be looked at. The lock
 *   then shows that the data could not be opened, with Try again, instead of an empty app.
 */
class AppSession(
    cipher: BlobCipher,
    layout: DataLayout,
    private val engine: InferenceEngine,
    val models: List<ModelSpec>,
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    clock: Clock = SystemClock,
    ids: IdSource = RandomIds,
) : UnlockedSession {

    private val _writeFailure = MutableStateFlow<String?>(null)

    /**
     * What went wrong in the last write that failed (an exception's name), until the screen has
     * shown it ([writeFailureShown]). A write that throws (a full disk) must not crash the app;
     * the other writes go on, and the next change of the same file is a new attempt.
     */
    val writeFailure: StateFlow<String?> = _writeFailure.asStateFlow()

    private val writerScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /** Every write to the session's files goes through here. [close] waits for it before the data key goes. */
    private val writer = CoalescingWriter(writerScope, LAZY_WRITE_MILLIS) { _, failure -> _writeFailure.value = failure.javaClass.simpleName }

    /** The encrypted settings. */
    val settings: SettingsStore

    /** The chats, and the reply that may be being written in one of them. */
    val chat: ChatController

    init {
        try {
            val settingsStore = SettingsStore.open(
                SealedFile(layout.settings, DataLayout.SETTINGS_PURPOSE, cipher, layout.kept, "settings"),
                writer,
            )
            val sessionStore = SessionStore(
                sessions = SealedDirectory(layout.sessions, DataLayout::sessionPurpose, cipher, layout.kept, "session"),
                indexFile = SealedFile(layout.sessionIndex, DataLayout.SESSION_INDEX_PURPOSE, cipher, layout.kept, "session-index"),
            )
            settings = settingsStore
            chat = ChatController(
                store = sessionStore,
                writer = writer,
                engine = engine,
                models = models,
                opened = sessionStore.open(),
                requestSettings = { model ->
                    val current = settingsStore.settings.value
                    ModelRequestSettings(current.systemPrompt(model.id).ifEmpty { null }, current.sampling(model))
                },
                clock = clock,
                ids = ids,
                mainDispatcher = mainDispatcher,
                ioDispatcher = ioDispatcher,
            )
        } catch (e: Exception) {
            // The session was never opened: its writer must not stay behind.
            writerScope.cancel()
            throw e
        }
    }

    val viewModelStore = ViewModelStore()

    fun writeFailureShown() {
        _writeFailure.value = null
    }

    /**
     * Hands everything that is waiting to be written to the disk now, without waiting for it.
     * Called when the app leaves the screen: the process may not live to see the lock.
     */
    fun flushWrites() = writer.flush()

    /**
     * Clears the screens' ViewModels: when the app locks, and when the activity finishes (not for
     * a rotation). What the screens changed is already with the writer, which is told to hurry.
     * A reply that is being written goes on: it belongs to the session, and ends in [close].
     * The store can be used again afterwards.
     */
    override fun clearScreens() {
        viewModelStore.clear()
        writer.flush()
    }

    /**
     * Ends the session, in this order:
     * 1. The reply in flight is stopped. What was written of it is kept, marked as stopped, and
     *    handed to the writer with everything else that changed.
     * 2. The engine forgets the conversation; the model stays loaded.
     * 3. The writer finishes what it was given and accepts nothing more.
     *
     * Only then does this return, and the lock destroys the data key. Every step is bounded, so
     * that a native call or a write that hangs cannot keep the key alive for ever; and each step
     * is taken whatever became of the one before.
     */
    override suspend fun close() {
        try {
            chat.shutdown()
        } finally {
            try {
                forgetConversation()
            } finally {
                withTimeoutOrNull(WRITE_TIMEOUT_MILLIS) { writer.closeAndJoin() }
                writerScope.coroutineContext.job.cancelAndJoin()
            }
        }
    }

    /**
     * Makes the engine drop the conversation, and waits for that, but not for ever. The request
     * is made in a scope of its own: once made it is carried out even if this session's close is
     * given up on.
     */
    private suspend fun forgetConversation() {
        val forgetting = CoroutineScope(ioDispatcher).launch {
            try {
                engine.forget()
            } catch (e: CancellationException) {
                throw e
            } catch (_: RuntimeException) {
                // Forgetting failed; freeing the model forgets for certain.
                try {
                    engine.unload()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: RuntimeException) {
                    // Nothing more can be asked of this engine.
                }
            }
        }
        withTimeoutOrNull(ChatController.ENGINE_TIMEOUT_MILLIS) { forgetting.join() }
    }

    private companion object {
        const val WRITE_TIMEOUT_MILLIS = 10_000L

        /** How far the files may lag behind a reply that is streaming or a text that is being typed. */
        const val LAZY_WRITE_MILLIS = 1_000L
    }
}
