package us.theworkmans.proseplow.core.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import us.theworkmans.proseplow.core.engine.FakeNativeLlama.Call

/**
 * The fake native layer must keep the contract of [NativeLlama] itself, or the engine tests
 * would prove nothing about the real one.
 */
class FakeNativeLlamaTest {

    private val fake = FakeNativeLlama(contextTokens = 16)

    private object NeverCancelled : EvaluationObserver {
        override fun onProgress(evaluatedTokens: Int) = Unit
        override fun isCancelled(): Boolean = false
    }

    private fun utf8(text: String) = text.toByteArray(Charsets.UTF_8)

    private fun evaluate(text: String) = fake.evaluate(fake.textTokens(text).toIntArray(), 4, NeverCancelled)

    private fun configure(banned: List<Int> = emptyList()) = fake.configureSampler(
        SamplerConfig(0f, 0, 1f, 0f, 1f, 0f, 0f, penaltyWindow = 16, seed = 1, bannedTokenIds = banned),
    )

    @Test
    fun `text is one token per byte and round-trips`() {
        val text = "h" + String(Character.toChars(0x1F600))
        val tokens = fake.tokenize(utf8(text), parseSpecial = false)
        assertEquals(5, tokens.size)
        assertEquals(text, fake.render(tokens.toList()))
        assertArrayEquals(byteArrayOf(0x68), fake.tokenToBytes(tokens[0]))
        assertEquals(0, fake.tokenize(ByteArray(0), parseSpecial = true).size)
    }

    @Test
    fun `control tokens are matched only with special parsing, longest first, and carry no text`() {
        val short = fake.addControl("<|a|>")
        val long = fake.addControl("<|a|><|b|>")
        assertEquals(listOf(long) + fake.textTokens("x"), fake.tokenize(utf8("<|a|><|b|>x"), parseSpecial = true).toList())
        assertEquals(listOf(short) + fake.textTokens("<|c|>"), fake.tokenize(utf8("<|a|><|c|>"), parseSpecial = true).toList())
        assertEquals(fake.textTokens("<|a|>"), fake.tokenize(utf8("<|a|>"), parseSpecial = false).toList())
        assertEquals(0, fake.tokenToBytes(short).size)
        assertEquals("<|a|>", fake.render(listOf(short)))
    }

    @Test
    fun `ids can be chosen, and clashes are refused`() {
        assertEquals(4, fake.addControl("<|im_end|>", id = 4, endOfGeneration = true))
        assertTrue(fake.isEndOfGeneration(4))
        assertFalse(fake.isEndOfGeneration(3))
        fake.markEndOfGeneration(3)
        assertTrue(fake.isEndOfGeneration(3))
        assertThrows(IllegalArgumentException::class.java) { fake.addControl("<|other|>", id = 4) }
        assertThrows(IllegalArgumentException::class.java) { fake.addControl("<|byte|>", id = fake.byteToken(0x41)) }
        assertTrue(fake.isEndOfGeneration(fake.endToken))
    }

    @Test
    fun `evaluation appends in chunks, reports progress and stops when cancelled`() {
        val progress = ArrayList<Int>()
        val observer = object : EvaluationObserver {
            override fun onProgress(evaluatedTokens: Int) { progress += evaluatedTokens }
            override fun isCancelled(): Boolean = progress.size == 2
        }
        val evaluated = fake.evaluate(fake.textTokens("abcdefghij").toIntArray(), 3, observer)
        assertEquals(6, evaluated)
        assertEquals(listOf(3, 6), progress)
        assertEquals("abcdef", fake.memoryText)
        assertEquals(6, fake.memoryTokenCount())
    }

    @Test
    fun `evaluating beyond the context fails as a full memory`() {
        evaluate("0123456789abcdef")
        val exception = assertThrows(NativeLlamaException::class.java) { evaluate("x") }
        assertEquals(NativeLlamaException.CONTEXT_FULL, exception.code)
    }

    @Test
    fun `rewinding shortens the memory unless the model refuses`() {
        evaluate("abcdef")
        assertTrue(fake.rewindTo(4))
        assertEquals("abcd", fake.memoryText)
        fake.refuseRewind = true
        assertFalse(fake.rewindTo(2))
        assertEquals("a refusal leaves the memory as it was", "abcd", fake.memoryText)
        assertTrue("rewinding to the present length is always possible", fake.rewindTo(4))
        assertThrows(IllegalArgumentException::class.java) { fake.rewindTo(5) }
    }

    @Test
    fun `a checkpoint brings the memory back to where it was saved and can be used again`() {
        evaluate("abc")
        val checkpoint = fake.saveCheckpoint()
        evaluate("def")
        assertEquals(mapOf(checkpoint to 3), fake.checkpoints)
        assertTrue(fake.restoreCheckpoint(checkpoint))
        assertEquals("abc", fake.memoryText)
        evaluate("xyz")
        assertTrue(fake.restoreCheckpoint(checkpoint))
        assertEquals("abc", fake.memoryText)

        fake.dropCheckpoint(checkpoint)
        assertFalse(fake.restoreCheckpoint(checkpoint))
        fake.dropCheckpoint(checkpoint)
    }

    @Test
    fun `restoring a checkpoint over a memory that has changed underneath it is an engine bug`() {
        evaluate("abc")
        val checkpoint = fake.saveCheckpoint()
        fake.rewindTo(1)
        evaluate("zz")
        assertThrows(IllegalStateException::class.java) { fake.restoreCheckpoint(checkpoint) }
    }

    @Test
    fun `clearing the memory frees the checkpoints too`() {
        evaluate("abc")
        val checkpoint = fake.saveCheckpoint()
        fake.clearMemory()
        assertEquals(0, fake.memoryTokenCount())
        assertTrue(fake.checkpoints.isEmpty())
        assertFalse(fake.restoreCheckpoint(checkpoint))
        fake.checkpointsAvailable = false
        assertEquals(NativeLlama.NO_CHECKPOINT, fake.saveCheckpoint())
    }

    @Test
    fun `sampling plays the script of the generation and then ends the turn`() {
        fake.enqueueReply("ab")
        fake.enqueueReply(listOf(fake.addPiece("whole")))
        configure()
        evaluate("p")
        assertEquals(fake.byteToken('a'.code), fake.sampleNext())
        evaluate("a")
        assertEquals(fake.byteToken('b'.code), fake.sampleNext())
        evaluate("b")
        assertEquals(fake.endToken, fake.sampleNext())

        configure()
        evaluate("q")
        val piece = fake.sampleNext()
        assertEquals("whole", String(fake.tokenToBytes(piece), Charsets.UTF_8))
    }

    @Test
    fun `sampling needs a sampler and a fresh, complete evaluation`() {
        evaluate("p")
        assertThrows("no sampler yet", IllegalStateException::class.java) { fake.sampleNext() }
        configure()
        fake.sampleNext()
        assertThrows("twice for one evaluation", IllegalStateException::class.java) { fake.sampleNext() }
        evaluate("a")
        fake.rewindTo(1)
        assertThrows("the prediction went with the rewind", IllegalStateException::class.java) { fake.sampleNext() }
    }

    @Test
    fun `a banned token is never sampled`() {
        val banned = fake.byteToken('x'.code)
        fake.enqueueReply("xaxbx")
        configure(banned = listOf(banned))
        evaluate("p")
        assertEquals(fake.byteToken('a'.code), fake.sampleNext())
        evaluate("a")
        assertEquals(fake.byteToken('b'.code), fake.sampleNext())
        evaluate("b")
        assertEquals(fake.endToken, fake.sampleNext())
    }

    @Test
    fun `calls are recorded in order and nothing works after close`() {
        fake.tokenize(utf8("hi"), parseSpecial = true)
        evaluate("hi")
        fake.rewindTo(1)
        fake.clearMemory()
        fake.close()
        fake.close()
        assertEquals(
            listOf(
                Call.Tokenize("hi", parseSpecial = true),
                Call.Evaluate(fake.textTokens("hi")),
                Call.RewindTo(1),
                Call.ClearMemory,
                Call.Close,
            ),
            fake.calls,
        )
        assertTrue(fake.closed)
        assertEquals(setOf(Thread.currentThread()), fake.threads)
        assertThrows(IllegalStateException::class.java) { fake.clearMemory() }
    }

    @Test
    fun `a hook can inject a failure into any call`() {
        fake.beforeCall = { call -> if (call is Call.RewindTo) throw NativeLlamaException(NativeLlamaException.INVALID_ARGUMENT, "scripted") }
        evaluate("abc")
        val exception = assertThrows(NativeLlamaException::class.java) { fake.rewindTo(1) }
        assertEquals(NativeLlamaException.INVALID_ARGUMENT, exception.code)
        assertEquals("scripted", exception.message)
    }

    @Test
    fun `the factory opens fakes, reports progress and can be told to fail`() {
        val factory = FakeNativeLlamaFactory()
        val reported = ArrayList<Float>()
        val native = factory.open(testModel(contextLength = 321), { fraction -> reported.add(fraction) }, 321, Long.MAX_VALUE)
        assertEquals(listOf(0f, 0.5f, 1f), reported)
        assertEquals(321, native.contextSize())
        assertEquals(native, factory.last)

        val cancelled = assertThrows(NativeLlamaException::class.java) { factory.open(testModel(), { false }, 4096, Long.MAX_VALUE) }
        assertEquals(NativeLlamaException.LOAD_CANCELLED, cancelled.code)
        assertEquals(1, factory.opened.size)

        factory.failure = NativeLlamaException(NativeLlamaException.LOAD_FAILED, "scripted")
        assertEquals(NativeLlamaException.LOAD_FAILED, assertThrows(NativeLlamaException::class.java) { factory.open(testModel(), { true }, 4096, Long.MAX_VALUE) }.code)
    }
}
