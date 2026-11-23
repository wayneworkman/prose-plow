package us.theworkmans.proseplow.llama

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import us.theworkmans.proseplow.core.engine.MemorySink
import us.theworkmans.proseplow.core.engine.MemorySource
import us.theworkmans.proseplow.core.engine.NativeLlama
import us.theworkmans.proseplow.core.engine.NativeLlamaException
import us.theworkmans.proseplow.core.model.ModelCatalog
import us.theworkmans.proseplow.core.model.ModelSpec
import java.nio.ByteBuffer

/**
 * The memory written out as an image and read back through the JNI binding
 * ([NativeLlama.exportMemory], [NativeLlama.importMemory]), for every model that is present.
 *
 * For each: a prompt with two checkpoints is written out, then continued as the control; the
 * memory is forgotten, and imports that end in the middle of the state (a source that throws,
 * one that stops, one whose image ends) must each leave an empty memory; then the image is read
 * back, and the next token and a short greedy run, and the same after going back through a
 * restored checkpoint, must equal the control's. Exports stopped by the sink, by false and by an
 * exception, must leave the memory as it was. The size of the image and the time of the export
 * and of the import are reported; they move the image between the native session and a direct
 * buffer in native memory, without encryption or storage.
 *
 * A source that throws in the middle of the state is the case that crosses the most: the
 * session calls the source from inside llama.cpp's reading of the state, the binding answers
 * the exception with "stop", and the session carries that out through llama.cpp's frames as a
 * C++ exception (between this library and `libllama.so`). It must end in the Kotlin exception,
 * not in a crash.
 *
 * The prompt is shorter than the window of Shard-2's local layers, within which its memory comes
 * back cell for cell, so that its run is held to the same exactness as the others.
 */
@RunWith(AndroidJUnit4::class)
class MemoryImageDeviceTest {

    @get:Rule
    val inFront = ActivityScenarioRule(ForegroundActivity::class.java)

    @Test
    fun peacebell() = roundTrip(ModelCatalog.PEACEBELL_V1_291M)

    @Test
    fun boris() = roundTrip(ModelCatalog.BORIS_2_INSTRUCT)

    @Test
    fun qwen() = roundTrip(ModelCatalog.QWEN35_0_8B)

    @Test
    fun shard() = roundTrip(ModelCatalog.SHARD_2)

    private class Boom : RuntimeException("thrown by the test's callback")

    /** Hands out the image kept in [store] in pieces of [buffer]'s size; [onRead] may stop it at a given call. */
    private class StoreSource(
        private val buffer: ByteBuffer,
        store: ByteBuffer,
        private val onRead: (call: Int) -> Int? = { null },
    ) : MemorySource {
        private val from: ByteBuffer = store.duplicate().also { it.rewind() }
        private var calls = 0

        override fun read(): Int {
            onRead(calls++)?.let { return it }
            val n = minOf(buffer.capacity(), from.remaining())
            if (n == 0) return 0
            val piece = from.duplicate()
            piece.limit(piece.position() + n)
            buffer.clear()
            buffer.put(piece)
            from.position(from.position() + n)
            return n
        }
    }

    private fun roundTrip(model: ModelSpec) {
        assumePresent(model)
        val native = open(model)
        try {
            val isEnd = { token: Int -> native.isEndOfGeneration(token) || token in model.stopTokenIds }
            // At most 240 tokens, below the window of 256 (a model with a small vocabulary makes more of the passage).
            val prompt = native.tokens(PASSAGE, false).let { it.copyOf(minOf(it.size, MAX_PROMPT)) }
            assertTrue("the passage is ${prompt.size} tokens", prompt.size >= 64)
            val split = prompt.size / 2
            val first = prompt.copyOfRange(0, split)
            val second = prompt.copyOfRange(split, prompt.size - 1)
            val lastToken = intArrayOf(prompt.last())
            val held = prompt.size - 1

            native.mustEvaluate(first)
            val early = native.saveCheckpoint()
            native.mustEvaluate(second)
            val late = native.saveCheckpoint()
            assertTrue("two checkpoints are saved", early >= 0 && late >= 0)
            val named = intArrayOf(late, early)
            val imageBytes = native.memoryStateBytes(named)
            val withoutCheckpoints = native.memoryStateBytes(IntArray(0))
            assertTrue("the image of ${model.id} is $imageBytes bytes", imageBytes > withoutCheckpoints && withoutCheckpoints > IMAGE_HEADER_BYTES)
            val record = native.memoryTokens()
            assertArrayEquals("the record of the tokens is the prompt so far", prompt.copyOf(held), record)
            record.fill(0)

            // Exports that end early leave the memory as it was, and the buffer overwritten.
            val small = ByteBuffer.allocateDirect(SMALL_PIECE)
            var handed = 0
            expectFailure(NativeLlamaException.MEMORY_STOPPED, "an export the sink stops at its third piece") {
                native.exportMemory(named, small, 0) { _, _ -> ++handed < 3 }
            }
            assertEquals(3, handed)
            assertTrue("a stopped export overwrites the buffer", small.isAllZero())
            try {
                native.exportMemory(named, small, 0) { _, _ -> throw Boom() }
                fail("the sink's exception was swallowed")
            } catch (_: Boom) {
                // expected: the sink's own exception, after the session has returned
            }
            assertTrue("an export ended by an exception overwrites the buffer", small.isAllZero())
            assertEquals("an export only reads", held, native.memoryTokenCount())

            // The export that is kept, with a header of the caller's at the start of the first piece.
            val buffer = ByteBuffer.allocateDirect(PIECE)
            val store = ByteBuffer.allocateDirect(HEADER_BYTES + imageBytes.toInt())
            for (index in 0 until HEADER_BYTES) buffer.put(index, headerByte(index))
            var pieces = 0
            var lastPieces = 0
            val sink = MemorySink { bytes, last ->
                if (pieces++ == 0) {
                    for (index in 0 until HEADER_BYTES) check(buffer.get(index) == headerByte(index)) { "the caller's own bytes were changed" }
                }
                if (last) lastPieces++
                val piece = buffer.duplicate()
                piece.clear()
                piece.limit(bytes)
                store.put(piece)
                true
            }
            val exportStart = System.nanoTime()
            val written = native.exportMemory(named, buffer, HEADER_BYTES, sink)
            val exportNanos = System.nanoTime() - exportStart
            assertEquals("the export hands over what was announced", imageBytes, written)
            assertEquals("the last piece is said once", 1, lastPieces)
            assertEquals("every byte arrived", HEADER_BYTES + imageBytes.toInt(), store.position())
            assertTrue("the export overwrites the buffer", buffer.isAllZero())
            store.flip()

            // The control: the memory as it was, continued, and continued again after going back.
            native.mustEvaluate(lastToken)
            native.configureSampler(greedy(native.contextSize(), model.bannedTokenIds))
            val control = native.continueReply(TAIL, isEnd)
            assertTrue(native.restoreCheckpoint(early))
            native.mustEvaluate(second + lastToken)
            native.configureSampler(greedy(native.contextSize(), model.bannedTokenIds))
            val controlBack = native.continueReply(TAIL, isEnd)
            native.clearMemory()

            // Imports that end in the middle of the state leave nothing behind.
            val stateStart = HEADER_BYTES + IMAGE_HEADER_BYTES + 4 * held
            val stateMiddle = stateStart + (HEADER_BYTES + withoutCheckpoints.toInt() - stateStart) / 2
            val middlePiece = stateMiddle / SMALL_PIECE
            try {
                native.importMemory(small, HEADER_BYTES, StoreSource(small, store) { if (it == middlePiece) throw Boom() else null })
                fail("the source's exception was swallowed")
            } catch (_: Boom) {
                // expected: the source's own exception, after the session has emptied the memory
            }
            assertNothingLeft(native, small, "a source that throws in the middle of the state")
            expectFailure(NativeLlamaException.MEMORY_STOPPED, "an import the source stops in the middle of the state") {
                native.importMemory(small, HEADER_BYTES, StoreSource(small, store) { if (it == middlePiece) -1 else null })
            }
            assertNothingLeft(native, small, "a source that stops in the middle of the state")
            expectFailure(NativeLlamaException.MEMORY_FAILED, "an image that ends in the middle of the state") {
                native.importMemory(small, HEADER_BYTES, StoreSource(small, store) { if (it == middlePiece) 0 else null })
            }
            assertNothingLeft(native, small, "an image that ends in the middle of the state")

            // The import that is kept: refused if a failed one had left a checkpoint behind.
            val importStart = System.nanoTime()
            val restored = native.importMemory(buffer, HEADER_BYTES, StoreSource(buffer, store))
            val importNanos = System.nanoTime() - importStart
            assertTrue("the import overwrites the buffer", buffer.isAllZero())
            assertEquals("the checkpoints come back in the order named", listOf(held, split), restored.map { it.tokenCount })
            assertEquals(held, native.memoryTokenCount())
            val back = native.memoryTokens()
            assertArrayEquals("the record of the tokens comes back", prompt.copyOf(held), back)
            back.fill(0)
            expectFailure(NativeLlamaException.SAMPLING_FAILED, "sampling straight after an import") { native.sampleNext() }

            native.mustEvaluate(lastToken)
            native.configureSampler(greedy(native.contextSize(), model.bannedTokenIds))
            assertEquals("the next token and a greedy run after the import are the control's", control, native.continueReply(TAIL, isEnd))
            assertTrue("a restored checkpoint can be gone back to", native.restoreCheckpoint(restored[1].handle))
            native.mustEvaluate(second + lastToken)
            native.configureSampler(greedy(native.contextSize(), model.bannedTokenIds))
            assertEquals("and gives the control's run after going back", controlBack, native.continueReply(TAIL, isEnd))

            val mib = imageBytes / 1048576.0
            report(
                "${model.id}: memory image of $held tokens with 2 checkpoints: $imageBytes bytes (%.1f MiB, %.1f MiB without the checkpoints); "
                    .format(mib, withoutCheckpoints / 1048576.0) +
                    "export in pieces of 4 MiB %.1f ms (%.0f MiB/s), import %.1f ms (%.0f MiB/s), each to or from a direct buffer, without encryption or storage"
                        .format(exportNanos / 1e6, mib * 1e9 / exportNanos, importNanos / 1e6, mib * 1e9 / importNanos),
            )
            store.wipe()
        } finally {
            native.close()
        }
    }

    private fun assertNothingLeft(native: NativeLlama, buffer: ByteBuffer, what: String) {
        assertEquals("$what leaves no token in memory", 0, native.memoryTokenCount())
        assertEquals("$what leaves an empty record of the tokens", 0, native.memoryTokens().size)
        assertTrue("$what overwrites the buffer", buffer.isAllZero())
    }

    private fun headerByte(index: Int): Byte = (0xA5 xor index).toByte()

    private companion object {
        /** How many tokens are generated to compare a memory that was read back with the control. */
        const val TAIL: Int = 16

        /** The pieces of the export and the import that are timed: the size the app means to seal. */
        const val PIECE: Int = 4 shl 20

        /** The pieces of the exports and imports that end early: many of them across the state. */
        const val SMALL_PIECE: Int = 64 shl 10

        /** Bytes of the caller's own at the start of the first piece. */
        const val HEADER_BYTES: Int = 64

        /** The fixed part an image begins with (native/README.md, "A memory image"). */
        const val IMAGE_HEADER_BYTES: Int = 176

        /** The longest prompt: below the 256 tokens of Shard-2's window. */
        const val MAX_PROMPT: Int = 240

        /** About 120 to 200 tokens of plain text, depending on the vocabulary; written for this project. */
        const val PASSAGE: String =
            "A lighthouse keeper on a small island kept a notebook in which she wrote down the weather every " +
                "morning and every evening: the direction of the wind, the height of the waves, the colour of the " +
                "sky and the number of ships that passed. After twenty years the notebook had become a shelf of " +
                "notebooks, and a scientist from the mainland asked whether he might borrow them. He found that " +
                "the storms had come a little earlier each autumn, and that the keeper had noticed it long before " +
                "any instrument did. When he thanked her, she said that she had only been paying attention."
    }
}

/** True when every byte of the buffer, from 0 to its capacity, is zero; its position and limit are left alone. */
internal fun ByteBuffer.isAllZero(): Boolean {
    val view = duplicate()
    view.clear()
    val chunk = ByteArray(65536)
    while (view.hasRemaining()) {
        val n = minOf(chunk.size, view.remaining())
        view.get(chunk, 0, n)
        for (index in 0 until n) if (chunk[index] != 0.toByte()) return false
    }
    return true
}

/** Overwrites the buffer with zeros from 0 to its capacity. */
internal fun ByteBuffer.wipe() {
    val view = duplicate()
    view.clear()
    val zeros = ByteArray(65536)
    while (view.hasRemaining()) view.put(zeros, 0, minOf(zeros.size, view.remaining()))
}
