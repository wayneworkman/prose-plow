package us.theworkmans.proseplow.core.model

import us.theworkmans.proseplow.core.prompt.PeacebellChatMlFormat
import us.theworkmans.proseplow.core.prompt.PlainChatMlFormat

/**
 * The models the app knows. Adding or removing a model is a change to this object and to the
 * build that packages the model file; nothing is ever discovered or downloaded at run time.
 *
 * Every number below is taken from the model's own published files and says which one. The two
 * exceptions are [ModelSpec.contextLength], which is this app's choice for a phone, and
 * [ModelSpec.defaultMaxNewTokens] where the model's files give no usable value; both say so.
 */
object ModelCatalog {

    /**
     * How much context the larger models get on the phone. All three were trained with far more
     * (32,768 tokens and up); the key-value memory for that would run to gigabytes. 4,096 tokens
     * is a few pages of conversation and stays within what a mid-range phone can spare.
     */
    private const val PHONE_CONTEXT: Int = 4096

    /** Reply length where the model's own files give no number suited to [PHONE_CONTEXT]. */
    private const val PHONE_MAX_NEW_TOKENS: Int = 1024

    /**
     * Qwen3.5-0.8B.
     * - Sampling: the model card's "Non-thinking mode for text tasks" line: `temperature=1.0,
     *   top_p=1.00, top_k=20, min_p=0.0, presence_penalty=2.0, repetition_penalty=1.0`. The card
     *   also says this model runs in non-thinking mode by default, which is the only mode the app
     *   uses.
     * - Stop tokens: `<|im_end|>` (248046, the `eos_token` of `tokenizer_config.json`) and
     *   `<|endoftext|>` (248044, its `pad_token`), ids from `added_tokens_decoder`.
     * - Context: 262,144 natively (model card); [PHONE_CONTEXT] here.
     * - Reply length: the card's examples ask a server for 32,768 tokens, which is no guide for a
     *   phone; [PHONE_MAX_NEW_TOKENS] here.
     * - Licence: `license: apache-2.0` in the model card.
     */
    val QWEN35_0_8B: ModelSpec = ModelSpec(
        id = "qwen3.5-0.8b",
        displayName = "Qwen3.5 0.8B",
        description = "A general assistant that speaks many languages. The most capable model here.",
        family = ModelFamily.QWEN35_CHATML,
        ggufFileName = "qwen3.5-0.8b.gguf",
        contextLength = PHONE_CONTEXT,
        defaultMaxNewTokens = PHONE_MAX_NEW_TOKENS,
        sampling = SamplingParams(temperature = 1.0f, topK = 20, topP = 1.0f, minP = 0.0f, repetitionPenalty = 1.0f, presencePenalty = 2.0f),
        stopTokenIds = listOf(248046, 248044),
        supportsSystemPrompt = true,
        sourceRepository = "Qwen/Qwen3.5-0.8B",
        licence = "Apache-2.0",
    )

    /**
     * peacebell-v1-291M.
     * - Sampling: greedy. The model card recommends temperature 0, and `stream_generate` in
     *   `modeling_gpt.py` defaults to `temperature=0.0, top_k=50, top_p=0.9`; the last two only
     *   matter once a user raises the temperature.
     * - Banned token: id 0 (`unk_pad_id` in `config.json`), which `stream_generate` sets to minus
     *   infinity on every step.
     * - Stop token: id 4, `<|im_end|>` (`im_end_id` in `config.json`, `eos_token_id` in
     *   `generation_config.json`), the only token `stream_generate` stops on.
     * - No system prompt: the model card documents alternating user and assistant messages only.
     * - Context: 32,768 trained (`block_size` in `config.json`); [PHONE_CONTEXT] here, because the
     *   key-value memory of this model costs about 67 KB per token.
     * - Reply length: `run_model.py` defaults to 2,048 with the full context behind it;
     *   [PHONE_MAX_NEW_TOKENS] here so that a reply cannot claim half of the phone's context.
     * - Licence: `license: apache-2.0` in the model card. The card reserves the name: a modified
     *   copy of the weights may not be called Peacebell.
     */
    val PEACEBELL_V1_291M: ModelSpec = ModelSpec(
        id = "peacebell-v1-291m",
        displayName = "Peacebell v1 291M",
        description = "Answers questions about the Second World War from memory. Not a general assistant.",
        family = ModelFamily.PEACEBELL_CHATML,
        ggufFileName = "peacebell-v1-291m.gguf",
        contextLength = PHONE_CONTEXT,
        defaultMaxNewTokens = PHONE_MAX_NEW_TOKENS,
        sampling = SamplingParams(temperature = 0.0f, topK = 50, topP = 0.9f),
        stopTokenIds = listOf(PeacebellChatMlFormat.IM_END_ID),
        bannedTokenIds = listOf(0),
        supportsSystemPrompt = false,
        sourceRepository = "wayneworkman2012/peacebell-v1-291M",
        licence = "Apache-2.0",
    )

    /**
     * opencerebral Boris-2-Instruct.
     * - Sampling: `generation_config.json` has `do_sample: true, temperature: 0.7, top_p: 0.9,
     *   repetition_penalty: 1.1`. It leaves `top_k` unset, and the transformers library then
     *   applies its default of 50, so 50 is what the reference actually samples with.
     * - Reply length: `max_new_tokens: 512` in `generation_config.json`.
     * - Context: 2,048, the most the model can address (`max_position_embeddings` in
     *   `config.json`, `model_max_length` in `tokenizer_config.json`).
     * - Stop string: `<|im_end|>`. The model card: it "is ordinary text spanning several tokens,
     *   not an end-of-sequence token, so pass it as a stop string".
     * - Stop token: id 0, `<|endoftext|>` (`eos_token_id` in `generation_config.json`).
     * - System prompt: allowed, because the template renders a `system` message like any other
     *   role. The model card does not say how well the model follows one.
     * - Licence: `license: apache-2.0` in the model card.
     */
    val BORIS_2_INSTRUCT: ModelSpec = ModelSpec(
        id = "boris-2-instruct",
        displayName = "Boris-2-Instruct",
        description = "A very small chat model for everyday questions. Fluent, but often wrong on facts.",
        family = ModelFamily.PLAIN_CHATML,
        ggufFileName = "boris-2-instruct.gguf",
        contextLength = 2048,
        defaultMaxNewTokens = 512,
        sampling = SamplingParams(temperature = 0.7f, topK = 50, topP = 0.9f, repetitionPenalty = 1.1f),
        stopStrings = listOf(PlainChatMlFormat.IM_END),
        stopTokenIds = listOf(0),
        supportsSystemPrompt = true,
        sourceRepository = "opencerebral/Boris-2-Instruct",
        licence = "Apache-2.0",
    )

    /**
     * boris2-peacebell-think: the Peacebell architecture with a 32,768-token byte-level BPE
     * vocabulary. It is still being trained, so it is not bundled and the app does not list it;
     * what is fixed about it is written down here so that shipping it is a one-line change.
     * - Prompt: ChatML as plain text, the same template as Boris-2-Instruct, with `<|im_end|>`
     *   as a stop string because the vocabulary has no such token.
     * - Banned token: id 188, the token of the raw byte 0xFF. Training used it as padding and
     *   never as a target, so its output row is untrained.
     * - Stop token: id 0, `<|endoftext|>`, the vocabulary's only special token.
     * - Sampling: greedy, as for peacebell-v1. The model's own `generation_config.json` does not
     *   exist yet; replace this when it does.
     * - Context: 32,768 trained; [PHONE_CONTEXT] here.
     * - Licence: not stated until the model is published.
     */
    val BORIS2_PEACEBELL_THINK: ModelSpec = ModelSpec(
        id = "boris2-peacebell-think",
        displayName = "Boris2 Peacebell Think",
        description = "A small chat model that is still being trained.",
        family = ModelFamily.PLAIN_CHATML,
        ggufFileName = "boris2-peacebell-think.gguf",
        contextLength = PHONE_CONTEXT,
        defaultMaxNewTokens = PHONE_MAX_NEW_TOKENS,
        sampling = SamplingParams(temperature = 0.0f),
        stopStrings = listOf(PlainChatMlFormat.IM_END),
        stopTokenIds = listOf(0),
        bannedTokenIds = listOf(188),
        supportsSystemPrompt = true,
        bundled = false,
        sourceRepository = "wayneworkman2012/boris2-peacebell-think",
        licence = null,
    )

    /** Every known model, bundled or not, in the order the model picker shows them. */
    val all: List<ModelSpec> = listOf(QWEN35_0_8B, PEACEBELL_V1_291M, BORIS_2_INSTRUCT, BORIS2_PEACEBELL_THINK)

    /** The models whose files ship with the app: the only ones a user can choose. */
    val bundled: List<ModelSpec> = all.filter { it.bundled }

    /** The model a new chat starts with. */
    val default: ModelSpec = QWEN35_0_8B

    /**
     * The model with this id, bundled or not, or null. A saved chat can name a model that a later
     * build no longer ships; callers decide what to do then.
     */
    fun find(id: String): ModelSpec? = all.firstOrNull { it.id == id }
}
