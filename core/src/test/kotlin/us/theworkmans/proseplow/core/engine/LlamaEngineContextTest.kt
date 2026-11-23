package us.theworkmans.proseplow.core.engine

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import us.theworkmans.proseplow.core.model.ModelSpec
import us.theworkmans.proseplow.core.model.ReadingTimes
import us.theworkmans.proseplow.core.prompt.PromptMessage

/**
 * A context that follows its conversation: it grows through its steps when the conversation
 * needs it, as far as the model, the device's memory and the reading time allow; it keeps the
 * model's memory where that can be carried over; what cannot be had becomes the limit, at which
 * the oldest turns are left out; and the memory is given back when the conversation is left.
 *
 * The fake counts one token for every character, so a prompt's length is its size in tokens.
 */
class LlamaEngineContextTest {

    /** A model that starts with 256 tokens of context and was trained with 4,096. */
    private val small: ModelSpec = testModel(contextLength = 256, maxNewTokens = 32).copy(maxContextLength = 4096)

    private var device = DeviceMemory.PLENTY

    /** What happens each time a generation that stands aside would have slept. Set by the test. */
    private var whileWaiting: () -> Unit = {}
    private var waits = 0
    private val gate = GenerationGate(idle = {
        waits++
        whileWaiting()
    })

    private suspend fun TestScope.engine(model: ModelSpec = small, configure: (FakeNativeLlama) -> Unit = {}): TestEngine {
        lateinit var created: TestEngine
        val factory = FakeNativeLlamaFactory { spec ->
            FakeNativeLlama(contextTokens = spec.contextLength).also { fake ->
                fake.trainedContextTokens = spec.maxContextLength
                created.chatMl = fake.registerChatMl()
                configure(fake)
            }
        }
        val engine = LlamaEngine(
            factory = factory,
            nativeDispatcher = StandardTestDispatcher(testScheduler),
            settings = LlamaEngine.Settings(evaluationChunk = 32, giveBackRereadTokens = 96),
            nanoTime = { factory.opened.lastOrNull()?.second?.nanos ?: 0L },
            newSeed = { TEST_SEED },
            gate = gate,
            memoryGauge = { device },
        )
        created = TestEngine(engine, factory)
        engine.load(model)
        return created
    }

    /** [turns] finished exchanges of about [size] characters a message, and one more question. */
    private fun talk(turns: Int, size: Int = 60): List<PromptMessage> = (1..turns).flatMap { turn ->
        listOf(user("Q$turn " + "q".repeat(size)), assistant("A$turn " + "a".repeat(size)))
    } + user("The latest question, which must always be sent.")

    /** About 200 tokens: fits the 256 the model starts with, room for the reply included. */
    private val short = talk(1, size = 20)

    /** About 830 tokens: needs the step of 1,024. */
    private val long = talk(4)

    private suspend fun TestEngine.ask(messages: List<PromptMessage>, limit: Int? = 2048): List<GenerationEvent> {
        fake.enqueueReply("Yes.")
        return engine.generate(request(*messages.toTypedArray()).copy(maxContextTokens = limit)).toList()
    }

    private fun List<GenerationEvent>.grown() = filterIsInstance<GenerationEvent.ContextGrown>()

    private fun List<GenerationEvent>.limited() = filterIsInstance<GenerationEvent.ContextLimited>()

    private fun List<GenerationEvent>.trimmed() = filterIsInstance<GenerationEvent.HistoryTrimmed>()

    @Test
    fun `a conversation that outgrows the context makes it larger, to the smallest step that holds it, and nothing is left out`() = runTest {
        val test = engine()
        assertEquals(256, test.fake.contextSize())
        val short = test.ask(short)
        assertTrue("a conversation that fits changes nothing", short.grown().isEmpty() && test.fake.resizes.isEmpty())

        val events = test.ask(long)

        assertEquals(listOf(GenerationEvent.ContextGrown(fromTokens = 256, toTokens = 1024, rereading = false)), events.grown())
        assertEquals("one step, the smallest that holds it: not 512, not 2,048", listOf(1024), test.fake.resizes.map { it.first })
        assertEquals(1024, test.fake.contextSize())
        assertTrue(events.trimmed().isEmpty() && events.limited().isEmpty())
        assertEquals(0, events.done().stats.droppedMessages)
        assertEquals("Yes.", events.text())
        assertTrue("the whole conversation is in the memory", test.fake.memoryText.contains("Q1 ") && test.fake.memory.size in 513..1024)
        assertEquals("the penalties look at the whole memory, whatever its size", SamplerConfig.NO_PENALTY_WINDOW, test.fake.sampler!!.penaltyWindow)

        // The same conversation again needs nothing more.
        test.ask(long)
        assertEquals(1, test.fake.resizes.size)
    }

    @Test
    fun `the model's memory is carried into the larger context and only what is new is read`() = runTest {
        val test = engine()
        test.ask(short)
        val held = test.fake.memory.size
        test.fake.clearCalls()

        val events = test.ask(short + assistant("Yes.") + user("x".repeat(400)))

        assertEquals(listOf(GenerationEvent.ContextGrown(256, 1024, rereading = false)), events.grown())
        val reused = events.done().stats.reusedPromptTokens
        assertTrue("what was in the memory stayed there: $reused of $held", reused >= held - 8)
        assertTrue(test.fake.evaluatedTokens.size < 520)
    }

    @Test
    fun `when the memory cannot be carried over the whole conversation is read again, and says so`() = runTest {
        val test = engine { it.carriesMemoryOnResize = false }
        test.ask(short)
        test.fake.clearCalls()

        val events = test.ask(short + assistant("Yes.") + user("x".repeat(400)))

        assertEquals(listOf(GenerationEvent.ContextGrown(256, 1024, rereading = true)), events.grown())
        assertEquals("nothing could be reused", 0, events.done().stats.reusedPromptTokens)
        assertEquals("Yes.", events.text())
        assertTrue("and all of it is in the larger context", test.fake.memory.size >= events.done().stats.promptTokens)
    }

    @Test
    fun `a size the device has no memory for is the limit from then on, said once, and only there are old turns left out`() = runTest {
        // 1,000 bytes a token. Above the floor there is room for 300,000 bytes beside the 256,000 held: 512 tokens fit, 1,024 do not.
        device = DeviceMemory(totalBytes = 1_000_000_000, availableBytes = 200_300_000, thresholdBytes = 0, low = false)
        val test = engine { it.bytesPerToken = 1000 }

        val events = test.ask(long)

        assertEquals("as far as the memory allows", listOf(GenerationEvent.ContextGrown(256, 512, rereading = false)), events.grown())
        assertEquals(listOf(GenerationEvent.ContextLimited(512, GenerationEvent.ContextLimited.Reason.MEMORY)), events.limited())
        assertEquals(1, events.trimmed().size)
        assertTrue(events.done().stats.droppedMessages > 0)
        assertTrue("the latest question is there", test.fake.memoryText.contains("The latest question"))
        assertEquals(512, test.fake.contextSize())
        assertTrue("nothing was asked for that the budget did not hold", test.fake.resizes.all { (tokens, budget) -> tokens * 1000L <= budget })

        // The next answer runs into the same limit without another word about it, and without asking again.
        val asked = test.fake.resizes.size
        val next = test.ask(talk(5))
        assertTrue(next.limited().isEmpty() && next.grown().isEmpty())
        assertEquals(1, next.trimmed().size)
        assertEquals(asked, test.fake.resizes.size)
    }

    @Test
    fun `what a session reserves whatever the size of its context is not counted against the budget, and the native side is given both`() = runTest {
        // As above: 1,000 bytes a token, and room for 300,000 bytes beside the 256,000 the tokens hold. The session also
        // reserves 500,000,000 bytes at every size, twice what the device's memory allows a context's tokens (250,000,000).
        device = DeviceMemory(totalBytes = 1_000_000_000, availableBytes = 200_300_000, thresholdBytes = 0, low = false)
        val test = engine {
            it.bytesPerToken = 1000
            it.reservedBytes = 500_000_000
        }

        val events = test.ask(long)

        assertEquals("the step the tokens' memory allows is taken", listOf(GenerationEvent.ContextGrown(256, 512, rereading = false)), events.grown())
        assertEquals(listOf(GenerationEvent.ContextLimited(512, GenerationEvent.ContextLimited.Reason.MEMORY)), events.limited())
        assertEquals("the native side counts the reservation, so its budget is the tokens' budget and the reservation", listOf(512 to 500_556_000L), test.fake.resizes)
        assertEquals("Yes.", events.text())
    }

    /**
     * A device whose quarter holds 3,072 tokens of 1,000 bytes, with plenty available beside it: the policy's limit
     * for a chosen size of 3,072 is 3,072, and the budget for the tokens is the quarter, 3,072,000 bytes. The sizes
     * the engine asks about to learn the cost of a token (1,024 and 2,048) are costed on the straight line, as the
     * native side costs sizes it has created.
     */
    private val quarterOf3072 = DeviceMemory(totalBytes = 12_288_000, availableBytes = 1_000_000_000, thresholdBytes = 0, low = false)

    /** The context at 2,048 first, then a conversation that needs the step of 3,072 (the limit) and fits in 2,816. */
    private suspend fun TestEngine.growToTheLimit(): List<GenerationEvent> {
        assertEquals(listOf(2048), ask(talk(8), limit = 3072).grown().map { it.toTokens })
        return ask(talk(14), limit = 3072)
    }

    @Test
    fun `a limit the native side costs just above the budget is not given up, and the largest size below it that fits is taken`() = runTest {
        device = quarterOf3072
        // As the native side counts a size it has not created yet: 2% above the token arithmetic.
        val test = engine { fake -> fake.contextCost = { tokens -> if (tokens > 2048) tokens * 1020L else tokens * 1000L } }

        val events = test.growToTheLimit()

        assertEquals("not left at 2,048", listOf(GenerationEvent.ContextGrown(2048, 2816, rereading = false)), events.grown())
        assertEquals("the size found is the limit, and the chat is told which", listOf(GenerationEvent.ContextLimited(2816, GenerationEvent.ContextLimited.Reason.MEMORY)), events.limited())
        assertEquals(2816, test.fake.contextSize())
        assertTrue("the conversation fits in it whole", events.trimmed().isEmpty())
        assertEquals("Yes.", events.text())
        assertTrue("nothing was asked for that the native side costs above the budget", test.fake.resizes.all { (tokens, budget) -> tokens * 1020L <= budget || tokens <= 2048 })

        // The next answer runs into the same limit without another word about it, and without asking again.
        val asked = test.fake.resizes.size
        val next = test.ask(talk(15), limit = 3072)
        assertTrue(next.limited().isEmpty() && next.grown().isEmpty())
        assertEquals(asked, test.fake.resizes.size)
    }

    @Test
    fun `a limit the native side costs within the budget is reached as before`() = runTest {
        device = quarterOf3072
        val test = engine { fake -> fake.contextCost = { tokens -> tokens * 1000L } }

        val events = test.growToTheLimit()

        assertEquals(listOf(GenerationEvent.ContextGrown(2048, 3072, rereading = false)), events.grown())
        assertTrue(events.limited().isEmpty())
        assertEquals(listOf(2048, 3072), test.fake.resizes.map { it.first })
    }

    @Test
    fun `when the native side costs every larger size above the budget the context stays as it is, as before`() = runTest {
        device = quarterOf3072
        // Twice the token arithmetic beyond 2,048: 2,304 tokens already cost 4,608,000 bytes.
        val test = engine { fake -> fake.contextCost = { tokens -> if (tokens > 2048) tokens * 2000L else tokens * 1000L } }

        val events = test.growToTheLimit()

        assertTrue(events.grown().isEmpty())
        assertEquals(listOf(GenerationEvent.ContextLimited(2048, GenerationEvent.ContextLimited.Reason.MEMORY)), events.limited())
        assertEquals("nothing more was asked for", listOf(2048), test.fake.resizes.map { it.first })
        assertEquals(2048, test.fake.contextSize())
        assertEquals(1, events.trimmed().size)
    }

    @Test
    fun `a session that cannot say what a context costs keeps the size it has and still answers`() = runTest {
        val test = engine { it.costKnown = false }

        val events = test.ask(long)

        assertTrue("nothing is asked for whose cost is not known", events.grown().isEmpty() && test.fake.resizes.isEmpty())
        assertEquals(listOf(GenerationEvent.ContextLimited(256, GenerationEvent.ContextLimited.Reason.FAILED)), events.limited())
        assertEquals(1, events.trimmed().size)
        assertEquals(256, test.fake.contextSize())
        assertEquals("Yes.", events.text())
    }

    @Test
    fun `while the system says memory is low nothing is added, and afterwards the context grows again`() = runTest {
        val test = engine()
        device = DeviceMemory.PLENTY.copy(low = true)

        val pressed = test.ask(long)
        assertTrue(pressed.grown().isEmpty() && test.fake.resizes.isEmpty())
        assertEquals(listOf(GenerationEvent.ContextLimited(256, GenerationEvent.ContextLimited.Reason.MEMORY)), pressed.limited())
        assertEquals(1, pressed.trimmed().size)
        assertTrue("said once while it lasts", test.ask(long).limited().isEmpty())

        device = DeviceMemory.PLENTY
        val relieved = test.ask(long)
        assertEquals(listOf(1024), relieved.grown().map { it.toTokens })
        assertTrue(relieved.trimmed().isEmpty())
    }

    @Test
    fun `a context that could not be created leaves what exists as the limit, with the memory if it survived`() = runTest {
        val test = engine { it.cannotCreateFrom = 1024 }
        test.ask(short)
        val held = test.fake.memory.size

        // The conversation goes on for three more exchanges: more than 256 tokens hold, with a latest question that fits.
        val events = test.ask(short + assistant("Yes.") + talk(3))

        assertTrue(events.grown().isEmpty())
        assertEquals(listOf(GenerationEvent.ContextLimited(256, GenerationEvent.ContextLimited.Reason.FAILED)), events.limited())
        assertEquals("Yes.", events.text())
        assertEquals(256, test.fake.contextSize())
        assertEquals("at the limit the oldest turns are left out", 1, events.trimmed().size)
        assertTrue("the old context came back with its memory, which the engine went on using", held > 0 && test.fake.calls.none { it is FakeNativeLlama.Call.ClearMemory })

        // The old size could not be had again either: something smaller exists, and it is empty.
        val worse = engine {
            it.cannotCreateFrom = 1024
            it.contextAfterFailedResize = 128
        }
        worse.ask(listOf(user("Short.")))
        val after = worse.ask(long)
        assertEquals(listOf(GenerationEvent.ContextLimited(128, GenerationEvent.ContextLimited.Reason.FAILED)), after.limited())
        assertEquals(128, worse.fake.contextSize())
        assertEquals("the answer is still written, with what fits", "Yes.", after.text())
        assertTrue(worse.fake.memory.size <= 128)
    }

    @Test
    fun `when no context exists at all any more the model counts as not loaded`() = runTest {
        val test = engine {
            it.cannotCreateFrom = 1024
            it.contextAfterFailedResize = 0
        }
        val events = test.ask(long)
        val error = (events.last() as GenerationEvent.Error).error
        assertEquals(EngineError.LoadFailed(TEST_MODEL_ID, LlamaEngine.TOO_LITTLE_MEMORY), error)
        assertEquals(EngineState.Failed(TEST_MODEL_ID, error), test.engine.state.value)
        assertTrue(test.fake.closed)
    }

    @Test
    fun `forgetting the conversation gives the memory of a grown context back`() = runTest {
        val test = engine()
        test.ask(long)
        assertEquals(1024, test.fake.contextSize())

        test.engine.forget()

        assertEquals("back at the size it was opened with", 256, test.fake.contextSize())
        val short = test.ask(short)
        assertTrue(short.grown().isEmpty())
        assertEquals(256, test.fake.contextSize())
    }

    @Test
    fun `another conversation that fits the starting size gets the grown context's memory back`() = runTest {
        // The fake can remove the end of its memory, as Peacebell can: without a rule of its own
        // the engine would keep what the two conversations have in common, and the grown context with it.
        val test = engine()
        test.ask(long)
        assertEquals(1024, test.fake.contextSize())
        test.fake.clearCalls()

        val other = test.ask(listOf(user("Something else altogether.")))

        assertEquals("back at the size it was opened with", 256, test.fake.contextSize())
        assertEquals(1, test.fake.callsOf<FakeNativeLlama.Call.ClearMemory>().size)
        assertTrue(other.grown().isEmpty() && other.limited().isEmpty() && other.trimmed().isEmpty())
        assertEquals("Yes.", other.text())
        assertEquals("the little the two had in common was read again", 0, other.done().stats.reusedPromptTokens)

        // And it grows again when the long conversation comes back.
        assertEquals(listOf(1024), test.ask(long).grown().map { it.toTokens })
    }

    @Test
    fun `a conversation that only became shorter keeps its context and what was read of it`() = runTest {
        val test = engine()
        // About 200 tokens that fit the starting size, then a long last question that does not.
        val grownBy = short + assistant("Yes.") + user("x".repeat(500))
        test.ask(grownBy)
        assertEquals(1024, test.fake.contextSize())
        test.fake.clearCalls()

        // The last question is edited into a short one: the conversation fits the starting size again,
        // but more than the 96 tokens of this test would have to be read again to give the memory back.
        val edited = test.ask(short + assistant("Yes.") + user("x"))

        assertEquals(1024, test.fake.contextSize())
        assertTrue(test.fake.callsOf<FakeNativeLlama.Call.ClearMemory>().isEmpty())
        assertTrue("what was read is used", edited.done().stats.reusedPromptTokens > 96)
        assertTrue(edited.grown().isEmpty())

        // A lock gives it back, as always.
        test.engine.forget()
        assertEquals(256, test.fake.contextSize())
    }

    @Test
    fun `left to itself the context grows only as far as the conversation can be read again in two minutes`() = runTest {
        // 20 tokens a second by the catalogue: 2,400 in two minutes, which is 2,048 in whole 1,024s.
        val test = engine(small.copy(readingTimes = ReadingTimes.flat(20.0)))
        val events = test.ask(talk(18, size = 100), limit = null)
        assertEquals(listOf(2048), events.grown().map { it.toTokens })
        assertEquals("beyond that the oldest turns go, although the model could hold more", 1, events.trimmed().size)
        assertTrue(events.limited().isEmpty())

        // A model whose speed nobody knows stays at the size it starts with.
        val unknown = engine()
        val stays = unknown.ask(long, limit = null)
        assertTrue(stays.grown().isEmpty())
        assertEquals(1, stays.trimmed().size)

        // What this device was measured to take scales the catalogue's times: twice as long reads 1,024 in two minutes.
        val measured = engine(small.copy(readingTimes = ReadingTimes.flat(20.0))) { it.nanosPerToken = 100_000_000 }
        measured.ask(long, limit = null) // Reads some hundred tokens at ten a second.
        measured.engine.forget()
        val slow = measured.ask(talk(18, size = 100), limit = null)
        assertEquals(listOf(1024), slow.grown().map { it.toTokens })

        // A model of which the catalogue knows no times reads by the speed it was seen to read at, as a constant.
        val unknownMeasured = engine { it.nanosPerToken = 50_000_000 }
        unknownMeasured.ask(long) // A size of the user's own, so that some 830 tokens are read at 20 a second.
        unknownMeasured.engine.forget()
        assertEquals("20 a second, measured", listOf(2048), unknownMeasured.ask(talk(18, size = 100), limit = null).grown().map { it.toTokens })
    }

    /** What the fake takes to read [tokens] from nothing when a token after `d` others costs [first] plus `d` times [perDepth] nanoseconds. */
    private fun fakeSeconds(tokens: Int, first: Long, perDepth: Long): Double =
        (first * tokens + perDepth * tokens.toLong() * (tokens - 1) / 2) / 1e9

    /**
     * A model whose catalogue curve is the fake's own times at 10 ms for a token with nothing
     * read and 0.02 ms more for every token already read, with a point every 64 tokens: two
     * minutes then read 3,000 tokens (2,048 in whole 1,024s), where the speed of a short
     * prompt, 100 tokens a second, would promise 12,000.
     */
    private val deepening: ModelSpec = small.copy(
        readingTimes = ReadingTimes((64..4096 step 64).map { ReadingTimes.Point(it, fakeSeconds(it, FIRST_TOKEN_NANOS, DEPTH_NANOS)) }),
    )

    @Test
    fun `a part of a prompt that leaves a long conversation in memory is read in the model's deep chunk`() = runTest {
        val test = engine()
        test.ask(short)
        val prompt = test.fake.evaluations.filter { it.to - it.from > 1 }
        assertTrue(prompt.isNotEmpty())
        assertTrue("a short prompt keeps the engine's chunk of 32", prompt.all { it.chunkSize == 32 })

        // Some 3,800 tokens, read in parts between checkpoints: those that end below 1,024 in 32s, the others in the model's 128s.
        test.fake.clearCalls()
        test.ask(talk(18, size = 100), limit = 4096)
        val long = test.fake.evaluations.filter { it.to - it.from > 1 }
        assertTrue("the conversation reaches beyond 1,024 tokens", long.any { it.to >= 1024 })
        for (part in long) assertEquals("the part from ${part.from} to ${part.to}", if (part.to >= 1024) 128 else 32, part.chunkSize)

        // A model with attention only reads the same in the session's whole batch.
        val wide = engine(small.copy(deepReadChunk = 256))
        wide.ask(talk(18, size = 100), limit = 4096)
        val deep = wide.fake.evaluations.filter { it.to - it.from > 1 && it.to >= 1024 }
        assertTrue(deep.isNotEmpty() && deep.all { it.chunkSize == 256 })

        assertEquals("the threshold is the engine's setting, 1,024 unless named", 1024, LlamaEngine.Settings().deepReadTokens)
    }

    @Test
    fun `the device's reading is a factor over the catalogue's curve, measured at whatever depth`() = runTest {
        // A device that reads as the catalogue says: the limit is the curve's, also after a prompt read deep in a conversation.
        val same = engine(deepening) {
            it.nanosPerToken = FIRST_TOKEN_NANOS
            it.nanosPerTokenPerDepth = DEPTH_NANOS
        }
        assertEquals(listOf(1024), same.ask(long, limit = null).grown().map { it.toTokens })
        val deeper = long + assistant("Yes.") + user("x".repeat(300))
        val second = same.ask(deeper, limit = null)
        assertTrue("some 300 tokens were read after some 830", second.done().stats.evaluatedPromptTokens >= 256)
        same.engine.forget()
        assertEquals("tokens read deep in cost more, and the curve knows it", listOf(2048), same.ask(talk(18, size = 100), limit = null).grown().map { it.toTokens })

        // A device that takes twice as long for everything: 60 s of the curve, 2,000 tokens.
        val slower = engine(deepening) {
            it.nanosPerToken = 2 * FIRST_TOKEN_NANOS
            it.nanosPerTokenPerDepth = 2 * DEPTH_NANOS
        }
        slower.ask(long, limit = null)
        slower.engine.forget()
        assertEquals(listOf(1024), slower.ask(talk(18, size = 100), limit = null).grown().map { it.toTokens })

        // Ten times as long is held to four: 30 s of the curve, 1,302 tokens. At ten it would be 704, below one 1,024.
        val slowest = engine(deepening) {
            it.nanosPerToken = 10 * FIRST_TOKEN_NANOS
            it.nanosPerTokenPerDepth = 10 * DEPTH_NANOS
        }
        slowest.ask(long, limit = null)
        slowest.engine.forget()
        assertEquals(listOf(1024), slowest.ask(talk(18, size = 100), limit = null).grown().map { it.toTokens })
    }

    @Test
    fun `a context that has grown by itself is not cut back when the model is then seen to read more slowly`() = runTest {
        // By the catalogue 20 tokens a second, so up to 2,048 tokens; in fact it reads 10 a second, which would be 1,024.
        val test = engine(small.copy(readingTimes = ReadingTimes.flat(20.0))) { it.nanosPerToken = 100_000_000 }
        val conversation = talk(9)
        val first = test.ask(conversation, limit = null)
        assertEquals(listOf(2048), first.grown().map { it.toTokens })
        assertTrue(first.trimmed().isEmpty())
        val held = test.fake.memory.size
        assertTrue("more than the slower limit would hold", held > 1024)

        // The conversation goes on: it keeps what it was given, and what was read stays read.
        test.fake.clearCalls()
        val second = test.ask(conversation + assistant("Yes.") + user("And then?"), limit = null)
        assertTrue(second.trimmed().isEmpty() && second.limited().isEmpty())
        assertTrue(test.fake.callsOf<FakeNativeLlama.Call.ClearMemory>().isEmpty())
        assertTrue("only the new turn was read", second.done().stats.reusedPromptTokens >= held - 8)
        assertEquals(2048, test.fake.contextSize())

        // Once the conversation is left, the speed that was measured counts.
        test.engine.forget()
        val next = test.ask(talk(18, size = 100), limit = null)
        assertEquals(listOf(1024), next.grown().map { it.toTokens })
        assertEquals(1, next.trimmed().size)

        // A size the user names holds at once, also below what the context has.
        val fixed = engine()
        fixed.ask(talk(9), limit = 2048)
        assertEquals(2048, fixed.fake.contextSize())
        val smaller = fixed.ask(talk(9) + assistant("Yes.") + user("And then?"), limit = 1024)
        assertEquals("the conversation is fitted to the smaller size", 1, smaller.trimmed().size)
        assertTrue(fixed.fake.memory.size <= 1024)
    }

    @Test
    fun `the smaller of the catalogue's maximum and the file's own counts`() = runTest {
        // The file would allow 4,096; the catalogue says 512 (as for a model whose card says less than its file).
        val byCatalogue = engine(small.copy(maxContextLength = 512)) { it.trainedContextTokens = 4096 }
        val held = byCatalogue.ask(long)
        assertEquals(listOf(512), held.grown().map { it.toTokens })
        assertEquals(1, held.trimmed().size)
        assertTrue("that is the model's limit, not a refusal to speak of", held.limited().isEmpty())
        assertTrue(byCatalogue.fake.resizes.all { it.first <= 512 })

        // And the other way round: the file allows less than the catalogue believes.
        val byFile = engine(small.copy(maxContextLength = 4096)) { it.trainedContextTokens = 512 }
        val alsoHeld = byFile.ask(long)
        assertEquals(listOf(512), alsoHeld.grown().map { it.toTokens })
        assertTrue("nothing was asked of the file that it cannot do", byFile.fake.resizes.all { it.first <= 512 })
    }

    @Test
    fun `reading the conversation again after a growth stands aside, can be cancelled and ends with a lock like any reading`() = runTest {
        // The memory cannot be carried over, so after the growth the whole conversation is read again.
        val longer = short + assistant("Yes.") + talk(3)

        // It stands aside while the gate is closed, and goes on where it was.
        val waiting = engine { it.carriesMemoryOnResize = false }
        waiting.ask(short)
        var closedAt = -1
        waiting.fake.afterChunk = { inMemory ->
            if (closedAt < 0 && waiting.fake.contextSize() > 256 && inMemory >= 64) {
                closedAt = inMemory
                gate.close()
            }
        }
        val inMemoryWhileWaiting = HashSet<Int>()
        whileWaiting = {
            inMemoryWhileWaiting += waiting.fake.memory.size
            if (waits == 30) gate.open()
        }
        val events = waiting.ask(longer)
        assertEquals(listOf(GenerationEvent.ContextGrown(256, 1024, rereading = true)), events.grown())
        assertEquals(30, waits)
        assertTrue("the gate closed in the middle of the reading", closedAt in 64 until 600)
        assertEquals("nothing was read while it stood aside", 1, inMemoryWhileWaiting.size)
        assertTrue("and it stood aside within a chunk of the gate closing", inMemoryWhileWaiting.single() - closedAt <= 32)
        assertEquals("Yes.", events.text())

        // A collector that goes away during it stops the reading; the larger context and what was read are kept and built on.
        val cancelled = engine { it.carriesMemoryOnResize = false }
        cancelled.ask(short)
        lateinit var collecting: Job
        var stoppedAt = -1
        cancelled.fake.afterChunk = { inMemory ->
            if (stoppedAt < 0 && cancelled.fake.contextSize() > 256 && inMemory >= 64) {
                stoppedAt = inMemory
                collecting.cancel()
            }
        }
        collecting = launch { cancelled.engine.generate(request(*longer.toTypedArray()).copy(maxContextTokens = 2048)).collect { } }
        collecting.join()
        assertTrue(collecting.isCancelled)
        assertEquals(stoppedAt, cancelled.fake.memory.size)
        assertEquals(1024, cancelled.fake.contextSize())
        cancelled.fake.afterChunk = {}
        val after = cancelled.ask(longer)
        assertTrue("no second growth", after.grown().isEmpty())
        assertEquals(stoppedAt, after.done().stats.reusedPromptTokens)
        assertEquals("Yes.", after.text())

        // A lock during it ends the answer and gives the grown context back.
        val locked = engine { it.carriesMemoryOnResize = false }
        locked.ask(short)
        var forgetting: Job? = null
        locked.fake.afterChunk = { inMemory ->
            if (forgetting == null && locked.fake.contextSize() > 256 && inMemory >= 64) {
                forgetting = launch(start = CoroutineStart.UNDISPATCHED) { locked.engine.forget() }
            }
        }
        val ended = locked.ask(longer)
        checkNotNull(forgetting).join()
        assertEquals(FinishReason.CANCELLED, ended.done().finishReason)
        assertEquals(0, locked.fake.memory.size)
        assertEquals("back at the size it was opened with", 256, locked.fake.contextSize())
    }

    @Test
    fun `a model is opened with less when a quarter of the device's memory does not hold its starting size, and not at all below the least`() = runTest {
        // The catalogue says 1,000 bytes for a token of this model's context.
        val model = testModel(contextLength = 4096).copy(maxContextLength = 8192, contextBytesPerToken = 1000)
        fun factory() = FakeNativeLlamaFactory { spec -> FakeNativeLlama(contextTokens = spec.contextLength).also { it.registerChatMl() } }
        fun engineOver(factory: FakeNativeLlamaFactory) =
            LlamaEngine(factory, StandardTestDispatcher(testScheduler), newSeed = { TEST_SEED }, memoryGauge = { device })

        // A quarter of 6,000,000 bytes is 1,500,000: the tokens of 4,096 and of 2,048 do not fit it, those of 1,024 do.
        // All of it is available, so only the quarter decides.
        device = DeviceMemory(totalBytes = 6_000_000, availableBytes = 6_000_000, thresholdBytes = 0, low = false)
        val tight = factory()
        val engine = engineOver(tight)
        engine.load(model)
        assertEquals("only the size that fits is asked for, and the native side is given no budget of its own to refuse it by", listOf(1024 to Long.MAX_VALUE), tight.openings)
        assertEquals(EngineState.Ready(model.id), engine.state.value)
        assertEquals(1024, tight.last.contextSize())

        // Not even that (a quarter of 2,000,000 bytes is 500,000): the model is not loaded, nothing is asked of the
        // native side, and the failure says why.
        device = DeviceMemory(totalBytes = 2_000_000, availableBytes = 2_000_000, thresholdBytes = 0, low = false)
        val none = factory()
        val failed = engineOver(none)
        failed.load(model)
        assertTrue(none.openings.isEmpty())
        assertEquals(EngineState.Failed(model.id, EngineError.LoadFailed(model.id, LlamaEngine.TOO_LITTLE_MEMORY)), failed.state.value)
        assertFalse(none.opened.isNotEmpty())

        // A native side that refuses a size all the same is asked for half, and half again.
        device = DeviceMemory.PLENTY
        val refusing = factory().apply { largestOpening = 1024 }
        val halved = engineOver(refusing)
        halved.load(model)
        assertEquals(listOf(4096, 2048, 1024), refusing.openings.map { it.first })
        assertEquals(EngineState.Ready(model.id), halved.state.value)
        assertEquals(1024, refusing.last.contextSize())

        // And one that refuses even the least is not loaded.
        val never = factory().apply { largestOpening = 512 }
        val refused = engineOver(never)
        refused.load(model)
        assertEquals(listOf(4096, 2048, 1024), never.openings.map { it.first })
        assertEquals(EngineState.Failed(model.id, EngineError.LoadFailed(model.id, LlamaEngine.TOO_LITTLE_MEMORY)), refused.state.value)
    }

    @Test
    fun `what is available at the moment neither shrinks nor refuses an opening, and growth still keeps the floor`() = runTest {
        // A large device that is busy: almost nothing available, far below the floor of a fifth, and memory called low.
        // A quarter of its memory holds the 256 tokens of the starting size many times over.
        device = DeviceMemory(totalBytes = 1_000_000_000, availableBytes = 1_000, thresholdBytes = 0, low = true)
        val test = engine { it.bytesPerToken = 1000 }

        assertEquals("opened at its full starting size", 256, test.fake.contextSize())
        assertEquals(listOf(256 to Long.MAX_VALUE), test.factory.openings)
        assertEquals(EngineState.Ready(small.id), test.engine.state.value)

        // Growing is another matter: below the floor nothing is added.
        val events = test.ask(long)
        assertTrue(events.grown().isEmpty() && test.fake.resizes.isEmpty())
        assertEquals(listOf(GenerationEvent.ContextLimited(256, GenerationEvent.ContextLimited.Reason.MEMORY)), events.limited())
        assertEquals("Yes.", events.text())
    }
}

/** What a token costs the fake with nothing read, in nanoseconds: 100 tokens a second for a short prompt. */
private const val FIRST_TOKEN_NANOS: Long = 10_000_000

/** What every token already read adds to the cost of the next, in nanoseconds. */
private const val DEPTH_NANOS: Long = 20_000
