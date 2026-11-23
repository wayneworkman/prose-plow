package us.theworkmans.proseplow.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import us.theworkmans.proseplow.security.AppFiles
import us.theworkmans.proseplow.security.ChunkCipher
import us.theworkmans.proseplow.security.DataCrypto
import us.theworkmans.proseplow.security.DataKey
import us.theworkmans.proseplow.security.KeyDestroyedException
import us.theworkmans.proseplow.security.Purposes
import us.theworkmans.proseplow.security.ScriptedRandom
import us.theworkmans.proseplow.security.SealedStream
import us.theworkmans.proseplow.security.StreamHeader
import us.theworkmans.proseplow.security.TestKeys
import us.theworkmans.proseplow.security.UndecryptableException
import us.theworkmans.proseplow.security.contents
import us.theworkmans.proseplow.security.directBuffer
import us.theworkmans.proseplow.security.everything
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * A sealed stream on the disk: what is written, what a reader hands back and what it refuses,
 * what is left in its buffer, and how a write takes the place of the file before it.
 *
 * The streams here have chunks of [SMALL] bytes, so that every cut and every changed byte of a
 * stream of several chunks can be tried; one test writes and reads a stream at the chunk size of
 * version 1.
 */
class SealedStreamFileTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val random = SecureRandom()
    private val keyBytes = ByteArray(DataKey.SIZE).also(random::nextBytes)
    private val cipher = DataCrypto(DataKey(keyBytes))
    private val files by lazy { temp.newFolder("files") }
    private val directory by lazy { File(files, "model-memory") }
    private val id = "3f2a7c1e-5b0d-4e8a-9c1f-0a1b2c3d4e5f"
    private val purpose = Purposes.of("model-memory", id)
    private val time = 1_700_000_000_000L

    private fun streamFile(name: String = "$id.enc", purpose: String = this.purpose, cipher: ChunkCipher = this.cipher) =
        SealedStreamFile(File(directory, name), purpose, cipher, SMALL)

    private fun bytes(size: Int, from: Int = 0) = ByteArray(size) { (from + it).toByte() }

    /** The names of the temporary files in the directory, in order. */
    private fun temporaries(): List<String> = directory.list().orEmpty().filter { it.endsWith(".tmp") }.sorted()

    /** Writes [plaintext] as one stream, piece by piece through one direct buffer, as an export hands its pieces over. */
    private fun SealedStreamFile.writeAll(plaintext: ByteArray, millis: Long = time) {
        write(millis).use { writer ->
            val piece = ByteBuffer.allocateDirect(SMALL)
            var offset = 0
            do {
                val length = minOf(SMALL, plaintext.size - offset)
                val last = offset + length == plaintext.size
                piece.clear()
                piece.put(plaintext, offset, length)
                piece.flip()
                writer.write(piece, last)
                offset += length
            } while (!last)
            writer.commit()
        }
    }

    /**
     * Reads the stream to its end and returns the plaintext, or null when there is no file.
     * Every chunk the reader handed back is added to [released] as it comes, so that a test can
     * say what was given out before a failure.
     */
    private fun SealedStreamFile.readAll(released: MutableList<ByteArray> = ArrayList()): ByteArray? {
        val reader = read() ?: return null
        reader.use {
            while (true) {
                val chunk = it.next() ?: break
                released += chunk.contents()
            }
            assertNull("the end stays the end", it.next())
        }
        return released.fold(ByteArray(0)) { all, chunk -> all + chunk }
    }

    /** The plaintext of 53 bytes most tests store: three full chunks and one of five bytes, 193 bytes on the disk. */
    private val plaintext = bytes(53, from = 60)

    private fun sealedChunks(stream: ByteArray): List<ByteArray> =
        (HEADER until stream.size step SEALED).map { stream.copyOfRange(it, minOf(it + SEALED, stream.size)) }

    private fun assertRefused(what: String, stream: ByteArray, releasedChunks: Int, file: SealedStreamFile = streamFile()) {
        file.file.writeBytes(stream)
        val released = ArrayList<ByteArray>()
        assertThrows(what, UndecryptableException::class.java) { file.readAll(released) }
        assertEquals("$what: chunks handed back before the failure", releasedChunks, released.size)
        // Whatever was handed back is what was sealed there: a failure later takes nothing back, but adds nothing false.
        for ((index, chunk) in released.withIndex()) {
            assertArrayEquals("$what: chunk $index", plaintext.copyOfRange(index * SMALL, minOf((index + 1) * SMALL, plaintext.size)), chunk)
        }
    }

    // --- What is stored ---

    @Test
    fun whatReachesTheDiskIsAStreamAndNeverThePlaintext() {
        val text = "the quick brown fox jumps over the lazy dog, twice over".toByteArray()
        val file = streamFile()
        file.writeAll(text)

        val stored = file.file.readBytes()
        assertTrue(SealedStream.looksSealed(stored))
        assertEquals(SealedStream.sealedSize(text.size.toLong(), SMALL), stored.size.toLong())
        assertFalse(String(stored, Charsets.ISO_8859_1).contains("quick"))
        assertEquals("no temp file is left behind", emptyList<String>(), temporaries())
        assertArrayEquals(text, file.readAll())
        assertEquals(time, SealedStreamFile.readHeader(file.file)!!.oldestMessageMillis)
    }

    @Test
    fun aStreamOfAnySizeComesBackChunkByChunk() {
        for (size in listOf(0, 1, 15, 16, 17, 31, 32, 33, 160, 161)) {
            val content = ByteArray(size).also(random::nextBytes)
            val file = streamFile()
            file.writeAll(content)
            assertEquals("size $size", SealedStream.sealedSize(size.toLong(), SMALL), file.file.length())

            val released = ArrayList<ByteArray>()
            assertArrayEquals("size $size", content, file.readAll(released))
            val expected = if (size == 0) listOf(0) else List((size - 1) / SMALL) { SMALL } + ((size - 1) % SMALL + 1)
            assertEquals("size $size", expected, released.map { it.size })
        }
    }

    @Test
    fun aFileThatIsNotThereReadsAsNothing() {
        assertNull(streamFile().read())
        assertNull(streamFile().readAll())
        assertNull(SealedStreamFile.readHeader(streamFile().file))
        assertFalse("looking makes nothing", directory.exists())
    }

    @Test
    fun anEncryptedFileMustBeNamedAsOne() {
        assertThrows(IllegalArgumentException::class.java) { streamFile(name = "$id.bin") }
        assertThrows(IllegalArgumentException::class.java) { SealedStreamFile(File(directory, "$id.enc"), purpose, cipher, 0) }
    }

    /** Version 1 at its real chunk size is what the public constructor writes. */
    @Test
    fun aStreamOfThreeChunksAtTheChunkSizeOfVersion1IsTheseBytes() {
        val chunkSize = SealedStream.CHUNK_SIZE
        val size = 2 * chunkSize + 5
        fun content(offset: Int) = ((offset * 31 + 7) % 251).toByte()
        val key = ByteArray(DataKey.SIZE) { it.toByte() }
        val nonces = (0..2).map { chunk -> ByteArray(12) { (0xe0 + chunk).toByte() } }
        val scripted = DataCrypto(DataKey(key), ScriptedRandom(bytes(16, from = 0xb0), *nonces.toTypedArray()))
        val file = SealedStreamFile(File(directory, "$id.enc"), purpose, scripted)

        val piece = ByteBuffer.allocateDirect(chunkSize)
        file.write(time).use { writer ->
            var offset = 0
            while (offset < size) {
                val length = minOf(chunkSize, size - offset)
                piece.clear()
                for (position in offset until offset + length) piece.put(content(position))
                piece.flip()
                writer.write(piece, last = offset + length == size)
                offset += length
            }
            writer.commit()
        }

        assertEquals(8_388_725L, file.file.length())
        assertEquals(SealedStream.sealedSize(size.toLong()), file.file.length())
        // Frozen: the digest of these bytes, computed from the written format alone with another
        // implementation of AES-GCM than the one the tests run on.
        val digest = MessageDigest.getInstance("SHA-256")
        file.file.inputStream().use { input ->
            val block = ByteArray(65_536)
            while (true) {
                val count = input.read(block)
                if (count < 0) break
                digest.update(block, 0, count)
            }
        }
        assertEquals("a211ca066c3913bfbed0d9026f7219192c6ad02c107abfe81d3946334a55a259", digest.digest().joinToString("") { "%02x".format(it) })

        val sizes = ArrayList<Int>()
        SealedStreamFile(file.file, purpose, DataCrypto(DataKey(key))).read()!!.use { reader ->
            var offset = 0
            while (true) {
                val chunk = reader.next() ?: break
                assertEquals("one buffer of one chunk, whatever the stream holds", chunkSize + SealedStream.TAG_SIZE, chunk.capacity())
                for (position in 0 until chunk.remaining()) {
                    if (chunk.get(position) != content(offset + position)) fail("byte ${offset + position} came back changed")
                }
                sizes += chunk.remaining()
                offset += chunk.remaining()
            }
        }
        assertEquals(listOf(chunkSize, chunkSize, 5), sizes)
    }

    // --- What is detected ---

    @Test
    fun aByteChangedAnywhereIsDetectedAndNothingOfItsChunkIsHandedBack() {
        val file = streamFile()
        file.writeAll(plaintext)
        val stored = file.file.readBytes()
        assertEquals(HEADER + 3 * SEALED + 5 + 28, stored.size)

        for (position in stored.indices) {
            val changed = stored.copyOf().also { it[position] = (it[position].toInt() xor 0x01).toByte() }
            // A change in the header fails the first chunk; one in a chunk fails that chunk, after those before it.
            assertRefused("byte $position", changed, releasedChunks = if (position < HEADER) 0 else (position - HEADER) / SEALED)
        }
        file.file.writeBytes(stored)
        assertArrayEquals(plaintext, file.readAll())
    }

    @Test
    fun aStreamCutOffAtAnyLengthIsDetected() {
        val file = streamFile()
        file.writeAll(plaintext)
        val stored = file.file.readBytes()

        for (length in 0 until stored.size) {
            val whole = (length - HEADER) / SEALED
            val betweenChunks = length > HEADER && (length - HEADER) % SEALED == 0
            // The chunks that still have something behind them open; the one the file now ends with never does.
            val released = when {
                length <= HEADER -> 0
                betweenChunks -> whole - 1
                else -> whole
            }
            assertRefused("cut to $length bytes", stored.copyOf(length), released)
        }
    }

    /**
     * The cut that leaves nothing damaged: the file ends exactly where a chunk ends, and every
     * chunk in it is whole and authentic. Only the chunk's own word that it is not the last
     * gives the cut away.
     */
    @Test
    fun aStreamThatEndsExactlyBetweenTwoChunksIsDetected() {
        val file = streamFile()
        file.writeAll(plaintext)
        val stored = file.file.readBytes()
        assertRefused("only the first chunk", stored.copyOf(HEADER + SEALED), releasedChunks = 0)
        assertRefused("two chunks", stored.copyOf(HEADER + 2 * SEALED), releasedChunks = 1)
        assertRefused("all but the last chunk", stored.copyOf(HEADER + 3 * SEALED), releasedChunks = 2)
        assertRefused("no chunk at all", stored.copyOf(HEADER), releasedChunks = 0)

        // The same where the last chunk is a full one.
        val full = bytes(48, from = 60)
        file.writeAll(full)
        val even = file.file.readBytes()
        assertEquals(HEADER + 3 * SEALED, even.size)
        assertArrayEquals(full, file.readAll())
        assertRefused("a full last chunk cut off", even.copyOf(HEADER + 2 * SEALED), releasedChunks = 1)
    }

    @Test
    fun chunksLeftOutRepeatedExchangedOrAddedAreDetected() {
        val file = streamFile()
        file.writeAll(plaintext)
        val stored = file.file.readBytes()
        val header = stored.copyOf(HEADER)
        val (first, second, third, last) = sealedChunks(stored)
        fun stream(vararg chunks: ByteArray) = chunks.fold(header) { all, chunk -> all + chunk }
        assertArrayEquals(stored, stream(first, second, third, last))

        assertRefused("the first left out", stream(second, third, last), 0)
        assertRefused("one in the middle left out", stream(first, third, last), 1)
        assertRefused("the first repeated", stream(first, first, second, third, last), 1)
        assertRefused("one in the middle repeated", stream(first, second, second, third, last), 2)
        assertRefused("two exchanged", stream(second, first, third, last), 0)
        assertRefused("two exchanged", stream(first, third, second, last), 1)
        assertRefused("the last moved forward", stream(first, second, last, third), 2)
        assertRefused("the first added at the end", stream(first, second, third, last, first), 3)
        assertRefused("the last added again", stream(first, second, third, last, last), 3)
        assertRefused("one byte added", stream(first, second, third, last, byteArrayOf(0)), 3)
        assertRefused("only the last", stream(last), 0)

        // Where the last chunk is full, what is added stands where a chunk could stand.
        val full = bytes(48, from = 60)
        file.writeAll(full)
        val even = file.file.readBytes()
        val (one, two, three) = sealedChunks(even)
        fun evenStream(vararg chunks: ByteArray) = chunks.fold(even.copyOf(HEADER)) { all, chunk -> all + chunk }
        assertRefused("the last added again", evenStream(one, two, three, three), 2)
        assertRefused("the first added at the end", evenStream(one, two, three, one), 2)
        assertRefused("a tag's worth of bytes added", evenStream(one, two, three, ByteArray(28)), 2)
        assertRefused("one byte added", evenStream(one, two, three, byteArrayOf(0)), 2)
    }

    @Test
    fun aChunkOfAnotherStreamIsDetected() {
        // Two writings of the same content for the same purpose with the same date: they differ in their stream ids and nonces only.
        val file = streamFile()
        file.writeAll(plaintext)
        val a = file.file.readBytes()
        file.writeAll(plaintext)
        val b = file.file.readBytes()
        assertFalse(a.copyOfRange(4, 20).contentEquals(b.copyOfRange(4, 20)))
        assertArrayEquals(a.copyOfRange(20, 28), b.copyOfRange(20, 28))
        val chunksOfA = sealedChunks(a)
        val chunksOfB = sealedChunks(b)

        for (index in chunksOfA.indices) {
            val mixed = chunksOfA.toMutableList().also { it[index] = chunksOfB[index] }
            assertRefused("chunk $index of the other writing", mixed.fold(a.copyOf(HEADER)) { all, chunk -> all + chunk }, index)
        }
        assertRefused("the other writing's header", b.copyOf(HEADER) + a.copyOfRange(HEADER, a.size), 0)
        // Whole, each of them is as good as the other: which writing a file is, is not authenticated.
        file.file.writeBytes(a)
        assertArrayEquals(plaintext, file.readAll())
    }

    @Test
    fun aStreamUnderAnotherPurposeIsDetected() {
        val file = streamFile()
        file.writeAll(plaintext)
        val stored = file.file.readBytes()

        // Renamed to another chat, and handed to another store.
        assertRefused("another chat", stored, 0, streamFile(name = "another.enc", purpose = Purposes.of("model-memory", "another")))
        assertRefused("another store", stored, 0, streamFile(name = "$id.enc", purpose = Purposes.of("session", id)))
        file.file.writeBytes(stored)
        assertArrayEquals(plaintext, file.readAll())
    }

    @Test
    fun aChangedDateIsDetectedThoughItReadsAsChangedWithoutTheKey() {
        val file = streamFile()
        file.writeAll(plaintext)
        val stored = file.file.readBytes()
        assertEquals(time, SealedStreamFile.readHeader(file.file)!!.oldestMessageMillis)

        // A day later, so that the stream would be kept a day longer.
        val later = time + 86_400_000L
        val changed = stored.copyOf()
        for (byte in 0 until 8) changed[20 + byte] = (later ushr (8 * (7 - byte))).toByte()
        file.file.writeBytes(changed)

        assertEquals("without the key the header is only what the file says", later, SealedStreamFile.readHeader(file.file)!!.oldestMessageMillis)
        val reader = file.read()!!
        reader.use {
            assertEquals(later, it.header.oldestMessageMillis)
            assertThrows("and with it, nothing opens under that date", UndecryptableException::class.java) { it.next() }
        }
        assertRefused("the date changed", changed, 0)
    }

    @Test
    fun anEnvelopeIsNotReadAsAStreamAndAStreamIsNotOpenedAsAnEnvelope() {
        val kept = File(files, "kept")
        // A blob under the very name and purpose of the stream.
        val blob = SealedFile(File(directory, "$id.enc"), purpose, cipher, kept, "model-memory-$id")
        blob.write(plaintext)
        assertNull("an envelope has no stream header", SealedStreamFile.readHeader(blob.file))
        assertThrows(UndecryptableException::class.java) { streamFile().read() }
        assertThrows(UndecryptableException::class.java) { streamFile().readAll() }

        // And a stream where a blob is expected.
        val file = streamFile()
        file.writeAll(plaintext)
        assertSame(SealedRead.Undecryptable, SealedFile(file.file, purpose, cipher, kept, "model-memory-$id").read())
        assertArrayEquals(plaintext, file.readAll())
    }

    @Test
    fun aStreamOfAnotherVersionOrNoStreamAtAllIsRefusedBeforeAnyKeyIsUsed() {
        val file = streamFile()
        file.writeAll(plaintext)
        val stored = file.file.readBytes()
        val locked = streamFile(cipher = DataCrypto(DataKey(keyBytes)).also { it.destroy() })

        for ((what, content) in listOf(
            "a later version" to stored.copyOf().also { it[3] = 2 },
            "another magic" to stored.copyOf().also { it[2] = 'E'.code.toByte() },
            "less than a header" to stored.copyOf(HEADER - 1),
            "nothing" to ByteArray(0),
            "text" to "{\"messages\":[]}".toByteArray(),
        )) {
            file.file.writeBytes(content)
            assertNull(what, SealedStreamFile.readHeader(file.file))
            assertThrows(what, UndecryptableException::class.java) { file.read() }
            assertThrows("$what: not a matter of the key", UndecryptableException::class.java) { locked.read() }
        }
    }

    @Test
    fun aWrongKeyOpensNothing() {
        val file = streamFile()
        file.writeAll(plaintext)
        assertRefused("another key", file.file.readBytes(), 0, streamFile(cipher = TestKeys.cipher()))
        assertArrayEquals(plaintext, file.readAll())
    }

    // --- The lock ---

    @Test
    fun onceTheKeyIsDestroyedNoFurtherChunkIsReadAndTheBufferIsBlank() {
        val file = streamFile()
        file.writeAll(plaintext)
        val reader = file.read()!!
        val first = reader.next()!!
        assertArrayEquals(plaintext.copyOfRange(0, SMALL), first.contents())

        cipher.destroy()

        assertThrows(KeyDestroyedException::class.java) { reader.next() }
        assertArrayEquals("what was handed back is gone with the next call", ByteArray(first.capacity()), first.everything())
        assertThrows("and it stays that way", KeyDestroyedException::class.java) { reader.next() }
        reader.close()
        assertTrue("a lock says nothing against the file", file.file.exists())
        assertArrayEquals(plaintext, streamFile(cipher = DataCrypto(DataKey(keyBytes))).readAll())
    }

    @Test
    fun onceTheKeyIsDestroyedNoFurtherChunkIsWrittenAndNoWriteBegun() {
        val file = streamFile()
        file.writeAll(plaintext)
        val before = file.file.readBytes()

        val writer = file.write(time)
        val temporary = writer.temp
        writer.write(directBuffer(bytes(SMALL)), false)
        cipher.destroy()

        assertThrows(KeyDestroyedException::class.java) { writer.write(directBuffer(bytes(SMALL)), false) }
        assertThrows(KeyDestroyedException::class.java) { writer.write(directBuffer(bytes(1)), true) }
        assertEquals("only the header and the one chunk reached the disk", (HEADER + SEALED).toLong(), temporary.length())
        assertThrows("a stream without its last chunk is not given the name", IllegalStateException::class.java) { writer.commit() }
        writer.close()
        assertFalse(temporary.exists())
        assertArrayEquals(before, file.file.readBytes())

        assertThrows(KeyDestroyedException::class.java) { file.write(time) }
        assertEquals("nothing is created for a write that cannot happen", emptyList<String>(), temporaries())
    }

    // --- The plaintext buffer ---

    @Test
    fun theReaderHandsBackOneDirectBufferOfItsOwn() {
        val file = streamFile()
        file.writeAll(plaintext)
        file.read()!!.use { reader ->
            val first = reader.next()!!
            assertTrue(first.isDirect)
            assertFalse("native code is handed it as it is", first.isReadOnly)
            assertEquals("a chunk, and the room for a tag that an older cipher asks for", SMALL + SealedStream.TAG_SIZE, first.capacity())
            assertEquals(0, first.position())
            assertEquals(SMALL, first.limit())
            // Whatever the caller does with its position and limit, the next chunk starts afresh.
            first.position(7)
            first.limit(9)
            val second = reader.next()!!
            assertSame(first, second)
            assertEquals(0, second.position())
            assertEquals(SMALL, second.limit())
            assertArrayEquals(plaintext.copyOfRange(SMALL, 2 * SMALL), second.contents())
        }
    }

    @Test
    fun thePlaintextIsOverwrittenBeforeEveryChunkAtTheEndAndWhenTheReaderIsClosed() {
        val file = streamFile()
        file.writeAll(ByteArray(SMALL) { 0x41 } + ByteArray(3) { 0x42 })
        val reader = file.read()!!
        val buffer = reader.next()!!
        assertArrayEquals(ByteArray(SMALL) { 0x41 }, buffer.contents())

        assertSame(buffer, reader.next())
        assertArrayEquals(ByteArray(3) { 0x42 }, buffer.contents())
        assertArrayEquals("nothing of the chunk before is left behind the shorter one", ByteArray(3) { 0x42 } + ByteArray(buffer.capacity() - 3), buffer.everything())

        assertNull(reader.next())
        assertArrayEquals("at the end", ByteArray(buffer.capacity()), buffer.everything())
        reader.close()

        val closing = file.read()!!
        val held = closing.next()!!
        assertArrayEquals(ByteArray(SMALL) { 0x41 }, held.contents())
        closing.close()
        assertArrayEquals("when closed half-way", ByteArray(held.capacity()), held.everything())
        closing.close()
        assertThrows(IllegalStateException::class.java) { closing.next() }
    }

    @Test
    fun whenAChunkFailsTheBufferIsBlankAndEveryLaterCallFailsToo() {
        val file = streamFile()
        file.writeAll(plaintext)
        val stored = file.file.readBytes()
        // The last byte of the second chunk's tag: its ciphertext is as it was, and decrypts.
        val position = HEADER + 2 * SEALED - 1
        file.file.writeBytes(stored.copyOf().also { it[position] = (it[position].toInt() xor 0x01).toByte() })

        file.read()!!.use { reader ->
            val buffer = reader.next()!!
            assertArrayEquals(plaintext.copyOfRange(0, SMALL), buffer.contents())
            assertThrows(UndecryptableException::class.java) { reader.next() }
            assertArrayEquals(ByteArray(buffer.capacity()), buffer.everything())
            assertThrows(UndecryptableException::class.java) { reader.next() }
            assertArrayEquals(ByteArray(buffer.capacity()), buffer.everything())
        }
    }

    @Test
    fun aReaderThatFailedForAnyReasonReadsNoFurther() {
        streamFile().writeAll(plaintext)
        // A cipher that opens one chunk and then breaks in a way no cipher should.
        val breaking = object : ChunkCipher by cipher {
            var opened = 0

            override fun openChunk(header: StreamHeader, purpose: String, index: Long, last: Boolean, sealed: ByteBuffer, plaintext: ByteBuffer): Int {
                if (opened++ > 0) throw IllegalStateException("broken")
                return cipher.openChunk(header, purpose, index, last, sealed, plaintext)
            }
        }
        streamFile(cipher = breaking).read()!!.use { reader ->
            val buffer = reader.next()!!
            assertThrows(IllegalStateException::class.java) { reader.next() }
            assertArrayEquals(ByteArray(buffer.capacity()), buffer.everything())
            assertThrows(IOException::class.java) { reader.next() }
            assertEquals("the cipher is not asked again", 2, breaking.opened)
        }
    }

    @Test
    fun theWriterReadsThePlaintextWhereItLiesAndLeavesItAsItIs() {
        val file = streamFile()
        val piece = directBuffer(bytes(SMALL, from = 30))
        file.write(time).use { writer ->
            writer.write(piece, false)
            assertEquals("read to its limit", SMALL, piece.position())
            assertArrayEquals("the buffer is the caller's, to fill again or to overwrite", bytes(SMALL, from = 30), piece.everything())
            piece.clear()
            piece.limit(4)
            writer.write(piece, true)
            writer.commit()
        }
        assertArrayEquals(bytes(SMALL, from = 30) + bytes(4, from = 30), file.readAll())
        assertThrows("a chunk in an array of the heap is not taken", IllegalArgumentException::class.java) {
            file.write(time).use { writer -> writer.write(ByteBuffer.wrap(bytes(SMALL)), false) }
        }
    }

    // --- Taking the place of the file before ---

    @Test
    fun untilItIsCommittedAStreamIsATemporaryFileAndTheOneBeforeItStays() {
        val lock = AppFiles(files, temp.newFolder("cache"))
        val file = streamFile()

        val writer = file.write(time)
        val temporary = writer.temp
        assertTrue(temporary.exists())
        assertEquals("the write's temporary file is the only one", listOf(temporary.name), temporaries())
        writer.write(directBuffer(bytes(SMALL)), false)
        assertFalse("an unfinished write is not data to the lock", lock.encryptedDataExists())
        assertNull(file.read())
        writer.write(directBuffer(bytes(4)), true)
        assertFalse(file.file.exists())

        var seen: ByteArray? = null
        writer.commit { complete ->
            assertEquals(temporary, complete)
            assertFalse("the name is still free while the store has its say", file.file.exists())
            seen = complete.readBytes()
            assertTrue(complete.setLastModified(time))
        }

        assertFalse(temporary.exists())
        assertArrayEquals("what was renamed is the complete file", seen, file.file.readBytes())
        assertEquals(SealedStream.sealedSize(SMALL + 4L, SMALL), file.file.length())
        assertEquals("the date the store gave it stays with it", time, file.file.lastModified())
        assertTrue("a stream is encrypted data to the lock", lock.encryptedDataExists())
        writer.close()
        assertTrue("closing after the commit takes nothing away", file.file.exists())
        assertThrows(IOException::class.java) { writer.commit() }

        // The next write replaces it only when it commits.
        val first = file.file.readBytes()
        val second = file.write(time + 1_000)
        second.write(directBuffer(bytes(9, from = 90)), true)
        assertArrayEquals(first, file.file.readBytes())
        assertArrayEquals(bytes(SMALL) + bytes(4), file.readAll())
        second.commit()
        assertArrayEquals(bytes(9, from = 90), file.readAll())
        assertEquals(time + 1_000, SealedStreamFile.readHeader(file.file)!!.oldestMessageMillis)
    }

    @Test
    fun aWriteThatIsGivenUpOrFailsLeavesTheFileBeforeIt() {
        val file = streamFile()
        file.writeAll(plaintext)
        val before = file.file.readBytes()

        // Closed without a commit, as `use` does when anything is thrown.
        file.write(time).use { writer -> writer.write(directBuffer(bytes(SMALL)), false) }
        assertEquals(emptyList<String>(), temporaries())
        assertArrayEquals(before, file.file.readBytes())

        // The store says no at the last moment.
        val refused = file.write(time)
        refused.write(directBuffer(bytes(3)), true)
        assertThrows(IOException::class.java) { refused.commit { throw IOException("the date could not be set") } }
        assertEquals(emptyList<String>(), temporaries())
        assertArrayEquals(before, file.file.readBytes())
        assertThrows("the write is over", IOException::class.java) { refused.write(directBuffer(bytes(3)), true) }

        // Left behind by a process that ended: not read, and not touched by the next write, which
        // has a temporary name of its own; a deletion (or the clean-up of every `*.tmp`) removes it.
        val leftBehind = File(directory, "$id.enc.0123456789abcdef.tmp").apply { writeBytes(bytes(500)) }
        assertArrayEquals(plaintext, file.readAll())
        file.writeAll(bytes(5))
        assertArrayEquals(bytes(5), file.readAll())
        assertEquals(listOf(leftBehind.name), temporaries())
        assertArrayEquals(bytes(500), leftBehind.readBytes())
    }

    @Test
    fun aStreamThatIsNotCompleteIsNeverGivenTheName() {
        val file = streamFile()
        file.writeAll(plaintext)
        val before = file.file.readBytes()

        val withoutItsLast = file.write(time)
        withoutItsLast.write(directBuffer(bytes(SMALL)), false)
        assertThrows(IllegalStateException::class.java) { withoutItsLast.commit() }
        assertFalse(withoutItsLast.temp.exists())

        val empty = file.write(time)
        assertThrows("not even an empty stream is no chunk", IllegalStateException::class.java) { empty.commit() }

        // Sealed but not appended: a chunk was lost between the two halves of a write.
        val lost = file.write(time)
        val sealed = ByteBuffer.allocateDirect(SEALED)
        lost.seal(directBuffer(bytes(SMALL)), false, sealed)
        sealed.flip()
        lost.append(sealed)
        sealed.clear()
        lost.seal(directBuffer(bytes(2)), true, sealed)
        assertThrows(IllegalStateException::class.java) { lost.commit() }
        assertEquals(emptyList<String>(), temporaries())
        assertArrayEquals(before, file.file.readBytes())

        // And nothing is appended that was not sealed, or that is no chunk.
        val counted = file.write(time)
        assertThrows(IllegalStateException::class.java) { counted.append(directBuffer(ByteArray(SEALED))) }
        counted.seal(directBuffer(bytes(2)), true, sealed.also { it.clear() })
        assertThrows(IllegalStateException::class.java) { counted.append(directBuffer(ByteArray(27))) }
        assertThrows(IllegalStateException::class.java) { counted.append(directBuffer(ByteArray(SEALED + 1))) }
        sealed.flip()
        counted.append(sealed)
        assertThrows(IllegalStateException::class.java) { counted.append(directBuffer(ByteArray(SEALED))) }
        counted.commit()
        assertArrayEquals(bytes(2), file.readAll())
    }

    /**
     * The two halves of a write on two threads, as the thread that holds the plaintext must
     * never wait for the disk: it seals into buffers from a small pool, and another thread
     * appends them and gives them back.
     */
    @Test
    fun aChunkCanBeSealedOnOneThreadAndWrittenToTheDiskOnAnother() {
        val content = ByteArray(7 * SMALL + 9).also(random::nextBytes)
        val file = streamFile()
        val writer = file.write(time)
        val free = ArrayBlockingQueue<ByteBuffer>(2)
        repeat(2) { free.put(ByteBuffer.allocateDirect(SEALED)) }
        val toDisk = ArrayBlockingQueue<ByteBuffer>(2)
        val noMore = ByteBuffer.allocateDirect(1)
        val failure = AtomicReference<Throwable>()
        val disk = thread {
            try {
                while (true) {
                    val sealed = toDisk.take()
                    if (sealed === noMore) break
                    writer.append(sealed)
                    free.put(sealed)
                }
            } catch (e: Throwable) {
                failure.set(e)
            }
        }

        val piece = ByteBuffer.allocateDirect(SMALL)
        var offset = 0
        while (offset < content.size) {
            val length = minOf(SMALL, content.size - offset)
            piece.clear()
            piece.put(content, offset, length)
            piece.flip()
            val sealed = free.take()
            sealed.clear()
            writer.seal(piece, offset + length == content.size, sealed)
            sealed.flip()
            toDisk.put(sealed)
            offset += length
        }
        toDisk.put(noMore)
        disk.join()

        assertNull(failure.get())
        writer.commit()
        assertArrayEquals(content, file.readAll())
    }

    @Test
    fun oneWriteAtATime() {
        val file = streamFile()
        val first = file.write(time)
        first.write(directBuffer(bytes(SMALL)), false)
        assertThrows("a write of this object is open", IOException::class.java) { file.write(time) }
        assertEquals("and what the first has written is untouched", (HEADER + SEALED).toLong(), first.temp.length())
        assertEquals("the refused write created nothing", listOf(first.temp.name), temporaries())
        first.write(directBuffer(bytes(1)), true)
        first.commit()

        val second = file.write(time)
        second.close()
        file.writeAll(bytes(3))
        assertArrayEquals(bytes(3), file.readAll())
    }

    @Test
    fun aCancelledWriteSealsAppendsAndCommitsNothing() {
        val file = streamFile()
        file.writeAll(plaintext)
        val before = file.file.readBytes()
        val writer = file.write(time)
        val sealed = ByteBuffer.allocateDirect(SEALED)
        writer.seal(directBuffer(bytes(SMALL)), false, sealed)
        sealed.flip()

        writer.cancel()

        assertThrows(IOException::class.java) { writer.seal(directBuffer(bytes(1)), true, ByteBuffer.allocateDirect(SEALED)) }
        assertThrows(IOException::class.java) { writer.write(directBuffer(bytes(1)), true) }
        assertThrows(IOException::class.java) { writer.append(sealed) }
        assertThrows(IOException::class.java) { writer.commit() }
        assertThrows("until it is closed no other write of this object begins", IOException::class.java) { file.write(time) }
        writer.close()
        assertEquals(emptyList<String>(), temporaries())
        assertArrayEquals(before, file.file.readBytes())
        file.writeAll(bytes(3))
        assertArrayEquals(bytes(3), file.readAll())
    }

    // --- Deleting, and reading without the key ---

    @Test
    fun deletingRemovesTheFileAndWhatAWriteLeftBesideIt() {
        val file = streamFile()
        file.writeAll(plaintext)
        val other = streamFile(name = "another.enc", purpose = Purposes.of("model-memory", "another"))
        other.writeAll(plaintext)
        File(directory, "$id.enc.tmp").writeBytes(bytes(40))
        File(directory, "$id.enc.0123456789abcdef.tmp").writeBytes(bytes(40))
        File(directory, "another.enc.fedcba9876543210.tmp").writeBytes(bytes(40))

        file.delete()

        assertEquals("every temporary file of this name goes, and nothing of another", listOf("another.enc", "another.enc.fedcba9876543210.tmp"), directory.list()!!.sorted())
        assertNull(file.read())
        file.delete() // Deleting what is not there is not an error.
        file.writeAll(bytes(3))
        assertArrayEquals(bytes(3), file.readAll())
    }

    @Test
    fun deletingNeedsNoKey() {
        val file = streamFile()
        file.writeAll(plaintext)
        cipher.destroy()
        file.delete()
        assertFalse(file.file.exists())
    }

    @Test
    fun aFileDeletedWhileItIsBeingWrittenStaysDeleted() {
        val file = streamFile()
        file.writeAll(plaintext)
        val writer = file.write(time)
        writer.write(directBuffer(bytes(SMALL)), false)

        file.delete()

        assertFalse(file.file.exists())
        assertThrows("the write that was under way ends there", IOException::class.java) { writer.write(directBuffer(bytes(1)), true) }
        assertThrows(IOException::class.java) { writer.commit() }
        writer.close()
        assertEquals(emptyList<String>(), directory.list()!!.toList())

        // Also when the write was complete and only the commit was still to come.
        val complete = file.write(time)
        complete.write(directBuffer(bytes(1)), true)
        file.delete()
        assertThrows(IOException::class.java) { complete.commit() }
        complete.close()
        assertEquals(emptyList<String>(), directory.list()!!.toList())
    }

    /**
     * The last moment at which a delete can come: the write has passed every check of its own
     * and is about to rename. The rename and the delete exclude each other, so the test holds
     * that exclusion, lets the commit run up to it, and deletes.
     */
    @Test
    fun aDeleteThatComesJustBeforeTheRenameStillWins() {
        val file = streamFile()
        val writer = file.write(time)
        writer.write(directBuffer(bytes(1)), true)
        var asked = false
        val failure = AtomicReference<Throwable>()
        val committing: Thread
        synchronized(file) {
            committing = thread {
                try {
                    writer.commit { asked = true }
                } catch (e: Throwable) {
                    failure.set(e)
                }
            }
            val deadline = System.nanoTime() + 10_000_000_000L
            while (committing.state != Thread.State.BLOCKED) {
                if (System.nanoTime() > deadline) fail("the commit never reached the rename")
                Thread.sleep(1)
            }
            file.delete()
        }
        committing.join()

        assertTrue("the commit fails: ${failure.get()}", failure.get() is IOException)
        assertFalse("the store is not asked about a file that is to stay deleted", asked)
        assertEquals(emptyList<String>(), directory.list()!!.toList())
    }

    @Test
    fun theHeaderIsReadWithoutTheKey() {
        val file = streamFile()
        file.writeAll(plaintext, millis = 1_699_999_999_123L)
        val stored = file.file.readBytes()
        cipher.destroy()

        val header = SealedStreamFile.readHeader(file.file)!!
        assertEquals(1_699_999_999_123L, header.oldestMessageMillis)
        assertArrayEquals(stored.copyOfRange(4, 20), header.streamId)
        assertArrayEquals(stored.copyOf(HEADER), header.bytes())
        assertThrows("a directory is not a file without a header: it cannot be read", IOException::class.java) { SealedStreamFile.readHeader(directory) }
    }

    private companion object {
        /** The chunk size of the streams here, in place of the 4 MiB of version 1. */
        const val SMALL = 16
        const val HEADER = SealedStream.HEADER_SIZE

        /** A full chunk as it is stored: its nonce, its ciphertext and its tag. */
        const val SEALED = SMALL + SealedStream.CHUNK_OVERHEAD
    }
}
