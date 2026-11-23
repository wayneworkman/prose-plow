package us.theworkmans.proseplow.llama

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import us.theworkmans.proseplow.core.engine.EngineState
import us.theworkmans.proseplow.core.engine.EvaluationObserver
import us.theworkmans.proseplow.core.engine.FinishReason
import us.theworkmans.proseplow.core.engine.GenerationEvent
import us.theworkmans.proseplow.core.engine.GenerationRequest
import us.theworkmans.proseplow.core.engine.LlamaEngine
import us.theworkmans.proseplow.core.engine.NativeLlama
import us.theworkmans.proseplow.core.engine.NativeLlamaException
import us.theworkmans.proseplow.core.model.ModelSpec
import us.theworkmans.proseplow.core.model.SamplingParams
import us.theworkmans.proseplow.core.prompt.PromptMessage
import us.theworkmans.proseplow.core.prompt.PromptRole
import java.util.concurrent.Executors

/*
 * The checks that every model gets on the phone, written once. Each takes what differs between
 * the models as arguments: the token sequences to use, whether the model's memory has recurrent
 * state, and what ends a reply.
 */

/** How many tokens are generated to compare two states of the memory. */
internal const val TAIL: Int = 12

/**
 * Shortening the memory. A model with attention only must let its end go and continue exactly
 * as a fresh evaluation does. A model with recurrent state must refuse, stay untouched by the
 * refusal, and come back through a checkpoint to the same exact continuation, more than once.
 */
internal fun checkRewind(native: NativeLlama, a: IntArray, b: IntArray, c: IntArray, recurrent: Boolean, isEnd: (Int) -> Boolean) {
    val fresh = native.freshReply(a + c, TAIL, isEnd = isEnd).tokens
    val undisturbed = native.freshReply(a + b + c, TAIL, isEnd = isEnd).tokens

    native.clearMemory()
    native.mustEvaluate(a)
    val checkpoint = native.saveCheckpoint()
    assertTrue("a checkpoint can be saved", checkpoint >= 0)
    native.mustEvaluate(b)
    native.configureSampler(greedy(native.contextSize()))

    val removed = native.rewindTo(a.size)
    if (recurrent) {
        assertFalse("a model with recurrent state refuses to give up the end of its memory", removed)
        assertEquals("the refusal leaves the memory's length alone", a.size + b.size, native.memoryTokenCount())
        native.mustEvaluate(c)
        assertEquals("after the refusal the memory continues as an undisturbed one does", undisturbed, native.continueReply(TAIL, isEnd))

        repeat(2) { round ->
            assertTrue("the checkpoint restores (round ${round + 1})", native.restoreCheckpoint(checkpoint))
            assertEquals(a.size, native.memoryTokenCount())
            expectFailure(NativeLlamaException.SAMPLING_FAILED, "sampling straight after a restore") { native.sampleNext() }
            native.mustEvaluate(c)
            assertEquals("the continuation after the restore equals a fresh evaluation (round ${round + 1})", fresh, native.continueReply(TAIL, isEnd))
        }
    } else {
        assertTrue("an attention-only model gives up the end of its memory", removed)
        assertEquals(a.size, native.memoryTokenCount())
        expectFailure(NativeLlamaException.SAMPLING_FAILED, "sampling straight after a removal") { native.sampleNext() }
        native.mustEvaluate(c)
        assertEquals("the continuation after the removal equals a fresh evaluation", fresh, native.continueReply(TAIL, isEnd))

        assertTrue("a checkpoint works for this model too", native.restoreCheckpoint(checkpoint))
        native.mustEvaluate(c)
        assertEquals("and gives the same continuation", fresh, native.continueReply(TAIL, isEnd))
    }

    native.dropCheckpoint(checkpoint)
    assertFalse("a dropped checkpoint is unknown", native.restoreCheckpoint(checkpoint))
    native.clearMemory()
    assertEquals(0, native.memoryTokenCount())
}

/**
 * Cancelling an evaluation: it must return promptly, without an error, with the memory's length
 * telling the truth, and the memory must be usable afterwards. An attention-only model is
 * interrupted inside a chunk; a model with recurrent state finishes the chunk it is in.
 *
 * @return the milliseconds between the observer first answering "stop" and the call returning.
 */
internal fun checkCancellation(native: NativeLlama, tokens: IntArray, recurrent: Boolean, isEnd: (Int) -> Boolean): Double {
    require(tokens.size > ENGINE_CHUNK) { "the cancellation check needs more than one chunk of tokens" }
    val uninterrupted = native.freshReply(tokens, TAIL, isEnd = isEnd).tokens

    native.clearMemory()
    var polls = 0
    var stopAt = 0L
    val observer = object : EvaluationObserver {
        override fun onProgress(evaluatedTokens: Int) = Unit
        override fun isCancelled(): Boolean {
            // "Stop" from the third time of asking: after the polls before the first chunks, or,
            // where a chunk can be interrupted, a few milliseconds into the first one.
            polls++
            if (polls >= 3 && stopAt == 0L) stopAt = System.nanoTime()
            return polls >= 3
        }
    }
    // One large chunk where it can be interrupted; the engine's chunk where it cannot.
    val chunk = if (recurrent) ENGINE_CHUNK else tokens.size
    val evaluated = native.evaluate(tokens, chunk, observer)
    val latency = (System.nanoTime() - stopAt) / 1e6
    assertTrue("the cancelled evaluation stopped early", evaluated < tokens.size)
    assertEquals("the memory holds what the call says it holds", evaluated, native.memoryTokenCount())
    if (recurrent) {
        assertEquals("a model with recurrent state stops between chunks", 2 * ENGINE_CHUNK, evaluated)
    } else {
        assertEquals("an interrupted chunk is taken out of the memory again", 0, evaluated)
    }
    expectFailure(NativeLlamaException.SAMPLING_FAILED, "sampling after a cancelled evaluation") { native.sampleNext() }

    native.mustEvaluate(tokens.copyOfRange(evaluated, tokens.size))
    native.configureSampler(greedy(native.contextSize()))
    assertEquals("the memory is intact after the cancellation", uninterrupted, native.continueReply(TAIL, isEnd))
    return latency
}

/** Speeds of one way of running a model, in tokens per second. */
internal class Speeds(val promptByChunk: Double, val promptInOneBatch: Double, val generation: Double, val threads: LlamaSession.ThreadSetup)

/**
 * Opens [model] with [settings] (and [plan], when the threads are to be chosen by the test) and
 * measures a prompt of [prompt] evaluated in the engine's chunks and in one go, and
 * [generated] tokens of greedy generation.
 */
internal fun measureSpeeds(
    model: ModelSpec,
    settings: NativeSettings,
    prompt: IntArray,
    generated: Int,
    plan: ThreadPlan? = null,
    isEnd: (Int) -> Boolean = { false },
): Speeds {
    val factory = if (plan == null) LlamaFactory(appContext, settings) else LlamaFactory(appContext, settings, plan)
    val native = factory.open(model) { true } as LlamaSession
    try {
        // Once unmeasured: the first evaluation builds the thread pool and touches every page.
        native.mustEvaluate(prompt)
        native.clearMemory()
        val byChunk = prompt.size * 1e9 / native.mustEvaluate(prompt, ENGINE_CHUNK)
        native.clearMemory()
        val inOne = prompt.size * 1e9 / native.mustEvaluate(prompt, settings.batchTokens)
        native.configureSampler(greedy(native.contextSize(), model.bannedTokenIds))
        val start = System.nanoTime()
        val reply = native.continueReply(generated, isEnd)
        val generation = reply.size * 1e9 / (System.nanoTime() - start)
        return Speeds(byChunk, inOne, generation, native.threads())
    } finally {
        native.close()
    }
}

internal fun reportSpeeds(label: String, speeds: Speeds, promptTokens: Int) {
    report(
        "$label: prompt %.1f tokens/s in chunks of %d, %.1f in one batch (%d tokens); generation %.1f tokens/s; %d thread(s)%s".format(
            speeds.promptByChunk, ENGINE_CHUNK, speeds.promptInOneBatch, promptTokens, speeds.generation, speeds.threads.count,
            if (speeds.threads.pinned) ", pinned" else ", not pinned",
        ),
    )
}

/**
 * Load time and memory of [model] opened with [settings]: the process's memory before, with
 * the model loaded and a prompt evaluated, after forgetting, and after closing; and how long
 * the forgetting took, which is what a lock waits for (the native side frees the context and
 * creates it again, at the model's real context size).
 */
internal fun checkLoadAndMemory(label: String, model: ModelSpec, settings: NativeSettings, prompt: IntArray) {
    val before = memory()
    val start = System.nanoTime()
    val native = open(model, settings)
    val loadMillis = (System.nanoTime() - start) / 1e6
    val loaded = memory()
    try {
        assertEquals(model.contextLength, native.contextSize())
        native.mustEvaluate(prompt)
        val used = memory()
        val clearStart = System.nanoTime()
        native.clearMemory()
        val clearMillis = (System.nanoTime() - clearStart) / 1e6
        val cleared = memory()
        report("$label: load %.0f ms; before: $before".format(loadMillis))
        report("$label: loaded: $loaded")
        report("$label: after a prompt of ${prompt.size} tokens: $used")
        report("$label: after clearing the memory, which took %.0f ms: $cleared".format(clearMillis))
    } finally {
        native.close()
    }
    val closed = memory()
    report("$label: after closing: $closed")
    val grown = loaded.pss - before.pss
    assertTrue(
        "closing gives the model's memory back (before %.0f, loaded %.0f, closed %.0f MiB)".format(before.pss, loaded.pss, closed.pss),
        closed.pss - before.pss < 0.1 * grown + 24.0,
    )
}

/**
 * The engine of `:core` on the real native layer: a chat of two turns, greedy. The second
 * request repeats the first turn with the model's own reply, so the engine must find its memory
 * (or a checkpoint of it) and evaluate only what is new.
 *
 * @return the text of the first reply, for the caller to judge; it is never reported.
 */
internal fun checkEngineChat(model: ModelSpec, systemPrompt: String?, first: String, second: String, maxNewTokens: Int = 48): String {
    val executor = Executors.newSingleThreadExecutor()
    val engine = LlamaEngine(LlamaFactory(appContext, NativeSettings()), executor.asCoroutineDispatcher())
    try {
        return runBlocking {
            engine.load(model)
            assertEquals(EngineState.Ready(model.id), engine.state.value)

            suspend fun turn(messages: List<PromptMessage>): GenerationEvent.Done {
                val events = engine.generate(
                    GenerationRequest(
                        modelId = model.id,
                        messages = messages,
                        systemPrompt = systemPrompt,
                        sampling = SamplingParams(temperature = 0f),
                        maxNewTokens = maxNewTokens,
                    ),
                ).toList()
                val error = events.filterIsInstance<GenerationEvent.Error>().firstOrNull()
                if (error != null) fail("the engine reported ${error.error}")
                val done = events.last() as GenerationEvent.Done
                assertEquals("the text of the deltas is the text of the reply", done.text, events.filterIsInstance<GenerationEvent.TextDelta>().joinToString("") { it.text })
                assertTrue("the reply ended in an ordinary way: ${done.finishReason}", done.finishReason != FinishReason.CANCELLED)
                return done
            }

            val one = turn(listOf(PromptMessage(PromptRole.USER, first)))
            assertTrue("the first reply has text", one.text.isNotBlank())
            val two = turn(
                listOf(
                    PromptMessage(PromptRole.USER, first),
                    PromptMessage(PromptRole.ASSISTANT, one.text),
                    PromptMessage(PromptRole.USER, second),
                ),
            )
            assertTrue("the second reply has text", two.text.isNotBlank())
            assertTrue("the second turn reuses the memory of the first (${two.stats.reusedPromptTokens} tokens)", two.stats.reusedPromptTokens > 0)
            for ((name, done) in listOf("first turn" to one, "second turn" to two)) {
                report(
                    "${model.id} engine, $name: ${done.finishReason}, prompt ${done.stats.promptTokens} tokens (${done.stats.reusedPromptTokens} reused) " +
                        "at %.1f tokens/s, reply ${done.stats.generatedTokens} tokens at %.1f tokens/s".format(
                            done.stats.promptTokensPerSecond ?: 0.0, done.stats.generatedTokensPerSecond ?: 0.0,
                        ),
                )
            }
            engine.forget()
            engine.unload()
            assertEquals(EngineState.Unloaded, engine.state.value)
            one.text
        }
    } finally {
        executor.shutdown()
    }
}

/** Runs [block] and requires it to throw [NativeLlamaException] with [code]. */
internal fun expectFailure(code: Int, what: String, block: () -> Unit) {
    try {
        block()
    } catch (e: NativeLlamaException) {
        assertEquals("$what fails with the expected code (${e.message})", code, e.code)
        return
    }
    fail("$what should have failed with code $code")
}
