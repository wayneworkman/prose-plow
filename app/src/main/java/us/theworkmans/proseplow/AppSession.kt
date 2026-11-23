package us.theworkmans.proseplow

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import us.theworkmans.proseplow.background.AnswerEvents
import us.theworkmans.proseplow.chat.ChatController
import us.theworkmans.proseplow.chat.ModelRequestSettings
import us.theworkmans.proseplow.core.chat.Clock
import us.theworkmans.proseplow.core.chat.IdSource
import us.theworkmans.proseplow.core.chat.MessageLifetime
import us.theworkmans.proseplow.core.chat.RandomIds
import us.theworkmans.proseplow.core.chat.SystemClock
import us.theworkmans.proseplow.core.engine.DeviceContextLimits
import us.theworkmans.proseplow.core.engine.InferenceEngine
import us.theworkmans.proseplow.core.model.ModelSpec
import us.theworkmans.proseplow.data.CoalescingWriter
import us.theworkmans.proseplow.data.MemoryStore
import us.theworkmans.proseplow.data.ModelMemoryFiles
import us.theworkmans.proseplow.data.SealedDirectory
import us.theworkmans.proseplow.data.SealedFile
import us.theworkmans.proseplow.data.SessionStore
import us.theworkmans.proseplow.data.SettingsStore
import us.theworkmans.proseplow.lock.MessageLifetimeSetting
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

    /**
     * The model's saved memory: at most one `<chat id>.enc`, a sealed stream for the purpose
     * [MemoryStore.purposeOf] (see [ModelMemoryFiles]). Under the files directory and outside
     * `security/`, with the encrypted suffix, so the lock counts it as data and a reset deletes it.
     */
    val modelMemory = File(files.filesDir, "model-memory")

    companion object {
        val SETTINGS_PURPOSE = Purposes.of("settings")
        val SESSION_INDEX_PURPOSE = Purposes.of("session-index")

        fun sessionPurpose(id: String) = Purposes.of("session", id)
    }
}

/**
 * Everything that holds or reads decrypted data, created when the app is unlocked and ended when
 * it locks (or, when the app locked by itself in the middle of an answer and the user allows
 * it, as soon as that answer is finished behind the lock, and no later than the lock's ten
 * minutes): the encrypted stores and their writer, the chats with the generation that may be
 * running in them ([chat]), and the ViewModels of the screens ([viewModelStore], which outlives
 * a rotation because it lives here and not in the activity).
 *
 * The [engine] is not made here. It belongs to the process, because its model takes seconds to
 * load and holds nothing private once it has forgotten the conversation; the session is its only
 * user while the app is unlocked, and makes it forget when the session ends.
 *
 * Constructing a session reads the settings, the list of chats and the chat that was on screen
 * last, so it is done off the main thread, where the lock opens it. Before any chat is shown,
 * what has expired under the user's limit is removed from the stored chats
 * ([SessionStore.expireAll]), and the entries of chats the locked app deleted are dropped.
 *
 * @param cipher the cipher of this unlock. It works until [close] has returned.
 * @param models the models this build offers.
 * @param mainDispatcher where [chat] keeps its state; the screens' thread.
 * @param ioDispatcher where files are read and written.
 * @param writeTimeoutMillis how long [close] waits for the writes that are queued.
 * @param contextLimits how large the context of a model may become on this device under the
 *   user's choice of size; the longest reply is held to what follows from it, and the settings
 *   show the sizes and what they cost by it. A session that is given none assumes a device
 *   with plenty of memory.
 * @param clock the time messages are dated and aged by: the trusted time of the lock, so that a
 *   date set back on the phone neither stamps nor keeps a message.
 * @param expiryIntervalMillis how often expired messages are looked for while the session lives:
 *   [ChatController.EXPIRY_INTERVAL_MILLIS] in the app, none unless a unit test asks for it.
 * @param lockedMessageLifetimeDays the readable copy of how long messages are kept, as the lock
 *   holds it when it opens the session. It is the limit whenever the settings do not say one
 *   that could be read ([SettingsStore.open]), so that a settings file that cannot be read never
 *   makes the default delete what the user chose to keep.
 * @param answerEvents told, on the main dispatcher, when an answer begins and ends being
 *   written and when one is finished: what keeps the process alive out of sight goes by it.
 * @param memoryFiles the files of the model's saved memory: one object for the whole process
 *   (see [ModelMemoryFiles]). At the opening, before anything else, what has expired of it goes
 *   by the rules that need no key; and every pruning, edit and deletion of a chat's stored
 *   messages deletes the chat's memory before the chat is written.
 * @throws IOException from the constructor when what is stored cannot be looked at. The lock
 *   then shows that the data could not be opened, with Try again, instead of an empty app.
 */
class AppSession(
    cipher: BlobCipher,
    layout: DataLayout,
    private val engine: InferenceEngine,
    val models: List<ModelSpec>,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    clock: Clock = SystemClock,
    ids: IdSource = RandomIds,
    private val writeTimeoutMillis: Long = WRITE_TIMEOUT_MILLIS,
    private val answerEvents: AnswerEvents = AnswerEvents.None,
    val contextLimits: DeviceContextLimits = DeviceContextLimits(),
    expiryIntervalMillis: Long? = null,
    lockedMessageLifetimeDays: Int? = null,
    val memoryFiles: ModelMemoryFiles = ModelMemoryFiles(layout.modelMemory, layout.sessions),
) : UnlockedSession, MessageLifetimeSetting {

    private val _writeFailure = MutableStateFlow<String?>(null)

    /**
     * What went wrong in the last write that failed (an exception's name), until the screen has
     * shown it ([writeFailureShown]). A write that throws (a full disk) must not crash the app;
     * the other writes go on, and the next change of the same file is a new attempt.
     */
    val writeFailure: StateFlow<String?> = _writeFailure.asStateFlow()

    private val writerScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /** Where [answerEvents] is told what the chats are doing. Ended in [close]. */
    private val watchScope = CoroutineScope(SupervisorJob() + mainDispatcher)

    /** Every write to the session's files goes through here. [close] gives it time to finish before the data key goes. */
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
                knownMessageLifetime = lockedMessageLifetimeDays?.let(MessageLifetime::fromStoredDays),
            )
            val sessionStore = SessionStore(
                sessions = SealedDirectory(layout.sessions, DataLayout::sessionPurpose, cipher, layout.kept, "session"),
                indexFile = SealedFile(layout.sessionIndex, DataLayout.SESSION_INDEX_PURPOSE, cipher, layout.kept, "session-index"),
                memory = memoryFiles,
            )
            settings = settingsStore
            // Nothing else uses the files yet: what has expired goes before anything is shown or
            // restored, the model's memory first.
            val now = clock.nowMillis()
            memoryFiles.sweep(settingsStore.settings.value.messageLifetime, now)
            val opened = sessionStore.expireAll(sessionStore.open(), settingsStore.settings.value.messageLifetime, now)
            chat = ChatController(
                store = sessionStore,
                writer = writer,
                engine = engine,
                models = models,
                opened = opened,
                requestSettings = { model ->
                    val current = settingsStore.settings.value
                    val chosen = contextLimits.chosen(model, current.contextTokens(model.id))
                    ModelRequestSettings(
                        current.systemPrompt(model.id).ifEmpty { null },
                        current.sampling(model),
                        current.maxNewTokens(model, contextLimits.limit(model, chosen)),
                        chosen,
                    )
                },
                clock = clock,
                ids = ids,
                mainDispatcher = mainDispatcher,
                ioDispatcher = ioDispatcher,
                onReplyFinished = answerEvents::onReplyFinished,
                lifetime = { settingsStore.settings.value.messageLifetime },
                expiryIntervalMillis = expiryIntervalMillis,
            )
        } catch (e: Exception) {
            // The session was never opened: its writer must not stay behind.
            writerScope.cancel()
            watchScope.cancel()
            throw e
        }
        val chats = chat
        watchScope.launch {
            chats.state.map { it.turn != null }.distinctUntilChanged().collect { writing -> answerEvents.onWriting(writing) }
        }
    }

    val viewModelStore = ViewModelStore()

    /** How long messages are kept, by the encrypted setting. */
    val messageLifetime: MessageLifetime get() = settings.settings.value.messageLifetime

    /** [messageLifetime] as the lock counts it, in days; the lock copies it at every unlock. */
    override val messageLifetimeDays: Int get() = messageLifetime.storedDays

    override fun changeMessageLifetimeDays(days: Int) {
        MessageLifetime.fromStoredDays(days)?.let(::changeMessageLifetime)
    }

    /**
     * Makes [lifetime] how long messages are kept, and removes at once what has expired under
     * it. Main thread. The screen asks for confirmation before a limit that deletes messages now
     * (a shorter one, or any after Never); the readable copy of the lock is the caller's to write.
     */
    fun changeMessageLifetime(lifetime: MessageLifetime) {
        settings.update(urgent = true) { it.copy(messageLifetime = lifetime) }
        chat.expireMessages()
    }

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

    /** An answer is being written, in whichever chat. */
    override fun hasUnfinishedWork(): Boolean = chat.state.value.turn != null

    override suspend fun awaitWorkFinished() {
        chat.state.first { it.turn == null }
    }

    /** Behind the lock: the answer that is being written is finished and saved; nothing else begins. */
    override fun finishOnly() = chat.finishOnly()

    override fun resume() = chat.resume()

    /**
     * Ends the session, in this order:
     * 1. The reply in flight is stopped. What was written of it is kept, marked as stopped, and
     *    handed to the writer with everything else that changed.
     * 2. The engine forgets the conversation; the model stays loaded.
     * 3. The writer accepts nothing more and gets [writeTimeoutMillis] to finish what it was
     *    given.
     *
     * Then this returns, and the lock destroys the data key. Every step is bounded, so that a
     * native call or a write that hangs cannot keep the key alive for ever; and each step is
     * taken whatever became of the one before.
     *
     * A write that is stuck in the disk when the time is up is not waited for: it cannot be
     * interrupted, and the key must go. Its bytes were sealed before, so it may still reach the
     * disk when the disk lets it; everything queued behind it fails, because sealing needs the
     * key. [awaitStopped] waits for that last write, for the reset. Until it has ended, another
     * unlock can already be writing the same file, and the late write may then replace the
     * newer content with what this session had; the stores cannot tell the two apart.
     */
    override suspend fun close() {
        try {
            chat.shutdown()
        } finally {
            // No answer is being written any more, said here in case the watcher was not yet told.
            watchScope.cancel()
            withContext(NonCancellable + mainDispatcher) { answerEvents.onWriting(false) }
            try {
                forgetConversation()
            } finally {
                withTimeoutOrNull(writeTimeoutMillis) { writer.closeAndJoin() }
                // Not joined: a blocking write cannot be cancelled, and waiting for it here is
                // what would keep the key alive.
                writerScope.cancel()
            }
        }
    }

    /**
     * Returns when the writer has really ended: when the write that [close] may have left
     * behind has returned, however long that takes. Nothing of this session touches a file after
     * that.
     */
    override suspend fun awaitStopped() {
        writerScope.coroutineContext.job.join()
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
