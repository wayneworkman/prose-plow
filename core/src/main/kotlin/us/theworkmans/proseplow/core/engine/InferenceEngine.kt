package us.theworkmans.proseplow.core.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import us.theworkmans.proseplow.core.model.ModelSpec
import us.theworkmans.proseplow.core.model.SamplingParams
import us.theworkmans.proseplow.core.prompt.PromptMessage

/** Whether a model is ready to generate. */
sealed interface EngineState {

    /** No model is loaded. */
    data object Unloaded : EngineState

    /** [modelId] is being loaded; [progress] runs from 0 to 1. */
    data class Loading(val modelId: String, val progress: Float) : EngineState

    /** [modelId] is loaded and can generate. */
    data class Ready(val modelId: String) : EngineState

    /** Loading [modelId] failed; nothing is loaded. */
    data class Failed(val modelId: String, val error: EngineError) : EngineState
}

/**
 * One reply to generate.
 *
 * @property modelId the model the caller believes is loaded, normally the session's current
 *   model, which is also what the assistant message is stamped with. The engine refuses the
 *   request when another model is loaded, so a reply can never be attributed to the wrong model.
 * @property messages the conversation, oldest first, reduced to what the model is to see.
 * @property systemPrompt the system prompt set for this model, or null. A blank one is not sent,
 *   and neither is any system text when the model does not support a system prompt.
 * @property extraSystemText more text for the system message, after the system prompt. This is
 *   where the description of tools will go; nothing sets it yet.
 * @property continueFinalMessage true when the last message is a partial assistant message that
 *   the model is to continue instead of starting a new reply. The events then carry only the
 *   continuation. The model's memory usually still holds the conversation up to that point, so
 *   continuing costs little; this is what resuming a stopped answer uses, and what a tool that
 *   writes its result into the middle of an answer will use.
 * @property sampling replaces the model's default sampling as a whole; null keeps the default.
 *   The model's banned tokens apply either way.
 * @property maxNewTokens the longest reply in tokens; null takes the model's default.
 * @property stopStrings text that ends the reply, in addition to the model's own stop strings.
 *   [GenerationEvent.Done.stopString] reports which one was reached.
 * @property maxContextTokens the size the user chose as the most the model's context may grow
 *   to for this conversation, or null to leave that to the engine (which then also holds it to
 *   what can be read again in reasonable time). The model's own maximum and the device's
 *   memory bound it either way; see [ContextPolicy].
 * @property conversationKey which conversation this is, in the caller's own terms; opaque to the
 *   engine, which only compares it with the key of a saved memory ([InferenceEngine.saveMemory]).
 *   A request for another key than that of the saved memory, or for none, deletes the saved
 *   memory.
 */
data class GenerationRequest(
    val modelId: String,
    val messages: List<PromptMessage>,
    val systemPrompt: String? = null,
    val extraSystemText: String? = null,
    val continueFinalMessage: Boolean = false,
    val sampling: SamplingParams? = null,
    val maxNewTokens: Int? = null,
    val stopStrings: List<String> = emptyList(),
    val maxContextTokens: Int? = null,
    val conversationKey: String? = null,
)

/**
 * What a read-ahead ([InferenceEngine.readAhead]) did.
 *
 * [Read] and [Stopped] are the outcomes that changed the model's memory; [Skipped] left it
 * exactly as it was, and so did a read-ahead whose caller was cancelled before it changed
 * anything (that caller gets no result at all).
 */
sealed interface ReadAheadResult {

    /**
     * The conversation is in memory up to the start of the next message: [keptTokens] of the
     * memory were kept, and [readTokens] were read after them.
     */
    data class Read(val keptTokens: Int, val readTokens: Int) : ReadAheadResult

    /**
     * Something that goes first stopped the read-ahead on the way, after it had kept
     * [keptTokens] of the memory and read [readTokens] after them. What it read stays: the
     * memory is a true state of that many tokens, the beginning of the next request's prompt.
     */
    data class Stopped(val keptTokens: Int, val readTokens: Int) : ReadAheadResult

    /** Nothing was done, for [reason]; the memory is as it was. */
    data class Skipped(val reason: Reason) : ReadAheadResult {
        enum class Reason {
            /** No model is loaded, or another model than the request's. */
            NOT_LOADED,

            /** The model continues text: its next request is the user's text alone, with nothing to prepare. */
            NOT_A_CHAT,

            /** The request continues its last message, or cannot be rendered. */
            INVALID,

            /** The memory is empty or holds another conversation than the request's. */
            OTHER_CONVERSATION,

            /** The next request would keep the whole memory: there is nothing it would read again. */
            NOTHING_TO_READ,

            /** The tokenizer puts no token boundary where the next message begins, so there is no place to read up to. */
            NO_BOUNDARY,

            /** The model cannot go back far enough without clearing its memory. */
            WOULD_CLEAR,

            /** The conversation and its reply would not fit the context as it is: the next request would grow it or leave turns out. */
            NO_ROOM,

            /** Something that goes first wanted the engine before the read-ahead had changed anything. */
            BUSY,

            /** The native side failed; the memory was cleared, as for a failed request. */
            FAILED,
        }
    }
}

/**
 * The inference engine as the rest of the app sees it. Everything above this interface is
 * independent of llama.cpp and can be tested against a fake.
 *
 * The engine assumes nothing about who is calling: no user interface, no lifecycle, no main
 * thread. Every function may be called from any thread or coroutine, by a screen or by a
 * background task, and several collectors may watch [state].
 */
interface InferenceEngine {

    /** The current state. Changes are published in the order they happen. */
    val state: StateFlow<EngineState>

    /**
     * Makes [model] the loaded model and returns when [state] is [EngineState.Ready] or
     * [EngineState.Failed]; it does not throw for a model that fails to load. A different model
     * that is loaded is unloaded first, ending any generation in flight with
     * [FinishReason.CANCELLED]. Loading the model that is already loaded does nothing.
     * Cancelling the caller abandons the load and leaves the engine unloaded.
     */
    suspend fun load(model: ModelSpec)

    /** Frees the loaded model. A generation in flight ends with [FinishReason.CANCELLED]. */
    suspend fun unload()

    /**
     * Generates one reply. The flow is cold: nothing happens until it is collected, and each
     * collection is its own generation. Generations never overlap; a second collection waits for
     * the first to end.
     *
     * The flow never throws for a failure of the engine; it emits [GenerationEvent.Error] and
     * completes. Cancelling the collection stops the generation promptly. The collector then
     * sees no [GenerationEvent.Done] (it is no longer listening), keeps whatever text it had
     * received, and the engine is ready for the next request.
     */
    fun generate(request: GenerationRequest): Flow<GenerationEvent>

    /**
     * Reads the conversation of [request] into the model's memory as the next new message will
     * find it, so that that message reads only itself: the work the next request would do
     * anyway, done earlier, while the person reads the answer. Some prompt formats write the
     * answer being written differently from the same answer once it is history (Qwen3.5 opens
     * the turn with an empty think block and leaves it out of earlier turns), so the memory
     * after an answer and the next request part ways before the answer, and the whole answer
     * is read again.
     *
     * [request] is the conversation as a request for the next message would carry it, ending
     * with the answer; [GenerationRequest.continueFinalMessage] must be false. The engine adds an
     * empty user message in place of the next one, renders and tokenises that, keeps of the
     * memory what a request would keep (the longest common beginning, or the checkpoint before
     * it on a model that cannot cut its memory back), reads up to the start of the placeholder,
     * and keeps a checkpoint there. It samples nothing and emits nothing.
     *
     * It does nothing ([ReadAheadResult.Skipped]) when the next request would keep the whole
     * memory anyway, and never clears the memory, grows the context or leaves turns out for it.
     * It gives way: a request, [forget], [forgetKeepingSavedMemory], a [load], an [unload], a
     * [saveMemory], a [restoreMemory], a closed [GenerationGate] and cancelling the caller each
     * stop it within one chunk of the prompt, and one that is asked for before it has begun
     * makes it do nothing. What it read by then stays.
     *
     * The checkpoint at its end is one of those that follow a reply, so it displaces none of a
     * prompt's; the next request takes it over as the checkpoint of the boundary it stands at.
     * The end-of-reply state is not kept: "Continue" after a read-ahead reads the answer again.
     */
    suspend fun readAhead(request: GenerationRequest): ReadAheadResult

    /**
     * Drops everything the engine and the native layer remember of any conversation, and returns
     * when that is done. The model stays loaded. A generation in flight ends with
     * [FinishReason.CANCELLED].
     *
     * A saved memory the engine knows of ([saveMemory], [restoreMemory]) is deleted as well:
     * this is the forgetting of a conversation that must not come back, such as one whose
     * messages expired. To forget at a lock and keep what was saved for the next unlock, use
     * [forgetKeepingSavedMemory].
     */
    suspend fun forget()

    /**
     * Everything [forget] does, except that a saved memory stays where it is, for the next
     * [restoreMemory]. The engine lets go of the vault: after this it neither deletes the image
     * nor knows of it. The session calls this when it closes, after its own [saveMemory].
     */
    suspend fun forgetKeepingSavedMemory()

    /**
     * How the latest restore went, for whoever shows it: in progress, restored, or read again.
     * Carries no text of the conversation.
     */
    val memoryRestore: StateFlow<MemoryRestoreState>

    /**
     * Saves what the model's memory holds now into [vault], as the memory of the conversation
     * [conversationKey], and returns at once with a reason when it should not:
     * - no model is loaded, or (for [SaveTrigger.LEFT_SCREEN]) the engine is busy with a reply,
     *   a load or anything else but a [readAhead]; for [SaveTrigger.LOCK] the save waits its
     *   turn instead;
     * - the memory is empty, or is not that conversation's (the latest request named another);
     * - the memory was marked with [markNotToBeSaved] and has not been rebuilt from nothing;
     * - reading it again would take less than the engine's least worth saving (about 20 seconds
     *   by the model's reading curve on this device), or nobody knows how long;
     * - for [SaveTrigger.LEFT_SCREEN], reading again what is new since the last save would take
     *   no more than about 10 seconds.
     *
     * Every checkpoint the engine holds goes into the image. The memory itself is only read:
     * whatever the outcome it is as it was. A request that arrives while a save runs stops the
     * save at its next piece and then runs; so do [forget], [unload], a [load] and cancelling the
     * caller. The image becomes the vault's only when every piece was written.
     *
     * A [readAhead] goes second to a save of either trigger: it stops within one chunk, and a
     * save on leaving the screen waits for that instead of answering that the engine is busy.
     * What the read-ahead had read by then is saved.
     */
    suspend fun saveMemory(vault: MemoryVault, conversationKey: String, trigger: SaveTrigger): MemorySaveResult

    /**
     * Reads back the memory that [vault] holds, when it is the memory of [conversationKey] made
     * with the loaded model: called after the model is loaded and before the first request of
     * that conversation. The context is given the size the image needs within the limits it
     * would have for a request naming [maxContextTokens]. Afterwards the memory is ordinary
     * memory, and the next request keeps of it what it has in common with its prompt.
     *
     * Any failure, at any point, leaves the memory empty, and the conversation is then read again
     * as it would have been without a saved memory. The image is deleted, except when nothing
     * speaks against it: when the vault cannot tell now ([MemoryVaultUnavailableException], a
     * key destroyed by a lock), and when [forget], [forgetKeepingSavedMemory], [unload], a [load]
     * or the caller stopped the restore. Such an image, like one of another conversation, is
     * kept until a request comes (which deletes it) or a restore for its own conversation; a
     * [forget] or an [unload] deletes it, [forgetKeepingSavedMemory] lets go of it.
     * [memoryRestore] says what happened.
     */
    suspend fun restoreMemory(vault: MemoryVault, conversationKey: String, maxContextTokens: Int? = null): MemoryRestoreResult

    /**
     * Marks the memory as not to be saved, because part of its conversation must be forgotten
     * (messages that expired): from the moment this is called, no save is made until the memory
     * has been emptied by a request made after the mark, or by [forget], [load] or [unload]; and
     * no saved memory is restored. Returns at once, from any thread, also while a reply runs.
     * The caller marks before it builds any request without the forgotten messages.
     */
    fun markNotToBeSaved()
}
