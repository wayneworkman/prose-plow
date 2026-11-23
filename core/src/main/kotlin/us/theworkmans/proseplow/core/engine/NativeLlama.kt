package us.theworkmans.proseplow.core.engine

import us.theworkmans.proseplow.core.model.ModelSpec
import java.nio.ByteBuffer

/**
 * The low-level seam between the Kotlin engine and llama.cpp: one loaded model with one context,
 * holding one conversation (llama.cpp sequence id 0).
 *
 * The rule for this interface is "thin native, smart Kotlin". An implementation exposes llama.cpp
 * operations and decides nothing: it never renders a prompt, never looks for a stop string, never
 * trims or shifts the context, never chooses a sampling default, and never retries. Each method
 * below names the llama.cpp calls it stands for; where behaviour is not stated here, do the
 * simplest thing those calls do.
 *
 * Rules that hold for every method:
 * - **Threading.** Calls are made one at a time and never concurrently; the engine issues all of
 *   them from one dispatcher (in the app, one dedicated thread). Every call blocks until its work
 *   is done. An implementation needs no locking and must not call back on any other thread.
 * - **Tokens** are llama.cpp token ids (`llama_token`) passed as `Int`.
 * - **Memory** means the context's memory for sequence 0 (`llama_get_memory`): the tokens that
 *   have been evaluated and can be attended to. Its length is the only position the two sides
 *   share; the implementation always appends at the end.
 * - **Failures** are reported by throwing [NativeLlamaException] with one of its codes and a
 *   short technical message. The message must never contain prompt or reply text. After a
 *   failure the engine assumes nothing about the memory and calls [clearMemory] before its next
 *   use. Passing an argument outside the documented range is a bug in the caller; answer it with
 *   [NativeLlamaException.INVALID_ARGUMENT] rather than undefined behaviour.
 * - **No logging** of token ids, pieces or text.
 * - After [close] no method is called again.
 */
interface NativeLlama {

    /**
     * Tokenises one string: `llama_tokenize(vocab, text, length, out, capacity, add_special =
     * false, parse_special = parseSpecial)`. When the buffer is too small the call returns the
     * negated size needed; allocate that and call again.
     *
     * - [utf8] is standard UTF-8 without a terminating NUL and may contain NUL bytes; pass its
     *   length explicitly. It is a byte array rather than a string on purpose: a JNI string would
     *   arrive as modified UTF-8, which encodes every character outside the basic plane
     *   differently and would tokenise differently.
     * - No beginning-of-sequence, end-of-sequence or any other token is added (`add_special` is
     *   always false). The prompt formats place every such token themselves.
     * - With [parseSpecial] false, text that spells a control token (`<|im_end|>`,
     *   `<|endoftext|>`) must come back as the tokens of its characters, never as the control
     *   token. Message content is always tokenised this way.
     * - With [parseSpecial] true, control tokens spelled in the text become their single ids.
     * - Each call is independent: the result must equal what the model's reference tokenizer
     *   returns for this string encoded on its own. For a SentencePiece model that includes the
     *   word-start piece the reference puts at the beginning of every call.
     * - An empty array in gives an empty array out.
     * - A text of more than [MAX_TOKENIZE_BYTES] bytes is refused, before it is looked at.
     *
     * @throws NativeLlamaException with [NativeLlamaException.TOKENIZE_FAILED].
     */
    fun tokenize(utf8: ByteArray, parseSpecial: Boolean): IntArray

    /**
     * The bytes a token contributes to the output text: `llama_token_to_piece(vocab, token, buffer,
     * capacity, lstrip = 0, special = false)`.
     *
     * The bytes are returned raw. One token can end in the middle of a UTF-8 character, or be a
     * single byte of one; the engine assembles characters, so do not validate, replace or buffer
     * anything here. Control tokens yield an empty array (that is what `special = false` does).
     */
    fun tokenToBytes(token: Int): ByteArray

    /** Whether the model ends its turn with this token: `llama_vocab_is_eog(vocab, token)`. */
    fun isEndOfGeneration(token: Int): Boolean

    /**
     * The number of tokens the memory can hold: `llama_n_ctx(ctx)`, read back from the created
     * context rather than repeated from the request, because llama.cpp may round it.
     */
    fun contextSize(): Int

    /**
     * The model's trained context (`llama_model_n_ctx_train`): the largest size the context can
     * usefully be given. The engine takes the smaller of this and the catalogue's
     * [ModelSpec.maxContextLength].
     */
    val maxContextSize: Int

    /**
     * The memory, in bytes, that a context of [tokens] tokens takes at this session's batch
     * size: what is allocated, which is an upper bound on what becomes resident. Budgets are
     * made of differences between two sizes.
     */
    fun contextBytes(tokens: Int): Long

    /**
     * Gives the context another size. [budgetBytes] is the most the context's memory may occupy
     * at any moment during and after the call; within it the implementation chooses the best
     * way there. Three outcomes:
     *
     * - **Done.** [contextSize] is [tokens]. The memory was carried over when that fitted the
     *   budget and is empty otherwise: ask [memoryTokenCount]. Checkpoint handles are valid
     *   exactly when the memory was kept. No prediction is ready: evaluate before sampling. The
     *   sampler's configuration and the threads are as they were.
     * - **Refused**, with [NativeLlamaException.CONTEXT_REFUSED]: [tokens] is above
     *   [maxContextSize] or would cost more than [budgetBytes]. Nothing was touched.
     * - **Failed**, with [NativeLlamaException.CONTEXT_NOT_CREATED]: the new context could not be
     *   created after the old one was given up. The implementation has then created the old
     *   size again, with its memory if that could be saved, and failing that ever smaller
     *   sizes. [contextSize] says what exists now, and [memoryTokenCount] what it holds; 0
     *   for the size means that no context exists and the model must be loaded again.
     */
    fun resizeContext(tokens: Int, budgetBytes: Long)

    /**
     * The number of tokens currently in memory: `llama_memory_seq_pos_max(mem, 0) + 1`, which is
     * 0 for an empty memory. The engine keeps its own copy of the tokens and uses this to detect
     * that the two have drifted apart.
     */
    fun memoryTokenCount(): Int

    /**
     * Evaluates [tokens], appended in order after the tokens already in memory, and leaves the
     * model's prediction for the token after the last one ready for [sampleNext].
     *
     * Work in chunks of at most [chunkSize] tokens (and at most the context's batch size if that
     * is smaller). For each chunk: fill a batch with `llama_batch_ext_add_token` for sequence 0,
     * give every token its position with `llama_batch_ext_set_pos` (the memory length before the
     * chunk, plus its index), request output only for the very last token of the whole call with
     * `llama_batch_ext_set_output_logits`, and run `llama_process(ctx,
     * LLAMA_PROCESS_TYPE_DECODE, batch)`.
     *
     * Cancellation and progress, both through [observer] and both on the calling thread:
     * - Before each chunk call [EvaluationObserver.isCancelled]; when it returns true, stop.
     * - Where the backend supports it (the CPU backend does, through
     *   `llama_set_abort_callback`), also poll [EvaluationObserver.isCancelled] from the abort
     *   callback so that a long chunk can be interrupted. It is cheap and may be called often.
     * - After each chunk that completed, call [EvaluationObserver.onProgress] with the number of
     *   tokens of this call evaluated so far.
     *
     * Returns the number of tokens of this call that are now in memory. That is `tokens.size`
     * after a complete run and less after a cancellation; an interrupted chunk may have been
     * evaluated in part, so compute the value from [memoryTokenCount] before and after rather
     * than by counting chunks. A prediction is ready for [sampleNext] only after a complete run.
     *
     * [tokens] is never empty and [chunkSize] is at least 1.
     *
     * @throws NativeLlamaException with [NativeLlamaException.CONTEXT_FULL] when `llama_process`
     *   reports that the memory has no room (return value 1), and with
     *   [NativeLlamaException.EVALUATION_FAILED] for every other failure (negative values). An
     *   abort (return value 2) is a cancellation, not a failure.
     */
    fun evaluate(tokens: IntArray, chunkSize: Int, observer: EvaluationObserver): Int

    /**
     * Shortens the memory to its first [tokenCount] tokens: `llama_memory_seq_rm(mem, 0,
     * tokenCount, -1)`.
     *
     * Returns the call's own result. False means this model cannot remove the end of its memory:
     * models with recurrent state (Qwen3.5 among them) can only be cleared or put back to a
     * checkpoint. False is an expected answer, not an error; do not fall back to anything, the
     * engine does that. When false is returned the memory must be exactly as it was.
     *
     * [tokenCount] is between 0 and [memoryTokenCount]. Equal to [memoryTokenCount] it removes
     * nothing and returns true. A prediction that was ready for [sampleNext] is gone after a
     * successful call. Checkpoints are not touched.
     *
     * Never use `llama_memory_seq_add` or `llama_memory_seq_div` here or anywhere else: on
     * Qwen3.5 they abort the process. The engine never needs the context shifted.
     */
    fun rewindTo(tokenCount: Int): Boolean

    /**
     * Saves what is needed to bring the memory back to its present length later, and returns a
     * handle for it: `llama_state_seq_get_size_ext` and `llama_state_seq_get_data_ext` for
     * sequence 0 with `LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY`. Keep the data in native memory,
     * together with the present [memoryTokenCount]; it never crosses into the Java heap.
     *
     * With that flag an attention-only model writes next to nothing and a model with recurrent
     * state writes that state, so the call is cheap to make for every model and the engine makes
     * it without asking which kind it has.
     *
     * Handles are non-negative and are not reused while the model is loaded. Return
     * [NO_CHECKPOINT] when the state could not be saved (out of memory, for instance); the engine
     * carries on without it. The engine keeps only a few checkpoints at a time and drops the
     * rest with [dropCheckpoint].
     */
    fun saveCheckpoint(): Int

    /**
     * Brings the memory back to the length it had when [checkpoint] was saved:
     * `llama_state_seq_set_data_ext` with `LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY`, then
     * `llama_memory_seq_rm(mem, 0, savedTokenCount, -1)` to cut the attention part back to the
     * same length.
     *
     * The engine guarantees the precondition that makes this valid: the memory still begins with
     * the very tokens it held when the checkpoint was saved, and is at least as long.
     *
     * Returns true when [memoryTokenCount] now equals the saved count. Returns false when the
     * handle is unknown or either call failed; the engine then calls [clearMemory]. The
     * checkpoint stays valid after a restore and can be restored again. A prediction that was
     * ready for [sampleNext] is gone.
     */
    fun restoreCheckpoint(checkpoint: Int): Boolean

    /**
     * Frees a checkpoint. An unknown or already dropped handle is ignored. Overwrite the buffer
     * with zeros before freeing it: it is derived from the conversation.
     */
    fun dropCheckpoint(checkpoint: Int)

    /**
     * Forgets the conversation completely. This is what runs when the app locks, so after it
     * nothing derived from the conversation may remain in native memory:
     * - `llama_memory_clear(mem, true)`: the `true` also clears the data buffers, not only the
     *   bookkeeping;
     * - every checkpoint is zeroed and freed, and all handles become invalid;
     * - the sampler forgets the tokens it has seen (`llama_sampler_reset`).
     *
     * Afterwards [memoryTokenCount] is 0. The model stays loaded and the sampler stays configured.
     * A context that had grown is back at the size it was opened with, so the memory a long
     * conversation needed is given back when the conversation is left or the app locks; ask
     * [contextSize].
     */
    fun clearMemory()

    /**
     * Replaces the sampler with a new one built from [config]. A token is then sampled in exactly
     * this order; steps 2 to 4 are a chain (`llama_sampler_chain_init(
     * llama_sampler_chain_default_params())`, then `llama_sampler_chain_add`; free the previous
     * chain):
     *
     * 1. The penalties, computed by the implementation itself from the tokens in memory and not
     *    by `llama_sampler_init_penalties`, which would give all three the same tokens to look at.
     *    [SamplerConfig.repetitionPenalty] covers every token in memory, the prompt as much as
     *    the reply, and a token once however often it occurs: a score below zero is multiplied
     *    by it, any other divided by it. [SamplerConfig.frequencyPenalty] and
     *    [SamplerConfig.presencePenalty] cover the tokens of the reply and no token of the
     *    prompt: the score less the frequency penalty times the number of times the token occurs
     *    in the reply, less the presence penalty when that number is not zero. The reply is what
     *    the memory holds from the length it had when the first token was sampled after this
     *    call. [SamplerConfig.penaltyWindow], when it is shorter than the memory, limits all
     *    three to the latest tokens. Nothing is done when all three penalties are at their
     *    neutral values (1, 0, 0).
     * 2. `llama_sampler_init_logit_bias(n_vocab, count, biases)` giving every id in
     *    [SamplerConfig.bannedTokenIds] a bias of negative infinity, with
     *    `llama_vocab_n_tokens(vocab)` for `n_vocab`. Skip when the list is empty.
     * 3. If [SamplerConfig.temperature] is greater than 0:
     *    `llama_sampler_init_temp(temperature)`; then `llama_sampler_init_top_k(topK)` unless
     *    topK is 0; then `llama_sampler_init_top_p(topP, 1)` unless topP is 1 or more; then
     *    `llama_sampler_init_min_p(minP, 1)` unless minP is 0 or less; then
     *    `llama_sampler_init_dist(seed)` with [SamplerConfig.seed] taken as an unsigned 32-bit
     *    value.
     * 4. Otherwise `llama_sampler_init_greedy()` and none of step 3.
     *
     * The order is the one the models' reference implementations use: penalties and banned
     * tokens act on the raw scores, the temperature comes next, and the truncations see the
     * distribution after the temperature. llama.cpp's own default order applies the temperature
     * last, which gives a different nucleus; do not use it.
     *
     * What each penalty covers is likewise what it covers where the models' settings come from.
     * A repetition penalty in a `generation_config.json` is the transformers library's, which
     * penalises the whole sequence it is shown, prompt included; a presence penalty in a model
     * card is the OpenAI API's, which the servers that implement it compute over the generated
     * tokens. The penalties follow the memory: a token is covered from the moment it is
     * evaluated, and no longer once [rewindTo], [restoreCheckpoint] or [clearMemory] has removed
     * it.
     *
     * The engine calls this once at the start of every reply. Where the reply begins for the
     * presence and frequency penalties is [SamplerConfig.replyStart]: where the first token is
     * sampled after this call, or, for a reply that continues an answer already in memory, the
     * memory position at which that answer began, so that the whole answer is counted.
     */
    fun configureSampler(config: SamplerConfig)

    /**
     * Chooses the next token from the prediction left by the last complete [evaluate]: the
     * penalties of [configureSampler], then what `llama_sampler_sample(chain, ctx, -1)` does. The
     * penalties see the token once it has been evaluated, like every other token in memory.
     *
     * The token is not evaluated: the engine passes it to [evaluate] itself if the reply goes on,
     * and does not if the token ends the reply. The engine calls this only directly after a
     * complete [evaluate], and at most once per [evaluate].
     *
     * @throws NativeLlamaException with [NativeLlamaException.SAMPLING_FAILED].
     */
    fun sampleNext(): Int

    /**
     * The ids of the tokens in memory, in order: the native session's own record of them
     * (`pp_session_memory_tokens`), which the penalties are computed from and which a memory
     * image carries. For a caller that keeps its own copy and has to set it after
     * [importMemory]. An empty array for an empty memory.
     *
     * The ids are the conversation: the caller overwrites the array when it is done with it.
     */
    fun memoryTokens(): IntArray

    /**
     * The size in bytes of the memory image that [exportMemory] would hand to its sink now with
     * the same [checkpoints], the caller's own first bytes not counted
     * (`pp_session_memory_state_bytes`). Nothing is copied to find it. 0 for an empty memory.
     *
     * @throws NativeLlamaException with [NativeLlamaException.INVALID_ARGUMENT] when a checkpoint
     *   is named that [exportMemory] would refuse.
     */
    fun memoryStateBytes(checkpoints: IntArray): Long

    /**
     * Writes the memory out as a memory image, piece by piece, through [buffer] and [sink]
     * (`pp_session_export_memory`). An image holds the record of the tokens in memory, the state
     * of the sequence as llama.cpp writes it, and the [checkpoints] named, each with the length
     * of memory it belongs to; not the sampler and not a prediction. It is only ever read back
     * by the same build on the same model file, which is the caller's to record and compare.
     *
     * **The buffer.** [buffer] is the caller's and must be a direct, writable [ByteBuffer] of at
     * least [MEMORY_PIECE_BYTES_MIN] bytes: its memory is handed to the native session as it is,
     * so that no byte of the state is ever copied into another array. (On Android a direct
     * buffer is itself an array of the runtime, but one that is never moved, so the bytes are
     * overwritten where they lie and no copy is left behind by the garbage collector.) The whole
     * capacity is used, from index 0, whatever the buffer's position and limit, and these are
     * neither read nor changed. A piece is the capacity, except the last, which has from 1 byte
     * up to it.
     *
     * **The caller's own first bytes.** The first [firstPieceOffset] bytes of the buffer are the
     * caller's when the call is made (a header of its own) and are left as they are; the image
     * begins behind them and they go out as the start of the first piece, so that every piece
     * the caller seals has the same size. 0 for none, and below the capacity.
     *
     * **The sink** is called once for every piece, on the calling thread, before this returns:
     * see [MemorySink.write]. Returning false stops the export, which then throws
     * [NativeLlamaException.MEMORY_STOPPED]. An exception thrown by the sink stops it the same
     * way and comes out of this call as itself, after the native side has finished.
     *
     * **What is left behind.** The memory is only read: whatever the outcome, the memory, every
     * checkpoint, the record of the tokens, the sampler and a prediction that was ready are as
     * they were. The whole buffer, the caller's own first bytes included, has been overwritten
     * with zeros when this returns or throws, so the caller writes its header again for the next
     * export. What the sink copied elsewhere is the caller's to wipe.
     *
     * Each of [checkpoints] must be known, named once and belong to a length the memory still
     * has; at most [MAX_MEMORY_CHECKPOINTS] of them, in the order [importMemory] will report them.
     *
     * Returns the bytes of the image handed over, which is [memoryStateBytes] for the same
     * checkpoints. What was handed over before an exception is not an image and is discarded.
     *
     * @throws NativeLlamaException with [NativeLlamaException.MEMORY_STOPPED] when the sink
     *   returned false, [NativeLlamaException.INVALID_ARGUMENT] before the sink is called for an
     *   empty memory, a checkpoint as above or a buffer as above,
     *   [NativeLlamaException.OUT_OF_MEMORY] when the session has no context, and
     *   [NativeLlamaException.MEMORY_FAILED] when the state could not be written.
     */
    fun exportMemory(checkpoints: IntArray, buffer: ByteBuffer, firstPieceOffset: Int, sink: MemorySink): Long

    /**
     * Reads a memory image back (`pp_session_import_memory`): afterwards the memory holds the
     * tokens it held when the image was written, the record of them is the same, and the image's
     * checkpoints are checkpoints of this session under new handles, returned in the order they
     * were named in at the export. No prediction is ready: evaluate at least one token before
     * sampling, as after a restored checkpoint.
     *
     * [buffer] and [firstPieceOffset] are as for [exportMemory]: the [source] fills the buffer
     * from index 0 (see [MemorySource.read]), and the first [firstPieceOffset] bytes of the first
     * piece are the caller's own and skipped. The whole buffer has been overwritten with zeros
     * when this returns or throws.
     *
     * The memory must be empty and no checkpoint held (as after [clearMemory]), and the context
     * must have room for the image's tokens: give it its size with [resizeContext] first, from
     * the number of tokens recorded beside the image.
     *
     * **What the caller guarantees.** The bytes are, complete and in order, what one
     * [exportMemory] of this same build wrote for this same model file, and nobody could have
     * changed them since: every piece is authenticated before the source returns it, and the
     * build and the model are compared before this is called. llama.cpp believes much of what a
     * state says, and a changed state can end the process at a later call. That is a condition,
     * not advice.
     *
     * **All or nothing.** After any exception, at whatever byte the import ended, the memory is
     * empty with its buffers overwritten, the record of the tokens is empty, and no checkpoint is
     * held: the caller reads the conversation again. An exception thrown by the source stops the
     * import the same way as a negative answer and comes out of this call as itself, after the
     * native side has wound down.
     *
     * @throws NativeLlamaException with [NativeLlamaException.MEMORY_STOPPED] when the source
     *   returned a negative number, [NativeLlamaException.CONTEXT_FULL] when the image holds more
     *   tokens than the context has room for, [NativeLlamaException.MEMORY_FAILED] when the
     *   image ended early, went on after its end or is not one this session accepts,
     *   [NativeLlamaException.OUT_OF_MEMORY] when the session has no context or a checkpoint
     *   could not be allocated, and [NativeLlamaException.INVALID_ARGUMENT], with nothing touched
     *   and the source never called, when the memory is not empty, a checkpoint is held, or the
     *   buffer or the offset is outside what is stated above.
     */
    fun importMemory(buffer: ByteBuffer, firstPieceOffset: Int, source: MemorySource): List<MemoryCheckpoint>

    /**
     * Frees everything: the sampler, the checkpoints (zeroed first), the context and the model.
     * Calling it twice is harmless.
     */
    fun close()

    companion object {
        /** What [saveCheckpoint] returns when no checkpoint could be saved. */
        const val NO_CHECKPOINT: Int = -1

        /**
         * The smallest buffer [exportMemory] and [importMemory] take for the pieces of a memory
         * image, in bytes: `PP_MEMORY_PIECE_BYTES_MIN` of the native session. There is no
         * largest: the buffer is the caller's and nothing is allocated to match it.
         */
        const val MEMORY_PIECE_BYTES_MIN: Int = 4096

        /**
         * The most checkpoints a session holds, and so the most a memory image carries:
         * `PP_CHECKPOINTS_MAX` of the native session.
         */
        const val MAX_MEMORY_CHECKPOINTS: Int = 8

        /**
         * The longest text [tokenize] accepts, in bytes: `PP_TOKENIZE_BYTES_MAX` of the native
         * session (256 KiB), repeated here so that the engine can keep every text it hands over
         * within it. Tokenising cannot be interrupted, and a lock has to wait for it.
         */
        const val MAX_TOKENIZE_BYTES: Int = 262_144
    }
}

/** The two callbacks of [NativeLlama.evaluate]. Both are called on the thread that called it. */
interface EvaluationObserver {

    /**
     * Called after each completed chunk with the number of tokens of the current
     * [NativeLlama.evaluate] call that have been evaluated so far.
     */
    fun onProgress(evaluatedTokens: Int)

    /** True when the evaluation should stop as soon as it can. Cheap; may be polled often. */
    fun isCancelled(): Boolean
}

/**
 * The sampler the engine asks for, already resolved: every value is concrete and none is a
 * "use the default" marker. [NativeLlama.configureSampler] says how each field is applied.
 *
 * @property temperature 0 or less selects greedy decoding.
 * @property topK 0 disables it.
 * @property topP 1 or more disables it.
 * @property minP 0 or less disables it.
 * @property repetitionPenalty 1 is neutral.
 * @property presencePenalty 0 is neutral.
 * @property frequencyPenalty 0 is neutral.
 * @property penaltyWindow how many of the latest tokens in memory the penalties look at:
 *   positive, or [NO_PENALTY_WINDOW]. The engine passes the latter: the references the models'
 *   settings come from penalise over the whole conversation, whatever size the context has.
 * @property replyStart where the reply began, for the presence and frequency penalties (the
 *   repetition penalty covers the whole memory regardless): [REPLY_START_AT_SAMPLING] for where
 *   the first token is sampled after [NativeLlama.configureSampler], or the position in memory
 *   (0 or more) at which the answer began, for a reply that continues an answer already in
 *   memory, so that its first part is counted too. A position beyond the memory counts nothing
 *   until the memory reaches it; clearing the memory ends the reply, and the next one begins
 *   where sampling begins. Anything below -1 is refused by the native side with
 *   [NativeLlamaException.INVALID_ARGUMENT]. This is `pp_sampler_config.reply_start`.
 * @property seed the seed of the random choice; its 32 bits are read as unsigned.
 * @property bannedTokenIds tokens that must never be returned by [NativeLlama.sampleNext].
 */
data class SamplerConfig(
    val temperature: Float,
    val topK: Int,
    val topP: Float,
    val minP: Float,
    val repetitionPenalty: Float,
    val presencePenalty: Float,
    val frequencyPenalty: Float,
    val penaltyWindow: Int,
    val seed: Int,
    val bannedTokenIds: List<Int>,
    val replyStart: Int = REPLY_START_AT_SAMPLING,
) {
    companion object {
        /** The whole memory counts for the penalties: llama.cpp's own -1. No other value below 1 is accepted. */
        const val NO_PENALTY_WINDOW: Int = -1

        /**
         * The reply begins where the first token is sampled after the sampler is configured: the
         * native `PP_REPLY_START_AT_SAMPLING` (-1). Every reply that does not continue an answer
         * already in memory uses it, and so does one whose answer has no usable position.
         */
        const val REPLY_START_AT_SAMPLING: Int = -1
    }
}

/**
 * Where the pieces of an exported memory image go ([NativeLlama.exportMemory]). Called on the
 * thread that called the export, before it returns.
 */
fun interface MemorySink {

    /**
     * A piece is ready in the export's buffer at indices 0 until [bytes]. [bytes] is the buffer's
     * capacity for every piece but the last, which has from 1 byte up to it, and it counts the
     * caller's own first bytes in the first piece. [last] is true for the piece that ends the
     * image, exactly once and only after every byte before it was handed over.
     *
     * The piece must be used (sealed and written, for instance) before this returns: the buffer
     * is filled again afterwards. The callback may set the buffer's position and limit to do so;
     * it must not call the [NativeLlama] it was called for. Return true to go on, false to stop
     * the export.
     */
    fun write(bytes: Int, last: Boolean): Boolean
}

/**
 * Where the pieces of a memory image to import come from ([NativeLlama.importMemory]). Called
 * on the thread that called the import, before it returns.
 */
fun interface MemorySource {

    /**
     * Puts the next bytes of the image at the start of the import's buffer (index 0 on) and
     * returns how many: from 1 up to the buffer's capacity. Pieces need not be full, but they are
     * the image's bytes in order with none left out, the caller's own first bytes at the start of
     * the first. Returns 0 when there are no more bytes, and a negative number when the bytes
     * cannot be had or the import should stop. It is called once more after the image's last
     * byte and must return 0 then: an image with anything behind it is not accepted.
     *
     * The callback may set the buffer's position and limit to fill it; it must not call the
     * [NativeLlama] it was called for.
     */
    fun read(): Int
}

/**
 * A checkpoint that [NativeLlama.importMemory] brought back.
 *
 * @property handle its handle in this session, as [NativeLlama.saveCheckpoint] returns them.
 * @property tokenCount the length of memory it belongs to: what [NativeLlama.memoryTokenCount]
 *   was when it was saved.
 */
data class MemoryCheckpoint(val handle: Int, val tokenCount: Int)

/** Receives the progress of a model load. */
fun interface LoadProgress {

    /**
     * Called with the fraction loaded, from 0 to 1 (llama.cpp's `progress_callback`). Return
     * false to abandon the load.
     */
    fun onProgress(fraction: Float): Boolean
}

/** Loads models. The app's implementation owns everything Android about that. */
fun interface NativeLlamaFactory {

    /**
     * Loads [model] and creates its context; blocks until done. Only one model is loaded at a
     * time: the engine closes the previous [NativeLlama] before it calls this.
     *
     * The implementation finds the file named [ModelSpec.ggufFileName], chooses threads and
     * backend, and creates the context with the smaller of [contextTokens] and the model's
     * trained context as `n_ctx`: the size the context begins with. [budgetBytes] is what that
     * context may cost. When it would cost more, nothing is created and this throws
     * [NativeLlamaException] with [NativeLlamaException.CONTEXT_REFUSED]; the caller may ask
     * again for less. Everything else about the model's behaviour reaches the implementation
     * later through the [NativeLlama] calls.
     *
     * [progress] is called on the calling thread. When it returns false the load is abandoned
     * (return false from llama.cpp's `progress_callback`) and this method throws
     * [NativeLlamaException] with [NativeLlamaException.LOAD_CANCELLED].
     *
     * @throws NativeLlamaException with [NativeLlamaException.LOAD_FAILED] when the model cannot
     *   be loaded, [NativeLlamaException.OUT_OF_MEMORY] when that is known to be the reason,
     *   [NativeLlamaException.CONTEXT_REFUSED] when the opening context is above the budget.
     */
    fun open(model: ModelSpec, progress: LoadProgress, contextTokens: Int, budgetBytes: Long): NativeLlama
}

/**
 * A failure reported by the native layer.
 *
 * @property code one of the constants below. A value the engine does not know is reported to the
 *   user as a generic native failure, so new codes can be added on the native side first.
 * @param message a short technical description for the error screen. Never prompt or reply text.
 */
class NativeLlamaException(val code: Int, message: String) : RuntimeException(message) {

    companion object {
        /** The model file is missing, unreadable or not a model llama.cpp accepts. */
        const val LOAD_FAILED: Int = 1

        /** The load was abandoned because [LoadProgress.onProgress] returned false. */
        const val LOAD_CANCELLED: Int = 2

        /** `llama_tokenize` failed. */
        const val TOKENIZE_FAILED: Int = 3

        /** `llama_process` found no room in the memory (its return value 1). */
        const val CONTEXT_FULL: Int = 4

        /** `llama_process` failed for any other reason. */
        const val EVALUATION_FAILED: Int = 5

        /** The sampler could not produce a token. */
        const val SAMPLING_FAILED: Int = 6

        /** An allocation failed. */
        const val OUT_OF_MEMORY: Int = 7

        /** The caller broke a documented precondition. */
        const val INVALID_ARGUMENT: Int = 8

        /** A context of the size asked for was not created, and nothing was touched: above the model's maximum, or above the budget. */
        const val CONTEXT_REFUSED: Int = 9

        /** The context could not be given its new size after the old one was given up; see [NativeLlama.resizeContext]. */
        const val CONTEXT_NOT_CREATED: Int = 10

        /**
         * The caller's callback ended an export or an import of the memory: the [MemorySink]
         * returned false, or the [MemorySource] a negative number. See [NativeLlama.exportMemory]
         * and [NativeLlama.importMemory].
         */
        const val MEMORY_STOPPED: Int = 11

        /**
         * A memory image could not be written out, or what was read is not an image this
         * session accepts: it ended early, went on after its end or failed one of the native
         * session's checks. See [NativeLlama.importMemory].
         */
        const val MEMORY_FAILED: Int = 12
    }
}
