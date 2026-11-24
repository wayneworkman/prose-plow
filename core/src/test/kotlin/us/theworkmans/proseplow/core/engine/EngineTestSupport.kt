package us.theworkmans.proseplow.core.engine

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import us.theworkmans.proseplow.core.model.ModelFamily
import us.theworkmans.proseplow.core.model.ModelSpec
import us.theworkmans.proseplow.core.model.SamplingParams
import us.theworkmans.proseplow.core.prompt.PromptMessage
import us.theworkmans.proseplow.core.prompt.PromptRole

/*
 * Shared set-up for the engine tests: a small model specification, an engine over a
 * FakeNativeLlama on the test's own dispatcher, and short names for the things every test reads.
 */

internal const val TEST_MODEL_ID = "test-model"

internal const val IM_END = "<|im_end|>"

/** The seed the test engines hand out, so that a test can tell it from one a request supplied. */
internal const val TEST_SEED = 4242

internal fun user(text: String) = PromptMessage(PromptRole.USER, text)

internal fun assistant(text: String) = PromptMessage(PromptRole.ASSISTANT, text)

/**
 * A model for tests. By default it is a plain-text ChatML model with a small context, greedy
 * sampling and its marker as a stop string.
 */
internal fun testModel(
    family: ModelFamily = ModelFamily.PLAIN_CHATML,
    contextLength: Int = 2000,
    maxNewTokens: Int = 64,
    sampling: SamplingParams = SamplingParams(),
    stopStrings: List<String> = if (family == ModelFamily.PLAIN_CHATML) listOf(IM_END) else emptyList(),
    stopTokenIds: List<Int> = emptyList(),
    bannedTokenIds: List<Int> = emptyList(),
    supportsSystemPrompt: Boolean = true,
    id: String = TEST_MODEL_ID,
    deletionTokenId: Int? = null,
    deepReadChunk: Int = 128,
) = ModelSpec(
    id = id,
    displayName = "Test model",
    description = "A model that exists only in tests.",
    family = family,
    ggufFileName = "$id.gguf",
    contextLength = contextLength,
    defaultMaxNewTokens = maxNewTokens,
    sampling = sampling,
    stopStrings = stopStrings,
    stopTokenIds = stopTokenIds,
    bannedTokenIds = bannedTokenIds,
    supportsSystemPrompt = supportsSystemPrompt,
    sourceRepository = "example/test-model",
    licence = "Apache-2.0",
    deepReadChunk = deepReadChunk,
    deletionTokenId = deletionTokenId,
)

internal fun request(
    vararg messages: PromptMessage,
    systemPrompt: String? = null,
    continueFinalMessage: Boolean = false,
    sampling: SamplingParams? = null,
    maxNewTokens: Int? = null,
    stopStrings: List<String> = emptyList(),
    modelId: String = TEST_MODEL_ID,
) = GenerationRequest(
    modelId = modelId,
    messages = messages.toList(),
    systemPrompt = systemPrompt,
    continueFinalMessage = continueFinalMessage,
    sampling = sampling,
    maxNewTokens = maxNewTokens,
    stopStrings = stopStrings,
)

/** The ids of the ChatML tokens registered in a test fake. */
internal class ChatMlTokens(val imStart: Int, val imEnd: Int, val thinkOpen: Int, val thinkClose: Int)

/**
 * Registers the ChatML markers as control tokens, as a Qwen vocabulary has them. A plain-text
 * ChatML model is unaffected: its prompt is one text segment, which is never parsed for them.
 */
internal fun FakeNativeLlama.registerChatMl() = ChatMlTokens(
    imStart = addControl("<|im_start|>"),
    imEnd = addControl(IM_END, endOfGeneration = true),
    thinkOpen = addControl("<think>"),
    thinkClose = addControl("</think>"),
)

/** An engine under test together with what it was built from. */
internal class TestEngine(val engine: LlamaEngine, val factory: FakeNativeLlamaFactory) {
    /** The fake of the model loaded last. */
    val fake: FakeNativeLlama get() = factory.last

    lateinit var chatMl: ChatMlTokens
}

/**
 * An engine whose native calls run on a dispatcher of this test's scheduler. Its clock is the
 * loaded fake's virtual clock and its seeds are always [TEST_SEED].
 */
internal fun TestScope.newEngine(
    settings: LlamaEngine.Settings = LlamaEngine.Settings(),
    gate: GenerationGate = GenerationGate(),
    configure: (FakeNativeLlama) -> Unit = {},
): TestEngine {
    lateinit var created: TestEngine
    val factory = FakeNativeLlamaFactory { spec ->
        FakeNativeLlama(contextTokens = spec.contextLength).also { fake ->
            created.chatMl = fake.registerChatMl()
            configure(fake)
        }
    }
    val engine = LlamaEngine(
        factory = factory,
        nativeDispatcher = StandardTestDispatcher(testScheduler),
        settings = settings,
        nanoTime = { factory.opened.lastOrNull()?.second?.nanos ?: 0L },
        newSeed = { TEST_SEED },
        gate = gate,
    )
    created = TestEngine(engine, factory)
    return created
}

/** [newEngine] with [model] already loaded. */
internal suspend fun TestScope.loadedEngine(
    model: ModelSpec = testModel(),
    settings: LlamaEngine.Settings = LlamaEngine.Settings(),
    gate: GenerationGate = GenerationGate(),
    configure: (FakeNativeLlama) -> Unit = {},
): TestEngine = newEngine(settings, gate, configure).also { it.engine.load(model) }

// --- Reading event lists ---

internal fun List<GenerationEvent>.text(): String = filterIsInstance<GenerationEvent.TextDelta>().joinToString("") { it.text }

internal fun List<GenerationEvent>.done(): GenerationEvent.Done {
    val last = lastOrNull()
    check(last is GenerationEvent.Done) { "The generation did not end with Done: $this" }
    return last
}

internal fun List<GenerationEvent>.error(): EngineError {
    val last = lastOrNull()
    check(last is GenerationEvent.Error) { "The generation did not end with Error: $this" }
    return last.error
}

internal fun List<GenerationEvent>.progress(): List<GenerationEvent.PromptProgress> = filterIsInstance<GenerationEvent.PromptProgress>()

// --- Reading the fake's call record ---

internal inline fun <reified T : FakeNativeLlama.Call> FakeNativeLlama.callsOf(): List<T> = calls.filterIsInstance<T>()

/** The tokens of the prompt evaluations among the recorded calls: every evaluate before the first sample. */
internal fun FakeNativeLlama.promptEvaluations(): List<List<Int>> =
    calls.takeWhile { it !is FakeNativeLlama.Call.SampleNext }.filterIsInstance<FakeNativeLlama.Call.Evaluate>().map { it.tokens }
