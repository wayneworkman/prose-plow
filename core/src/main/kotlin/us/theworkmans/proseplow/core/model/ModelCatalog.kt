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
     * How much context the larger models get on the phone. Both were trained with far more
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
        maxContextLength = 262_144,
        contextBytesPerToken = 12_288,
        readTokensPerSecond = 90,
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
        maxContextLength = 32_768,
        contextBytesPerToken = 66_560,
        readTokensPerSecond = 184,
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
        maxContextLength = 2048,
        contextBytesPerToken = 63_488,
        readTokensPerSecond = 380,
        licence = "Apache-2.0",
    )

    /**
     * Glint-Research Shard-2: a 49.4M-parameter base model that continues text and can take back
     * what it has just written. It is not a chat model: it has no template, no roles and no
     * system prompt.
     * - Sampling: the defaults of the model's reference script `infer.py`, `--temperature 0.9`
     *   and `--top-k 100`, with no nucleus and no penalties. The model card explains that the
     *   deletions depend on them: with a tighter sampler the model rarely writes a token it
     *   would want to take back.
     * - Deletion token: id 4096, `<bs>` (`backspace_token_id` in `config.json`), the one token
     *   beyond the 4,096 of its tokenizer.
     * - Stop tokens: ids 1 and 2. They are the begin and end markers, and the model's files do
     *   not agree which is which (`tokenizer.json` calls 1 `<bos>` and 2 `<eos>`, `config.json`
     *   has `eos_token_id: 1` and `bos_token_id: 2`). Neither is text, so either ends the reply.
     *   The reference script itself never stops early; it writes a fixed number of tokens.
     * - Reply length: 200 tokens, this app's choice. The reference script's default is 80, a
     *   couple of sentences; "Continue" carries on from wherever the limit fell.
     * - Context: 4,096, the length the model was trained at (`trained_sequence_length` in
     *   `config.json`). Its positions reach 8,192, which the model card calls untested.
     * - Licence: `license: mit` in the metadata of the model card, and nowhere else: the model's
     *   repository has no licence file and no copyright notice.
     * - Not bundled, for that reason: this app ships a model only when its licence is
     *   established. The entry, the architecture and their tests stay, so that switching
     *   [ModelSpec.bundled] back on (together with the model's purpose in
     *   `models/manifest.json`) is all it takes once the repository carries a licence.
     */
    val SHARD_2: ModelSpec = ModelSpec(
        id = "shard-2",
        displayName = "Shard-2",
        description = "Continues the text you write; not a chat model. It can take back words it has just written.",
        family = ModelFamily.COMPLETION,
        ggufFileName = "shard-2.gguf",
        contextLength = 4096,
        defaultMaxNewTokens = 200,
        sampling = SamplingParams(temperature = 0.9f, topK = 100, topP = 1.0f, minP = 0.0f),
        stopTokenIds = listOf(1, 2),
        deletionTokenId = 4096,
        supportsSystemPrompt = false,
        bundled = false,
        sourceRepository = "Glint-Research/Shard-2",
        // Its card calls 4,096 the trained context; the file itself would allow 8,192. The smaller counts.
        maxContextLength = 4096,
        contextBytesPerToken = 40_960,
        licence = "MIT",
    )

    /**
     * Every known model, bundled or not, in the order the model picker shows them. An entry
     * with [ModelSpec.bundled] false, which today is [SHARD_2] alone, is known (a chat can name
     * it, [find] returns it) but is not offered.
     */
    val all: List<ModelSpec> = listOf(QWEN35_0_8B, PEACEBELL_V1_291M, BORIS_2_INSTRUCT, SHARD_2)

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
