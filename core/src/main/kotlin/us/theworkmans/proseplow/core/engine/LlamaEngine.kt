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
import us.theworkmans.proseplow.core.prompt.PromptFormatException
import us.theworkmans.proseplow.core.prompt.PromptInput
import us.theworkmans.proseplow.core.prompt.PromptRole
import us.theworkmans.proseplow.core.prompt.PromptWhitespace
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

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
 *    no partial character and no part of a stop string is ever emitted ([ReplyAssembler]).
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
 *
 * @param factory loads models.
 * @param nativeDispatcher where every native call runs. In the app this is one dedicated thread;
 *   it must not run two tasks at the same time.
 * @param settings the engine's own tuning, independent of the model.
 * @param nanoTime a monotonic clock for the speeds in [GenerationStats]; replaceable in tests.
 * @param newSeed supplies the sampling seed when neither the request nor the model fixes one.
 */
class LlamaEngine(
    private val factory: NativeLlamaFactory,
    private val nativeDispatcher: CoroutineDispatcher,
    private val settings: Settings = Settings(),
    private val nanoTime: () -> Long = System::nanoTime,
    private val newSeed: () -> Int = { Random.nextInt() },
) : InferenceEngine {

    /**
     * @property maxCheckpoints how many memory checkpoints are kept; 0 switches them off. Each
     *   costs native memory on a model with recurrent state (some megabytes to some tens of
     *   megabytes) and next to nothing on other models. Four cover the usual actions: a new turn,
     *   regenerating the last answer, and editing either of the last two user messages.
     * @property evaluationChunk how many prompt tokens are evaluated between two progress
     *   reports and cancellation checks.
     * @property minReplyTokens the least room for a reply with which a generation is still
     *   started when the latest turn alone nearly fills the context.
     */
    data class Settings(
        val maxCheckpoints: Int = 4,
        val evaluationChunk: Int = 32,
        val minReplyTokens: Int = 16,
    ) {
        init {
            require(maxCheckpoints >= 0) { "maxCheckpoints must not be negative" }
            require(evaluationChunk >= 1) { "evaluationChunk must be at least 1" }
            require(minReplyTokens >= 1) { "minReplyTokens must be at least 1" }
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
                    val opened = factory.open(model) { fraction ->
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

    /** Frees the loaded model, if any, and forgets everything about its memory. */
    private fun closeNative() {
        val current = native
        native = null
        loaded = null
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

        /** True once [forget], [unload] or a [load] has asked this generation to end. */
        private fun interrupted(): Boolean = epoch.get() != startEpoch

        /** Polled by the native side: interrupted by the engine, or abandoned by the collector. */
        private fun shouldStop(): Boolean = interrupted() || !job.isActive

        suspend fun run() {
            if (interrupted()) {
                finish(FinishReason.CANCELLED, null)
                return
            }
            val contextSize = native.contextSize()
            val requestedNewTokens = (request.maxNewTokens ?: model.defaultMaxNewTokens).coerceAtLeast(1)
            val fitted = when (val result = fitPrompt(contextSize, requestedNewTokens)) {
                is ContextBudget.Result.TooLong -> {
                    out.send(GenerationEvent.Error(EngineError.PromptTooLong(result.promptTokens, contextSize)))
                    return
                }
                is ContextBudget.Result.Fits -> result
            }
            val tokens = fitted.prompt.tokens
            if (tokens.isEmpty()) throw PromptFormatException("The prompt is empty.")
            // Nothing has touched the memory yet: a collector that has gone costs nothing so far.
            job.ensureActive()
            promptTokens = tokens.size
            droppedMessages = fitted.droppedMessages
            if (droppedMessages > 0) {
                out.send(GenerationEvent.HistoryTrimmed(droppedMessages, request.messages.size - droppedMessages))
            }

            reuseMemory(tokens)
            reusedTokens = memory.size
            out.send(GenerationEvent.PromptProgress(reusedTokens, tokens.size))
            if (!evaluatePrompt(fitted.prompt)) {
                finish(FinishReason.CANCELLED, null)
                return
            }
            promptReadyAt = nanoTime()
            out.send(GenerationEvent.PromptProgress(tokens.size, tokens.size))

            sample(maxNewTokens = minOf(requestedNewTokens, contextSize - tokens.size), contextSize = contextSize)
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
            return ContextBudget.fit(
                messageCount = messages.size,
                starts = ContextBudget.startCandidates(messages),
                contextSize = contextSize,
                reserve = minOf(requestedNewTokens, contextSize / 2),
                minimum = minOf(requestedNewTokens, settings.minReplyTokens),
            ) { start ->
                val input = PromptInput(
                    messages = messages.subList(start, messages.size),
                    systemPrompt = systemPrompt,
                    extraSystemText = extraSystemText,
                    continueFinalMessage = request.continueFinalMessage,
                )
                tokeniser.tokenise(model.promptFormat.render(input))
            }
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
                    val evaluated = native.evaluate(part, settings.evaluationChunk, PromptObserver(base, tokens.size))
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
            }

            override fun isCancelled(): Boolean = shouldStop()
        }

        // --- Step 4: sample the reply ---

        private suspend fun sample(maxNewTokens: Int, contextSize: Int) {
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
                    penaltyWindow = contextSize,
                    seed = sampling.seed ?: newSeed(),
                    bannedTokenIds = model.bannedTokenIds,
                ),
            )
            // A continued message already has its first word; only a new reply loses leading space.
            val continuing = request.continueFinalMessage && !PromptWhitespace.isBlank(request.messages.last().content)
            val reply = ReplyAssembler(model.stopStrings + request.stopStrings, dropLeadingWhitespace = !continuing)
            this.reply = reply
            val single = IntArray(1)
            val observer = object : EvaluationObserver {
                override fun onProgress(evaluatedTokens: Int) = Unit
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
                if (native.isEndOfGeneration(token) || token in model.stopTokenIds) {
                    reason = FinishReason.END_OF_TURN
                    break
                }
                generatedTokens++
                val text = reply.accept(native.tokenToBytes(token))
                if (text.isNotEmpty()) out.send(GenerationEvent.TextDelta(text))
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
                        promptMillis = ((if (generating) promptReadyAt else now) - startedAt) / NANOS_PER_MILLI,
                        generationMillis = if (generating) (now - promptReadyAt) / NANOS_PER_MILLI else 0,
                        droppedMessages = droppedMessages,
                    ),
                ),
            )
        }
    }

    /** A description of an unexpected exception that cannot contain conversation text. */
    private fun describe(e: RuntimeException): String {
        val frame = e.stackTrace.firstOrNull()
        return if (frame == null) e.javaClass.simpleName else "${e.javaClass.simpleName} at ${frame.fileName}:${frame.lineNumber}"
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
