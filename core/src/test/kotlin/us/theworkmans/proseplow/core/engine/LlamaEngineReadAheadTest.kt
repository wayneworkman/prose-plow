package us.theworkmans.proseplow.core.engine

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import us.theworkmans.proseplow.core.engine.FakeNativeLlama.Call
import us.theworkmans.proseplow.core.model.ModelFamily
import us.theworkmans.proseplow.core.model.ModelSpec
import us.theworkmans.proseplow.core.model.ReadingTimes
import us.theworkmans.proseplow.core.prompt.PlainChatMlFormat
import us.theworkmans.proseplow.core.prompt.PromptInput
import us.theworkmans.proseplow.core.prompt.PromptMessage
import us.theworkmans.proseplow.core.prompt.PromptWhitespace
import us.theworkmans.proseplow.core.prompt.Qwen35ChatMlFormat

/**
 * Reading ahead ([LlamaEngine.readAhead]): after an answer, the conversation as the next new
 * message will render it is read up to where that message begins, so that the message reads
 * only itself.
 *
 * The model here behaves like Qwen3.5 where it matters: its prompt format is Qwen3.5's own, which
 * opens the turn being written with an empty think block and writes the same answer without it
 * once it is history, and it cannot cut its memory back (recurrent state). The fake tokenises
 * one token per byte of text and one per control token. It reads 10 tokens a second by its
 * curve, so the few hundred tokens of a conversation here are worth saving.
 */
class LlamaEngineReadAheadTest {

    private val qwen: ModelSpec = testModel(family = ModelFamily.QWEN35_CHATML, contextLength = 4000, maxNewTokens = 1000)
        .copy(readingTimes = ReadingTimes.flat(10.0), cutsMemoryBack = false)

    private val cannotCut: (FakeNativeLlama) -> Unit = { it.refuseRewind = true }

    private val vault = FakeMemoryVault()

    private val question = user("Tell me a long story.")

    private suspend fun TestScope.engine(
        spec: ModelSpec = qwen,
        settings: LlamaEngine.Settings = LlamaEngine.Settings(),
        gate: GenerationGate = GenerationGate(),
    ): TestEngine = loadedEngine(spec, settings, gate, cannotCut).also { test -> vault.onCommit = { test.fake.memory.toList() } }

    private fun ask(vararg messages: PromptMessage, continueFinal: Boolean = false, maxNewTokens: Int = 1000): GenerationRequest =
        request(*messages, continueFinalMessage = continueFinal, maxNewTokens = maxNewTokens).copy(conversationKey = KEY)

    /** Runs [messages] with the scripted [reply] and returns the events. */
    private suspend fun TestEngine.generate(vararg messages: PromptMessage, reply: String = "Fine.", continueFinal: Boolean = false, maxNewTokens: Int = 1000): List<GenerationEvent> {
        fake.enqueueReply(reply)
        return engine.generate(ask(*messages, continueFinal = continueFinal, maxNewTokens = maxNewTokens)).toList()
    }

    /** An answer of [length] tokens that ends by itself, as the chat stores it. */
    private suspend fun TestEngine.answerEndedByItself(length: Int = 300): PromptMessage {
        val text = generate(question, reply = "A".repeat(length)).done().text
        return assistant(PromptWhitespace.strip(text))
    }

    /** An answer cut off at [limit] tokens; its text. */
    private suspend fun TestEngine.answerCutAt(limit: Int = 300): String {
        val done = generate(question, reply = "A".repeat(limit + 50), maxNewTokens = limit).done()
        assertEquals(FinishReason.LENGTH, done.finishReason)
        return done.text
    }

    /** An answer stopped by its reader after [samples] tokens were sampled; the text the memory holds of it. */
    private suspend fun TestScope.answerStopped(test: TestEngine, samples: Int = 300): String {
        lateinit var collecting: Job
        var seen = 0
        test.fake.beforeCall = { call -> if (call is Call.SampleNext && ++seen == samples) collecting.cancel() }
        test.fake.enqueueReply("A".repeat(samples + 50))
        collecting = launch { test.engine.generate(ask(question)).collect { } }
        collecting.join()
        test.fake.beforeCall = {}
        // One character for each token of the answer in memory, which is what the reader was shown.
        return "A".repeat(test.fake.memory.size - tokens(test, listOf(question)).size)
    }

    private fun tokens(test: TestEngine, messages: List<PromptMessage>, continueFinal: Boolean = false): List<Int> =
        PromptTokeniser(test.fake).tokenise(Qwen35ChatMlFormat.render(PromptInput(messages, continueFinalMessage = continueFinal))).tokens.toList()

    /** Where the message after [history] begins: the position of the placeholder's boundary. */
    private fun nextMessageStart(test: TestEngine, history: List<PromptMessage>): Int {
        val prompt = Qwen35ChatMlFormat.render(PromptInput(history + user("")))
        val tokeniser = PromptTokeniser(test.fake)
        return checkNotNull(tokeniser.boundaryPosition(tokeniser.tokenise(prompt), prompt.boundaries.lastIndex - 1))
    }

    private suspend fun TestEngine.readAhead(vararg history: PromptMessage): ReadAheadResult = engine.readAhead(ask(*history))

    /** How many prompt tokens [events] read, and that none of them cleared the memory. */
    private fun TestEngine.readFor(events: List<GenerationEvent>): Int {
        assertTrue("a request cleared the memory", fake.callsOf<Call.ClearMemory>().isEmpty())
        return events.done().stats.evaluatedPromptTokens
    }

    private fun TestEngine.changesToMemory(): List<Call> =
        fake.calls.filter { it is Call.Evaluate || it is Call.RewindTo || it is Call.RestoreCheckpoint || it is Call.ClearMemory || it is Call.SaveCheckpoint || it is Call.DropCheckpoint }

    // --- What it is for ---

    /**
     * The point of the feature. Catches a read-ahead that reads the wrong form of the answer, or
     * stops short of where the next message begins: the message would read the answer again.
     */
    @Test
    fun `after a read-ahead a new message reads only itself and the opening of the reply`() = runTest {
        val without = engine()
        val answerA = without.answerEndedByItself()
        without.fake.clearCalls()
        val control = without.readFor(without.generate(question, answerA, user("Shorter.")))
        assertTrue("without a read-ahead the whole answer is read again ($control)", control > 300)

        val test = engine()
        val answer = test.answerEndedByItself()
        val history = listOf(question, answer)
        val start = nextMessageStart(test, history)
        val result = test.readAhead(*history.toTypedArray())
        assertTrue("$result", result is ReadAheadResult.Read)
        assertEquals("it reads up to where the next message begins", start, test.fake.memory.size)
        assertEquals("and keeps a checkpoint there", true, start in test.fake.checkpoints.values)
        test.fake.clearCalls()
        val next = history + user("Shorter.")
        val events = test.generate(*next.toTypedArray())
        assertEquals("the message reads only what comes after the read-ahead", tokens(test, next).size - start, test.readFor(events))
        assertTrue("nothing is restored or cut back", test.fake.calls.none { it is Call.RestoreCheckpoint || it is Call.RewindTo })
    }

    /**
     * Catches a read-ahead that drops the checkpoint in front of the answer: "Answer again"
     * would then read the whole question again, or clear the memory.
     */
    @Test
    fun `answer again after a read-ahead goes back through its own checkpoint as without one`() = runTest {
        val without = engine()
        without.answerEndedByItself()
        without.fake.clearCalls()
        val control = without.readFor(without.generate(question))

        val test = engine()
        val answer = test.answerEndedByItself()
        test.readAhead(question, answer)
        test.fake.clearCalls()
        val events = test.generate(question)
        assertEquals(control, test.readFor(events))
        assertEquals(1, test.fake.callsOf<Call.RestoreCheckpoint>().size)
    }

    /**
     * Stated and expected: a read-ahead replaces the end of the answer in memory with its history
     * form, so "Continue" reads the answer again. Without one, "Continue" at the length limit
     * reads one token, and after a stop no more than the checkpoints that follow the reply
     * allow. Catches a read-ahead that tries to keep the end-of-reply state as well, which the
     * fake refuses to restore over the other timeline.
     */
    @Test
    fun `continue reads the answer again after a read-ahead and little without one`() = runTest {
        val interval = LlamaEngine.Settings().followingCheckpointInterval

        val atLimit = engine()
        val cut = atLimit.answerCutAt()
        atLimit.fake.clearCalls()
        assertEquals(1, atLimit.readFor(atLimit.generate(question, assistant(PromptWhitespace.stripStart(cut)), continueFinal = true)))

        val stopped = engine()
        val partial = answerStopped(stopped)
        stopped.fake.clearCalls()
        val afterStop = stopped.readFor(stopped.generate(question, assistant(partial), continueFinal = true))
        assertTrue("continue after a stop reads $afterStop", afterStop <= 2 * interval)

        val test = engine()
        val text = test.answerCutAt()
        test.readAhead(question, assistant(PromptWhitespace.strip(text)))
        test.fake.clearCalls()
        val again = test.readFor(test.generate(question, assistant(PromptWhitespace.stripStart(text)), continueFinal = true))
        assertTrue("continue after a read-ahead reads the answer again ($again)", again >= 300)
    }

    /**
     * Catches a checkpoint at the read-ahead's end that stays one that follows a reply: the next
     * reply's own following checkpoints would push it out, and editing the message after it
     * would read the whole answer again.
     */
    @Test
    fun `the checkpoint where the next message begins serves an edit of that message after a long reply`() = runTest {
        val test = engine()
        val answer = test.answerEndedByItself()
        val history = listOf(question, answer)
        val start = nextMessageStart(test, history)
        test.readAhead(*history.toTypedArray())
        test.generate(*(history + user("Shorter.")).toTypedArray(), reply = "B".repeat(200))
        assertTrue("the checkpoint survives the reply's own", start in test.fake.checkpoints.values)
        test.fake.clearCalls()
        val edited = history + user("Longer.")
        val events = test.generate(*edited.toTypedArray())
        assertEquals(tokens(test, edited).size - start, test.readFor(events))
    }

    // --- It does only what the next request would do ---

    /**
     * Catches a read-ahead that does anything at all for a model whose next request keeps the
     * whole memory (Boris-2: plain-text ChatML, which writes an answer the same way before and
     * after): no evaluation, no checkpoint, nothing cut or restored.
     */
    @Test
    fun `nothing happens for a model without the difference`() = runTest {
        val plain = testModel(contextLength = 2000).copy(readingTimes = ReadingTimes.flat(10.0), cutsMemoryBack = false)
        val test = engine(spec = plain)
        val text = test.generate(question, reply = "Fine.$IM_END and more").done().text
        val held = test.fake.memory.toList()
        val history = listOf(question, assistant(PromptWhitespace.strip(text)))
        assertTrue(PlainChatMlFormat.render(PromptInput(history + user(""))).text().startsWith(test.fake.render(held)))
        test.fake.clearCalls()
        assertEquals(ReadAheadResult.Skipped(ReadAheadResult.Skipped.Reason.NOTHING_TO_READ), test.readAhead(*history.toTypedArray()))
        assertEquals(emptyList<Call>(), test.changesToMemory())
        assertEquals(held, test.fake.memory)
    }

    /** Catches a second read-ahead that does the work again. */
    @Test
    fun `a second read-ahead of the same conversation does nothing`() = runTest {
        val test = engine()
        val answer = test.answerEndedByItself()
        test.readAhead(question, answer)
        test.fake.clearCalls()
        assertEquals(ReadAheadResult.Skipped(ReadAheadResult.Skipped.Reason.NOTHING_TO_READ), test.readAhead(question, answer))
        assertEquals(emptyList<Call>(), test.changesToMemory())
    }

    /**
     * Catches a read-ahead that clears the memory where it has nothing to go back to: with no
     * checkpoints at all, and when the conversation differs before the first checkpoint (another
     * system prompt). Either is the next request's decision.
     */
    @Test
    fun `it never clears the memory`() = runTest {
        val noCheckpoints = engine(settings = LlamaEngine.Settings(maxCheckpoints = 0, followingCheckpointInterval = 0))
        val answer = noCheckpoints.answerEndedByItself()
        val held = noCheckpoints.fake.memory.toList()
        noCheckpoints.fake.clearCalls()
        assertEquals(ReadAheadResult.Skipped(ReadAheadResult.Skipped.Reason.WOULD_CLEAR), noCheckpoints.readAhead(question, answer))
        assertTrue(noCheckpoints.fake.calls.none { it is Call.ClearMemory || it is Call.Evaluate })
        assertEquals(held, noCheckpoints.fake.memory)

        val otherSystem = engine()
        val again = otherSystem.answerEndedByItself()
        val before = otherSystem.fake.memory.toList()
        otherSystem.fake.clearCalls()
        val result = otherSystem.engine.readAhead(ask(question, again).copy(systemPrompt = "Be brief."))
        assertEquals(ReadAheadResult.Skipped(ReadAheadResult.Skipped.Reason.WOULD_CLEAR), result)
        assertTrue(otherSystem.fake.calls.none { it is Call.ClearMemory || it is Call.Evaluate })
        assertEquals(before, otherSystem.fake.memory)
    }

    /**
     * Catches a read-ahead that grows the context, or reads a conversation the next request
     * would have to shorten: when the conversation and the room for its reply do not fit the
     * context as it is, nothing is done.
     */
    @Test
    fun `it never grows the context and never leaves turns out`() = runTest {
        // A context of 600 tokens, which cannot grow: the conversation of about 370 tokens and a
        // reply of up to 300 (half the context) do not fit, so the next request would leave the
        // first turn out.
        val small = engine(spec = qwen.copy(contextLength = 600, maxContextLength = 600, defaultMaxNewTokens = 300))
        val answer = small.answerEndedByItself()
        val held = small.fake.memory.toList()
        small.fake.clearCalls()
        assertEquals(ReadAheadResult.Skipped(ReadAheadResult.Skipped.Reason.NO_ROOM), small.readAhead(question, answer))
        assertTrue(small.fake.resizes.isEmpty())
        assertEquals(emptyList<Call>(), small.changesToMemory())
        assertEquals(held, small.fake.memory)

        // A size the user chose below the context as it is counts at once, as it does for a request.
        val test = engine()
        val again = test.answerEndedByItself()
        val before = test.fake.memory.toList()
        test.fake.clearCalls()
        val chosen = test.engine.readAhead(ask(question, again).copy(maxContextTokens = 600))
        assertEquals(ReadAheadResult.Skipped(ReadAheadResult.Skipped.Reason.NO_ROOM), chosen)
        assertTrue(test.fake.resizes.isEmpty())
        assertEquals(before, test.fake.memory)
    }

    /** Catches a read-ahead that works on a memory it was not asked about. */
    @Test
    fun `it does nothing for another conversation, a model that continues text, or a continuation`() = runTest {
        val test = engine()
        val answer = test.answerEndedByItself()
        test.fake.clearCalls()
        assertEquals(
            ReadAheadResult.Skipped(ReadAheadResult.Skipped.Reason.OTHER_CONVERSATION),
            test.engine.readAhead(ask(question, answer).copy(conversationKey = "another")),
        )
        assertEquals(
            ReadAheadResult.Skipped(ReadAheadResult.Skipped.Reason.INVALID),
            test.engine.readAhead(ask(question, answer, continueFinal = true)),
        )
        assertEquals(emptyList<Call>(), test.changesToMemory())

        val completion = engine(spec = testModel(family = ModelFamily.COMPLETION).copy(cutsMemoryBack = false))
        completion.generate(user("Once upon a time"), reply = " there was")
        assertEquals(
            ReadAheadResult.Skipped(ReadAheadResult.Skipped.Reason.NOT_A_CHAT),
            completion.readAhead(user("Once upon a time"), assistant("there was")),
        )
    }

    // --- It gives way ---

    /**
     * Runs a read-ahead of [history] and calls [interrupt] after the first of its chunks that
     * leaves at least [from] tokens in memory (inside the native evaluation, as another thread
     * would). Returns the result, or null when the read-ahead's caller was cancelled.
     */
    private suspend fun TestScope.interrupted(test: TestEngine, history: List<PromptMessage>, from: Int = 0, interrupt: () -> Unit): ReadAheadResult? {
        var fired = false
        test.fake.afterChunk = { held ->
            if (!fired && held >= from) {
                fired = true
                interrupt()
            }
        }
        var result: ReadAheadResult? = null
        val job = launch { result = test.engine.readAhead(ask(*history.toTypedArray())) }
        job.join()
        test.fake.afterChunk = {}
        return result
    }

    /**
     * Catches a read-ahead that holds the engine for more than a chunk after a real request was
     * asked for, and a request that then does not find what the read-ahead read as its
     * beginning.
     */
    @Test
    fun `a request stops it within a chunk and keeps what it read`() = runTest {
        val test = engine()
        val history = listOf(question, test.answerEndedByItself())
        val next = history + user("Shorter.")
        var events: List<GenerationEvent>? = null
        var heldWhenAsked = 0
        val result = interrupted(test, history) {
            heldWhenAsked = test.fake.memory.size
            test.fake.enqueueReply("Fine.")
            launch(start = CoroutineStart.UNDISPATCHED) { events = test.engine.generate(ask(*next.toTypedArray())).toList() }
        }
        assertTrue("$result", result is ReadAheadResult.Stopped)
        val stopped = result as ReadAheadResult.Stopped
        assertEquals("it stopped at the end of the chunk it was in", heldWhenAsked, stopped.keptTokens + stopped.readTokens)
        runCurrent()
        val done = checkNotNull(events).done()
        assertEquals("the request keeps what the read-ahead read", heldWhenAsked, done.stats.reusedPromptTokens)
        assertTrue(test.fake.callsOf<Call.ClearMemory>().isEmpty())
    }

    /** Catches a read-ahead that goes on after the lock's forgetting, an unload or another model's load. */
    @Test
    fun `forgetting, unloading and loading stop it`() = runTest {
        for (what in listOf("forget", "unload", "load")) {
            val test = engine()
            val history = listOf(question, test.answerEndedByItself())
            var heldWhenAsked = 0
            val result = interrupted(test, history) {
                heldWhenAsked = test.fake.memory.size
                launch(start = CoroutineStart.UNDISPATCHED) {
                    when (what) {
                        "forget" -> test.engine.forget()
                        "unload" -> test.engine.unload()
                        else -> test.engine.load(testModel(id = "another"))
                    }
                }
            }
            assertTrue("$what: $result", result is ReadAheadResult.Stopped)
            assertEquals(what, heldWhenAsked, (result as ReadAheadResult.Stopped).keptTokens + result.readTokens)
            runCurrent()
        }
    }

    /**
     * Catches a read-ahead that waits at a closed gate, holding the engine, instead of ending,
     * and one that begins while it is closed. The read-ahead asked for once the gate is open
     * again reads on from where the stopped one ended.
     */
    @Test
    fun `a closed gate stops it, and it does not begin while the gate is closed`() = runTest {
        val gate = GenerationGate()
        val test = engine(gate = gate)
        val history = listOf(question, test.answerEndedByItself())
        gate.close()
        test.fake.clearCalls()
        assertEquals(ReadAheadResult.Skipped(ReadAheadResult.Skipped.Reason.BUSY), test.readAhead(*history.toTypedArray()))
        assertEquals(emptyList<Call>(), test.changesToMemory())
        gate.open()
        val result = interrupted(test, history) { gate.close() }
        assertTrue("$result", result is ReadAheadResult.Stopped)
        val held = test.fake.memory.size
        gate.open()
        assertEquals(ReadAheadResult.Read(held, nextMessageStart(test, history) - held), test.readAhead(*history.toTypedArray()))
    }

    /** Catches a read-ahead that does not stop for its caller, or leaves the engine's record of the memory behind the native one. */
    @Test
    fun `cancelling its caller stops it and leaves a consistent memory`() = runTest {
        val test = engine()
        val history = listOf(question, test.answerEndedByItself())
        lateinit var job: Job
        var chunks = 0
        test.fake.afterChunk = { if (++chunks == 1) job.cancel() }
        job = launch { test.engine.readAhead(ask(*history.toTypedArray())) }
        job.join()
        test.fake.afterChunk = {}
        assertTrue(job.isCancelled)
        val held = test.fake.memory.size
        test.fake.clearCalls()
        val events = test.generate(*(history + user("Shorter.")).toTypedArray())
        assertEquals(held, events.done().stats.reusedPromptTokens)
    }

    /**
     * Catches the save on leaving the screen answered BUSY because a read-ahead holds the
     * engine, and the lock's save waiting behind the whole read-ahead. The save writes what the
     * read-ahead had read.
     */
    @Test
    fun `a save of either kind stops it and is made`() = runTest {
        for (trigger in SaveTrigger.entries) {
            val test = engine()
            val history = listOf(question, test.answerEndedByItself())
            var saved: MemorySaveResult? = null
            var heldWhenAsked = 0
            // Late enough for the memory to be worth saving.
            val result = interrupted(test, history, from = 250) {
                heldWhenAsked = test.fake.memory.size
                launch(start = CoroutineStart.UNDISPATCHED) { saved = test.engine.saveMemory(vault, KEY, trigger) }
            }
            runCurrent()
            assertTrue("$trigger: $result", result is ReadAheadResult.Stopped)
            assertTrue("$trigger: $saved", saved is MemorySaveResult.Saved)
            assertEquals("$trigger", heldWhenAsked, (saved as MemorySaveResult.Saved).tokens)
            assertEquals("$trigger", test.fake.memory.subList(0, heldWhenAsked), vault.note)
        }
    }

    // --- Its interaction with a saved memory ---

    /**
     * Going back below a saved image deletes it, as every going back does (the image must be the
     * memory's beginning); a save after the read-ahead holds the history form, and after a lock
     * and a restore the next message reads only itself.
     */
    @Test
    fun `a saved image is deleted when it goes below it, and one saved after it serves the next unlock`() = runTest {
        val test = engine()
        val answer = test.answerEndedByItself()
        val history = listOf(question, answer)
        assertTrue(test.engine.saveMemory(vault, KEY, SaveTrigger.LOCK) is MemorySaveResult.Saved)
        assertNotNull(vault.image)
        test.readAhead(*history.toTypedArray())
        assertNull("the image of the answer as it was written is gone", vault.image)

        val start = nextMessageStart(test, history)
        val saved = test.engine.saveMemory(vault, KEY, SaveTrigger.LOCK)
        assertEquals("the image holds the conversation up to the next message", start, (saved as MemorySaveResult.Saved).tokens)
        test.engine.forgetKeepingSavedMemory()
        assertEquals(MemoryRestoreResult.Restored(start), test.engine.restoreMemory(vault, KEY))
        test.fake.clearCalls()
        val next = history + user("Shorter.")
        val events = test.generate(*next.toTypedArray())
        assertEquals(tokens(test, next).size - start, test.readFor(events))
    }

    // --- Stops, overlaps, failures and what a request would do on the way ---

    /**
     * A native layer that does [after] each time it has been told to stop, before it returns:
     * another thread can act between the native side's last look and the engine's next one.
     */
    private class ActsAfterStop(private val delegate: FakeNativeLlama, private val after: () -> Unit) : NativeLlama by delegate {
        override fun evaluate(tokens: IntArray, chunkSize: Int, observer: EvaluationObserver): Int =
            delegate.evaluate(
                tokens,
                chunkSize,
                object : EvaluationObserver {
                    override fun onProgress(evaluatedTokens: Int) = observer.onProgress(evaluatedTokens)

                    override fun isCancelled(): Boolean = observer.isCancelled().also { if (it) after() }
                },
            )
    }

    /**
     * Catches a stop whose reason went away again (the gate opened again before the native side
     * returned) taken for a failure of the native side: the memory would be emptied, the saved
     * image deleted, and the next message would read the whole conversation.
     */
    @Test
    fun `a reason to stop that goes away again is still a stop, and clears nothing`() = runTest {
        val gate = GenerationGate()
        val fake = FakeNativeLlama(contextTokens = qwen.contextLength).also {
            it.registerChatMl()
            it.refuseRewind = true
        }
        val engine = LlamaEngine(
            factory = NativeLlamaFactory { _, _, _, _ -> ActsAfterStop(fake) { gate.open() } },
            nativeDispatcher = StandardTestDispatcher(testScheduler),
            gate = gate,
        )
        engine.load(qwen)
        vault.onCommit = { fake.memory.toList() }
        fake.enqueueReply("A".repeat(300))
        val answer = assistant(PromptWhitespace.strip(engine.generate(ask(question)).toList().done().text))
        assertTrue(engine.saveMemory(vault, KEY, SaveTrigger.LOCK) is MemorySaveResult.Saved)
        var closed = false
        fake.afterChunk = {
            if (!closed) {
                closed = true
                gate.close()
            }
        }
        val result = engine.readAhead(ask(question, answer))
        fake.afterChunk = {}
        assertTrue("$result", result is ReadAheadResult.Stopped)
        val stopped = result as ReadAheadResult.Stopped
        assertEquals("the memory is as far as it had read", stopped.keptTokens + stopped.readTokens, fake.memory.size)
        assertTrue("nothing was cleared", fake.calls.none { it is Call.ClearMemory })
        fake.clearCalls()
        fake.enqueueReply("Fine.")
        val events = engine.generate(ask(question, answer, user("Shorter."))).toList()
        assertEquals("the next message keeps what was read", stopped.keptTokens + stopped.readTokens, events.done().stats.reusedPromptTokens)
        assertTrue(fake.calls.none { it is Call.ClearMemory })
    }

    /**
     * Catches a second read-ahead that throws while the first holds the engine (both held the
     * mutex as the same owner), and one that does not take up what the first left: the second
     * waits for the first, stopped by its caller, to let go, and then reads on from there.
     */
    @Test
    fun `a second read-ahead waits for the first to let go and then does what is left`() = runTest {
        val test = engine()
        val history = listOf(question, test.answerEndedByItself())
        val start = nextMessageStart(test, history)
        lateinit var first: Job
        var second: Any? = null
        var heldAtStop = 0
        var chunks = 0
        test.fake.afterChunk = { held ->
            if (++chunks == 1) {
                heldAtStop = held
                first.cancel()
                launch(start = CoroutineStart.UNDISPATCHED) {
                    second = try {
                        test.engine.readAhead(ask(*history.toTypedArray()))
                    } catch (e: IllegalStateException) {
                        e
                    }
                }
                assertNull("it waits for the first", second)
            }
        }
        first = launch { test.engine.readAhead(ask(*history.toTypedArray())) }
        first.join()
        runCurrent()
        test.fake.afterChunk = {}
        assertEquals(ReadAheadResult.Read(heldAtStop, start - heldAtStop), second)
        assertEquals(start, test.fake.memory.size)
    }

    /**
     * Catches a read-ahead stopped part-way (by the save on leaving the screen) that the next
     * one does not take up: the next message would read the rest of the answer again. Also that
     * a request in between, which changes the memory, ends that.
     */
    @Test
    fun `a stopped read-ahead is taken up by the next one, up to where the next message begins`() = runTest {
        val test = engine()
        val history = listOf(question, test.answerEndedByItself())
        val start = nextMessageStart(test, history)
        var saved: MemorySaveResult? = null
        val result = interrupted(test, history) {
            launch(start = CoroutineStart.UNDISPATCHED) { saved = test.engine.saveMemory(vault, KEY, SaveTrigger.LEFT_SCREEN) }
        }
        runCurrent()
        assertTrue("$result", result is ReadAheadResult.Stopped)
        assertNotNull(saved)
        val held = test.fake.memory.size
        assertTrue(held < start)
        test.fake.clearCalls()
        assertEquals(ReadAheadResult.Read(held, start - held), test.readAhead(*history.toTypedArray()))
        assertTrue("it only reads on", test.fake.calls.none { it is Call.RestoreCheckpoint || it is Call.RewindTo || it is Call.ClearMemory })
        assertTrue("and keeps a checkpoint where the next message begins", start in test.fake.checkpoints.values)
        test.fake.clearCalls()
        val next = history + user("Shorter.")
        assertEquals(tokens(test, next).size - start, test.readFor(test.generate(*next.toTypedArray())))
    }

    /**
     * Catches the checkpoint at a read-ahead's end taken as one of the prompt's four instead of
     * one that follows a reply: after three turns, each read ahead, it would push out the
     * checkpoint where the second question begins, and an edit of that question would read the
     * whole conversation again after clearing the memory.
     */
    @Test
    fun `after three read-ahead turns an edit of the second-last question goes back to where it begins`() = runTest {
        val test = engine()
        val q1 = user("One?")
        val a1 = assistant(PromptWhitespace.strip(test.generate(q1, reply = "A".repeat(100)).done().text))
        test.readAhead(q1, a1)
        val q2 = user("Two?")
        val a2 = assistant(PromptWhitespace.strip(test.generate(q1, a1, q2, reply = "B".repeat(100)).done().text))
        test.readAhead(q1, a1, q2, a2)
        val q3 = user("Three?")
        val a3 = assistant(PromptWhitespace.strip(test.generate(q1, a1, q2, a2, q3, reply = "C".repeat(100)).done().text))
        assertTrue(test.readAhead(q1, a1, q2, a2, q3, a3) is ReadAheadResult.Read)
        test.fake.clearCalls()
        val edited = listOf(q1, a1, user("Two, edited?"))
        val events = test.generate(*edited.toTypedArray())
        assertEquals(tokens(test, edited).size - nextMessageStart(test, listOf(q1, a1)), test.readFor(events))
    }

    /**
     * Catches a read-ahead that begins although something else has asked for the engine and
     * waits for its turn: it must answer that the engine is busy at once, not after the
     * read-ahead under way has let go (or, without the second look, after the request too).
     */
    @Test
    fun `one asked for while a request waits for the engine answers busy at once`() = runTest {
        val test = engine()
        val history = listOf(question, test.answerEndedByItself())
        var second: ReadAheadResult? = null
        var answeredAtOnce = false
        var chunks = 0
        test.fake.afterChunk = {
            if (++chunks == 1) {
                test.fake.enqueueReply("Fine.")
                launch(start = CoroutineStart.UNDISPATCHED) { test.engine.generate(ask(*(history + user("Shorter.")).toTypedArray())).toList() }
                launch(start = CoroutineStart.UNDISPATCHED) { second = test.engine.readAhead(ask(*history.toTypedArray())) }
                answeredAtOnce = second != null
            }
        }
        test.readAhead(*history.toTypedArray())
        test.fake.afterChunk = {}
        runCurrent()
        assertTrue("answered before the first let go", answeredAtOnce)
        assertEquals(ReadAheadResult.Skipped(ReadAheadResult.Skipped.Reason.BUSY), second)
    }

    /**
     * Catches a read-ahead that waited for the engine and then runs although a request had the
     * engine meanwhile: it would take the memory back from the request's reply to the answer
     * before it.
     */
    @Test
    fun `one that waited while a request had the engine does nothing`() = runTest {
        val test = engine()
        val history = listOf(question, test.answerEndedByItself())
        val next = history + user("Shorter.")
        lateinit var first: Job
        var second: ReadAheadResult? = null
        var chunks = 0
        test.fake.afterChunk = {
            if (++chunks == 1) {
                // The first is stopped by its caller; the second waits for it; a request comes after both.
                first.cancel()
                launch(start = CoroutineStart.UNDISPATCHED) { second = test.engine.readAhead(ask(*history.toTypedArray())) }
                test.fake.enqueueReply("Fine.")
                launch(start = CoroutineStart.UNDISPATCHED) { test.engine.generate(ask(*next.toTypedArray())).toList() }
            }
        }
        first = launch { test.engine.readAhead(ask(*history.toTypedArray())) }
        first.join()
        test.fake.afterChunk = {}
        runCurrent()
        assertEquals(ReadAheadResult.Skipped(ReadAheadResult.Skipped.Reason.BUSY), second)
        val prompt = tokens(test, next)
        assertTrue("the request's reply stays", test.fake.memory.size > prompt.size)
        assertEquals(prompt, test.fake.memory.subList(0, prompt.size))
    }

    /**
     * Catches a failure of the native side after the read-ahead went back that leaves the memory
     * as it is (it can no longer be trusted), one reported as [ReadAheadResult.Skipped] (which
     * says the memory is as it was), and a failure before anything was changed that empties it.
     */
    @Test
    fun `a failure after the memory was changed empties it and says so, one before changes nothing`() = runTest {
        val test = engine()
        val history = listOf(question, test.answerEndedByItself())
        val held = test.fake.memory.toList()
        test.fake.clearCalls()
        test.fake.beforeCall = { if (it is Call.Tokenize) throw NativeLlamaException(NativeLlamaException.EVALUATION_FAILED, "failed") }
        assertEquals(ReadAheadResult.Skipped(ReadAheadResult.Skipped.Reason.FAILED), test.readAhead(*history.toTypedArray()))
        test.fake.beforeCall = {}
        assertEquals(emptyList<Call>(), test.changesToMemory())
        assertEquals(held, test.fake.memory)

        test.fake.beforeCall = { if (it is Call.Evaluate) throw NativeLlamaException(NativeLlamaException.EVALUATION_FAILED, "failed") }
        assertEquals(ReadAheadResult.Failed, test.readAhead(*history.toTypedArray()))
        test.fake.beforeCall = {}
        assertEquals(1, test.fake.callsOf<Call.ClearMemory>().size)
        assertEquals(0, test.fake.memory.size)
    }

    /**
     * Catches a read-ahead that lifts a mark of [LlamaEngine.markNotToBeSaved] when a failure
     * empties the memory: only a request made after the mark, forgetting, a load or an unload
     * may, so a saved memory must still not be restored afterwards.
     */
    @Test
    fun `a failed read-ahead lifts no mark`() = runTest {
        // A saved memory of the same conversation, made by another engine.
        val elsewhere = engine()
        val history = listOf(question, elsewhere.answerEndedByItself())
        val otherVault = FakeMemoryVault().also { v -> v.onCommit = { elsewhere.fake.memory.toList() } }
        assertTrue(elsewhere.engine.saveMemory(otherVault, KEY, SaveTrigger.LOCK) is MemorySaveResult.Saved)

        val test = engine()
        test.answerEndedByItself()
        test.engine.markNotToBeSaved()
        // A request made after the mark, which keeps the memory: the mark stays.
        val next = history + user("Shorter.")
        val answer = assistant(PromptWhitespace.strip(test.generate(*next.toTypedArray()).done().text))
        test.fake.beforeCall = { if (it is Call.Evaluate) throw NativeLlamaException(NativeLlamaException.EVALUATION_FAILED, "failed") }
        assertEquals(ReadAheadResult.Failed, test.readAhead(*(next + answer).toTypedArray()))
        test.fake.beforeCall = {}
        assertEquals(MemoryRestoreResult.NotRestored(MemoryRestoreResult.NotRestored.Reason.MARKED), test.engine.restoreMemory(otherVault, KEY))
    }

    /**
     * Holds the chunk sizes, which decide how long whatever stops a read-ahead waits: 32 tokens
     * while the part read leaves less than 1,024 in memory, the model's deep chunk (128 here, as
     * for Qwen3.5) once it leaves that many or more.
     */
    @Test
    fun `it reads in small chunks below the deep-read depth and in the deep chunk beyond it`() = runTest {
        val settings = LlamaEngine.Settings()
        for ((length, chunk) in listOf(300 to settings.evaluationChunk, 1200 to qwen.deepReadChunk)) {
            val test = engine()
            val history = listOf(question, test.answerEndedByItself(length))
            test.fake.clearCalls()
            assertTrue(test.readAhead(*history.toTypedArray()) is ReadAheadResult.Read)
            val evaluations = test.fake.evaluations
            assertTrue("$length", evaluations.isNotEmpty())
            for (evaluation in evaluations) {
                assertEquals("$length: $evaluation", if (evaluation.to >= settings.deepReadTokens) qwen.deepReadChunk else settings.evaluationChunk, evaluation.chunkSize)
            }
            assertTrue("$length", evaluations.any { it.chunkSize == chunk })
        }
    }

    /** Catches a read-ahead for another model than the loaded one, and one over a memory whose native length is not the engine's record. */
    @Test
    fun `it does nothing for another model or when the native memory is not what the engine knows`() = runTest {
        val test = engine()
        val history = listOf(question, test.answerEndedByItself())
        val held = test.fake.memory.toList()
        test.fake.clearCalls()
        assertEquals(
            ReadAheadResult.Skipped(ReadAheadResult.Skipped.Reason.NOT_LOADED),
            test.engine.readAhead(ask(*history.toTypedArray()).copy(modelId = "another")),
        )
        assertEquals(emptyList<Call>(), test.changesToMemory())

        // One token more natively than the engine knows of.
        test.fake.evaluate(
            intArrayOf(held.last()),
            1,
            object : EvaluationObserver {
                override fun onProgress(evaluatedTokens: Int) = Unit

                override fun isCancelled(): Boolean = false
            },
        )
        test.fake.clearCalls()
        assertEquals(ReadAheadResult.Skipped(ReadAheadResult.Skipped.Reason.OTHER_CONVERSATION), test.readAhead(*history.toTypedArray()))
        assertEquals(emptyList<Call>(), test.changesToMemory())
    }

    /**
     * Catches a read-ahead that takes no checkpoints at the boundaries it reads past, as a
     * request would: one that goes back to an earlier question (here, one edited in the
     * conversation it is given) must leave a checkpoint where the next question begins, so that
     * an edit of that one does not read from the earlier one again.
     */
    @Test
    fun `it keeps checkpoints at the boundaries it reads past`() = runTest {
        val test = engine()
        val q1 = user("One?")
        val a1 = assistant(PromptWhitespace.strip(test.generate(q1, reply = "A".repeat(100)).done().text))
        val q2 = user("Two?")
        val a2 = assistant(PromptWhitespace.strip(test.generate(q1, a1, q2, reply = "B".repeat(100)).done().text))
        val changed = listOf(q1, assistant(a1.content.dropLast(10)), q2, a2)
        assertTrue(test.readAhead(*changed.toTypedArray()) is ReadAheadResult.Read)
        test.fake.clearCalls()
        val edited = listOf(q1, assistant(a1.content.dropLast(10)), user("Two, edited?"))
        val events = test.generate(*edited.toTypedArray())
        assertEquals(tokens(test, edited).size - nextMessageStart(test, edited.dropLast(1)), test.readFor(events))
    }

    private companion object {
        const val KEY = "conversation"
    }
}
