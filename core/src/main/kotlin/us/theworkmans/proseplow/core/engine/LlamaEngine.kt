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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
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
import us.theworkmans.proseplow.core.prompt.PromptMessage
import us.theworkmans.proseplow.core.prompt.PromptRole
import us.theworkmans.proseplow.core.prompt.PromptWhitespace
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
 *    the reply the reader sees loses its last token ([GenerationEvent.TextReplaced]). For a
 *    model that cannot cut its memory back ([ModelSpec.cutsMemoryBack]) two more checkpoints
 *    **follow the reply**: one is taken every [Settings.followingCheckpointInterval] tokens of
 *    the reply, and the latest two are kept. They serve a request that differs from the memory
 *    near the end of the reply. "Continue" after a reply was stopped is one: its prompt ends
 *    with the last token in memory, which must be read afresh for a prediction, and such a
 *    model cannot go back by one token. A reply whose text, tokenised again, is not the tokens
 *    that were sampled near its end (cut off at the length limit, whitespace trimmed at its end)
 *    is another. Where the request differs within the last interval of the reply, one of the
 *    two lies at or before that point and less than two intervals before it, so the request
 *    reads again at most two intervals and the difference, not the whole reply. They cannot
 *    help a request that differs from the memory before the reply's first token: a prompt
 *    format that writes an earlier answer differently from the turn that was being written
 *    (Qwen3.5 opens that turn with an empty think block and leaves it out of the history) makes
 *    the next new message read the whole answer again, whatever checkpoints follow it; step 5
 *    does that reading earlier. They are apart from the checkpoints of step 3 and never
 *    displace one of them, so at most [Settings.maxCheckpoints] and [FOLLOWING_CHECKPOINTS]
 *    more are held at any moment.
 * 5. **Reads ahead** when asked to ([readAhead]), after an answer: the conversation as the next
 *    new message will render it, read through the same steps 1 to 3 up to the start of that
 *    message, with nothing sampled. It is the reading that message would do anyway, done while
 *    the person reads the answer, and it gives way to everything else within one chunk.
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
     *   regenerating the last answer, and editing either of the last two user messages. The
     *   checkpoints that follow a reply ([followingCheckpointInterval]) are not among them.
     * @property followingCheckpointInterval every how many tokens of a reply a checkpoint that
     *   follows it is taken, for a model that cannot cut its memory back
     *   ([ModelSpec.cutsMemoryBack]); 0 switches them off. The latest [FOLLOWING_CHECKPOINTS]
     *   are kept, so a request whose tokens differ from the memory anywhere in the last this
     *   many tokens of the last reply reads again at most twice this many and the difference:
     *   "Continue" after a reply was stopped, and a reply whose end tokenises differently. A
     *   difference before the reply (Qwen3.5's think block) is beyond them; see [readAhead].
     *   32: a checkpoint is a copy of the recurrent state (19.3 MiB for Qwen3.5, 0.36 MiB for
     *   Boris-2), a few milliseconds every 32 tokens of a reply that takes Qwen3.5 more than
     *   three seconds to write on the tested phone, while reading 64 tokens again takes it about
     *   a second.
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
     * @property minSavedReadingSeconds the least time reading a memory again must take, by the
     *   model's reading curve on this device, for the memory to be saved or restored: below it
     *   reading again costs less than is worth keeping a large file for. It rules out a model by
     *   its numbers (Boris-2 reads its whole context in about 7 seconds), never by its name.
     * @property newReadingSecondsOnLeaving how long reading again what is new since the last save
     *   must take for a save when the app leaves the screen ([SaveTrigger.LEFT_SCREEN]): more than
     *   this. Leaving the screen happens often, and a save writes the whole memory.
     */
    data class Settings(
        val maxCheckpoints: Int = 4,
        val followingCheckpointInterval: Int = 32,
        val evaluationChunk: Int = 32,
        val deepReadTokens: Int = 1024,
        val minReplyTokens: Int = ReplyLength.MIN_TOKENS,
        val giveBackRereadTokens: Int = ContextPolicy.GRANULARITY,
        val minSavedReadingSeconds: Double = 20.0,
        val newReadingSecondsOnLeaving: Double = 10.0,
    ) {
        init {
            require(maxCheckpoints >= 0) { "maxCheckpoints must not be negative" }
            require(followingCheckpointInterval >= 0) { "followingCheckpointInterval must not be negative" }
            require(evaluationChunk >= 1) { "evaluationChunk must be at least 1" }
            require(deepReadTokens >= 0) { "deepReadTokens must not be negative" }
            require(minReplyTokens >= 1) { "minReplyTokens must be at least 1" }
            require(giveBackRereadTokens >= 0) { "giveBackRereadTokens must not be negative" }
            require(minSavedReadingSeconds >= 0 && newReadingSecondsOnLeaving >= 0) { "the reading times of saving must not be negative" }
        }
    }

    /**
     * A saved state of the memory when it held exactly the first [position] tokens. [follows]
     * marks a checkpoint that follows a reply ([Settings.followingCheckpointInterval]), or the
     * one a read-ahead keeps at its end ([readAhead]); the others were taken at boundaries of a
     * prompt, and only they count towards [Settings.maxCheckpoints].
     */
    private class Checkpoint(val position: Int, val handle: Int, val follows: Boolean = false)

    /**
     * The image a vault holds that the engine knows of: of [tokenCount] tokens of the
     * conversation [conversationKey], made with [modelId].
     *
     * While it is not [waiting], its tokens are the first [tokenCount] tokens of the memory: it
     * was written from this memory or read into it, and since then the memory has only grown,
     * because every place that makes the memory shorter than [tokenCount] deletes it first
     * ([dropSavedIfBeyondMemory]). So comparing lengths is comparing tokens.
     *
     * A [waiting] image is of another conversation than the memory's, found by a restore for
     * another one; the first request deletes it, unless a restore for its own conversation
     * comes first.
     */
    private class KnownImage(val vault: MemoryVault, val conversationKey: String, val modelId: String, val tokenCount: Int, val waiting: Boolean)

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

    /** The saved image the engine knows of, if any; see [KnownImage]. */
    private var savedImage: KnownImage? = null

    /**
     * Which conversation the memory is of: the key of the request or restore that last made it
     * agree with a prompt. Meaningful only while the memory is not empty.
     */
    private var memoryKey: String? = null

    /**
     * How many marks of [markNotToBeSaved] have been made, and how many of them the memory has
     * since been emptied after. The memory is marked while the first is ahead of the second.
     * Marks are made from any thread; [liftedMarks] is written on the native thread only.
     */
    private val marks = AtomicLong(0)
    @Volatile private var liftedMarks = 0L

    /**
     * The marks that were made before the operation now holding the engine was asked for: an
     * emptied memory lifts those, and none made later. Set at the start of every operation; 0
     * for one that must lift none (a restore, which fills the memory with what was saved).
     */
    private var marksBeforeOperation = 0L

    /** Moves on whenever a request is collected, so that a save in progress stops for it. */
    private val requests = AtomicInteger(0)

    /**
     * How many operations other than a read-ahead have asked for the engine and not yet ended:
     * requests, saves, restores, loads, unloads and forgetting. Raised the moment one is asked
     * for, before it waits for the mutex, so that a read-ahead stops for it within a chunk, and
     * one that has not begun does nothing ([readAhead]).
     */
    private val contenders = AtomicInteger(0)

    /**
     * Moves on whenever an operation other than a read-ahead gets the mutex. A read-ahead that
     * finds it moved since it was asked for does nothing: the memory may no longer be the one
     * whose answer it was asked to prepare.
     */
    private val operations = AtomicInteger(0)

    /**
     * Counts the read-aheads that have let go of the mutex, raised just after each lets go, so
     * that a save on leaving the screen can wait for the one holding it ([lockForLeaving]).
     */
    private val readAheadsEnded = MutableStateFlow(0L)

    private val _memoryRestore = MutableStateFlow<MemoryRestoreState>(MemoryRestoreState.Idle)
    override val memoryRestore: StateFlow<MemoryRestoreState> = _memoryRestore.asStateFlow()

    /** The limit the collector was last told of, so that the same one is not announced with every answer; null after a growth. */
    private var announcedContextLimit: Int? = null
    private val checkpoints = ArrayList<Checkpoint>()

    // --- Loading ---

    override suspend fun load(model: ModelSpec) {
        val current = _state.value
        if (current is EngineState.Ready && current.modelId == model.id) return
        epoch.incrementAndGet()
        val marksBefore = marks.get()
        turn {
            withContext(nativeDispatcher) {
                if (native != null && loaded?.id == model.id) return@withContext
                marksBeforeOperation = marksBefore
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
        val marksBefore = marks.get()
        // Not cancellable: once asked for, the model must really be freed.
        withContext(NonCancellable) {
            turn {
                withContext(nativeDispatcher) {
                    marksBeforeOperation = marksBefore
                    closeNative()
                    _state.value = EngineState.Unloaded
                }
            }
        }
    }

    override suspend fun forget() = forget(keepSaved = false)

    override suspend fun forgetKeepingSavedMemory() = forget(keepSaved = true)

    /**
     * Empties the memory. The engine lets go of a saved image before anything else. With
     * [keepSaved] it is not deleted, so that no failure on the way can delete it either;
     * otherwise it is deleted once the memory has been cleared (or the model freed), never
     * before: the conversation does not wait in memory for the disk.
     */
    private suspend fun forget(keepSaved: Boolean) {
        epoch.incrementAndGet()
        val marksBefore = marks.get()
        // Not cancellable: the app is locking, and the conversation must be gone when this returns.
        withContext(NonCancellable) {
            turn {
                withContext(nativeDispatcher) {
                    marksBeforeOperation = marksBefore
                    // The memory is cleared first and the image deleted second: deleting is work
                    // on the disk, which may be slow, and the conversation is not to wait in memory
                    // for it. The image is let go of here and deleted below, however the clearing went.
                    val image = savedImage
                    savedImage = null
                    _memoryRestore.value = MemoryRestoreState.Idle
                    try {
                        val current = native
                        if (current == null) {
                            // No model, so no memory: nothing is left that a mark could concern.
                            liftMarks()
                        } else if (!clearMemory(current)) {
                            // The memory could not be cleared; freeing the model clears it for certain.
                            closeNative()
                            _state.value = EngineState.Unloaded
                        }
                    } finally {
                        if (!keepSaved && image != null) deleteQuietly(image.vault)
                    }
                }
            }
        }
    }

    /**
     * Opens [model] with a context of its starting size, or, when that does not fit the policy's
     * opening budget (a quarter of the device's memory, by [ContextPolicy.openingBudgetBytes]),
     * of half that size, and half again, down to [MIN_OPENING_CONTEXT]. Below that the model is
     * not opened: a context too small to hold a question is of no use. What is available at the
     * moment and the floor kept for everything else are not asked here: they bound growth only,
     * because a refusal now would leave no model at all and Android makes room for the app in
     * front by closing others.
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
        val budget = contextPolicy.openingBudgetBytes(memoryGauge.read())
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

    /**
     * Frees the loaded model, if any, and forgets everything about its memory, a saved image the
     * engine knows of included: no other model can use it, and this one has nothing it begins.
     */
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
        memoryKey = null
        liftMarks()
        _memoryRestore.value = MemoryRestoreState.Idle
        try {
            current?.close()
        } catch (_: RuntimeException) {
            // Nothing more can be done for it; the engine no longer refers to it either way.
        } finally {
            // After the model's memory is freed: deleting is work on the disk (see forget).
            dropSavedImage()
        }
    }

    /**
     * Empties the native memory, which also frees every checkpoint, and the engine's copy with
     * it. Returns false when the native side failed; the copy is emptied regardless, so the next
     * generation notices the mismatch and tries again. A saved image the memory no longer
     * begins with is deleted, and the marks made before the operation in progress are lifted.
     */
    private fun clearMemory(native: NativeLlama): Boolean {
        memory.wipe()
        checkpoints.clear()
        liftMarks()
        val cleared = try {
            native.clearMemory()
            true
        } catch (_: RuntimeException) {
            false
        }
        // The key stays: a request clears and then rebuilds the memory as its own conversation's.
        // An empty memory is never saved, whatever key it has. The image goes only now, after the
        // native memory: deleting is work on the disk (see forget).
        dropSavedIfBeyondMemory()
        return cleared
    }

    // --- What was saved, and what may be ---

    /**
     * Deletes the saved image the engine knows of, if any, and forgets it. A vault that fails to
     * delete is not asked again by the engine: the vault's own checks of the conversation catch
     * an image that outlived its memory before it could be restored.
     */
    private fun dropSavedImage() {
        val image = savedImage ?: return
        savedImage = null
        try {
            image.vault.delete()
        } catch (_: Exception) {
            // See above; the engine goes on without it.
        }
    }

    /**
     * Called after everything that makes the memory shorter: a saved image longer than the
     * memory now is no longer its beginning, so it is deleted at once (see [KnownImage]).
     */
    private fun dropSavedIfBeyondMemory() {
        val image = savedImage ?: return
        if (!image.waiting && memory.size < image.tokenCount) dropSavedImage()
    }

    /** The memory is empty: the marks made before the operation in progress no longer apply. */
    private fun liftMarks() {
        if (marksBeforeOperation > liftedMarks) liftedMarks = marksBeforeOperation
    }

    private fun isMarked(): Boolean = marks.get() > liftedMarks

    override fun markNotToBeSaved() {
        marks.incrementAndGet()
    }

    /**
     * How long [model] takes to read a conversation on this device: the catalogue's curve
     * scaled by the factor last measured (1 until then). A model whose catalogue entry has no
     * curve gets a flat one from the speed it was last seen to read at, and none at all before
     * that.
     */
    private fun readingTimes(model: ModelSpec): ReadingTimes {
        val catalogue = model.readingTimes
        return when {
            catalogue.isKnown -> if (measuredReadingFactor > 0) catalogue.scaled(measuredReadingFactor) else catalogue
            measuredReadSpeed > 0 -> ReadingTimes.flat(measuredReadSpeed)
            else -> ReadingTimes.UNKNOWN
        }
    }

    /** Whether reading [tokens] again takes [model] at least the least worth saving; false when nobody knows. */
    private fun worthSaving(model: ModelSpec, tokens: Int): Boolean {
        val times = readingTimes(model)
        return times.isKnown && times.seconds(tokens) >= settings.minSavedReadingSeconds
    }

    // --- Saving and restoring the memory ---

    override suspend fun saveMemory(vault: MemoryVault, conversationKey: String, trigger: SaveTrigger): MemorySaveResult {
        val startEpoch = epoch.get()
        // Counted at once: a read-ahead stops for the save within a chunk, and none begins.
        contenders.incrementAndGet()
        try {
            // On leaving the screen a reply, a load or anything else that holds the engine goes
            // first, and the save is not made, except a read-ahead, which gives way to it; the
            // lock's save waits its turn (its caller bounds the wait).
            if (trigger == SaveTrigger.LEFT_SCREEN) {
                if (!lockForLeaving()) return MemorySaveResult.NotSaved(MemorySaveResult.NotSaved.Reason.BUSY)
            } else {
                mutex.lock()
            }
            try {
                operations.incrementAndGet()
                return withContext(nativeDispatcher) { save(vault, conversationKey, trigger, startEpoch, coroutineContext.job) }
            } finally {
                mutex.unlock()
            }
        } finally {
            contenders.decrementAndGet()
        }
    }

    /**
     * Takes the mutex for a save on leaving the screen when nothing but a read-ahead holds it,
     * and returns false at once when anything else does. A read-ahead stops within a chunk for
     * the save, which is already counted among the [contenders], and lets go; this waits for
     * that ([readAheadsEnded]) instead of answering that the engine is busy, and asks again. A
     * read-ahead asked for meanwhile does nothing while the save is counted, so the asking ends.
     */
    private suspend fun lockForLeaving(): Boolean {
        while (true) {
            val ended = readAheadsEnded.value
            if (mutex.tryLock()) return true
            if (mutex.holdsLock(READ_AHEAD)) {
                readAheadsEnded.first { it != ended }
                continue
            }
            // Something else holds it, or a read-ahead let go of it between the two looks.
            if (mutex.tryLock()) return true
            if (!mutex.holdsLock(READ_AHEAD)) return false
        }
    }

    /**
     * Runs [block] with the mutex held, as an operation a read-ahead gives way to: counted among
     * the [contenders] from the moment it is asked for until it has ended, and moving
     * [operations] on once it has the mutex.
     */
    private suspend inline fun <T> turn(block: () -> T): T {
        contenders.incrementAndGet()
        try {
            return mutex.withLock {
                operations.incrementAndGet()
                block()
            }
        } finally {
            contenders.decrementAndGet()
        }
    }

    /** [saveMemory] on [nativeDispatcher] with the mutex held. */
    private fun save(vault: MemoryVault, conversationKey: String, trigger: SaveTrigger, startEpoch: Int, job: Job): MemorySaveResult {
        val startRequests = requests.get()
        fun notSaved(reason: MemorySaveResult.NotSaved.Reason) = MemorySaveResult.NotSaved(reason)
        val native = native ?: return notSaved(MemorySaveResult.NotSaved.Reason.NOT_LOADED)
        val model = loaded ?: return notSaved(MemorySaveResult.NotSaved.Reason.NOT_LOADED)
        val tokens = memory.size
        if (tokens == 0 || native.memoryTokenCount() != tokens) return notSaved(MemorySaveResult.NotSaved.Reason.EMPTY)
        if (memoryKey != conversationKey) return notSaved(MemorySaveResult.NotSaved.Reason.OTHER_CONVERSATION)
        if (isMarked()) return notSaved(MemorySaveResult.NotSaved.Reason.MARKED)
        val times = readingTimes(model)
        if (!worthSaving(model, tokens)) return notSaved(MemorySaveResult.NotSaved.Reason.NOT_WORTH_IT)
        if (trigger == SaveTrigger.LEFT_SCREEN) {
            // What the vault already holds of this memory need not be read again; only what is new.
            val image = savedImage
            val since = if (image != null && !image.waiting && image.vault === vault && image.conversationKey == conversationKey) image.tokenCount else 0
            if (times.secondsBetween(since, tokens) <= settings.newReadingSecondsOnLeaving) return notSaved(MemorySaveResult.NotSaved.Reason.LITTLE_NEW)
        }
        val handles = imageCheckpoints()
        // An image the engine knows of in another vault is not the one this save replaces.
        if (savedImage?.vault !== vault) dropSavedImage()
        // Stops at the next piece for a request, for forgetting, unloading or loading, and for the
        // caller; and the vault is given the same condition, so that a wait of its own for its
        // storage ends for them too.
        val stopped = { epoch.get() != startEpoch || requests.get() != startRequests || !job.isActive }
        val writer: MemoryWriter
        val bytes: Long
        try {
            bytes = native.memoryStateBytes(handles)
            writer = vault.beginSave(
                SavedMemory(
                    conversationKey = conversationKey,
                    modelId = model.id,
                    contextTokens = native.contextSize(),
                    tokenCount = tokens,
                    readingSeconds = times.seconds(tokens),
                    imageBytes = bytes,
                ),
                stopped,
            )
        } catch (_: Exception) {
            return notSaved(MemorySaveResult.NotSaved.Reason.FAILED)
        }
        try {
            val written = writer.use {
                val written = native.exportMemory(handles, writer.buffer, writer.firstPieceOffset) { piece, last ->
                    !stopped() && writer.write(piece, last)
                }
                if (written != bytes) return notSaved(MemorySaveResult.NotSaved.Reason.FAILED)
                writer.commit()
                written
            }
            savedImage = KnownImage(vault, conversationKey, model.id, tokens, waiting = false)
            return MemorySaveResult.Saved(tokens, written)
        } catch (e: NativeLlamaException) {
            return notSaved(if (e.code == NativeLlamaException.MEMORY_STOPPED) MemorySaveResult.NotSaved.Reason.STOPPED else MemorySaveResult.NotSaved.Reason.FAILED)
        } catch (_: Exception) {
            return notSaved(MemorySaveResult.NotSaved.Reason.FAILED)
        }
    }

    /**
     * The handles of the checkpoints that go into an image, in the order the import reports
     * them back: those that follow the last reply first, the latest first (so in descending
     * order of their lengths), and then those of the prompts, oldest first (ascending), as many
     * as an image carries beside them. Every checkpoint the engine holds fits:
     * [Settings.maxCheckpoints] and [FOLLOWING_CHECKPOINTS], 6 by default, against the 8 of
     * [NativeLlama.MAX_MEMORY_CHECKPOINTS], which is also what the native session holds at once.
     * Should they ever not fit, the following ones and the latest of the prompts' go in: they
     * are the ones the next request after a restore most likely goes back to. The order is what
     * tells a restore which is which ([labelRestored]): an image carries no more than the
     * lengths.
     */
    private fun imageCheckpoints(): IntArray {
        val followers = checkpoints.filter { it.follows }.sortedByDescending { it.position }.take(NativeLlama.MAX_MEMORY_CHECKPOINTS)
        val others = checkpoints.filter { !it.follows }.sortedBy { it.position }.takeLast(NativeLlama.MAX_MEMORY_CHECKPOINTS - followers.size)
        return (followers + others).map { it.handle }.toIntArray()
    }

    /**
     * Which of the checkpoints a restore brought back, in the order [imageCheckpoints] wrote
     * them, followed the last reply. Those of the prompts were written in ascending order of
     * their lengths, the following ones before them in descending order; so the following ones
     * are the leading checkpoints, at most [FOLLOWING_CHECKPOINTS], each of which is longer than
     * the one after it. A checkpoint that has none after it, or one not longer than the next, is
     * taken for one of the prompts'. That is every checkpoint of an image without following
     * ones, among them an image written before there were any (all ascending); the second
     * following checkpoint of an image that has no checkpoint of a prompt at all; and a
     * following checkpoint behind which a later prompt was read in part. Taking a following one
     * for a prompt's costs at most a step back that reads more, never a wrong memory: every
     * checkpoint is a true state of its length either way.
     */
    private fun labelRestored(model: ModelSpec, restored: List<MemoryCheckpoint>): Pair<List<MemoryCheckpoint>, List<MemoryCheckpoint>> {
        if (!takesFollowingCheckpoints(model)) return emptyList<MemoryCheckpoint>() to restored
        var leading = 0
        while (leading < FOLLOWING_CHECKPOINTS && leading + 1 < restored.size && restored[leading].tokenCount > restored[leading + 1].tokenCount) leading++
        return restored.take(leading) to restored.drop(leading)
    }

    /** Whether the engine keeps a checkpoint that follows a reply for [model]; see [Settings.followingCheckpointInterval]. */
    private fun takesFollowingCheckpoints(model: ModelSpec): Boolean = !model.cutsMemoryBack && settings.followingCheckpointInterval > 0

    override suspend fun restoreMemory(vault: MemoryVault, conversationKey: String, maxContextTokens: Int?): MemoryRestoreResult {
        val startEpoch = epoch.get()
        return turn {
            withContext(nativeDispatcher) { restore(vault, conversationKey, maxContextTokens, startEpoch, coroutineContext.job) }
        }
    }

    /** [restoreMemory] on [nativeDispatcher] with the mutex held. */
    private suspend fun restore(vault: MemoryVault, conversationKey: String, maxContextTokens: Int?, startEpoch: Int, job: Job): MemoryRestoreResult {
        // Nothing a restore empties is rebuilt from nothing: it is filled with what was saved.
        marksBeforeOperation = 0
        val native = native ?: return MemoryRestoreResult.NotRestored(MemoryRestoreResult.NotRestored.Reason.NOT_LOADED)
        val model = loaded ?: return MemoryRestoreResult.NotRestored(MemoryRestoreResult.NotRestored.Reason.NOT_LOADED)
        val image = try {
            vault.saved()
        } catch (_: MemoryVaultUnavailableException) {
            // Nothing is known against the image, and nothing was touched: it stays as it was.
            return MemoryRestoreResult.NotRestored(MemoryRestoreResult.NotRestored.Reason.UNAVAILABLE)
        } catch (_: Exception) {
            return refuseImage(vault, MemoryRestoreResult.NotRestored.Reason.FAILED)
        }
        if (image == null) {
            // The vault holds nothing: what the engine knew of it is gone.
            if (savedImage?.vault === vault) savedImage = null
            return MemoryRestoreResult.NothingSaved
        }
        if (image.modelId != model.id) return refuseImage(vault, MemoryRestoreResult.NotRestored.Reason.OTHER_MODEL)
        if (image.conversationKey != conversationKey) {
            // It waits for its own conversation; the next request deletes it.
            savedImage = KnownImage(vault, image.conversationKey, image.modelId, image.tokenCount, waiting = true)
            return MemoryRestoreResult.NotRestored(MemoryRestoreResult.NotRestored.Reason.OTHER_CONVERSATION)
        }
        if (memory.size > 0 && memoryKey == conversationKey) {
            // The memory is this conversation already. The image is its beginning only when it
            // is the very one the engine wrote or read; anything else cannot be placed.
            val known = savedImage
            if (known != null && !known.waiting && known.vault === vault && known.conversationKey == conversationKey && known.tokenCount == image.tokenCount) {
                return MemoryRestoreResult.AlreadyHeld
            }
            return refuseImage(vault, MemoryRestoreResult.NotRestored.Reason.MEMORY_IN_USE)
        }

        _memoryRestore.value = MemoryRestoreState.Restoring(model.id)
        // Whatever the memory holds is another conversation's, or nothing: it goes. The image is
        // not the engine's to keep track of until it has been read; one in another vault goes.
        if (savedImage?.vault === vault) savedImage = null else dropSavedImage()
        if (memory.size > 0 || native.memoryTokenCount() > 0) {
            if (!clearMemory(native)) return readAgain(vault, native, MemoryRestoreResult.NotRestored.Reason.FAILED)
        }
        if (isMarked()) return readAgain(vault, native, MemoryRestoreResult.NotRestored.Reason.MARKED)
        if (image.tokenCount < 1 || !worthSaving(model, image.tokenCount)) {
            return readAgain(vault, native, if (image.tokenCount < 1) MemoryRestoreResult.NotRestored.Reason.FAILED else MemoryRestoreResult.NotRestored.Reason.NOT_WORTH_IT)
        }

        // The size: the saved one, or the smallest step that holds the tokens, by the same
        // growth and memory checks as for a request, with nothing to carry over.
        try {
            val sizing = Sizing(native, model, maxContextTokens, tell = null)
            val limit = sizing.contextLimit()
            if (image.tokenCount > limit) return readAgain(vault, native, MemoryRestoreResult.NotRestored.Reason.CONTEXT_TOO_SMALL)
            val wanted = maxOf(image.tokenCount, minOf(image.contextTokens, limit))
            if (native.contextSize() < wanted) sizing.growTowards(wanted, limit)
        } catch (_: NativeLlamaException) {
            if (native.contextSize() <= 0) {
                // No context exists any more, of any size: the model is as good as not loaded.
                closeNative()
                _state.value = EngineState.Failed(model.id, EngineError.LoadFailed(model.id, TOO_LITTLE_MEMORY))
                deleteQuietly(vault)
                _memoryRestore.value = MemoryRestoreState.ReadAgain(model.id)
                return MemoryRestoreResult.NotRestored(MemoryRestoreResult.NotRestored.Reason.CONTEXT_TOO_SMALL)
            }
            return readAgain(vault, native, MemoryRestoreResult.NotRestored.Reason.CONTEXT_TOO_SMALL)
        }
        if (native.contextSize() < image.tokenCount) return readAgain(vault, native, MemoryRestoreResult.NotRestored.Reason.CONTEXT_TOO_SMALL)

        val reader = try {
            vault.beginRestore()
        } catch (_: MemoryVaultUnavailableException) {
            return keepImage(vault, image, native, MemoryRestoreResult.NotRestored.Reason.UNAVAILABLE)
        } catch (_: Exception) {
            return readAgain(vault, native, MemoryRestoreResult.NotRestored.Reason.FAILED)
        }
        // Whether a stop came from here (forgetting, unloading, loading, the caller) and not from
        // the vault's refusal of a piece: only the vault's refusal says anything against the image.
        var stoppedHere = false
        try {
            reader.use {
                if (reader.memory != image) return readAgain(vault, native, MemoryRestoreResult.NotRestored.Reason.FAILED)
                // Stops for forgetting, unloading or loading, and for the caller.
                val source = MemorySource {
                    if (epoch.get() != startEpoch || !job.isActive) {
                        stoppedHere = true
                        -1
                    } else {
                        reader.read()
                    }
                }
                val restored = native.importMemory(reader.buffer, reader.firstPieceOffset, source)
                // Restored only when the import returned normally and the memory is as long as the header said.
                val tokens = native.memoryTokens()
                try {
                    val count = native.memoryTokenCount()
                    val positions = restored.map { it.tokenCount }
                    val agrees = tokens.size == image.tokenCount && count == image.tokenCount &&
                        positions.all { it in 1..image.tokenCount } && positions.distinct().size == positions.size
                    if (!agrees) return readAgain(vault, native, MemoryRestoreResult.NotRestored.Reason.FAILED)
                    memory.wipe()
                    memory.addAll(tokens)
                } finally {
                    tokens.fill(0)
                }
                // The checkpoints that followed the last reply are kept as such; of the prompts'
                // the latest are kept, as many as the engine keeps. The rest are freed.
                val (followers, prompts) = labelRestored(model, restored)
                val ordered = prompts.sortedBy { it.tokenCount }
                val surplus = (ordered.size - settings.maxCheckpoints).coerceAtLeast(0)
                for (checkpoint in ordered.take(surplus)) native.dropCheckpoint(checkpoint.handle)
                checkpoints.clear()
                for (checkpoint in ordered.drop(surplus)) checkpoints += Checkpoint(checkpoint.tokenCount, checkpoint.handle)
                for (checkpoint in followers) checkpoints += Checkpoint(checkpoint.tokenCount, checkpoint.handle, follows = true)
            }
        } catch (e: NativeLlamaException) {
            if (e.code == NativeLlamaException.MEMORY_STOPPED) {
                return if (stoppedHere) {
                    keepImage(vault, image, native, MemoryRestoreResult.NotRestored.Reason.STOPPED)
                } else {
                    readAgain(vault, native, MemoryRestoreResult.NotRestored.Reason.STOPPED)
                }
            }
            return readAgain(vault, native, MemoryRestoreResult.NotRestored.Reason.FAILED)
        } catch (_: MemoryVaultUnavailableException) {
            return keepImage(vault, image, native, MemoryRestoreResult.NotRestored.Reason.UNAVAILABLE)
        } catch (_: Exception) {
            return readAgain(vault, native, MemoryRestoreResult.NotRestored.Reason.FAILED)
        }
        memoryKey = conversationKey
        savedImage = KnownImage(vault, conversationKey, model.id, image.tokenCount, waiting = false)
        _memoryRestore.value = MemoryRestoreState.Restored(model.id, image.tokenCount)
        return MemoryRestoreResult.Restored(image.tokenCount)
    }

    /** A restore that did not begin: the image is deleted, and the memory is left as it was. */
    private fun refuseImage(vault: MemoryVault, reason: MemoryRestoreResult.NotRestored.Reason): MemoryRestoreResult {
        if (savedImage?.vault === vault) savedImage = null
        deleteQuietly(vault)
        return MemoryRestoreResult.NotRestored(reason)
    }

    /**
     * A restore that began and failed: the memory is emptied (and the model freed if even that
     * fails, as [forget] does), the image deleted, and the conversation is read again at the
     * next request.
     */
    private fun readAgain(vault: MemoryVault, native: NativeLlama, reason: MemoryRestoreResult.NotRestored.Reason): MemoryRestoreResult {
        val modelId = loaded?.id
        savedImage = null
        try {
            if (!clearMemory(native)) {
                closeNative()
                _state.value = EngineState.Unloaded
            }
        } finally {
            // After the memory: deleting is work on the disk (see forget).
            deleteQuietly(vault)
        }
        if (modelId != null) _memoryRestore.value = MemoryRestoreState.ReadAgain(modelId)
        return MemoryRestoreResult.NotRestored(reason)
    }

    /**
     * A restore that began and could not finish for a reason that says nothing against [image]:
     * the vault could not tell now ([MemoryVaultUnavailableException]), or the engine itself or
     * the caller stopped it (a lock forgets and destroys the key in the middle of a restore). The
     * memory is emptied as for any failure (and the model freed if even that fails, which lets go
     * of the image too, as an unload does), but the image is not deleted: it waits, as one of
     * another conversation does, for a restore of its own conversation, and the first request
     * deletes it. A lock lets go of it without deleting it ([forgetKeepingSavedMemory]).
     */
    private fun keepImage(vault: MemoryVault, image: SavedMemory, native: NativeLlama, reason: MemoryRestoreResult.NotRestored.Reason): MemoryRestoreResult {
        savedImage = null
        if (clearMemory(native)) {
            savedImage = KnownImage(vault, image.conversationKey, image.modelId, image.tokenCount, waiting = true)
        } else {
            closeNative()
            _state.value = EngineState.Unloaded
        }
        _memoryRestore.value = MemoryRestoreState.Idle
        return MemoryRestoreResult.NotRestored(reason)
    }

    /** Tells [vault] to delete; see [dropSavedImage] for why a failure is let go. */
    private fun deleteQuietly(vault: MemoryVault) {
        try {
            vault.delete()
        } catch (_: Exception) {
            // As in dropSavedImage.
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
    override fun generate(request: GenerationRequest): Flow<GenerationEvent> {
        // Taken when the request is handed over, which is after the caller built it: a mark made
        // later may concern messages this request still holds, and its emptying must not lift it.
        val marksBefore = marks.get()
        return flow {
            val startEpoch = epoch.get()
            // A save in progress stops at its next piece and lets this request have the engine,
            // and so does a read-ahead, which stops within a chunk.
            requests.incrementAndGet()
            contenders.incrementAndGet()
            try {
                emitAll(
                    channelFlow {
                        mutex.withLock {
                            operations.incrementAndGet()
                            generate(request, startEpoch, marksBefore)
                        }
                    }
                        .buffer(Channel.UNLIMITED)
                        .flowOn(nativeDispatcher),
                )
            } finally {
                contenders.decrementAndGet()
            }
        }
    }

    private suspend fun ProducerScope<GenerationEvent>.generate(request: GenerationRequest, startEpoch: Int, marksBefore: Long) {
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
        marksBeforeOperation = marksBefore
        // A saved image of another conversation, or one still waiting for a restore, is not what
        // this request will make of the memory.
        val image = savedImage
        if (image != null && (image.waiting || image.conversationKey != request.conversationKey)) dropSavedImage()
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
     * What [model]'s prompt format is given for [messages] of [request]: the request's system
     * prompt and extra system text, each left out when it is blank, and both when the model was
     * trained without a system message, which then gets no system text of any kind.
     */
    private fun promptInput(model: ModelSpec, request: GenerationRequest, messages: List<PromptMessage>): PromptInput {
        fun String?.forModel(): String? = this?.takeIf { model.supportsSystemPrompt }?.takeUnless(PromptWhitespace::isBlank)
        return PromptInput(
            messages = messages,
            systemPrompt = request.systemPrompt.forModel(),
            extraSystemText = request.extraSystemText.forModel(),
            continueFinalMessage = request.continueFinalMessage,
        )
    }

    // --- Reading ahead ---

    override suspend fun readAhead(request: GenerationRequest): ReadAheadResult {
        val startEpoch = epoch.get()
        val startOperations = operations.get()
        // Whatever has asked for the engine goes first, and a closed gate means that someone
        // needs the processor: neither waits for a read-ahead, which is never needed.
        if (contenders.get() > 0 || gate.isClosed) return ReadAheadResult.Skipped(ReadAheadResult.Skipped.Reason.BUSY)
        mutex.lock(READ_AHEAD)
        try {
            return withContext(nativeDispatcher) { ReadAhead(request, startEpoch, startOperations, coroutineContext.job).run() }
        } finally {
            mutex.unlock(READ_AHEAD)
            // After letting go: a save on leaving the screen that waits for this asks for the mutex now.
            readAheadsEnded.update { it + 1 }
        }
    }

    /**
     * One read-ahead ([readAhead]) from start to finish, on [nativeDispatcher] with the mutex
     * held; used once. Its steps are those of a request ([Generation]) up to the point where the
     * next message begins, with the rules that make it the work of the next request done
     * earlier and nothing more:
     * - It keeps of the memory exactly what a request for the same prompt would keep, and does
     *   nothing at all when that is the whole memory: the next request would read nothing again.
     * - It does nothing where a request would clear the memory, grow the context or leave turns
     *   out: those are a request's decisions, made with the message it carries.
     * - It stops between and inside chunks the moment anything else asks for the engine
     *   ([contenders]), the engine moves to another epoch, the [gate] closes or its caller is
     *   cancelled, and does nothing when an operation had the engine since it was asked for.
     *   The memory then holds what it read, a true state and the beginning of the next prompt.
     * - The only time it empties the memory is a failure of the native side once it had begun
     *   to change it, when the memory can no longer be trusted, exactly as a request does.
     *
     * @param startOperations [operations] when the read-ahead was asked for.
     */
    private inner class ReadAhead(
        private val request: GenerationRequest,
        private val startEpoch: Int,
        private val startOperations: Int,
        private val job: Job,
    ) {
        private var tokeniser: PromptTokeniser? = null

        /** True once the memory may have been changed: a failure from then on empties it. */
        private var touched = false

        /** Polled between and inside chunks: something goes first. */
        private fun shouldStop(): Boolean = epoch.get() != startEpoch || !job.isActive || contenders.get() > 0 || gate.isClosed

        private fun skipped(reason: ReadAheadResult.Skipped.Reason) = ReadAheadResult.Skipped(reason)

        /** Runs the read-ahead and wipes the token arrays it made, however it ends (see [Generation.run]). */
        fun run(): ReadAheadResult {
            val native = native ?: return skipped(ReadAheadResult.Skipped.Reason.NOT_LOADED)
            try {
                return steps(native)
            } catch (_: NativeLlamaException) {
                return failed(native)
            } catch (_: RuntimeException) {
                return failed(native)
            } finally {
                tokeniser?.wipe()
                tokeniser = null
            }
        }

        private fun failed(native: NativeLlama): ReadAheadResult {
            if (touched) clearMemory(native)
            return skipped(ReadAheadResult.Skipped.Reason.FAILED)
        }

        private fun steps(native: NativeLlama): ReadAheadResult {
            if (shouldStop() || operations.get() != startOperations) return skipped(ReadAheadResult.Skipped.Reason.BUSY)
            val model = loaded ?: return skipped(ReadAheadResult.Skipped.Reason.NOT_LOADED)
            if (request.modelId != model.id) return skipped(ReadAheadResult.Skipped.Reason.NOT_LOADED)
            if (model.family.continuesText) return skipped(ReadAheadResult.Skipped.Reason.NOT_A_CHAT)
            if (request.continueFinalMessage) return skipped(ReadAheadResult.Skipped.Reason.INVALID)
            if (memory.size == 0 || memoryKey != request.conversationKey || native.memoryTokenCount() != memory.size) {
                return skipped(ReadAheadResult.Skipped.Reason.OTHER_CONVERSATION)
            }
            // A read-ahead rebuilds nothing from an empty memory, so it lifts no mark.
            marksBeforeOperation = 0

            // The conversation as the next message will render it, with an empty message in its place.
            val prompt = try {
                model.promptFormat.render(promptInput(model, request, request.messages + PromptMessage(PromptRole.USER, "")))
            } catch (_: PromptFormatException) {
                return skipped(ReadAheadResult.Skipped.Reason.INVALID)
            }
            if (!PromptTokeniser.withinLimit(prompt)) return skipped(ReadAheadResult.Skipped.Reason.NO_ROOM)
            val tokeniser = PromptTokeniser(native).also { tokeniser = it }
            val tokenised = tokeniser.tokenise(prompt)
            val tokens = tokenised.tokens
            // The last boundary opens the reply; the one before it is where the placeholder, and so
            // the next message, begins.
            val target = tokeniser.boundaryPosition(tokenised, prompt.boundaries.lastIndex - 1)
            if (target == null || target < 1) return skipped(ReadAheadResult.Skipped.Reason.NO_BOUNDARY)

            // The next request and its reply in the context as it is, without growing it and
            // without leaving anything out; a size the user chose below it counts at once.
            val room = minOf(Sizing(native, model, request.maxContextTokens, tell = null).contextLimit(), native.contextSize())
            val requested = (request.maxNewTokens ?: model.defaultMaxNewTokens).coerceAtLeast(1)
            if (tokens.size + minOf(requested, ReplyLength.maxTokens(room)) > room) return skipped(ReadAheadResult.Skipped.Reason.NO_ROOM)

            val common = memory.commonPrefixWith(tokens)
            if (common >= memory.size) return skipped(ReadAheadResult.Skipped.Reason.NOTHING_TO_READ)
            if (!goBack(native, minOf(common, target))) return skipped(ReadAheadResult.Skipped.Reason.WOULD_CLEAR)
            val kept = memory.size
            return read(native, model, tokenised, target, kept)
        }

        /**
         * Shortens the memory to [keep] tokens, or to the latest checkpoint at or before that
         * when the model cannot cut its memory back, as a request does; returns false, with
         * nothing changed, where a request would have to clear the memory instead.
         */
        private fun goBack(native: NativeLlama, keep: Int): Boolean {
            touched = true
            if (keep > 0 && native.rewindTo(keep)) {
                memory.truncate(keep)
                dropSavedIfBeyondMemory()
                dropCheckpointsAfter(native, keep)
                return true
            }
            // A refused cut leaves the memory exactly as it was.
            val checkpoint = checkpoints.filter { it.position in 1..keep }.maxByOrNull { it.position } ?: return false
            if (!native.restoreCheckpoint(checkpoint.handle)) {
                throw NativeLlamaException(NativeLlamaException.EVALUATION_FAILED, "A checkpoint could not be restored")
            }
            memory.truncate(checkpoint.position)
            dropSavedIfBeyondMemory()
            dropCheckpointsAfter(native, checkpoint.position)
            return true
        }

        /**
         * Reads the prompt from the memory's end up to [target], taking the checkpoints a request
         * would take at the boundaries on the way, and at [target] one that follows the reply
         * (for a model that cannot cut its memory back; the next request takes it over as its
         * own, see [Generation]). A stop leaves what was read.
         */
        private fun read(native: NativeLlama, model: ModelSpec, tokenised: TokenisedPrompt, target: Int, kept: Int): ReadAheadResult {
            val tokens = tokenised.tokens
            // The boundaries a request for this prompt would keep checkpoints at, before the target.
            val stops = if (settings.maxCheckpoints == 0) {
                emptyList()
            } else {
                checkNotNull(tokeniser).boundaryPositions(tokenised, after = kept, limit = settings.maxCheckpoints).filter { it < target }
            }
            val observer = object : EvaluationObserver {
                override fun onProgress(evaluatedTokens: Int) = Unit
                override fun isCancelled(): Boolean = shouldStop()
            }
            for (stop in stops + target) {
                if (stop > memory.size) {
                    val part = tokens.copyOfRange(memory.size, stop)
                    val chunk = if (stop >= settings.deepReadTokens) model.deepReadChunk else settings.evaluationChunk
                    val evaluated: Int
                    try {
                        evaluated = native.evaluate(part, chunk, observer)
                        memory.addAll(part, evaluated)
                    } finally {
                        part.fill(0)
                    }
                    if (evaluated < part.size) {
                        if (!shouldStop()) throw NativeLlamaException(NativeLlamaException.EVALUATION_FAILED, "Evaluation stopped early without being stopped")
                        return ReadAheadResult.Stopped(kept, memory.size - kept)
                    }
                }
                if (stop < target) {
                    saveCheckpoint(native, stop)
                } else if (!model.cutsMemoryBack && settings.maxCheckpoints > 0) {
                    saveFollowingCheckpoint(native)
                }
            }
            return ReadAheadResult.Read(kept, memory.size - kept)
        }
    }

    /**
     * The decisions about the size of the loaded model's context ([ContextPolicy]), shared by a
     * generation and a restore. Runs on [nativeDispatcher] with the mutex held.
     *
     * @param chosen the size the user chose as the most the context may grow to, or null for
     *   automatic ([GenerationRequest.maxContextTokens]).
     * @param tell receives the events about the size, in a generation; null for a restore, which
     *   has nobody to tell. A limit found without being told is still this model's limit, and
     *   the next generation that runs into it says so.
     */
    private inner class Sizing(
        private val native: NativeLlama,
        private val model: ModelSpec,
        private val chosen: Int?,
        private val tell: (suspend (GenerationEvent) -> Unit)?,
    ) {
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
         * How large this model's context may become now: what the policy allows for the model
         * on this device (under the user's choice of size, [chosen], if there is one), and no
         * more than a limit that was found earlier by running into it.
         *
         * Left to itself, a context that has grown is not cut back because the model was since
         * seen to read more slowly: the speed is measured again with every prompt, and a limit
         * that followed every measurement down would leave out turns of a conversation that was
         * already read, and read the rest again. What a conversation was given, it keeps until
         * it is left; the limit of the moment counts for what is asked for from then on. A size
         * the user chose is another matter: it is the user's, and holds at once.
         */
        fun contextLimit(): Int {
            val allowed = contextPolicy.limit(
                start = openedContext,
                maxContext = minOf(model.maxContextLength, native.maxContextSize),
                bytesPerToken = bytesPerToken(),
                totalBytes = memoryGauge.read().totalBytes,
                chosen = chosen,
                readingTimes = readingTimes(model),
            )
            val limit = minOf(allowed, foundContextLimit ?: Int.MAX_VALUE)
            return (if (chosen == null) maxOf(limit, native.contextSize()) else limit).coerceAtLeast(1)
        }

        /**
         * Makes the context larger, towards [wanted] tokens and never beyond [limit]: to the
         * smallest step that holds [wanted] if the device's memory allows that now, otherwise
         * to the largest size it does allow below that step, a step or any whole multiple of
         * [NATIVE_CONTEXT_STEP] tokens ([largestWithin]). Where it ends below what was wanted,
         * that size is the model's limit from now on, and the collector is told so once.
         *
         * The device's memory is read here, at the moment of the decision. The policy budgets
         * what the tokens of a context take. The native side counts more: what a session
         * reserves whatever the size of its context, most of which is never touched. That part
         * is the same before and after a step, so it is added to the policy's budget, and the
         * sum is what the native side is given; it keeps the context's memory within it at
         * every moment, choosing itself whether the model's memory can be carried over.
         *
         * The native side counts a size larger than any it has created a little higher than its
         * own line through the sizes it has (a margin it keeps against costing too low), while
         * the policy's limit is the token arithmetic alone. At the very largest sizes the step
         * the policy allows can therefore cost the native side just more than the budget; the
         * size taken is then the largest below it that the native side costs within the budget,
         * not the step before, which can be tens of thousands of tokens smaller.
         */
        suspend fun growTowards(wanted: Int, limit: Int) {
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
            fun fits(size: Int): Boolean = nativeCost(size).let { it != null && it <= budget }
            val step = steps.lastOrNull { step -> step > from && step <= target && fits(step) }
            val affordable = if (step == target) step else largestWithin(above = step ?: from, below = target, fits = ::fits) ?: step
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
            tell?.invoke(GenerationEvent.ContextGrown(fromTokens = from, toTokens = native.contextSize(), rereading = held > 0 && memory.size == 0))
            if (affordable < target) limitFound(affordable, GenerationEvent.ContextLimited.Reason.MEMORY)
        }

        /**
         * The largest whole multiple of [NATIVE_CONTEXT_STEP] tokens strictly between [above] and
         * [below] that [fits], or null when none does. What a context costs rises with its size,
         * so the multiples are searched by halving: a few questions to the native side, each of
         * which is arithmetic on figures it already has, and none of which allocates anything.
         */
        private fun largestWithin(above: Int, below: Int, fits: (Int) -> Boolean): Int? {
            var low = above / NATIVE_CONTEXT_STEP + 1
            var high = (below - 1) / NATIVE_CONTEXT_STEP
            if (low > high || !fits(low * NATIVE_CONTEXT_STEP)) return null
            // Invariant: the multiple low fits; every multiple above high does not, or is out of range.
            while (low < high) {
                val middle = low + (high - low + 1) / 2
                if (fits(middle * NATIVE_CONTEXT_STEP)) low = middle else high = middle - 1
            }
            return low * NATIVE_CONTEXT_STEP
        }

        /** After a resize: the engine's copy of the memory is what the native side kept, which is all of it or nothing. */
        private fun agreeWithNativeMemory() {
            if (native.memoryTokenCount() == memory.size) return
            if (native.memoryTokenCount() == 0) {
                // Not carried over. The checkpoints went with it, and a saved image of it is no longer its beginning.
                memory.wipe()
                checkpoints.clear()
                dropSavedIfBeyondMemory()
            } else if (!clearMemory(native)) {
                throw NativeLlamaException(NativeLlamaException.EVALUATION_FAILED, "The memory could not be cleared")
            }
        }

        /** [tokens] is this model's limit until it is loaded again; the collector is told once. */
        private suspend fun limitFound(tokens: Int, reason: GenerationEvent.ContextLimited.Reason) {
            foundContextLimit = minOf(tokens, foundContextLimit ?: Int.MAX_VALUE)
            announceLimit(tokens, reason)
        }

        private suspend fun announceLimit(tokens: Int, reason: GenerationEvent.ContextLimited.Reason) {
            val tell = tell ?: return
            if (announcedContextLimit == tokens) return
            announcedContextLimit = tokens
            tell(GenerationEvent.ContextLimited(tokens, reason))
        }
    }

    // --- Checkpoints, shared by a generation and a read-ahead (native thread, mutex held) ---

    /** Checkpoints beyond [position] describe a conversation that no longer exists. */
    private fun dropCheckpointsAfter(native: NativeLlama, position: Int) {
        val stale = checkpoints.filter { it.position > position }
        for (checkpoint in stale) native.dropCheckpoint(checkpoint.handle)
        checkpoints.removeAll(stale.toSet())
    }

    /**
     * Keeps a checkpoint of the memory as it is now, at [position] (its length), among
     * those of the prompts, dropping the oldest of them first when [Settings.maxCheckpoints]
     * are held, so that the native side never holds more than the limit. The checkpoints
     * that follow a reply are not counted and never dropped here. When one of them sits at
     * this very position it is taken over as this boundary's checkpoint instead of a copy
     * being made: it is the same state, and the next reply takes its own.
     */
    private fun saveCheckpoint(native: NativeLlama, position: Int) {
        val existing = checkpoints.firstOrNull { it.position == position }
        if (existing != null && !existing.follows) return
        while (checkpoints.count { !it.follows } >= settings.maxCheckpoints) {
            val oldest = checkpoints.filter { !it.follows }.minBy { it.position }
            native.dropCheckpoint(oldest.handle)
            checkpoints.remove(oldest)
        }
        if (existing != null) {
            checkpoints.remove(existing)
            checkpoints += Checkpoint(position, existing.handle)
            return
        }
        val handle = native.saveCheckpoint()
        if (handle != NativeLlama.NO_CHECKPOINT) checkpoints += Checkpoint(position, handle)
    }

    /**
     * Takes a checkpoint that follows the reply, of the memory as it is now, and keeps the
     * latest [FOLLOWING_CHECKPOINTS]. The oldest following one is dropped before the new one
     * is taken, so that no more than [Settings.maxCheckpoints] and [FOLLOWING_CHECKPOINTS]
     * are ever held, even for a moment (with the defaults: 4 and 1 held, then 4 and 2). Called
     * on the native thread between two tokens of the reply, or where a read-ahead ends, never
     * inside a native computation.
     */
    private fun saveFollowingCheckpoint(native: NativeLlama) {
        while (checkpoints.count { it.follows } >= FOLLOWING_CHECKPOINTS) {
            val oldest = checkpoints.filter { it.follows }.minBy { it.position }
            native.dropCheckpoint(oldest.handle)
            checkpoints.remove(oldest)
        }
        // A checkpoint of this length is this state already. During a reply none is: every
        // checkpoint of a prompt lies at or before the reply's start.
        if (checkpoints.any { it.position == memory.size }) return
        val handle = native.saveCheckpoint()
        if (handle != NativeLlama.NO_CHECKPOINT) checkpoints += Checkpoint(memory.size, handle, follows = true)
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
        private val sizing = Sizing(native, model, request.maxContextTokens) { out.send(it) }
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

        /**
         * Runs the generation and, however it ends (finished, stopped, failed or cancelled),
         * overwrites the arrays of token ids it made on the way: the tokenised prompts and the
         * tokeniser's cache. They spell out the conversation, and the garbage collector would
         * otherwise leave them in the heap until it reuses the memory. The engine's own copy of
         * the memory ([memory]) is not among them: it outlives the generation, and is wiped when
         * the memory is cleared.
         */
        suspend fun run() {
            try {
                runSteps()
            } finally {
                tokeniser?.wipe()
                tokeniser = null
            }
        }

        private suspend fun runSteps() {
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
            var limit = sizing.contextLimit()
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
                // From here on the memory is made to agree with this prompt: it is this conversation's.
                memoryKey = request.conversationKey
                val wanted = minOf(limit, fitted.prompt.tokens.size + minOf(requestedNewTokens, ReplyLength.maxTokens(limit)))
                giveBackIfLeft(fitted.prompt.tokens, wanted)
                reuseMemory(fitted.prompt.tokens)
                if (native.contextSize() >= wanted) break
                sizing.growTowards(wanted, limit)
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

            sample(fitted.prompt, maxNewTokens = minOf(requestedNewTokens, contextSize - tokens.size))
        }

        // --- The size of the context ---

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

        // --- Step 1: render, tokenise, fit ---

        private fun fitPrompt(contextSize: Int, requestedNewTokens: Int): ContextBudget.Result {
            val messages = request.messages
            if (messages.none { it.role == PromptRole.USER }) {
                throw PromptFormatException("The conversation has no user message.")
            }
            // Each round tokenises afresh; what the previous round's tokeniser made is not used again.
            this.tokeniser?.wipe()
            val tokeniser = PromptTokeniser(native)
            this.tokeniser = tokeniser
            // A model that continues text is only ever given the latest message: there are no
            // older turns to leave out, and none must be reported as left out.
            val candidates = if (model.family.continuesText) listOf(0) else ContextBudget.startCandidates(messages)
            val rendered = HashMap<Int, Prompt>()
            fun rendering(start: Int): Prompt = rendered.getOrPut(start) {
                model.promptFormat.render(promptInput(model, request, messages.subList(start, messages.size)))
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

        /** The tokeniser of the latest round of fitting; [run] wipes it when the generation ends. */
        private var tokeniser: PromptTokeniser? = null

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
                dropSavedIfBeyondMemory()
                dropCheckpointsAfter(keep)
                return
            }
            // This model cannot remove the end of its memory: go back to a checkpoint instead.
            val checkpoint = checkpoints.filter { it.position in 1..keep }.maxByOrNull { it.position }
            if (checkpoint != null && native.restoreCheckpoint(checkpoint.handle)) {
                memory.truncate(checkpoint.position)
                dropSavedIfBeyondMemory()
                dropCheckpointsAfter(checkpoint.position)
                return
            }
            clearMemory()
        }

        private fun dropCheckpointsAfter(position: Int) = dropCheckpointsAfter(native, position)

        private fun clearMemory() {
            if (!this@LlamaEngine.clearMemory(native)) {
                throw NativeLlamaException(NativeLlamaException.EVALUATION_FAILED, "The memory could not be cleared")
            }
        }

        // --- Step 3: evaluate the rest of the prompt, with checkpoints ---

        /**
         * Returns false when the engine interrupted the evaluation.
         *
         * A boundary where the memory already ends is one of the prompt's last boundaries too
         * when a checkpoint that follows a reply stands there: the one a read-ahead keeps where
         * the next message begins. It is taken over as that boundary's checkpoint, as the request
         * would have taken one had it read up to it itself. At such a boundary without one,
         * nothing is taken: a request takes checkpoints only of what it reads.
         */
        private suspend fun evaluatePrompt(prompt: TokenisedPrompt): Boolean {
            val tokens = prompt.tokens
            val start = memory.size
            val stops = if (settings.maxCheckpoints == 0) {
                emptyList()
            } else {
                checkNotNull(tokeniser).boundaryPositions(prompt, after = maxOf(start - 1, 0), limit = settings.maxCheckpoints)
            }
            for (stop in stops + tokens.size) {
                if (stop > memory.size) {
                    val base = memory.size
                    val part = tokens.copyOfRange(base, stop)
                    standAside()
                    // A part that leaves a long conversation in memory is read in the model's deep chunk.
                    val chunk = if (stop >= settings.deepReadTokens) model.deepReadChunk else settings.evaluationChunk
                    val evaluated: Int
                    try {
                        evaluated = native.evaluate(part, chunk, PromptObserver(base, tokens.size))
                        memory.addAll(part, evaluated)
                    } finally {
                        // The native side and the engine's copy of the memory have what they need of it.
                        part.fill(0)
                    }
                    if (evaluated < part.size) {
                        if (interrupted()) return false
                        job.ensureActive()
                        throw NativeLlamaException(NativeLlamaException.EVALUATION_FAILED, "Evaluation stopped early without being cancelled")
                    }
                }
                if (stop < tokens.size && (stop > start || checkpoints.any { it.position == stop && it.follows })) saveCheckpoint(stop)
            }
            return true
        }

        private fun saveCheckpoint(position: Int) = saveCheckpoint(native, position)

        private fun saveFollowingCheckpoint() = saveFollowingCheckpoint(native)

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

        /**
         * Samples the reply to [prompt], which is in memory as evaluated. The presence and
         * frequency penalties count the reply from [replyStart] on.
         */
        private suspend fun sample(prompt: TokenisedPrompt, maxNewTokens: Int) {
            val sampling = request.sampling ?: model.sampling
            // A continued message already has its first word; only a new reply loses leading space,
            // and only a chat model's: a model that continues text keeps every character it writes.
            val continuing = request.continueFinalMessage && !PromptWhitespace.isBlank(request.messages.last().content)
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
                    replyStart = if (continuing) replyStart(prompt) else SamplerConfig.REPLY_START_AT_SAMPLING,
                ),
            )
            val reply = ReplyAssembler(
                stopStrings = model.stopStrings + request.stopStrings,
                dropLeadingWhitespace = !continuing && !model.family.continuesText,
            )
            this.reply = reply
            val single = IntArray(1)
            val follows = takesFollowingCheckpoints(model)
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
                val evaluated = try {
                    native.evaluate(single, 1, observer)
                } finally {
                    // The id is in the native memory and, below, in the engine's copy; not left here as well.
                    single[0] = 0
                }
                if (evaluated == 1) {
                    memory.add(token)
                    // The memory now holds the prompt and generatedTokens tokens of the reply.
                    if (follows && generatedTokens % settings.followingCheckpointInterval == 0) saveFollowingCheckpoint()
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

        /**
         * Where the answer being continued began in the model's memory: the token position of the
         * prompt's [Prompt.replyContentStart], so that the presence and frequency penalties count
         * the whole answer and not only what is sampled after "Continue". [prompt] is the prompt
         * as fitted and evaluated, after any older turns were left out, so its token positions are
         * the memory's. [SamplerConfig.REPLY_START_AT_SAMPLING] when the tokenizer puts no token
         * boundary at the marker: a position inside a token would count text that is not the
         * answer's, and counting from where sampling begins is the safer mistake.
         */
        private fun replyStart(prompt: TokenisedPrompt): Int =
            checkNotNull(tokeniser).replyContentPosition(prompt) ?: SamplerConfig.REPLY_START_AT_SAMPLING

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

        /**
         * Every size of context the native side creates is a whole multiple of this many tokens:
         * llama.cpp rounds a size up to it, and the native side costs a size as so rounded.
         */
        private const val NATIVE_CONTEXT_STEP: Int = 256

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

        /**
         * How many checkpoints that follow a reply are kept: the latest and the one before it.
         * With one, a reply that ended just after a checkpoint was taken leaves none before a
         * difference a few tokens before its end; with two, one always lies within two intervals
         * before any point of the reply's last interval.
         */
        const val FOLLOWING_CHECKPOINTS: Int = 2

        /** More rounds of fitting than a context has ways to shrink mean that something is wrong. */
        private const val MAX_FITTING_ROUNDS = 8

        /** The owner a read-ahead holds the mutex as, so that a save on leaving the screen can tell it from anything else. */
        private val READ_AHEAD = Any()
    }
}
