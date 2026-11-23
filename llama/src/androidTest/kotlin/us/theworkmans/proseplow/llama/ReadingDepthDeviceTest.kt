package us.theworkmans.proseplow.llama

import android.app.ActivityManager
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import us.theworkmans.proseplow.core.engine.NativeLlamaException
import us.theworkmans.proseplow.core.engine.SamplerConfig
import us.theworkmans.proseplow.core.model.ModelCatalog
import us.theworkmans.proseplow.core.model.ModelSpec

/**
 * How much slower a model reads the longer the conversation already is, measured with the
 * test's activity in front and with the settings the app uses. It is a run that reports
 * numbers, not a test: nothing is required of them.
 *
 * Why it matters. A model with attention looks at everything it has read for every token it
 * reads, so a token costs more the more tokens are in memory, and reading a whole conversation
 * from nothing costs more than its length divided by the speed of a short prompt. The app
 * reads a whole conversation again whenever its process has ended, and promises how long that
 * takes, so it needs both numbers: what a token costs at the start, and how much every token
 * already read adds to that.
 *
 * It is not part of the ordinary run: it takes memory on purpose and many minutes. Ask for it
 * with the number of tokens to read:
 *
 *     adb shell am instrument -w -e class us.theworkmans.proseplow.llama.ReadingDepthDeviceTest \
 *         -e readingDepth 16384 us.theworkmans.proseplow.llama.test/androidx.test.runner.AndroidJUnitRunner
 *
 * A model is read to that many tokens, or to its own largest context if that is less, or to
 * the largest size whose memory stays inside a fraction of what the system reports as free
 * above its own low-memory threshold (0.4 unless `-e contextFraction` names another, between
 * 0.05 and 0.5). That fraction is the whole safeguard, as in [ContextGrowthDeviceTest].
 * `-e readingChunk` names how many tokens are evaluated in one go (the engine's 32 unless
 * named, and at most the 256 a session is opened with): a larger chunk passes over what was
 * read once for more tokens, and the run shows what that is worth. `-e readingModel` with a
 * model's id reads only that model.
 *
 * Each line reports one block of [BLOCK] tokens, read in chunks of the size named: how many
 * tokens were in memory when it began and how long it took. The last line of a model fits a
 * straight line through the cost of a token against the tokens already read, and says what
 * reading a whole conversation of several lengths costs by that line.
 */
@RunWith(AndroidJUnit4::class)
class ReadingDepthDeviceTest {

    @get:Rule
    val inFront = ActivityScenarioRule(ForegroundActivity::class.java)

    @Test
    fun readingSlowsWithWhatWasAlreadyRead() {
        val arguments = InstrumentationRegistry.getArguments()
        val asked = arguments.getString("readingDepth")?.toIntOrNull()
        assumeTrue("not asked for: pass -e readingDepth with a number of tokens (the run takes memory on purpose, and minutes)", asked != null && asked >= 2 * BLOCK)
        val fraction = (arguments.getString("contextFraction")?.toDoubleOrNull() ?: DEFAULT_FRACTION).coerceIn(0.05, 0.5)
        val chunk = (arguments.getString("readingChunk")?.toIntOrNull() ?: ENGINE_CHUNK).coerceIn(1, NativeSettings().batchTokens)
        val only = arguments.getString("readingModel")
        val models = DEPTH_MODELS.filter { ModelFiles.locate(appContext, it) != null && (only == null || it.id == only) }
        assumeTrue("no model of this measurement is present", models.isNotEmpty())
        for (model in models) measure(model, checkNotNull(asked), fraction, chunk)
    }

    private fun measure(model: ModelSpec, asked: Int, fraction: Double, chunk: Int) {
        val native = open(model)
        try {
            val opened = native.contextSize()
            val base = native.tokens(FILLER.repeat(8), false)
            // Once unmeasured: the first evaluation builds the thread pool and touches every page of the weights.
            native.mustEvaluate(IntArray(WARM_UP_TOKENS) { base[it % base.size] })
            native.clearMemory()

            // The size to read to. Forgetting returns a context to its opening size, so it is set after the warm-up.
            val room = (fraction * freeAboveThreshold()).toLong()
            val budget = native.contextBytes(opened) + room
            var size = minOf(asked, native.maxContextSize) / BLOCK * BLOCK
            while (size > opened && native.contextBytes(size) > budget) size -= BLOCK
            if (size > opened) {
                try {
                    native.resizeContext(size, budget)
                } catch (e: NativeLlamaException) {
                    report("${model.id}: a context of $size tokens was not granted (code ${e.code}); reading to ${native.contextSize()}")
                }
            }
            size = native.contextSize()
            val threads = native.threads()
            report(
                "${model.id}: reading to a context of $size tokens (${mib(native.contextBytes(size))} MiB by the session's count; asked for $asked, the model's largest is " +
                    "${native.maxContextSize}, room ${mib(room)} MiB) in chunks of $chunk; ${threads.count} thread(s)${if (threads.pinned) ", pinned" else ", not pinned"}; ${schedulingGroup()}; ${memory()}",
            )

            // Block after block, each in the engine's chunks, with room left for the tokens generated at the end.
            val blocks = (size - GENERATED - 1) / BLOCK
            val depths = DoubleArray(blocks)
            val secondsPerToken = DoubleArray(blocks)
            var total = 0L
            for (block in 0 until blocks) {
                val before = native.memoryTokenCount()
                val nanos = native.mustEvaluate(IntArray(BLOCK) { base[(before + it) % base.size] }, chunk)
                total += nanos
                depths[block] = before + BLOCK / 2.0
                secondsPerToken[block] = nanos / 1e9 / BLOCK
                report(
                    "${model.id}: with $before tokens read, the next $BLOCK took %.2f s (%.1f tokens/s); %.1f s since the start".format(
                        nanos / 1e9, BLOCK * 1e9 / nanos, total / 1e9,
                    ),
                )
            }

            native.configureSampler(greedy(SamplerConfig.NO_PENALTY_WINDOW, model.bannedTokenIds))
            val isEnd = { token: Int -> native.isEndOfGeneration(token) || token in model.stopTokenIds }
            val read = native.memoryTokenCount()
            val start = System.nanoTime()
            val reply = native.continueReply(GENERATED, isEnd)
            val replyNanos = System.nanoTime() - start
            report(
                "${model.id}: with $read tokens read, generation %.1f tokens/s over ${reply.size}; ${schedulingGroup()}; ${memory()}".format(
                    if (reply.isEmpty()) 0.0 else reply.size * 1e9 / replyNanos,
                ),
            )

            if (blocks >= 2) {
                // Least squares: seconds for one token = atStart + perTokenRead * (tokens already read).
                val meanX = depths.average()
                val meanY = secondsPerToken.average()
                var sxy = 0.0
                var sxx = 0.0
                for (i in 0 until blocks) {
                    sxy += (depths[i] - meanX) * (secondsPerToken[i] - meanY)
                    sxx += (depths[i] - meanX) * (depths[i] - meanX)
                }
                val perTokenRead = sxy / sxx
                val atStart = meanY - perTokenRead * meanX
                var worst = 0.0
                for (i in 0 until blocks) worst = maxOf(worst, Math.abs(atStart + perTokenRead * depths[i] - secondsPerToken[i]) / secondsPerToken[i])
                val whole = LENGTHS.joinToString(", ") { n -> "$n in %.0f s".format(atStart * n + perTokenRead * n.toDouble() * n / 2) }
                report(
                    "${model.id}: a token costs %.3f ms with nothing read (%.1f tokens/s) and %.1f ns more for every token already read; the line misses a block by %.0f%% at most; ".format(
                        atStart * 1e3, 1 / atStart, perTokenRead * 1e9, worst * 100,
                    ) + "by that line, reading a whole conversation from nothing takes: $whole",
                )
            }
        } finally {
            native.close()
        }
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
        /** Every bundled model: the two whose context can grow, and the one that is read to its own limit. */
        val DEPTH_MODELS: List<ModelSpec> = listOf(ModelCatalog.QWEN35_0_8B, ModelCatalog.PEACEBELL_V1_291M, ModelCatalog.BORIS_2_INSTRUCT)

        const val DEFAULT_FRACTION: Double = 0.4

        /** The tokens of one measured block. */
        const val BLOCK: Int = 512

        const val WARM_UP_TOKENS: Int = 256

        const val GENERATED: Int = 16

        /** The lengths of conversation whose reading time the fitted line is asked for. */
        val LENGTHS: List<Int> = listOf(2048, 4096, 8192, 16384, 32768, 65536)

        const val FILLER: String = "The quick brown fox jumps over the lazy dog, and then it does so again and again until evening. "
    }
}
