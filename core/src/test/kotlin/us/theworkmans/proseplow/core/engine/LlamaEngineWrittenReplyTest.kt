package us.theworkmans.proseplow.core.engine

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import us.theworkmans.proseplow.core.engine.FakeNativeLlama.Call
import us.theworkmans.proseplow.core.model.ModelFamily
import us.theworkmans.proseplow.core.model.ModelSpec
import us.theworkmans.proseplow.core.model.ReadingTimes
import us.theworkmans.proseplow.core.prompt.PromptInput
import us.theworkmans.proseplow.core.prompt.PromptMessage
import us.theworkmans.proseplow.core.prompt.PromptWhitespace

/**
 * The tokens a model wrote for an answer, put back when a later prompt carries that answer
 * ([WrittenReply], [PromptTokeniser]).
 *
 * The fake's tokenizer is like a BPE one where it matters: text has more than one encoding. It
 * encodes text one token per byte, and the model here samples a piece, one token that stands
 * for the two characters `Zz`, which the text encoded again spells with two. Without the
 * written replies every request that carries the answer differs from the memory at the piece,
 * and a model that cannot cut its memory back reads the answer again from a checkpoint before
 * it. The answers are long enough (300 tokens, the piece 200 tokens in) that the checkpoints
 * that follow a reply all lie beyond the piece.
 */
class LlamaEngineWrittenReplyTest {

    /** Like Qwen3.5 where it matters: its prompt format, and no cutting back of its memory. */
    private val qwen: ModelSpec = testModel(family = ModelFamily.QWEN35_CHATML, contextLength = 4000, maxNewTokens = 1000)
        .copy(readingTimes = ReadingTimes.flat(10.0), cutsMemoryBack = false)

    /** Like Boris-2 where it matters: the whole conversation one plain text, `<|im_end|>` a stop string, no cutting back. */
    private val boris: ModelSpec = testModel(family = ModelFamily.PLAIN_CHATML, contextLength = 4000, maxNewTokens = 1000)
        .copy(readingTimes = ReadingTimes.flat(10.0), cutsMemoryBack = false)

    /** A model with attention only, which cuts its memory back to any length. */
    private val attention: ModelSpec = qwen.copy(cutsMemoryBack = true)

    private val question = user("Tell me a long story.")

    private val vault = FakeMemoryVault()

    private suspend fun TestScope.engine(spec: ModelSpec = qwen): TestEngine =
        loadedEngine(spec, configure = { if (!spec.cutsMemoryBack) it.refuseRewind = true })

    private fun ask(vararg messages: PromptMessage, continueFinal: Boolean = false, maxNewTokens: Int = 1000, key: String? = KEY): GenerationRequest =
        request(*messages, continueFinalMessage = continueFinal, maxNewTokens = maxNewTokens).copy(conversationKey = key)

    /** Queues 200 `A`, the piece `Zz`, then [bees] `B` and [after]: the piece where the text encoded again has `Z` and `z`. */
    private fun TestEngine.enqueueAnswer(bees: Int = 150, after: String = "") {
        fake.enqueueReply(fake.textTokens("A".repeat(200)) + fake.addPiece("Zz") + fake.textTokens("B".repeat(bees) + after))
    }

    /**
     * An answer stopped by its reader: the collector is cancelled at the 300th token sampled,
     * which is then given out but not evaluated. Returns the text the reader was shown, which
     * is that token's too, as on the phone (the "Continue" prompt one token longer than the memory).
     */
    private suspend fun TestScope.answerStopped(test: TestEngine, key: String? = KEY): String {
        lateinit var collecting: Job
        var samples = 0
        test.fake.beforeCall = { call -> if (call is Call.SampleNext && ++samples == 300) collecting.cancel() }
        test.enqueueAnswer()
        collecting = launch { test.engine.generate(ask(question, key = key)).collect { } }
        collecting.join()
        test.fake.beforeCall = {}
        return "A".repeat(200) + "Zz" + "B".repeat(99)
    }

    /** "Continue" of [text], with one token to write; its statistics. */
    private suspend fun TestEngine.continueWith(text: String, key: String? = KEY): GenerationStats {
        fake.enqueueReply("C")
        fake.clearCalls()
        val events = engine.generate(ask(question, assistant(PromptWhitespace.stripStart(text)), continueFinal = true, maxNewTokens = 1, key = key)).toList()
        return events.done().stats
    }

    /** How many tokens the prompt of [messages] has, encoded throughout: where the reply to them begins in the memory. */
    private fun TestEngine.promptLength(vararg messages: PromptMessage = arrayOf(question)): Int =
        PromptTokeniser(fake).tokenise(qwen.promptFormat.render(PromptInput(messages.toList()))).tokens.size

    // --- "Continue" ---

    /**
     * The measured case: "Continue" after an answer was stopped. Catches the answer encoded
     * again, which differs from the memory at the piece, 100 tokens before the end, and makes the
     * model read the whole answer again from the checkpoint in front of it.
     */
    @Test
    fun `continue after a stopped answer reads only the token the memory does not have`() = runTest {
        for (spec in listOf(qwen, attention)) {
            val test = engine(spec)
            val shown = answerStopped(test)
            val memory = test.fake.memory.size
            val stats = test.continueWith(shown)
            assertEquals("${spec.cutsMemoryBack}: read", 1, stats.evaluatedPromptTokens)
            assertEquals("${spec.cutsMemoryBack}: the prompt is the memory and the token it lacks", memory + 1, stats.promptTokens)
            assertTrue(test.fake.callsOf<Call.RestoreCheckpoint>().isEmpty() && test.fake.callsOf<Call.ClearMemory>().isEmpty())
        }
    }

    /** Catches: the last token of an answer cut off at its length, which is sampled but never evaluated, left out of the written reply. */
    @Test
    fun `continue after an answer cut off at its length reads one token`() = runTest {
        val test = engine()
        test.enqueueAnswer()
        val done = test.engine.generate(ask(question, maxNewTokens = 250)).toList().done()
        assertEquals(FinishReason.LENGTH, done.finishReason)
        val memory = test.fake.memory.size
        val stats = test.continueWith(done.text)
        assertEquals("read", 1, stats.evaluatedPromptTokens)
        assertEquals(memory + 1, stats.promptTokens)
    }

    /** Catches: a continued answer kept as only what was written after "Continue", which the next "Continue" then reads again. */
    @Test
    fun `an answer continued twice is still spelled as the model wrote it`() = runTest {
        val test = engine()
        val shown = answerStopped(test)
        test.fake.enqueueReply(listOf(test.fake.addPiece("Zz")) + test.fake.textTokens("D".repeat(20)))
        val first = test.engine.generate(ask(question, assistant(shown), continueFinal = true, maxNewTokens = 15)).toList().done()
        assertEquals(FinishReason.LENGTH, first.finishReason)
        val memory = test.fake.memory.size
        val stats = test.continueWith(shown + first.text)
        assertEquals("read", 1, stats.evaluatedPromptTokens)
        assertEquals(memory + 1, stats.promptTokens)
    }

    // --- History ---

    /**
     * A model whose history form of an answer is the form it wrote it in (Boris-2's plain-text
     * ChatML). Catches the answer encoded again in the history: the read-ahead would go back
     * into the answer and read it again; with the written reply the memory is already the
     * beginning of the next prompt, and the next message reads only itself.
     */
    @Test
    fun `a follow-up after the read-ahead reads only the new message`() = runTest {
        val test = engine(boris)
        test.enqueueAnswer(bees = 100, after = IM_END)
        val done = test.engine.generate(ask(question)).toList().done()
        assertEquals(FinishReason.STOP_STRING, done.finishReason)
        val history = arrayOf(question, assistant(PromptWhitespace.strip(done.text)))
        val memoryAfterAnswer = test.fake.memory.toList()
        test.fake.clearCalls()
        assertEquals(ReadAheadResult.Skipped(ReadAheadResult.Skipped.Reason.NOTHING_TO_READ), test.engine.readAhead(ask(*history)))
        assertTrue("the read-ahead changed nothing", test.fake.calls.none { it is Call.Evaluate || it is Call.RestoreCheckpoint || it is Call.RewindTo })

        test.fake.enqueueReply("Fine.")
        val stats = test.engine.generate(ask(*history, user("Thanks."))).toList().done().stats
        assertEquals("the whole memory kept", memoryAfterAnswer.size, stats.reusedPromptTokens)
        assertTrue(test.fake.callsOf<Call.RestoreCheckpoint>().isEmpty())
    }

    /**
     * Qwen3.5 writes an answer in history without the think block it was written after, so its
     * read-ahead reads the answer again in any case. Guards the turns after that: the read-ahead
     * spells the answer with the model's tokens, so every later prompt must spell it the same
     * way, also once another answer has been written since; otherwise the read-ahead after the
     * second answer would differ from the memory inside the first and read both again.
     */
    @Test
    fun `later read-aheads and follow-ups read only the latest turn`() = runTest {
        val test = engine()
        test.enqueueAnswer()
        val first = assistant(PromptWhitespace.strip(test.engine.generate(ask(question)).toList().done().text))
        assertTrue(test.engine.readAhead(ask(question, first)) is ReadAheadResult.Read)
        test.enqueueAnswer(bees = 80)
        val second = assistant(PromptWhitespace.strip(test.engine.generate(ask(question, first, user("More."))).toList().done().text))
        val beforeSecondTurn = test.promptLength(question, first, user("More."))

        val result = test.engine.readAhead(ask(question, first, user("More."), second))
        assertTrue("$result", result is ReadAheadResult.Read && result.keptTokens >= beforeSecondTurn - 20)
        test.fake.enqueueReply("Fine.")
        test.fake.clearCalls()
        val stats = test.engine.generate(ask(question, first, user("More."), second, user("Thanks."))).toList().done().stats
        assertTrue(test.fake.callsOf<Call.RestoreCheckpoint>().isEmpty() && test.fake.callsOf<Call.ClearMemory>().isEmpty())
        assertTrue("read ${stats.evaluatedPromptTokens}", stats.evaluatedPromptTokens <= "Thanks.".length + 30)
    }

    // --- Falling back to encoding ---

    /**
     * "Continue" of the stopped answer through [change], and the same without written replies:
     * every request of the control carries no conversation, and the engine keeps none for such
     * a request. [change] is given the engine, the text shown and the conversation's key.
     */
    private suspend fun TestScope.againstEncoding(change: suspend (TestEngine, String, String?) -> GenerationStats): Pair<GenerationStats, GenerationStats> {
        val test = engine()
        val changed = change(test, answerStopped(test), KEY)
        val control = engine()
        val encoded = change(control, answerStopped(control, key = null), null)
        return changed to encoded
    }

    /**
     * Catches written tokens used where the text is not what the model wrote, where the memory
     * no longer holds them, or for another conversation: each reads what it reads with the
     * answer encoded, which keeps nothing of the memory beyond the piece.
     */
    @Test
    fun `an edited answer, another conversation, a cleared memory and a restore fall back to encoding`() = runTest {
        val cases = mapOf<String, suspend (TestEngine, String, String?) -> GenerationStats>(
            "edited" to { test, shown, key -> test.continueWith(shown.replace("Zz", "Zy"), key) },
            "another conversation" to { test, shown, key -> test.continueWith(shown, key?.let { "another" }) },
            "cleared" to { test, shown, key ->
                test.engine.forget()
                test.continueWith(shown, key)
            },
        )
        for ((name, change) in cases) {
            val (changed, encoded) = againstEncoding(change)
            assertEquals("$name: as encoded", encoded.reusedPromptTokens, changed.reusedPromptTokens)
            assertEquals("$name: as encoded", encoded.promptTokens, changed.promptTokens)
            assertTrue("$name: kept ${changed.reusedPromptTokens}", changed.evaluatedPromptTokens > 64)
        }

        // A restore: the image carries no written reply, so the answer is encoded.
        val test = engine()
        vault.onCommit = { test.fake.memory.toList() }
        val shown = answerStopped(test)
        val start = test.promptLength()
        assertTrue(test.engine.saveMemory(vault, KEY, SaveTrigger.LOCK) is MemorySaveResult.Saved)
        test.engine.forgetKeepingSavedMemory()
        assertTrue(test.engine.restoreMemory(vault, KEY) is MemoryRestoreResult.Restored)
        assertTrue(test.engine.writtenRepliesForTests().isEmpty())
        val stats = test.continueWith(shown)
        assertTrue("kept ${stats.reusedPromptTokens}, beyond the piece", stats.reusedPromptTokens <= start + 200)
        assertTrue(stats.evaluatedPromptTokens > 64)
    }

    // --- Overwritten ---

    /** Catches a written reply let go of without being overwritten: its arrays spell the answer. */
    @Test
    fun `written replies are overwritten when they are let go of`() = runTest {
        val triggers = mapOf<String, suspend (TestEngine, String) -> Unit>(
            "forget" to { test, _ -> test.engine.forget() },
            "unload" to { test, _ -> test.engine.unload() },
            "another model" to { test, _ -> test.engine.load(qwen.copy(id = "other-model")) },
            "a request of another conversation" to { test, _ ->
                test.fake.enqueueReply("Hi.")
                test.engine.generate(ask(user("Hello."), key = "another")).toList()
            },
            "a request without the answer" to { test, shown ->
                test.fake.enqueueReply("Hi.")
                test.engine.generate(ask(question, assistant(shown.replace("Zz", "Zy")), user("Hello."))).toList()
            },
            "a restore" to { test, _ ->
                assertTrue(test.engine.saveMemory(vault, KEY, SaveTrigger.LOCK) is MemorySaveResult.Saved)
                test.engine.forgetKeepingSavedMemory()
                test.engine.restoreMemory(vault, KEY)
            },
        )
        for ((name, trigger) in triggers) {
            val test = engine()
            val shown = answerStopped(test)
            val held = test.engine.writtenRepliesForTests()
            assertEquals(name, 1, held.size)
            val reply = held.single()
            assertEquals(name, 300, reply.ids.size)
            assertTrue(name, reply.ids.any { it != 0 } && reply.content.any { it != 0.toByte() } && reply.ends.any { it != 0 })
            trigger(test, shown)
            assertFalse("$name: still held", test.engine.writtenRepliesForTests().any { it === reply })
            assertTrue("$name: ids", reply.ids.all { it == 0 })
            assertTrue("$name: ends", reply.ends.all { it == 0 })
            assertTrue("$name: text", reply.content.all { it == 0.toByte() })
        }
    }

    // --- Unaffected ---

    /**
     * Where the model's tokens are the encoding of its text, as for most answers, the written
     * replies change nothing: three turns with read-aheads between them make the same native
     * calls with them as without them, for a Boris-2-like model and one with attention only.
     */
    @Test
    fun `models whose answers encode as written read exactly as before`() = runTest {
        for (spec in listOf(boris, attention, qwen)) {
            suspend fun conversation(key: String?): List<Call> {
                val test = engine(spec)
                val history = ArrayList<PromptMessage>()
                for (turn in 1..3) {
                    history += user("Question $turn?")
                    test.fake.enqueueReply("A".repeat(90 + turn) + (if (spec === boris) IM_END else ""))
                    val text = test.engine.generate(ask(*history.toTypedArray(), key = key)).toList().done().text
                    history += assistant(PromptWhitespace.strip(text))
                    test.engine.readAhead(ask(*history.toTypedArray(), key = key))
                }
                return test.fake.calls.filter { it !is Call.Tokenize }
            }
            assertEquals(spec.family.name, conversation(key = null), conversation(key = KEY))
        }
    }

    private companion object {
        const val KEY = "chat"
    }
}
