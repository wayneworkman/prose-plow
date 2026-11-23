package us.theworkmans.proseplow.core.model

import us.theworkmans.proseplow.core.prompt.CompletionFormat
import us.theworkmans.proseplow.core.prompt.PeacebellChatMlFormat
import us.theworkmans.proseplow.core.prompt.PlainChatMlFormat
import us.theworkmans.proseplow.core.prompt.PromptFormat
import us.theworkmans.proseplow.core.prompt.Qwen35ChatMlFormat

/**
 * The families of bundled models, told apart by how a conversation has to be laid out for them.
 * Models of one family share a [promptFormat]; everything else that differs between two models is
 * a field of [ModelSpec].
 *
 * @property continuesText true for a family that is not a chat model at all: it writes on from
 *   the text it is given. Three things follow, and each part of the app that depends on one of
 *   them reads this property rather than the family's name:
 *   - Only the latest message is the prompt. There is no history to send, and so none to leave
 *     out when the context is short.
 *   - The user's text is kept exactly as typed. Nothing is trimmed from it, because a space at
 *     its end decides what the model writes next.
 *   - The model's text is kept exactly as written, from its first character to its last,
 *     because "Continue" appends to it. A chat model's reply, by contrast, loses the whitespace
 *     around it.
 */
enum class ModelFamily(val promptFormat: PromptFormat, val continuesText: Boolean = false) {
    /** Qwen3.5: ChatML with control tokens, rendered as its Jinja template does. */
    QWEN35_CHATML(Qwen35ChatMlFormat),

    /** peacebell-v1: ChatML whose markers are injected as token ids, each part encoded on its own. */
    PEACEBELL_CHATML(PeacebellChatMlFormat),

    /** ChatML as ordinary text in one string, ended by a stop string: Boris-2-Instruct and alike. */
    PLAIN_CHATML(PlainChatMlFormat),

    /** No layout at all: a base model that continues the user's text, such as Shard-2. */
    COMPLETION(CompletionFormat, continuesText = true),
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
 * @property maxContextLength the context the model was trained with: the most its context can
 *   ever be given. [contextLength] is where a context starts; it grows towards this as a
 *   conversation needs it, as far as the device allows (see the engine's ContextPolicy). A
 *   model that is given none cannot grow.
 * @property contextBytesPerToken the memory one token of context costs, in bytes, for showing
 *   what a context size costs before the model is loaded; 0 when not known. The loaded model
 *   itself is asked when a size is decided on.
 * @property readingTimes how long the model takes to read a conversation of a given length from
 *   nothing on the phone it was measured on, in front; [ReadingTimes.UNKNOWN] when not known.
 *   It bounds how far the context grows by itself and is the time the settings show for each
 *   size; on another phone the engine scales it by how much faster or slower that phone was
 *   seen to read.
 * @property deepReadChunk how many prompt tokens the engine evaluates in one go once the memory
 *   holds a long conversation (see the engine's `Settings.deepReadTokens`); short prompts are
 *   read in the engine's small chunk. Every chunk costs time in proportion to the tokens already
 *   in memory, on top of its tokens' own cost, so small chunks make a long conversation slow to
 *   read: on the tested phone, with 7,168 tokens read, a token cost Qwen3.5 33.7 ms in chunks of
 *   32 and 19.3 ms in chunks of 128, and Peacebell 48.7 ms in chunks of 32 and 14.5 ms in chunks
 *   of 256. A larger chunk has a price only for a model with recurrent state (Qwen3.5, Boris-2):
 *   such a model can be stopped only between chunks, so a stop, a lock, and an unlock that asks
 *   the generation to make way each wait for the chunk in progress; it gets 128 tokens, which
 *   take Qwen3.5 about 1.4 s with nothing read and about 2.5 s with 7,000 read on the tested
 *   phone. A model with attention only is stopped inside a chunk and gets the session's whole
 *   batch, [MAX_EVALUATION_CHUNK]. Never more than that.
 * @property cutsMemoryBack false for a model whose memory cannot be cut back to an earlier
 *   length (a model with recurrent state: Qwen3.5, Boris-2), which can only go back to a
 *   checkpoint. The engine then also keeps two checkpoints that follow a reply while it is
 *   written, so that a next request that differs from the memory near the end of that reply
 *   does not read the whole reply again. A model that can cut its memory back (attention only:
 *   Peacebell, and Shard-2, whose session refuses only cuts behind a restored window) needs no
 *   such checkpoints and is given none. A wrong true costs time and never correctness: the
 *   engine still falls back to the checkpoints of the prompt.
 * @property defaultMaxNewTokens the longest reply, in tokens, when a request does not say. It is
 *   also the room the engine keeps free for the reply when it decides how much history fits.
 * @property sampling the model's reference sampling settings.
 * @property stopStrings text that ends a reply when the model writes it. Needed by models whose
 *   end-of-turn marker is not a token.
 * @property stopTokenIds tokens that end a reply, in addition to whatever the model file itself
 *   declares as end of generation. Listed here so that the reply ends correctly even if the
 *   file's metadata is incomplete.
 * @property bannedTokenIds tokens that must never be sampled.
 * @property deletionTokenId the token with which the model takes back the token it wrote before
 *   it, or null for a model that has none. To the model it is a token like any other: it is
 *   evaluated and stays in the model's memory together with the token it took back. Only the
 *   reader's view changes: the engine drops the last token of the visible reply, never reaching
 *   back beyond the start of the reply, and says so with
 *   [us.theworkmans.proseplow.core.engine.GenerationEvent.TextReplaced].
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
    val deletionTokenId: Int? = null,
    val maxContextLength: Int = contextLength,
    val contextBytesPerToken: Long = 0,
    val readingTimes: ReadingTimes = ReadingTimes.UNKNOWN,
    val deepReadChunk: Int,
    val cutsMemoryBack: Boolean = true,
) {
    init {
        require(id.isNotBlank()) { "A model needs an id" }
        require(contextLength > 0) { "contextLength must be positive" }
        require(defaultMaxNewTokens in 1..contextLength) { "defaultMaxNewTokens must fit in the context" }
        require(deepReadChunk in 1..MAX_EVALUATION_CHUNK) { "deepReadChunk must be between 1 and the session's batch" }
        require(stopStrings.none { it.isEmpty() }) { "A stop string cannot be empty" }
        require(deletionTokenId == null || (deletionTokenId !in stopTokenIds && deletionTokenId !in bannedTokenIds)) {
            "The deletion token cannot also end the reply or be banned"
        }
    }

    /** The layout of a conversation for this model. */
    val promptFormat: PromptFormat get() = family.promptFormat

    companion object {
        /**
         * The most tokens a session evaluates in one go: the batch every session is opened
         * with (`NativeSettings.batchTokens` in the `llama` module, which must agree). No chunk
         * the engine asks for can be larger.
         */
        const val MAX_EVALUATION_CHUNK: Int = 256
    }
}
