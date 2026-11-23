package us.theworkmans.proseplow.core.engine

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import us.theworkmans.proseplow.core.engine.FakeNativeLlama.Call
import us.theworkmans.proseplow.core.engine.GenerationEvent.TextDelta
import us.theworkmans.proseplow.core.engine.GenerationEvent.TextReplaced
import us.theworkmans.proseplow.core.model.ModelCatalog
import us.theworkmans.proseplow.core.model.ModelFamily

/**
 * A model that continues text and can take back what it wrote: the prompt it is given, and what
 * the engine makes of its deletion token.
 *
 * The test model is shaped like Shard-2: no template, end markers 1 and 2, and the deletion token
 * 4096. Replies are scripted with whole-word tokens (the fake's "pieces"), so that one deletion
 * takes back one word, as it does with a real vocabulary. Every script is a handful of tokens.
 */
class LlamaEngineDeletionTest {

    private val bs = 4096

    private fun continuer(maxNewTokens: Int = 64, contextLength: Int = 2000) = testModel(
        family = ModelFamily.COMPLETION,
        contextLength = contextLength,
        maxNewTokens = maxNewTokens,
        stopTokenIds = listOf(1, 2),
        supportsSystemPrompt = false,
        deletionTokenId = bs,
    )

    /** An engine with the continuation model loaded; the fake spells the deletion token `<bs>`. */
    private suspend fun TestScope.loaded(maxNewTokens: Int = 64, contextLength: Int = 2000): TestEngine =
        loadedEngine(continuer(maxNewTokens, contextLength)) { it.addControl("<bs>", id = bs) }

    private fun TestEngine.pieces(vararg texts: String): List<Int> = texts.map { fake.addPiece(it) }

    /** The text events of a generation, in order. */
    private fun List<GenerationEvent>.textEvents(): List<GenerationEvent> = filter { it is TextDelta || it is TextReplaced }

    /**
     * What a reader has on screen after these events, checking on the way that every replacement
     * is what it says it is: the text before it, shorter by the characters it names.
     */
    private fun List<GenerationEvent>.shown(): String {
        val text = StringBuilder()
        for (event in this) {
            when (event) {
                is TextDelta -> text.append(event.text)
                is TextReplaced -> {
                    assertTrue("a replacement takes something back", event.removed > 0)
                    assertEquals(text.substring(0, text.length - event.removed), event.text)
                    text.setLength(text.length - event.removed)
                }
                else -> Unit
            }
        }
        return text.toString()
    }

    // --- The prompt ---

    @Test
    fun `the prompt is the user's text exactly as written, with nothing added`() = runTest {
        val test = loaded()
        test.fake.enqueueReply(test.pieces(" there", " was"))
        val events = test.engine.generate(request(user("  Once upon a time "), systemPrompt = "You are a helpful assistant.")).toList()

        // One tokenizer call, for the text as it is, never parsed for special tokens.
        assertEquals(listOf(Call.Tokenize("  Once upon a time ", parseSpecial = false)), test.fake.callsOf<Call.Tokenize>())
        assertEquals(test.fake.textTokens("  Once upon a time "), test.fake.promptEvaluations().flatten())
        assertEquals("  Once upon a time  there was", test.fake.memoryText)
        assertEquals("  Once upon a time ".length, events.done().stats.promptTokens)
    }

    @Test
    fun `each message is continued on its own and no history is reported as left out`() = runTest {
        // A context that could not hold the earlier turns: they are never sent, so nothing is "trimmed".
        val test = loaded(maxNewTokens = 8, contextLength = 40)
        val old = "An earlier text that is much longer than the whole context of this model could ever hold."
        test.fake.enqueueReply(test.pieces(" red"))
        val events = test.engine.generate(request(user(old), assistant(old), user("Roses are"))).toList()

        assertTrue(events.none { it is GenerationEvent.HistoryTrimmed })
        assertEquals(0, events.done().stats.droppedMessages)
        assertEquals("Roses are red", test.fake.memoryText)
    }

    @Test
    fun `a latest message that leaves no room for a reply is too long, whatever came before`() = runTest {
        val test = loaded(maxNewTokens = 8, contextLength = 40)
        val long = "x".repeat(40)
        val events = test.engine.generate(request(user("short"), assistant("reply"), user(long))).toList()
        assertEquals(EngineError.PromptTooLong(promptTokens = 40, contextSize = 40), events.error())
        assertEquals(1, events.size)
    }

    @Test
    fun `a continued message is the user's text followed directly by the partial answer`() = runTest {
        val test = loaded()
        test.fake.enqueueReply(test.pieces(" However,"))
        val events = test.engine.generate(
            request(user("The capital of France is"), assistant(" Nice. "), continueFinalMessage = true),
        ).toList()
        assertEquals(listOf(Call.Tokenize("The capital of France is Nice. ", parseSpecial = false)), test.fake.callsOf<Call.Tokenize>().take(1))
        assertEquals("The capital of France is Nice.  However,", test.fake.memoryText)
        assertEquals(" However,", events.done().text)
    }

    // --- The reply is kept exactly ---

    @Test
    fun `the reply keeps its leading whitespace and says that it must be stored exactly`() = runTest {
        val test = loaded()
        test.fake.enqueueReply(test.pieces(" ", "\n", " Nice.", " \n"))
        val events = test.engine.generate(request(user("The capital of France is"))).toList()
        assertEquals(listOf(TextDelta(" "), TextDelta("\n"), TextDelta(" Nice."), TextDelta(" \n")), events.textEvents())
        assertEquals(" \n Nice. \n", events.done().text)
        assertTrue(events.done().exactText)
    }

    @Test
    fun `either end marker ends the reply and is neither shown nor evaluated`() = runTest {
        for (marker in listOf(1, 2)) {
            val test = loaded()
            test.fake.enqueueReply(test.pieces(" one") + marker + test.pieces(" never"))
            val events = test.engine.generate(request(user("Count:"))).toList()
            assertEquals(FinishReason.END_OF_TURN, events.done().finishReason)
            assertEquals(" one", events.done().text)
            assertEquals(1, events.done().stats.generatedTokens)
            assertFalse(marker in test.fake.memory)
        }
    }

    // --- Deletions ---

    @Test
    fun `a deletion takes back the token before it and the reader is told what is left`() = runTest {
        val test = loaded()
        val (nice, number, development) = test.pieces(" Nice.", " 198", " development")
        test.fake.enqueueReply(listOf(nice, number, bs, development))
        val events = test.engine.generate(request(user("The capital of France is"))).toList()

        assertEquals(
            listOf(TextDelta(" Nice."), TextDelta(" 198"), TextReplaced(" Nice.", removed = 4), TextDelta(" development")),
            events.textEvents(),
        )
        val done = events.done()
        assertEquals(" Nice. development", done.text)
        assertEquals(done.text, events.shown())
        assertEquals(FinishReason.END_OF_TURN, done.finishReason)
        assertEquals("the deletion and the token it took back were written by the model", 4, done.stats.generatedTokens)
        assertEquals(1, done.stats.deletions)
    }

    @Test
    fun `the deletion token reaches the model like any other token and stays in its memory`() = runTest {
        val test = loaded()
        val (nice, number, development) = test.pieces(" Nice.", " 198", " development")
        test.fake.enqueueReply(listOf(nice, number, bs, development))
        test.engine.generate(request(user("The capital of France is"))).toList()

        // The model reads its abandoned attempt and the deletion; only the reader does not.
        assertEquals(listOf(nice, number, bs, development), test.fake.memory.takeLast(4))
        assertEquals("The capital of France is Nice. 198<bs> development", test.fake.memoryText)
        assertTrue(Call.Evaluate(listOf(bs)) in test.fake.calls)
        assertEquals("the sampler was not told to avoid it", emptyList<Int>(), test.fake.sampler?.bannedTokenIds)
    }

    @Test
    fun `deletions in a row take back one token each`() = runTest {
        val test = loaded()
        val (a, russ, ia, os) = test.pieces("a", " Russ", "ia", "os Angeles")
        test.fake.enqueueReply(listOf(a, russ, ia, bs, bs, bs, os))
        val events = test.engine.generate(request(user("Ambassador of the State of L"))).toList()

        assertEquals(
            listOf(
                TextDelta("a"), TextDelta(" Russ"), TextDelta("ia"),
                TextReplaced("a Russ", removed = 2), TextReplaced("a", removed = 5), TextReplaced("", removed = 1),
                TextDelta("os Angeles"),
            ),
            events.textEvents(),
        )
        assertEquals("os Angeles", events.done().text)
        assertEquals(3, events.done().stats.deletions)
        assertEquals(7, events.done().stats.generatedTokens)
        assertEquals("Ambassador of the State of La Russia<bs><bs><bs>os Angeles", test.fake.memoryText)
    }

    @Test
    fun `a deletion as the very first token changes nothing the reader sees`() = runTest {
        val test = loaded()
        val hello = test.pieces(" Hello").single()
        test.fake.enqueueReply(listOf(bs, bs, hello))
        val events = test.engine.generate(request(user("Greeting:"))).toList()

        assertEquals(listOf(TextDelta(" Hello")), events.textEvents())
        assertEquals(" Hello", events.done().text)
        assertEquals("they are counted all the same", 2, events.done().stats.deletions)
        assertEquals(3, events.done().stats.generatedTokens)
        // And they were evaluated: the prompt itself is untouched, with the deletions after it.
        assertEquals("Greeting:<bs><bs> Hello", test.fake.memoryText)
    }

    @Test
    fun `more deletions than tokens stop at the start of the reply`() = runTest {
        val test = loaded()
        val (first, second) = test.pieces(" first", " second")
        test.fake.enqueueReply(listOf(first, bs, bs, bs, second))
        val events = test.engine.generate(request(user("The prompt stays"))).toList()

        assertEquals(listOf(TextDelta(" first"), TextReplaced("", removed = 6), TextDelta(" second")), events.textEvents())
        assertEquals(" second", events.done().text)
        assertTrue(test.fake.memoryText.startsWith("The prompt stays first<bs><bs><bs>"))
    }

    @Test
    fun `a deletion right after a continued prefix cannot reach into it`() = runTest {
        val test = loaded()
        val (however, paris) = test.pieces(" However,", " Paris.")
        test.fake.enqueueReply(listOf(bs, however, bs, bs, paris))
        val events = test.engine.generate(
            request(user("The capital of France is"), assistant(" Nice."), continueFinalMessage = true),
        ).toList()

        // The first deletion finds nothing of this generation; the third finds nothing left of it.
        assertEquals(listOf(TextDelta(" However,"), TextReplaced("", removed = 9), TextDelta(" Paris.")), events.textEvents())
        assertEquals("only what this generation wrote", " Paris.", events.done().text)
        assertEquals(3, events.done().stats.deletions)
        assertEquals("The capital of France is Nice.<bs> However,<bs><bs> Paris.", test.fake.memoryText)
    }

    @Test
    fun `taking back half of a character takes the character back, and it can be completed differently`() = runTest {
        val test = loaded()
        val x = test.fake.addPiece("x")
        val firstTwoBytes = test.fake.addPiece(byteArrayOf(0xE2.toByte(), 0x82.toByte()))
        val euro = test.fake.addPiece(byteArrayOf(0xAC.toByte()))
        val won = test.fake.addPiece(byteArrayOf(0xA9.toByte()))
        test.fake.enqueueReply(listOf(x, firstTwoBytes, euro, bs, won))
        val events = test.engine.generate(request(user("Price: "))).toList()

        assertEquals(
            listOf(TextDelta("x"), TextDelta("\u20AC"), TextReplaced("x", removed = 1), TextDelta("\u20A9")),
            events.textEvents(),
        )
        assertEquals("x\u20A9", events.done().text)
    }

    @Test
    fun `taking back bytes that were never a whole character is invisible`() = runTest {
        val test = loaded()
        val x = test.fake.addPiece("x")
        val half = test.fake.addPiece(byteArrayOf(0xF0.toByte(), 0x9F.toByte()))
        val y = test.fake.addPiece("y")
        test.fake.enqueueReply(listOf(x, half, bs, y))
        val events = test.engine.generate(request(user("Go: "))).toList()

        assertEquals(listOf(TextDelta("x"), TextDelta("y")), events.textEvents())
        assertEquals("xy", events.done().text)
        assertEquals(1, events.done().stats.deletions)
    }

    @Test
    fun `deletions count towards the reply's token limit`() = runTest {
        val test = loaded()
        val (a, b, c) = test.pieces(" a", " b", " c")
        test.fake.enqueueReply(listOf(a, bs, b, bs, c, c, c))
        val events = test.engine.generate(request(user("Letters:"), maxNewTokens = 4)).toList()

        val done = events.done()
        assertEquals(FinishReason.LENGTH, done.finishReason)
        assertEquals(4, done.stats.generatedTokens)
        assertEquals(2, done.stats.deletions)
        assertEquals("two words written, two taken back", "", done.text)
        assertEquals("", events.shown())
        assertEquals(4, test.fake.callsOf<Call.SampleNext>().size)
        // Like any last token at the limit, the fourth was not evaluated.
        assertEquals("Letters: a<bs> b", test.fake.memoryText)
    }

    @Test
    fun `a stop string is found in what is left after a deletion`() = runTest {
        val test = loaded()
        val (one, x, top, never) = test.pieces("one S", "x", "TOP", " never")
        test.fake.enqueueReply(listOf(one, x, bs, top, never))
        val events = test.engine.generate(request(user("Say: "), stopStrings = listOf("STOP"))).toList()

        // "Sx" was let through when it could no longer be the stop string, and then taken back.
        assertEquals(listOf(TextDelta("one "), TextDelta("Sx"), TextReplaced("one ", removed = 2)), events.textEvents())
        assertEquals(FinishReason.STOP_STRING, events.done().finishReason)
        assertEquals("STOP", events.done().stopString)
        assertEquals("one ", events.done().text)
    }

    @Test
    fun `the catalogue's deletion token is a deletion even if the model file calls it an end marker`() = runTest {
        val test = loaded()
        test.fake.markEndOfGeneration(bs)
        val (a, b) = test.pieces(" a", " b")
        test.fake.enqueueReply(listOf(a, bs, b))
        val events = test.engine.generate(request(user("Go:"))).toList()
        assertEquals(" b", events.done().text)
        assertEquals(1, events.done().stats.deletions)
    }

    // --- Continuing after deletions ---

    @Test
    fun `continuing shows the model the text that was left, and the memory is rewound to where they part`() = runTest {
        val test = loaded()
        val (red, blue, comma) = test.pieces(" red", " blue", ",")
        test.fake.enqueueReply(listOf(red, blue, bs, comma))
        val first = test.engine.generate(request(user("Roses are"))).toList()
        assertEquals(" red,", first.done().text)

        test.fake.clearCalls()
        test.fake.enqueueReply(test.pieces(" violets"))
        val second = test.engine.generate(request(user("Roses are"), assistant(" red,"), continueFinalMessage = true)).toList()

        // The memory held the reply as the model wrote it, deletion and all; the continued prompt
        // is the text the reader has. The two agree up to the end of the user's text.
        assertEquals(listOf<Call>(Call.RewindTo("Roses are".length)), test.fake.calls.filterIsInstance<Call.RewindTo>())
        assertEquals("Roses are".length, second.done().stats.reusedPromptTokens)
        assertEquals("Roses are red, violets", test.fake.memoryText)
        assertEquals(" violets", second.done().text)
    }

    // --- Cancellation in the middle of a retraction ---

    @Test
    fun `the engine cancelling in the middle of a chain of deletions reports the text as it stands`() = runTest {
        val test = loaded()
        val (a, b, c, d) = test.pieces(" a", " b", " c", " d")
        test.fake.enqueueReply(listOf(a, b, c, bs, bs, bs, d))
        var samples = 0
        var forgetting: Job? = null
        test.fake.beforeCall = { call ->
            // At the second of the three deletions the engine is told to forget.
            if (call is Call.SampleNext && ++samples == 5) forgetting = launch(start = CoroutineStart.UNDISPATCHED) { test.engine.forget() }
        }
        val events = test.engine.generate(request(user("Letters:"))).toList()
        checkNotNull(forgetting).join()

        val done = events.done()
        assertEquals(FinishReason.CANCELLED, done.finishReason)
        assertEquals("two of the three deletions had been applied", " a", done.text)
        assertEquals(done.text, events.shown())
        assertEquals(2, done.stats.deletions)
        assertEquals(5, done.stats.generatedTokens)
        assertEquals(0, test.fake.memory.size)
    }

    @Test
    fun `a collector leaving in the middle of a chain of deletions leaves the engine usable`() = runTest {
        val test = loaded()
        val (a, b, c, d) = test.pieces(" a", " b", " c", " d")
        test.fake.enqueueReply(listOf(a, b, c, bs, bs, bs, d))
        var samples = 0
        lateinit var collecting: Job
        test.fake.beforeCall = { call -> if (call is Call.SampleNext && ++samples == 5) collecting.cancel() }
        collecting = launch { test.engine.generate(request(user("Letters:"))).collect { } }
        collecting.join()

        // Nothing was sampled after the cancellation, and the deletion sampled last was not evaluated.
        assertEquals(5, test.fake.callsOf<Call.SampleNext>().size)
        assertEquals("Letters: a b c<bs>", test.fake.memoryText)

        // The engine's record of the memory is right: the next request builds on it.
        test.fake.beforeCall = {}
        test.fake.clearCalls()
        test.fake.enqueueReply(test.pieces(" e"))
        val events = test.engine.generate(request(user("Letters:"), assistant(" a"), continueFinalMessage = true)).toList()
        assertEquals(" e", events.done().text)
        assertEquals("Letters:".length, events.done().stats.reusedPromptTokens)
        assertTrue(test.fake.callsOf<Call.ClearMemory>().isEmpty())
        assertEquals("Letters: a e", test.fake.memoryText)
    }

    // --- Chat models are not affected ---

    @Test
    fun `a chat model has no deletion token - the same id is an ordinary token for it`() = runTest {
        val test = loadedEngine(testModel()) { it.addControl("<bs>", id = bs) }
        test.fake.enqueueReply(test.fake.textTokens(" Hi") + bs + test.fake.textTokens(" there \n"))
        val events = test.engine.generate(request(user("Hello"))).toList()

        assertTrue(events.none { it is TextReplaced })
        assertEquals("the reply lost its leading space as before, and nothing else", "Hi there \n", events.done().text)
        assertEquals(events.done().text, events.text())
        assertEquals(0, events.done().stats.deletions)
        assertFalse(events.done().exactText)
        assertNull(testModel().deletionTokenId)
    }

    @Test
    fun `no chat model of the catalogue has a deletion token or continues text`() {
        for (model in ModelCatalog.all - ModelCatalog.SHARD_2) {
            assertNull(model.id, model.deletionTokenId)
            assertFalse(model.id, model.family.continuesText)
        }
    }
}
