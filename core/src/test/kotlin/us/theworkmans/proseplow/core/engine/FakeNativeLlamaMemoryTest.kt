package us.theworkmans.proseplow.core.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * The fake's memory images keep the contract of [NativeLlama.exportMemory] and
 * [NativeLlama.importMemory] as the native session states it, so that the engine's save and
 * restore can be tested against it: pieces of the buffer's size, the caller's own first bytes,
 * a buffer overwritten afterwards, an import that is all or nothing.
 */
class FakeNativeLlamaMemoryTest {

    private val fake = FakeNativeLlama(contextTokens = 1024)

    private object NeverCancelled : EvaluationObserver {
        override fun onProgress(evaluatedTokens: Int) = Unit
        override fun isCancelled(): Boolean = false
    }

    private val header = ByteArray(HEADER_BYTES) { (it + 1).toByte() }

    private fun evaluate(count: Int, first: Int = 0) {
        fake.evaluate(IntArray(count) { fake.byteToken(first + it) }, 16, NeverCancelled)
    }

    /** A memory of 200 tokens with checkpoints at 50 and 150, named in that order. */
    private fun conversation(): IntArray {
        evaluate(50)
        val at50 = fake.saveCheckpoint()
        evaluate(100, first = 50)
        val at150 = fake.saveCheckpoint()
        evaluate(50, first = 150)
        return intArrayOf(at150, at50)
    }

    private class Piece(val bytes: ByteArray, val last: Boolean)

    /** Exports with [header] at the start of the first piece and returns copies of the pieces. */
    private fun export(checkpoints: IntArray, buffer: ByteBuffer = ByteBuffer.allocateDirect(PIECE)): List<Piece> {
        val pieces = ArrayList<Piece>()
        for (index in header.indices) buffer.put(index, header[index])
        val written = fake.exportMemory(checkpoints, buffer, HEADER_BYTES) { bytes, last ->
            pieces += Piece(ByteArray(bytes) { buffer.get(it) }, last)
            true
        }
        assertEquals("the bytes handed over are the image, the caller's own not counted", pieces.sumOf { it.bytes.size } - HEADER_BYTES.toLong(), written)
        return pieces
    }

    /** A source that hands over [bytes] in pieces of at most [step] bytes, then 0. */
    private class Feed(private val buffer: ByteBuffer, private val bytes: ByteArray, private val step: Int) : MemorySource {
        var at = 0
        var calls = 0
        override fun read(): Int {
            calls++
            val n = minOf(step, bytes.size - at)
            for (index in 0 until n) buffer.put(index, bytes[at + index])
            at += n
            return n
        }
    }

    private fun joined(pieces: List<Piece>): ByteArray = ByteArrayOutputStream().also { out -> pieces.forEach { out.write(it.bytes) } }.toByteArray()

    private fun ByteBuffer.isZero(): Boolean = (0 until capacity()).all { get(it) == 0.toByte() }

    private fun assertEmpty() {
        assertEquals(0, fake.memoryTokenCount())
        assertTrue(fake.checkpoints.isEmpty())
        assertEquals(0, fake.memoryTokens().size)
    }

    @Test
    fun `an image goes out in pieces of the buffer's size and comes back whole`() {
        val checkpoints = conversation()
        val tokens = fake.memoryTokens()
        val size = fake.memoryStateBytes(checkpoints)
        val buffer = ByteBuffer.allocateDirect(PIECE)
        val pieces = export(checkpoints, buffer)

        assertEquals(size, pieces.sumOf { it.bytes.size } - HEADER_BYTES.toLong())
        assertTrue("several pieces", pieces.size > 3)
        assertTrue("every piece but the last is the buffer's size", pieces.dropLast(1).all { it.bytes.size == PIECE })
        assertTrue(pieces.last().bytes.size in 1..PIECE)
        assertEquals("last exactly once", 1, pieces.count { it.last })
        assertTrue("and at the end", pieces.last().last)
        assertArrayEquals("the caller's own bytes go out first, as they were", header, pieces.first().bytes.copyOf(HEADER_BYTES))
        assertTrue("the buffer is overwritten afterwards, the caller's bytes too", buffer.isZero())
        assertEquals("an export only reads", tokens.toList(), fake.memory)

        fake.clearMemory()
        val image = joined(pieces)
        val feed = Feed(buffer, image, 1000) // pieces need not be full, nor of the export's size
        val restored = fake.importMemory(buffer, HEADER_BYTES, feed)

        assertEquals(tokens.toList(), fake.memory)
        assertArrayEquals(tokens, fake.memoryTokens())
        assertEquals("the checkpoints in the order they were named, with their lengths", listOf(150, 50), restored.map { it.tokenCount })
        assertTrue("under new handles", restored.none { it.handle in checkpoints.toList() })
        assertEquals(restored.associate { it.handle to it.tokenCount }, fake.checkpoints)
        assertEquals("asked once more after the last byte", image.size, feed.at)
        assertTrue(buffer.isZero())

        // A restored checkpoint takes the memory back, and the memory goes on.
        assertTrue(fake.restoreCheckpoint(restored[1].handle))
        assertEquals(tokens.toList().subList(0, 50), fake.memory)
        evaluate(10, first = 50)
    }

    @Test
    fun `an image with no checkpoints and no caller's bytes, of exactly one piece's multiple`() {
        fake.stateBytesPerToken = 0
        // A fixed header of 24 bytes and 4 per token: 1018 tokens make exactly one piece of 4096.
        evaluate(1018)
        val buffer = ByteBuffer.allocateDirect(PIECE)
        val pieces = ArrayList<Piece>()
        val written = fake.exportMemory(IntArray(0), buffer, 0) { bytes, last ->
            pieces += Piece(ByteArray(bytes) { buffer.get(it) }, last)
            true
        }
        assertEquals(4096L, written)
        assertEquals(1, pieces.size)
        assertTrue(pieces[0].last && pieces[0].bytes.size == PIECE)
        fake.clearMemory()
        assertTrue(fake.importMemory(buffer, 0, Feed(buffer, pieces[0].bytes, PIECE)).isEmpty())
        assertEquals(1018, fake.memoryTokenCount())
    }

    @Test
    fun `an export fails or is stopped at a chosen piece and changes nothing`() {
        val checkpoints = conversation()
        val before = fake.memory.toList()
        val buffer = ByteBuffer.allocateDirect(PIECE)

        fake.exportFailsAtPiece = 2
        var handed = 0
        val failed = assertThrows(NativeLlamaException::class.java) {
            fake.exportMemory(checkpoints, buffer, 0) { _, _ -> handed++; true }
        }
        assertEquals(NativeLlamaException.MEMORY_FAILED, failed.code)
        assertEquals(2, handed)
        assertTrue(buffer.isZero())
        fake.exportFailsAtPiece = null

        val stopped = assertThrows(NativeLlamaException::class.java) {
            fake.exportMemory(checkpoints, buffer, 0) { _, _ -> false }
        }
        assertEquals(NativeLlamaException.MEMORY_STOPPED, stopped.code)
        assertTrue(buffer.isZero())

        class Boom : RuntimeException("thrown by the test's sink")
        val boom = Boom()
        assertSame(boom, assertThrows(Boom::class.java) { fake.exportMemory(checkpoints, buffer, 0) { _, _ -> throw boom } })
        assertTrue(buffer.isZero())

        assertEquals(before, fake.memory)
        assertEquals(setOf(150, 50), fake.checkpoints.values.toSet())
    }

    @Test
    fun `an import that ends early, at any piece and for any reason, leaves nothing behind`() {
        val pieces = export(conversation())
        val image = joined(pieces)
        fake.clearMemory()
        val buffer = ByteBuffer.allocateDirect(PIECE)

        fun expect(code: Int, source: MemorySource) {
            val e = assertThrows(NativeLlamaException::class.java) { fake.importMemory(buffer, HEADER_BYTES, source) }
            assertEquals(e.message, code, e.code)
            assertEmpty()
            assertTrue(buffer.isZero())
        }

        for (piece in 0 until pieces.size) {
            fake.importFailsAtPiece = piece
            expect(NativeLlamaException.MEMORY_FAILED, Feed(buffer, image, PIECE))
        }
        fake.importFailsAtPiece = null

        // The source stops in the middle, at the end, or cuts the image short.
        expect(NativeLlamaException.MEMORY_STOPPED, object : MemorySource {
            val feed = Feed(buffer, image, PIECE)
            override fun read(): Int = if (feed.calls == 2) -1 else feed.read()
        })
        expect(NativeLlamaException.MEMORY_STOPPED, object : MemorySource {
            val feed = Feed(buffer, image, PIECE)
            override fun read(): Int = if (feed.at == image.size) -1 else feed.read()
        })
        expect(NativeLlamaException.MEMORY_FAILED, Feed(buffer, image.copyOf(image.size - 1), PIECE))
        // Something behind the image, in its last piece or as another piece.
        expect(NativeLlamaException.MEMORY_FAILED, Feed(buffer, image + byteArrayOf(0), PIECE))
        expect(NativeLlamaException.MEMORY_FAILED, object : MemorySource {
            val feed = Feed(buffer, image, PIECE)
            override fun read(): Int = if (feed.at == image.size) 1 else feed.read()
        })
        // A first piece shorter than the caller's own bytes, and a piece larger than the buffer.
        expect(NativeLlamaException.MEMORY_FAILED, Feed(buffer, image, HEADER_BYTES - 1))
        expect(NativeLlamaException.MEMORY_FAILED, object : MemorySource {
            override fun read(): Int = PIECE + 1
        })
        // A state that is not the one written.
        expect(NativeLlamaException.MEMORY_FAILED, Feed(buffer, image.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }, PIECE))

        // An exception from the source stops the import and comes out as itself.
        class Boom : RuntimeException("thrown by the test's source")
        val feed = Feed(buffer, image, PIECE)
        assertThrows(Boom::class.java) { fake.importMemory(buffer, HEADER_BYTES) { if (feed.calls == 1) throw Boom() else feed.read() } }
        assertEmpty()
        assertTrue(buffer.isZero())

        // And the image still goes in afterwards.
        fake.importMemory(buffer, HEADER_BYTES, Feed(buffer, image, PIECE))
        assertEquals(200, fake.memoryTokenCount())
    }

    @Test
    fun `an image with more tokens than the context holds is refused as context full`() {
        val image = joined(export(conversation()))
        fake.clearMemory()
        fake.contextTokens = 199
        val buffer = ByteBuffer.allocateDirect(PIECE)
        val e = assertThrows(NativeLlamaException::class.java) { fake.importMemory(buffer, HEADER_BYTES, Feed(buffer, image, PIECE)) }
        assertEquals(NativeLlamaException.CONTEXT_FULL, e.code)
        assertEmpty()
        fake.contextTokens = 200
        fake.importMemory(buffer, HEADER_BYTES, Feed(buffer, image, PIECE))
        assertEquals(200, fake.memoryTokenCount())
    }

    @Test
    fun `an import needs an empty memory and no checkpoint, and does not call the source otherwise`() {
        val image = joined(export(conversation()))
        val buffer = ByteBuffer.allocateDirect(PIECE)
        val feed = Feed(buffer, image, PIECE)
        val held = assertThrows(NativeLlamaException::class.java) { fake.importMemory(buffer, HEADER_BYTES, feed) }
        assertEquals(NativeLlamaException.INVALID_ARGUMENT, held.code)
        assertEquals(200, fake.memoryTokenCount())

        // A checkpoint without a memory, as after a refused rewind followed by nothing else.
        fake.clearMemory()
        evaluate(4)
        fake.saveCheckpoint()
        assertTrue(fake.rewindTo(0))
        val checkpointHeld = assertThrows(NativeLlamaException::class.java) { fake.importMemory(buffer, HEADER_BYTES, feed) }
        assertEquals(NativeLlamaException.INVALID_ARGUMENT, checkpointHeld.code)
        assertEquals(0, feed.calls)
    }

    @Test
    fun `buffers and checkpoints an image cannot use are refused before a callback is called`() {
        val checkpoints = conversation()
        val sink = MemorySink { _, _ -> error("the sink was called") }
        val source = MemorySource { error("the source was called") }
        fun refused(block: () -> Unit) {
            assertEquals(NativeLlamaException.INVALID_ARGUMENT, assertThrows(NativeLlamaException::class.java) { block() }.code)
        }
        val direct = ByteBuffer.allocateDirect(PIECE)
        for (buffer in listOf(ByteBuffer.allocate(PIECE), direct.asReadOnlyBuffer(), ByteBuffer.allocateDirect(PIECE - 1))) {
            refused { fake.exportMemory(checkpoints, buffer, 0, sink) }
        }
        refused { fake.exportMemory(checkpoints, direct, PIECE, sink) }
        refused { fake.exportMemory(checkpoints, direct, -1, sink) }
        refused { fake.exportMemory(intArrayOf(12345), direct, 0, sink) }
        refused { fake.exportMemory(intArrayOf(checkpoints[0], checkpoints[0]), direct, 0, sink) }
        refused { fake.memoryStateBytes(intArrayOf(12345)) }
        refused { fake.exportMemory(IntArray(NativeLlama.MAX_MEMORY_CHECKPOINTS + 1) { it }, direct, 0, sink) }

        // A checkpoint beyond the end of the memory belongs to tokens the image does not hold.
        assertTrue(fake.rewindTo(100))
        refused { fake.exportMemory(intArrayOf(checkpoints[0]), direct, 0, sink) }
        refused { fake.memoryStateBytes(intArrayOf(checkpoints[0])) }
        assertEquals(fake.memoryStateBytes(intArrayOf(checkpoints[1])), joined(export(intArrayOf(checkpoints[1]))).size - HEADER_BYTES.toLong())

        fake.clearMemory()
        assertEquals("an empty memory has an image of nothing", 0L, fake.memoryStateBytes(IntArray(0)))
        refused { fake.exportMemory(IntArray(0), direct, 0, sink) }
        refused { fake.importMemory(ByteBuffer.allocate(PIECE), 0, source) }
        refused { fake.importMemory(direct, PIECE, source) }
    }

    @Test
    fun `the tokens in memory are handed out as an array the caller can overwrite`() {
        evaluate(3)
        val tokens = fake.memoryTokens()
        assertSame(tokens, fake.memoryTokenArraysReturned.last())
        tokens.fill(0)
        assertEquals("the fake's own record is not that array", 3, fake.memory.count { it != 0 })
    }

    private companion object {
        /** The smallest piece the native session takes. */
        const val PIECE: Int = NativeLlama.MEMORY_PIECE_BYTES_MIN

        const val HEADER_BYTES: Int = 40
    }
}
