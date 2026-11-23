package us.theworkmans.proseplow.llama

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import us.theworkmans.proseplow.core.engine.EvaluationObserver
import us.theworkmans.proseplow.core.model.ModelCatalog
import us.theworkmans.proseplow.core.model.ModelSpec

/** Plain prose to make a long prompt of; written for this project. */
private const val PASSAGE: String =
    "The ferry leaves the island at seven in the morning and again at four in the afternoon, and in winter " +
        "only when the wind allows it. The crossing takes forty minutes, long enough for a cup of tea and " +
        "not long enough to finish a chapter, which is why the regular passengers bring a newspaper. "

/**
 * What a session does when Android moves the process to another set of cores in the middle of
 * ONE evaluation call. A long prompt is evaluated as one call of many seconds, so a session
 * that only looked at its threads when a call begins would compute the rest of such a call with
 * threads pinned to cores the process may no longer use, several of them on one core, which is
 * the stall that the thread plan exists to prevent (see `CpuTopology.kt`). The session looks
 * before every chunk instead, and this checks that it does, on the phone's own scheduler.
 *
 * The move is made the way a user makes it: the test's activity leaves the front while the
 * prompt is being evaluated. Runs with whichever model of the catalogue is present, the smallest
 * first, and is skipped on a phone without cores to pin to or one that does not move the process.
 */
@RunWith(AndroidJUnit4::class)
class ThreadMoveDeviceTest {

    @get:Rule
    val inFront = ActivityScenarioRule(ForegroundActivity::class.java)

    private val model: ModelSpec by lazy {
        val present = listOf(ModelCatalog.BORIS_2_INSTRUCT, ModelCatalog.PEACEBELL_V1_291M, ModelCatalog.QWEN35_0_8B, ModelCatalog.SHARD_2)
            .firstOrNull { ModelFiles.locate(appContext, it) != null }
        assumeTrue("no model is in this test APK or sideloaded", present != null)
        present!!
    }

    @Test
    fun aMoveDuringOneEvaluationIsFollowedAtTheNextChunk() {
        val plan = ThreadPlanner.plan(CpuSysfs().capacities(), Runtime.getRuntime().availableProcessors())
        assumeTrue("this phone has no cores to pin a thread to", plan.pinnedCpus.isNotEmpty())
        val native = open(model, NativeSettings(pinThreads = true))
        try {
            val passage = native.tokens(PASSAGE.repeat(40), false)
            val prompt = passage.copyOf(minOf(passage.size, native.contextSize() - 64, 1536))

            // Once unmeasured, with the activity in front: the pool is built, and pinned.
            native.mustEvaluate(prompt.copyOf(2 * ENGINE_CHUNK))
            native.clearMemory()
            val before = native.threads()
            val groupBefore = schedulingGroup()
            assumeTrue("the threads could not be pinned with the test in front ($before; $groupBefore)", before.pinned)

            val chunkMillis = ArrayList<Long>()
            var last = System.nanoTime()
            var chunksInFront = 0
            val evaluated = native.evaluate(prompt, ENGINE_CHUNK, object : EvaluationObserver {
                override fun onProgress(evaluatedTokens: Int) {
                    chunkMillis += (System.nanoTime() - last) / 1_000_000
                    if (chunksInFront == 0 && chunkMillis.size == 2) {
                        chunksInFront = chunkMillis.size
                        // The activity goes; the system moves the process to its new set of
                        // cores a moment afterwards, while the chunks that follow are computed.
                        inFront.scenario.close()
                    }
                    last = System.nanoTime()
                }

                override fun isCancelled(): Boolean = false
            })
            val after = native.threads()
            val groupAfter = schedulingGroup()
            val inFrontMillis = chunkMillis.take(chunksInFront)
            val movedMillis = chunkMillis.drop(chunksInFront)
            report(
                "${model.id}: ${prompt.size} tokens in one call of ${chunkMillis.size} chunks, the activity closed after $chunksInFront; " +
                    "threads before: ${before.count}${if (before.pinned) " pinned" else ""}, after: ${after.count}${if (after.pinned) " pinned" else ""}; " +
                    "a chunk took ${inFrontMillis.maxOrNull()} ms at most in front, then between ${movedMillis.minOrNull()} and ${movedMillis.maxOrNull()} ms",
            )
            report("${model.id}: in front: $groupBefore; when the call returned: $groupAfter")

            assertEquals("the whole prompt was evaluated", prompt.size, evaluated)
            assumeTrue("the system did not move the process while the prompt was evaluated ($groupAfter)", groupAfter != groupBefore)
            assertFalse(
                "after the move the session runs free threads, in the same call (a session that only looked when a call begins would still say pinned)",
                after.pinned,
            )
            assertEquals("and as many of them as the plan gives for threads that cannot be pinned", plan.unpinnedThreads, after.count)
        } finally {
            native.close()
        }
    }
}
