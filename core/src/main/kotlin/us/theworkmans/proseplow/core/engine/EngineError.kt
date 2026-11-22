package us.theworkmans.proseplow.core.engine

/**
 * What can go wrong in the engine, as types the app can turn into its own wording. The `detail`
 * strings are technical and in English; they are for an expandable "details" line, never the
 * message itself, and never contain prompt or reply text.
 */
sealed interface EngineError {

    /** A generation was requested while no model was loaded. */
    data object NotLoaded : EngineError

    /** The request names [requested] but the loaded model is [loaded]. Load the right one first. */
    data class WrongModel(val requested: String, val loaded: String) : EngineError

    /** The model [modelId] could not be loaded. */
    data class LoadFailed(val modelId: String, val detail: String) : EngineError

    /** The request cannot be rendered, for example because it contains no user message. */
    data class InvalidRequest(val detail: String) : EngineError

    /**
     * Even with all older turns left out, the system prompt and the latest user message need
     * [promptTokens] tokens, which leaves no room for a reply in a context of [contextSize].
     */
    data class PromptTooLong(val promptTokens: Int, val contextSize: Int) : EngineError

    /** The model's memory ran out of room although the budget said it would fit. */
    data class ContextFull(val detail: String) : EngineError

    /** The native layer reported a failure; [code] is a [NativeLlamaException] code. */
    data class Native(val code: Int, val detail: String) : EngineError

    /** Anything else, reported instead of crashing. */
    data class Unexpected(val detail: String) : EngineError
}

/** The typed error for a failure the native layer reported during a generation. */
internal fun NativeLlamaException.toEngineError(): EngineError = when (code) {
    NativeLlamaException.CONTEXT_FULL -> EngineError.ContextFull(message.orEmpty())
    else -> EngineError.Native(code, message.orEmpty())
}
