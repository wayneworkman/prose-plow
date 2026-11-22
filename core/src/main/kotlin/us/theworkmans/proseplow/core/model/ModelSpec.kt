package us.theworkmans.proseplow.core.model

import us.theworkmans.proseplow.core.prompt.PeacebellChatMlFormat
import us.theworkmans.proseplow.core.prompt.PlainChatMlFormat
import us.theworkmans.proseplow.core.prompt.PromptFormat
import us.theworkmans.proseplow.core.prompt.Qwen35ChatMlFormat

/**
 * The families of bundled models, told apart by how a conversation has to be laid out for them.
 * Models of one family share a [promptFormat]; everything else that differs between two models is
 * a field of [ModelSpec].
 */
enum class ModelFamily(val promptFormat: PromptFormat) {
    /** Qwen3.5: ChatML with control tokens, rendered as its Jinja template does. */
    QWEN35_CHATML(Qwen35ChatMlFormat),

    /** peacebell-v1: ChatML whose markers are injected as token ids, each part encoded on its own. */
    PEACEBELL_CHATML(PeacebellChatMlFormat),

    /** ChatML as ordinary text in one string, ended by a stop string: Boris-2-Instruct and alike. */
    PLAIN_CHATML(PlainChatMlFormat),
}

/**
 * Everything the app knows about one model. The engine takes all of its per-model behaviour from
 * here, so supporting another model of a known family is a new entry in the [ModelCatalog] and no
 * new code.
 *
 * @property id stable identifier, stored in sessions and on every assistant message. Never reuse
 *   or change one: saved chats refer to it.
 * @property displayName the model's proper name, shown as it is in every language.
 * @property description one short sentence for the model picker.
 * @property family decides the prompt layout.
 * @property ggufFileName name of the model file among the app's assets.
 * @property contextLength the context, in tokens, the model is run with on the phone. This is a
 *   memory and speed decision and usually far below what the model was trained with.
 * @property defaultMaxNewTokens the longest reply, in tokens, when a request does not say. It is
 *   also the room the engine keeps free for the reply when it decides how much history fits.
 * @property sampling the model's reference sampling settings.
 * @property stopStrings text that ends a reply when the model writes it. Needed by models whose
 *   end-of-turn marker is not a token.
 * @property stopTokenIds tokens that end a reply, in addition to whatever the model file itself
 *   declares as end of generation. Listed here so that the reply ends correctly even if the
 *   file's metadata is incomplete.
 * @property bannedTokenIds tokens that must never be sampled.
 * @property supportsSystemPrompt false for a model that was trained without a system message;
 *   the engine then leaves the system prompt out instead of showing the model a role it has
 *   never seen.
 * @property bundled false for a model whose file is not shipped yet. The app lists only bundled
 *   models; the entry exists so that everything else is ready when the file arrives.
 * @property sourceRepository the Hugging Face repository the weights come from, for the About
 *   screen.
 * @property licence SPDX identifier of the weights' licence, for the About screen; null while
 *   the model is unpublished and its licence is not yet stated.
 */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val description: String,
    val family: ModelFamily,
    val ggufFileName: String,
    val contextLength: Int,
    val defaultMaxNewTokens: Int,
    val sampling: SamplingParams,
    val stopStrings: List<String> = emptyList(),
    val stopTokenIds: List<Int> = emptyList(),
    val bannedTokenIds: List<Int> = emptyList(),
    val supportsSystemPrompt: Boolean = true,
    val bundled: Boolean = true,
    val sourceRepository: String,
    val licence: String?,
) {
    init {
        require(id.isNotBlank()) { "A model needs an id" }
        require(contextLength > 0) { "contextLength must be positive" }
        require(defaultMaxNewTokens in 1..contextLength) { "defaultMaxNewTokens must fit in the context" }
        require(stopStrings.none { it.isEmpty() }) { "A stop string cannot be empty" }
    }

    /** The layout of a conversation for this model. */
    val promptFormat: PromptFormat get() = family.promptFormat
}
