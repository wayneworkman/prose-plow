package us.theworkmans.proseplow.llama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import us.theworkmans.proseplow.core.engine.MemorySink
import us.theworkmans.proseplow.core.engine.MemorySource
import us.theworkmans.proseplow.core.engine.NativeLlama
import us.theworkmans.proseplow.core.engine.NativeLlamaException
import java.nio.ByteBuffer

/**
 * What [LlamaSession] refuses before native code is reached when a memory image is to go out or
 * come in. The native library is not loaded in these tests, so a call that got as far as
 * [LlamaJni] would fail with [UnsatisfiedLinkError] instead of the exception expected: each
 * refusal here is proved to happen on the Kotlin side.
 */
class LlamaSessionMemoryArgumentsTest {

    /** A handle that is never dereferenced: no call below reaches native code. */
    private val session = LlamaSession(handle = 1L)

    private val sink = MemorySink { _, _ -> error("the sink was called") }
    private val source = MemorySource { error("the source was called") }

    private fun refused(block: () -> Unit) {
        val e = assertThrows(NativeLlamaException::class.java) { block() }
        assertEquals(e.message, NativeLlamaException.INVALID_ARGUMENT, e.code)
    }

    @Test
    fun `a buffer that is not direct never reaches native code`() {
        val heap = ByteBuffer.allocate(PIECE)
        refused { session.exportMemory(IntArray(0), heap, 0, sink) }
        refused { session.importMemory(heap, 0, source) }
    }

    @Test
    fun `a read-only buffer never reaches native code, which would write to it`() {
        val readOnly = ByteBuffer.allocateDirect(PIECE).asReadOnlyBuffer()
        refused { session.exportMemory(IntArray(0), readOnly, 0, sink) }
        refused { session.importMemory(readOnly, 0, source) }
    }

    @Test
    fun `a buffer below the smallest piece, and an offset outside the buffer, are refused`() {
        val small = ByteBuffer.allocateDirect(PIECE - 1)
        refused { session.exportMemory(IntArray(0), small, 0, sink) }
        refused { session.importMemory(small, 0, source) }
        val direct = ByteBuffer.allocateDirect(PIECE)
        for (offset in listOf(-1, PIECE, Int.MAX_VALUE)) {
            refused { session.exportMemory(IntArray(0), direct, offset, sink) }
            refused { session.importMemory(direct, offset, source) }
        }
    }

    @Test
    fun `more checkpoints than a session holds are refused before they are copied`() {
        val tooMany = IntArray(NativeLlama.MAX_MEMORY_CHECKPOINTS + 1) { it }
        refused { session.memoryStateBytes(tooMany) }
        refused { session.exportMemory(tooMany, ByteBuffer.allocateDirect(PIECE), 0, sink) }
    }

    @Test
    fun `the smallest piece and the most checkpoints are the native session's`() {
        // PP_MEMORY_PIECE_BYTES_MIN and PP_CHECKPOINTS_MAX of proseplow_session.h, repeated on
        // purpose so that moving one is noticed here as well as on the phone.
        assertEquals(4096, NativeLlama.MEMORY_PIECE_BYTES_MIN)
        assertEquals(8, NativeLlama.MAX_MEMORY_CHECKPOINTS)
    }

    @Test
    fun `a closed session never reaches native code`() {
        val closed = LlamaSession(handle = 0L)
        val direct = ByteBuffer.allocateDirect(PIECE)
        assertThrows(IllegalStateException::class.java) { closed.memoryTokens() }
        assertThrows(IllegalStateException::class.java) { closed.memoryStateBytes(IntArray(0)) }
        assertThrows(IllegalStateException::class.java) { closed.exportMemory(IntArray(0), direct, 0, sink) }
        assertThrows(IllegalStateException::class.java) { closed.importMemory(direct, 0, source) }
    }

    private companion object {
        const val PIECE: Int = NativeLlama.MEMORY_PIECE_BYTES_MIN
    }
}
