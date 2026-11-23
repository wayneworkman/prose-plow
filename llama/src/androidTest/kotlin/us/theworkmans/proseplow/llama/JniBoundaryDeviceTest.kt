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
        val present = listOf(ModelCatalog.BORIS_2_INSTRUCT, ModelCatalog.PEACEBELL_V1_291M, ModelCatalog.QWEN35_0_8B, ModelCatalog.SHARD_2)
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

    /**
     * A size that arrives from outside and is beyond its limit must come back as an exception
     * with its code, at once: llama.cpp would allocate for any size it is handed, and on a phone
     * an allocation that cannot be met ends the process instead of failing. The limits are the
     * ones of `proseplow_session.h` (`PP_CONTEXT_TOKENS_MAX`, `PP_TOKENIZE_BYTES_MAX`,
     * `PP_CHECKPOINTS_MAX`); the numbers are repeated here on purpose, so that moving one is
     * noticed.
     */
    @Test
    fun aSizeBeyondItsLimitIsAnExceptionWithItsCode() {
        val factory = LlamaFactory(appContext, NativeSettings())
        val before = memory()
        for (context in intArrayOf(Int.MAX_VALUE, 32769)) {
            try {
                factory.open(model.copy(contextLength = context)) { true }.close()
                fail("a context of $context tokens was opened")
            } catch (e: NativeLlamaException) {
                assertEquals("a context of $context tokens (${e.message})", NativeLlamaException.INVALID_ARGUMENT, e.code)
            }
        }
        val after = memory()
        report("${model.id} after two contexts beyond the limit were refused: $after (before: $before)")
        assertTrue("a refused context allocates nothing that stays", after.pss - before.pss < 48.0)

        val native = open(model)
        try {
            val tokens = native.someTokens()
            assertTrue("the longest text is tokenised", native.tokenize(ByteArray(262144) { 'a'.code.toByte() }, false).isNotEmpty())
            expectFailure(NativeLlamaException.TOKENIZE_FAILED, "a text one byte longer than the longest") {
                native.tokenize(ByteArray(262145) { 'a'.code.toByte() }, false)
            }
            expectFailure(NativeLlamaException.CONTEXT_FULL, "more tokens than the largest context holds") {
                native.evaluate(IntArray(32769) { tokens[0] }, ENGINE_CHUNK, Uninterrupted)
            }
            assertEquals("none of them reached the memory", 0, native.memoryTokenCount())
            expectFailure(NativeLlamaException.INVALID_ARGUMENT, "more banned tokens than a vocabulary has") {
                native.configureSampler(greedy(native.contextSize(), banned = List(1 shl 20) { 0 }))
            }

            // A penalty window beyond the context is the context: accepted, and it costs nothing.
            val beforeWindow = memory()
            native.configureSampler(greedy(Int.MAX_VALUE, model.bannedTokenIds).copy(repetitionPenalty = 1.1f))
            val afterWindow = memory()
            assertTrue("a penalty window of ${Int.MAX_VALUE} allocates next to nothing ($afterWindow, before: $beforeWindow)", afterWindow.pss - beforeWindow.pss < 16.0)

            native.mustEvaluate(tokens)
            var saved = 0
            while (saved < 100 && native.saveCheckpoint() >= 0) saved++
            assertEquals("checkpoints held before the session answers that it has enough", 8, saved)

            // Forgetting frees and recreates the context: the session must be whole afterwards,
            // with the sampler it had and room for checkpoints again.
            val start = System.nanoTime()
            native.clearMemory()
            report("${model.id}: forgetting a conversation of ${tokens.size} tokens took ${(System.nanoTime() - start) / 1_000_000} ms")
            assertEquals(0, native.memoryTokenCount())
            assertEquals(model.contextLength, native.contextSize())
            assertTrue(native.saveCheckpoint() >= 0)
            native.mustEvaluate(tokens)
            assertTrue(native.sampleNext() >= 0)
        } finally {
            native.close()
        }
    }

    /**
     * Tokenising cannot be interrupted, so the longest text a session accepts must not take long
     * whatever it is made of. Line breaks are the case that once did: characters outside a
     * SentencePiece vocabulary took time with the square of their number (patch 0004 of
     * `native/patches`), four seconds on a desktop processor for this text. Every model that is
     * present is timed; the limit is far above what linear time needs on a phone.
     */
    @Test
    fun theLongestTextIsTokenisedWithoutAStall() {
        val present = listOf(ModelCatalog.BORIS_2_INSTRUCT, ModelCatalog.PEACEBELL_V1_291M, ModelCatalog.QWEN35_0_8B, ModelCatalog.SHARD_2)
            .filter { ModelFiles.locate(appContext, it) != null }
        assumeTrue("no model is in this test APK or sideloaded", present.isNotEmpty())
        val lineBreaks = ByteArray(262144) { '\n'.code.toByte() }
        for (spec in present) {
            val native = open(spec)
            try {
                val start = System.nanoTime()
                val tokens = native.tokenize(lineBreaks, false)
                val millis = (System.nanoTime() - start) / 1_000_000
                report("${spec.id}: 256 KiB of line breaks tokenised to ${tokens.size} tokens in $millis ms")
                assertTrue("${spec.id} tokenises 256 KiB of line breaks in $millis ms", millis < 5_000)
            } finally {
                native.close()
            }
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

        // A model the catalogue could know but no build carries: a copy of a real entry under a
        // name for which there is neither an asset nor a sideloaded file.
        val notCarried = model.copy(id = "not-in-any-build", ggufFileName = "not-in-any-build.gguf", bundled = false)
        try {
            factory.open(notCarried) { true }.close()
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
