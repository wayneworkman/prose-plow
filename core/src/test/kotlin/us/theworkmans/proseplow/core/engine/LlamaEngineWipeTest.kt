package us.theworkmans.proseplow.core.engine

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import us.theworkmans.proseplow.core.engine.FakeNativeLlama.Call
import us.theworkmans.proseplow.core.prompt.PromptMessage

/**
 * The arrays of token ids a generation makes, and the bytes of the texts it hands to the
 * tokenizer, are overwritten when it ends, however it ends. The fake keeps a reference to every
 * array that crossed the seam in either direction: what the tokenizer gave back is what the
 * engine caches, and what was evaluated is what the engine cut from its tokenised prompt. No
 * token id of the fake is 0, so an array of zeros is one that was overwritten.
 */
class LlamaEngineWipeTest {

    private val question = "How does a sailing ship make way against the wind?"

    /** Every array the engine handed over or was handed, overwritten. */
    private fun FakeNativeLlama.assertEverythingWiped() {
        assertTrue("the tokenizer was used", tokenArraysReturned.isNotEmpty() && textArraysReceived.isNotEmpty())
        for (tokens in tokenArraysReturned) assertTrue("tokenised text left: ${tokens.toList()}", tokens.all { it == 0 })
        for (tokens in tokenArraysReceived) assertTrue("evaluated tokens left: ${tokens.toList()}", tokens.all { it == 0 })
        for (bytes in textArraysReceived) assertTrue("text bytes left", bytes.all { it == 0.toByte() })
    }

    @Test
    fun `a reply that ends its turn leaves no token array behind`() = runTest {
        val test = loadedEngine()
        test.fake.enqueueReply("Close-hauled.")
        val events = test.engine.generate(request(user(question))).toList()
        assertEquals(FinishReason.END_OF_TURN, events.done().finishReason)
        assertTrue("the prompt and the reply were evaluated", test.fake.tokenArraysReceived.size >= 2)
        test.fake.assertEverythingWiped()
    }

    @Test
    fun `a reply that ends at a stop string or at its length leaves none either`() = runTest {
        val test = loadedEngine()
        test.fake.enqueueReply("Close-hauled.$IM_END more")
        assertEquals(FinishReason.STOP_STRING, test.engine.generate(request(user(question))).toList().done().finishReason)
        test.fake.assertEverythingWiped()

        test.fake.clearCalls()
        test.fake.enqueueReply("abcdefghijklmnop")
        val events = test.engine.generate(request(user("Another question?"), maxNewTokens = 4)).toList()
        assertEquals(FinishReason.LENGTH, events.done().finishReason)
        test.fake.assertEverythingWiped()
    }

    @Test
    fun `a collector that stops listening while the prompt is read leaves none`() = runTest {
        val test = loadedEngine(settings = LlamaEngine.Settings(evaluationChunk = 8))
        lateinit var collecting: Job
        test.fake.afterChunk = { inMemory -> if (inMemory == 24) collecting.cancel() }
        collecting = launch { test.engine.generate(request(user(question))).collect { } }
        collecting.join()
        assertTrue(collecting.isCancelled)
        test.fake.assertEverythingWiped()
    }

    @Test
    fun `a collector that stops listening during the reply leaves none`() = runTest {
        val test = loadedEngine()
        lateinit var collecting: Job
        var samples = 0
        test.fake.beforeCall = { call -> if (call is Call.SampleNext && ++samples == 4) collecting.cancel() }
        test.fake.enqueueReply("abcdefghijklmnopqrstuvwxyz")
        collecting = launch { test.engine.generate(request(user(question))).collect { } }
        collecting.join()
        assertTrue(collecting.isCancelled)
        test.fake.assertEverythingWiped()
    }

    @Test
    fun `a generation the engine cancels with forget leaves none`() = runTest {
        val test = loadedEngine()
        var samples = 0
        var forgetting: Job? = null
        test.fake.beforeCall = { call ->
            if (call is Call.SampleNext && ++samples == 4) forgetting = launch(start = CoroutineStart.UNDISPATCHED) { test.engine.forget() }
        }
        test.fake.enqueueReply("abcdefghij")
        val events = test.engine.generate(request(user(question))).toList()
        checkNotNull(forgetting).join()
        assertEquals(FinishReason.CANCELLED, events.done().finishReason)
        test.fake.assertEverythingWiped()
    }

    @Test
    fun `a generation that fails in the native side leaves none`() = runTest {
        val test = loadedEngine()
        var evaluations = 0
        test.fake.beforeCall = { call ->
            if (call is Call.Evaluate && ++evaluations == 3) throw NativeLlamaException(NativeLlamaException.EVALUATION_FAILED, "broken")
        }
        test.fake.enqueueReply("abcdefghij")
        val events = test.engine.generate(request(user(question))).toList()
        assertTrue(events.last() is GenerationEvent.Error)
        test.fake.assertEverythingWiped()
    }

    @Test
    fun `a prompt too long for any context is refused and leaves none`() = runTest {
        val test = loadedEngine(model = testModel(contextLength = 64))
        val events = test.engine.generate(request(user("x".repeat(200)))).toList()
        assertTrue(events.error() is EngineError.PromptTooLong)
        assertTrue("nothing was evaluated", test.fake.tokenArraysReceived.isEmpty())
        test.fake.assertEverythingWiped()
    }

    @Test
    fun `every round of fitting the conversation to a context that could not grow as far leaves none`() = runTest {
        // As in the context tests: room for 512 tokens and not for the 1,024 the conversation asks for, so the first
        // round grows to 512 and the second fits the conversation to that with a tokeniser of its own.
        val events = growingEngine().let { test ->
            val talk: List<PromptMessage> = (1..4).flatMap { turn -> listOf(user("Q$turn " + "q".repeat(60)), assistant("A$turn " + "a".repeat(60))) } +
                user("The latest question, which must always be sent.")
            test.fake.enqueueReply("Yes.")
            test.engine.generate(request(*talk.toTypedArray()).copy(maxContextTokens = 2048)).toList().also { test.fake.assertEverythingWiped() }
        }
        assertEquals(1, events.filterIsInstance<GenerationEvent.ContextLimited>().size)
        assertTrue(events.done().stats.droppedMessages > 0)
    }

    private suspend fun TestScope.growingEngine(): TestEngine {
        val device = DeviceMemory(totalBytes = 1_000_000_000, availableBytes = 200_300_000, thresholdBytes = 0, low = false)
        lateinit var created: TestEngine
        val factory = FakeNativeLlamaFactory { spec ->
            FakeNativeLlama(contextTokens = spec.contextLength).also { fake ->
                fake.trainedContextTokens = spec.maxContextLength
                fake.bytesPerToken = 1000
                created.chatMl = fake.registerChatMl()
            }
        }
        val engine = LlamaEngine(
            factory = factory,
            nativeDispatcher = StandardTestDispatcher(testScheduler),
            nanoTime = { factory.opened.lastOrNull()?.second?.nanos ?: 0L },
            newSeed = { TEST_SEED },
            memoryGauge = { device },
        )
        created = TestEngine(engine, factory)
        engine.load(testModel(contextLength = 256, maxNewTokens = 32).copy(maxContextLength = 4096))
        return created
    }
}
