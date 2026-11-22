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
)

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
     * Drops everything the engine and the native layer remember of any conversation, and returns
     * when that is done. The model stays loaded. A generation in flight ends with
     * [FinishReason.CANCELLED]. The app calls this when it locks.
     */
    suspend fun forget()
}
