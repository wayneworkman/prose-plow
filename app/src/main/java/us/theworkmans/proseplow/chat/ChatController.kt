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
import us.theworkmans.proseplow.core.chat.MessageStatus
import us.theworkmans.proseplow.core.chat.Role
import us.theworkmans.proseplow.core.chat.SessionSummary
import us.theworkmans.proseplow.core.chat.generationRequest
import us.theworkmans.proseplow.core.engine.EngineError
import us.theworkmans.proseplow.core.engine.EngineState
import us.theworkmans.proseplow.core.engine.FinishReason
import us.theworkmans.proseplow.core.engine.GenerationEvent
import us.theworkmans.proseplow.core.engine.InferenceEngine
import us.theworkmans.proseplow.core.model.ModelCatalog
import us.theworkmans.proseplow.core.model.ModelSpec
import us.theworkmans.proseplow.core.model.SamplingParams
import us.theworkmans.proseplow.core.prompt.PromptWhitespace
import us.theworkmans.proseplow.data.CoalescingWriter
import us.theworkmans.proseplow.data.SessionIndex
import us.theworkmans.proseplow.data.SessionStore
import java.io.IOException

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
 * @property trimmedMessages by chat id, how many of the oldest messages the latest reply in that
 *   chat was generated without, because the conversation no longer fitted the model's context.
 */
data class ChatState(
    val chats: List<SessionSummary>,
    val open: OpenChat,
    val turn: Turn? = null,
    val engine: EngineState = EngineState.Unloaded,
    val models: List<ModelSpec> = emptyList(),
    val trimmedMessages: Map<String, Int> = emptyMap(),
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
 */
data class ModelRequestSettings(val systemPrompt: String?, val sampling: SamplingParams)

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
 *
 * Threading: everything except the constructor and [shutdown] is called on the main dispatcher
 * and all state is confined to it. The constructor reads the chat that was on screen last from
 * the disk, so it is called off the main thread, when the session is opened.
 *
 * @param opened the index of chats as the store has just reconciled it.
 * @param requestSettings what the settings hold for a model right now. It is asked for every
 *   reply when the reply is about to be generated, so a change in Settings applies from the next
 *   answer, whether that is a new message, an answer asked for again or one that is continued.
 * @param models the models this build offers.
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
) {
    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)

    /** The sessions read or made since the unlock, by id. The truth about each of them. */
    private val sessions = HashMap<String, ChatSession>()

    /** What could not be read of a loaded session, until the user has been told. */
    private val incomplete = HashMap<String, SessionStore.Incomplete>()

    /** A reply in flight. An object of its own so that a job can tell whether it is still the current one. */
    private class Running(val sessionId: String, val assistantId: String) {
        lateinit var job: Job
    }

    private class Loading(val modelId: String, val job: Job)

    private var running: Running? = null

    /** The job of the latest turn, running or not: the next turn waits until it has let go of the engine. */
    private var lastTurn: Job? = null

    /** A model load that no reply is waiting for: the one at start, or one the user asked for. */
    private var loading: Loading? = null
    private var opening: Job? = null
    private var closed = false

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
        // The model of the chat on screen, so that the first message does not have to wait for it.
        (first as? OpenChat.Ready)?.let { preload(it.session.modelId) }
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
        opening = scope.launch {
            val loaded = withContext(ioDispatcher) { loadOrUnreadable(id) }
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
     * screen, a new empty chat takes its place.
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
        writer.submit(sessionKey(id), urgent = true) { store.delete(id) }
        val replacement = if (_state.value.open.id == id) OpenChat.Ready(newSession(removed?.modelId)) else null
        _state.update { state ->
            state.copy(
                chats = state.chats.filterNot { it.id == id },
                open = replacement ?: state.open,
                turn = state.turn?.takeUnless { it.sessionId == id },
                trimmedMessages = state.trimmedMessages - id,
            )
        }
        saveIndex(urgent = true)
    }

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
        if (PromptWhitespace.isBlank(text)) return false
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
        if (PromptWhitespace.isBlank(newText)) return false
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
            writer.submit(sessionKey(id), urgent) { store.save(updated) }
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

    // --- Internals: models ---------------------------------------------------------------------

    /**
     * Starts loading [modelId] for no reply in particular, unless a reply is being written (it is
     * using the engine's one model). A load of another model that was started the same way is
     * abandoned first; a reply that needs the engine takes over from this ([awaitModel]).
     */
    private fun preload(modelId: String) {
        val spec = models.firstOrNull { it.id == modelId } ?: return
        if (closed || running != null) return
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
        if (prepared == null) return false
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
        _state.update { it.copy(turn = Turn(reply.sessionId, reply.assistantId, TurnPhase.Preparing), trimmedMessages = it.trimmedMessages - reply.sessionId) }
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
            // Read again: the draft may have changed while the model was loading.
            val settings = requestSettings(spec)
            val request = (sessions[reply.sessionId] ?: return).generationRequest(spec, settings.systemPrompt, settings.sampling)
            engine.generate(request).collect { event -> if (running === reply) onEvent(reply, event) }
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
        }
    }

    private fun onEvent(reply: Running, event: GenerationEvent) {
        val session = sessions[reply.sessionId] ?: return
        when (event) {
            is GenerationEvent.HistoryTrimmed ->
                _state.update { it.copy(trimmedMessages = it.trimmedMessages + (reply.sessionId to event.droppedMessages)) }
            is GenerationEvent.PromptProgress -> phase(reply, TurnPhase.ReadingPrompt(event.evaluatedTokens, event.totalTokens))
            // Text added, or text the model took back: either way the message changes as it is written.
            is GenerationEvent.TextDelta, is GenerationEvent.TextReplaced -> {
                phase(reply, TurnPhase.Writing)
                commit(session.applyEvent(reply.assistantId, event, clock), urgent = false)
            }
            is GenerationEvent.Done, is GenerationEvent.Error -> commit(session.applyEvent(reply.assistantId, event, clock), urgent = true)
        }
    }

    private fun phase(reply: Running, phase: TurnPhase) {
        _state.update { state -> if (state.turn?.phase == phase) state else state.copy(turn = Turn(reply.sessionId, reply.assistantId, phase)) }
    }

    private fun fail(reply: Running, error: EngineError) {
        sessions[reply.sessionId]?.let { commit(it.failed(reply.assistantId, error, clock), urgent = true) }
    }

    companion object {
        /** The key of the index in the [CoalescingWriter]. */
        const val INDEX_KEY: String = "session-index"

        /** How long [shutdown] waits for the engine to let go of a generation or a load. */
        const val ENGINE_TIMEOUT_MILLIS: Long = 10_000L

        /** The key of the session [id] in the [CoalescingWriter]. */
        fun sessionKey(id: String): String = "session:$id"

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
