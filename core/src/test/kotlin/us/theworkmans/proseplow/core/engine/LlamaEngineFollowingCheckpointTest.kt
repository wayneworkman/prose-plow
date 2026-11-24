package us.theworkmans.proseplow.core.engine

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import us.theworkmans.proseplow.core.engine.FakeNativeLlama.Call
import us.theworkmans.proseplow.core.model.ModelSpec
import us.theworkmans.proseplow.core.model.ReadingTimes
import us.theworkmans.proseplow.core.prompt.PlainChatMlFormat
import us.theworkmans.proseplow.core.prompt.PromptInput
import us.theworkmans.proseplow.core.prompt.PromptMessage

/**
 * The checkpoints that follow a reply, for a model that cannot cut its memory back: one taken
 * every [LlamaEngine.Settings.followingCheckpointInterval] tokens of the reply, the latest
 * [LlamaEngine.FOLLOWING_CHECKPOINTS] kept, apart from the checkpoints of the prompt, and saved
 * and restored with the memory.
 *
 * A reply is scripted with a piece: one token that stands for several characters, which the
 * text of the reply tokenised again spells with one token each. The next request therefore
 * differs from the memory at the piece, as a real reply that was cut off or trimmed does near
 * its end. The fake counts one token for every character of a plain-text ChatML prompt.
 */
class LlamaEngineFollowingCheckpointTest {

    private val interval = LlamaEngine.Settings().followingCheckpointInterval

    /** A model with recurrent state, which reads 10 tokens a second: a few hundred tokens are worth saving. */
    private val recurrent: ModelSpec = testModel(contextLength = 2000).copy(readingTimes = ReadingTimes.flat(10.0), cutsMemoryBack = false)

    private val cannotCut: (FakeNativeLlama) -> Unit = { it.refuseRewind = true }

    private val vault = FakeMemoryVault()

    private val system = "Be brief."

    private fun prompt(vararg messages: PromptMessage): String =
        PlainChatMlFormat.render(PromptInput(messages.toList(), systemPrompt = system)).text()

    private suspend fun TestScope.engine(
        spec: ModelSpec = recurrent,
        settings: LlamaEngine.Settings = LlamaEngine.Settings(),
        configure: (FakeNativeLlama) -> Unit = cannotCut,
    ): TestEngine = loadedEngine(spec, settings, configure = configure)

    /** Asks [messages] with the scripted [reply], which is text and, where [piece] is given, that piece after [before] characters. */
    private suspend fun TestEngine.ask(
        vararg messages: PromptMessage,
        reply: String,
        piece: String? = null,
        before: Int = reply.length,
        maxNewTokens: Int = 1000,
    ): List<GenerationEvent> {
        enqueue(reply, piece, before)
        return engine.generate(request(*messages, systemPrompt = system, maxNewTokens = maxNewTokens).copy(conversationKey = KEY)).toList()
    }

    /** Queues [reply] as one token per character, with [piece] as a single token after its first [before] characters. */
    private fun TestEngine.enqueue(reply: String, piece: String?, before: Int) {
        val tokens = fake.textTokens(reply.substring(0, before)) +
            (if (piece == null) emptyList() else listOf(fake.addPiece(piece))) +
            fake.textTokens(reply.substring(before))
        fake.enqueueReply(tokens)
    }

    /** The checkpoints the fake holds, as their lengths, in ascending order. */
    private fun TestEngine.checkpointLengths(): List<Int> = fake.checkpoints.values.sorted()

    /** How many tokens [memory] and [tokens] have in common from their beginning. */
    private fun commonLength(memory: List<Int>, tokens: List<Int>): Int {
        var n = 0
        while (n < memory.size && n < tokens.size && memory[n] == tokens[n]) n++
        return n
    }

    // --- A long reply, then a request that differs near its end ---

    /**
     * Catches: no checkpoint following the reply, so that the only one before the difference is
     * the one in front of the reply and the whole reply is read again.
     */
    @Test
    fun `a request that differs near the end of a long reply reads again at most the interval and the difference`() = runTest {
        val test = engine()
        val answer = "A".repeat(200) + "Zz" + "B".repeat(10)
        test.ask(user("Tell me a long story."), reply = answer.removeRange(200, 202), piece = "Zz", before = 200)
        val memoryBefore = test.fake.memory.toList()
        val replyStart = prompt(user("Tell me a long story.")).length

        val next = arrayOf(user("Tell me a long story."), assistant(answer), user("Shorter."))
        test.fake.clearCalls()
        val events = test.ask(*next, reply = "Fine.")

        val stats = events.done().stats
        val common = commonLength(memoryBefore, tokensOf(test, *next))
        assertEquals("the request differs from the memory at the piece", replyStart + 200, common)
        val difference = stats.promptTokens - common
        val reread = stats.promptTokens - stats.reusedPromptTokens
        assertTrue("read again $reread tokens, more than $interval and the difference of $difference", reread <= interval + difference)
        assertEquals(replyStart + 192, stats.reusedPromptTokens)
        assertTrue("the memory was not cleared", test.fake.callsOf<Call.ClearMemory>().isEmpty())
        assertEquals(1, test.fake.callsOf<Call.RestoreCheckpoint>().size)
        assertEquals(prompt(*next) + "Fine.", test.fake.memoryText)
    }

    /** Catches: the following checkpoints left out of the image, or restored as the prompt's and then dropped. */
    @Test
    fun `the same holds after the memory was saved, forgotten and restored`() = runTest {
        val test = engine()
        val answer = "A".repeat(200) + "Zz" + "B".repeat(10)
        test.ask(user("Tell me a long story."), reply = answer.removeRange(200, 202), piece = "Zz", before = 200)
        val memoryBefore = test.fake.memory.toList()
        val lengthsBefore = test.checkpointLengths()
        val replyStart = prompt(user("Tell me a long story.")).length
        assertTrue("two checkpoints follow the reply", replyStart + 160 in lengthsBefore && replyStart + 192 in lengthsBefore)
        val lengthOf = test.fake.checkpoints

        assertTrue(test.engine.saveMemory(vault, KEY, SaveTrigger.LOCK) is MemorySaveResult.Saved)
        val exported = test.fake.callsOf<Call.ExportMemory>().single().checkpoints.map { lengthOf.getValue(it) }
        val prompts = lengthsBefore.filter { it <= replyStart }
        assertEquals("every checkpoint, the following ones first and latest first, then the prompt's in order", listOf(replyStart + 192, replyStart + 160) + prompts, exported)
        test.engine.forgetKeepingSavedMemory()
        assertTrue(test.fake.checkpoints.isEmpty())
        assertEquals(MemoryRestoreResult.Restored(memoryBefore.size), test.engine.restoreMemory(vault, KEY))
        assertEquals(lengthsBefore, test.checkpointLengths())

        val next = arrayOf(user("Tell me a long story."), assistant(answer), user("Shorter."))
        test.fake.clearCalls()
        val events = test.ask(*next, reply = "Fine.")

        val stats = events.done().stats
        val common = commonLength(memoryBefore, tokensOf(test, *next))
        val reread = stats.promptTokens - stats.reusedPromptTokens
        assertTrue("read again $reread tokens", reread <= interval + (stats.promptTokens - common))
        assertEquals(replyStart + 192, stats.reusedPromptTokens)
        assertTrue(test.fake.callsOf<Call.ClearMemory>().isEmpty())
        assertEquals(prompt(*next) + "Fine.", test.fake.memoryText)
    }

    /** Catches: a following checkpoint only taken when a reply ends normally, or only at its end. */
    @Test
    fun `a reply cut off at the length limit or cancelled part-way leaves a usable following checkpoint`() = runTest {
        // Cut off at the length limit: the last token sampled is not evaluated, the following
        // checkpoint lies 192 tokens into the reply, and the difference begins at 195.
        val cut = engine()
        val answer = "A".repeat(195) + "Zz" + "B".repeat(4)
        val cutEvents = cut.ask(user("Go on and on."), reply = answer.removeRange(195, 197), piece = "Zz", before = 195, maxNewTokens = 200)
        assertEquals(FinishReason.LENGTH, cutEvents.done().finishReason)
        val cutMemory = cut.fake.memory.toList()
        val next = arrayOf(user("Go on and on."), assistant(answer), user("Stop."))
        cut.fake.clearCalls()
        val cutNext = cut.ask(*next, reply = "Stopped.").done().stats
        val cutCommon = commonLength(cutMemory, tokensOf(cut, *next))
        assertEquals(prompt(user("Go on and on.")).length + 195, cutCommon)
        assertTrue(cutNext.promptTokens - cutNext.reusedPromptTokens <= interval + (cutNext.promptTokens - cutCommon))
        assertEquals(prompt(user("Go on and on.")).length + 192, cutNext.reusedPromptTokens)
        assertTrue(cut.fake.callsOf<Call.ClearMemory>().isEmpty())

        // Cancelled by its collector after 100 tokens were sampled.
        val cancelled = engine()
        lateinit var collecting: Job
        var samples = 0
        cancelled.fake.beforeCall = { call -> if (call is Call.SampleNext && ++samples == 100) collecting.cancel() }
        cancelled.enqueue("A".repeat(97) + "C".repeat(50), piece = "Zz", before = 97)
        collecting = launch { cancelled.engine.generate(request(user("Go on and on."), systemPrompt = system, maxNewTokens = 1000)).collect { } }
        collecting.join()
        cancelled.fake.beforeCall = {}
        val replyStart = prompt(user("Go on and on.")).length
        assertEquals("99 tokens of the reply were evaluated", replyStart + 99, cancelled.fake.memory.size)
        assertTrue("a checkpoint follows the part written", replyStart + 96 in cancelled.checkpointLengths())
        val cancelledMemory = cancelled.fake.memory.toList()
        val after = arrayOf(user("Go on and on."), assistant("A".repeat(97) + "ZzC"), user("Why stop?"))
        cancelled.fake.clearCalls()
        val afterStats = cancelled.ask(*after, reply = "You asked.").done().stats
        val afterCommon = commonLength(cancelledMemory, tokensOf(cancelled, *after))
        assertEquals(replyStart + 97, afterCommon)
        assertEquals(replyStart + 96, afterStats.reusedPromptTokens)
        assertTrue(cancelled.fake.callsOf<Call.ClearMemory>().isEmpty())
    }

    /**
     * The guarantee of two following checkpoints: for every position of the reply's end
     * relative to the interval (replies of 200 to 231 tokens), a request that differs from the
     * memory anywhere in the reply's last 32 tokens reads again at most two intervals and the
     * difference, and never goes back to the checkpoint in front of the reply. Catches one
     * following checkpoint kept instead of two, which a difference just before a checkpoint
     * taken near the end misses.
     */
    @Test
    fun `a difference anywhere in the last interval of a reply never goes back to the checkpoint in front of it`() = runTest {
        val question = user("Go on and on.")
        val replyStart = prompt(question).length
        for (length in 200 until 200 + interval) {
            for (back in 1..interval) {
                val case = "a reply of $length tokens that differs $back before its end"
                val test = engine()
                val at = length - back
                val answer = "A".repeat(at) + "Zz" + "B".repeat(back - 1)
                test.ask(question, reply = answer.removeRange(at, at + 2), piece = "Zz", before = at)
                assertEquals(case, replyStart + length, test.fake.memory.size)
                val memoryBefore = test.fake.memory.toList()
                val next = arrayOf(question, assistant(answer), user("Stop."))
                test.fake.clearCalls()
                val stats = test.ask(*next, reply = "Stopped.").done().stats
                val common = commonLength(memoryBefore, tokensOf(test, *next))
                assertEquals(case, replyStart + at, common)
                val reread = stats.promptTokens - stats.reusedPromptTokens
                assertTrue("$case: read again $reread", reread <= 2 * interval + (stats.promptTokens - common))
                assertTrue("$case: went back to ${stats.reusedPromptTokens}, in front of the reply", stats.reusedPromptTokens > replyStart)
                assertTrue(case, test.fake.callsOf<Call.ClearMemory>().isEmpty())
            }
        }
    }

    /**
     * An image without following checkpoints, as one written before there were any (here by an
     * engine that takes none), restores its checkpoints as the prompt's, all of them; the next
     * long reply, an answer given again, then takes its following ones beside them. Catches a
     * checkpoint of a prompt taken for a following one, which that reply would drop.
     */
    @Test
    fun `an image without following checkpoints restores them all as the prompt's`() = runTest {
        val first = "A".repeat(120)
        val history = arrayOf(user("One?"), assistant(first), user("Two?"))
        val old = engine(settings = LlamaEngine.Settings(followingCheckpointInterval = 0))
        old.ask(user("One?"), reply = first)
        old.ask(*history, reply = "B".repeat(130))
        val prompts = old.checkpointLengths()
        assertEquals(4, prompts.size)
        assertTrue(old.engine.saveMemory(vault, KEY, SaveTrigger.LOCK) is MemorySaveResult.Saved)

        val test = engine()
        assertTrue(test.engine.restoreMemory(vault, KEY) is MemoryRestoreResult.Restored)
        assertEquals(prompts, test.checkpointLengths())
        // Answering again adds no checkpoint of a prompt, and its reply takes two following ones.
        test.ask(*history, reply = "C".repeat(100))
        val control = engine()
        control.ask(user("One?"), reply = first)
        control.ask(*history, reply = "B".repeat(130))
        control.ask(*history, reply = "C".repeat(100))
        assertEquals("the same checkpoints as without the lock", control.checkpointLengths(), test.checkpointLengths())
        assertEquals("the prompt's four are all still there", prompts, test.checkpointLengths().filter { it in prompts })
    }

    // --- The checkpoints of the prompt are not displaced ---

    /** Two turns with long replies, then [last]; returns the stats of [last]'s request and the position of the checkpoint it went back through. */
    private suspend fun TestScope.twoTurnsThen(settings: LlamaEngine.Settings, vararg last: PromptMessage): Triple<GenerationStats, Int, List<List<Int>>> {
        val test = engine(settings = settings)
        val first = "A".repeat(120)
        val second = "B".repeat(130)
        test.ask(user("One?"), reply = first)
        test.ask(user("One?"), assistant(first), user("Two?"), reply = second)
        val lengths = test.fake.checkpoints
        test.fake.clearCalls()
        val stats = test.ask(*last, reply = "Again.").done().stats
        val handle = test.fake.callsOf<Call.RestoreCheckpoint>().single().checkpoint
        assertTrue(test.fake.callsOf<Call.ClearMemory>().isEmpty())
        return Triple(stats, lengths.getValue(handle), test.fake.promptEvaluations())
    }

    /** Catches: a following checkpoint that takes the place of one of the prompt's, which "answer again" or an edit then misses. */
    @Test
    fun `answering again and editing the last two questions go back through their own checkpoints and read no more than before`() = runTest {
        val first = "A".repeat(120)
        val cases = mapOf(
            "answer again" to arrayOf(user("One?"), assistant(first), user("Two?")),
            "edit of the last question" to arrayOf(user("One?"), assistant(first), user("Three?")),
            "edit of the question before it" to arrayOf(user("Uno?")),
        )
        for ((name, last) in cases) {
            val without = twoTurnsThen(LlamaEngine.Settings(followingCheckpointInterval = 0), *last)
            val with = twoTurnsThen(LlamaEngine.Settings(), *last)
            assertEquals("$name: the same checkpoint", without.second, with.second)
            assertEquals("$name: the same reuse", without.first.reusedPromptTokens, with.first.reusedPromptTokens)
            assertEquals("$name: the same reading", without.third, with.third)
        }
    }

    /** Catches: a following checkpoint counted among the prompt's, before or after a restore. */
    @Test
    fun `the checkpoints of the prompt are those kept without following ones, also after a restore`() = runTest {
        suspend fun conversation(settings: LlamaEngine.Settings): TestEngine {
            val test = engine(settings = settings)
            val history = ArrayList<PromptMessage>()
            for (turn in 1..3) {
                history += user("Question $turn?")
                val answer = "A".repeat(90 + turn)
                test.ask(*history.toTypedArray(), reply = answer)
                history += assistant(answer)
            }
            return test
        }
        val without = conversation(LlamaEngine.Settings(followingCheckpointInterval = 0)).checkpointLengths()
        val test = conversation(LlamaEngine.Settings())
        val replyStart = test.fake.memory.size - 93
        val followers = listOf(replyStart + 32, replyStart + 64)
        assertEquals("the prompt's four and the two that follow the reply", (without + followers).sorted(), test.checkpointLengths())
        assertEquals(4, without.size)

        assertTrue(test.engine.saveMemory(vault, KEY, SaveTrigger.LOCK) is MemorySaveResult.Saved)
        test.engine.forgetKeepingSavedMemory()
        assertTrue(test.engine.restoreMemory(vault, KEY) is MemoryRestoreResult.Restored)
        assertEquals((without + followers).sorted(), test.checkpointLengths())
    }

    // --- Bounds ---

    /** Catches: a new following checkpoint taken before the oldest one is dropped, or more than two kept. */
    @Test
    fun `no more than the prompt's checkpoints and two are ever held during long replies`() = runTest {
        val settings = LlamaEngine.Settings()
        val test = engine(settings = settings)
        var mostAtOnce = 0
        test.fake.beforeCall = { mostAtOnce = maxOf(mostAtOnce, test.fake.checkpoints.size) }
        val history = ArrayList<PromptMessage>()
        for (turn in 1..4) {
            history += user("Question $turn?")
            val answer = "A".repeat(300)
            test.ask(*history.toTypedArray(), reply = answer)
            mostAtOnce = maxOf(mostAtOnce, test.fake.checkpoints.size)
            history += assistant(answer)
        }
        assertEquals(settings.maxCheckpoints + LlamaEngine.FOLLOWING_CHECKPOINTS, mostAtOnce)
        // Nine following checkpoints were taken in each reply of 300 tokens; the latest two are kept.
        val replyStart = test.fake.memory.size - 300
        assertEquals(listOf(replyStart + 256, replyStart + 288), test.checkpointLengths().filter { it > replyStart })
    }

    /** Catches: a following checkpoint for a model that can cut its memory back, or when switched off. */
    @Test
    fun `a model that can cut its memory back takes no following checkpoint`() = runTest {
        val cases = listOf(
            "attention only" to engine(spec = recurrent.copy(cutsMemoryBack = true), configure = {}),
            "switched off" to engine(settings = LlamaEngine.Settings(followingCheckpointInterval = 0)),
        )
        for ((name, test) in cases) {
            test.ask(user("Tell me a long story."), reply = "A".repeat(300))
            val inReply = test.fake.calls.dropWhile { it !is Call.SampleNext }
            assertTrue("$name: no checkpoint while the reply is written", inReply.none { it is Call.SaveCheckpoint })
            assertTrue("$name: none beyond the prompt", test.checkpointLengths().all { it <= prompt(user("Tell me a long story.")).length })
        }
        val recurrentTest = engine()
        recurrentTest.ask(user("Tell me a long story."), reply = "A".repeat(300))
        assertTrue("a model with recurrent state does take one", recurrentTest.checkpointLengths().any { it > prompt(user("Tell me a long story.")).length })
    }

    /** The tokens of the prompt of [messages] as the engine asks them, by the fake's one token per character. */
    private fun tokensOf(test: TestEngine, vararg messages: PromptMessage): List<Int> = test.fake.textTokens(prompt(*messages))

    private companion object {
        const val KEY = "chat"
    }
}
