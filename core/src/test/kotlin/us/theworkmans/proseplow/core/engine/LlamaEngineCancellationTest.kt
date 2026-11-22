package us.theworkmans.proseplow.core.engine

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import us.theworkmans.proseplow.core.engine.FakeNativeLlama.Call
import us.theworkmans.proseplow.core.prompt.PlainChatMlFormat
import us.theworkmans.proseplow.core.prompt.PromptInput
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stopping a generation: by cancelling its collector, and by the engine itself when it is told to
 * forget or unload. Also what keeps generations apart: one dispatcher, one at a time.
 *
 * In the single-threaded tests a cancellation "from outside" is issued from a hook inside the
 * fake, at an exact point of the native work; the last tests use a real thread instead.
 */
class LlamaEngineCancellationTest {

    private val longQuestion = "Please explain, in as much detail as you can, how a sailing ship makes way against the wind."

    private fun promptOf(question: String): String =
        PlainChatMlFormat.render(PromptInput(listOf(user(question)))).text()

    // --- Cancelling the collector ---

    @Test
    fun `cancelling while the prompt is evaluated stops at the next chunk and keeps the engine usable`() = runTest {
        val test = loadedEngine(settings = LlamaEngine.Settings(evaluationChunk = 8))
        val received = ArrayList<GenerationEvent>()
        lateinit var collecting: Job
        test.fake.afterChunk = { inMemory -> if (inMemory == 24) collecting.cancel() }
        collecting = launch { test.engine.generate(request(user(longQuestion))).collect { received += it } }
        collecting.join()

        assertTrue(collecting.isCancelled)
        assertEquals("evaluation stopped at the chunk after the cancellation", 24, test.fake.memory.size)
        assertTrue("nothing was sampled", test.fake.callsOf<Call.SampleNext>().isEmpty())
        assertTrue("a cancelled collector gets no closing event", received.none { it is GenerationEvent.Done || it is GenerationEvent.Error })

        // The engine knows exactly what is in memory and builds on it.
        test.fake.afterChunk = {}
        test.fake.clearCalls()
        test.fake.enqueueReply("With the sails close-hauled.")
        val events = test.engine.generate(request(user(longQuestion))).toList()
        assertEquals("With the sails close-hauled.", events.done().text)
        assertEquals(24, events.done().stats.reusedPromptTokens)
        assertTrue(test.fake.callsOf<Call.ClearMemory>().isEmpty())
        assertEquals(promptOf(longQuestion) + "With the sails close-hauled.", test.fake.memoryText)
    }

    @Test
    fun `cancelling while the reply is generated stops within one token and keeps the engine usable`() = runTest {
        val test = loadedEngine()
        lateinit var collecting: Job
        var samples = 0
        test.fake.beforeCall = { call -> if (call is Call.SampleNext && ++samples == 4) collecting.cancel() }
        test.fake.enqueueReply("abcdefghijklmnopqrstuvwxyz")
        collecting = launch { test.engine.generate(request(user("Alphabet?"))).collect { } }
        collecting.join()

        assertEquals("no token was sampled after the cancellation", 4, test.fake.callsOf<Call.SampleNext>().size)
        // Three reply tokens had been evaluated; the fourth was sampled but not evaluated.
        assertEquals(promptOf("Alphabet?") + "abc", test.fake.memoryText)

        // Continuing the stopped answer builds on what is in memory.
        test.fake.beforeCall = {}
        test.fake.clearCalls()
        test.fake.enqueueReply("defg")
        val events = test.engine.generate(
            request(user("Alphabet?"), assistant("abc"), continueFinalMessage = true),
        ).toList()
        assertEquals("defg", events.done().text)
        assertEquals("all but the last token were reused", promptOf("Alphabet?").length + 2, events.done().stats.reusedPromptTokens)
        assertEquals(promptOf("Alphabet?") + "abcdefg", test.fake.memoryText)
    }

    @Test
    fun `a collection cancelled before it starts touches nothing`() = runTest {
        val test = loadedEngine()
        val collecting = launch(start = CoroutineStart.LAZY) { test.engine.generate(request(user("Hi"))).collect { } }
        collecting.cancel()
        collecting.join()
        assertTrue(test.fake.calls.isEmpty())
    }

    // --- The engine ending a generation ---

    @Test
    fun `forget during a reply ends it as cancelled with the text so far, then clears the memory`() = runTest {
        val test = loadedEngine()
        var samples = 0
        var forgetting: Job? = null
        test.fake.beforeCall = { call ->
            // Started on the spot, as another thread would: it raises the flag and then waits
            // for the generation to let go of the engine.
            if (call is Call.SampleNext && ++samples == 4) forgetting = launch(start = CoroutineStart.UNDISPATCHED) { test.engine.forget() }
        }
        test.fake.enqueueReply("abcdefghij")
        val events = test.engine.generate(request(user("Alphabet?"))).toList()
        checkNotNull(forgetting).join()

        val done = events.done()
        assertEquals(FinishReason.CANCELLED, done.finishReason)
        assertEquals("abcd", done.text)
        assertEquals("abcd", events.text())
        assertEquals(4, done.stats.generatedTokens)
        assertEquals(0, test.fake.memory.size)
        assertTrue(test.fake.checkpoints.isEmpty())
        assertEquals(EngineState.Ready(TEST_MODEL_ID), test.engine.state.value)
    }

    @Test
    fun `forget during prompt evaluation ends the generation as cancelled without text`() = runTest {
        val test = loadedEngine(settings = LlamaEngine.Settings(evaluationChunk = 8))
        var forgetting: Job? = null
        test.fake.afterChunk = { inMemory ->
            if (inMemory == 16) forgetting = launch(start = CoroutineStart.UNDISPATCHED) { test.engine.forget() }
        }
        val events = test.engine.generate(request(user(longQuestion))).toList()
        checkNotNull(forgetting).join()

        assertEquals(FinishReason.CANCELLED, events.done().finishReason)
        assertEquals("", events.done().text)
        assertEquals(0, events.done().stats.generatedTokens)
        assertTrue(test.fake.callsOf<Call.SampleNext>().isEmpty())
        assertEquals(0, test.fake.memory.size)
    }

    @Test
    fun `text held back as a possible stop string is delivered when the engine cancels`() = runTest {
        val test = loadedEngine()
        var samples = 0
        var forgetting: Job? = null
        test.fake.beforeCall = { call ->
            if (call is Call.SampleNext && ++samples == 5) forgetting = launch(start = CoroutineStart.UNDISPATCHED) { test.engine.forget() }
        }
        test.fake.enqueueReply("ab <|im_end")
        val events = test.engine.generate(request(user("Hi"))).toList()
        checkNotNull(forgetting).join()
        assertEquals(FinishReason.CANCELLED, events.done().finishReason)
        assertEquals("ab <|", events.done().text)
    }

    @Test
    fun `unload during a reply ends it as cancelled and frees the model`() = runTest {
        val test = loadedEngine()
        val fake = test.fake
        var samples = 0
        var unloading: Job? = null
        fake.beforeCall = { call ->
            if (call is Call.SampleNext && ++samples == 3) unloading = launch(start = CoroutineStart.UNDISPATCHED) { test.engine.unload() }
        }
        fake.enqueueReply("abcdefghij")
        val events = test.engine.generate(request(user("Alphabet?"))).toList()
        checkNotNull(unloading).join()

        assertEquals(FinishReason.CANCELLED, events.done().finishReason)
        assertEquals("abc", events.done().text)
        assertTrue(fake.closed)
        assertEquals(EngineState.Unloaded, test.engine.state.value)
    }

    @Test
    fun `a generation that was waiting its turn when the engine was told to forget never runs`() = runTest {
        val test = loadedEngine()
        // Two collections begin; the engine is told to forget while the first is generating.
        // The second began before that, so it must not run afterwards either.
        var samples = 0
        var forgetting: Job? = null
        test.fake.beforeCall = { call ->
            if (call is Call.SampleNext && ++samples == 2) forgetting = launch(start = CoroutineStart.UNDISPATCHED) { test.engine.forget() }
        }
        test.fake.enqueueReply("first reply")
        test.fake.enqueueReply("second reply")
        val first = async { test.engine.generate(request(user("One"))).toList() }
        val second = async { test.engine.generate(request(user("Two"))).toList() }
        assertEquals(FinishReason.CANCELLED, first.await().done().finishReason)
        assertEquals(FinishReason.CANCELLED, second.await().done().finishReason)
        assertEquals("", second.await().done().text)
        checkNotNull(forgetting).join()
        assertEquals(0, test.fake.memory.size)
        assertFalse("the second conversation never reached the model", test.fake.callsOf<Call.Tokenize>().any { "Two" in it.text })

        // A request made after the forget runs normally (and gets the reply the second never asked for).
        test.fake.beforeCall = {}
        val events = test.engine.generate(request(user("Three"))).toList()
        assertEquals(FinishReason.END_OF_TURN, events.done().finishReason)
        assertEquals("second reply", events.done().text)
    }

    // runCurrent is the experimental part: it lets the generation run before the test goes on.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `forget does not wait for a collector that has stopped collecting`() = runTest {
        // The collector takes the first event and then never comes back for more, as a screen
        // would that is itself waiting for the lock to finish. The generation must not sit on the
        // engine because of it, and forget must return.
        val test = loadedEngine(testModel(maxNewTokens = 500))
        test.fake.enqueueReply("x".repeat(300))
        val stuck = backgroundScope.launch { test.engine.generate(request(user("Hi"))).collect { awaitCancellation() } }
        runCurrent()
        assertEquals("the generation ran to its end regardless", 300, test.fake.callsOf<Call.SampleNext>().size - 1)

        test.engine.forget()
        assertEquals(0, test.fake.memory.size)
        stuck.cancel()
    }

    // --- One at a time ---

    @Test
    fun `generations never overlap`() = runTest {
        val test = loadedEngine()
        test.fake.enqueueReply("first reply")
        test.fake.enqueueReply("second reply")
        val first = async { test.engine.generate(request(user("One"))).toList() }
        val second = async { test.engine.generate(request(user("Two"))).toList() }
        assertEquals("first reply", first.await().done().text)
        assertEquals("second reply", second.await().done().text)

        // Every call of the first generation comes before every call of the second.
        val tokenizeTwo = test.fake.calls.indexOfFirst { it is Call.Tokenize && "Two" in it.text }
        val samplers = test.fake.calls.withIndex().filter { it.value is Call.ConfigureSampler }.map { it.index }
        assertEquals(2, samplers.size)
        val lastSampleOfFirst = test.fake.calls.withIndex().filter { it.value is Call.SampleNext && it.index < samplers[1] }.maxOf { it.index }
        assertTrue(lastSampleOfFirst < tokenizeTwo)
    }

    // --- Real threads ---

    /**
     * Runs [block] with an engine whose native calls run on one real thread named `native`, and
     * checks afterwards that no other thread ever reached the native layer.
     */
    private fun withRealEngine(block: suspend (engine: LlamaEngine, fake: FakeNativeLlama, nativeThread: () -> Thread) -> Unit) {
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "native").apply { isDaemon = true } }
        val dispatcher = executor.asCoroutineDispatcher()
        val fake = FakeNativeLlama(contextTokens = 100_000)
        val factory = FakeNativeLlamaFactory { fake }
        try {
            runBlocking {
                withTimeout(30_000) {
                    val engine = LlamaEngine(factory, dispatcher)
                    engine.load(testModel(contextLength = 100_000, maxNewTokens = 50_000))
                    block(engine, fake) { fake.threads.single() }
                    engine.unload()
                }
            }
        } finally {
            dispatcher.close()
        }
        assertEquals(setOf("native"), (fake.threads + factory.threads).map { it.name }.toSet())
    }

    @Test
    fun `every native call is made on the injected dispatcher`() = withRealEngine { engine, fake, nativeThread ->
        fake.enqueueReply("Hello!")
        val events = engine.generate(request(user("Hi"), systemPrompt = "Be brief.")).toList()
        assertEquals("Hello!", events.done().text)
        engine.generate(request(user("Hi"), systemPrompt = "Be brief.")).toList()
        engine.forget()

        assertEquals("one thread made every call", 1, fake.threads.size)
        assertEquals("native", nativeThread().name)
        assertFalse(Thread.currentThread() === nativeThread())
        // Every kind of call was among them.
        val kinds = fake.calls.map { it::class.simpleName }.toSet()
        assertTrue(kinds.containsAll(listOf("Tokenize", "Evaluate", "RewindTo", "SaveCheckpoint", "ConfigureSampler", "SampleNext", "ClearMemory")))
    }

    @Test
    fun `a collector that stops listening stops the generation promptly`() = withRealEngine { engine, fake, _ ->
        // The model would write 20,000 tokens, each taking a millisecond after the first few.
        // The collector takes three pieces of text and leaves; the generation must end long
        // before the script does.
        val samples = AtomicInteger(0)
        fake.beforeCall = { call -> if (call is Call.SampleNext && samples.incrementAndGet() > 5) Thread.sleep(1) }
        fake.enqueueReply("abcdefghij".repeat(2_000))
        val text = engine.generate(request(user("Go"))).filterIsInstance<GenerationEvent.TextDelta>().take(3).toList()
        // Collection has returned, so the generation has ended: nothing runs behind our back.
        val sampled = samples.get()
        assertEquals("abc", text.joinToString("") { it.text })
        assertTrue("stopped after $sampled of 20000 tokens", sampled < 2_000)
        assertEquals("and stays stopped", sampled, fake.callsOf<Call.SampleNext>().size)

        // The engine is free and consistent: the next request runs and reuses the memory.
        fake.beforeCall = {}
        fake.clearCalls()
        fake.enqueueReply("done")
        val events = engine.generate(request(user("Go"))).toList()
        assertEquals("done", events.done().text)
        assertTrue(events.done().stats.reusedPromptTokens > 0)
        assertTrue(fake.callsOf<Call.ClearMemory>().isEmpty())
    }
}
