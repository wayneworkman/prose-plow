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
import us.theworkmans.proseplow.core.engine.MemoryRestoreResult.NotRestored
import us.theworkmans.proseplow.core.engine.MemorySaveResult.NotSaved
import us.theworkmans.proseplow.core.model.ModelCatalog
import us.theworkmans.proseplow.core.model.ModelSpec
import us.theworkmans.proseplow.core.model.ReadingTimes
import us.theworkmans.proseplow.core.prompt.PlainChatMlFormat
import us.theworkmans.proseplow.core.prompt.PromptInput
import us.theworkmans.proseplow.core.prompt.PromptMessage

/**
 * The model's memory saved into a vault and read back: when the engine saves and when it does
 * not, what a restore does to the memory, the context and the checkpoints, that every failure of
 * a restore ends with an empty memory, a deleted image and a full read, and the invariant that a
 * saved image is only ever the beginning of the memory the engine holds (or waits for a restore).
 *
 * The model reads 10 tokens a second by its curve, so 200 tokens are the 20 seconds below which
 * nothing is saved, and 100 new tokens the 10 seconds of the rule for leaving the screen. The
 * fake counts one token for every character of a plain-text ChatML prompt.
 */
class LlamaEngineSavedMemoryTest {

    private val model: ModelSpec = testModel(contextLength = 2000).copy(readingTimes = ReadingTimes.flat(10.0))

    private val vault = FakeMemoryVault()

    private fun prompt(vararg messages: PromptMessage, systemPrompt: String? = null): String =
        PlainChatMlFormat.render(PromptInput(messages.toList(), systemPrompt = systemPrompt)).text()

    /** About 330 tokens, 33 seconds of reading: worth saving. */
    private val first = arrayOf(user("Q".repeat(300)))

    /** The first exchange and a short question: about 50 tokens more, 5 seconds. */
    private val second = arrayOf(*first, assistant("Fine."), user("And then?"))

    private suspend fun TestScope.engine(spec: ModelSpec = model, configure: (FakeNativeLlama) -> Unit = {}): TestEngine =
        loadedEngine(spec, configure = configure).also { test -> vault.onCommit = { test.fake.memory.toList() } }

    private suspend fun TestEngine.ask(vararg messages: PromptMessage, key: String? = A, reply: String = "Fine.", systemPrompt: String? = null): List<GenerationEvent> {
        fake.enqueueReply(reply)
        return engine.generate(request(*messages, systemPrompt = systemPrompt).copy(conversationKey = key)).toList()
    }

    private suspend fun TestEngine.save(key: String = A, trigger: SaveTrigger = SaveTrigger.LOCK) = engine.saveMemory(vault, key, trigger)

    private suspend fun TestEngine.restore(key: String = A) = engine.restoreMemory(vault, key)

    /** The tokens the vault's image is of, as noted at its commit. */
    @Suppress("UNCHECKED_CAST") // The note is always what onCommit returned: a list of tokens.
    private fun savedTokens(): List<Int>? = if (vault.image == null) null else vault.note as List<Int>

    /**
     * Design section 6: an image exists only while its tokens are the beginning of the memory, or
     * while it waits for the first request after a restore could be asked for ([waiting]).
     */
    private fun TestEngine.assertInvariant(step: String, waiting: Boolean = false) {
        val saved = savedTokens() ?: return
        if (waiting) return
        val memory = fake.memory
        assertTrue("$step: the image is not the beginning of the memory", memory.size >= saved.size && memory.subList(0, saved.size) == saved)
    }

    private fun assertDeleted(step: String) {
        assertNull("$step: the image should have been deleted", vault.image)
    }

    // --- Save, restore, answer ---

    /**
     * Catches: a restore that leaves the memory other than it was saved, a first request after a
     * restore that clears or reads more than what is new (ENG-07 not applied to restored memory),
     * and a restore that skips the final token read before sampling.
     */
    @Test
    fun `save, forget, restore and answer reads only what is new, as without the lock`() = runTest {
        // Without a lock in between.
        val control = engine()
        control.ask(*first)
        control.fake.clearCalls()
        val controlEvents = control.ask(*second, reply = "Then this.")

        val test = engine()
        test.ask(*first)
        val held = test.fake.memory.toList()
        val saved = test.save()
        assertEquals(held.size, (saved as MemorySaveResult.Saved).tokens)
        test.engine.forgetKeepingSavedMemory()
        assertTrue("the lock leaves nothing in memory", test.fake.memory.isEmpty())
        assertNotNull("the image is kept for the next unlock", vault.image)

        assertEquals(MemoryRestoreResult.Restored(held.size), test.restore())
        assertEquals(held, test.fake.memory)
        test.fake.clearCalls()
        val events = test.ask(*second, reply = "Then this.")

        val newPrompt = prompt(*second)
        assertEquals(held.size, events.done().stats.reusedPromptTokens)
        assertEquals(newPrompt.substring(held.size), test.fake.render(test.fake.promptEvaluations().flatten()))
        assertTrue("nothing was removed from memory", test.fake.calls.none { it is Call.ClearMemory || it is Call.RewindTo || it is Call.RestoreCheckpoint })
        // The model saw the same memory and the same sampler, so the reply is the one it gives without a lock.
        assertEquals(control.fake.memory, test.fake.memory)
        assertEquals(control.fake.sampler, test.fake.sampler)
        assertEquals(control.fake.promptEvaluations(), test.fake.promptEvaluations())
        assertEquals(controlEvents.done().text, events.done().text)
        assertEquals(controlEvents.done().stats, events.done().stats)
        test.assertInvariant("after the answer")
    }

    /**
     * Catches: checkpoints left out of the image, restored under handles the engine does not
     * record, or at the wrong lengths, which would make "answer again" on a model that cannot
     * cut its memory clear it and read everything.
     */
    @Test
    fun `checkpoints come back and take a step back as they did before the lock`() = runTest {
        val cannotCut: (FakeNativeLlama) -> Unit = { it.refuseRewind = true }
        val control = engine(configure = cannotCut)
        control.ask(*second, systemPrompt = "Be brief.")
        val positions = control.fake.checkpoints.values.sorted()
        assertTrue("the conversation has checkpoints to save", positions.size >= 2)
        control.fake.clearCalls()
        control.ask(*second, systemPrompt = "Be brief.", reply = "Again.")

        val test = engine(configure = cannotCut)
        test.ask(*second, systemPrompt = "Be brief.")
        test.save()
        test.engine.forgetKeepingSavedMemory()
        assertTrue(test.fake.checkpoints.isEmpty())
        assertTrue(test.restore() is MemoryRestoreResult.Restored)
        assertEquals(positions, test.fake.checkpoints.values.sorted())
        val imported = test.fake.checkpoints.keys.toSet()

        test.fake.clearCalls()
        test.ask(*second, systemPrompt = "Be brief.", reply = "Again.")
        val restoredHandle = test.fake.callsOf<Call.RestoreCheckpoint>().single().checkpoint
        assertTrue("the checkpoint used is one the import brought back", restoredHandle in imported)
        assertTrue("the memory was not cleared", test.fake.callsOf<Call.ClearMemory>().isEmpty())
        assertEquals(control.fake.promptEvaluations(), test.fake.promptEvaluations())
        assertEquals(control.fake.memory, test.fake.memory)
    }

    // --- Every failure of a restore ---

    /** An image of [first] with its header and its tokens, made by an engine of its own. */
    private suspend fun TestScope.imageOfFirst(): Triple<SavedMemory, ByteArray, Any?> {
        val source = engine()
        source.ask(*first)
        source.save()
        return Triple(vault.image!!, vault.storedBytes!!, vault.note)
    }

    /**
     * Catches any failure path that leaves part of a memory, keeps the image, or leaves the next
     * request reusing something: every one must end with an empty memory, no checkpoint, the
     * image deleted, and the conversation read again from its first token.
     */
    @Test
    fun `every failure of a restore leaves an empty memory, no image and a full read`() = runTest {
        val (header, bytes, note) = imageOfFirst()
        val pieces = ((header.imageBytes + 40 + NativeLlama.MEMORY_PIECE_BYTES_MIN - 1) / NativeLlama.MEMORY_PIECE_BYTES_MIN).toInt()
        assertTrue("the image spans several pieces", pieces >= 3)

        val readAgain = MemoryRestoreState.ReadAgain(model.id)
        class Case(val name: String, val reason: NotRestored.Reason, val state: MemoryRestoreState, val arrange: TestScope.(TestEngine) -> Unit)
        val cases = ArrayList<Case>()
        cases += Case("the vault cannot check its image", NotRestored.Reason.FAILED, MemoryRestoreState.Idle) { vault.savedFails = true }
        cases += Case("the image is another model's", NotRestored.Reason.OTHER_MODEL, MemoryRestoreState.Idle) {
            vault.put(header.copy(modelId = "another-model"), bytes, note)
        }
        cases += Case("the header says more tokens than the image holds", NotRestored.Reason.FAILED, readAgain) {
            vault.reportAs = { it.copy(tokenCount = it.tokenCount + 1) }
        }
        cases += Case("the reader reports another header", NotRestored.Reason.FAILED, readAgain) { test ->
            var calls = 0
            vault.reportAs = { if (++calls > 1) it.copy(contextTokens = it.contextTokens + 1) else it }
        }
        cases += Case("the vault read the image again and refused it", NotRestored.Reason.FAILED, readAgain) { vault.restoreRefused = true }
        for (piece in 0 until pieces) {
            cases += Case("the native side refuses piece $piece", NotRestored.Reason.FAILED, readAgain) { it.fake.importFailsAtPiece = piece }
            cases += Case("the vault stops at piece $piece", NotRestored.Reason.STOPPED, readAgain) { vault.readStopsAtPiece = piece }
        }

        for (case in cases) {
            vault.savedFails = false
            vault.restoreRefused = false
            vault.reportAs = null
            vault.readStopsAtPiece = null
            vault.beforeRead = {}
            vault.put(header, bytes, note)
            val test = engine()
            case.arrange(this, test)
            val deletesBefore = vault.deletes

            val result = test.restore()
            runCurrent()

            assertEquals(case.name, NotRestored(case.reason), result)
            assertTrue("${case.name}: the memory is empty", test.fake.memory.isEmpty())
            assertTrue("${case.name}: no checkpoint is held", test.fake.checkpoints.isEmpty())
            assertTrue("${case.name}: the vault was told to delete", vault.deletes > deletesBefore)
            assertDeleted(case.name)
            assertEquals(case.name, case.state, test.engine.memoryRestore.value)

            val events = test.ask(*second)
            assertEquals("${case.name}: the conversation is read again", 0, events.done().stats.reusedPromptTokens)
        }
    }

    /** Catches: a restore that imports into a context too small, or grows past what the device's memory allows. */
    @Test
    fun `a restore sizes the context within the device's memory, and is refused when that cannot hold it`() = runTest {
        var device = DeviceMemory(totalBytes = 40_000_000, availableBytes = 40_000_000, thresholdBytes = 0, low = false)
        val small = testModel(contextLength = 256, maxNewTokens = 32).copy(maxContextLength = 4096, readingTimes = ReadingTimes.flat(10.0))
        suspend fun sized(): TestEngine {
            lateinit var created: TestEngine
            val factory = FakeNativeLlamaFactory { spec ->
                FakeNativeLlama(contextTokens = spec.contextLength).also { fake ->
                    fake.trainedContextTokens = spec.maxContextLength
                    fake.bytesPerToken = 1000
                    created.chatMl = fake.registerChatMl()
                }
            }
            val engine = LlamaEngine(
                factory = factory,
                nativeDispatcher = StandardTestDispatcher(testScheduler),
                newSeed = { TEST_SEED },
                memoryGauge = { device },
            )
            created = TestEngine(engine, factory)
            engine.load(small)
            vault.onCommit = { created.fake.memory.toList() }
            return created
        }
        // About 830 tokens: the step of 1,024, which the automatic limit (1,200 tokens in 120 seconds) allows.
        val long = (1..4).flatMap { listOf(user("Q$it " + "q".repeat(60)), assistant("A$it " + "a".repeat(60))) } + user("The latest question.")

        val test = sized()
        test.ask(*long.toTypedArray())
        assertEquals(1024, test.fake.contextSize())
        val held = test.fake.memory.toList()
        test.save()
        assertEquals(1024, vault.image!!.contextTokens)
        test.engine.forgetKeepingSavedMemory()
        assertEquals("forgetting gives the grown context back", 256, test.fake.contextSize())

        assertEquals(MemoryRestoreResult.Restored(held.size), test.restore())
        assertEquals("the saved size", 1024, test.fake.contextSize())
        val (_, budget) = test.fake.resizes.last()
        val policy = ContextPolicy()
        assertTrue("the step was asked for within the policy's budget", budget <= policy.budgetBytes(device, 256L * 1000))
        assertEquals(held, test.fake.memory)

        // Too little memory available for a context that holds the image: refused, deleted, empty.
        test.engine.forgetKeepingSavedMemory()
        val bytes = vault.storedBytes!!
        val header = vault.image!!
        val note = vault.note
        val refused = sized()
        vault.put(header, bytes, note)
        val restoresBegun = vault.restoresBegun
        device = device.copy(availableBytes = policy.floorBytes(device) + 500_000)
        assertEquals(NotRestored(NotRestored.Reason.CONTEXT_TOO_SMALL), refused.restore())
        assertTrue(refused.fake.contextSize() < held.size)
        assertTrue(refused.fake.memory.isEmpty())
        assertEquals("nothing was read", restoresBegun, vault.restoresBegun)
        assertDeleted("a context too small")
    }

    /** Catches a growth of the context that comes back with an empty memory and leaves the image of what was lost. */
    @Test
    fun `a context that grows without carrying the memory deletes the image`() = runTest {
        val small = testModel(contextLength = 256, maxNewTokens = 32).copy(maxContextLength = 4096, readingTimes = ReadingTimes.flat(10.0))
        val factory = FakeNativeLlamaFactory { spec ->
            FakeNativeLlama(contextTokens = spec.contextLength).also { fake ->
                fake.trainedContextTokens = spec.maxContextLength
                fake.carriesMemoryOnResize = false
                fake.registerChatMl()
            }
        }
        val engine = LlamaEngine(factory = factory, nativeDispatcher = StandardTestDispatcher(testScheduler), newSeed = { TEST_SEED })
        engine.load(small)
        val test = TestEngine(engine, factory)
        vault.onCommit = { test.fake.memory.toList() }

        // About 215 tokens with the reply: worth saving, and within the 256 the context starts with.
        val short = arrayOf(user("Q " + "q".repeat(158)))
        test.ask(*short)
        assertTrue(test.save() is MemorySaveResult.Saved)
        val longer = arrayOf(*short, assistant("Fine."), user("R".repeat(400)))
        val events = test.ask(*longer)
        assertTrue("the context grew and the memory was read again", events.filterIsInstance<GenerationEvent.ContextGrown>().single().rereading)
        assertDeleted("a growth that did not carry the memory")
        test.assertInvariant("after the growth")
    }

    // --- When nothing is saved ---

    /** Catches a save that ignores any one of its rules: each of these must leave the vault untouched. */
    @Test
    fun `no save of an empty memory, below twenty seconds, of a marked memory, or of another conversation`() = runTest {
        val test = engine()
        assertEquals(NotSaved(NotSaved.Reason.EMPTY), test.save())

        test.ask(user("Hi"))
        assertTrue("well below 20 seconds", prompt(user("Hi")).length < 100)
        assertEquals(NotSaved(NotSaved.Reason.NOT_WORTH_IT), test.save())

        test.ask(*first)
        assertEquals(NotSaved(NotSaved.Reason.OTHER_CONVERSATION), test.save(key = B))
        test.engine.markNotToBeSaved()
        assertEquals(NotSaved(NotSaved.Reason.MARKED), test.save())
        // A request that only appends does not rebuild the memory from nothing: the mark holds.
        test.ask(*second)
        assertEquals(NotSaved(NotSaved.Reason.MARKED), test.save())
        assertEquals(0, vault.savesBegun)

        // Emptied after the mark, and built again from nothing: the mark is lifted.
        test.engine.forget()
        test.ask(*first)
        assertTrue(test.save() is MemorySaveResult.Saved)

        // A request without a key is no conversation the vault can hold.
        test.ask(*second, key = null)
        assertEquals(NotSaved(NotSaved.Reason.OTHER_CONVERSATION), test.save())

        val unknown = engine(testModel(contextLength = 2000))
        unknown.ask(*first)
        assertEquals("nobody knows how long reading takes", NotSaved(NotSaved.Reason.NOT_WORTH_IT), unknown.save())
    }

    /** Catches a mark lifted by the emptying of a request that was made before it, which may still hold the forgotten messages. */
    @Test
    fun `a mark made while a reply runs outlives that reply's own clearing`() = runTest {
        val test = engine { it.refuseRewind = true }
        test.ask(*first)
        var marked = false
        test.fake.beforeCall = { call ->
            if (call is Call.Tokenize && !marked) {
                marked = true
                test.engine.markNotToBeSaved()
            }
        }
        // Another system prompt shares only the first marker, and the model cannot cut its memory
        // back to it: the memory is cleared during this request, after the mark.
        test.ask(*first, systemPrompt = "Other.")
        test.fake.beforeCall = {}
        assertTrue("the request did clear the memory", test.fake.calls.any { it is Call.ClearMemory })
        assertEquals(NotSaved(NotSaved.Reason.MARKED), test.save())
    }

    /**
     * Catches the rule of decision 5 applied by name instead of by numbers, or not at all:
     * Boris-2 reads its whole context in less than 20 seconds by its catalogue curve.
     */
    @Test
    fun `nothing is saved or restored for a model the twenty-second rule excludes`() = runTest {
        val boris = ModelCatalog.BORIS_2_INSTRUCT
        assertTrue("by its numbers", boris.readingTimes.seconds(boris.maxContextLength) < 20)
        val test = engine(boris)
        test.fake.enqueueReply("Fine.")
        test.engine.generate(request(user("Q".repeat(1500)), modelId = boris.id).copy(conversationKey = A)).toList()
        assertTrue(test.fake.memory.size > 1500)
        assertEquals(NotSaved(NotSaved.Reason.NOT_WORTH_IT), test.save())
        assertEquals(0, vault.savesBegun)

        // An image made where the same model read slowly enough is not restored where it does not.
        val (header, bytes, note) = imageOfFirst()
        vault.put(header, bytes, note)
        val fast = engine(model.copy(readingTimes = ReadingTimes.flat(1000.0)))
        assertEquals(NotRestored(NotRestored.Reason.NOT_WORTH_IT), fast.restore())
        assertTrue(fast.fake.memory.isEmpty())
        assertDeleted("a model that reads fast")
    }

    /** Catches the rule for leaving the screen not applied, applied to the lock, or counted from nothing instead of from the last save. */
    @Test
    fun `leaving the screen saves only when more than ten seconds are new, the lock regardless`() = runTest {
        val test = engine()
        test.ask(*first)
        assertTrue("33 seconds are new", test.save(trigger = SaveTrigger.LEFT_SCREEN) is MemorySaveResult.Saved)

        test.ask(*second)
        assertEquals("5 seconds are new", NotSaved(NotSaved.Reason.LITTLE_NEW), test.save(trigger = SaveTrigger.LEFT_SCREEN))
        assertEquals(1, vault.commits)
        assertTrue("the lock's save goes ahead", test.save(trigger = SaveTrigger.LOCK) is MemorySaveResult.Saved)

        val third = arrayOf(*second, assistant("Fine."), user("R".repeat(150)))
        test.ask(*third)
        assertTrue("about 19 seconds are new", test.save(trigger = SaveTrigger.LEFT_SCREEN) is MemorySaveResult.Saved)
        assertEquals(3, vault.commits)
    }

    // --- A save gives way ---

    /** Catches a save that holds a request back, commits a partial image, or touches the memory. */
    @Test
    fun `a request that arrives during a save stops it, and finds the memory untouched`() = runTest {
        val test = engine()
        test.ask(*first)
        val held = test.fake.memory.toList()
        var answer: List<GenerationEvent>? = null
        test.fake.enqueueReply("Fine.")
        vault.beforeWrite = { piece ->
            if (piece == 1) {
                backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    answer = test.engine.generate(request(*second).copy(conversationKey = A)).toList()
                }
            }
        }
        test.fake.clearCalls()

        assertEquals(NotSaved(NotSaved.Reason.STOPPED), test.save())
        assertEquals(0, vault.commits)
        assertNull(vault.image)
        runCurrent()
        assertEquals("the request reused the whole memory", held.size, answer!!.done().stats.reusedPromptTokens)
        assertTrue(test.fake.calls.none { it is Call.ClearMemory || it is Call.RewindTo })
    }

    /** Catches a save on leaving the screen that waits for, or runs into, a reply in progress. */
    @Test
    fun `leaving the screen while a reply runs saves nothing`() = runTest {
        val test = engine()
        test.ask(*first)
        var result: MemorySaveResult? = null
        test.fake.afterChunk = {
            if (result == null) {
                backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { result = test.save(trigger = SaveTrigger.LEFT_SCREEN) }
            }
        }
        test.ask(*second)
        assertEquals(NotSaved(NotSaved.Reason.BUSY), result)
        assertEquals(0, vault.savesBegun)
    }

    /** Catches a failed save that commits, or leaves the memory changed. */
    @Test
    fun `a save that fails commits nothing and leaves the memory as it was`() = runTest {
        val test = engine()
        test.ask(*first)
        val held = test.fake.memory.toList()
        test.fake.exportFailsAtPiece = 1
        assertEquals(NotSaved(NotSaved.Reason.FAILED), test.save())
        test.fake.exportFailsAtPiece = null
        vault.writeFailsAtPiece = 2
        assertEquals(NotSaved(NotSaved.Reason.FAILED), test.save())
        assertEquals(0, vault.commits)
        assertEquals(held, test.fake.memory)
    }

    // --- The invariant ---

    /**
     * Catches any operation that makes the memory shorter than the image, or another
     * conversation's, without deleting the image: the invariant is checked after every step.
     */
    @Test
    fun `the image is always the beginning of the memory, through every kind of change`() = runTest {
        val test = engine()
        test.ask(*first)
        test.save()
        test.assertInvariant("saved")

        test.ask(*first, reply = "Another answer.")
        assertDeleted("answered again")
        test.assertInvariant("answered again")

        test.ask(*second)
        test.save()
        test.ask(*second, assistant("Fine."), user("More?"))
        assertNotNull("a next turn only adds", vault.image)
        test.assertInvariant("a next turn")

        test.ask(first[0], assistant("Fine."), user("And now?"))
        assertDeleted("an edit")
        test.assertInvariant("an edit")

        test.ask(*second)
        test.save()
        test.ask(*second, systemPrompt = "Another system prompt.")
        assertDeleted("another system prompt")

        test.ask(*second)
        test.save()
        // A prompt that only adds to the memory: nothing but the key says it is not this conversation.
        test.ask(*second, assistant("Fine."), user("More?"), key = B)
        assertDeleted("another conversation's request")
        test.assertInvariant("another conversation's request")

        test.ask(*first, key = B)
        assertTrue(test.save(key = B) is MemorySaveResult.Saved)
        test.engine.forget()
        assertDeleted("forget")
        test.assertInvariant("forget")

        test.ask(*first)
        test.save()
        test.engine.forgetKeepingSavedMemory()
        assertNotNull("kept at a lock", vault.image)
        test.assertInvariant("the lock", waiting = true)

        assertTrue(test.restore() is MemoryRestoreResult.Restored)
        test.assertInvariant("restored")
        test.ask(*second)
        assertNotNull(vault.image)
        test.assertInvariant("the first answer after the restore")

        test.fake.failRestore = true
        test.fake.refuseRewind = true
        test.fake.checkpointsAvailable = false
        test.fake.beforeCall = { call -> if (call is Call.SampleNext) throw NativeLlamaException(NativeLlamaException.SAMPLING_FAILED, "injected") }
        test.ask(*second, assistant("Fine."), user("More?"))
        assertDeleted("a failed generation that cleared")
        test.fake.beforeCall = {}
        test.assertInvariant("a failed generation")

        test.ask(*first)
        test.save()
        test.engine.load(testModel(id = "another-model"))
        assertDeleted("another model loaded")
    }

    /** Catches an image waiting for its conversation that survives a request elsewhere. */
    @Test
    fun `an image of another conversation waits, and the first request elsewhere deletes it`() = runTest {
        val (header, bytes, note) = imageOfFirst()
        vault.put(header, bytes, note)
        val test = engine()
        assertEquals(NotRestored(NotRestored.Reason.OTHER_CONVERSATION), test.restore(key = B))
        assertNotNull("it waits for its own conversation", vault.image)
        assertTrue(test.fake.memory.isEmpty())
        test.ask(user("Hello"), key = B)
        assertDeleted("a request for another conversation")
    }

    /** Catches a restore that replaces a newer live memory of the same conversation, or keeps an image it cannot place. */
    @Test
    fun `a restore over a live memory of the same conversation keeps the memory and deletes the image`() = runTest {
        val (header, bytes, note) = imageOfFirst()
        vault.put(header, bytes, note)
        val test = engine()
        test.ask(*second)
        val held = test.fake.memory.toList()
        assertEquals(NotRestored(NotRestored.Reason.MEMORY_IN_USE), test.restore())
        assertEquals(held, test.fake.memory)
        assertDeleted("an image the engine cannot place")

        // The image the engine wrote itself is already held.
        test.save()
        assertEquals(MemoryRestoreResult.AlreadyHeld, test.restore())
        assertNotNull(vault.image)
    }

    /** Catches a restore of an image while the memory is marked: it may hold what was to be forgotten. */
    @Test
    fun `a marked memory restores nothing`() = runTest {
        val (header, bytes, note) = imageOfFirst()
        vault.put(header, bytes, note)
        val test = engine()
        test.engine.markNotToBeSaved()
        assertEquals(NotRestored(NotRestored.Reason.MARKED), test.restore())
        assertTrue(test.fake.memory.isEmpty())
        assertDeleted("marked")
    }

    // --- What a user interface sees ---

    /** Catches a missing or wrong state: in progress while reading, restored at the end. */
    @Test
    fun `the restore state says restoring while it reads and restored when it is done`() = runTest {
        val (header, bytes, note) = imageOfFirst()
        vault.put(header, bytes, note)
        val test = engine()
        assertEquals(MemoryRestoreState.Idle, test.engine.memoryRestore.value)
        val seen = ArrayList<MemoryRestoreState>()
        vault.beforeRead = { seen += test.engine.memoryRestore.value }
        test.restore()
        assertTrue(seen.isNotEmpty() && seen.all { it == MemoryRestoreState.Restoring(model.id) })
        assertEquals(MemoryRestoreState.Restored(model.id, header.tokenCount), test.engine.memoryRestore.value)
        test.engine.forgetKeepingSavedMemory()
        assertEquals(MemoryRestoreState.Idle, test.engine.memoryRestore.value)
    }

    /**
     * Catches a restore that ignores the caller's cancellation, and one whose stop deletes the
     * image: the session cancels a restore at a lock, and the image is then still the good one
     * for the next unlock.
     */
    @Test
    fun `a restore whose caller is cancelled stops, leaves an empty memory and keeps the image`() = runTest {
        val (header, bytes, note) = imageOfFirst()
        vault.put(header, bytes, note)
        val test = engine()
        lateinit var restoring: Job
        vault.beforeRead = { piece -> if (piece == 1) restoring.cancel() }
        restoring = launch { test.restore() }
        runCurrent()
        assertTrue(restoring.isCancelled)
        assertTrue(test.fake.memory.isEmpty())
        assertTrue(test.fake.checkpoints.isEmpty())
        assertEquals("the image is kept", header, vault.image)
        assertEquals(0, vault.deletes)
        assertEquals(MemoryRestoreState.Idle, test.engine.memoryRestore.value)

        // At the next unlock it is restored after all.
        test.engine.forgetKeepingSavedMemory()
        vault.beforeRead = {}
        assertEquals(MemoryRestoreResult.Restored(header.tokenCount), test.restore())
    }

    /**
     * The defect this guards against: a lock that destroys the key in the middle of a restore
     * made the engine delete a good image, whatever the vault had said. Catches an engine that
     * answers "cannot tell now" with a deletion at any of the three places a vault can say it
     * (before the restore, as it begins, in the middle of reading), and one that forgets the
     * memory's state: the memory must be empty and the conversation read again from nothing.
     * The contrast, a vault that read the image and refused it, is in the test of every failure
     * above and must still delete.
     */
    @Test
    fun `a vault that cannot tell now keeps its image, and the conversation is read again`() = runTest {
        val (header, bytes, note) = imageOfFirst()
        class Case(val name: String, val began: Boolean, val arrange: () -> Unit)
        val cases = listOf(
            Case("saved() cannot tell", began = false) { vault.unavailable = true },
            Case("beginRestore() cannot tell", began = true) { vault.restoreUnavailable = true },
            Case("a read in the middle cannot tell", began = true) { vault.readUnavailableAtPiece = 1 },
        )
        for (case in cases) {
            vault.unavailable = false
            vault.restoreUnavailable = false
            vault.readUnavailableAtPiece = null
            vault.put(header, bytes, note)
            val test = engine()
            case.arrange()
            val deletesBefore = vault.deletes

            assertEquals(case.name, NotRestored(NotRestored.Reason.UNAVAILABLE), test.restore())

            assertEquals("${case.name}: nothing was deleted", deletesBefore, vault.deletes)
            assertEquals("${case.name}: the image is kept", header, vault.image)
            assertTrue("${case.name}: the memory is empty", test.fake.memory.isEmpty())
            assertTrue("${case.name}: no checkpoint is held", test.fake.checkpoints.isEmpty())
            assertEquals(case.name, MemoryRestoreState.Idle, test.engine.memoryRestore.value)
            if (case.began) {
                // The image waits as one of another conversation does: a lock lets go of it,
                // and the next unlock restores it.
                test.engine.forgetKeepingSavedMemory()
                vault.restoreUnavailable = false
                vault.readUnavailableAtPiece = null
                assertEquals(case.name, MemoryRestoreResult.Restored(header.tokenCount), test.restore())
            } else {
                vault.unavailable = false
                val events = test.ask(*second)
                assertEquals("${case.name}: read from nothing", 0, events.done().stats.reusedPromptTokens)
            }
        }
    }

    /** Catches a stop by forgetting at a lock that deletes the image the next unlock would use. */
    @Test
    fun `forgetting at a lock in the middle of a restore keeps the image for the next unlock`() = runTest {
        val (header, bytes, note) = imageOfFirst()
        vault.put(header, bytes, note)
        val test = engine()
        vault.beforeRead = { piece -> if (piece == 1) launch(start = CoroutineStart.UNDISPATCHED) { test.engine.forgetKeepingSavedMemory() } }

        assertEquals(NotRestored(NotRestored.Reason.STOPPED), test.restore())
        runCurrent()

        assertTrue(test.fake.memory.isEmpty())
        assertEquals(0, vault.deletes)
        assertEquals(header, vault.image)
        assertEquals(MemoryRestoreState.Idle, test.engine.memoryRestore.value)
        vault.beforeRead = {}
        assertEquals(MemoryRestoreResult.Restored(header.tokenCount), test.restore())
    }

    /** Catches an image kept after a stop that a request elsewhere, or a forgetting that is not a lock's, fails to delete. */
    @Test
    fun `an image kept after a stop is deleted by the next request or by forgetting`() = runTest {
        val (header, bytes, note) = imageOfFirst()
        for (end in listOf("request", "forget")) {
            vault.put(header, bytes, note)
            val test = engine()
            lateinit var restoring: Job
            vault.beforeRead = { piece -> if (piece == 1) restoring.cancel() }
            restoring = launch { test.restore() }
            runCurrent()
            vault.beforeRead = {}
            assertEquals(end, header, vault.image)
            if (end == "request") test.ask(*second, key = B) else test.engine.forget()
            assertDeleted(end)
        }
    }

    private companion object {
        const val A = "conversation-a"
        const val B = "conversation-b"
    }
}
