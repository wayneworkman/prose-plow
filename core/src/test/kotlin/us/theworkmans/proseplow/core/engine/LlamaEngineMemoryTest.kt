package us.theworkmans.proseplow.core.engine

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import us.theworkmans.proseplow.core.engine.FakeNativeLlama.Call
import us.theworkmans.proseplow.core.model.ModelFamily
import us.theworkmans.proseplow.core.prompt.PlainChatMlFormat
import us.theworkmans.proseplow.core.prompt.PromptInput
import us.theworkmans.proseplow.core.prompt.PromptMessage

/**
 * What the engine does with the model's memory between requests: reusing the common beginning,
 * rewinding, and falling back to checkpoints or a full evaluation when the model cannot rewind.
 *
 * Most tests use the plain-text ChatML model, where the fake tokenizer gives one token per
 * character, so a position in the prompt text is also a position in the memory.
 */
class LlamaEngineMemoryTest {

    private fun prompt(vararg messages: PromptMessage, systemPrompt: String? = null): String =
        PlainChatMlFormat.render(PromptInput(messages.toList(), systemPrompt = systemPrompt)).text()

    private val assistantOpening = "<|im_start|>assistant\n"

    /** Where each turn of [text] begins, except the very first: the boundaries a checkpoint can sit at. */
    private fun turnStarts(text: String): List<Int> =
        Regex("<\\|im_start\\|>").findAll(text).map { it.range.first }.filter { it > 0 }.toList()

    private fun FakeNativeLlama.memoryChangingCalls(): List<Call> =
        calls.filter { it is Call.RewindTo || it is Call.ClearMemory || it is Call.RestoreCheckpoint }

    // --- Reuse by appending ---

    @Test
    fun `the next turn only evaluates what is new`() = runTest {
        val test = loadedEngine()
        test.fake.enqueueReply("Hello!")
        test.engine.generate(request(user("Hi"))).toList()
        val inMemory = test.fake.memory.size
        assertEquals(prompt(user("Hi")) + "Hello!", test.fake.memoryText)

        test.fake.clearCalls()
        test.fake.enqueueReply("Fine.")
        val second = test.engine.generate(request(user("Hi"), assistant("Hello!"), user("How are you?"))).toList()

        val newPrompt = prompt(user("Hi"), assistant("Hello!"), user("How are you?"))
        assertEquals("nothing had to be removed from memory", emptyList<Call>(), test.fake.memoryChangingCalls())
        assertEquals(inMemory, second.done().stats.reusedPromptTokens)
        assertEquals(newPrompt.length, second.done().stats.promptTokens)
        assertEquals(newPrompt.substring(inMemory), test.fake.render(test.fake.promptEvaluations().flatten()))
        assertEquals(GenerationEvent.PromptProgress(inMemory, newPrompt.length), second.progress().first())
        assertEquals(newPrompt + "Fine.", test.fake.memoryText)
    }

    @Test
    fun `a reply ended by its stop string leaves a memory the next turn simply extends`() = runTest {
        val test = loadedEngine()
        test.fake.enqueueReply("4.$IM_END\n<|im_start|>user\n")
        val first = test.engine.generate(request(user("2 + 2?"))).toList()
        assertEquals("4.", first.done().text)
        // Everything up to the last character of the marker was evaluated; that character was not.
        assertEquals(prompt(user("2 + 2?")) + "4.<|im_end|", test.fake.memoryText)

        test.fake.clearCalls()
        test.engine.generate(request(user("2 + 2?"), assistant("4."), user("Sure?"))).toList()
        assertEquals(emptyList<Call>(), test.fake.memoryChangingCalls())
    }

    // --- Reuse by rewinding ---

    @Test
    fun `regenerating rewinds to just before the last prompt token`() = runTest {
        val test = loadedEngine()
        test.fake.enqueueReply("First answer")
        test.engine.generate(request(user("Hi"))).toList()
        val promptLength = prompt(user("Hi")).length

        test.fake.clearCalls()
        test.fake.enqueueReply("Second answer")
        val again = test.engine.generate(request(user("Hi"))).toList()

        // The whole prompt is in memory, but the model's prediction only exists after an
        // evaluation, so the last prompt token is evaluated again.
        assertEquals(listOf<Call>(Call.RewindTo(promptLength - 1)), test.fake.memoryChangingCalls())
        assertEquals(listOf("\n"), test.fake.promptEvaluations().map { test.fake.render(it) })
        assertEquals(promptLength - 1, again.done().stats.reusedPromptTokens)
        assertEquals("Second answer", again.done().text)
        assertEquals(prompt(user("Hi")) + "Second answer", test.fake.memoryText)
    }

    @Test
    fun `after an edit the memory is rewound to the common beginning and the rest evaluated`() = runTest {
        val test = loadedEngine()
        test.fake.enqueueReply("Canberra.")
        test.engine.generate(request(user("Capital of Australia?"), systemPrompt = "Be brief.")).toList()
        test.fake.enqueueReply("Wellington.")
        test.engine.generate(
            request(user("Capital of Australia?"), assistant("Canberra."), user("And of New Zealand?"), systemPrompt = "Be brief."),
        ).toList()

        // The second question is edited: everything before it is unchanged.
        val edited = arrayOf(user("Capital of Australia?"), assistant("Canberra."), user("And of Norway?"))
        val newPrompt = prompt(*edited, systemPrompt = "Be brief.")
        val common = newPrompt.indexOf("And of N") + "And of N".length
        test.fake.clearCalls()
        test.fake.enqueueReply("Oslo.")
        val events = test.engine.generate(request(*edited, systemPrompt = "Be brief.")).toList()

        assertEquals(listOf<Call>(Call.RewindTo(common)), test.fake.memoryChangingCalls())
        assertEquals(common, events.done().stats.reusedPromptTokens)
        assertEquals(newPrompt.substring(common), test.fake.render(test.fake.promptEvaluations().flatten()))
        assertEquals(newPrompt + "Oslo.", test.fake.memoryText)
    }

    @Test
    fun `a reply written with tokens the tokenizer would not choose is rewound at its start`() = runTest {
        val test = loadedEngine()
        // One token for the whole word; tokenising the text again gives one token per letter.
        test.fake.enqueueReply(listOf(test.fake.addPiece("Hello!")))
        test.engine.generate(request(user("Hi"))).toList()
        val firstPrompt = prompt(user("Hi"))

        test.fake.clearCalls()
        val second = test.engine.generate(request(user("Hi"), assistant("Hello!"), user("Bye"))).toList()
        assertEquals(listOf<Call>(Call.RewindTo(firstPrompt.length)), test.fake.memoryChangingCalls())
        assertEquals(firstPrompt.length, second.done().stats.reusedPromptTokens)
        assertEquals(prompt(user("Hi"), assistant("Hello!"), user("Bye")), test.fake.memoryText)
    }

    @Test
    fun `switching to another conversation keeps only the tokens the two have in common`() = runTest {
        val test = loadedEngine(testModel(family = ModelFamily.PEACEBELL_CHATML, stopTokenIds = listOf(4)))
        test.engine.generate(request(user("First chat"))).toList()
        test.fake.clearCalls()
        val second = test.engine.generate(request(user("Other chat"))).toList()
        // Both begin with id 3 and the five tokens of the role line; the content differs at once.
        assertEquals(listOf<Call>(Call.RewindTo(6)), test.fake.memoryChangingCalls())
        assertEquals(6, second.done().stats.reusedPromptTokens)
        assertEquals(listOf(3) + test.fake.textTokens("user\nOther chat") + listOf(4, 3) + test.fake.textTokens("assistant\n"), test.fake.memory)
    }

    // --- Models that cannot rewind ---

    @Test
    fun `checkpoints are saved at the turn boundaries while the prompt is evaluated`() = runTest {
        val test = loadedEngine()
        test.fake.enqueueReply("Hello!")
        test.engine.generate(request(user("Hi"), systemPrompt = "Be brief.")).toList()

        val text = prompt(user("Hi"), systemPrompt = "Be brief.")
        // The start of the user turn and the start of the assistant turn; never position 0.
        assertEquals(turnStarts(text), test.fake.checkpoints.values.sorted())
        assertEquals(2, test.fake.checkpoints.size)
        // The prompt was evaluated in stretches that end at those boundaries.
        assertEquals(
            listOf(text.substring(0, turnStarts(text)[0]), text.substring(turnStarts(text)[0], turnStarts(text)[1]), assistantOpening),
            test.fake.promptEvaluations().map { test.fake.render(it) },
        )
    }

    @Test
    fun `when the model cannot rewind, regenerating goes back to the checkpoint before the new turn`() = runTest {
        val test = loadedEngine { it.refuseRewind = true }
        test.fake.enqueueReply("First answer")
        test.engine.generate(request(user("Hi"), systemPrompt = "Be brief.")).toList()
        val text = prompt(user("Hi"), systemPrompt = "Be brief.")
        val turnStart = text.length - assistantOpening.length
        val handle = test.fake.checkpoints.entries.single { it.value == turnStart }.key

        test.fake.clearCalls()
        test.fake.enqueueReply("Second answer")
        val again = test.engine.generate(request(user("Hi"), systemPrompt = "Be brief.")).toList()

        assertEquals(
            listOf(Call.RewindTo(text.length - 1), Call.RestoreCheckpoint(handle)),
            test.fake.memoryChangingCalls(),
        )
        // Only the opening of the assistant turn had to be evaluated again.
        assertEquals(listOf(assistantOpening), test.fake.promptEvaluations().map { test.fake.render(it) })
        assertEquals(turnStart, again.done().stats.reusedPromptTokens)
        assertEquals("Second answer", again.done().text)
        assertEquals(text + "Second answer", test.fake.memoryText)
    }

    @Test
    fun `when the model cannot rewind and there is no checkpoint, everything is evaluated again`() = runTest {
        val test = loadedEngine(settings = LlamaEngine.Settings(maxCheckpoints = 0)) { it.refuseRewind = true }
        test.fake.enqueueReply("First answer")
        test.engine.generate(request(user("Hi"), systemPrompt = "Be brief.")).toList()
        assertTrue(test.fake.callsOf<Call.SaveCheckpoint>().isEmpty())
        val text = prompt(user("Hi"), systemPrompt = "Be brief.")

        test.fake.clearCalls()
        test.fake.enqueueReply("Second answer")
        val again = test.engine.generate(request(user("Hi"), systemPrompt = "Be brief.")).toList()

        assertEquals(listOf(Call.RewindTo(text.length - 1), Call.ClearMemory), test.fake.memoryChangingCalls())
        assertEquals(listOf(text), test.fake.promptEvaluations().map { test.fake.render(it) })
        assertEquals(0, again.done().stats.reusedPromptTokens)
        assertEquals(GenerationEvent.PromptProgress(0, text.length), again.progress().first())
        assertEquals("Second answer", again.done().text)
    }

    @Test
    fun `a native side that cannot save checkpoints is tolerated`() = runTest {
        val test = loadedEngine { it.refuseRewind = true; it.checkpointsAvailable = false }
        test.fake.enqueueReply("First answer")
        test.engine.generate(request(user("Hi"), systemPrompt = "Be brief.")).toList()
        test.fake.clearCalls()
        test.fake.enqueueReply("Second answer")
        val again = test.engine.generate(request(user("Hi"), systemPrompt = "Be brief.")).toList()
        assertTrue(Call.ClearMemory in test.fake.calls)
        assertTrue(test.fake.callsOf<Call.RestoreCheckpoint>().isEmpty())
        assertEquals("Second answer", again.done().text)
    }

    @Test
    fun `a checkpoint that fails to restore falls back to a full evaluation`() = runTest {
        val test = loadedEngine { it.refuseRewind = true }
        test.fake.enqueueReply("First answer")
        test.engine.generate(request(user("Hi"), systemPrompt = "Be brief.")).toList()
        test.fake.failRestore = true
        test.fake.clearCalls()
        test.fake.enqueueReply("Second answer")
        val again = test.engine.generate(request(user("Hi"), systemPrompt = "Be brief.")).toList()

        val kinds = test.fake.memoryChangingCalls().map { it::class.simpleName }
        assertEquals(listOf("RewindTo", "RestoreCheckpoint", "ClearMemory"), kinds)
        assertEquals(0, again.done().stats.reusedPromptTokens)
        assertEquals("Second answer", again.done().text)
        // The memory was cleared with all its checkpoints; new ones were saved on the way back.
        assertEquals(2, test.fake.checkpoints.size)
    }

    @Test
    fun `when the model cannot rewind, an edit goes back to the nearest checkpoint before it`() = runTest {
        val test = loadedEngine { it.refuseRewind = true }
        val system = "Be brief."
        test.fake.enqueueReply("Canberra.")
        test.engine.generate(request(user("Capital of Australia?"), systemPrompt = system)).toList()
        test.fake.enqueueReply("Wellington.")
        test.engine.generate(
            request(user("Capital of Australia?"), assistant("Canberra."), user("And of New Zealand?"), systemPrompt = system),
        ).toList()

        // Edit the second question.
        val editedSecond = arrayOf(user("Capital of Australia?"), assistant("Canberra."), user("And of Norway?"))
        val secondPrompt = prompt(*editedSecond, systemPrompt = system)
        val secondQuestionStart = turnStarts(secondPrompt)[2]
        test.fake.clearCalls()
        test.fake.enqueueReply("Oslo.")
        val second = test.engine.generate(request(*editedSecond, systemPrompt = system)).toList()
        assertEquals(1, test.fake.callsOf<Call.RestoreCheckpoint>().size)
        assertTrue(test.fake.callsOf<Call.ClearMemory>().isEmpty())
        assertEquals("the checkpoint at the start of the edited message", secondQuestionStart, second.done().stats.reusedPromptTokens)
        assertEquals(secondPrompt + "Oslo.", test.fake.memoryText)

        // Edit the first question: the nearest checkpoint is the one after the system prompt.
        val editedFirst = arrayOf(user("Capital of Austria?"))
        val firstPrompt = prompt(*editedFirst, systemPrompt = system)
        test.fake.clearCalls()
        test.fake.enqueueReply("Vienna.")
        val first = test.engine.generate(request(*editedFirst, systemPrompt = system)).toList()
        assertEquals(1, test.fake.callsOf<Call.RestoreCheckpoint>().size)
        assertTrue(test.fake.callsOf<Call.ClearMemory>().isEmpty())
        assertEquals(turnStarts(firstPrompt)[0], first.done().stats.reusedPromptTokens)
        assertEquals(firstPrompt + "Vienna.", test.fake.memoryText)
    }

    @Test
    fun `checkpoints beyond the point a conversation was cut at are dropped`() = runTest {
        val test = loadedEngine { it.refuseRewind = true }
        val system = "Be brief."
        test.fake.enqueueReply("Canberra.")
        test.engine.generate(request(user("Capital of Australia?"), systemPrompt = system)).toList()
        test.fake.enqueueReply("Wellington.")
        test.engine.generate(
            request(user("Capital of Australia?"), assistant("Canberra."), user("And of New Zealand?"), systemPrompt = system),
        ).toList()
        assertEquals(4, test.fake.checkpoints.size)

        val edited = prompt(user("Capital of Austria?"), systemPrompt = system)
        test.engine.generate(request(user("Capital of Austria?"), systemPrompt = system)).toList()
        // Only checkpoints of the conversation as it now is remain.
        assertEquals(turnStarts(edited), test.fake.checkpoints.values.sorted())
    }

    @Test
    fun `only a bounded number of checkpoints is kept, the latest ones`() = runTest {
        val test = loadedEngine(settings = LlamaEngine.Settings(maxCheckpoints = 2))
        var mostAtOnce = 0
        test.fake.beforeCall = { mostAtOnce = maxOf(mostAtOnce, test.fake.checkpoints.size) }
        val conversation = ArrayList<PromptMessage>()
        for (turn in 1..4) {
            conversation += user("Question $turn")
            test.fake.enqueueReply("Answer $turn")
            test.engine.generate(request(*conversation.toTypedArray(), systemPrompt = "Be brief.")).toList()
            conversation += assistant("Answer $turn")
        }
        val lastPrompt = prompt(*conversation.dropLast(1).toTypedArray(), systemPrompt = "Be brief.")
        assertEquals("never more than the limit, even for a moment", 2, mostAtOnce)
        assertEquals(turnStarts(lastPrompt).takeLast(2), test.fake.checkpoints.values.sorted())
        assertTrue(test.fake.callsOf<Call.DropCheckpoint>().isNotEmpty())
    }

    @Test
    fun `a Qwen conversation continues from the checkpoint in front of the previous reply`() = runTest {
        // The opening of a new turn carries an empty think block that the same turn, rendered
        // as history, does not have. The next prompt therefore always departs from the memory
        // inside that opening, and a model that cannot rewind needs the checkpoint in front of it.
        val test = loadedEngine(testModel(family = ModelFamily.QWEN35_CHATML)) { it.refuseRewind = true }
        test.fake.enqueueReply("Canberra.")
        val first = test.engine.generate(request(user("Capital of Australia?"))).toList()
        val generationPromptTokens = 1 + "assistant\n".length + 1 + 2 + 1 + 2
        val turnStart = first.done().stats.promptTokens - generationPromptTokens
        assertEquals(listOf(turnStart), test.fake.checkpoints.values.toList())

        test.fake.clearCalls()
        test.fake.enqueueReply("Wellington.")
        val second = test.engine.generate(
            request(user("Capital of Australia?"), assistant("Canberra."), user("And of New Zealand?")),
        ).toList()

        assertEquals(1, test.fake.callsOf<Call.RestoreCheckpoint>().size)
        assertTrue(test.fake.callsOf<Call.ClearMemory>().isEmpty())
        assertEquals(turnStart, second.done().stats.reusedPromptTokens)
        assertEquals("Wellington.", second.done().text)
        // History carries the answer without the think block.
        assertTrue("<|im_start|>assistant\nCanberra.<|im_end|>\n" in test.fake.memoryText)
        assertEquals(1, test.fake.memory.count { it == test.chatMl.thinkOpen })
    }

    // --- Drift ---

    @Test
    fun `a memory that no longer matches the engine's record is cleared and rebuilt`() = runTest {
        val test = loadedEngine()
        test.fake.enqueueReply("Hello!")
        test.engine.generate(request(user("Hi"))).toList()
        // Something changed the native memory behind the engine's back.
        test.fake.rewindTo(5)

        test.fake.clearCalls()
        test.fake.enqueueReply("Fine.")
        val second = test.engine.generate(request(user("Hi"), assistant("Hello!"), user("How are you?"))).toList()
        assertEquals(listOf<Call>(Call.ClearMemory), test.fake.memoryChangingCalls())
        assertEquals(0, second.done().stats.reusedPromptTokens)
        assertEquals("Fine.", second.done().text)
        assertEquals(prompt(user("Hi"), assistant("Hello!"), user("How are you?")) + "Fine.", test.fake.memoryText)
    }

    @Test
    fun `forgetting clears the native memory and its checkpoints, and the next request starts from nothing`() = runTest {
        val test = loadedEngine()
        test.fake.enqueueReply("Hello!")
        test.engine.generate(request(user("Hi"), systemPrompt = "Be brief.")).toList()
        assertTrue(test.fake.memory.isNotEmpty())
        assertTrue(test.fake.checkpoints.isNotEmpty())

        test.engine.forget()
        assertEquals(0, test.fake.memory.size)
        assertTrue(test.fake.checkpoints.isEmpty())
        assertEquals("the model stays loaded", EngineState.Ready(TEST_MODEL_ID), test.engine.state.value)
        assertFalse(test.fake.closed)

        test.fake.clearCalls()
        test.fake.enqueueReply("Hello again!")
        val events = test.engine.generate(request(user("Hi"), systemPrompt = "Be brief.")).toList()
        assertEquals(0, events.done().stats.reusedPromptTokens)
        assertEquals("no rewind was attempted on the empty memory", emptyList<Call>(), test.fake.memoryChangingCalls())
    }

    @Test
    fun `forgetting without a loaded model does nothing`() = runTest {
        val test = newEngine()
        test.engine.forget()
        assertEquals(EngineState.Unloaded, test.engine.state.value)
    }

    @Test
    fun `a memory that cannot be cleared when forgetting is freed with the model`() = runTest {
        val test = loadedEngine()
        test.fake.enqueueReply("Hello!")
        test.engine.generate(request(user("Hi"))).toList()
        val fake = test.fake
        fake.beforeCall = { call -> if (call is Call.ClearMemory) throw NativeLlamaException(NativeLlamaException.EVALUATION_FAILED, "clear failed") }
        test.engine.forget()
        assertTrue("nothing of the conversation may stay behind", fake.closed)
        assertEquals(EngineState.Unloaded, test.engine.state.value)
    }
}
