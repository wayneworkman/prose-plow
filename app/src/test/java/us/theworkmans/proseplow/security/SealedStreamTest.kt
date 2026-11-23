package us.theworkmans.proseplow.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * The sealed stream as a format: its bytes, what every chunk authenticates, the rules a stream
 * is held to when it is sealed and when it is opened, and how it stands beside the envelope and
 * under the data key. What a stream is on the disk is `SealedStreamFileTest`.
 *
 * The chunk size of version 1 is 4 MiB. Most tests here seal chunks of [SMALL] bytes instead,
 * which the classes take from inside the module only, so that every case of the chunking is
 * walked through without megabytes; the vectors of version 1 itself are at the real size.
 */
class SealedStreamTest {
    private val random = SecureRandom()
    private val keyBytes = ByteArray(DataKey.SIZE).also(random::nextBytes)
    private val cipher = DataCrypto(DataKey(keyBytes))
    private val purpose = Purposes.of("model-memory", "3f2a7c1e-5b0d-4e8a-9c1f-0a1b2c3d4e5f")
    private val time = 1_700_000_000_000L

    private fun hex(text: String) = text.replace(Regex("\\s"), "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private fun bytes(size: Int, from: Int = 0) = ByteArray(size) { (from + it).toByte() }

    /** [plaintext] as a stream of chunks of [chunkSize] bytes: the header and every sealed chunk, as a file would hold them. */
    private fun seal(plaintext: ByteArray, cipher: ChunkCipher = this.cipher, purpose: String = this.purpose, chunkSize: Int = SMALL): ByteArray {
        val sealer = StreamSealer(cipher, purpose, time, chunkSize)
        val stream = ByteArrayOutputStream()
        stream.write(sealer.header.bytes())
        val sealed = ByteBuffer.allocateDirect(chunkSize + SealedStream.CHUNK_OVERHEAD)
        var offset = 0
        do {
            val length = minOf(chunkSize, plaintext.size - offset)
            val last = offset + length == plaintext.size
            sealed.clear()
            sealer.seal(directBuffer(plaintext.copyOfRange(offset, offset + length)), last, sealed)
            sealed.flip()
            stream.write(sealed.contents())
            offset += length
        } while (!last)
        assertTrue(sealer.isFinished)
        return stream.toByteArray()
    }

    /** The sealed chunks of [stream], which was made by [seal] with the same [chunkSize]. */
    private fun chunks(stream: ByteArray, chunkSize: Int = SMALL): List<ByteArray> {
        val sealedSize = chunkSize + SealedStream.CHUNK_OVERHEAD
        return (SealedStream.HEADER_SIZE until stream.size step sealedSize).map { stream.copyOfRange(it, minOf(it + sealedSize, stream.size)) }
    }

    /** Opens [chunks] in order under [header], the last of them as the last, and returns the plaintext. */
    private fun open(header: StreamHeader, chunks: List<ByteArray>, cipher: ChunkCipher = this.cipher, purpose: String = this.purpose, chunkSize: Int = SMALL): ByteArray {
        val opener = StreamOpener(cipher, purpose, header, chunkSize)
        val plaintext = ByteArrayOutputStream()
        val plain = ByteBuffer.allocateDirect(chunkSize + SealedStream.TAG_SIZE)
        for ((index, chunk) in chunks.withIndex()) {
            plain.clear()
            plain.limit(maxOf(chunk.size - SealedStream.NONCE_SIZE, 0))
            val written = opener.open(directBuffer(chunk), index == chunks.lastIndex, plain)
            plain.flip()
            assertEquals(written, plain.remaining())
            plaintext.write(plain.contents())
        }
        assertTrue(opener.isFinished)
        return plaintext.toByteArray()
    }

    private fun open(stream: ByteArray, cipher: ChunkCipher = this.cipher, purpose: String = this.purpose): ByteArray =
        open(SealedStream.parseHeader(stream)!!, chunks(stream), cipher, purpose)

    // --- The bytes ---

    @Test
    fun aStreamHasTheDocumentedLayout() {
        val plaintext = bytes(37)
        val stream = seal(plaintext)

        assertEquals('P'.code.toByte(), stream[0])
        assertEquals('P'.code.toByte(), stream[1])
        assertEquals('S'.code.toByte(), stream[2])
        assertEquals(0x01.toByte(), stream[3])
        assertEquals(SealedStream.VERSION, stream[3])
        assertEquals(28, SealedStream.HEADER_SIZE)
        assertArrayEquals("the time, most significant byte first", hex("0000018bcfe56800"), stream.copyOfRange(20, 28))
        // Two full chunks and a last one of five bytes, each with its nonce and its tag.
        assertEquals(listOf(16 + 28, 16 + 28, 5 + 28), chunks(stream).map { it.size })
        assertEquals(28 + 37 + 3 * 28, stream.size)
        assertEquals(stream.size.toLong(), SealedStream.sealedSize(37, SMALL))
        assertTrue(SealedStream.looksSealed(stream))
        assertFalse("an envelope begins otherwise", Envelope.looksEncrypted(stream))

        val header = SealedStream.parseHeader(stream)!!
        assertArrayEquals(stream.copyOfRange(4, 20), header.streamId)
        assertEquals(time, header.oldestMessageMillis)
        assertArrayEquals(plaintext, open(stream))
    }

    @Test
    fun theChunkSizeOfVersion1IsFourMebibytes() {
        assertEquals(4_194_304, SealedStream.CHUNK_SIZE)
        assertEquals(28, SealedStream.CHUNK_OVERHEAD)
        assertEquals("an empty stream is a header and one empty chunk", 28L + 28, SealedStream.sealedSize(0))
        assertEquals(28L + 1 + 28, SealedStream.sealedSize(1))
        assertEquals(28L + 4_194_304 + 28, SealedStream.sealedSize(4_194_304))
        assertEquals(28L + 4_194_305 + 2 * 28, SealedStream.sealedSize(4_194_305))
        // The largest memory the form is for: 1.5 GB is 358 chunks.
        assertEquals(1_500_000_000L + 28 + 358 * 28, SealedStream.sealedSize(1_500_000_000L))
        assertThrows(IllegalArgumentException::class.java) { SealedStream.sealedSize(-1) }
    }

    /**
     * What every chunk authenticates, pinned from outside: a stream sealed here opens with plain
     * JCA calls that spell out the magic, the version, the stream id, the time, the `proseplow:`
     * prefix, the purpose, the index and the last-chunk byte. If any of them changes, or moves,
     * everything stored before becomes unreadable.
     */
    @Test
    fun theAdditionalAuthenticatedDataIsTheHeaderThePrefixedPurposeTheIndexAndTheLastChunkByte() {
        val plaintext = bytes(37)
        val stream = seal(plaintext, purpose = "model-memory:abc")
        val id = stream.copyOfRange(4, 20)
        val header = byteArrayOf(0x50, 0x50, 0x53, 0x01) + id + byteArrayOf(0x00, 0x00, 0x01, 0x8b.toByte(), 0xcf.toByte(), 0xe5.toByte(), 0x68, 0x00)

        fun openWithJca(offset: Int, length: Int, index: Byte, last: Byte): ByteArray {
            val jca = Cipher.getInstance("AES/GCM/NoPadding")
            jca.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, stream, offset, 12))
            jca.updateAAD(header + "proseplow:model-memory:abc".toByteArray(Charsets.UTF_8) + byteArrayOf(0, 0, 0, 0, 0, 0, 0, index) + byteArrayOf(last))
            return jca.doFinal(stream, offset + 12, length - 12)
        }
        assertArrayEquals(plaintext.copyOfRange(0, 16), openWithJca(28, 44, index = 0, last = 0))
        assertArrayEquals(plaintext.copyOfRange(16, 32), openWithJca(72, 44, index = 1, last = 0))
        assertArrayEquals(plaintext.copyOfRange(32, 37), openWithJca(116, 33, index = 2, last = 1))
    }

    @Test
    fun everyStreamHasAnIdOfItsOwnAndEveryChunkAFreshNonce() {
        val plaintext = bytes(48)
        val a = seal(plaintext)
        val b = seal(plaintext)
        assertFalse("two streams of the same content", a.copyOfRange(4, 20).contentEquals(b.copyOfRange(4, 20)))
        val nonces = (chunks(a) + chunks(b)).map { it.copyOfRange(0, 12).hex() }
        assertEquals(6, nonces.toSet().size)
        assertFalse(chunks(a)[0].contentEquals(chunks(b)[0]))
    }

    // --- Vectors ---

    /**
     * Version 1, frozen: with this key, stream id, time, purpose and nonce, these are the bytes
     * of a stream, and no later change of the code may produce others. The expected bytes were
     * computed from the written format alone with another implementation of AES-GCM than the
     * one the tests run on. A stream of several chunks at the real chunk size is frozen by its
     * digest in `SealedStreamFileTest`.
     */
    @Test
    fun aStreamOfVersion1WithOneChunkIsTheseBytes() {
        val scripted = DataCrypto(DataKey(bytes(32)), ScriptedRandom(bytes(16, from = 0xa0), bytes(12, from = 0xc0)))
        val sealer = StreamSealer(scripted, purpose, time)
        val sealed = ByteBuffer.allocateDirect(21 + SealedStream.CHUNK_OVERHEAD)
        sealer.seal(directBuffer("the memory of a model".toByteArray(Charsets.US_ASCII)), last = true, sealed)
        sealed.flip()
        val stream = sealer.header.bytes() + sealed.contents()

        assertEquals(
            "50505301a0a1a2a3a4a5a6a7a8a9aaabacadaeaf0000018bcfe56800" +
                "c0c1c2c3c4c5c6c7c8c9cacb" + "742042471a1dfb31eaa0ddcd548c8976211c875a50" + "5b1f9129147be31e7473448491bec6d6",
            stream.hex(),
        )
        assertEquals(
            "what the one chunk authenticates",
            "50505301a0a1a2a3a4a5a6a7a8a9aaabacadaeaf0000018bcfe56800" +
                "70726f7365706c6f773a" + "6d6f64656c2d6d656d6f72793a33663261376331652d356230642d346538612d396331662d306131623263336434653566" +
                "0000000000000000" + "01",
            SealedStream.aad(sealer.header, purpose, 0, last = true).hex(),
        )
        // And the frozen bytes open, whoever made them.
        val opener = StreamOpener(DataCrypto(DataKey(bytes(32))), purpose, SealedStream.parseHeader(stream)!!)
        val plain = ByteBuffer.allocateDirect(21 + SealedStream.TAG_SIZE)
        assertEquals(21, opener.open(directBuffer(stream.copyOfRange(28, stream.size)), last = true, plain))
        plain.flip()
        assertEquals("the memory of a model", String(plain.contents(), Charsets.US_ASCII))
    }

    /**
     * The same construction with chunks of 16 bytes, which no version writes, so that every byte
     * of a stream of several chunks can be written out: the index counts up, and only the last
     * chunk is sealed as the last. Computed like the vector above.
     */
    @Test
    fun aStreamOfSeveralSmallChunksIsTheseBytes() {
        val nonces = (1..3).map { chunk -> bytes(12, from = 0x10 * chunk) }
        val scripted = DataCrypto(DataKey(bytes(32)), ScriptedRandom(bytes(16, from = 0xa0), *nonces.toTypedArray()))

        val stream = seal(bytes(37), cipher = scripted)

        assertEquals(
            "50505301a0a1a2a3a4a5a6a7a8a9aaabacadaeaf0000018bcfe56800" +
                "101112131415161718191a1b" + "7dff9a154dcc3cb4c27c02160374675c" + "52d635db5a17836563f9bf27f032762f" +
                "202122232425262728292a2b" + "c22bb463788d0c19026558d5dd05eae6" + "4334fd72017ffd5709906609ba90c41c" +
                "303132333435363738393a3b" + "931413e3cd" + "914bbe59650f595f91f6b552496d0cd7",
            stream.hex(),
        )
        assertArrayEquals(bytes(37), open(stream, cipher = DataCrypto(DataKey(bytes(32)))))
    }

    // --- The two forms ---

    @Test
    fun anEnvelopeIsNotAStreamAndAStreamIsNotAnEnvelope() {
        val plaintext = bytes(40)
        val stream = seal(plaintext)
        val envelope = Envelope.seal(keyBytes, purpose, plaintext, random)

        assertNull("an envelope has no stream header", SealedStream.parseHeader(envelope))
        assertFalse(SealedStream.looksSealed(envelope))
        assertThrows(UndecryptableException::class.java) { Envelope.open(keyBytes, purpose, stream) }
        assertThrows(UndecryptableException::class.java) { cipher.open(purpose, stream) }
        // The first form is where it was: its magic, its version, and what it opens.
        assertArrayEquals(byteArrayOf(0x50, 0x50, 0x45, 0x01), envelope.copyOfRange(0, 4))
        assertArrayEquals(plaintext, Envelope.open(keyBytes, purpose, envelope))
    }

    /**
     * Why no chunk can ever open as an envelope, or an envelope as a chunk, although both are
     * AES-GCM messages under the same key: the additional authenticated data of each form begins
     * with its own magic. This test builds the closest the two can come. A stream id is 16 bytes
     * of anyone's choosing once a file can be written, so it is made to begin with the envelope's
     * prefix, and the envelope's purpose is made to be everything that then follows in the
     * chunk's data. The two texts are then equal in every byte but one, the third of the magic,
     * and that one is enough.
     */
    @Test
    fun theAdditionalAuthenticatedDataOfAChunkNeverEqualsThatOfAnEnvelope() {
        val plaintext = "the same key seals both".toByteArray()
        val spaces = 0x2020202020202020L
        val header = SealedStream.newHeader(spaces, ScriptedRandom("proseplow:stream".toByteArray(Charsets.US_ASCII)))
        val chunkAad = SealedStream.aad(header, purpose, index = 0, last = true)
        val envelopePurpose = String(chunkAad, 14, chunkAad.size - 14, Charsets.UTF_8)
        // The envelope's data as its own test pins it: its four-byte header, the prefix, the purpose.
        val envelopeAad = byteArrayOf(0x50, 0x50, 0x45, 0x01) + "proseplow:$envelopePurpose".toByteArray(Charsets.UTF_8)

        assertEquals(chunkAad.size, envelopeAad.size)
        assertEquals("only the magic tells them apart", listOf(2), chunkAad.indices.filter { chunkAad[it] != envelopeAad[it] })
        assertEquals('S'.code.toByte(), chunkAad[2])
        assertEquals('E'.code.toByte(), envelopeAad[2])

        // A chunk dressed up as an envelope: the magic rewritten, then its own nonce, ciphertext and tag.
        val sealed = ByteBuffer.allocateDirect(plaintext.size + SealedStream.CHUNK_OVERHEAD)
        SealedStream.sealChunk(keyBytes, header, purpose, 0, true, directBuffer(plaintext), sealed, random)
        sealed.flip()
        val chunk = sealed.contents()
        assertThrows(UndecryptableException::class.java) { Envelope.open(keyBytes, envelopePurpose, byteArrayOf(0x50, 0x50, 0x45, 0x01) + chunk) }
        // Nothing else keeps it shut: under the chunk's own data the very same bytes open.
        val jca = Cipher.getInstance("AES/GCM/NoPadding")
        jca.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, chunk, 0, 12))
        jca.updateAAD(chunkAad)
        assertArrayEquals(plaintext, jca.doFinal(chunk, 12, chunk.size - 12))

        // And an envelope's nonce, ciphertext and tag, handed over as that chunk.
        val envelope = Envelope.seal(keyBytes, envelopePurpose, plaintext, random)
        val plain = ByteBuffer.allocateDirect(plaintext.size + SealedStream.TAG_SIZE)
        assertThrows(UndecryptableException::class.java) {
            SealedStream.openChunk(keyBytes, header, purpose, 0, true, directBuffer(envelope.copyOfRange(4, envelope.size)), plain)
        }
        assertArrayEquals(plaintext, Envelope.open(keyBytes, envelopePurpose, envelope))
    }

    @Test
    fun whateverThePurposeAndTheStreamTheThirdByteOfTheDataIsTheFormsOwn() {
        val seeded = Random(11)
        repeat(500) {
            val id = seeded.nextBytes(16)
            val header = SealedStream.newHeader(seeded.nextLong(), ScriptedRandom(id))
            val text = String(CharArray(seeded.nextInt(0, 40)) { seeded.nextInt(0x20, 0x7f).toChar() })
            val aad = SealedStream.aad(header, text, seeded.nextLong(0, Long.MAX_VALUE), seeded.nextBoolean())
            assertArrayEquals(byteArrayOf(0x50, 0x50, 0x53, 0x01), aad.copyOfRange(0, 4))
            assertNotEquals("an envelope's data has E here", 0x45.toByte(), aad[2])
        }
    }

    // --- The header ---

    @Test
    fun aHeaderCarriesTheIdAndTheTimeAndIsOnlyReadAsAStreamOfVersion1() {
        for (millis in listOf(0L, 1L, time, -1L, Long.MAX_VALUE, Long.MIN_VALUE)) {
            val id = ByteArray(16).also(random::nextBytes)
            val header = SealedStream.newHeader(millis, ScriptedRandom(id))
            assertEquals(millis, header.oldestMessageMillis)
            assertArrayEquals(id, header.streamId)
            val read = SealedStream.parseHeader(header.bytes() + bytes(9))!!
            assertEquals(header, read)
            assertEquals(header.hashCode(), read.hashCode())
            assertEquals(millis, read.oldestMessageMillis)
        }
        val good = SealedStream.newHeader(time, random).bytes()
        assertNull("too short", SealedStream.parseHeader(good.copyOf(27)))
        assertNull(SealedStream.parseHeader(ByteArray(0)))
        for (position in 0..2) assertNull("magic byte $position", SealedStream.parseHeader(good.copyOf().also { it[position] = 'X'.code.toByte() }))
        for (version in listOf(0, 2, 0x7f)) assertNull("version $version", SealedStream.parseHeader(good.copyOf().also { it[3] = version.toByte() }))
        assertFalse(SealedStream.looksSealed("# Notes".toByteArray()))
        assertFalse(SealedStream.looksSealed(ByteArray(0)))
        // A header gives away nothing it holds by accident, and nothing changes it from outside.
        val header = SealedStream.parseHeader(good)!!
        header.bytes().fill(0)
        header.streamId.fill(0)
        assertArrayEquals(good, header.bytes())
    }

    // --- Sealing ---

    @Test
    fun aStreamOfAnySizeIsFullChunksAndOneLastChunk() {
        for (size in listOf(0, 1, 15, 16, 17, 31, 32, 33, 48, 100)) {
            val plaintext = ByteArray(size).also(random::nextBytes)
            val stream = seal(plaintext)
            assertEquals("size $size", SealedStream.sealedSize(size.toLong(), SMALL), stream.size.toLong())
            val sizes = chunks(stream).map { it.size - SealedStream.CHUNK_OVERHEAD }
            assertEquals("size $size: one form only", if (size == 0) listOf(0) else List((size - 1) / 16) { 16 } + ((size - 1) % 16 + 1), sizes)
            assertArrayEquals("size $size", plaintext, open(stream))
        }
    }

    @Test
    fun aChunkOfAnotherSizeIsNotSealedAndNothingFollowsTheLast() {
        val sealer = StreamSealer(cipher, purpose, time, SMALL)
        val sealed = ByteBuffer.allocateDirect(64)

        assertThrows("a chunk before the last is full", IllegalArgumentException::class.java) { sealer.seal(directBuffer(bytes(15)), false, sealed) }
        assertThrows(IllegalArgumentException::class.java) { sealer.seal(directBuffer(bytes(0)), false, sealed) }
        assertThrows("no chunk holds more", IllegalArgumentException::class.java) { sealer.seal(directBuffer(bytes(17)), false, sealed) }
        assertThrows(IllegalArgumentException::class.java) { sealer.seal(directBuffer(bytes(17)), true, sealed) }
        assertThrows("the plaintext is direct", IllegalArgumentException::class.java) { sealer.seal(ByteBuffer.wrap(bytes(16)), false, sealed) }
        assertThrows("and so is the sealed chunk", IllegalArgumentException::class.java) { sealer.seal(directBuffer(bytes(16)), false, ByteBuffer.allocate(64)) }
        assertThrows("room for the nonce and the tag", IllegalArgumentException::class.java) { sealer.seal(directBuffer(bytes(16)), false, ByteBuffer.allocateDirect(43)) }
        assertEquals("none of these was sealed", 0L, sealer.chunks)
        assertEquals(0, sealed.position())

        sealer.seal(directBuffer(bytes(16)), false, sealed)
        assertEquals(1L, sealer.chunks)
        sealed.clear()
        assertThrows("only the one chunk of an empty stream is empty", IllegalArgumentException::class.java) { sealer.seal(directBuffer(bytes(0)), true, sealed) }
        assertFalse(sealer.isFinished)
        sealer.seal(directBuffer(bytes(1)), true, sealed)
        assertTrue(sealer.isFinished)
        assertEquals(2L, sealer.chunks)
        sealed.clear()
        assertThrows(IllegalStateException::class.java) { sealer.seal(directBuffer(bytes(16)), false, sealed) }
        assertThrows(IllegalStateException::class.java) { sealer.seal(directBuffer(bytes(1)), true, sealed) }
    }

    @Test
    fun sealingReadsThePlaintextWhereItLiesAndLeavesItAsItIs() {
        val sealer = StreamSealer(cipher, purpose, time, SMALL)
        // The chunk is a part of a larger buffer, as a piece is of the buffer an export fills.
        val buffer = ByteBuffer.allocateDirect(40)
        buffer.put(bytes(40, from = 100))
        buffer.position(3)
        buffer.limit(19)
        val sealed = ByteBuffer.allocateDirect(4 + 44 + 4)
        sealed.position(4)

        sealer.seal(buffer, false, sealed)

        assertEquals("read to its limit", 19, buffer.position())
        assertArrayEquals("and not changed", bytes(40, from = 100), buffer.everything())
        assertEquals("written from where the buffer stood", 4 + 44, sealed.position())
        sealed.flip()
        sealed.position(4)
        assertArrayEquals(bytes(16, from = 103) + bytes(1), open(sealer.header, listOf(sealed.contents(), lastChunk(sealer))))
    }

    private fun lastChunk(sealer: StreamSealer): ByteArray {
        val sealed = ByteBuffer.allocateDirect(1 + SealedStream.CHUNK_OVERHEAD)
        sealer.seal(directBuffer(bytes(1)), true, sealed)
        sealed.flip()
        return sealed.contents()
    }

    // --- Opening ---

    /** One chunk, sealed directly, at any place and of any size: what a writer never makes. */
    private fun chunk(header: StreamHeader, index: Long, last: Boolean, plaintext: ByteArray, purpose: String = this.purpose, cipher: ChunkCipher = this.cipher): ByteArray {
        val sealed = ByteBuffer.allocateDirect(plaintext.size + SealedStream.CHUNK_OVERHEAD)
        cipher.sealChunk(header, purpose, index, last, directBuffer(plaintext), sealed)
        sealed.flip()
        return sealed.contents()
    }

    private fun assertOpens(expected: ByteArray, header: StreamHeader, purpose: String, index: Long, last: Boolean, chunk: ByteArray, cipher: ChunkCipher = this.cipher) {
        val plain = ByteBuffer.allocateDirect(chunk.size)
        assertEquals(expected.size, cipher.openChunk(header, purpose, index, last, directBuffer(chunk), plain))
        plain.flip()
        assertArrayEquals(expected, plain.contents())
    }

    private fun assertDoesNotOpen(what: String, header: StreamHeader, purpose: String, index: Long, last: Boolean, chunk: ByteArray, cipher: ChunkCipher = this.cipher) {
        val plain = ByteBuffer.allocateDirect(chunk.size)
        assertThrows(what, UndecryptableException::class.java) { cipher.openChunk(header, purpose, index, last, directBuffer(chunk), plain) }
    }

    /** Each part of what a chunk authenticates, changed alone: the stream, the time, the purpose, the place, the end. */
    @Test
    fun aChunkOnlyOpensAsTheChunkItWasSealedAs() {
        val id = ByteArray(16).also(random::nextBytes)
        val header = SealedStream.newHeader(time, ScriptedRandom(id))
        val plaintext = bytes(16)
        val sealed = chunk(header, index = 5, last = false, plaintext)

        assertOpens(plaintext, header, purpose, 5, false, sealed)
        assertOpens(plaintext, SealedStream.parseHeader(header.bytes())!!, purpose, 5, false, sealed)

        val otherStream = SealedStream.newHeader(time, ScriptedRandom(id.copyOf().also { it[15] = (it[15] + 1).toByte() }))
        assertDoesNotOpen("another stream", otherStream, purpose, 5, false, sealed)
        assertDoesNotOpen("another time", SealedStream.newHeader(time + 1, ScriptedRandom(id)), purpose, 5, false, sealed)
        assertDoesNotOpen("another time", SealedStream.newHeader(0, ScriptedRandom(id)), purpose, 5, false, sealed)
        assertDoesNotOpen("another chat", header, Purposes.of("model-memory", "another"), 5, false, sealed)
        assertDoesNotOpen("another store", header, Purposes.of("session", "3f2a7c1e-5b0d-4e8a-9c1f-0a1b2c3d4e5f"), 5, false, sealed)
        assertDoesNotOpen("another place", header, purpose, 4, false, sealed)
        assertDoesNotOpen("another place", header, purpose, 6, false, sealed)
        assertDoesNotOpen("another place", header, purpose, 5L + (1L shl 32), false, sealed)
        assertDoesNotOpen("as the last chunk", header, purpose, 5, true, sealed)
        assertDoesNotOpen("the last chunk as one before it", header, purpose, 5, false, chunk(header, 5, true, plaintext))
        assertDoesNotOpen("another key", header, purpose, 5, false, sealed, cipher = TestKeys.cipher())
        for (position in sealed.indices) {
            val changed = sealed.copyOf().also { it[position] = (it[position].toInt() xor 0x01).toByte() }
            assertDoesNotOpen("byte $position", header, purpose, 5, false, changed)
        }
        for (length in listOf(0, 11, 12, 27, sealed.size - 1)) assertDoesNotOpen("length $length", header, purpose, 5, false, sealed.copyOf(length))
    }

    @Test
    fun aChunkIsOnlyEverSealedAndOpenedBetweenDirectBuffersWithRoomForTheTag() {
        val header = cipher.newStream(time)
        val sealed = chunk(header, 0, true, bytes(16))
        assertThrows(IllegalArgumentException::class.java) { cipher.sealChunk(header, purpose, 0, true, ByteBuffer.wrap(bytes(16)), ByteBuffer.allocateDirect(44)) }
        assertThrows(IllegalArgumentException::class.java) { cipher.sealChunk(header, purpose, 0, true, directBuffer(bytes(16)), ByteBuffer.allocate(44)) }
        assertThrows(IllegalArgumentException::class.java) { cipher.openChunk(header, purpose, 0, true, ByteBuffer.wrap(sealed), ByteBuffer.allocateDirect(32)) }
        assertThrows(IllegalArgumentException::class.java) { cipher.openChunk(header, purpose, 0, true, directBuffer(sealed), ByteBuffer.allocate(32)) }
        assertThrows(IllegalArgumentException::class.java) { cipher.openChunk(header, purpose, 0, true, directBuffer(sealed), ByteBuffer.allocateDirect(32).asReadOnlyBuffer()) }
        // The cipher of the oldest Android the app runs on refuses an output without room for the
        // tag as well as the plaintext; the same is asked for everywhere, so that no test passes with less.
        for (room in listOf(16, 31)) {
            assertThrows("room for $room bytes", IllegalArgumentException::class.java) { cipher.openChunk(header, purpose, 0, true, directBuffer(sealed), ByteBuffer.allocateDirect(room)) }
        }
        assertOpens(bytes(16), header, purpose, 0, true, sealed)

        val opener = StreamOpener(cipher, purpose, header, SMALL)
        assertThrows(IllegalArgumentException::class.java) { opener.open(ByteBuffer.wrap(sealed), true, ByteBuffer.allocateDirect(32)) }
        assertThrows(IllegalArgumentException::class.java) { opener.open(directBuffer(sealed), true, ByteBuffer.allocate(32)) }
        assertThrows(IllegalArgumentException::class.java) { opener.open(directBuffer(sealed), true, ByteBuffer.allocateDirect(32).asReadOnlyBuffer()) }
        assertFalse(opener.isFinished)
    }

    /**
     * The form of a stream is held to when it is opened as well, so that a plaintext has one
     * form as a stream and no other is ever accepted: these chunks were sealed with the right
     * key for the very place they stand at, and are refused for their size alone.
     */
    @Test
    fun chunksThatAuthenticateButBreakTheFormAreNotOpened() {
        val header = cipher.newStream(time)
        fun opener() = StreamOpener(cipher, purpose, header, SMALL)
        fun plain() = ByteBuffer.allocateDirect(SMALL + 2 * SealedStream.TAG_SIZE)

        val shortBeforeTheLast = chunk(header, 0, false, bytes(5))
        assertOpens(bytes(5), header, purpose, 0, false, shortBeforeTheLast)
        assertThrows(UndecryptableException::class.java) { opener().open(directBuffer(shortBeforeTheLast), false, plain()) }

        val tooLong = chunk(header, 0, true, bytes(17))
        assertOpens(bytes(17), header, purpose, 0, true, tooLong)
        assertThrows(UndecryptableException::class.java) { opener().open(directBuffer(tooLong), true, plain()) }

        val emptyAfterAFullOne = chunk(header, 1, true, bytes(0))
        assertOpens(bytes(0), header, purpose, 1, true, emptyAfterAFullOne)
        val opener = opener()
        assertEquals(16, opener.open(directBuffer(chunk(header, 0, false, bytes(16))), false, plain()))
        assertThrows(UndecryptableException::class.java) { opener.open(directBuffer(emptyAfterAFullOne), true, plain()) }

        // The one empty chunk there is: the whole of an empty stream.
        assertEquals(0, opener().open(directBuffer(chunk(header, 0, true, bytes(0))), true, plain()))
    }

    @Test
    fun chunksOutOfPlaceDoNotOpenAndNoChunkIsOpenedAfterOneThatFailed() {
        val stream = seal(bytes(53))
        val header = SealedStream.parseHeader(stream)!!
        val (first, second, third, fourth) = chunks(stream)
        assertArrayEquals(bytes(53), open(header, listOf(first, second, third, fourth)))

        assertThrows("one left out", UndecryptableException::class.java) { open(header, listOf(first, third, fourth)) }
        assertThrows("one repeated", UndecryptableException::class.java) { open(header, listOf(first, first, second, third, fourth)) }
        assertThrows("two exchanged", UndecryptableException::class.java) { open(header, listOf(second, first, third, fourth)) }
        assertThrows("the last cut off", UndecryptableException::class.java) { open(header, listOf(first, second, third)) }
        assertThrows("one added", UndecryptableException::class.java) { open(header, listOf(first, second, third, fourth, fourth)) }
        assertThrows("one added", UndecryptableException::class.java) { open(header, listOf(first, second, third, fourth, first)) }

        // A stream whose last chunk is full: what was added cannot hide behind a size no chunk has.
        val exact = seal(bytes(48))
        val (one, two, three) = chunks(exact)
        val exactHeader = SealedStream.parseHeader(exact)!!
        assertArrayEquals(bytes(48), open(exactHeader, listOf(one, two, three)))
        assertThrows("one added", UndecryptableException::class.java) { open(exactHeader, listOf(one, two, three, three)) }
        assertThrows("one added", UndecryptableException::class.java) { open(exactHeader, listOf(one, two, three, one)) }
        assertThrows("the last cut off", UndecryptableException::class.java) { open(exactHeader, listOf(one, two)) }
        assertThrows("all but the first cut off", UndecryptableException::class.java) { open(exactHeader, listOf(one)) }

        // After a chunk that failed, the very chunk that belongs there is not opened either.
        val opener = StreamOpener(cipher, purpose, header, SMALL)
        val plain = ByteBuffer.allocateDirect(SMALL + SealedStream.TAG_SIZE)
        assertEquals(16, opener.open(directBuffer(first), false, plain))
        plain.clear()
        assertThrows(UndecryptableException::class.java) { opener.open(directBuffer(third), false, plain) }
        assertThrows(UndecryptableException::class.java) { opener.open(directBuffer(second), false, plain) }
        assertFalse(opener.isFinished)

        // And after the last chunk there is nothing to open.
        val whole = StreamOpener(cipher, purpose, header, SMALL)
        for (chunk in listOf(first, second, third)) {
            plain.clear()
            whole.open(directBuffer(chunk), false, plain)
        }
        plain.clear()
        plain.limit(fourth.size - SealedStream.NONCE_SIZE)
        assertEquals(5, whole.open(directBuffer(fourth), true, plain))
        assertTrue(whole.isFinished)
        plain.clear()
        assertThrows(IllegalStateException::class.java) { whole.open(directBuffer(fourth), true, plain) }
    }

    /** A cipher as every cipher is inside: the plaintext is in the caller's buffer before the tag has been compared. */
    private class WritesBeforeItFails(private val failure: IOException) : ChunkCipher {
        var calls = 0

        override fun newStream(oldestMessageMillis: Long): StreamHeader = throw AssertionError("not used")

        override fun sealChunk(header: StreamHeader, purpose: String, index: Long, last: Boolean, plaintext: ByteBuffer, sealed: ByteBuffer) = throw AssertionError("not used")

        override fun openChunk(header: StreamHeader, purpose: String, index: Long, last: Boolean, sealed: ByteBuffer, plaintext: ByteBuffer): Int {
            calls++
            while (plaintext.hasRemaining()) plaintext.put(0x5a)
            throw failure
        }
    }

    @Test
    fun whatAChunkThatDoesNotOpenLeftInTheBufferIsOverwritten() {
        val header = cipher.newStream(time)
        val sealed = chunk(header, 0, true, bytes(16))
        for (failure in listOf(UndecryptableException("Decryption failed"), KeyDestroyedException(), IOException("anything else"))) {
            val leaking = WritesBeforeItFails(failure)
            val opener = StreamOpener(leaking, purpose, header, SMALL)
            // The window the cipher may write to lies inside a larger buffer; what is around it stays.
            val plain = ByteBuffer.allocateDirect(48)
            repeat(48) { plain.put(0x11) }
            plain.position(8)
            plain.limit(40)

            val thrown = assertThrows(IOException::class.java) { opener.open(directBuffer(sealed), true, plain) }

            assertEquals(failure, thrown)
            assertEquals(1, leaking.calls)
            assertEquals("the position is where it was", 8, plain.position())
            assertEquals(40, plain.limit())
            assertArrayEquals(ByteArray(8) { 0x11 } + ByteArray(32) + ByteArray(8) { 0x11 }, plain.everything())
        }

        // The same with the real cipher and a chunk whose tag is wrong.
        val changed = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x80).toByte() }
        val opener = StreamOpener(cipher, purpose, header, SMALL)
        val plain = ByteBuffer.allocateDirect(32)
        repeat(32) { plain.put(0x11) }
        plain.clear()
        assertThrows(UndecryptableException::class.java) { opener.open(directBuffer(changed), true, plain) }
        assertArrayEquals(ByteArray(32), plain.everything())
        assertEquals(0, plain.position())
    }

    @Test
    fun aStreamThatFailedIsNotOpenedFurtherButALockDoesNotCondemnIt() {
        val header = cipher.newStream(time)
        val sealed = chunk(header, 0, true, bytes(16))
        val plain = ByteBuffer.allocateDirect(32)

        // A chunk that did not authenticate ends the stream: the cipher is not asked again.
        val refusing = WritesBeforeItFails(UndecryptableException("Decryption failed"))
        val failed = StreamOpener(refusing, purpose, header, SMALL)
        assertThrows(UndecryptableException::class.java) { failed.open(directBuffer(sealed), true, plain) }
        assertThrows(UndecryptableException::class.java) { failed.open(directBuffer(sealed), true, plain) }
        assertEquals(1, refusing.calls)

        // A key that is gone says nothing about the stream: it is the lock that answers, every time.
        val locked = WritesBeforeItFails(KeyDestroyedException())
        val waiting = StreamOpener(locked, purpose, header, SMALL)
        assertThrows(KeyDestroyedException::class.java) { waiting.open(directBuffer(sealed), true, plain) }
        assertThrows(KeyDestroyedException::class.java) { waiting.open(directBuffer(sealed), true, plain) }
        assertEquals(2, locked.calls)
    }

    // --- The data key ---

    @Test
    fun onceTheKeyIsDestroyedNoFurtherChunkIsSealedOrOpenedAndNoStreamBegun() {
        val dataKey = DataKey(keyBytes)
        val crypto = DataCrypto(dataKey)
        val sealer = StreamSealer(crypto, purpose, time, SMALL)
        val first = ByteBuffer.allocateDirect(44)
        sealer.seal(directBuffer(bytes(16)), false, first)
        first.flip()
        val opener = StreamOpener(crypto, purpose, sealer.header, SMALL)
        val plain = ByteBuffer.allocateDirect(32)
        assertEquals(16, opener.open(first.duplicate(), false, plain))

        crypto.destroy()

        assertTrue(dataKey.isDestroyed)
        val second = ByteBuffer.allocateDirect(44)
        assertThrows(KeyDestroyedException::class.java) { sealer.seal(directBuffer(bytes(16, from = 16)), false, second) }
        assertThrows("and again: the stream is not continued under any name", KeyDestroyedException::class.java) { sealer.seal(directBuffer(bytes(1)), true, second) }
        assertEquals("nothing was written for it", 0, second.position())
        assertArrayEquals(ByteArray(44), second.everything())
        assertEquals(1L, sealer.chunks)
        assertFalse(sealer.isFinished)
        assertThrows(KeyDestroyedException::class.java) { StreamSealer(crypto, purpose, time, SMALL) }
        assertThrows(KeyDestroyedException::class.java) { crypto.newStream(time) }

        // A chunk that was sealed while the key lived does not open any more either.
        val again = StreamOpener(crypto, purpose, sealer.header, SMALL)
        plain.clear()
        repeat(32) { plain.put(0x11) }
        plain.clear()
        assertThrows(KeyDestroyedException::class.java) { again.open(first.duplicate(), false, plain) }
        assertArrayEquals("and the buffer it would have gone to is blank", ByteArray(32), plain.everything())
        // It was whole, though: with the key it opens.
        assertOpens(bytes(16), sealer.header, purpose, 0, false, first.contents(), cipher = DataCrypto(DataKey(keyBytes)))
    }

    /**
     * Destroying the key and a chunk in progress exclude each other: the lock waits until the
     * chunk is sealed, the chunk is whole, and the stream gets no further one. The random
     * source is where the test holds the chunk, since the nonce is drawn inside the operation.
     */
    @Test
    fun destroyingTheKeyWaitsForTheChunkThatIsBeingSealed() {
        val inside = CountDownLatch(1)
        val goOn = CountDownLatch(1)
        val pausing = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) {
                if (bytes.size == SealedStream.NONCE_SIZE) {
                    inside.countDown()
                    goOn.await()
                }
                super.nextBytes(bytes)
            }
        }
        val dataKey = DataKey(keyBytes)
        val crypto = DataCrypto(dataKey, pausing)
        val sealer = StreamSealer(crypto, purpose, time, SMALL)
        val sealed = ByteBuffer.allocateDirect(44)
        val failure = AtomicReference<Throwable>()
        val sealing = thread {
            try {
                sealer.seal(directBuffer(bytes(16)), false, sealed)
            } catch (e: Throwable) {
                failure.set(e)
            }
        }
        assertTrue(inside.await(10, TimeUnit.SECONDS))

        val destroyed = CountDownLatch(1)
        val destroying = thread {
            crypto.destroy()
            destroyed.countDown()
        }
        assertFalse("the lock waits for the chunk", destroyed.await(300, TimeUnit.MILLISECONDS))
        assertFalse(dataKey.isDestroyed)

        goOn.countDown()
        sealing.join()
        destroying.join()

        assertNull(failure.get())
        assertTrue(dataKey.isDestroyed)
        sealed.flip()
        assertOpens(bytes(16), sealer.header, purpose, 0, false, sealed.contents(), cipher = DataCrypto(DataKey(keyBytes)))
        assertThrows(KeyDestroyedException::class.java) { sealer.seal(directBuffer(bytes(1)), true, ByteBuffer.allocateDirect(44)) }
    }

    // --- Overwriting ---

    @Test
    fun wipingOverwritesTheRangeAndLeavesThePositionAndTheLimit() {
        val buffer = ByteBuffer.allocateDirect(20_000)
        val filled = ByteArray(20_000) { 0x7f }
        buffer.put(filled)
        buffer.position(5)
        buffer.limit(17)

        buffer.wipe(100, 19_000)

        assertEquals(5, buffer.position())
        assertEquals(17, buffer.limit())
        assertArrayEquals(ByteArray(100) { 0x7f } + ByteArray(18_900) + ByteArray(1_000) { 0x7f }, buffer.everything())
        buffer.wipe()
        assertArrayEquals(ByteArray(20_000), buffer.everything())
        buffer.wipe(7, 7)
        assertThrows(IllegalArgumentException::class.java) { buffer.wipe(8, 7) }
        assertThrows(IllegalArgumentException::class.java) { buffer.wipe(0, 20_001) }
    }

    private companion object {
        /** The chunk size of most tests here, in place of the 4 MiB of version 1. */
        const val SMALL = 16
    }
}
