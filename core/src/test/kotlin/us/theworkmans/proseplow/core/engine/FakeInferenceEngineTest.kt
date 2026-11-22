package us.theworkmans.proseplow.core.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import us.theworkmans.proseplow.core.engine.FakeInferenceEngine.Step

/**
 * The scripted engine that other modules test against must itself behave as documented.
 *
 * The opt-in is for the virtual-time controls of the coroutine test library (`advanceTimeBy`,
 * `runCurrent`, `currentTime`), which these tests are about.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeInferenceEngineTest {

    private val model = testModel()

    @Test
    fun `it loads, reports its state and records what was asked of it`() = runTest {
        val engine = FakeInferenceEngine()
        assertEquals(EngineState.Unloaded, engine.state.value)
        engine.loadMillis = 2_000
        val loading = launch { engine.load(model) }
        runCurrent()
        assertEquals(EngineState.Loading(TEST_MODEL_ID, 0f), engine.state.value)
        advanceTimeBy(2_001)
        loading.join()
        assertEquals(EngineState.Ready(TEST_MODEL_ID), engine.state.value)
        assertEquals(listOf(TEST_MODEL_ID), engine.loads)

        engine.unload()
        assertEquals(EngineState.Unloaded, engine.state.value)
        assertEquals(1, engine.unloadCount)
        engine.forget()
        assertEquals(1, engine.forgetCount)
    }

    @Test
    fun `a load can be made to fail once`() = runTest {
        val engine = FakeInferenceEngine()
        engine.loadFailure = EngineError.LoadFailed(TEST_MODEL_ID, "scripted")
        engine.load(model)
        assertEquals(EngineState.Failed(TEST_MODEL_ID, EngineError.LoadFailed(TEST_MODEL_ID, "scripted")), engine.state.value)
        engine.load(model)
        assertEquals(EngineState.Ready(TEST_MODEL_ID), engine.state.value)
    }

    @Test
    fun `a scripted reply is played back in virtual time`() = runTest {
        val engine = FakeInferenceEngine()
        engine.load(model)
        engine.enqueueReply(listOf("Hello", ", ", "world"), millisBetween = 100)
        val started = currentTime
        val request = request(user("Hi"))
        val events = engine.generate(request).toList()
        assertEquals(300L, currentTime - started)
        assertEquals("Hello, world", events.text())
        assertEquals(FinishReason.END_OF_TURN, events.done().finishReason)
        assertEquals("Hello, world", events.done().text)
        assertEquals(listOf(request), engine.requests)
        assertEquals(1, engine.startedGenerations)
        assertEquals(1, engine.completedGenerations)
        assertEquals(0, engine.cancelledGenerations)
        assertEquals(0, engine.activeGenerations)
    }

    @Test
    fun `without a script the reply is empty`() = runTest {
        val engine = FakeInferenceEngine()
        engine.load(model)
        val events = engine.generate(request(user("Hi"))).toList()
        assertEquals(1, events.size)
        assertEquals(FinishReason.END_OF_TURN, events.done().finishReason)
    }

    @Test
    fun `an injected failure ends the generation with a typed error after the text before it`() = runTest {
        val engine = FakeInferenceEngine()
        engine.load(model)
        engine.enqueueFailure(EngineError.Native(5, "scripted"), pieces = listOf("Half an "))
        val events = engine.generate(request(user("Hi"))).toList()
        assertEquals("Half an ", events.text())
        assertEquals(EngineError.Native(5, "scripted"), events.error())
    }

    @Test
    fun `requests without the right model loaded fail as they do in the real engine`() = runTest {
        val engine = FakeInferenceEngine()
        assertEquals(EngineError.NotLoaded, engine.generate(request(user("Hi"))).toList().error())
        engine.load(model)
        assertEquals(
            EngineError.WrongModel(requested = "other", loaded = TEST_MODEL_ID),
            engine.generate(request(user("Hi"), modelId = "other")).toList().error(),
        )
        // Neither consumed a script.
        engine.enqueueReply(listOf("still here"))
        engine.generate(request(user("Hi"), modelId = "other")).toList()
        assertEquals("still here", engine.generate(request(user("Hi"))).toList().done().text)
    }

    @Test
    fun `cancelling the collector between two pieces is observable`() = runTest {
        val engine = FakeInferenceEngine()
        engine.load(model)
        engine.enqueueReply(listOf("one", "two", "three"), millisBetween = 1_000)
        val received = ArrayList<GenerationEvent>()
        val collecting = launch { engine.generate(request(user("Hi"))).collect { received += it } }
        advanceTimeBy(1_500)
        assertEquals("one", received.text())
        assertEquals(1, engine.activeGenerations)

        collecting.cancel()
        collecting.join()
        assertEquals("one", received.text())
        assertEquals(1, engine.cancelledGenerations)
        assertEquals(0, engine.completedGenerations)
        assertEquals(0, engine.activeGenerations)
    }

    @Test
    fun `forget ends a generation in flight as cancelled, with the text so far`() = runTest {
        val engine = FakeInferenceEngine()
        engine.load(model)
        engine.enqueueReply(listOf("one", "two", "three"), millisBetween = 1_000)
        val received = ArrayList<GenerationEvent>()
        val collecting = launch { engine.generate(request(user("Hi"))).collect { received += it } }
        advanceTimeBy(2_500)
        engine.forget()
        collecting.join()
        assertEquals(FinishReason.CANCELLED, received.done().finishReason)
        assertEquals("onetwo", received.done().text)
        assertEquals(1, engine.completedGenerations)
        assertEquals(0, engine.cancelledGenerations)
    }

    @Test
    fun `a hanging generation runs until it is cancelled or the engine unloads`() = runTest {
        val engine = FakeInferenceEngine()
        engine.load(model)
        engine.enqueue(listOf(Step.Emit(GenerationEvent.TextDelta("thinking")), Step.Hang))
        engine.enqueue(listOf(Step.Hang))
        val received = ArrayList<GenerationEvent>()
        val first = launch { engine.generate(request(user("Hi"))).collect { received += it } }
        advanceTimeBy(60_000)
        assertTrue(first.isActive)
        engine.unload()
        first.join()
        assertEquals(FinishReason.CANCELLED, received.done().finishReason)
        assertEquals("thinking", received.done().text)

        engine.load(model)
        val second = launch { engine.generate(request(user("Hi"))).collect { } }
        advanceTimeBy(60_000)
        assertTrue(second.isActive)
        second.cancel()
        second.join()
        assertEquals(1, engine.cancelledGenerations)
    }

    @Test
    fun `loading another model ends a generation in flight`() = runTest {
        val engine = FakeInferenceEngine()
        engine.load(model)
        engine.enqueue(listOf(Step.Hang))
        val received = ArrayList<GenerationEvent>()
        val collecting = launch { engine.generate(request(user("Hi"))).collect { received += it } }
        runCurrent()
        engine.load(testModel(id = "other"))
        collecting.join()
        assertEquals(FinishReason.CANCELLED, received.done().finishReason)
        assertEquals(EngineState.Ready("other"), engine.state.value)
    }
}
