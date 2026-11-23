package us.theworkmans.proseplow.core.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import us.theworkmans.proseplow.core.engine.FakeNativeLlama.Call
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The gate at which a generation stands aside for something more urgent: it waits between two
 * native computations, does nothing while it waits, goes on unharmed when the gate opens, and
 * still ends at once when it is cancelled or interrupted.
 *
 * In the single-threaded tests the gate is given an `idle` step, which is the one place where
 * something can happen while the generation waits; the last tests wait on a real thread.
 */
class LlamaEngineGateTest {

    private val longQuestion = "Please explain, in as much detail as you can, how a sailing ship makes way against the wind."

    /** What happens each time the waiting generation would have slept. Set by the test. */
    private var whileWaiting: () -> Unit = {}
    private var waits = 0
    private val gate = GenerationGate(idle = {
        waits++
        whileWaiting()
    })

    private suspend fun TestScope.gatedEngine(settings: LlamaEngine.Settings = LlamaEngine.Settings()): TestEngine {
        lateinit var created: TestEngine
        val factory = FakeNativeLlamaFactory { spec ->
            FakeNativeLlama(contextTokens = spec.contextLength).also { fake -> created.chatMl = fake.registerChatMl() }
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
        engine.load(testModel())
        return created
    }

    @Test
    fun `a reply stands aside within one token and goes on unharmed when the gate opens`() = runTest {
        val test = gatedEngine()
        test.fake.enqueueReply("With the sails close-hauled.")
        // The gate closes while the fourth token of the reply is being sampled.
        test.fake.beforeCall = { call -> if (call is Call.SampleNext && test.fake.callsOf<Call.SampleNext>().size == 4) gate.close() }
        val callsWhileWaiting = ArrayList<Int>()
        var sampledAtFirstWait = -1
        whileWaiting = {
            if (sampledAtFirstWait < 0) sampledAtFirstWait = test.fake.callsOf<Call.SampleNext>().size
            callsWhileWaiting += test.fake.calls.size
            if (waits == 50) gate.open()
        }

        val events = test.engine.generate(request(user("How?"))).toList()

        assertEquals("it stood aside before the next token was sampled", 4, sampledAtFirstWait)
        assertEquals(50, waits)
        assertEquals("and did nothing at all while it waited", 1, callsWhileWaiting.toSet().size)
        assertEquals("With the sails close-hauled.", events.text())
        assertEquals(FinishReason.END_OF_TURN, events.done().finishReason)
        assertFalse(gate.isClosed)
    }

    @Test
    fun `reading the prompt stands aside within one chunk`() = runTest {
        val test = gatedEngine(LlamaEngine.Settings(evaluationChunk = 8, maxCheckpoints = 0))
        test.fake.enqueueReply("Like this.")
        test.fake.afterChunk = { inMemory -> if (inMemory == 16) gate.close() }
        val memoryWhileWaiting = ArrayList<Int>()
        whileWaiting = {
            memoryWhileWaiting += test.fake.memory.size
            if (waits == 20) {
                test.fake.afterChunk = {}
                gate.open()
            }
        }

        val events = test.engine.generate(request(user(longQuestion))).toList()

        assertEquals("one more chunk at most after the gate closed, then nothing", setOf(24), memoryWhileWaiting.toSet())
        assertEquals(20, waits)
        assertEquals("Like this.", events.text())
        assertTrue("the whole prompt was read in the end", test.fake.memory.size > 24)
    }

    @Test
    fun `a generation that begins while the gate is closed waits before its first chunk`() = runTest {
        val test = gatedEngine()
        test.fake.enqueueReply("Yes.")
        test.fake.clearCalls()
        gate.close()
        whileWaiting = {
            assertTrue("nothing has been evaluated yet", test.fake.callsOf<Call.Evaluate>().isEmpty())
            if (waits == 3) gate.open()
        }

        val events = test.engine.generate(request(user("Ready?"))).toList()

        assertEquals(3, waits)
        assertEquals("Yes.", events.text())
    }

    @Test
    fun `closing and opening with nothing running does nothing and the next generation runs as always`() = runTest {
        val test = gatedEngine()
        gate.close()
        assertTrue(gate.isClosed)
        gate.open()
        assertFalse(gate.isClosed)
        gate.open() // One open too many is harmless.
        assertFalse(gate.isClosed)

        test.fake.enqueueReply("As always.")
        val events = test.engine.generate(request(user("Still there?"))).toList()
        assertEquals("As always.", events.text())
        assertEquals("it never waited", 0, waits)
    }

    @Test
    fun `the gate is open again only when every close has had its open`() = runTest {
        val test = gatedEngine()
        test.fake.enqueueReply("Done.")
        gate.close()
        gate.close()
        whileWaiting = {
            when (waits) {
                2 -> gate.open()
                3, 4 -> assertTrue("one open of two is not enough", gate.isClosed)
                5 -> gate.open()
            }
        }
        val events = test.engine.generate(request(user("Twice?"))).toList()
        assertEquals(5, waits)
        assertEquals("Done.", events.text())
    }

    @Test
    fun `cancelling the collector while it stands aside ends the generation at once`() = runTest {
        val test = gatedEngine()
        test.fake.enqueueReply("This reply is never finished.")
        val received = ArrayList<GenerationEvent>()
        lateinit var collecting: Job
        test.fake.beforeCall = { call -> if (call is Call.SampleNext && test.fake.callsOf<Call.SampleNext>().size == 3) gate.close() }
        whileWaiting = { collecting.cancel() }
        collecting = launch { test.engine.generate(request(user("How?"))).collect { received += it } }
        collecting.join()

        assertTrue(collecting.isCancelled)
        assertEquals("it left the gate the first time it was asked", 1, waits)
        assertTrue("the gate itself is still closed", gate.isClosed)
        assertTrue(received.none { it is GenerationEvent.Done })
        val sampled = test.fake.callsOf<Call.SampleNext>().size
        assertEquals("nothing more was sampled", 3, sampled)

        // The engine is as usable as after any cancellation, once the gate is open.
        gate.open()
        test.fake.beforeCall = {}
        test.fake.clearCalls()
        test.fake.enqueueReply("A new one.")
        assertEquals("A new one.", test.engine.generate(request(user("And now?"))).toList().text())
    }

    @Test
    fun `the time spent standing aside is not counted as the model's speed`() = runTest {
        val test = gatedEngine(LlamaEngine.Settings(evaluationChunk = 8, maxCheckpoints = 0))
        test.fake.nanosPerToken = 1_000_000 // One millisecond for every token evaluated.
        test.fake.enqueueReply("Four")
        var closedFor = ""
        test.fake.afterChunk = { inMemory ->
            if (inMemory == 8) {
                closedFor = "prompt"
                gate.close()
            }
        }
        test.fake.beforeCall = { call ->
            if (call is Call.SampleNext && test.fake.callsOf<Call.SampleNext>().size == 2) {
                closedFor = "reply"
                gate.close()
            }
        }
        whileWaiting = {
            // Two seconds pass for the prompt's pause, three for the reply's.
            test.fake.nanos += if (closedFor == "prompt") 2_000_000_000L else 3_000_000_000L
            gate.open()
        }

        val unpaused = run {
            val plain = loadedEngine(settings = LlamaEngine.Settings(evaluationChunk = 8, maxCheckpoints = 0)) { it.nanosPerToken = 1_000_000 }
            plain.fake.enqueueReply("Four")
            plain.engine.generate(request(user(longQuestion))).toList().done().stats
        }
        val stats = test.engine.generate(request(user(longQuestion))).toList().done().stats

        assertEquals(2, waits)
        assertEquals("the same as without any pause", unpaused.promptMillis, stats.promptMillis)
        assertEquals(unpaused.generationMillis, stats.generationMillis)
        assertTrue(stats.promptMillis in 1..1_999)
    }

    // --- On real threads: the gate's own waiting ---

    @Test(timeout = 60_000)
    fun `on a real thread the generation waits at the closed gate and is woken when it opens`() {
        val nativeThread = Executors.newSingleThreadExecutor()
        try {
            val real = GenerationGate()
            val factory = FakeNativeLlamaFactory { spec -> FakeNativeLlama(contextTokens = spec.contextLength).also { it.registerChatMl() } }
            val engine = LlamaEngine(factory, nativeThread.asCoroutineDispatcher(), newSeed = { TEST_SEED }, gate = real)
            runBlocking {
                withTimeout(30_000) {
                    engine.load(testModel())
                    val fake = factory.last
                    fake.enqueueReply("Woken up.")
                    val sampled = AtomicInteger(0)
                    val third = CountDownLatch(1)
                    fake.beforeCall = { call ->
                        if (call is Call.SampleNext && sampled.incrementAndGet() == 3) {
                            real.close()
                            third.countDown()
                        }
                    }
                    // Collected on another thread: this one blocks on the latch below.
                    val events = async(Dispatchers.Default) { engine.generate(request(user("How?"))).toList() }
                    assertTrue(third.await(20, TimeUnit.SECONDS))
                    // Long enough for a generation that did not wait to have finished many times over.
                    Thread.sleep(300)
                    val whileClosed = sampled.get()
                    assertFalse("it has not finished", events.isCompleted)
                    assertTrue("at most the token under way was finished: $whileClosed", whileClosed <= 4)
                    Thread.sleep(200)
                    assertEquals("and nothing happens while the gate stays closed", whileClosed, sampled.get())

                    real.open()
                    assertEquals("Woken up.", events.await().text())
                }
            }
        } finally {
            nativeThread.shutdownNow()
        }
    }

    @Test(timeout = 60_000)
    fun `forgetting while a generation stands aside ends it although the gate stays closed`() {
        val nativeThread = Executors.newSingleThreadExecutor()
        try {
            val real = GenerationGate()
            val factory = FakeNativeLlamaFactory { spec -> FakeNativeLlama(contextTokens = spec.contextLength).also { it.registerChatMl() } }
            val engine = LlamaEngine(factory, nativeThread.asCoroutineDispatcher(), newSeed = { TEST_SEED }, gate = real)
            runBlocking {
                withTimeout(30_000) {
                    engine.load(testModel())
                    val fake = factory.last
                    fake.enqueueReply("This reply is never finished.")
                    val sampled = AtomicInteger(0)
                    val third = CountDownLatch(1)
                    fake.beforeCall = { call ->
                        if (call is Call.SampleNext && sampled.incrementAndGet() == 3) {
                            real.close()
                            third.countDown()
                        }
                    }
                    // Collected on another thread: this one blocks on the latch below.
                    val events = async(Dispatchers.Default) { engine.generate(request(user("How?"))).toList() }
                    assertTrue(third.await(20, TimeUnit.SECONDS))
                    Thread.sleep(200)

                    // What a lock does. It must not wait for a gate nobody is going to open.
                    engine.forget()
                    val done = events.await().done()
                    assertEquals(FinishReason.CANCELLED, done.finishReason)
                    assertTrue(real.isClosed)
                    assertTrue("the conversation is gone from the memory", fake.memory.isEmpty())
                }
            }
        } finally {
            nativeThread.shutdownNow()
        }
    }
}
