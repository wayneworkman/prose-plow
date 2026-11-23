package us.theworkmans.proseplow.llama

import android.app.ActivityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import us.theworkmans.proseplow.core.engine.NativeLlamaException
import us.theworkmans.proseplow.core.engine.SamplerConfig
import us.theworkmans.proseplow.core.model.ModelCatalog
import us.theworkmans.proseplow.core.model.ModelSpec

/**
 * What growing a context costs on this phone, measured. It is a run that reports numbers more
 * than a test: the host tests hold the session to its contract, and what only a phone can say
 * is how long a context of hundreds of MiB takes to create and to forget, how fast a model is
 * in a large context that is nearly empty and in one that is full, and whether the memory of a
 * context that was given up goes back to the system.
 *
 * It is not part of the ordinary run, because it takes memory on purpose. Ask for it:
 *
 *     adb shell am instrument -w -e class us.theworkmans.proseplow.llama.ContextGrowthDeviceTest \
 *         -e contextGrowth true us.theworkmans.proseplow.llama.test/androidx.test.runner.AndroidJUnitRunner
 *
 * The context grows by doubling for as long as the growth stays inside a fraction of the memory
 * the system reports as free above its own low-memory threshold: a quarter unless
 * `-e contextFraction` names another fraction (between 0.05 and 0.5). That fraction is the
 * whole safeguard here. Android grants an allocation it cannot back and ends the process when
 * the pages are touched, so nothing in this run finds a limit by reaching it.
 */
@RunWith(AndroidJUnit4::class)
class ContextGrowthDeviceTest {

    @Test
    fun aContextGrowsInsideAFractionOfTheFreeMemory() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "not asked for: pass -e contextGrowth true (the run takes hundreds of MiB of memory on purpose)",
            arguments.getString("contextGrowth") == "true",
        )
        val fraction = (arguments.getString("contextFraction")?.toDoubleOrNull() ?: DEFAULT_FRACTION).coerceIn(0.05, 0.5)
        val models = GROWTH_MODELS.filter { locateForTests(it) != null }
        assumeTrue("no model of this measurement is present", models.isNotEmpty())
        for (model in models) measure(model, fraction)
    }

    /** Opens [model], measures it at its opening size, grows it step by step and forgets. */
    private fun measure(model: ModelSpec, fraction: Double) {
        val native = open(model)
        try {
            val opened = native.contextSize()
            val largest = native.maxContextSize
            val room = (fraction * freeAboveThreshold()).toLong()
            val budget = native.contextBytes(opened) + room
            report(
                "${model.id}: opened with $opened tokens (${mib(native.contextBytes(opened))} MiB by the session's count), the model's largest is $largest " +
                    "(${mib(native.contextBytes(largest))} MiB); room to grow ${mib(room)} MiB, which is ${(fraction * 100).toInt()}% of the free memory; ${memory()}",
            )
            native.configureSampler(greedy(SamplerConfig.NO_PENALTY_WINDOW, model.bannedTokenIds))
            val base = native.tokens(FILLER.repeat(8), false)
            val isEnd = { token: Int -> native.isEndOfGeneration(token) || token in model.stopTokenIds }

            // The opening size, nearly empty and then full.
            reportSpeed(model, native, base, PROBE_TOKENS, "nearly empty", isEnd)
            native.clearMemory()
            reportSpeed(model, native, base, opened - GENERATED - 1, "full", isEnd)
            native.clearMemory()

            // Growth, with a short conversation in memory so that there is something to carry.
            native.mustEvaluate(IntArray(PROBE_TOKENS) { base[it % base.size] })
            var size = opened
            while (size <= largest / 2 && native.contextBytes(size * 2) <= budget) {
                val before = memory()
                val inMemory = native.memoryTokenCount()
                val start = System.nanoTime()
                native.resizeContext(size * 2, budget)
                val millis = (System.nanoTime() - start) / 1_000_000
                size = native.contextSize()
                report(
                    "${model.id}: grown to $size tokens in $millis ms (${mib(native.contextBytes(size))} MiB by the session's count); " +
                        "${native.memoryTokenCount()} of $inMemory tokens still in memory; before: $before; after: ${memory()}",
                )
                assertEquals("the context has the size that was asked for", size, native.contextSize())
                reportSpeed(model, native, base, PROBE_TOKENS, "nearly empty", isEnd)
            }
            if (size == opened) {
                report("${model.id}: the next size, ${opened * 2} tokens, does not fit the room (or the model has no larger context): nothing was grown")
            }

            // Forgetting gives the memory back: the context is the opening size again.
            val grown = memory()
            val start = System.nanoTime()
            native.clearMemory()
            val millis = (System.nanoTime() - start) / 1_000_000
            report("${model.id}: forgetting at $size tokens took $millis ms and left a context of ${native.contextSize()}; before: $grown; after: ${memory()}")
            assertEquals("forgetting returns to the opening size", opened, native.contextSize())
            // Whether the allocator has given the grown context's pages back, at once or only after a while.
            var waited = 0
            for (seconds in LINGER_SECONDS) {
                Thread.sleep((seconds - waited) * 1000L)
                waited = seconds
                report("${model.id}: $seconds s after forgetting: ${memory()}")
            }

            // A size that is refused changes nothing, and says so with its own code.
            try {
                native.resizeContext(opened * 2, 1L)
                assertTrue("a context was granted within a budget of one byte", false)
            } catch (e: NativeLlamaException) {
                assertEquals("a size above the budget (${e.message})", NativeLlamaException.CONTEXT_REFUSED, e.code)
            }
        } finally {
            native.close()
        }
        report("${model.id}: closed; ${memory()}")
    }

    /**
     * Evaluates [promptTokens] tokens into the memory as it is and generates [GENERATED] more,
     * and reports both speeds with the size of the context and how full it is.
     */
    private fun reportSpeed(model: ModelSpec, native: LlamaSession, base: IntArray, promptTokens: Int, state: String, isEnd: (Int) -> Boolean) {
        val prompt = IntArray(promptTokens) { base[it % base.size] }
        val promptNanos = native.mustEvaluate(prompt)
        val start = System.nanoTime()
        val reply = native.continueReply(GENERATED, isEnd)
        val nanos = System.nanoTime() - start
        report(
            "${model.id}: a context of ${native.contextSize()} tokens, $state (${native.memoryTokenCount()} in memory): prompt " +
                "%.0f tokens/s over $promptTokens tokens, generation %.1f tokens/s over ${reply.size}".format(
                    promptTokens * 1e9 / promptNanos,
                    if (reply.isEmpty()) 0.0 else reply.size * 1e9 / nanos,
                ),
        )
    }

    /** The memory the system reports as available beyond the level at which it calls itself low, in bytes. */
    private fun freeAboveThreshold(): Long {
        val info = ActivityManager.MemoryInfo()
        appContext.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        report("the system: ${mib(info.availMem)} MiB available of ${mib(info.totalMem)}, low-memory threshold ${mib(info.threshold)} MiB, low now: ${info.lowMemory}")
        return (info.availMem - info.threshold).coerceAtLeast(0L)
    }

    private fun mib(bytes: Long): Long = bytes / (1L shl 20)

    private companion object {
        /** The bundled models whose context can grow beyond what the app opens them with. */
        val GROWTH_MODELS: List<ModelSpec> = listOf(ModelCatalog.QWEN35_0_8B, ModelCatalog.PEACEBELL_V1_291M, ModelCatalog.BORIS_2_INSTRUCT)

        const val DEFAULT_FRACTION: Double = 0.25

        /** A short conversation: enough to measure a speed and to be worth carrying over. */
        const val PROBE_TOKENS: Int = 256

        const val GENERATED: Int = 16

        /** When, in seconds after forgetting, the process's memory is read again. */
        val LINGER_SECONDS: List<Int> = listOf(1, 3, 10)

        const val FILLER: String = "The quick brown fox jumps over the lazy dog, and then it does so again and again until evening. "
    }
}
