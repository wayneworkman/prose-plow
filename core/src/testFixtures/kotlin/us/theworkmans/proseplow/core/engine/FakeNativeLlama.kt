package us.theworkmans.proseplow.core.engine

import us.theworkmans.proseplow.core.model.ModelSpec
import java.io.ByteArrayOutputStream

/**
 * A [NativeLlama] without a model, for testing everything above the native seam. It follows the
 * seam's contract to the letter and fails loudly when the engine breaks it (sampling without a
 * fresh evaluation, restoring a checkpoint over a memory that has changed underneath it), so a
 * test that passes against it says something about the real thing.
 *
 * **The toy tokenizer.** Ordinary text is tokenised byte by byte: the byte `b` is the token
 * [byteToken]`(b)`, which keeps the low ids free for the special ids real models use. Control
 * tokens are registered with [addControl]; they are recognised in text only when special parsing
 * is on, exactly like the real tokenizer, and they contribute no text to a reply. Because every
 * byte is its own token, a character of several bytes is naturally spread over several tokens.
 *
 * **Scripted replies.** [enqueueReply] queues the tokens the model will "sample" in one
 * generation; when a script runs out the model ends its turn with [endToken]. A script may
 * contain tokens made with [addPiece], which stand for multi-byte tokens of a real vocabulary:
 * the tokenizer never produces them, so a reply written with them does not re-tokenise to the
 * same ids. That is the situation in which prefix reuse has to rewind.
 *
 * **Recording.** Every call that changes state is appended to [calls], and [threads] collects the
 * threads the calls came from.
 *
 * Not thread-safe, like the interface it implements.
 *
 * @param contextTokens what [contextSize] reports; evaluating beyond it fails with
 *   [NativeLlamaException.CONTEXT_FULL].
 */
class FakeNativeLlama(var contextTokens: Int = 4096) : NativeLlama {

    /** A recorded call with its arguments. */
    sealed interface Call {
        data class Tokenize(val text: String, val parseSpecial: Boolean) : Call
        data class Evaluate(val tokens: List<Int>) : Call
        data class RewindTo(val tokenCount: Int) : Call
        data object SaveCheckpoint : Call
        data class RestoreCheckpoint(val checkpoint: Int) : Call
        data class DropCheckpoint(val checkpoint: Int) : Call
        data object ClearMemory : Call
        data class ConfigureSampler(val config: SamplerConfig) : Call
        data object SampleNext : Call
        data object Close : Call
    }

    // --- Switches a test can flip ---

    /** True makes [rewindTo] refuse, as a model with recurrent state does. */
    var refuseRewind: Boolean = false

    /** False makes [saveCheckpoint] return [NativeLlama.NO_CHECKPOINT]. */
    var checkpointsAvailable: Boolean = true

    /** True makes [restoreCheckpoint] report failure. */
    var failRestore: Boolean = false

    /**
     * Runs at the start of every recorded call. A test injects a failure by throwing from here,
     * or cancels a coroutine "from another thread" at a chosen moment.
     */
    var beforeCall: (Call) -> Unit = {}

    /** Runs after each evaluated chunk with the number of tokens then in memory. */
    var afterChunk: (tokensInMemory: Int) -> Unit = {}

    /** Virtual time: every evaluated token moves [nanos] on by this much. */
    var nanosPerToken: Long = 0

    /**
     * Virtual time that grows with depth, as attention does: every evaluated token moves
     * [nanos] on by this much more for every token already in memory before it. With both set,
     * a token read after `d` others costs `nanosPerToken + d * nanosPerTokenPerDepth`.
     */
    var nanosPerTokenPerDepth: Long = 0

    /** One call of [evaluate]: the chunk size asked for, and the tokens in memory before it and once all its tokens are read. */
    data class Evaluation(val chunkSize: Int, val from: Int, val to: Int)

    private val evaluationsMade = ArrayList<Evaluation>()

    /** Every call of [evaluate] since the last [clearCalls], in order. */
    val evaluations: List<Evaluation> get() = evaluationsMade

    /** The virtual clock; pass `{ fake.nanos }` to the engine as its time source. */
    var nanos: Long = 0

    // --- What a test can look at ---

    private val recorded = ArrayList<Call>()
    val calls: List<Call> get() = recorded

    private val callerThreads = LinkedHashSet<Thread>()
    val threads: Set<Thread> get() = callerThreads

    private val memoryTokens = ArrayList<Int>()

    /** The tokens in memory. */
    val memory: List<Int> get() = memoryTokens

    private val savedCheckpoints = LinkedHashMap<Int, List<Int>>()

    /** The checkpoints that exist, as handle to the memory length they were saved at. */
    val checkpoints: Map<Int, Int> get() = savedCheckpoints.mapValues { it.value.size }

    /** The sampler configuration of the current generation, or null before the first. */
    var sampler: SamplerConfig? = null
        private set

    /**
     * Where the current reply began for the presence and frequency penalties, as last configured
     * ([SamplerConfig.replyStart]), or null before the first configuration.
     */
    val replyStart: Int? get() = sampler?.replyStart

    var closed: Boolean = false
        private set

    /** All tokens passed to [evaluate] so far, flattened, including those of generated replies. */
    val evaluatedTokens: List<Int> get() = recorded.filterIsInstance<Call.Evaluate>().flatMap { it.tokens }

    private val tokenizeInputs = ArrayList<ByteArray>()
    private val tokenizeOutputs = ArrayList<IntArray>()
    private val evaluateInputs = ArrayList<IntArray>()

    /**
     * The very arrays the caller handed to [tokenize] and [evaluate], and those [tokenize] handed
     * back, kept by reference (not copied, unlike [calls]) so that a test can see whether the
     * caller overwrote them afterwards. Kept until [clearCalls].
     */
    val textArraysReceived: List<ByteArray> get() = tokenizeInputs
    val tokenArraysReturned: List<IntArray> get() = tokenizeOutputs
    val tokenArraysReceived: List<IntArray> get() = evaluateInputs

    /** Forgets the recorded calls (not the memory), to look only at what happens next. */
    fun clearCalls() {
        recorded.clear()
        evaluationsMade.clear()
        tokenizeInputs.clear()
        tokenizeOutputs.clear()
        evaluateInputs.clear()
    }

    // --- Vocabulary ---

    private class Control(val id: Int, val text: String, val bytes: ByteArray)

    private val controls = ArrayList<Control>()
    private val pieces = HashMap<Int, ByteArray>()
    private val endOfGeneration = HashSet<Int>()
    private var nextId = FIRST_FREE_ID

    /** The token of the single byte [byte] (0 to 255). */
    fun byteToken(byte: Int): Int = BYTE_BASE + (byte and 0xFF)

    /** The tokens the toy tokenizer gives ordinary text: one per UTF-8 byte. */
    fun textTokens(text: String): List<Int> = text.toByteArray(Charsets.UTF_8).map { byteToken(it.toInt()) }

    /**
     * Registers a control token spelled [text]. It is matched in text only when special parsing
     * is on and yields no bytes in a reply. [id] may be any id not yet in use; the default takes
     * the next free one. With [endOfGeneration] the model ends its turn with it.
     */
    fun addControl(text: String, id: Int = nextId++, endOfGeneration: Boolean = false): Int {
        require(text.isNotEmpty()) { "A control token needs a spelling" }
        require(id !in BYTE_BASE until BYTE_BASE + 256 && controls.none { it.id == id } && id !in pieces) { "Token id $id is in use" }
        controls += Control(id, text, text.toByteArray(Charsets.UTF_8))
        // Longest first, so that a marker is not mistaken for a shorter one it begins with.
        controls.sortByDescending { it.bytes.size }
        if (endOfGeneration) this.endOfGeneration += id
        return id
    }

    /** Marks an arbitrary id as ending the model's turn, for ids that are injected and never spelled. */
    fun markEndOfGeneration(id: Int) {
        endOfGeneration += id
    }

    /** A token for scripts that carries [bytes] at once, whole characters or not. */
    fun addPiece(bytes: ByteArray): Int {
        val id = nextId++
        pieces[id] = bytes.copyOf()
        return id
    }

    /** A token for scripts that carries the UTF-8 bytes of [text]. */
    fun addPiece(text: String): Int = addPiece(text.toByteArray(Charsets.UTF_8))

    /** The token the model ends its turn with when a script runs out. */
    val endToken: Int = addControl("<|fake_end|>", endOfGeneration = true)

    /** Tokens as text, with control tokens written out; for readable assertions. */
    fun render(tokens: List<Int>): String {
        val bytes = ByteArrayOutputStream()
        for (token in tokens) {
            val control = controls.firstOrNull { it.id == token }
            bytes.write(control?.bytes ?: pieces[token] ?: byteOf(token) ?: "<$token>".toByteArray(Charsets.UTF_8))
        }
        return bytes.toString(Charsets.UTF_8)
    }

    /** The memory as text, with control tokens written out. */
    val memoryText: String get() = render(memoryTokens)

    private fun byteOf(token: Int): ByteArray? =
        if (token in BYTE_BASE until BYTE_BASE + 256) byteArrayOf((token - BYTE_BASE).toByte()) else null

    // --- Scripts ---

    private val replies = ArrayDeque<List<Int>>()
    private var script = ArrayDeque<Int>()

    /** Queues the tokens of one generation. The turn ends with [endToken] after the last one. */
    fun enqueueReply(tokens: List<Int>) {
        replies += tokens.toList()
    }

    /** Queues a reply spelled byte by byte, the way the tokenizer itself would encode it. */
    fun enqueueReply(text: String) = enqueueReply(textTokens(text))

    // --- NativeLlama ---

    private var predictionReady = false

    private fun enter(call: Call) {
        check(!closed) { "$call after close" }
        callerThreads += Thread.currentThread()
        recorded += call
        beforeCall(call)
    }

    override fun tokenize(utf8: ByteArray, parseSpecial: Boolean): IntArray {
        enter(Call.Tokenize(String(utf8, Charsets.UTF_8), parseSpecial))
        tokenizeInputs += utf8
        // As the native session: a text beyond the limit is refused before it is looked at.
        if (utf8.size > NativeLlama.MAX_TOKENIZE_BYTES) {
            throw NativeLlamaException(NativeLlamaException.TOKENIZE_FAILED, "Text of ${utf8.size} bytes is longer than ${NativeLlama.MAX_TOKENIZE_BYTES}")
        }
        val out = ArrayList<Int>(utf8.size)
        var index = 0
        while (index < utf8.size) {
            val control = if (parseSpecial) controls.firstOrNull { startsWith(utf8, index, it.bytes) } else null
            if (control != null) {
                out += control.id
                index += control.bytes.size
            } else {
                out += byteToken(utf8[index].toInt())
                index++
            }
        }
        return out.toIntArray().also { tokenizeOutputs += it }
    }

    private fun startsWith(data: ByteArray, at: Int, prefix: ByteArray): Boolean {
        if (at + prefix.size > data.size) return false
        for (offset in prefix.indices) if (data[at + offset] != prefix[offset]) return false
        return true
    }

    override fun tokenToBytes(token: Int): ByteArray {
        check(!closed) { "tokenToBytes after close" }
        return pieces[token]?.copyOf() ?: byteOf(token) ?: ByteArray(0)
    }

    override fun isEndOfGeneration(token: Int): Boolean = token in endOfGeneration

    override fun contextSize(): Int = contextTokens

    /** The size the context was opened with, which [clearMemory] returns it to. */
    var openingTokens: Int = contextTokens

    /** What [maxContextSize] reports: the model's trained context. A fake that is given nothing cannot grow. */
    var trainedContextTokens: Int = contextTokens

    /** The memory one token of context costs; [contextBytes] is this many times the size, on top of [reservedBytes]. */
    var bytesPerToken: Long = 0

    /** What a context costs whatever its size, as a real session's reservations do. */
    var reservedBytes: Long = 0

    /**
     * What a context of so many tokens costs, in place of [reservedBytes] plus [bytesPerToken]
     * times the size; null for that line. For a cost that is not a straight line, such as the
     * real session's margin on sizes it has not created.
     */
    var contextCost: ((tokens: Int) -> Long)? = null

    /** When false, the session does not know what a context costs: [contextBytes] says so and every resize is refused. */
    var costKnown: Boolean = true

    /** When false, a resize comes back with an empty memory, as when carrying it over did not fit the budget. */
    var carriesMemoryOnResize: Boolean = true

    /**
     * Sizes from this one on cannot be created although they fit the budget: a resize to one of
     * them fails after the old context was given up, and [contextAfterFailedResize] is what
     * exists afterwards (the old size with its memory when null).
     */
    var cannotCreateFrom: Int? = null
    var contextAfterFailedResize: Int? = null

    /** Every resize asked for, as (tokens, budget), refused ones included. */
    val resizes = ArrayList<Pair<Int, Long>>()

    override val maxContextSize: Int get() = trainedContextTokens

    override fun contextBytes(tokens: Int): Long {
        if (tokens < 1 || tokens > trainedContextTokens) {
            throw NativeLlamaException(NativeLlamaException.INVALID_ARGUMENT, "a context of $tokens tokens has no cost")
        }
        if (!costKnown) throw NativeLlamaException(NativeLlamaException.CONTEXT_REFUSED, "what a context costs is not known")
        return contextCost?.invoke(tokens) ?: (reservedBytes + tokens * bytesPerToken)
    }

    override fun resizeContext(tokens: Int, budgetBytes: Long) {
        callerThreads += Thread.currentThread()
        check(!closed) { "resizeContext after close" }
        resizes += tokens to budgetBytes
        if (tokens > trainedContextTokens || !costKnown || contextBytes(tokens) > budgetBytes) {
            throw NativeLlamaException(NativeLlamaException.CONTEXT_REFUSED, "a context of $tokens tokens is refused")
        }
        val impossible = cannotCreateFrom
        if (impossible != null && tokens >= impossible) {
            val left = contextAfterFailedResize
            if (left != null) {
                // The old size could not be restored with its memory: something smaller, and empty.
                contextTokens = left
                memoryTokens.clear()
                savedCheckpoints.clear()
            }
            predictionReady = false
            throw NativeLlamaException(NativeLlamaException.CONTEXT_NOT_CREATED, "a context of $tokens tokens could not be created")
        }
        require(tokens >= memoryTokens.size || !carriesMemoryOnResize) { "a context of $tokens tokens cannot hold the ${memoryTokens.size} in memory" }
        contextTokens = tokens
        if (!carriesMemoryOnResize) {
            memoryTokens.clear()
            savedCheckpoints.clear()
        }
        predictionReady = false
    }

    override fun memoryTokenCount(): Int = memoryTokens.size

    override fun evaluate(tokens: IntArray, chunkSize: Int, observer: EvaluationObserver): Int {
        enter(Call.Evaluate(tokens.toList()))
        evaluateInputs += tokens
        require(tokens.isNotEmpty()) { "evaluate needs at least one token" }
        require(chunkSize >= 1) { "chunkSize must be at least 1" }
        evaluationsMade += Evaluation(chunkSize, memoryTokens.size, memoryTokens.size + tokens.size)
        predictionReady = false
        var done = 0
        while (done < tokens.size) {
            if (observer.isCancelled()) return done
            val count = minOf(chunkSize, tokens.size - done)
            if (memoryTokens.size + count > contextTokens) {
                throw NativeLlamaException(NativeLlamaException.CONTEXT_FULL, "no room for $count more tokens")
            }
            for (offset in 0 until count) {
                nanos += nanosPerToken + memoryTokens.size * nanosPerTokenPerDepth
                memoryTokens += tokens[done + offset]
            }
            done += count
            observer.onProgress(done)
            afterChunk(memoryTokens.size)
        }
        predictionReady = true
        return done
    }

    override fun rewindTo(tokenCount: Int): Boolean {
        enter(Call.RewindTo(tokenCount))
        require(tokenCount in 0..memoryTokens.size) { "rewindTo($tokenCount) with ${memoryTokens.size} tokens in memory" }
        if (tokenCount == memoryTokens.size) return true
        if (refuseRewind) return false
        memoryTokens.subList(tokenCount, memoryTokens.size).clear()
        predictionReady = false
        return true
    }

    private var nextCheckpoint = 0

    override fun saveCheckpoint(): Int {
        enter(Call.SaveCheckpoint)
        if (!checkpointsAvailable) return NativeLlama.NO_CHECKPOINT
        val handle = nextCheckpoint++
        savedCheckpoints[handle] = memoryTokens.toList()
        return handle
    }

    override fun restoreCheckpoint(checkpoint: Int): Boolean {
        enter(Call.RestoreCheckpoint(checkpoint))
        val saved = savedCheckpoints[checkpoint]
        if (saved == null || failRestore) return false
        check(memoryTokens.size >= saved.size && memoryTokens.subList(0, saved.size) == saved) {
            "Checkpoint $checkpoint restored over a memory that no longer begins with the tokens it was saved at"
        }
        memoryTokens.subList(saved.size, memoryTokens.size).clear()
        predictionReady = false
        return true
    }

    override fun dropCheckpoint(checkpoint: Int) {
        enter(Call.DropCheckpoint(checkpoint))
        savedCheckpoints.remove(checkpoint)
    }

    override fun clearMemory() {
        enter(Call.ClearMemory)
        memoryTokens.clear()
        savedCheckpoints.clear()
        predictionReady = false
        contextTokens = openingTokens
    }

    override fun configureSampler(config: SamplerConfig) {
        enter(Call.ConfigureSampler(config))
        require(config.penaltyWindow > 0 || config.penaltyWindow == SamplerConfig.NO_PENALTY_WINDOW) { "penaltyWindow ${config.penaltyWindow}" }
        // As the native session: a reply start below -1 is refused and the previous sampler kept.
        if (config.replyStart < SamplerConfig.REPLY_START_AT_SAMPLING) {
            throw NativeLlamaException(NativeLlamaException.INVALID_ARGUMENT, "replyStart ${config.replyStart} is below -1")
        }
        sampler = config
        script = ArrayDeque(replies.removeFirstOrNull() ?: emptyList())
    }

    override fun sampleNext(): Int {
        enter(Call.SampleNext)
        check(sampler != null) { "sampleNext before configureSampler" }
        check(predictionReady) { "sampleNext without a complete evaluate straight before it" }
        predictionReady = false
        val banned = sampler?.bannedTokenIds.orEmpty()
        // A real sampler can never return a banned token; neither does the script.
        while (true) {
            val next = script.removeFirstOrNull() ?: return endToken
            if (next !in banned) return next
        }
    }

    override fun close() {
        if (closed) return
        recorded += Call.Close
        callerThreads += Thread.currentThread()
        memoryTokens.clear()
        savedCheckpoints.clear()
        closed = true
    }

    companion object {
        /** Byte `b` is the token `BYTE_BASE + b`. */
        const val BYTE_BASE: Int = 256

        /** Ids from here on are handed out by [addControl] and [addPiece]. */
        const val FIRST_FREE_ID: Int = 1000
    }
}

/**
 * A [NativeLlamaFactory] that "loads" [FakeNativeLlama] instances.
 *
 * @param create makes the fake for a model; the default gives it the model's context length.
 */
class FakeNativeLlamaFactory(
    private val create: (ModelSpec) -> FakeNativeLlama = { FakeNativeLlama(contextTokens = it.contextLength) },
) : NativeLlamaFactory {

    private val openedModels = ArrayList<Pair<ModelSpec, FakeNativeLlama>>()

    /** Every model opened so far with its fake, oldest first. */
    val opened: List<Pair<ModelSpec, FakeNativeLlama>> get() = openedModels

    /** The fake of the most recently opened model. */
    val last: FakeNativeLlama get() = openedModels.last().second

    /** The progress values reported during a load. */
    var progressSteps: List<Float> = listOf(0f, 0.5f, 1f)

    /** Runs before each progress report; a test cancels the loading coroutine from here. */
    var beforeProgress: (Float) -> Unit = {}

    /** When set, [open] throws it instead of loading. */
    var failure: RuntimeException? = null

    private val callerThreads = LinkedHashSet<Thread>()
    val threads: Set<Thread> get() = callerThreads

    /** Every opening asked for, as (tokens, budget), refused ones included. */
    val openings = ArrayList<Pair<Int, Long>>()

    /** The largest context this factory opens: a larger one is refused, whatever the budget, as by a native side that knows a cost its caller does not. */
    var largestOpening: Int = Int.MAX_VALUE

    override fun open(model: ModelSpec, progress: LoadProgress, contextTokens: Int, budgetBytes: Long): NativeLlama {
        openings += contextTokens to budgetBytes
        if (contextTokens > largestOpening) {
            throw NativeLlamaException(NativeLlamaException.CONTEXT_REFUSED, "a context of $contextTokens tokens is refused")
        }
        val native = load(model, progress)
        if (native.contextTokens > contextTokens) {
            native.contextTokens = contextTokens
            native.openingTokens = contextTokens
        }
        return native
    }

    /** The load itself: the scripted failure, the progress reports with their cancellation, then the fake of [create]. */
    private fun load(model: ModelSpec, progress: LoadProgress): FakeNativeLlama {
        callerThreads += Thread.currentThread()
        failure?.let { throw it }
        for (step in progressSteps) {
            beforeProgress(step)
            if (!progress.onProgress(step)) {
                throw NativeLlamaException(NativeLlamaException.LOAD_CANCELLED, "load cancelled")
            }
        }
        val native = create(model)
        openedModels += model to native
        return native
    }
}
