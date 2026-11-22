package us.theworkmans.proseplow.llama

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import us.theworkmans.proseplow.core.engine.EvaluationObserver
import us.theworkmans.proseplow.core.engine.NativeLlamaException
import us.theworkmans.proseplow.core.model.ModelCatalog
import us.theworkmans.proseplow.core.model.ModelSpec

/**
 * What happens at the border between Kotlin and native code when something goes wrong: a
 * failure on the native side must arrive as [NativeLlamaException] with its code, an exception
 * thrown by a Kotlin callback during a native call must come back out as itself, and neither
 * may take the process down or leave the session unusable.
 *
 * Runs with whichever model of the catalogue is present, the smallest first.
 */
@RunWith(AndroidJUnit4::class)
class JniBoundaryDeviceTest {

    private val model: ModelSpec by lazy {
        val present = listOf(ModelCatalog.BORIS_2_INSTRUCT, ModelCatalog.PEACEBELL_V1_291M, ModelCatalog.QWEN35_0_8B)
            .firstOrNull { ModelFiles.locate(appContext, it) != null }
        assumeTrue("no model is in this test APK or sideloaded", present != null)
        present!!
    }

    /** Some tokens the model accepts: plain text never contains a token a model refuses as input. */
    private fun LlamaSession.someTokens(): IntArray =
        tokens("The quick brown fox jumps over the lazy dog, and then it does so again and again until evening. ".repeat(3), false)

    @Test
    fun aCallerMistakeIsAnExceptionWithItsCode() {
        val native = open(model)
        try {
            expectFailure(NativeLlamaException.INVALID_ARGUMENT, "the bytes of a token outside the vocabulary") { native.tokenToBytes(-1) }
            expectFailure(NativeLlamaException.INVALID_ARGUMENT, "evaluating no tokens") { native.evaluate(IntArray(0), 8, Uninterrupted) }
            expectFailure(NativeLlamaException.INVALID_ARGUMENT, "evaluating a token outside the vocabulary") { native.evaluate(intArrayOf(Int.MAX_VALUE), 8, Uninterrupted) }
            expectFailure(NativeLlamaException.INVALID_ARGUMENT, "a chunk size of 0") { native.evaluate(native.someTokens(), 0, Uninterrupted) }
            expectFailure(NativeLlamaException.INVALID_ARGUMENT, "shortening the memory to -1") { native.rewindTo(-1) }
            expectFailure(NativeLlamaException.INVALID_ARGUMENT, "shortening the memory beyond its end") { native.rewindTo(1) }
            expectFailure(NativeLlamaException.SAMPLING_FAILED, "sampling without a sampler") { native.sampleNext() }
            expectFailure(NativeLlamaException.INVALID_ARGUMENT, "a penalty window of 0") { native.configureSampler(greedy(0)) }
            expectFailure(NativeLlamaException.INVALID_ARGUMENT, "a banned token outside the vocabulary") {
                native.configureSampler(greedy(native.contextSize(), banned = listOf(-5)))
            }
            // Not errors: an unknown checkpoint, an empty text, a token id that ends nothing.
            assertFalse(native.restoreCheckpoint(12345))
            native.dropCheckpoint(12345)
            assertEquals(0, native.tokenize(ByteArray(0), false).size)
            assertFalse(native.isEndOfGeneration(-1))
            assertEquals("nothing above reached the memory", 0, native.memoryTokenCount())
            // And the session works afterwards.
            native.mustEvaluate(native.someTokens())
            native.configureSampler(greedy(native.contextSize(), model.bannedTokenIds))
            assertTrue(native.sampleNext() >= 0)
        } finally {
            native.close()
        }
    }

    @Test
    fun anExceptionFromAnObserverComesBackOutAsItself() {
        val native = open(model)
        try {
            val tokens = native.someTokens()
            assertTrue("the text gives several chunks of 8", tokens.size > 24)

            class Boom : RuntimeException("thrown by the test's observer")

            // From onProgress, after the first chunk.
            try {
                native.evaluate(tokens, 8, object : EvaluationObserver {
                    override fun onProgress(evaluatedTokens: Int): Unit = throw Boom()
                    override fun isCancelled(): Boolean = false
                })
                fail("the observer's exception was swallowed")
            } catch (_: Boom) {
                // expected
            }
            assertEquals("the chunk that had been evaluated is in memory, and no more", 8, native.memoryTokenCount())

            // From isCancelled, at the second time of asking.
            native.clearMemory()
            var asked = 0
            try {
                native.evaluate(tokens, 8, object : EvaluationObserver {
                    override fun onProgress(evaluatedTokens: Int) = Unit
                    override fun isCancelled(): Boolean = if (++asked >= 2) throw Boom() else false
                })
                fail("the observer's exception was swallowed")
            } catch (_: Boom) {
                // expected
            }
            assertTrue("evaluation stopped where the exception was thrown", native.memoryTokenCount() <= 8)

            // The session is as usable as after any interrupted evaluation.
            native.clearMemory()
            native.mustEvaluate(tokens)
            assertEquals(tokens.size, native.memoryTokenCount())
        } finally {
            native.close()
        }
    }

    @Test
    fun aLoadCanBeAbandonedAndCanFail() {
        val factory = LlamaFactory(appContext, NativeSettings())
        val before = memory()

        var reports = 0
        try {
            factory.open(model) { ++reports < 2 }.close()
            fail("an abandoned load returned a model")
        } catch (e: NativeLlamaException) {
            assertEquals("abandoned by the progress callback (${e.message})", NativeLlamaException.LOAD_CANCELLED, e.code)
        }
        assertEquals("the load stopped at the callback that said so", 2, reports)

        class Boom : RuntimeException("thrown by the test's progress callback")
        try {
            factory.open(model) { throw Boom() }.close()
            fail("the progress callback's exception was swallowed")
        } catch (_: Boom) {
            // expected
        }

        try {
            factory.open(ModelCatalog.BORIS2_PEACEBELL_THINK) { true }.close()
            fail("a model that no build carries was opened")
        } catch (e: NativeLlamaException) {
            assertEquals("a model this build does not carry (${e.message})", NativeLlamaException.LOAD_FAILED, e.code)
        }

        val after = memory()
        report("${model.id} after three loads that did not complete: $after (before: $before)")
        assertTrue("nothing of an abandoned load stays allocated", after.pss - before.pss < 48.0)

        // And a load still works.
        factory.open(model) { true }.close()
    }

    @Test
    fun aClosedSessionNeverReachesNativeCode() {
        val native = open(model)
        native.close()
        native.close()
        try {
            native.tokenize("after close".toByteArray(), false)
            fail("a call on a closed session went through")
        } catch (_: IllegalStateException) {
            // expected: a bug in the caller, reported as one
        }
    }
}
