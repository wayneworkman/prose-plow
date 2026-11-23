package us.theworkmans.proseplow.core.engine

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * A [MemoryVault] in memory, for testing the engine's saving and restoring. It keeps the image
 * as the pieces arrived, with a header of its own in front, and hands it back in pieces of the
 * same size. It checks what a real vault relies on: that the engine leaves the vault's header in
 * the first piece alone, that a save is committed only after its last piece, and that nothing is
 * kept of a save that was abandoned.
 *
 * Failures are injected with the switches; [beforeWrite] and [beforeRead] run before each piece
 * and let a test act in the middle of a save or a restore. What happened is counted.
 *
 * Not thread-safe; the engine calls it from its native thread only.
 *
 * @param pieceBytes the capacity of the vault's buffer.
 * @param headerBytes how many bytes of the first piece are the vault's own header.
 */
class FakeMemoryVault(
    private val pieceBytes: Int = NativeLlama.MEMORY_PIECE_BYTES_MIN,
    private val headerBytes: Int = 40,
) : MemoryVault {

    init {
        require(pieceBytes >= NativeLlama.MEMORY_PIECE_BYTES_MIN && headerBytes in 0 until pieceBytes)
    }

    /** The header of the image the vault holds, or null. */
    var image: SavedMemory? = null
        private set

    private var stored: ByteArray? = null

    /** The bytes of the image held, the vault's header included; for comparing two saves. */
    val storedBytes: ByteArray? get() = stored?.copyOf()

    /** What [onCommit] returned at the commit of the image held: the test's note of what it is an image of. */
    var note: Any? = null
        private set

    /** Called at every commit; what it returns is kept as [note] with the image. */
    var onCommit: () -> Any? = { null }

    // --- Switches ---

    /** When true, [saved] throws, as when the vault's own checks cannot run. */
    var savedFails: Boolean = false

    /**
     * When true, [saved] and [beginRestore] throw [MemoryVaultUnavailableException], as when the
     * key was destroyed by a lock: the vault cannot tell now, and nothing speaks against the image.
     */
    var unavailable: Boolean = false

    /** When true, [beginRestore] (but not [saved]) throws [MemoryVaultUnavailableException]. */
    var restoreUnavailable: Boolean = false

    /** When true, [beginRestore] throws as a vault does that read the image again and refused it. */
    var restoreRefused: Boolean = false

    /** When set, [MemorySource.read] throws [MemoryVaultUnavailableException] at the piece with this index. */
    var readUnavailableAtPiece: Int? = null

    /** Changes what [saved] and the reader report of the image, as a header that does not agree with the image would. */
    var reportAs: ((SavedMemory) -> SavedMemory)? = null

    /** When set, [MemoryWriter.write] throws at the piece with this index, as on a full disk. */
    var writeFailsAtPiece: Int? = null

    /** When set, [MemorySource.read] returns -1 at the piece with this index, as when a piece does not authenticate. */
    var readStopsAtPiece: Int? = null

    /** When true, [delete] throws, as when the file cannot be removed. */
    var deleteFails: Boolean = false

    /** Runs before each piece of a save with its index. */
    var beforeWrite: (piece: Int) -> Unit = {}

    /** Runs before each piece of a restore with its index. */
    var beforeRead: (piece: Int) -> Unit = {}

    /** Runs at every [delete], before anything is deleted: for a test of what the engine has done by then. */
    var onDelete: () -> Unit = {}

    // --- What happened ---

    var deletes: Int = 0
        private set
    var savesBegun: Int = 0
        private set
    var commits: Int = 0
        private set
    var restoresBegun: Int = 0
        private set

    /** The headers the engine gave at every save begun, in order. */
    val headers = ArrayList<SavedMemory>()

    /** Puts an image in place directly, as one a former session left: [bytes] are as [storedBytes] gave them. */
    fun put(header: SavedMemory, bytes: ByteArray, note: Any? = null) {
        image = header
        stored = bytes.copyOf()
        this.note = note
    }

    private fun reported(): SavedMemory? = image?.let { reportAs?.invoke(it) ?: it }

    override fun saved(): SavedMemory? {
        if (savedFails) throw IllegalStateException("the vault could not check its image")
        if (unavailable) throw MemoryVaultUnavailableException("the key is gone")
        return reported()
    }

    override fun delete() {
        onDelete()
        deletes++
        if (deleteFails) throw IllegalStateException("the image could not be deleted")
        image = null
        stored = null
        note = null
    }

    /** The engine's stop condition of the latest save begun ([MemoryVault.beginSave]); for asking it from a test. */
    var lastStopCondition: (() -> Boolean)? = null
        private set

    override fun beginSave(memory: SavedMemory, stopped: () -> Boolean): MemoryWriter {
        savesBegun++
        headers += memory
        lastStopCondition = stopped
        return Writer(memory)
    }

    override fun beginRestore(): MemoryReader {
        restoresBegun++
        if (unavailable || restoreUnavailable) throw MemoryVaultUnavailableException("the key is gone")
        if (restoreRefused) throw IllegalStateException("the image was read again and refused")
        val header = checkNotNull(reported()) { "a restore of nothing" }
        return Reader(header, checkNotNull(stored))
    }

    private inner class Writer(private val memory: SavedMemory) : MemoryWriter {
        override val buffer: ByteBuffer = ByteBuffer.allocateDirect(pieceBytes)
        override val firstPieceOffset: Int = headerBytes
        private val out = ByteArrayOutputStream()
        private var piece = 0
        private var sawLast = false
        private var committed = false

        init {
            for (index in 0 until headerBytes) buffer.put(index, HEADER_BYTE)
        }

        override fun write(bytes: Int, last: Boolean): Boolean {
            check(!sawLast && !committed) { "a piece after the last" }
            beforeWrite(piece)
            if (writeFailsAtPiece == piece) throw IllegalStateException("no room for piece $piece")
            if (piece == 0) {
                for (index in 0 until headerBytes) check(buffer.get(index) == HEADER_BYTE) { "the vault's header was changed" }
            }
            for (index in 0 until bytes) out.write(buffer.get(index).toInt())
            piece++
            sawLast = last
            return true
        }

        override fun commit() {
            check(sawLast) { "a commit before the last piece" }
            check(!committed)
            committed = true
            commits++
            image = memory
            stored = out.toByteArray()
            note = onCommit()
        }

        override fun close() {
            out.reset()
        }
    }

    private inner class Reader(override val memory: SavedMemory, private val bytes: ByteArray) : MemoryReader {
        override val buffer: ByteBuffer = ByteBuffer.allocateDirect(pieceBytes)
        override val firstPieceOffset: Int = headerBytes
        private var at = 0
        private var piece = 0

        override fun read(): Int {
            if (at == bytes.size) return 0
            beforeRead(piece)
            if (readStopsAtPiece == piece) return -1
            if (readUnavailableAtPiece == piece) throw MemoryVaultUnavailableException("the key is gone")
            val n = minOf(pieceBytes, bytes.size - at)
            for (index in 0 until n) buffer.put(index, bytes[at + index])
            at += n
            piece++
            return n
        }

        override fun close() = Unit
    }

    private companion object {
        const val HEADER_BYTE: Byte = 0x5A
    }
}
