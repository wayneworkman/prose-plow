package us.theworkmans.proseplow.security

import java.io.IOException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The 28 bytes a sealed stream begins with ([SealedStream]): the magic and the version, the 16
 * random bytes that tell this stream from every other, and the date of the oldest message its
 * content was made from.
 *
 * The header is stored in the clear and is part of what every chunk authenticates. Read from a
 * file, it is therefore only as true as the file until a chunk of the stream has opened under
 * it: before that it may decide that a file is deleted, never that one is kept or used.
 */
class StreamHeader internal constructor(encoded: ByteArray) {
    init {
        require(encoded.size == SealedStream.HEADER_SIZE) { "A stream header has ${SealedStream.HEADER_SIZE} bytes" }
    }

    private val encoded = encoded.copyOf()

    /** The 16 random bytes that tell this stream from every other; a copy. */
    val streamId: ByteArray get() = encoded.copyOfRange(SealedStream.ID_OFFSET, SealedStream.TIME_OFFSET)

    /**
     * The date the header carries, in milliseconds since 1970: for a memory of a conversation,
     * the date of the oldest message it was made from, which is the date its file expires by.
     * The format gives the number no further meaning; whether a value is usable is its store's
     * rule.
     */
    val oldestMessageMillis: Long
        get() {
            var value = 0L
            for (index in SealedStream.TIME_OFFSET until SealedStream.HEADER_SIZE) value = (value shl 8) or (encoded[index].toLong() and 0xff)
            return value
        }

    /** The header as it is stored: a copy of its 28 bytes. */
    fun bytes(): ByteArray = encoded.copyOf()

    /** Copies the 28 bytes into [target] from [offset] on. */
    internal fun copyInto(target: ByteArray, offset: Int) {
        encoded.copyInto(target, offset)
    }

    override fun equals(other: Any?): Boolean = other is StreamHeader && encoded.contentEquals(other.encoded)

    override fun hashCode(): Int = encoded.contentHashCode()

    override fun toString(): String = "StreamHeader(oldestMessageMillis=$oldestMessageMillis)"
}

/**
 * The second form of the app's encrypted data, beside [Envelope]: a stream of chunks, for content
 * that is too large to be held in memory at once (the working memory of a model, hundreds of
 * megabytes). An envelope is one AES-GCM message, and nothing of such a message may be used before
 * its tag has been checked, so it needs the whole content in memory, as plaintext and as
 * ciphertext. A stream is sealed and opened one chunk at a time:
 *
 * ```
 * stream  = "PPS" | version (1 byte, 0x01) | stream id (16 bytes, random) | oldest message time (8 bytes) | chunk 0 | chunk 1 | ...
 * chunk i = nonce (12 bytes, random) | AES-256-GCM ciphertext | tag (16 bytes)
 * ```
 *
 * The additional authenticated data of chunk `i` is the 28-byte header, the UTF-8 text
 * `proseplow:<purpose>`, `i` as 8 bytes and one byte that is 1 for the last chunk of the stream
 * and 0 for every other. All numbers have their most significant byte first. The key is the data
 * key itself, as for an envelope.
 *
 * Every chunk but the last holds exactly [CHUNK_SIZE] bytes of plaintext; the last holds from 1
 * byte up to that, or none when it is the only chunk (an empty stream). A plaintext therefore
 * has exactly one form as a stream, and a stream has at least one chunk.
 *
 * **The chunk size belongs to the version; it is not stored.** A reader has to know where a
 * chunk ends before it can check the chunk's tag, so a size read from the file would have to be
 * believed, and a buffer of that size allocated, before anything had authenticated. With the
 * size fixed, every buffer a reader ever allocates has a size the code states, whatever the file
 * says. Another chunk size is another version, whose byte is in the header and so in what every
 * chunk authenticates.
 *
 * What each part is for:
 * - the stream id: a chunk of another stream, even of an earlier writing of the same content,
 *   does not open in this one;
 * - the purpose: a stream moved to another file, or handed to another store, does not open
 *   ([Purposes]);
 * - the index: a chunk that is left out, repeated or moved does not open where it stands;
 * - the last-chunk byte: a stream cut off after any chunk does not open, because the chunk it
 *   then ends with was not sealed as the last, and nothing can follow the chunk that was;
 * - the time in the header: it cannot be changed without every chunk failing.
 *
 * The magic differs from the envelope's in its third byte, and both forms begin their
 * additional authenticated data with their own magic, so no chunk can open as an envelope and no
 * envelope as a chunk, whatever the purposes are. The magic, the version byte, the prefix and the
 * chunk size of version 1 are permanent once data has been written with them.
 *
 * **Random nonces.** Every chunk draws a new 96-bit nonce and counts as one message under the
 * data key, as every envelope does. Random nonces are safe for 2^32 messages under one key (the
 * chance that two ever agree then stays below 2^-32); at 4 MiB a chunk that is 2^54 bytes of
 * streams, or 16 PiB.
 *
 * **Buffers.** Chunks are sealed and opened between direct buffers only, so that nothing here
 * copies a chunk of plaintext into an array of the collected heap, and neither the plaintext nor
 * the ciphertext of a whole stream is ever in memory. What the cipher itself does with a chunk
 * is the platform's: from Android 12 on it reads and writes direct buffers where they lie; the
 * versions before that copy each chunk they are given into an array of their own, which nothing
 * can overwrite. The plaintext and the sealed buffer of one operation must be separate
 * allocations: where the two overlap or touch, also the newer cipher copies its input.
 */
object SealedStream {
    const val VERSION: Byte = 1
    private val MAGIC = byteArrayOf('P'.code.toByte(), 'P'.code.toByte(), 'S'.code.toByte())
    const val STREAM_ID_SIZE = 16
    internal const val ID_OFFSET = 4
    internal const val TIME_OFFSET = ID_OFFSET + STREAM_ID_SIZE
    const val HEADER_SIZE = TIME_OFFSET + 8
    const val NONCE_SIZE = 12
    const val TAG_SIZE = 16

    /** What a sealed chunk has beyond its plaintext: the nonce and the tag. */
    const val CHUNK_OVERHEAD = NONCE_SIZE + TAG_SIZE

    /** The plaintext of every chunk but the last, in bytes (4 MiB): part of version 1. */
    const val CHUNK_SIZE = 4 * 1024 * 1024

    private const val INDEX_SIZE = 8
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val AAD_PREFIX = "proseplow:"

    /** True if [prefix] starts like a sealed stream (any version); an envelope never does. */
    fun looksSealed(prefix: ByteArray): Boolean =
        prefix.size >= MAGIC.size && MAGIC.indices.all { prefix[it] == MAGIC[it] }

    /**
     * The header that [bytes] begin with, or null when they do not begin with the header of a
     * stream of this version: fewer than 28 bytes, another magic or another version. Nothing is
     * authenticated here; see [StreamHeader].
     */
    fun parseHeader(bytes: ByteArray): StreamHeader? {
        if (bytes.size < HEADER_SIZE || !looksSealed(bytes) || bytes[MAGIC.size] != VERSION) return null
        return StreamHeader(bytes.copyOf(HEADER_SIZE))
    }

    /** The header of a new stream: a stream id drawn from [random], and [oldestMessageMillis]. */
    internal fun newHeader(oldestMessageMillis: Long, random: SecureRandom): StreamHeader {
        val encoded = ByteArray(HEADER_SIZE)
        MAGIC.copyInto(encoded)
        encoded[MAGIC.size] = VERSION
        val id = ByteArray(STREAM_ID_SIZE).also(random::nextBytes)
        id.copyInto(encoded, ID_OFFSET)
        putLong(encoded, TIME_OFFSET, oldestMessageMillis)
        return StreamHeader(encoded)
    }

    /**
     * The size of the stream that holds [plaintextSize] bytes, header included: what a store
     * needs free before it writes one.
     */
    fun sealedSize(plaintextSize: Long): Long = sealedSize(plaintextSize, CHUNK_SIZE)

    internal fun sealedSize(plaintextSize: Long, chunkSize: Int): Long {
        require(plaintextSize >= 0) { "A size cannot be negative" }
        val chunks = if (plaintextSize == 0L) 1 else (plaintextSize + chunkSize - 1) / chunkSize
        return HEADER_SIZE + plaintextSize + chunks * CHUNK_OVERHEAD
    }

    /**
     * True when a chunk of [length] bytes of plaintext may stand at [index] in a stream whose
     * chunks hold [chunkSize] bytes: full unless it is the last, and not empty unless it is the
     * only one.
     */
    internal fun chunkFits(length: Int, index: Long, last: Boolean, chunkSize: Int): Boolean =
        if (last) length in 1..chunkSize || (length == 0 && index == 0L) else length == chunkSize

    /**
     * The additional authenticated data of chunk [index]: the header, the prefixed purpose, the
     * index and the last-chunk byte. The purpose is the only part without a fixed length and
     * stands between parts that have one, so two different chunks never share this text.
     */
    internal fun aad(header: StreamHeader, purpose: String, index: Long, last: Boolean): ByteArray {
        require(index >= 0) { "A chunk index cannot be negative" }
        val text = "$AAD_PREFIX$purpose".toByteArray(Charsets.UTF_8)
        val aad = ByteArray(HEADER_SIZE + text.size + INDEX_SIZE + 1)
        header.copyInto(aad, 0)
        text.copyInto(aad, HEADER_SIZE)
        putLong(aad, HEADER_SIZE + text.size, index)
        aad[aad.size - 1] = if (last) 1 else 0
        return aad
    }

    /**
     * Seals one chunk with [key]: [plaintext] from its position to its limit, written to [sealed]
     * from its position on as nonce, ciphertext and tag. Both buffers are left at the end of
     * what was read and written. Which sizes a stream allows, and in which order, is
     * [StreamSealer]'s to hold; this is the one operation on one chunk.
     *
     * @throws IllegalArgumentException for a buffer that is not direct, and when [sealed] has no
     *   room for the chunk and its [CHUNK_OVERHEAD].
     */
    internal fun sealChunk(
        key: ByteArray,
        header: StreamHeader,
        purpose: String,
        index: Long,
        last: Boolean,
        plaintext: ByteBuffer,
        sealed: ByteBuffer,
        random: SecureRandom,
    ) {
        require(plaintext.isDirect && sealed.isDirect) { "A chunk is sealed between direct buffers only" }
        val length = plaintext.remaining()
        require(sealed.remaining() >= length + CHUNK_OVERHEAD) { "No room for a sealed chunk of $length bytes" }
        val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_SIZE * 8, nonce))
        cipher.updateAAD(aad(header, purpose, index, last))
        sealed.put(nonce)
        val written = cipher.doFinal(plaintext, sealed)
        check(written == length + TAG_SIZE) { "The cipher wrote $written bytes for a chunk of $length" }
    }

    /**
     * Opens one chunk with [key]: [sealed] from its position to its limit is the nonce, the
     * ciphertext and the tag, and the plaintext is written to [plaintext] from its position on.
     * Both buffers are left at the end of what was read and written.
     *
     * The cipher decrypts into [plaintext] before it has compared the tag. When this throws,
     * what [plaintext] holds from its position to its limit is therefore not to be used and not
     * to be left there: [StreamOpener], through which every chunk is opened, overwrites it.
     *
     * [plaintext] must have room for the ciphertext and the tag together, 16 bytes more than
     * will be written: the cipher of the oldest Android the app runs on refuses an output buffer
     * with less (it would look like a chunk that does not open), and asking for it everywhere
     * makes every test meet the same condition.
     *
     * @return the number of bytes of plaintext.
     * @throws UndecryptableException when the chunk does not authenticate as chunk [index] of
     *   this stream and purpose under [key], or is shorter than a nonce and a tag.
     * @throws IllegalArgumentException for a buffer that is not direct, and when [plaintext] has
     *   too little room or cannot be written to.
     */
    internal fun openChunk(
        key: ByteArray,
        header: StreamHeader,
        purpose: String,
        index: Long,
        last: Boolean,
        sealed: ByteBuffer,
        plaintext: ByteBuffer,
    ): Int {
        require(plaintext.isDirect && sealed.isDirect) { "A chunk is opened between direct buffers only" }
        require(!plaintext.isReadOnly) { "The plaintext of a chunk is written to" }
        val sealedLength = sealed.remaining()
        if (sealedLength < CHUNK_OVERHEAD) throw UndecryptableException("Not a sealed chunk")
        require(plaintext.remaining() >= sealedLength - NONCE_SIZE) { "No room to open a sealed chunk of $sealedLength bytes" }
        val nonce = ByteArray(NONCE_SIZE)
        sealed.get(nonce)
        val written = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_SIZE * 8, nonce))
            cipher.updateAAD(aad(header, purpose, index, last))
            cipher.doFinal(sealed, plaintext)
        } catch (e: GeneralSecurityException) {
            throw UndecryptableException("Decryption failed", e)
        }
        check(written == sealedLength - CHUNK_OVERHEAD) { "The cipher wrote $written bytes for a sealed chunk of $sealedLength" }
        return written
    }

    private fun putLong(target: ByteArray, offset: Int, value: Long) {
        for (byte in 0 until 8) target[offset + byte] = (value ushr (8 * (7 - byte))).toByte()
    }
}

/**
 * Overwrites this buffer with zeros from index [from] up to [to], whatever its position and
 * limit are, and leaves both as they were. For a buffer that held plaintext.
 */
fun ByteBuffer.wipe(from: Int = 0, to: Int = capacity()) {
    require(from in 0..to && to <= capacity()) { "Nothing to overwrite from $from to $to in ${capacity()} bytes" }
    val view = duplicate()
    view.clear()
    view.position(from)
    view.limit(to)
    while (view.hasRemaining()) view.put(ZEROS, 0, minOf(view.remaining(), ZEROS.size))
}

/** Never written to. */
private val ZEROS = ByteArray(8192)

/**
 * Seals and opens the chunks of sealed streams with the data key; see [SealedStream]. Beside
 * [BlobCipher], this is all a store ever sees of the key. Each call is one chunk, so the key is
 * never held for longer than one chunk takes, and once it has been destroyed no further chunk
 * of any stream is sealed or opened.
 *
 * A stream is not written or read through these three functions directly: [StreamSealer] and
 * [StreamOpener] count its chunks and hold it to its form.
 */
interface ChunkCipher {
    /**
     * The header of a new stream, with a stream id drawn afresh.
     *
     * @throws KeyDestroyedException once the app has locked: no stream is begun then.
     */
    @Throws(IOException::class)
    fun newStream(oldestMessageMillis: Long): StreamHeader

    /** Seals chunk [index] of the stream of [header] for [purpose]; see [SealedStream.sealChunk]. */
    @Throws(IOException::class)
    fun sealChunk(header: StreamHeader, purpose: String, index: Long, last: Boolean, plaintext: ByteBuffer, sealed: ByteBuffer)

    /**
     * Opens chunk [index] of the stream of [header] for [purpose] and returns the number of bytes
     * of plaintext; see [SealedStream.openChunk].
     *
     * @throws UndecryptableException for anything that is not that chunk, sealed with this key.
     */
    @Throws(IOException::class)
    fun openChunk(header: StreamHeader, purpose: String, index: Long, last: Boolean, sealed: ByteBuffer, plaintext: ByteBuffer): Int
}

/**
 * Seals the chunks of one new stream, in order. It draws the stream's header when it is made,
 * counts the chunks, and holds them to the form of [SealedStream]: every chunk full but the
 * last, the last not empty unless it is the only one, and nothing after the last.
 *
 * It does no input or output and owns no buffer. The plaintext stays in the caller's buffer
 * (for the memory of a model, the direct buffer that the native export fills) and is read from
 * there by the cipher; the caller overwrites that buffer when it is done with it.
 *
 * [seal] blocks, and one call of it excludes another.
 */
class StreamSealer internal constructor(
    private val cipher: ChunkCipher,
    private val purpose: String,
    oldestMessageMillis: Long,
    private val chunkSize: Int,
) {
    /**
     * @param purpose what the stream is sealed for; see [Purposes].
     * @param oldestMessageMillis the date for the header; see [StreamHeader.oldestMessageMillis].
     * @throws KeyDestroyedException once the app has locked.
     */
    @Throws(IOException::class)
    constructor(cipher: ChunkCipher, purpose: String, oldestMessageMillis: Long) : this(cipher, purpose, oldestMessageMillis, SealedStream.CHUNK_SIZE)

    /** The header of this stream: what its file begins with. */
    val header: StreamHeader = cipher.newStream(oldestMessageMillis)

    @Volatile private var index = 0L

    @Volatile private var finished = false
    private var broken = false

    /**
     * The number of chunks sealed so far. Read without waiting for a chunk that is being sealed,
     * so that whoever writes sealed chunks out is not held up by the sealing of the next.
     */
    val chunks: Long get() = index

    /** True once the last chunk has been sealed: the stream is complete. */
    val isFinished: Boolean get() = finished

    /**
     * Seals the next chunk: [plaintext] from its position to its limit, into [sealed] from its
     * position on. Both are left at the end of what was read and written, so [sealed] is flipped
     * before it is written out. The plaintext is only read.
     *
     * Both buffers must be direct, and separate allocations (see [SealedStream]). [sealed] needs
     * room for the plaintext and [SealedStream.CHUNK_OVERHEAD] bytes more.
     *
     * @param last true for the chunk that ends the stream, which is the only one that may hold
     *   fewer bytes than a full chunk.
     * @throws KeyDestroyedException once the app has locked. Nothing was sealed, and the stream
     *   can only be given up.
     * @throws IllegalArgumentException for a chunk of a size the stream cannot hold at this
     *   place, and for buffers as above. Nothing was sealed.
     * @throws IllegalStateException after the last chunk, and after a chunk that failed for
     *   another reason than those above.
     */
    @Synchronized
    @Throws(IOException::class)
    fun seal(plaintext: ByteBuffer, last: Boolean, sealed: ByteBuffer) {
        check(!finished) { "The last chunk of this stream has been sealed" }
        check(!broken) { "A chunk of this stream could not be sealed" }
        require(plaintext.isDirect && sealed.isDirect) { "A chunk is sealed between direct buffers only" }
        val length = plaintext.remaining()
        require(SealedStream.chunkFits(length, index, last, chunkSize)) {
            "A chunk of $length bytes cannot be ${if (last) "the last chunk" else "a chunk before the last"} at index $index"
        }
        require(sealed.remaining() >= length + SealedStream.CHUNK_OVERHEAD) { "No room for a sealed chunk of $length bytes" }
        try {
            cipher.sealChunk(header, purpose, index, last, plaintext, sealed)
        } catch (e: KeyDestroyedException) {
            throw e
        } catch (e: Exception) {
            broken = true
            throw e
        }
        index++
        finished = last
    }
}

/**
 * Opens the chunks of one stream, in order, and gives back nothing that has not authenticated.
 * It counts the chunks and holds them to the form of [SealedStream]; where the stream ends it
 * is told by the caller, who knows it from where the data ends, and the chunk's tag then proves
 * or refutes that.
 *
 * A chunk is decrypted into the caller's buffer before its tag can be compared. When a chunk
 * does not open, for whatever reason, the part of that buffer the cipher could write to is
 * overwritten with zeros before the failure is reported, and no later chunk is opened.
 *
 * [open] blocks, and one call of it excludes another.
 */
class StreamOpener internal constructor(
    private val cipher: ChunkCipher,
    private val purpose: String,
    private val header: StreamHeader,
    private val chunkSize: Int,
) {
    /**
     * @param purpose what the stream must have been sealed for; see [Purposes].
     * @param header the header the stream begins with, as [SealedStream.parseHeader] read it.
     */
    constructor(cipher: ChunkCipher, purpose: String, header: StreamHeader) : this(cipher, purpose, header, SealedStream.CHUNK_SIZE)

    private var index = 0L

    @Volatile private var finished = false
    private var failed = false

    /** True once the last chunk has opened: the stream is complete and nothing follows it. */
    val isFinished: Boolean get() = finished

    /**
     * Opens the next chunk: [sealed] from its position to its limit is the whole sealed chunk,
     * and its plaintext is written to [plaintext] from its position on. Both are left at the end
     * of what was read and written.
     *
     * Both buffers must be direct, and separate allocations (see [SealedStream]). [plaintext]
     * needs room for the sealed chunk less its nonce, which is 16 bytes more than the plaintext
     * (see [SealedStream.openChunk]).
     *
     * @param last true when the stream's data ends with this chunk.
     * @return the number of bytes of plaintext.
     * @throws UndecryptableException when the chunk is not the next chunk of this stream: it
     *   does not authenticate, has a size no chunk can have at this place, or follows one that
     *   failed. [plaintext] holds zeros from its position to its limit, and its position is
     *   unchanged.
     * @throws KeyDestroyedException once the app has locked; [plaintext] as above.
     * @throws IllegalArgumentException for buffers other than stated above.
     * @throws IllegalStateException after the last chunk.
     */
    @Synchronized
    @Throws(IOException::class)
    fun open(sealed: ByteBuffer, last: Boolean, plaintext: ByteBuffer): Int {
        check(!finished) { "The last chunk of this stream has been opened" }
        require(plaintext.isDirect && sealed.isDirect) { "A chunk is opened between direct buffers only" }
        require(!plaintext.isReadOnly) { "The plaintext of a chunk is written to" }
        val start = plaintext.position()
        var opened = false
        try {
            if (failed) throw UndecryptableException("An earlier chunk of this stream did not open")
            val length = sealed.remaining() - SealedStream.CHUNK_OVERHEAD
            if (!SealedStream.chunkFits(length, index, last, chunkSize)) {
                throw UndecryptableException("Not a chunk of a sealed stream: cut short, or not one")
            }
            val written = cipher.openChunk(header, purpose, index, last, sealed, plaintext)
            opened = true
            index++
            finished = last
            return written
        } catch (e: UndecryptableException) {
            failed = true
            throw e
        } finally {
            if (!opened) {
                plaintext.wipe(start, plaintext.limit())
                plaintext.position(start)
            }
        }
    }
}
