package us.theworkmans.proseplow.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import us.theworkmans.proseplow.DeviceTests
import us.theworkmans.proseplow.data.SealedStreamFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The sealed stream through the phone's own AES-GCM, which is what seals and opens the model's
 * memory, and which the unit tests on the build machine never run: the frozen vectors of
 * version 1, and one stream of three chunks at the real chunk size written and read back through
 * direct buffers, as a save and a restore do. If the platform's cipher treats a direct buffer
 * differently from the build machine's (an offset, a limit, the tag), the bytes differ here.
 *
 * The expected bytes are the ones the unit tests freeze (`SealedStreamTest`,
 * `SealedStreamFileTest`), computed from the written format alone with another implementation.
 */
@RunWith(AndroidJUnit4::class)
class SealedStreamOnDeviceTest {

    private lateinit var directory: File
    private val id = "3f2a7c1e-5b0d-4e8a-9c1f-0a1b2c3d4e5f"
    private val purpose = Purposes.of("model-memory", id)
    private val time = 1_700_000_000_000L

    @Before
    fun aDirectoryOfItsOwn() {
        directory = DeviceTests.scratchDirectory("sealed-stream")
    }

    @After
    fun removeIt() {
        directory.deleteRecursively()
    }

    /** Hands out the bytes written down for it, draw by draw, in place of random ones. */
    private class ScriptedRandom(vararg draws: ByteArray) : SecureRandom() {
        private val draws = ArrayDeque(draws.toList())

        @Synchronized
        override fun nextBytes(bytes: ByteArray) {
            val next = draws.removeFirstOrNull() ?: throw AssertionError("a draw of ${bytes.size} bytes that was not written down")
            if (next.size != bytes.size) throw AssertionError("a draw of ${bytes.size} bytes where one of ${next.size} was written down")
            next.copyInto(bytes)
        }
    }

    private fun bytes(size: Int, from: Int = 0) = ByteArray(size) { (from + it).toByte() }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private fun direct(bytes: ByteArray): ByteBuffer = ByteBuffer.allocateDirect(bytes.size).put(bytes).also { it.flip() }

    private fun ByteBuffer.contents(): ByteArray = ByteArray(remaining()).also { duplicate().get(it) }

    /** Catches a platform cipher that seals one chunk otherwise than the format says. */
    @Test
    fun aStreamOfVersion1WithOneChunkIsTheFrozenBytes() {
        val scripted = DataCrypto(DataKey(bytes(32)), ScriptedRandom(bytes(16, from = 0xa0), bytes(12, from = 0xc0)))
        val sealer = StreamSealer(scripted, purpose, time)
        val sealed = ByteBuffer.allocateDirect(21 + SealedStream.CHUNK_OVERHEAD)
        sealer.seal(direct("the memory of a model".toByteArray(Charsets.US_ASCII)), last = true, sealed)
        sealed.flip()
        val stream = sealer.header.bytes() + sealed.contents()

        assertEquals(
            "50505301a0a1a2a3a4a5a6a7a8a9aaabacadaeaf0000018bcfe56800" +
                "c0c1c2c3c4c5c6c7c8c9cacb" + "742042471a1dfb31eaa0ddcd548c8976211c875a50" + "5b1f9129147be31e7473448491bec6d6",
            stream.hex(),
        )
        val opener = StreamOpener(DataCrypto(DataKey(bytes(32))), purpose, SealedStream.parseHeader(stream)!!)
        val plain = ByteBuffer.allocateDirect(21 + SealedStream.TAG_SIZE)
        assertEquals(21, opener.open(direct(stream.copyOfRange(28, stream.size)), last = true, plain))
        plain.flip()
        assertEquals("the memory of a model", String(plain.contents(), Charsets.US_ASCII))
    }

    /** Catches a platform cipher that gets the index or the last-chunk byte of a later chunk wrong. */
    @Test
    fun aStreamOfSeveralSmallChunksIsTheFrozenBytes() {
        val chunkSize = 16
        val nonces = (1..3).map { chunk -> bytes(12, from = 0x10 * chunk) }
        val scripted = DataCrypto(DataKey(bytes(32)), ScriptedRandom(bytes(16, from = 0xa0), *nonces.toTypedArray()))
        val sealer = StreamSealer(scripted, purpose, time, chunkSize)
        val plaintext = bytes(37)
        val stream = ByteArrayOutputStream().apply { write(sealer.header.bytes()) }
        val sealed = ByteBuffer.allocateDirect(chunkSize + SealedStream.CHUNK_OVERHEAD)
        var offset = 0
        while (offset < plaintext.size) {
            val length = minOf(chunkSize, plaintext.size - offset)
            sealed.clear()
            sealer.seal(direct(plaintext.copyOfRange(offset, offset + length)), last = offset + length == plaintext.size, sealed)
            sealed.flip()
            stream.write(sealed.contents())
            offset += length
        }

        assertEquals(
            "50505301a0a1a2a3a4a5a6a7a8a9aaabacadaeaf0000018bcfe56800" +
                "101112131415161718191a1b" + "7dff9a154dcc3cb4c27c02160374675c" + "52d635db5a17836563f9bf27f032762f" +
                "202122232425262728292a2b" + "c22bb463788d0c19026558d5dd05eae6" + "4334fd72017ffd5709906609ba90c41c" +
                "303132333435363738393a3b" + "931413e3cd" + "914bbe59650f595f91f6b552496d0cd7",
            stream.toByteArray().hex(),
        )
    }

    /**
     * One stream at the real size, through the file and its direct buffers, as a save writes it
     * and a restore reads it: two full chunks of 4 MiB and a last one of five bytes. Catches a
     * platform cipher whose handling of large direct buffers changes a byte, and a read that
     * hands back anything other than what was written.
     */
    @Test
    fun aStreamOfThreeChunksAtTheRealSizeIsTheFrozenBytesAndComesBackWhole() {
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

        assertEquals(SealedStream.sealedSize(size.toLong()), file.file.length())
        val digest = MessageDigest.getInstance("SHA-256")
        file.file.inputStream().use { input ->
            val block = ByteArray(65_536)
            while (true) {
                val count = input.read(block)
                if (count < 0) break
                digest.update(block, 0, count)
            }
        }
        assertEquals("a211ca066c3913bfbed0d9026f7219192c6ad02c107abfe81d3946334a55a259", digest.digest().hex())

        val sizes = ArrayList<Int>()
        SealedStreamFile(file.file, purpose, DataCrypto(DataKey(key))).read()!!.use { reader ->
            var offset = 0
            while (true) {
                val chunk = reader.next() ?: break
                for (position in 0 until chunk.remaining()) {
                    if (chunk.get(position) != content(offset + position)) fail("byte ${offset + position} came back changed")
                }
                sizes += chunk.remaining()
                offset += chunk.remaining()
            }
        }
        assertEquals(listOf(chunkSize, chunkSize, 5), sizes)
    }
}
