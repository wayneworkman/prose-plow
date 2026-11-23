package us.theworkmans.proseplow.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import us.theworkmans.proseplow.core.chat.ChatSession
import us.theworkmans.proseplow.core.chat.Clock
import us.theworkmans.proseplow.core.chat.IdSource
import us.theworkmans.proseplow.core.chat.MessageExpiry
import us.theworkmans.proseplow.core.chat.MessageLifetime
import us.theworkmans.proseplow.core.chat.MessageStatus
import us.theworkmans.proseplow.core.chat.Role
import us.theworkmans.proseplow.core.chat.SessionSummary
import us.theworkmans.proseplow.core.chat.generationRequest
import us.theworkmans.proseplow.core.engine.EngineError
import us.theworkmans.proseplow.core.engine.EngineState
import us.theworkmans.proseplow.core.engine.FinishReason
import us.theworkmans.proseplow.core.engine.GenerationEvent
import us.theworkmans.proseplow.core.engine.InferenceEngine
import us.theworkmans.proseplow.core.engine.MemoryRestoreResult
import us.theworkmans.proseplow.core.engine.MemoryRestoreState
import us.theworkmans.proseplow.core.engine.MemoryVault
import us.theworkmans.proseplow.core.model.ModelCatalog
import us.theworkmans.proseplow.core.model.ModelSpec
import us.theworkmans.proseplow.core.model.SamplingParams
import us.theworkmans.proseplow.core.prompt.PromptWhitespace
import us.theworkmans.proseplow.data.CoalescingWriter
import us.theworkmans.proseplow.data.MemoryConversation
import us.theworkmans.proseplow.data.MemoryConversations
import us.theworkmans.proseplow.data.SessionIndex
import us.theworkmans.proseplow.data.SessionStore
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** The chat that is on screen. */
sealed interface OpenChat {
    val id: String

    /**
     * A chat that can be used.
     *
     * @property incomplete set when not everything that was stored of it could be read; a copy
     *   of its file as it was has been kept. Null once the user has been told.
     */
    data class Ready(val session: ChatSession, val incomplete: SessionStore.Incomplete? = null) : OpenChat {
        override val id: String get() = session.id
    }

    /**
     * A stored chat whose file cannot be read. Nothing is done to the file; all the user can do
     * with the chat is delete it.
     */
    data class Unreadable(override val id: String, val title: String) : OpenChat
}

/** How far the reply that is being generated has got. */
sealed interface TurnPhase {

    /** Waiting for the model: it is being loaded ([ChatState.engine] says how far), or the engine is still busy. */
    data object Preparing : TurnPhase

    /** The conversation is being read into the model's memory: [evaluatedTokens] of [totalTokens]. */
    data class ReadingPrompt(val evaluatedTokens: Int, val totalTokens: Int) : TurnPhase

    /** The reply is being written. */
    data object Writing : TurnPhase
}

/** The one reply that is being generated: for [assistantId] in the chat [sessionId]. */
data class Turn(val sessionId: String, val assistantId: String, val phase: TurnPhase)

/**
 * Everything the chat screens show, as one immutable value.
 *
 * @property chats the stored chats, the most recently changed first. A new chat joins the list
 *   with its first message or draft.
 * @property open the chat on screen.
 * @property turn the reply being generated, in whichever chat; null when none is.
 * @property engine what the inference engine is doing.
 * @property models the models this build offers, in the order the picker shows them.
 * @property contextNotes by chat id, what became of the model's context for the latest reply in
 *   that chat, when something did.
 * @property trimmedMessages by chat id, how many of the oldest messages the latest reply in that
 *   chat was generated without, because the conversation no longer fitted the model's context.
 * @property restoringMemory true while the engine reads a saved memory of a conversation back
 *   ([MemoryRestoreState.Restoring]); a reply waiting for it says so.
 */
data class ChatState(
    val chats: List<SessionSummary>,
    val open: OpenChat,
    val turn: Turn? = null,
    val engine: EngineState = EngineState.Unloaded,
    val models: List<ModelSpec> = emptyList(),
    val trimmedMessages: Map<String, Int> = emptyMap(),
    val contextNotes: Map<String, ContextNote> = emptyMap(),
    val restoringMemory: Boolean = false,
) {
    /** True when [modelId] is one of [models]. */
    fun offers(modelId: String): Boolean = models.any { it.id == modelId }
}

/**
 * What a reply from one model is asked for with, apart from the conversation itself. It is the
 * model's, not the chat's: a chat that switches models gets the other model's values with it.
 *
 * @property systemPrompt the system prompt set for the model, or null.
 * @property sampling how the next token is chosen: the model's own values from the catalogue
 *   with what the user set for this model in their place. It is always passed to the engine in
 *   full, so that what a reply was generated with never depends on a second place that knows
 *   the defaults.
 * @property maxNewTokens the longest the reply may get, in tokens: what the user set for this
 *   model, or the model's own. Passed in full for the same reason.
 */
data class ModelRequestSettings(val systemPrompt: String?, val sampling: SamplingParams, val maxNewTokens: Int, val maxContextTokens: Int? = null)

/**
 * What became of the model's context while the latest reply in a chat was prepared: said under
 * the conversation, like the note about messages that were left out.
 *
 * @property grownTo the size the context was given, in tokens, when it had to grow; null when
 *   it did not.
 * @property reread true when the whole conversation was read again for that.
 * @property limitedAt the size beyond which the context could not grow, when that was found
 *   out with this reply; null otherwise.
 * @property limitedByMemory true when the device's memory was the reason, false when a larger
 *   context could not be created.
 */
data class ContextNote(val grownTo: Int? = null, val reread: Boolean = false, val limitedAt: Int? = null, val limitedByMemory: Boolean = true)

/** Things that happened and are said once. */
enum class ChatNotice {
    /** A reply that was being written in another chat was stopped because this chat asked for one. */
    OTHER_REPLY_STOPPED,

    /** The chat that was to be opened is no longer stored. */
    CHAT_GONE,
}

/**
 * The chats of an unlocked session and the one generation that may be running in them.
 *
 * It belongs to the session, not to a screen: a reply keeps being written while the user looks
 * at another chat or at the settings, a rotation does not touch it, and nothing in here needs a
 * screen to be there at all. Screens watch [state] and call the functions below.
 *
 * The rules it keeps:
 * - **One reply at a time.** The engine holds one model and one conversation. Asking for a reply
 *   while another chat is still getting one stops that other reply, which is kept as a stopped
 *   message ([ChatNotice.OTHER_REPLY_STOPPED]).
 * - **A reply never outlives its generation.** Whichever way a generation ends (finished, failed,
 *   stopped, cancelled with the session), its message is no longer marked as being written when
 *   this class is done with it.
 * - **One model in memory.** A model is loaded when the session opens (the model of the chat on
 *   screen), when the user picks one, and when a reply needs one. Opening another chat does not
 *   load anything, so looking through chats that use different models costs nothing.
 * - **Everything is saved as it changes.** Every change of a chat is handed to the
 *   [CoalescingWriter] at once: urgently for what the user did, lazily for the text of a reply
 *   as it streams and for a draft as it is typed. The index of chats follows.
 * - **A chat with nothing in it is not stored.** A new chat is listed and written with its first
 *   message or draft, and a chat that is left empty is forgotten when the user moves on.
 * - **Expired messages go.** Under the limit [lifetime] gives, turns that have expired are
 *   removed ([MessageExpiry]) when a stored chat is opened, every [expiryIntervalMillis] while
 *   the session lives (hourly in the app), when [expireMessages] is called (the setting was shortened), and from a
 *   chat whose reply was being written as soon as that reply has ended, never during it. A chat
 *   left without messages is deleted whole, quietly. When the engine may hold the conversation
 *   of a chat that was pruned, its memory is marked not to be saved at once
 *   ([InferenceEngine.markNotToBeSaved]), before any request can be built, and the engine is
 *   made to forget it. (What expired before the unlock was removed before this class was made;
 *   see [SessionStore.expireAll].)
 * - **The model's saved memory comes back before the first request.** While [memoryVault] gives
 *   a vault, the memory saved of a chat is read back ([InferenceEngine.restoreMemory]) before a
 *   request of that chat is built, whenever the engine may not hold that chat already: after
 *   the unlock for the chat on screen once its model is loaded, and before the first request of
 *   any other chat. A request is built only after that call has returned. Every request carries
 *   its chat's [conversationKeyOf].
 * - **No memory is saved of text that is gone.** This class is the session's
 *   [MemoryConversations]: it tells the store what a conversation is now, and answers "no such
 *   conversation" for a chat whose stored messages were removed or changed (an edit, an answer
 *   asked for again, a retry, an expiry) until the engine has read a request built after that
 *   change. So a save at a lock that comes before that request cannot keep the old text. A
 *   change of a system prompt is such a change too ([systemPromptChanged]): the prompt is part
 *   of what the engine holds.
 *
 * Threading: everything except the constructor, [shutdown], [conversation], [requestsBuilt] and
 * [heldConversationKey] is called on the main dispatcher and all state is confined to it. The
 * constructor reads the chat that was on screen last from the disk, so it is called off the
 * main thread, when the session is opened.
 *
 * @param opened the index of chats as the store has just reconciled it.
 * @param requestSettings what the settings hold for a model right now. It is asked for every
 *   reply when the reply is about to be generated, so a change in Settings applies from the next
 *   answer, whether that is a new message, an answer asked for again or one that is continued.
 * @param models the models this build offers.
 * @param onReplyFinished called on the main dispatcher when the model has finished a reply by
 *   itself (it reached its end, a stop string or its length limit), not when one was stopped or
 *   failed.
 * @param lifetime how long messages are kept, as the settings say now; asked at every pass.
 *   [clock] is the time they are dated and aged by.
 * @param expiryIntervalMillis how often expired messages are looked for while the session lives
 *   ([EXPIRY_INTERVAL_MILLIS] in the app); null for no timer. The unit tests leave it out,
 *   because a timer that repeats for ever never lets virtual time come to rest.
 * @param memoryVault where the engine finds the model's saved memory now; null while none may be
 *   restored (the switch is off, the platform cannot keep one, the session is closing).
 * @param onTurnEnded called on the main dispatcher whenever a reply has ended, however it ended,
 *   and no other reply has begun in its place.
 * @param onMemoryRestored called on the main dispatcher when the engine's memory was read back
 *   from the saved one, or already was it.
 * @param deleteSavedMemory deletes whatever saved memory there is, of whichever chat, without
 *   the key; blocking, run by the writer. For a change of a system prompt
 *   ([systemPromptChanged]).
 */
class ChatController(
    private val store: SessionStore,
    private val writer: CoalescingWriter,
    private val engine: InferenceEngine,
    private val models: List<ModelSpec>,
    opened: SessionStore.Opened,
    private val requestSettings: (model: ModelSpec) -> ModelRequestSettings,
    private val clock: Clock,
    private val ids: IdSource,
    private val mainDispatcher: CoroutineDispatcher,
    private val ioDispatcher: CoroutineDispatcher,
    private val onReplyFinished: () -> Unit = {},
    private val lifetime: () -> MessageLifetime = { MessageLifetime.NEVER },
    private val expiryIntervalMillis: Long? = null,
    private val memoryVault: () -> MemoryVault? = { null },
    private val onTurnEnded: () -> Unit = {},
    private val onMemoryRestored: () -> Unit = {},
    private val deleteSavedMemory: () -> Unit = {},
) : MemoryConversations {
    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)

    /**
     * The sessions read or made since the unlock, by id. The truth about each of them. Written on
     * the main thread only; concurrent so that [conversation] can read it from the engine's.
     */
    private val sessions: MutableMap<String, ChatSession> = ConcurrentHashMap()

    /** What could not be read of a loaded session, until the user has been told. */
    private val incomplete = HashMap<String, SessionStore.Incomplete>()

    /** A reply in flight. An object of its own so that a job can tell whether it is still the current one. */
    private class Running(val sessionId: String, val assistantId: String) {
        lateinit var job: Job
    }

    private class Loading(val modelId: String, val job: Job)

    private var running: Running? = null

    /**
     * The chat whose conversation the engine was last given, or read back from a saved memory,
     * and so may still hold in its memory; null when it was made to forget since. Written on the
     * main thread only.
     */
    @Volatile private var engineHolds: String? = null

    /**
     * Every chat whose conversation the engine may hold in its memory now, as far as can be told
     * from here: [engineHolds] says which chat the engine was last given, but a request can be
     * given up before the engine has read any of it (stopped while it waited for the engine), and
     * the engine then still holds what it held before. So a chat leaves this set only once the
     * engine has shown that it read another chat's request ([showsPromptRead]) or a restore has
     * filled its memory with another chat, or when the engine is made to forget. Main thread only.
     */
    private val mayHold = HashSet<String>()

    /** The chat whose saved memory the engine is reading back right now, if any. Main thread only. */
    private var restoring: String? = null

    /** The restore queued at the opening ([queueRestore]), so that [shutdown] can stop it. Main thread only. */
    private var restoreJob: Job? = null

    private val builtRequests = AtomicLong(0)

    /** How many requests have been handed to the engine since the session opened; from any thread. */
    val requestsBuilt: Long get() = builtRequests.get()

    /**
     * Counts every change of a chat that removes or alters stored messages ([keepsSavedMemory]
     * false); [staleSince] holds, by chat, the count of its latest such change until the engine
     * has read a request built after it. See the class comment: such a chat has no conversation
     * the store may save.
     */
    private val changes = AtomicLong(0)
    private val staleSince = ConcurrentHashMap<String, Long>()

    /** The job of the latest turn, running or not: the next turn waits until it has let go of the engine. */
    private var lastTurn: Job? = null

    /** A model load that no reply is waiting for: the one at start, or one the user asked for. */
    private var loading: Loading? = null
    private var opening: Job? = null
    private var closed = false

    /** Set while the session goes on behind the lock: the reply in flight may finish, nothing new begins. */
    private var finishingOnly = false

    /**
     * The chats whose next write removes or changes messages that were stored, so that their
     * saved memory is to be deleted before that write ([keepsSavedMemory]). Added to on the main
     * thread when the change is made, and taken by the write that carries it out, whichever write
     * of the chat that turns out to be: a newer write takes the place of one that has not run
     * yet ([CoalescingWriter]), and must not take the deletion away with it.
     */
    private val memoryToDrop: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The index as it was last handed to the writer; null when what is on disk is known to be out of date. */
    private var writtenIndex: SessionIndex? = opened.index.takeUnless { opened.stale }

    private val _state: MutableStateFlow<ChatState>
    val state: StateFlow<ChatState>

    private val _notices = Channel<ChatNotice>(Channel.BUFFERED)

    /** One-off messages for the screen. They wait here until a screen collects them. */
    val notices: Flow<ChatNotice> = _notices.receiveAsFlow()

    init {
        val listed = opened.index.sessions
        val first = opened.index.activeId
            ?.let { id -> openChatFor(id, loadOrUnreadable(id), listed.firstOrNull { it.id == id }?.title.orEmpty()) }
            ?: OpenChat.Ready(newSession(null))
        _state = MutableStateFlow(
            ChatState(chats = ordered(listed), open = first, engine = engine.state.value, models = models),
        )
        state = _state.asStateFlow()
        saveIndex(urgent = false)
        scope.launch {
            engine.state.collect { engineState -> _state.update { it.copy(engine = engineState) } }
        }
        scope.launch {
            engine.memoryRestore.collect { restore -> _state.update { it.copy(restoringMemory = restore is MemoryRestoreState.Restoring) } }
        }
        // The model of the chat on screen, so that the first message does not have to wait for
        // it, and then its saved memory, so that the first message need not read it all again.
        (first as? OpenChat.Ready)?.let {
            preload(it.session.modelId)
            queueRestore(it.id)
        }
        expiryIntervalMillis?.let { interval ->
            scope.launch {
                while (true) {
                    delay(interval)
                    expireMessages()
                }
            }
        }
    }

    // --- Chats ---------------------------------------------------------------------------------

    /**
     * Shows a new, empty chat, which uses the model of the chat that was on screen. Does nothing
     * when the chat on screen is such a chat already.
     */
    fun newChat() {
        if (closed) return
        val current = (_state.value.open as? OpenChat.Ready)?.session
        if (current != null && !hasContent(current)) return
        opening?.cancel()
        show(OpenChat.Ready(newSession(current?.modelId)))
    }

    /**
     * Shows the stored chat [id]. A chat that was used since the unlock appears at once; another
     * is read from the disk first, and the screen changes when that is done.
     */
    fun openChat(id: String) {
        if (closed) return
        opening?.cancel()
        opening = null
        if (id == _state.value.open.id) return
        val known = sessions[id]
        if (known != null) {
            show(OpenChat.Ready(known, incomplete[id]))
            return
        }
        val title = _state.value.chats.firstOrNull { it.id == id }?.title.orEmpty()
        val now = clock.nowMillis()
        val limit = lifetime()
        opening = scope.launch {
            val loaded = withContext(ioDispatcher) {
                // What has expired goes before the chat is shown; a chat that has expired whole is gone.
                expireStored(id, limit, now)
                loadOrUnreadable(id)
            }
            val chat = openChatFor(id, loaded, title)
            if (chat != null) {
                show(chat)
            } else {
                // Its file is gone: the entry was all that was left of it.
                _state.update { state -> state.copy(chats = state.chats.filterNot { it.id == id }) }
                saveIndex(urgent = false)
                _notices.trySend(ChatNotice.CHAT_GONE)
            }
        }
    }

    /**
     * Deletes the chat [id] for good, readable or not: its file, every kept copy of it and its
     * entry in the list. A reply being written in it is abandoned. When it was the chat on
     * screen, a new empty chat takes its place. When the engine may hold its conversation, the
     * engine is made to forget it, as for a pruned chat ([forgetIfHeld]).
     */
    fun deleteChat(id: String) {
        if (closed) return
        // A chat that is still being read from the disk must not arrive after its own deletion.
        opening?.cancel()
        opening = null
        running?.takeIf { it.sessionId == id }?.let { reply ->
            running = null
            reply.job.cancel()
        }
        val removed = sessions.remove(id)
        incomplete.remove(id)
        // The store deletes the chat's saved memory first.
        writer.submit(sessionKey(id), urgent = true) { store.delete(id) }
        val replacement = if (_state.value.open.id == id) OpenChat.Ready(newSession(removed?.modelId)) else null
        _state.update { state ->
            state.copy(
                chats = state.chats.filterNot { it.id == id },
                open = replacement ?: state.open,
                turn = state.turn?.takeUnless { it.sessionId == id },
                trimmedMessages = state.trimmedMessages - id,
                contextNotes = state.contextNotes - id,
            )
        }
        // A chat the user deleted does not stay in the model's memory either.
        forgetIfHeld(id)
        saveIndex(urgent = true)
    }

    /**
     * Removes what has expired under the limit of [lifetime] at the time of [clock], from every
     * chat: from those in memory here, and from the stored chats that are not, unless their
     * summaries rule it out. A chat whose reply is being written is left until that reply ends.
     * Main thread; the stored chats are read and written by the writer, in turn with everything
     * else written to them.
     */
    fun expireMessages() {
        if (closed) return
        val limit = lifetime()
        if (limit.limitMillis == null) return
        val now = clock.nowMillis()
        for (id in sessions.keys.toList()) expireLoaded(id, limit, now)
        for (summary in _state.value.chats) {
            if (summary.id in sessions || !MessageExpiry.mayHaveExpired(summary.oldestMessageAtMillis, limit, now)) continue
            val id = summary.id
            // Nothing else is pending for this chat: it is listed and not in memory, so it was
            // neither changed nor deleted since the unlock read it.
            writer.submit(sessionKey(id), urgent = false) {
                val expiry = expireStored(id, limit, now)
                scope.launch { storedExpired(id, expiry) }
            }
        }
    }

    /**
     * The time the chats are dated and aged by: the trusted time, which a phone clock set back
     * cannot take back. Whatever shows how long ago a chat changed measures against this, never
     * against the raw wall clock, or it could show a change in the future.
     */
    fun nowMillis(): Long = clock.nowMillis()

    /** The user has read that part of the chat [sessionId] could not be loaded. */
    fun incompleteNoticeShown(sessionId: String) {
        if (incomplete.remove(sessionId) == null) return
        _state.update { state ->
            val open = state.open
            if (open is OpenChat.Ready && open.id == sessionId) state.copy(open = open.copy(incomplete = null)) else state
        }
    }

    /** Keeps what is typed but not sent in the chat [sessionId]. It is stored, encrypted, like the rest. */
    fun setDraft(sessionId: String, text: String) {
        val session = usable(sessionId) ?: return
        if (session.draft != text) commit(session.withDraft(text), urgent = false)
    }

    // --- Turns ---------------------------------------------------------------------------------

    /**
     * Sends [text] in the chat [sessionId] and starts the reply.
     *
     * @return false, with nothing changed, when it cannot be sent now: the text is blank, a reply
     *   is being written in this chat, or the chat's model is not one this build offers.
     */
    fun send(sessionId: String, text: String): Boolean {
        val session = sendable(sessionId) ?: return false
        if (isBlankMessage(text)) return false
        return begin(session.withUserTurn(typed(session, text), clock, ids))
    }

    /**
     * Stops the reply being written in the chat [sessionId]. What has been written is kept,
     * marked as stopped, and saved before this returns to its caller's next line; the engine is
     * told to stop in the background.
     */
    fun stop(sessionId: String) {
        if (running?.sessionId == sessionId) endRunning()
    }

    /** Asks again for the reply that failed, was stopped before it said anything, or never started. */
    fun retry(sessionId: String): Boolean {
        val session = sendable(sessionId) ?: return false
        return begin(session.forRetry(clock)?.withAssistantPlaceholder(clock, ids))
    }

    /** Replaces the answer [assistantMessageId], and everything after it, with a new reply to the same question. */
    fun regenerate(sessionId: String, assistantMessageId: String): Boolean {
        val session = sendable(sessionId) ?: return false
        return begin(session.regenerateFrom(assistantMessageId, clock)?.withAssistantPlaceholder(clock, ids))
    }

    /**
     * Replaces the text of the user message [userMessageId] with [newText], removes everything
     * after it, and starts the reply to the edited message.
     */
    fun edit(sessionId: String, userMessageId: String, newText: String): Boolean {
        val session = sendable(sessionId) ?: return false
        if (isBlankMessage(newText)) return false
        return begin(session.editAndTruncate(userMessageId, typed(session, newText), clock)?.withAssistantPlaceholder(clock, ids))
    }

    /**
     * Has the model carry on writing the answer [assistantMessageId] from where it ends: for a
     * reply that was stopped or reached its length limit. Only the last message can be continued,
     * and only by the model that wrote it.
     */
    fun continueReply(sessionId: String, assistantMessageId: String): Boolean {
        val session = sendable(sessionId) ?: return false
        return begin(session.forContinuation(assistantMessageId, clock))
    }

    // --- Models --------------------------------------------------------------------------------

    /**
     * Makes [modelId] the model of the replies to come in the chat [sessionId], and loads it
     * unless a reply is being written somewhere (the engine has one model, and that reply is
     * using it; the new model is then loaded when it is first needed). Refused while a reply is
     * being written in this very chat.
     */
    fun selectModel(sessionId: String, modelId: String) {
        val session = usable(sessionId) ?: return
        val spec = models.firstOrNull { it.id == modelId } ?: return
        if (running?.sessionId == sessionId) return
        if (session.modelId != modelId) commit(session.withModel(modelId), urgent = true)
        preload(spec.id)
    }

    /** Tries again to load the model of the chat on screen, after a load that failed. */
    fun reloadModel() {
        if (closed || running != null) return
        val session = (_state.value.open as? OpenChat.Ready)?.session ?: return
        preload(session.modelId)
    }

    // --- The session's end ---------------------------------------------------------------------

    /** Hands every lazy write to the disk now: called when the app leaves the screen. */
    fun flush() = writer.flush()

    /**
     * The app has locked and this session is only left alive to finish the reply that is being
     * written: from now on no reply and no model load is started, whoever asks. The reply in
     * flight goes on and is saved as it goes.
     */
    fun finishOnly() {
        finishingOnly = true
    }

    /** The user has unlocked again and is back in this very session: replies can be asked for as before. */
    fun resume() {
        finishingOnly = false
    }

    /**
     * Ends everything, for the lock: a reply in flight is stopped and kept as a stopped message,
     * a load is abandoned, every change is handed to the writer, and when this returns nothing
     * here is using the engine any more. Waiting for the engine is bounded, so that a native call
     * that hangs cannot keep the data key alive.
     *
     * May be called from any thread. After it, every function of this class does nothing.
     */
    suspend fun shutdown() {
        withContext(NonCancellable + mainDispatcher) {
            closed = true
            opening?.cancel()
            loading?.job?.cancel()
            // A restore stops at its next piece; the saved memory it was reading stays on disk.
            restoreJob?.cancel()
            endRunning()
            withTimeoutOrNull(ENGINE_TIMEOUT_MILLIS) { listOfNotNull(lastTurn, loading?.job, opening).joinAll() }
            scope.cancel()
            writer.flush()
        }
    }

    // --- Internals: chats ----------------------------------------------------------------------

    private fun hasContent(session: ChatSession): Boolean = session.messages.isNotEmpty() || !PromptWhitespace.isBlank(session.draft)

    private fun usable(sessionId: String): ChatSession? = if (closed) null else sessions[sessionId]

    /** The session [sessionId] if a reply may be asked for in it now. */
    private fun sendable(sessionId: String): ChatSession? =
        usable(sessionId)?.takeIf { !it.isGenerating && models.any { model -> model.id == it.modelId } }

    /**
     * What is stored of [text] the user sends in [session]: without the blanks around it for a
     * chat model, and exactly as typed for a model that continues the text, where a space at the
     * end decides what the model writes next.
     */
    private fun typed(session: ChatSession, text: String): String =
        if (models.firstOrNull { it.id == session.modelId }?.family?.continuesText == true) text else text.trim()

    private fun newSession(preferredModelId: String?): ChatSession {
        val modelId = models.firstOrNull { it.id == preferredModelId }?.id
            ?: models.firstOrNull { it.id == ModelCatalog.default.id }?.id
            ?: models.firstOrNull()?.id
            ?: ModelCatalog.default.id
        return ChatSession.create(modelId, clock, ids).also { sessions[it.id] = it }
    }

    /** Reads a session; a file the disk will not give counts as unreadable, which is what the user is then told. */
    private fun loadOrUnreadable(id: String): SessionStore.Loaded = try {
        store.load(id)
    } catch (_: IOException) {
        SessionStore.Loaded.Unreadable
    }

    private fun openChatFor(id: String, loaded: SessionStore.Loaded, title: String): OpenChat? = when (loaded) {
        is SessionStore.Loaded.Readable -> {
            sessions[id] = loaded.session
            loaded.incomplete?.let { incomplete[id] = it }
            OpenChat.Ready(loaded.session, loaded.incomplete)
        }
        SessionStore.Loaded.Unreadable -> OpenChat.Unreadable(id, title)
        SessionStore.Loaded.Missing -> null
    }

    /** Puts [chat] on screen, and forgets the chat that was there if nothing was ever put into it. */
    private fun show(chat: OpenChat) {
        val leaving = _state.value.open
        _state.update { it.copy(open = chat) }
        val left = (leaving as? OpenChat.Ready)?.let { sessions[it.id] }
        if (left != null && left.id != chat.id && !hasContent(left) && running?.sessionId != left.id) {
            sessions.remove(left.id)
            if (_state.value.chats.any { it.id == left.id }) {
                // It was stored while it had a draft; the draft is gone, and so is the chat.
                _state.update { state -> state.copy(chats = state.chats.filterNot { it.id == left.id }) }
                writer.submit(sessionKey(left.id), urgent = true) { store.delete(left.id) }
            }
        }
        saveIndex(urgent = false)
    }

    /**
     * Makes [updated] the state of its session: publishes it, lists it once it has content, and
     * hands it to the writer. Does nothing for a session that is no longer here (deleted while a
     * reply was still arriving for it).
     *
     * A change that removes or alters a message that was stored (an edit, an answer asked for
     * again, a retry, an expiry; see [keepsSavedMemory]) has the chat's saved memory deleted
     * before the chat is written, in the same write.
     */
    private fun commit(updated: ChatSession, urgent: Boolean) {
        val id = updated.id
        val current = sessions[id] ?: return
        if (updated === current) return
        sessions[id] = updated
        val listed = _state.value.chats.any { it.id == id }
        val stored = listed || hasContent(updated)
        _state.update { state ->
            val open = state.open
            state.copy(
                chats = if (stored) withSummary(state.chats, updated.summary()) else state.chats,
                open = if (open is OpenChat.Ready && open.id == id) open.copy(session = updated) else open,
            )
        }
        if (stored) {
            if (!keepsSavedMemory(current, updated)) {
                memoryToDrop += id
                staleSince[id] = changes.incrementAndGet()
            }
            writer.submit(sessionKey(id), urgent) { store.save(updated, dropMemoryFirst = memoryToDrop.remove(id)) }
            saveIndex(urgent = false)
        }
    }

    /** [chats] with [summary] in place of the entry for the same chat; the same list when nothing changes. */
    private fun withSummary(chats: List<SessionSummary>, summary: SessionSummary): List<SessionSummary> =
        if (chats.any { it == summary }) chats else ordered(chats.filterNot { it.id == summary.id } + summary)

    private fun ordered(chats: List<SessionSummary>): List<SessionSummary> =
        chats.sortedWith(compareByDescending<SessionSummary> { it.updatedAtMillis }.thenByDescending { it.createdAtMillis }.thenBy { it.id })

    /**
     * Writes the index when it differs from the one last written. The chat on screen is the
     * active one once it is stored. Which chats there are and which one is on screen is written
     * at once, whatever [urgent] says: that changes rarely, and it is what the next start goes
     * by. What changes with every turn (a title, a time, a count) may wait.
     */
    private fun saveIndex(urgent: Boolean) {
        val state = _state.value
        val index = SessionIndex(activeId = state.open.id.takeIf { id -> state.chats.any { it.id == id } }, sessions = state.chats)
        val written = writtenIndex
        if (index == written) return
        val structural = written == null || written.activeId != index.activeId || written.sessions.size != index.sessions.size
        writtenIndex = index
        writer.submit(INDEX_KEY, urgent || structural) { store.saveIndex(index) }
    }

    // --- Internals: expiry ---------------------------------------------------------------------

    /**
     * Applies the expiry of messages to the stored chat [id] (blocking, off the main thread). A
     * failure to read, write or delete leaves it as it is until the next pass.
     */
    private fun expireStored(id: String, limit: MessageLifetime, now: Long): SessionStore.Expiry = try {
        store.expire(id, limit, now)
    } catch (_: IOException) {
        SessionStore.Expiry.Unchanged
    }

    /** Main thread: what [expireStored] did to [id], which was not in memory, made known to the list. */
    private fun storedExpired(id: String, expiry: SessionStore.Expiry) {
        // Opened meanwhile: it was expired again on the way, and what is in memory is the truth.
        if (closed || id in sessions || _state.value.chats.none { it.id == id }) return
        when (expiry) {
            // A missing file is not an expiry: the entry stays, and opening it says the chat is gone.
            SessionStore.Expiry.Unchanged, SessionStore.Expiry.Missing -> return
            is SessionStore.Expiry.Checked -> {
                _state.update { state ->
                    state.copy(chats = state.chats.map { if (it.id == id) it.copy(oldestMessageAtMillis = expiry.oldestMessageAtMillis) else it })
                }
                saveIndex(urgent = false)
                return
            }
            is SessionStore.Expiry.Pruned -> _state.update { it.copy(chats = withSummary(it.chats, expiry.session.summary())) }
            SessionStore.Expiry.Deleted -> _state.update { state -> state.copy(chats = state.chats.filterNot { it.id == id }) }
        }
        forgetIfHeld(id)
        saveIndex(urgent = false)
    }

    /**
     * Main thread: applies the expiry of messages to the chat [id] in memory. A chat whose reply
     * is being written is left alone; [run] calls this again when the reply has ended. A chat
     * without messages has nothing to date it by here, and is judged by its file at the next
     * unlock. Messages without a date take the time the conversation last changed, the nearest
     * thing to their file's date in memory (the unlock has normally given them one already).
     */
    private fun expireLoaded(id: String, limit: MessageLifetime, now: Long) {
        val session = sessions[id] ?: return
        if (running?.sessionId == id || session.isGenerating || session.messages.isEmpty()) return
        val normalised = MessageExpiry.normalised(session, now, fileMillis = session.updatedAtMillis)
        when (val outcome = MessageExpiry.prune(normalised, limit, now)) {
            MessageExpiry.Outcome.Unchanged -> if (normalised !== session) commit(normalised, urgent = false)
            is MessageExpiry.Outcome.Pruned -> {
                commit(outcome.session, urgent = true)
                forgetIfHeld(id)
            }
            MessageExpiry.Outcome.Emptied -> removeExpired(id)
        }
    }

    /**
     * Main thread: deletes the chat [id], all of whose messages have expired, as [deleteChat]
     * does but quietly: its file, its kept copies and its entry go, and a chat on screen gives
     * way to a new one with the same model and the same draft, which the user may be typing.
     */
    private fun removeExpired(id: String) {
        val removed = sessions.remove(id)
        incomplete.remove(id)
        writer.submit(sessionKey(id), urgent = true) { store.delete(id) }
        val replacement = if (_state.value.open.id == id) OpenChat.Ready(newSession(removed?.modelId)) else null
        // What the user may be typing at this very moment is not a message, and has not expired.
        val draft = removed?.draft.orEmpty()
        _state.update { state ->
            state.copy(
                chats = state.chats.filterNot { it.id == id },
                open = replacement ?: state.open,
                trimmedMessages = state.trimmedMessages - id,
                contextNotes = state.contextNotes - id,
            )
        }
        if (replacement != null && draft.isNotEmpty()) commit(replacement.session.withDraft(draft), urgent = true)
        forgetIfHeld(id)
        saveIndex(urgent = true)
    }

    /**
     * Main thread: when the engine may still hold the conversation of the chat [id] ([mayHold]),
     * whose messages have just been removed or which was deleted, it is made to forget it, so
     * that no text that is gone stays in its memory. Done in turn with the replies: the next one
     * waits for it, as it waits for the one before.
     */
    private fun forgetIfHeld(id: String) {
        if ((id !in mayHold && engineHolds != id && restoring != id) || closed) return
        // At once, before any request can be built without the removed messages: no save of
        // what the engine holds now, and no restore, until its memory has been emptied.
        engine.markNotToBeSaved()
        engineHolds = null
        mayHold.clear()
        val previous = lastTurn
        lastTurn = scope.launch {
            previous?.join()
            try {
                engine.forget()
            } catch (e: CancellationException) {
                throw e
            } catch (_: RuntimeException) {
                // The engine promises not to throw; the lock makes it forget in any case.
            }
        }
    }

    // --- Internals: models ---------------------------------------------------------------------

    /**
     * Starts loading [modelId] for no reply in particular, unless a reply is being written (it is
     * using the engine's one model). A load of another model that was started the same way is
     * abandoned first; a reply that needs the engine takes over from this ([awaitModel]).
     */
    private fun preload(modelId: String) {
        val spec = models.firstOrNull { it.id == modelId } ?: return
        if (closed || finishingOnly || running != null) return
        val current = engine.state.value
        if (current is EngineState.Ready && current.modelId == spec.id) return
        val previous = loading
        if (previous != null && previous.modelId == spec.id && previous.job.isActive) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            previous?.job?.cancelAndJoin()
            engine.load(spec)
        }
        loading = Loading(spec.id, job)
        job.start()
    }

    /** Returns when the engine has finished loading [spec], one way or the other. */
    private suspend fun awaitModel(spec: ModelSpec) {
        val pending = loading
        if (pending != null) {
            if (pending.modelId == spec.id) {
                // The same model is on its way: wait for it. If that load fails, it has failed for
                // this reply too; trying again at once would only make the user wait twice.
                val underWay = pending.job.isActive
                pending.job.join()
                val result = engine.state.value
                if (underWay && result is EngineState.Failed && result.modelId == spec.id) return
            } else {
                // Another one: it is no longer wanted.
                loading = null
                pending.job.cancelAndJoin()
            }
        }
        val current = engine.state.value
        if (current is EngineState.Ready && current.modelId == spec.id) return
        engine.load(spec)
    }

    // --- Internals: turns ----------------------------------------------------------------------

    /** Stores [prepared], which ends in the reply to be written, and starts writing it. False when [prepared] is null. */
    private fun begin(prepared: ChatSession?): Boolean {
        if (prepared == null || finishingOnly) return false
        val assistantId = prepared.streamingMessageId ?: return false
        val interrupted = endRunning()
        if (interrupted != null && interrupted.sessionId != prepared.id) _notices.trySend(ChatNotice.OTHER_REPLY_STOPPED)
        commit(prepared, urgent = true)

        val reply = Running(prepared.id, assistantId)
        val previous = lastTurn
        val job = scope.launch(start = CoroutineStart.LAZY) { run(reply, previous) }
        reply.job = job
        running = reply
        lastTurn = job
        _state.update { it.copy(turn = Turn(reply.sessionId, reply.assistantId, TurnPhase.Preparing), trimmedMessages = it.trimmedMessages - reply.sessionId, contextNotes = it.contextNotes - reply.sessionId) }
        job.start()
        return true
    }

    /**
     * Ends the reply in flight, if there is one, as the Stop button does: its text so far is
     * kept and marked as stopped at once, and its job is cancelled, which makes the engine stop.
     */
    private fun endRunning(): Running? {
        val reply = running ?: return null
        running = null
        sessions[reply.sessionId]?.let { commit(it.stopped(reply.assistantId, clock), urgent = true) }
        _state.update { it.copy(turn = null) }
        reply.job.cancel()
        return reply
    }

    private suspend fun run(reply: Running, previous: Job?) {
        try {
            // The turn before may still be unwinding inside the engine.
            previous?.join()
            val session = sessions[reply.sessionId] ?: return
            val modelId = session.messages.firstOrNull { it.id == reply.assistantId }?.modelId ?: session.modelId
            val spec = models.firstOrNull { it.id == modelId }
            if (spec == null) {
                fail(reply, EngineError.NotLoaded)
                return
            }
            awaitModel(spec)
            val engineState = engine.state.value
            if (engineState is EngineState.Failed && engineState.modelId == spec.id) {
                fail(reply, engineState.error)
                return
            }
            // The saved memory of this chat, unless the engine may hold the chat already: read
            // back before the request is built, never after. The restore of the opening comes first.
            restoreJob?.join()
            if (engineHolds != reply.sessionId) restoreMemory(reply.sessionId, spec)
            // Read again: the draft may have changed while the model was loading.
            val settings = requestSettings(spec)
            val request = (sessions[reply.sessionId] ?: return).generationRequest(spec, settings.systemPrompt, settings.sampling, settings.maxNewTokens, settings.maxContextTokens).copy(conversationKey = conversationKeyOf(reply.sessionId))
            val builtAfter = changes.get()
            builtRequests.incrementAndGet()
            engineHolds = reply.sessionId
            mayHold += reply.sessionId
            engine.generate(request).collect { event ->
                if (event.showsPromptRead() && !closed) {
                    // The engine has read this request into its memory, which now holds this chat
                    // and no other (it keeps only what this request's prompt begins with).
                    mayHold.retainAll(setOf(reply.sessionId))
                    mayHold += reply.sessionId
                }
                if (running === reply) {
                    // The engine has read this request: what it holds no longer has the text of a change made before it.
                    if (event.showsPromptRead()) staleSince.computeIfPresent(reply.sessionId) { _, since -> since.takeIf { it > builtAfter } }
                    onEvent(reply, event)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            // The engine promises not to throw. If it does, the chat shows that instead of the app crashing.
            fail(reply, EngineError.Unexpected(e.javaClass.simpleName))
        } finally {
            // However the generation ended, its message is not left marked as being written.
            sessions[reply.sessionId]
                ?.takeIf { session -> session.messages.any { it.id == reply.assistantId && it.status == MessageStatus.STREAMING } }
                ?.let { commit(it.stopped(reply.assistantId, clock), urgent = true) }
            if (running === reply) {
                running = null
                _state.update { it.copy(turn = null) }
            }
            // It could not be pruned while the reply was being written; it can now.
            if (running?.sessionId != reply.sessionId && !closed) {
                val limit = lifetime()
                if (limit.limitMillis != null) expireLoaded(reply.sessionId, limit, clock.nowMillis())
            }
            if (running == null && !closed) onTurnEnded()
        }
    }

    // --- Internals: the model's saved memory -----------------------------------------------------

    /**
     * Starts the restore of the saved memory of the chat [id] once the load that is under way has
     * ended: at the opening, for the chat on screen, so that it is read back while the user reads
     * and types. Every request waits for it to have returned before it is built ([run]).
     */
    private fun queueRestore(id: String) {
        val load = loading?.job
        restoreJob = scope.launch {
            load?.join()
            val session = sessions[id] ?: return@launch
            val spec = models.firstOrNull { it.id == session.modelId } ?: return@launch
            if (engineHolds != id) restoreMemory(id, spec)
        }
    }

    /**
     * Has the engine read back the saved memory of the chat [id], when there is a vault and
     * [spec] is the loaded model, with the context size the user chose. Main thread, in turn with
     * the replies. While it runs, a prune of the chat marks and forgets as for a chat the engine
     * holds ([forgetIfHeld]).
     */
    private suspend fun restoreMemory(id: String, spec: ModelSpec) {
        val vault = memoryVault() ?: return
        val loaded = engine.state.value
        if (loaded !is EngineState.Ready || loaded.modelId != spec.id) return
        restoring = id
        try {
            val result = engine.restoreMemory(vault, conversationKeyOf(id), requestSettings(spec).maxContextTokens)
            if (result is MemoryRestoreResult.Restored || result == MemoryRestoreResult.AlreadyHeld) {
                engineHolds = id
                // A restore empties the memory before it fills it with this chat's.
                if (result is MemoryRestoreResult.Restored) mayHold.clear()
                mayHold += id
                onMemoryRestored()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: RuntimeException) {
            // The engine promises not to throw; the request reads the conversation as it is.
        } finally {
            restoring = null
        }
    }

    /**
     * Main thread: the system prompt of the model [modelId] was changed or cleared in the
     * settings. The engine's memory, and a memory saved of it, hold the whole prompt the engine
     * was given, the old system prompt included; neither may keep that text now that the user has
     * replaced it. So, as for an edit:
     * - every chat the engine may hold now ([mayHold], and the one being restored) is treated as
     *   changed, hidden from the store ([conversation]) until the engine has read a request built
     *   after this change, unless the engine has another model loaded, whose memory the prompt
     *   of [modelId] cannot be in;
     * - the saved memory is deleted at once (handed to the writer, as every deletion of a chat's
     *   memory is), which also stops a save under way. It is deleted whichever chat and model it
     *   is of: which model a memory was made with is only known by opening it, and a chat's
     *   model can have changed since. The cost is one reading of a conversation.
     */
    fun systemPromptChanged(modelId: String) {
        if (closed) return
        val loaded = (engine.state.value as? EngineState.Ready)?.modelId
        if (loaded == null || loaded == modelId) {
            val held = mayHold + listOfNotNull(engineHolds, restoring)
            if (held.isNotEmpty()) {
                val change = changes.incrementAndGet()
                for (id in held) staleSince[id] = change
            }
        }
        writer.submit(MEMORY_KEY, urgent = true) {
            try {
                deleteSavedMemory()
            } catch (_: IOException) {
                // The store's checks refuse the memory of a hidden chat, and delete it, before it could be used.
            }
        }
    }

    /** The conversation key of what the engine holds now, as far as this class knows; null when nothing. From any thread. */
    fun heldConversationKey(): String? = engineHolds?.let(::conversationKeyOf)

    /**
     * The conversation [conversationKey] names, for the store of the saved memory; see
     * [MemoryConversations.conversation]. Null for a key that is not a chat's, for a chat that
     * was deleted, and for one whose stored messages were changed since the engine last read a
     * request of it (see the class comment). A chat that was not opened since the unlock is read
     * from the disk. From any thread.
     *
     * @throws IOException when the chat's file exists and cannot be read.
     */
    override fun conversation(conversationKey: String): MemoryConversation? {
        val id = chatIdOf(conversationKey) ?: return null
        if (staleSince.containsKey(id)) return null
        val listed = _state.value.chats.firstOrNull { it.id == id }
        val session = sessions[id] ?: run {
            if (listed == null) return null
            when (val loaded = store.load(id)) {
                is SessionStore.Loaded.Readable -> loaded.session
                SessionStore.Loaded.Missing -> return null
                SessionStore.Loaded.Unreadable -> throw IOException("The chat cannot be read")
            }
        }
        val oldest = listOfNotNull(listed?.oldestMessageAtMillis, session.messages.minOfOrNull { it.createdAtMillis }).minOrNull() ?: return null
        return MemoryConversation(id, session.messages, oldest)
    }

    private fun onEvent(reply: Running, event: GenerationEvent) {
        val session = sessions[reply.sessionId] ?: return
        when (event) {
            is GenerationEvent.HistoryTrimmed ->
                _state.update { it.copy(trimmedMessages = it.trimmedMessages + (reply.sessionId to event.droppedMessages)) }
            is GenerationEvent.ContextGrown -> note(reply) { it.copy(grownTo = event.toTokens, reread = it.reread || event.rereading) }
            is GenerationEvent.ContextLimited ->
                note(reply) { it.copy(limitedAt = event.limitTokens, limitedByMemory = event.reason == GenerationEvent.ContextLimited.Reason.MEMORY) }
            is GenerationEvent.PromptProgress -> phase(reply, TurnPhase.ReadingPrompt(event.evaluatedTokens, event.totalTokens))
            // Text added, or text the model took back: either way the message changes as it is written.
            is GenerationEvent.TextDelta, is GenerationEvent.TextReplaced -> {
                phase(reply, TurnPhase.Writing)
                commit(session.applyEvent(reply.assistantId, event, clock), urgent = false)
            }
            is GenerationEvent.Done, is GenerationEvent.Error -> {
                commit(session.applyEvent(reply.assistantId, event, clock), urgent = true)
                if (event is GenerationEvent.Done && event.finishReason != FinishReason.CANCELLED) onReplyFinished()
            }
        }
    }

    private fun note(reply: Running, change: (ContextNote) -> ContextNote) {
        _state.update { it.copy(contextNotes = it.contextNotes + (reply.sessionId to change(it.contextNotes[reply.sessionId] ?: ContextNote()))) }
    }

    private fun phase(reply: Running, phase: TurnPhase) {
        _state.update { state -> if (state.turn?.phase == phase) state else state.copy(turn = Turn(reply.sessionId, reply.assistantId, phase)) }
    }

    private fun fail(reply: Running, error: EngineError) {
        sessions[reply.sessionId]?.let { commit(it.failed(reply.assistantId, error, clock), urgent = true) }
    }

    companion object {
        /**
         * The one definition of a message too blank to send, for Send, for an edit and for the
         * button that offers either: nothing would be left of it once the prompt strips it, by
         * the models' own rule ([PromptWhitespace]). A definition of its own anywhere else (such
         * as Kotlin's `isBlank`, for which U+0085 is not whitespace) would offer a button
         * that then does nothing.
         */
        fun isBlankMessage(text: String): Boolean = PromptWhitespace.isBlank(text)

        /** The key of the index in the [CoalescingWriter]. */
        const val INDEX_KEY: String = "session-index"

        /** The key in the [CoalescingWriter] of a deletion of the saved memory for a changed system prompt. */
        const val MEMORY_KEY: String = "model-memory"

        /** How often expired messages are looked for while a session lives: hourly. */
        const val EXPIRY_INTERVAL_MILLIS: Long = 60 * 60 * 1000L

        /** How long [shutdown] waits for the engine to let go of a generation or a load. */
        const val ENGINE_TIMEOUT_MILLIS: Long = 10_000L

        /** The key of the session [id] in the [CoalescingWriter]. */
        fun sessionKey(id: String): String = "session:$id"

        /**
         * The conversation key ([us.theworkmans.proseplow.core.engine.GenerationRequest.conversationKey])
         * of the chat [id]: its id after a fixed prefix. Two chats never share one, and a chat
         * keeps its key for life, through every unlock, so that a memory saved of it is found
         * again; whether that memory still fits the chat's messages is the store's to check.
         */
        fun conversationKeyOf(id: String): String = CONVERSATION_KEY_PREFIX + id

        /** The chat id in [key], or null when [key] is not a chat's conversation key. */
        fun chatIdOf(key: String): String? = key.takeIf { it.startsWith(CONVERSATION_KEY_PREFIX) }?.removePrefix(CONVERSATION_KEY_PREFIX)?.takeIf { it.isNotEmpty() }

        private const val CONVERSATION_KEY_PREFIX = "chat:"

        /** True for an event that is only emitted once the engine has read the request's prompt into its memory. */
        private fun GenerationEvent.showsPromptRead(): Boolean = when (this) {
            is GenerationEvent.TextDelta, is GenerationEvent.TextReplaced -> true
            is GenerationEvent.Done -> finishReason != FinishReason.CANCELLED
            else -> false
        }

        /**
         * True when going from [before] to [after] leaves every message a saved memory of the
         * chat can have been made from as it was: each message of [before] that is not being
         * written stands at the same place in [after] with the same id, role and date, and with
         * its text unchanged or only added to at its end (an answer that is continued). Messages
         * may be added after them. Anything else (a message removed, its text changed, its date
         * made usable) means that the saved memory may hold text that is gone, and it is deleted
         * before the chat is written ([SessionStore.save]).
         *
         * The message being written is left out: its text changes with every piece of the reply,
         * text is only ever taken back from what the same reply wrote, and no memory is saved
         * while a reply is being written.
         */
        fun keepsSavedMemory(before: ChatSession, after: ChatSession): Boolean {
            if (before.messages === after.messages) return true
            for ((index, old) in before.messages.withIndex()) {
                if (old.status == MessageStatus.STREAMING) continue
                val new = after.messages.getOrNull(index) ?: return false
                if (new.id != old.id || new.role != old.role || new.createdAtMillis != old.createdAtMillis) return false
                if (!new.text.startsWith(old.text)) return false
            }
            return true
        }

        /**
         * True when the chat's last message can be continued by its model: an answer with some
         * text that was stopped or cut off at the length limit, written by the chat's current
         * model, while nothing is being generated.
         */
        fun canContinue(session: ChatSession): Boolean {
            val last = session.messages.lastOrNull() ?: return false
            if (session.isGenerating || last.role != Role.ASSISTANT || last.modelId != session.modelId) return false
            if (PromptWhitespace.isBlank(last.text)) return false
            return last.status == MessageStatus.STOPPED || last.finishReason == FinishReason.LENGTH
        }
    }
}
