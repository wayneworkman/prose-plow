package us.theworkmans.proseplow.core.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import us.theworkmans.proseplow.core.model.ModelSpec

/**
 * An [InferenceEngine] that plays back scripts, for testing whatever drives an engine: view
 * models, a background task, the code that locks the app.
 *
 * Each call of [generate] takes the next script from the queue ([enqueue], [enqueueReply]) and
 * plays its steps in order. Delays use the coroutine `delay`, so under `runTest` they pass in
 * virtual time and a test can stop a reply between two words without sleeping. Without a script
 * the reply is empty and ends at once.
 *
 * It behaves like the real engine where callers depend on it:
 * - A request made while no model is loaded, or for another model than the loaded one, gets the
 *   same typed errors.
 * - [forget], [unload] and a [load] of another model end a generation in flight with
 *   [FinishReason.CANCELLED], reporting the text emitted so far.
 * - A cancelled collection ends the generation without a final event.
 *
 * - A read-ahead ([readAhead]) takes [readAheadMillis] and answers [readAheadResult]; a request,
 *   a save, a restore, [forget], [unload] and a [load] stop it, as does cancelling its caller.
 *
 * What happened is recorded: [requests], [readAheads], and the counters of generations started,
 * completed and cancelled. Saving and restoring the memory are recorded and answered from [saveResult] and
 * [restoreResult]; nothing is written to a vault. Meant for single-threaded tests; nothing here
 * is synchronised.
 */
class FakeInferenceEngine : InferenceEngine {

    /** One step of a scripted generation. */
    sealed interface Step {
        /** Emits [event]. A [GenerationEvent.Done] or [GenerationEvent.Error] ends the script. */
        data class Emit(val event: GenerationEvent) : Step

        /** Waits [millis] of (virtual) time. */
        data class Wait(val millis: Long) : Step

        /** Never continues: the generation hangs until it is cancelled or interrupted. */
        data object Hang : Step
    }

    private val _state = MutableStateFlow<EngineState>(EngineState.Unloaded)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    // --- Scripting ---

    private val scripts = ArrayDeque<List<Step>>()

    /** How long [load] takes, in (virtual) milliseconds. */
    var loadMillis: Long = 0

    /** When set, the next [load] fails with it. */
    var loadFailure: EngineError? = null

    /** Queues the script of one generation. */
    fun enqueue(steps: List<Step>) {
        scripts += steps.toList()
    }

    /**
     * Queues an ordinary reply: [pieces] as text deltas with [millisBetween] before each, then a
     * [GenerationEvent.Done] with [finishReason].
     */
    fun enqueueReply(
        pieces: List<String>,
        millisBetween: Long = 0,
        finishReason: FinishReason = FinishReason.END_OF_TURN,
        stopString: String? = null,
    ) {
        val steps = ArrayList<Step>()
        for (piece in pieces) {
            if (millisBetween > 0) steps += Step.Wait(millisBetween)
            steps += Step.Emit(GenerationEvent.TextDelta(piece))
        }
        steps += Step.Emit(GenerationEvent.Done(finishReason, stopString, pieces.joinToString(""), stats(pieces.size)))
        enqueue(steps)
    }

    /** Queues a generation that fails with [error] after emitting [pieces]. */
    fun enqueueFailure(error: EngineError, pieces: List<String> = emptyList()) {
        enqueue(pieces.map { Step.Emit(GenerationEvent.TextDelta(it)) } + Step.Emit(GenerationEvent.Error(error)))
    }

    // --- What happened ---

    private val recordedRequests = ArrayList<GenerationRequest>()

    /** Every request whose flow was collected, oldest first. */
    val requests: List<GenerationRequest> get() = recordedRequests

    /** Generations whose collection began. */
    var startedGenerations: Int = 0
        private set

    /** Generations that played their script to its end, or were ended by the engine. */
    var completedGenerations: Int = 0
        private set

    /** Generations whose collector was cancelled before the end. */
    var cancelledGenerations: Int = 0
        private set

    /** Generations being collected right now. */
    var activeGenerations: Int = 0
        private set

    /** Ids of the models passed to [load], oldest first, whether or not the load succeeded. */
    val loads: List<String> get() = recordedLoads
    private val recordedLoads = ArrayList<String>()

    var unloadCount: Int = 0
        private set

    var forgetCount: Int = 0
        private set

    /** How often [forgetKeepingSavedMemory] was called; it also counts towards nothing else. */
    var forgetKeepingSavedCount: Int = 0
        private set

    /** How often [markNotToBeSaved] was called. */
    var notToBeSavedMarks: Int = 0
        private set

    /** Every save asked for, as (conversation key, trigger), oldest first. */
    val saves = ArrayList<Pair<String, SaveTrigger>>()

    /** Every restore asked for, by conversation key, oldest first. */
    val restores = ArrayList<String>()

    /** What [saveMemory] answers. */
    var saveResult: MemorySaveResult = MemorySaveResult.NotSaved(MemorySaveResult.NotSaved.Reason.NOT_WORTH_IT)

    /** What [restoreMemory] answers. */
    var restoreResult: MemoryRestoreResult = MemoryRestoreResult.NothingSaved

    /**
     * When set, [saveMemory] runs this once the call is recorded and answers what it returns in
     * place of [saveResult]: for a test that needs a save to take time, to hang, or to write into
     * the vault it was given as the real engine would.
     */
    var onSave: (suspend (vault: MemoryVault, conversationKey: String, trigger: SaveTrigger) -> MemorySaveResult)? = null

    /** As [onSave], for [restoreMemory]. */
    var onRestore: (suspend (vault: MemoryVault, conversationKey: String, maxContextTokens: Int?) -> MemoryRestoreResult)? = null

    /** Every read-ahead asked for, oldest first. */
    val readAheads = ArrayList<GenerationRequest>()

    /** Read-aheads that ended because their caller was cancelled, or the engine was made to forget, unload or load. */
    var stoppedReadAheads: Int = 0
        private set

    /** Read-aheads under way right now. */
    var activeReadAheads: Int = 0
        private set

    /** What [readAhead] answers. */
    var readAheadResult: ReadAheadResult = ReadAheadResult.Skipped(ReadAheadResult.Skipped.Reason.NOTHING_TO_READ)

    /**
     * How long a read-ahead takes, in (virtual) milliseconds; null for one that runs until it is
     * stopped. [forget], [unload], a [load], a request, a save and a restore stop it, as the real engine's do.
     */
    var readAheadMillis: Long? = 0

    /**
     * False makes a read-ahead stop only for its caller's cancellation, so that a test can tell
     * that whoever drives the engine stops it, without relying on the engine to.
     */
    var readAheadGivesWay: Boolean = true

    /** The context size passed with every restore, in the order of [restores]. */
    val restoreContexts = ArrayList<Int?>()

    private val _memoryRestore = MutableStateFlow<MemoryRestoreState>(MemoryRestoreState.Idle)
    override val memoryRestore: StateFlow<MemoryRestoreState> = _memoryRestore.asStateFlow()

    /** Sets what [memoryRestore] reports, for a test of whoever shows it. */
    fun reportRestore(state: MemoryRestoreState) {
        _memoryRestore.value = state
    }

    // --- InferenceEngine ---

    /** Moves on whenever generations in flight must end; see the class comment. */
    private val epoch = MutableStateFlow(0)

    override suspend fun load(model: ModelSpec) {
        recordedLoads += model.id
        val current = _state.value
        if (current is EngineState.Ready && current.modelId == model.id) return
        epoch.value++
        _state.value = EngineState.Loading(model.id, 0f)
        try {
            delay(loadMillis)
        } catch (e: CancellationException) {
            _state.value = EngineState.Unloaded
            throw e
        }
        val failure = loadFailure
        loadFailure = null
        _state.value = if (failure != null) EngineState.Failed(model.id, failure) else EngineState.Ready(model.id)
    }

    override suspend fun unload() {
        unloadCount++
        epoch.value++
        _state.value = EngineState.Unloaded
    }

    override suspend fun forget() {
        forgetCount++
        epoch.value++
    }

    override suspend fun forgetKeepingSavedMemory() {
        forgetKeepingSavedCount++
        epoch.value++
    }

    override suspend fun readAhead(request: GenerationRequest): ReadAheadResult {
        readAheads += request
        activeReadAheads++
        val startEpoch = epoch.value
        val startRequests = interruptions.value
        try {
            val stop = combine(epoch, interruptions) { now, asked -> readAheadGivesWay && (now != startEpoch || asked != startRequests) }
            val stopped = when (val millis = readAheadMillis) {
                null -> stop.first { it }
                else -> withTimeoutOrNull(millis) { stop.first { it } } ?: false
            }
            if (stopped) {
                stoppedReadAheads++
                return ReadAheadResult.Stopped(keptTokens = 0, readTokens = 0)
            }
            return readAheadResult
        } catch (e: CancellationException) {
            stoppedReadAheads++
            throw e
        } finally {
            activeReadAheads--
        }
    }

    /** Moves on with every request, save and restore, which a read-ahead gives way to. */
    private val interruptions = MutableStateFlow(0)

    override suspend fun saveMemory(vault: MemoryVault, conversationKey: String, trigger: SaveTrigger): MemorySaveResult {
        interruptions.value++
        saves += conversationKey to trigger
        return onSave?.invoke(vault, conversationKey, trigger) ?: saveResult
    }

    override suspend fun restoreMemory(vault: MemoryVault, conversationKey: String, maxContextTokens: Int?): MemoryRestoreResult {
        interruptions.value++
        restores += conversationKey
        restoreContexts += maxContextTokens
        return onRestore?.invoke(vault, conversationKey, maxContextTokens) ?: restoreResult
    }

    override fun markNotToBeSaved() {
        notToBeSavedMarks++
    }

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = flow {
        interruptions.value++
        recordedRequests += request
        startedGenerations++
        activeGenerations++
        val startEpoch = epoch.value
        val emitted = StringBuilder()
        try {
            val loaded = (_state.value as? EngineState.Ready)?.modelId
            val steps = when {
                loaded == null -> listOf(Step.Emit(GenerationEvent.Error(EngineError.NotLoaded)))
                loaded != request.modelId ->
                    listOf(Step.Emit(GenerationEvent.Error(EngineError.WrongModel(request.modelId, loaded))))
                else -> scripts.removeFirstOrNull() ?: listOf(Step.Emit(GenerationEvent.Done(FinishReason.END_OF_TURN, stats = stats(0))))
            }
            for (step in steps) {
                val interrupted = when (step) {
                    is Step.Emit -> epoch.value != startEpoch
                    is Step.Wait -> withTimeoutOrNull(step.millis) { epoch.first { it != startEpoch } } != null
                    Step.Hang -> epoch.first { it != startEpoch } != startEpoch
                }
                if (interrupted) {
                    emit(GenerationEvent.Done(FinishReason.CANCELLED, text = emitted.toString(), stats = stats(0)))
                    break
                }
                if (step is Step.Emit) {
                    when (val event = step.event) {
                        is GenerationEvent.TextDelta -> emitted.append(event.text)
                        is GenerationEvent.TextReplaced -> emitted.setLength(maxOf(0, emitted.length - event.removed))
                        else -> Unit
                    }
                    emit(step.event)
                    if (step.event is GenerationEvent.Done || step.event is GenerationEvent.Error) break
                }
            }
            completedGenerations++
        } catch (e: CancellationException) {
            cancelledGenerations++
            throw e
        } finally {
            activeGenerations--
        }
    }

    private fun stats(generatedTokens: Int) = GenerationStats(
        promptTokens = 0, reusedPromptTokens = 0, generatedTokens = generatedTokens, promptMillis = 0, generationMillis = 0,
    )
}
