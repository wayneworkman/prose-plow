package us.theworkmans.proseplow.core.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import us.theworkmans.proseplow.core.model.ModelSpec
import us.theworkmans.proseplow.core.model.ReadingTimes
import us.theworkmans.proseplow.core.model.ReplyLength
import us.theworkmans.proseplow.core.prompt.Prompt
import us.theworkmans.proseplow.core.prompt.PromptFormatException
import us.theworkmans.proseplow.core.prompt.PromptInput
import us.theworkmans.proseplow.core.prompt.PromptRole
import us.theworkmans.proseplow.core.prompt.PromptWhitespace
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.random.Random

/**
 * Lets something more urgent than an answer have the processor for a moment (the app's key
 * derivation, which a person is waiting for with a finger on the lock screen). While the gate is
 * closed, a generation stops at its next safe point, between two chunks of the prompt or between
 * two tokens of the reply, and waits there; when it is opened, the generation goes on from where
 * it was, with nothing lost. A generation that is waiting has not ended: it is neither finished
 * nor stopped, and its collector sees no event until it goes on.
 *
 * The wait is between two native computations and never inside one. The native side polls for
 * cancellation in the middle of a computation as well, with its worker threads spinning at a
 * barrier meanwhile; waiting there would keep the processor as busy as computing does. Between
 * computations the worker threads have nothing to do and go to sleep.
 *
 * A closed gate does not hold a generation that should end. Whoever waits is asked again, at
 * least every [pollMillis], whether it has been cancelled or interrupted, and leaves the gate at
 * once if so; so stopping an answer, locking the app and forgetting a conversation all work
 * while the gate is closed.
 *
 * Thread-safe. [close] and [open] come in pairs and may nest: the gate is open again when every
 * [close] has had its [open]. Whoever closes it must open it in a `finally`.
 *
 * @param pollMillis how long a waiting generation sleeps before it asks again whether it should
 *   end. An [open] wakes it at once in any case.
 * @param idle what a waiting generation does for one such period, in place of sleeping on the
 *   gate's own condition. Only for single-threaded tests, where it is the one place at which
 *   something else can happen while a generation waits; it is called with the gate's lock held.
 */
class GenerationGate(private val pollMillis: Long = 25, private val idle: (() -> Unit)? = null) {
    private val lock = ReentrantLock()
    private val opened = lock.newCondition()

    /** How many [close] calls have not had their [open] yet. Written with [lock] held. */
    @Volatile private var closers = 0

    init {
        require(pollMillis >= 1) { "pollMillis must be at least 1" }
    }

    val isClosed: Boolean get() = closers > 0

    /** Asks the generation that is running, or the next one, to stand aside until [open]. Returns at once. */
    fun close() {
        lock.withLock { closers++ }
    }

    /** Takes one [close] back. One more [open] than there were [close] calls does nothing. */
    fun open() {
        lock.withLock {
            if (closers > 0) closers--
            if (closers == 0) opened.signalAll()
        }
    }

    /**
     * Blocks the calling thread while the gate is closed, unless [abandon] says that the caller
     * should end instead. Returns at once when the gate is open, which costs one volatile read.
     */
    internal fun pass(abandon: () -> Boolean) {
        if (closers == 0) return
        lock.withLock {
            while (closers > 0 && !abandon()) {
                if (idle != null) idle.invoke() else opened.await(pollMillis, TimeUnit.MILLISECONDS)
            }
        }
    }
}

/**
 * The real [InferenceEngine]: every decision about a generation, made in Kotlin on top of the
 * operations of a [NativeLlama].
 *
 * What it does for each request, in order:
 * 1. **Renders** the conversation with the model's prompt format and **tokenises** it, leaving
 *    out the oldest whole turns if it would not fit the context ([ContextBudget]).
 * 2. **Reuses** the model's memory. The engine keeps a copy of the tokens the memory holds. The
 *    longest common beginning of that copy and the new prompt stays; the rest of the memory is
 *    removed. When the model cannot remove the end of its memory (models with recurrent state),
 *    the engine goes back to the nearest checkpoint at or before that point, and when there is
 *    none it clears the memory and evaluates the whole prompt.
 * 3. **Evaluates** the part of the prompt that is not in memory, saving a **checkpoint** at the
 *    last few message boundaries on the way. A conversation that is continued, regenerated or
 *    edited stays unchanged up to one of those boundaries, so the next request usually finds a
 *    checkpoint to go back to. Only [Settings.maxCheckpoints] are kept, the latest ones.
 * 4. **Samples** token by token with the model's sampling defaults (or the request's) and the
 *    model's banned tokens, until the model ends its turn, a stop string appears, the token
 *    limit is reached or the generation is cancelled. Text is assembled from token bytes so that
 *    no partial character and no part of a stop string is ever emitted ([ReplyAssembler]). When
 *    the model has a deletion token and samples it, the token is evaluated like any other and
 *    the reply the reader sees loses its last token ([GenerationEvent.TextReplaced]).
 *
 * Invariants:
 * - All calls into [NativeLlama] and [NativeLlamaFactory] are made on [nativeDispatcher], one at
 *   a time. A mutex on top of that keeps generations, loads, unloads and [forget] whole: none of
 *   them can start in the middle of another, wherever the other may suspend.
 * - The engine's copy of the memory is brought up to date straight after every native call that
 *   changes the memory, before anything that can suspend. A generation can therefore be cancelled
 *   at any point and leave the engine consistent.
 * - At least one prompt token is always evaluated before sampling, because the model's
 *   prediction only exists after an evaluation. That is why at most all but one token are reused.
 * - **The context follows the conversation.** A model is opened with a context of its starting
 *   size. When a conversation and the room for its reply no longer fit, the context is made
 *   larger, to the smallest of its steps that fits, as far as the [contextPolicy] allows for
 *   this model on this device: the model's own maximum, the device's memory, and, unless the
 *   request names a size, the time it would take to read the conversation again. The model's
 *   memory is carried into the larger context when that can be done; otherwise the conversation
 *   is read again, which is an evaluation like any other and can be cancelled and paused like
 *   one. A size that the device refuses, or that cannot be created, is this model's limit until
 *   it is loaded again, and the oldest turns are left out only at the limit. Clearing the
 *   memory gives the memory of a grown context back: that happens when the app locks, and
 *   when a conversation is asked for that fits the starting size and shares little with the
 *   one in memory.
 * - A generation stands aside while the [gate] is closed, at the points between two native
 *   computations: before the first chunk of the prompt, after every chunk, and after every token
 *   of the reply. The time it spends there is left out of the speeds it reports.
 *
 * @param factory loads models.
 * @param nativeDispatcher where every native call runs. In the app this is one dedicated thread;
 *   it must not run two tasks at the same time.
 * @param settings the engine's own tuning, independent of the model.
 * @param nanoTime a monotonic clock for the speeds in [GenerationStats]; replaceable in tests.
 * @param newSeed supplies the sampling seed when neither the request nor the model fixes one.
 * @param gate closed by whoever needs the processor more urgently than an answer does; see
 *   [GenerationGate]. An engine that is given none has one that nobody closes.
 * @param memoryGauge says how much memory the device has and has available; asked before every
 *   decision about the size of the context. An engine that is given none assumes plenty.
 * @param contextPolicy how large a context may be on this device and through which sizes it
 *   grows; see [ContextPolicy].
 */
class LlamaEngine(
    private val factory: NativeLlamaFactory,
    private val nativeDispatcher: CoroutineDispatcher,
    private val settings: Settings = Settings(),
    private val nanoTime: () -> Long = System::nanoTime,
    private val newSeed: () -> Int = { Random.nextInt() },
    private val gate: GenerationGate = GenerationGate(),
    private val memoryGauge: MemoryGauge = MemoryGauge { DeviceMemory.PLENTY },
    private val contextPolicy: ContextPolicy = ContextPolicy(),
) : InferenceEngine {

    /**
     * @property maxCheckpoints how many memory checkpoints are kept; 0 switches them off. Each
     *   costs native memory on a model with recurrent state (some megabytes to some tens of
     *   megabytes) and next to nothing on other models. Four cover the usual actions: a new turn,
     *   regenerating the last answer, and editing either of the last two user messages.
     * @property evaluationChunk how many prompt tokens are evaluated between two progress
     *   reports and cancellation checks while the memory holds a short conversation.
     * @property deepReadTokens from how many tokens in memory on a prompt is read in the model's
     *   own larger chunk, [ModelSpec.deepReadChunk]: a part of the prompt whose end lies at or
     *   beyond this many tokens is read in that chunk. Every chunk costs time in proportion to
     *   the tokens already in memory, so a long conversation read in small chunks is read
     *   slowly; a short one is not, and keeps the small chunk with its quick stops.
     * @property minReplyTokens the least room for a reply with which a generation is still
     *   started when the latest turn alone nearly fills the context.
     * @property giveBackRereadTokens the most tokens the engine reads again in order to give a
     *   grown context back: a conversation that fits the size the context was opened with gets
     *   that size back when no more than this many of the tokens in memory could have been
     *   used for it. That is the case when another conversation is asked for, which shares
     *   only its beginning with the one in memory. A conversation that merely became shorter
     *   keeps its context, and what was read of it.
     */
    data class Settings(
        val maxCheckpoints: Int = 4,
        val evaluationChunk: Int = 32,
        val deepReadTokens: Int = 1024,
        val minReplyTokens: Int = ReplyLength.MIN_TOKENS,
        val giveBackRereadTokens: Int = ContextPolicy.GRANULARITY,
    ) {
        init {
            require(maxCheckpoints >= 0) { "maxCheckpoints must not be negative" }
            require(evaluationChunk >= 1) { "evaluationChunk must be at least 1" }
            require(deepReadTokens >= 0) { "deepReadTokens must not be negative" }
            require(minReplyTokens >= 1) { "minReplyTokens must be at least 1" }
            require(giveBackRereadTokens >= 0) { "giveBackRereadTokens must not be negative" }
        }
    }

    /** A saved state of the memory when it held exactly the first [position] tokens. */
    private class Checkpoint(val position: Int, val handle: Int)

    private val _state = MutableStateFlow<EngineState>(EngineState.Unloaded)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val mutex = Mutex()

    /**
     * Raised by everything that must end the generations begun before it: [forget], [unload] and
     * a [load] of another model. A generation remembers the value it started with and ends with
     * [FinishReason.CANCELLED] once the value has moved on.
     */
    private val epoch = AtomicInteger(0)

    // Everything below is touched only on nativeDispatcher while the mutex is held.
    private var native: NativeLlama? = null
    private var loaded: ModelSpec? = null
    private val memory = TokenList()

    /** The size the loaded model's context was opened with: where its steps begin. */
    private var openedContext = 0

    /** A limit that was found for the loaded model's context by being refused or failing; null until then. */
    private var foundContextLimit: Int? = null

    /**
     * How many times as long as the catalogue's reading times the loaded model last took on
     * this device to read a prompt, between [MIN_READING_FACTOR] and [MAX_READING_FACTOR]; 0
     * until measured. Used for a model whose catalogue entry has reading times.
     */
    private var measuredReadingFactor = 0.0

    /**
     * How fast the loaded model has last been seen to read a prompt on this device, in tokens
     * per second; 0 until measured. Used only for a model whose catalogue entry has no reading
     * times, which then has no curve to scale.
     */
    private var measuredReadSpeed = 0.0

    /** The limit the collector was last told of, so that the same one is not announced with every answer; null after a growth. */
    private var announcedContextLimit: Int? = null
    private val checkpoints = ArrayList<Checkpoint>()

    // --- Loading ---

    override suspend fun load(model: ModelSpec) {
        val current = _state.value
        if (current is EngineState.Ready && current.modelId == model.id) return
        epoch.incrementAndGet()
        mutex.withLock {
            withContext(nativeDispatcher) {
                if (native != null && loaded?.id == model.id) return@withContext
                closeNative()
                _state.value = EngineState.Loading(model.id, 0f)
                val job = coroutineContext.job
                try {
                    val opened = openAtStartingSize(model) { fraction ->
                        _state.value = EngineState.Loading(model.id, fraction.coerceIn(0f, 1f))
                        job.isActive
                    }
                    if (job.isActive) {
                        native = opened
                        loaded = model
                        _state.value = EngineState.Ready(model.id)
                    } else {
                        // Cancelled too late for the native side to notice: do not keep the model.
                        opened.close()
                        _state.value = EngineState.Unloaded
                    }
                } catch (e: CancellationException) {
                    _state.value = EngineState.Unloaded
                    throw e
                } catch (e: NativeLlamaException) {
                    _state.value = if (e.code == NativeLlamaException.LOAD_CANCELLED) {
                        EngineState.Unloaded
                    } else {
                        EngineState.Failed(model.id, EngineError.LoadFailed(model.id, e.message.orEmpty()))
                    }
                } catch (e: RuntimeException) {
                    _state.value = EngineState.Failed(model.id, EngineError.LoadFailed(model.id, describe(e)))
                }
            }
        }
    }

    override suspend fun unload() {
        epoch.incrementAndGet()
        // Not cancellable: once asked for, the model must really be freed.
        withContext(NonCancellable) {
            mutex.withLock {
                withContext(nativeDispatcher) {
                    closeNative()
                    _state.value = EngineState.Unloaded
                }
            }
        }
    }

    override suspend fun forget() {
        epoch.incrementAndGet()
        // Not cancellable: the app is locking, and the conversation must be gone when this returns.
        withContext(NonCancellable) {
            mutex.withLock {
                withContext(nativeDispatcher) {
                    val current = native ?: return@withContext
                    if (!clearMemory(current)) {
                        // The memory could not be cleared; freeing the model clears it for certain.
                        closeNative()
                        _state.value = EngineState.Unloaded
                    }
                }
            }
        }
    }

    /**
     * Opens [model] with a context of its starting size, or, when the device has not the memory
     * for that now, of half that size, and half again, down to [MIN_OPENING_CONTEXT]. Below
     * that the model is not opened: a context too small to hold a question is of no use.
     *
     * What is held to the budget is what the tokens of the context take, by the catalogue's
     * figure for one token, which is the model file's own. A session reserves more than that,
     * the same amount at every size and most of it never touched (the scores of a whole batch
     * above all). No choice of size changes that amount, it was there before a context could
     * grow, and it is not known before a session exists. The native side is therefore given no
     * budget of its own to refuse the opening by; it may still refuse, and is then asked for
     * less.
     */
    private fun openAtStartingSize(model: ModelSpec, progress: LoadProgress): NativeLlama {
        val budget = contextPolicy.budgetBytes(memoryGauge.read(), currentBytes = 0)
        var size = minOf(model.contextLength, model.maxContextLength)
        while (size.toLong() * model.contextBytesPerToken > budget) {
            if (size / 2 < MIN_OPENING_CONTEXT) throw NativeLlamaException(NativeLlamaException.OUT_OF_MEMORY, TOO_LITTLE_MEMORY)
            size /= 2
        }
        while (true) {
            try {
                return factory.open(model, progress, size, Long.MAX_VALUE).also {
                    openedContext = it.contextSize()
                    foundContextLimit = null
                    measuredReadingFactor = 0.0
                    measuredReadSpeed = 0.0
                }
            } catch (e: NativeLlamaException) {
                if (e.code != NativeLlamaException.CONTEXT_REFUSED) throw e
                if (size / 2 < MIN_OPENING_CONTEXT) throw NativeLlamaException(NativeLlamaException.OUT_OF_MEMORY, TOO_LITTLE_MEMORY)
                size /= 2
            }
        }
    }

    /** Frees the loaded model, if any, and forgets everything about its memory. */
    private fun closeNative() {
        val current = native
        native = null
        loaded = null
        openedContext = 0
        foundContextLimit = null
        measuredReadingFactor = 0.0
        measuredReadSpeed = 0.0
        announcedContextLimit = null
        memory.wipe()
        checkpoints.clear()
        try {
            current?.close()
        } catch (_: RuntimeException) {
            // Nothing more can be done for it; the engine no longer refers to it either way.
        }
    }

    /**
     * Empties the native memory, which also frees every checkpoint, and the engine's copy with
     * it. Returns false when the native side failed; the copy is emptied regardless, so the next
     * generation notices the mismatch and tries again.
     */
    private fun clearMemory(native: NativeLlama): Boolean {
        memory.wipe()
        checkpoints.clear()
        return try {
            native.clearMemory()
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    // --- Generating ---

    /*
     * Two things about this flow are deliberate.
     *
     * The epoch is read in the collector's own context, at the moment collection begins, and not
     * when the generation finally gets the engine. A generation that was still waiting its turn
     * when the app locked is therefore cancelled too, instead of running after the lock.
     *
     * The events travel through an unlimited buffer, so a generation never waits for its
     * collector. forget() must not hang because the collector happens to be busy (or is the very
     * caller that is waiting for forget() to return). The buffer cannot grow without bound: a
     * generation emits at most one event per token or per chunk of the prompt.
     */
    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = flow {
        val startEpoch = epoch.get()
        emitAll(
            channelFlow { mutex.withLock { generate(request, startEpoch) } }
                .buffer(Channel.UNLIMITED)
                .flowOn(nativeDispatcher),
        )
    }

    private suspend fun ProducerScope<GenerationEvent>.generate(request: GenerationRequest, startEpoch: Int) {
        val native = native
        val model = loaded
        if (native == null || model == null) {
            send(GenerationEvent.Error(EngineError.NotLoaded))
            return
        }
        if (request.modelId != model.id) {
            send(GenerationEvent.Error(EngineError.WrongModel(requested = request.modelId, loaded = model.id)))
            return
        }
        val error = try {
            Generation(this, native, model, request, startEpoch, coroutineContext.job).run()
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: PromptFormatException) {
            // Raised before the memory was touched.
            EngineError.InvalidRequest(e.message.orEmpty())
        } catch (e: NativeLlamaException) {
            if (e.code == NativeLlamaException.CONTEXT_NOT_CREATED && native.contextSize() <= 0) {
                // No context exists any more, of any size: the model is as good as not loaded.
                closeNative()
                val failure = EngineError.LoadFailed(model.id, TOO_LITTLE_MEMORY)
                _state.value = EngineState.Failed(model.id, failure)
                send(GenerationEvent.Error(failure))
                return
            }
            clearMemory(native)
            e.toEngineError()
        } catch (e: RuntimeException) {
            clearMemory(native)
            EngineError.Unexpected(describe(e))
        }
        send(GenerationEvent.Error(error))
    }

    /**
     * One generation from start to finish. A class only so that the steps can share their state;
     * it runs on [nativeDispatcher] with the mutex held and is used once.
     */
    private inner class Generation(
        private val out: ProducerScope<GenerationEvent>,
        private val native: NativeLlama,
        private val model: ModelSpec,
        private val request: GenerationRequest,
        private val startEpoch: Int,
        private val job: Job,
    ) {
        private val startedAt = nanoTime()
        private var promptReadyAt = startedAt
        private var promptTokens = 0
        private var reusedTokens = 0
        private var generatedTokens = 0
        private var droppedMessages = 0
        private var deletions = 0

        /** Time spent standing aside at the [gate], before and after the prompt was ready. */
        private var asideBeforeReply = 0L
        private var asideDuringReply = 0L

        /** True once [forget], [unload] or a [load] has asked this generation to end. */
        private fun interrupted(): Boolean = epoch.get() != startEpoch

        /** Polled by the native side: interrupted by the engine, or abandoned by the collector. */
        private fun shouldStop(): Boolean = interrupted() || !job.isActive

        /**
         * Waits while the [gate] is closed, unless this generation should end. Only ever called
         * between two native computations (see [GenerationGate] for why), on the native thread,
         * which has nothing else to do meanwhile: everything else that wants it is waiting for
         * this generation's mutex.
         */
        private fun standAside() {
            if (!gate.isClosed) return
            val since = nanoTime()
            gate.pass(::shouldStop)
            val waited = nanoTime() - since
            if (reply == null) asideBeforeReply += waited else asideDuringReply += waited
        }

        suspend fun run() {
            if (interrupted()) {
                finish(FinishReason.CANCELLED, null)
                return
            }
            val requestedNewTokens = (request.maxNewTokens ?: model.defaultMaxNewTokens).coerceAtLeast(1)
            // The conversation is fitted to the limit of the context, not to its present size:
            // while the context can still grow, nothing is left out. Each round fits, makes the
            // memory agree with the prompt (which may clear it, and with it give a grown context
            // back), and then makes room. A size that cannot be had lowers the limit, and the
            // next round fits to that; so the limit only ever goes down and the rounds end.
            var limit = contextLimit()
            var fitted: ContextBudget.Result.Fits
            var rounds = 0
            while (true) {
                fitted = when (val result = fitPrompt(limit, requestedNewTokens)) {
                    is ContextBudget.Result.TooLong -> {
                        out.send(GenerationEvent.Error(EngineError.PromptTooLong(result.promptTokens, limit)))
                        return
                    }
                    is ContextBudget.Result.Fits -> result
                }
                if (fitted.prompt.tokens.isEmpty()) throw PromptFormatException("The prompt is empty.")
                // A collector that has gone is noticed before the memory is touched again.
                job.ensureActive()
                val wanted = minOf(limit, fitted.prompt.tokens.size + minOf(requestedNewTokens, ReplyLength.maxTokens(limit)))
                giveBackIfLeft(fitted.prompt.tokens, wanted)
                reuseMemory(fitted.prompt.tokens)
                if (native.contextSize() >= wanted) break
                growTowards(wanted, limit)
                if (native.contextSize() >= wanted) break
                limit = native.contextSize()
                if (++rounds >= MAX_FITTING_ROUNDS) {
                    throw NativeLlamaException(NativeLlamaException.CONTEXT_FULL, "The context could not be made to hold the conversation")
                }
            }
            val contextSize = native.contextSize()
            val tokens = fitted.prompt.tokens
            promptTokens = tokens.size
            droppedMessages = fitted.droppedMessages
            if (droppedMessages > 0) {
                out.send(GenerationEvent.HistoryTrimmed(droppedMessages, request.messages.size - droppedMessages))
            }

            reusedTokens = memory.size
            out.send(GenerationEvent.PromptProgress(reusedTokens, tokens.size))
            val readingFrom = nanoTime()
            val asideBeforeReading = asideBeforeReply
            if (!evaluatePrompt(fitted.prompt)) {
                finish(FinishReason.CANCELLED, null)
                return
            }
            promptReadyAt = nanoTime()
            // The reading alone: not the fitting and the growing before it, and not the time spent standing aside.
            noteReading(from = reusedTokens, to = tokens.size, nanos = promptReadyAt - readingFrom - (asideBeforeReply - asideBeforeReading))
            out.send(GenerationEvent.PromptProgress(tokens.size, tokens.size))

            sample(maxNewTokens = minOf(requestedNewTokens, contextSize - tokens.size))
        }

        // --- The size of the context ---

        /**
         * How long this model takes to read a conversation on this device: the catalogue's
         * curve scaled by the factor last measured (1 until then). A model whose catalogue
         * entry has no curve gets a flat one from the speed it was last seen to read at, and
         * none at all before that.
         */
        private fun readingTimes(): ReadingTimes {
            val catalogue = model.readingTimes
            return when {
                catalogue.isKnown -> if (measuredReadingFactor > 0) catalogue.scaled(measuredReadingFactor) else catalogue
                measuredReadSpeed > 0 -> ReadingTimes.flat(measuredReadSpeed)
                else -> ReadingTimes.UNKNOWN
            }
        }

        /**
         * Remembers how fast a prompt was read that took the memory from [from] to [to] tokens
         * in [nanos], when it was long enough to say: as a factor over the catalogue's time
         * for the same stretch, so that a short prompt read deep into a conversation is
         * compared with what the curve says of that depth and not with the speed of a short
         * prompt from nothing. The latest measurement replaces the earlier one.
         */
        private fun noteReading(from: Int, to: Int, nanos: Long) {
            if (to - from < MIN_TOKENS_FOR_SPEED || nanos <= 0) return
            val seconds = nanos / NANOS_PER_SECOND
            val catalogue = model.readingTimes
            if (catalogue.isKnown) {
                measuredReadingFactor = (seconds / catalogue.secondsBetween(from, to)).coerceIn(MIN_READING_FACTOR, MAX_READING_FACTOR)
            } else {
                measuredReadSpeed = (to - from) / seconds
            }
        }

        /**
         * What a context of [tokens] costs as the native side counts it, or null when it cannot
         * say: for a size beyond the model's largest, and for a session that could not learn
         * what its contexts cost. Such a session keeps the size it has; it still answers.
         */
        private fun nativeCost(tokens: Int): Long? {
            if (tokens < 1 || tokens > native.maxContextSize) return null
            return try {
                native.contextBytes(tokens)
            } catch (e: NativeLlamaException) {
                if (e.code != NativeLlamaException.CONTEXT_REFUSED) throw e
                null
            }
        }

        /** The memory one more token of context costs for the loaded model, as it says itself; the catalogue's figure when it does not. */
        private fun bytesPerToken(): Long {
            val low = nativeCost(ContextPolicy.GRANULARITY)
            val high = nativeCost(2 * ContextPolicy.GRANULARITY)
            val asked = if (low != null && high != null) (high - low) / ContextPolicy.GRANULARITY else 0
            return if (asked > 0) asked else model.contextBytesPerToken
        }

        /**
         * How large this model's context may become for this request: what the policy allows
         * for the model on this device (under the request's own choice of size, if it makes
         * one), and no more than a limit that was found earlier by running into it.
         *
         * Left to itself, a context that has grown is not cut back because the model was since
         * seen to read more slowly: the speed is measured again with every prompt, and a limit
         * that followed every measurement down would leave out turns of a conversation that was
         * already read, and read the rest again. What a conversation was given, it keeps until
         * it is left; the limit of the moment counts for what is asked for from then on. A size
         * the request names is another matter: it is the user's, and holds at once.
         */
        private fun contextLimit(): Int {
            val allowed = contextPolicy.limit(
                start = openedContext,
                maxContext = minOf(model.maxContextLength, native.maxContextSize),
                bytesPerToken = bytesPerToken(),
                totalBytes = memoryGauge.read().totalBytes,
                chosen = request.maxContextTokens,
                readingTimes = readingTimes(),
            )
            val limit = minOf(allowed, foundContextLimit ?: Int.MAX_VALUE)
            return (if (request.maxContextTokens == null) maxOf(limit, native.contextSize()) else limit).coerceAtLeast(1)
        }

        /**
         * Makes the context larger, towards [wanted] tokens and never beyond [limit]: to the
         * smallest step that holds [wanted] if the device's memory allows that now, otherwise
         * to the largest step it does allow. Where it ends below what was wanted, that size is
         * the model's limit from now on, and the collector is told so once.
         *
         * The device's memory is read here, at the moment of the decision. The policy budgets
         * what the tokens of a context take. The native side counts more: what a session
         * reserves whatever the size of its context, most of which is never touched. That part
         * is the same before and after a step, so it is added to the policy's budget, and the
         * sum is what the native side is given; it keeps the context's memory within it at
         * every moment, choosing itself whether the model's memory can be carried over.
         */
        private suspend fun growTowards(wanted: Int, limit: Int) {
            val from = native.contextSize()
            val device = memoryGauge.read()
            val cost = nativeCost(from)
            if (cost == null) {
                // What a context costs is not known, so none is asked for.
                limitFound(from, GenerationEvent.ContextLimited.Reason.FAILED)
                return
            }
            val tokenBytes = from.toLong() * bytesPerToken()
            val reserved = (cost - tokenBytes).coerceAtLeast(0)
            val allowed = contextPolicy.budgetBytes(device, minOf(tokenBytes, cost))
            val budget = if (allowed > Long.MAX_VALUE - reserved) Long.MAX_VALUE else allowed + reserved
            val steps = contextPolicy.steps(start = openedContext, limit = limit)
            val target = steps.firstOrNull { it >= wanted } ?: limit
            if (device.low) {
                // The system says memory is low: nothing is added while it says so. That is a
                // state of the moment and not a limit of this model, so it is not remembered.
                announceLimit(from, GenerationEvent.ContextLimited.Reason.MEMORY)
                return
            }
            val affordable = steps.lastOrNull { step -> step > from && step <= target && nativeCost(step).let { it != null && it <= budget } }
            if (affordable == null) {
                limitFound(from, GenerationEvent.ContextLimited.Reason.MEMORY)
                return
            }
            val held = memory.size
            try {
                native.resizeContext(affordable, budget)
            } catch (e: NativeLlamaException) {
                when (e.code) {
                    NativeLlamaException.CONTEXT_REFUSED -> limitFound(native.contextSize(), GenerationEvent.ContextLimited.Reason.MEMORY)
                    NativeLlamaException.CONTEXT_NOT_CREATED -> {
                        // Whatever exists now is what there is; a context that does not exist at all ends the generation as a failure of the model.
                        if (native.contextSize() <= 0) throw e
                        agreeWithNativeMemory()
                        limitFound(native.contextSize(), GenerationEvent.ContextLimited.Reason.FAILED)
                    }
                    else -> throw e
                }
                return
            }
            agreeWithNativeMemory()
            announcedContextLimit = null
            out.send(GenerationEvent.ContextGrown(fromTokens = from, toTokens = native.contextSize(), rereading = held > 0 && memory.size == 0))
            if (affordable < target) limitFound(affordable, GenerationEvent.ContextLimited.Reason.MEMORY)
        }

        /**
         * Gives a grown context back when the conversation that needed it has been left: when
         * what is asked for now fits the size the context was opened with, and little of what
         * the model has read could be used for it ([Settings.giveBackRereadTokens] at most,
         * which are then read again). Clearing the memory is what returns the context to that
         * size, so nothing is asked of the native side that a lock does not ask as well.
         *
         * Without this a model that can remove the end of its memory would keep the context of
         * its longest conversation until the next lock, however short the conversations after
         * it. A conversation that merely became shorter (its latest message was edited, say)
         * keeps its context and what was read of it: reading thousands of tokens again costs
         * the user more than the memory is worth until the next lock.
         */
        private fun giveBackIfLeft(prompt: IntArray, wanted: Int) {
            if (native.contextSize() <= openedContext || wanted > openedContext) return
            val reusable = minOf(memory.commonPrefixWith(prompt), prompt.size - 1)
            if (reusable <= settings.giveBackRereadTokens) clearMemory()
        }

        /** After a resize: the engine's copy of the memory is what the native side kept, which is all of it or nothing. */
        private fun agreeWithNativeMemory() {
            if (native.memoryTokenCount() == memory.size) return
            if (native.memoryTokenCount() == 0) {
                // Not carried over. The checkpoints went with it.
                memory.wipe()
                checkpoints.clear()
            } else {
                clearMemory()
            }
        }

        /** [tokens] is this model's limit until it is loaded again; the collector is told once. */
        private suspend fun limitFound(tokens: Int, reason: GenerationEvent.ContextLimited.Reason) {
            foundContextLimit = minOf(tokens, foundContextLimit ?: Int.MAX_VALUE)
            announceLimit(tokens, reason)
        }

        private suspend fun announceLimit(tokens: Int, reason: GenerationEvent.ContextLimited.Reason) {
            if (announcedContextLimit == tokens) return
            announcedContextLimit = tokens
            out.send(GenerationEvent.ContextLimited(tokens, reason))
        }

        // --- Step 1: render, tokenise, fit ---

        private fun fitPrompt(contextSize: Int, requestedNewTokens: Int): ContextBudget.Result {
            val messages = request.messages
            if (messages.none { it.role == PromptRole.USER }) {
                throw PromptFormatException("The conversation has no user message.")
            }
            // A model trained without a system message gets no system text of any kind.
            val systemPrompt = request.systemPrompt.takeIf { model.supportsSystemPrompt }.nonBlank()
            val extraSystemText = request.extraSystemText.takeIf { model.supportsSystemPrompt }.nonBlank()
            val tokeniser = PromptTokeniser(native)
            this.tokeniser = tokeniser
            // A model that continues text is only ever given the latest message: there are no
            // older turns to leave out, and none must be reported as left out.
            val candidates = if (model.family.continuesText) listOf(0) else ContextBudget.startCandidates(messages)
            val rendered = HashMap<Int, Prompt>()
            fun rendering(start: Int): Prompt = rendered.getOrPut(start) {
                model.promptFormat.render(
                    PromptInput(
                        messages = messages.subList(start, messages.size),
                        systemPrompt = systemPrompt,
                        extraSystemText = extraSystemText,
                        continueFinalMessage = request.continueFinalMessage,
                    ),
                )
            }
            // Turns whose text the tokenizer could not take at all are left out first.
            val starts = ContextBudget.tokenisable(candidates) { start -> PromptTokeniser.withinLimit(rendering(start)) }
            return ContextBudget.fit(
                messageCount = messages.size,
                starts = starts,
                contextSize = contextSize,
                reserve = minOf(requestedNewTokens, ReplyLength.maxTokens(contextSize)),
                minimum = minOf(requestedNewTokens, settings.minReplyTokens),
            ) { start -> tokeniser.tokenise(rendering(start)) }
        }

        private var tokeniser: PromptTokeniser? = null

        private fun String?.nonBlank(): String? = this?.takeUnless(PromptWhitespace::isBlank)

        // --- Step 2: reuse what the memory holds ---

        /** Shortens the memory to the longest beginning it shares with [prompt], or less. */
        private fun reuseMemory(prompt: IntArray) {
            if (native.memoryTokenCount() != memory.size) clearMemory()
            // The last prompt token is always evaluated afresh: sampling needs its prediction.
            val keep = minOf(memory.commonPrefixWith(prompt), prompt.size - 1)
            if (keep < memory.size) shortenMemoryTo(keep)
            if (native.memoryTokenCount() != memory.size) clearMemory()
        }

        private fun shortenMemoryTo(keep: Int) {
            if (keep > 0 && native.rewindTo(keep)) {
                memory.truncate(keep)
                dropCheckpointsAfter(keep)
                return
            }
            // This model cannot remove the end of its memory: go back to a checkpoint instead.
            val checkpoint = checkpoints.filter { it.position in 1..keep }.maxByOrNull { it.position }
            if (checkpoint != null && native.restoreCheckpoint(checkpoint.handle)) {
                memory.truncate(checkpoint.position)
                dropCheckpointsAfter(checkpoint.position)
                return
            }
            clearMemory()
        }

        /** Checkpoints beyond [position] describe a conversation that no longer exists. */
        private fun dropCheckpointsAfter(position: Int) {
            val stale = checkpoints.filter { it.position > position }
            for (checkpoint in stale) native.dropCheckpoint(checkpoint.handle)
            checkpoints.removeAll(stale.toSet())
        }

        private fun clearMemory() {
            if (!this@LlamaEngine.clearMemory(native)) {
                throw NativeLlamaException(NativeLlamaException.EVALUATION_FAILED, "The memory could not be cleared")
            }
        }

        // --- Step 3: evaluate the rest of the prompt, with checkpoints ---

        /** Returns false when the engine interrupted the evaluation. */
        private suspend fun evaluatePrompt(prompt: TokenisedPrompt): Boolean {
            val tokens = prompt.tokens
            val stops = if (settings.maxCheckpoints == 0) {
                emptyList()
            } else {
                checkNotNull(tokeniser).boundaryPositions(prompt, after = memory.size, limit = settings.maxCheckpoints)
            }
            for (stop in stops + tokens.size) {
                if (stop > memory.size) {
                    val base = memory.size
                    val part = tokens.copyOfRange(base, stop)
                    standAside()
                    // A part that leaves a long conversation in memory is read in the model's deep chunk.
                    val chunk = if (stop >= settings.deepReadTokens) model.deepReadChunk else settings.evaluationChunk
                    val evaluated = native.evaluate(part, chunk, PromptObserver(base, tokens.size))
                    memory.addAll(part, evaluated)
                    if (evaluated < part.size) {
                        if (interrupted()) return false
                        job.ensureActive()
                        throw NativeLlamaException(NativeLlamaException.EVALUATION_FAILED, "Evaluation stopped early without being cancelled")
                    }
                }
                if (stop < tokens.size) saveCheckpoint(stop)
            }
            return true
        }

        private fun saveCheckpoint(position: Int) {
            if (checkpoints.any { it.position == position }) return
            // Make room first, so that the native side never holds more than the limit.
            while (checkpoints.size >= settings.maxCheckpoints) {
                val oldest = checkpoints.minBy { it.position }
                native.dropCheckpoint(oldest.handle)
                checkpoints.remove(oldest)
            }
            val handle = native.saveCheckpoint()
            if (handle != NativeLlama.NO_CHECKPOINT) checkpoints += Checkpoint(position, handle)
        }

        /** Reports prompt progress from inside the native call, and passes on cancellation. */
        private inner class PromptObserver(private val base: Int, private val total: Int) : EvaluationObserver {
            override fun onProgress(evaluatedTokens: Int) {
                // Not a suspending send: this runs inside a blocking native call. The buffer is
                // unlimited, so nothing is lost. The final report comes from run().
                if (base + evaluatedTokens < total) out.trySend(GenerationEvent.PromptProgress(base + evaluatedTokens, total))
                // A chunk is done and the next has not begun: the place to make way.
                standAside()
            }

            override fun isCancelled(): Boolean = shouldStop()
        }

        // --- Step 4: sample the reply ---

        private suspend fun sample(maxNewTokens: Int) {
            val sampling = request.sampling ?: model.sampling
            native.configureSampler(
                SamplerConfig(
                    temperature = sampling.temperature,
                    topK = sampling.topK,
                    topP = sampling.topP,
                    minP = sampling.minP,
                    repetitionPenalty = sampling.repetitionPenalty,
                    presencePenalty = sampling.presencePenalty,
                    frequencyPenalty = sampling.frequencyPenalty,
                    penaltyWindow = SamplerConfig.NO_PENALTY_WINDOW,
                    seed = sampling.seed ?: newSeed(),
                    bannedTokenIds = model.bannedTokenIds,
                ),
            )
            // A continued message already has its first word; only a new reply loses leading space,
            // and only a chat model's: a model that continues text keeps every character it writes.
            val continuing = request.continueFinalMessage && !PromptWhitespace.isBlank(request.messages.last().content)
            val reply = ReplyAssembler(
                stopStrings = model.stopStrings + request.stopStrings,
                dropLeadingWhitespace = !continuing && !model.family.continuesText,
            )
            this.reply = reply
            val single = IntArray(1)
            val observer = object : EvaluationObserver {
                // The token is in the memory and the next is not yet sampled: the place to make way.
                override fun onProgress(evaluatedTokens: Int) = standAside()
                override fun isCancelled(): Boolean = shouldStop()
            }

            val reason: FinishReason
            while (true) {
                if (interrupted()) {
                    reason = FinishReason.CANCELLED
                    break
                }
                // Sending never suspends (the buffer is unlimited), so it never notices that the
                // collector has gone; this does.
                job.ensureActive()
                val token = native.sampleNext()
                // The deletion token is asked about first: the catalogue's word on what a token
                // means goes before what the model file says about ending a turn.
                val change = if (token == model.deletionTokenId) {
                    deletions++
                    reply.deleteLast()
                } else if (native.isEndOfGeneration(token) || token in model.stopTokenIds) {
                    reason = FinishReason.END_OF_TURN
                    break
                } else {
                    reply.accept(native.tokenToBytes(token))
                }
                // A deletion counts towards the limit and is evaluated below like any other
                // token: to the model it is one, and it must find it in its memory.
                generatedTokens++
                when (change) {
                    is ReplyAssembler.Change.Appended -> out.send(GenerationEvent.TextDelta(change.text))
                    is ReplyAssembler.Change.Replaced -> out.send(GenerationEvent.TextReplaced(change.text, change.removed))
                    ReplyAssembler.Change.None -> Unit
                }
                if (reply.matchedStop != null) {
                    reason = FinishReason.STOP_STRING
                    break
                }
                if (generatedTokens >= maxNewTokens) {
                    // The last token is not evaluated: nothing will be sampled after it.
                    reason = FinishReason.LENGTH
                    break
                }
                single[0] = token
                if (native.evaluate(single, 1, observer) == 1) {
                    memory.add(token)
                } else {
                    if (!interrupted()) {
                        job.ensureActive()
                        throw NativeLlamaException(NativeLlamaException.EVALUATION_FAILED, "Evaluation stopped early without being cancelled")
                    }
                    reason = FinishReason.CANCELLED
                    break
                }
            }
            finish(reason, reply.matchedStop)
        }

        private var reply: ReplyAssembler? = null

        private suspend fun finish(reason: FinishReason, stopString: String?) {
            val reply = reply
            val rest = reply?.finish().orEmpty()
            if (rest.isNotEmpty()) out.send(GenerationEvent.TextDelta(rest))
            val now = nanoTime()
            val generating = reply != null
            out.send(
                GenerationEvent.Done(
                    finishReason = reason,
                    stopString = stopString,
                    text = reply?.text.orEmpty(),
                    stats = GenerationStats(
                        promptTokens = promptTokens,
                        reusedPromptTokens = reusedTokens,
                        generatedTokens = generatedTokens,
                        promptMillis = ((if (generating) promptReadyAt else now) - startedAt - asideBeforeReply) / NANOS_PER_MILLI,
                        generationMillis = if (generating) (now - promptReadyAt - asideDuringReply) / NANOS_PER_MILLI else 0,
                        droppedMessages = droppedMessages,
                        deletions = deletions,
                    ),
                    exactText = model.family.continuesText,
                ),
            )
        }
    }

    /** A description of an unexpected exception that cannot contain conversation text. */
    private fun describe(e: RuntimeException): String {
        val frame = e.stackTrace.firstOrNull()
        return if (frame == null) e.javaClass.simpleName else "${e.javaClass.simpleName} at ${frame.fileName}:${frame.lineNumber}"
    }

    companion object {
        /**
         * The message of the failure to load a model for which the device has too little memory
         * available now, so that whoever words the failure for the user can tell it from the others.
         */
        const val TOO_LITTLE_MEMORY: String = "too little memory available for this model"

        /** No model is opened with a context smaller than this. */
        const val MIN_OPENING_CONTEXT: Int = 1024

        private const val NANOS_PER_MILLI = 1_000_000L
        private const val NANOS_PER_SECOND = 1e9

        /** A prompt shorter than this says too little about the reading speed. */
        private const val MIN_TOKENS_FOR_SPEED = 256

        /**
         * The bounds of the measured reading factor. A device four times faster or slower than
         * the tested phone is beyond what one measurement should be trusted to say; a reading
         * slowed by something passing (the system busy for a moment) is held to them.
         */
        private const val MIN_READING_FACTOR = 0.25
        private const val MAX_READING_FACTOR = 4.0

        /** More rounds of fitting than a context has ways to shrink mean that something is wrong. */
        private const val MAX_FITTING_ROUNDS = 8
    }
}
