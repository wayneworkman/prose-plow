package us.theworkmans.proseplow.core.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Loading, unloading and the engine's observable state. */
class LlamaEngineLoadTest {

    @Test
    fun `the engine starts unloaded and refuses to generate`() = runTest {
        val test = newEngine()
        assertEquals(EngineState.Unloaded, test.engine.state.value)
        assertEquals(EngineError.NotLoaded, test.engine.generate(request(user("Hi"))).toList().error())
        assertTrue(test.factory.opened.isEmpty())
    }

    @Test
    fun `loading reports progress and ends ready`() = runTest {
        val test = newEngine()
        val seen = ArrayList<EngineState>()
        test.factory.progressSteps = listOf(0f, 0.25f, 0.5f, 1f)
        test.factory.beforeProgress = { seen += test.engine.state.value }
        val model = testModel()
        test.engine.load(model)
        assertEquals(EngineState.Ready(TEST_MODEL_ID), test.engine.state.value)
        // Each report is visible in the state by the time the next one is made.
        assertEquals(
            listOf(0f, 0f, 0.25f, 0.5f).map { EngineState.Loading(TEST_MODEL_ID, it) },
            seen,
        )
        assertSame(model, test.factory.opened.single().first)
    }

    @Test
    fun `progress outside zero to one is clamped`() = runTest {
        val test = newEngine()
        val seen = ArrayList<EngineState>()
        test.factory.progressSteps = listOf(-0.5f, 7f, 1f)
        test.factory.beforeProgress = { seen += test.engine.state.value }
        test.engine.load(testModel())
        assertEquals(listOf(0f, 0f, 1f).map { EngineState.Loading(TEST_MODEL_ID, it) }, seen)
    }

    @Test
    fun `a model that cannot be loaded leaves the engine failed, without throwing`() = runTest {
        val test = newEngine()
        test.factory.failure = NativeLlamaException(NativeLlamaException.LOAD_FAILED, "not a model file")
        test.engine.load(testModel())
        assertEquals(
            EngineState.Failed(TEST_MODEL_ID, EngineError.LoadFailed(TEST_MODEL_ID, "not a model file")),
            test.engine.state.value,
        )
        assertEquals(EngineError.NotLoaded, test.engine.generate(request(user("Hi"))).toList().error())

        // The same engine can load once the cause is gone.
        test.factory.failure = null
        test.engine.load(testModel())
        assertEquals(EngineState.Ready(TEST_MODEL_ID), test.engine.state.value)
    }

    @Test
    fun `an unexpected failure while loading is reported without its message`() = runTest {
        val test = newEngine()
        test.factory.failure = IllegalStateException("a message that might quote something private")
        test.engine.load(testModel())
        val failed = test.engine.state.value as EngineState.Failed
        val detail = (failed.error as EngineError.LoadFailed).detail
        assertTrue(detail, detail.startsWith("IllegalStateException"))
        assertFalse(detail, "private" in detail)
    }

    @Test
    fun `loading the loaded model again does nothing`() = runTest {
        val test = loadedEngine()
        test.engine.load(testModel())
        assertEquals(1, test.factory.opened.size)
        assertFalse(test.fake.closed)
    }

    @Test
    fun `loading another model frees the first one, so only one is ever loaded`() = runTest {
        val test = loadedEngine(testModel(id = "first"))
        val first = test.fake
        var firstWasClosedBeforeTheSecondOpened = false
        test.factory.beforeProgress = { firstWasClosedBeforeTheSecondOpened = first.closed }
        test.engine.load(testModel(id = "second"))
        assertTrue(firstWasClosedBeforeTheSecondOpened)
        assertEquals(EngineState.Ready("second"), test.engine.state.value)
        assertEquals(listOf("first", "second"), test.factory.opened.map { it.first.id })
        assertFalse(test.fake.closed)
    }

    @Test
    fun `a request for a model other than the loaded one is refused`() = runTest {
        val test = loadedEngine(testModel(id = "loaded"))
        val events = test.engine.generate(request(user("Hi"), modelId = "wanted")).toList()
        assertEquals(EngineError.WrongModel(requested = "wanted", loaded = "loaded"), events.error())
        assertTrue("nothing was evaluated for it", test.fake.calls.isEmpty())
    }

    @Test
    fun `unloading frees the model`() = runTest {
        val test = loadedEngine()
        val fake = test.fake
        test.engine.unload()
        assertEquals(EngineState.Unloaded, test.engine.state.value)
        assertTrue(fake.closed)
        assertEquals(EngineError.NotLoaded, test.engine.generate(request(user("Hi"))).toList().error())
        // Unloading twice is harmless.
        test.engine.unload()
        assertEquals(EngineState.Unloaded, test.engine.state.value)
    }

    @Test
    fun `cancelling a load abandons it and leaves the engine unloaded`() = runTest {
        val test = newEngine()
        lateinit var loading: Job
        test.factory.beforeProgress = { fraction -> if (fraction == 0.5f) loading.cancel() }
        var cancelled = false
        loading = launch {
            try {
                test.engine.load(testModel())
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        loading.join()
        assertTrue(cancelled)
        assertEquals(EngineState.Unloaded, test.engine.state.value)
        assertTrue("the native side was told to stop and nothing was opened", test.factory.opened.isEmpty())

        // And the engine still works.
        test.engine.load(testModel())
        assertEquals(EngineState.Ready(TEST_MODEL_ID), test.engine.state.value)
    }

    @Test
    fun `a load cancelled too late for the native side to notice does not keep the model`() = runTest {
        val test = newEngine()
        lateinit var loading: Job
        // Cancelled during the last progress report: the factory still returns a model.
        test.factory.progressSteps = listOf(0f)
        var first = true
        val factory = NativeLlamaFactory { model, progress, contextTokens, budgetBytes ->
            val native = test.factory.open(model, progress, contextTokens, budgetBytes)
            if (first) {
                first = false
                loading.cancel()
            }
            native
        }
        val engine = LlamaEngine(factory, StandardTestDispatcher(testScheduler))
        loading = launch { engine.load(testModel()) }
        loading.join()
        assertEquals(EngineState.Unloaded, engine.state.value)
        assertTrue(test.factory.last.closed)
    }
}
