package us.theworkmans.proseplow.llama

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import us.theworkmans.proseplow.core.engine.EvaluationObserver
import us.theworkmans.proseplow.core.engine.FinishReason
import us.theworkmans.proseplow.core.engine.GenerationEvent
import us.theworkmans.proseplow.core.engine.GenerationRequest
import us.theworkmans.proseplow.core.engine.LlamaEngine
import us.theworkmans.proseplow.core.engine.NativeLlama
import us.theworkmans.proseplow.core.engine.NativeLlamaFactory
import us.theworkmans.proseplow.core.engine.ReadAheadResult
import us.theworkmans.proseplow.core.engine.SamplerConfig
import us.theworkmans.proseplow.core.model.ModelCatalog
import us.theworkmans.proseplow.core.model.ModelSpec
import us.theworkmans.proseplow.core.prompt.PromptMessage
import us.theworkmans.proseplow.core.prompt.PromptRole
import us.theworkmans.proseplow.core.prompt.PromptWhitespace
import java.util.concurrent.Executors

/**
 * What a conversation costs to read again after an answer, on the phone, with the real engine
 * over the real session: the acceptance test of reading ahead ([LlamaEngine.readAhead]).
 *
 * After an answer that ended by itself and one cut off at its length limit, the engine reads
 * ahead as the app has it do (at once, or once the user starts typing), and the next message
 * must then read no more than its own turn and a small constant: [OPENING_ALLOWANCE] tokens for
 * the markers around it, the opening of the reply, and for Boris-2 the end of the earlier
 * answer that its plain-text markers leave unread. For Qwen3.5 the read-ahead must have read
 * (its template writes the answer differently once it is history); for Boris-2 it must find
 * nothing to do and touch nothing. After an answer stopped by its reader once it is several
 * hundred tokens long ([STOP_AFTER_DELTAS]), "Continue" without a read-ahead must read no more
 * than two intervals of the checkpoints that follow a reply and whatever of the prompt differs
 * from the memory: without those checkpoints it would read the whole answer again, so the
 * bound tells the two apart by hundreds of tokens.
 *
 * The requests are the app's: the model's own sampling (with a fixed seed), its prompt format,
 * the history as [us.theworkmans.proseplow.core.chat.promptHistory] sends it (every text
 * trimmed) and a conversation key. Each case prints a line that begins with `PROSEPLOW`, with
 * counts only, never text; the session is wrapped in a [Recording] that notes what changed its
 * memory, for the lines and for the assertion that Boris-2's read-ahead changes nothing.
 */
@RunWith(AndroidJUnit4::class)
class RereadDiagnosisDeviceTest {

    @get:Rule
    val inFront = ActivityScenarioRule(ForegroundActivity::class.java)

    @Test
    fun qwen() = accept(ModelCatalog.QWEN35_0_8B, readsAhead = true)

    @Test
    fun boris() = accept(ModelCatalog.BORIS_2_INSTRUCT, readsAhead = false)

    /** The session with every change of its memory noted. */
    private class Recording(private val d: NativeLlama) : NativeLlama by d {
        /** Live checkpoints: handle to the length of the memory they hold. */
        val live = LinkedHashMap<Int, Int>()

        /** What changed the memory since it was last cleared: evaluations, cuts, checkpoints. */
        val log = ArrayList<String>()

        override fun evaluate(tokens: IntArray, chunkSize: Int, observer: EvaluationObserver): Int {
            val from = d.memoryTokenCount()
            val done = d.evaluate(tokens, chunkSize, observer)
            log += "read $from+$done"
            return done
        }

        override fun configureSampler(config: SamplerConfig) = d.configureSampler(config)

        override fun saveCheckpoint(): Int {
            val handle = d.saveCheckpoint()
            val at = d.memoryTokenCount()
            if (handle >= 0) live[handle] = at
            log += "save#$handle@$at"
            return handle
        }

        override fun restoreCheckpoint(checkpoint: Int): Boolean {
            val ok = d.restoreCheckpoint(checkpoint)
            log += "restore#$checkpoint@${live[checkpoint]}=$ok"
            return ok
        }

        override fun dropCheckpoint(checkpoint: Int) {
            d.dropCheckpoint(checkpoint)
            log += "drop#$checkpoint@${live.remove(checkpoint)}"
        }

        override fun rewindTo(tokenCount: Int): Boolean {
            val ok = d.rewindTo(tokenCount)
            log += "rewindTo($tokenCount)=$ok"
            return ok
        }

        override fun clearMemory() {
            d.clearMemory()
            live.clear()
            log += "clear"
        }
    }

    private enum class Ending { BY_ITSELF, LENGTH, STOPPED }

    private fun accept(model: ModelSpec, readsAhead: Boolean) {
        assumePresent(model)
        val executor = Executors.newSingleThreadExecutor()
        var session: Recording? = null
        val factory = NativeLlamaFactory { m, progress, context, budget ->
            Recording(testFactory().open(m, progress, context, budget)).also { session = it }
        }
        val engine = LlamaEngine(factory, executor.asCoroutineDispatcher())
        val interval = LlamaEngine.Settings().followingCheckpointInterval
        try {
            runBlocking {
                engine.load(model)
                val rec = checkNotNull(session)
                val sampling = model.sampling.copy(seed = SEED)
                fun request(messages: List<PromptMessage>, maxNewTokens: Int? = null, continueFinal: Boolean = false) = GenerationRequest(
                    model.id, messages, sampling = sampling, maxNewTokens = maxNewTokens, continueFinalMessage = continueFinal, conversationKey = KEY,
                )
                for (ending in Ending.entries) {
                    engine.forget()
                    rec.log.clear()
                    val tag = "${model.id} ${ending.name}"
                    val question = if (ending == Ending.BY_ITSELF) SHORT_QUESTION else LONG_QUESTION
                    val first = request(listOf(PromptMessage(PromptRole.USER, question)), maxNewTokens = if (ending == Ending.LENGTH) CUT_AT else null)
                    val replyText = if (ending == Ending.STOPPED) {
                        val text = StringBuilder()
                        val enough = CompletableDeferred<Unit>()
                        var deltas = 0
                        var endedByItself = false
                        val job = launch {
                            engine.generate(first).collect { event ->
                                if (event is GenerationEvent.TextDelta) {
                                    text.append(event.text)
                                    if (++deltas >= STOP_AFTER_DELTAS) enough.complete(Unit)
                                }
                                if (event is GenerationEvent.Done) {
                                    // Ended before it was long enough to stop.
                                    if (deltas < STOP_AFTER_DELTAS) endedByItself = true
                                    enough.complete(Unit)
                                }
                            }
                        }
                        enough.await()
                        job.cancelAndJoin()
                        assertTrue("$tag: the answer ended by itself after $deltas pieces, before it could be stopped", !endedByItself && deltas >= STOP_AFTER_DELTAS)
                        text.toString()
                    } else {
                        val done = finished(engine.generate(first).toList())
                        if (ending == Ending.LENGTH) {
                            assertEquals(tag, FinishReason.LENGTH, done.finishReason)
                        } else {
                            assertTrue("$tag: ${done.finishReason}", done.finishReason == FinishReason.END_OF_TURN || done.finishReason == FinishReason.STOP_STRING)
                        }
                        done.text
                    }
                    val question1 = PromptMessage(PromptRole.USER, PromptWhitespace.strip(question))
                    val memoryAfterAnswer = rec.memoryTokenCount()

                    if (ending == Ending.STOPPED) {
                        // "Continue" without a read-ahead, as the app sends it: the answer with its start trimmed.
                        val continued = listOf(question1, PromptMessage(PromptRole.ASSISTANT, PromptWhitespace.stripStart(replyText)))
                        val held = rec.memoryTokens()
                        rec.log.clear()
                        val done = finished(engine.generate(request(continued, maxNewTokens = 1, continueFinal = true)).toList())
                        val prompt = done.stats.promptTokens
                        // With a limit of one token nothing is read after the prompt: the memory is the prompt.
                        val common = commonPrefix(held, rec.memoryTokens())
                        val difference = prompt - minOf(common, prompt)
                        report("reread $tag: memory $memoryAfterAnswer, continue prompt $prompt, common $common, read ${done.stats.evaluatedPromptTokens} in ${done.stats.promptMillis} ms; ${rec.log.joinToString(" ")}")
                        assertTrue(
                            "$tag: continue read ${done.stats.evaluatedPromptTokens} tokens, more than ${2 * interval} and the difference $difference",
                            done.stats.evaluatedPromptTokens <= 2 * interval + difference,
                        )
                        continue
                    }

                    // The read-ahead, as the app asks for it: the history as the next message will carry it.
                    val history = listOf(question1, PromptMessage(PromptRole.ASSISTANT, PromptWhitespace.strip(replyText)))
                    rec.log.clear()
                    val started = System.nanoTime()
                    val result = engine.readAhead(request(history))
                    val aheadMillis = (System.nanoTime() - started) / 1_000_000
                    val aheadLog = rec.log.joinToString(" ")
                    val memoryAfterReadAhead = rec.memoryTokenCount()
                    if (readsAhead) {
                        assertTrue("$tag: $result", result is ReadAheadResult.Read)
                    } else {
                        assertEquals(tag, ReadAheadResult.Skipped(ReadAheadResult.Skipped.Reason.NOTHING_TO_READ), result)
                        assertEquals("$tag: the read-ahead changed the memory", "", aheadLog)
                    }

                    // The next message.
                    val next = history + PromptMessage(PromptRole.USER, FOLLOW_UP)
                    rec.log.clear()
                    val done = finished(engine.generate(request(next, maxNewTokens = 1)).toList())
                    val ownTurn = done.stats.promptTokens - memoryAfterReadAhead
                    val messageTokens = rec.tokenize(FOLLOW_UP.toByteArray(Charsets.UTF_8), false).size
                    report(
                        "reread $tag: memory after the answer $memoryAfterAnswer, read-ahead $result in $aheadMillis ms ($aheadLog), " +
                            "memory after it $memoryAfterReadAhead; next prompt ${done.stats.promptTokens}, message $messageTokens tokens, " +
                            "read ${done.stats.evaluatedPromptTokens} in ${done.stats.promptMillis} ms; ${rec.log.joinToString(" ")}",
                    )
                    assertTrue("$tag: the next message went back", rec.log.none { it.startsWith("restore") || it == "clear" || (it.startsWith("rewindTo") && !it.endsWith("=true")) })
                    assertEquals("$tag: the next message kept the whole memory", ownTurn, done.stats.evaluatedPromptTokens)
                    assertTrue(
                        "$tag: the next message read ${done.stats.evaluatedPromptTokens} tokens, more than its own $messageTokens and $OPENING_ALLOWANCE",
                        done.stats.evaluatedPromptTokens <= messageTokens + OPENING_ALLOWANCE,
                    )
                }
                engine.forget()
                engine.unload()
            }
        } finally {
            executor.shutdown()
        }
    }

    private fun finished(events: List<GenerationEvent>): GenerationEvent.Done {
        events.filterIsInstance<GenerationEvent.Error>().firstOrNull()?.let { fail("${it.error}") }
        return events.last() as GenerationEvent.Done
    }

    private fun commonPrefix(a: IntArray, b: IntArray): Int {
        var i = 0
        while (i < a.size && i < b.size && a[i] == b[i]) i++
        return i
    }

    private companion object {
        const val SEED = 20261123
        const val CUT_AT = 96

        /** How many pieces of text the stopped answer has: several hundred tokens, far more than the 64 "Continue" may read. */
        const val STOP_AFTER_DELTAS = 400

        const val KEY = "acceptance"

        /**
         * What the next message may read besides its own text: the markers of its turn and the
         * opening of the reply (12 tokens for Qwen3.5, whose markers are single tokens), and for
         * Boris-2, whose markers are spelled in several ordinary tokens, those and the end of
         * the answer before it.
         */
        const val OPENING_ALLOWANCE = 48
        const val SHORT_QUESTION = "What is the capital of France? Answer in three words."
        const val LONG_QUESTION = "Write a long, detailed story about a lighthouse keeper on a stormy island."
        const val FOLLOW_UP = "Thank you. Say it again in one sentence."
    }
}
