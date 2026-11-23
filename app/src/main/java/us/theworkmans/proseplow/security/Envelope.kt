package us.theworkmans.proseplow.security

import java.io.IOException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.read
import kotlin.concurrent.write

/** Stored data that cannot be decrypted: tampered with, truncated, damaged, or for another file. */
class UndecryptableException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The data key has been dropped (the app was locked); nothing can be read or written with it. */
class KeyDestroyedException : IOException("The app is locked")

/**
 * The 256-bit data key (DEK) that encrypts everything the app stores. Kept as a byte array so it
 * can be zeroed by [destroy]; the JCA keeps its own copies inside short-lived key objects, which
 * cannot be cleared and are left to the garbage collector.
 */
class DataKey(bytes: ByteArray) {
    init {
        require(bytes.size == SIZE) { "A data key has $SIZE bytes" }
    }

    private val material = bytes.copyOf()

    @Volatile private var destroyed = false

    /**
     * The key bytes. Callers must not keep or modify them.
     *
     * @throws KeyDestroyedException once the key has been destroyed. The array is all zeros
     *   then, and zeros must never be taken for a key: sealed with them, data would be readable
     *   by anyone, and wrapped by a new passphrase they would replace the only copy of the real
     *   key.
     */
    internal val bytes: ByteArray
        get() {
            if (destroyed) throw KeyDestroyedException()
            return material
        }

    /** True once [destroy] has been called; the key can be used for nothing then. */
    val isDestroyed: Boolean get() = destroyed

    fun destroy() {
        destroyed = true
        material.fill(0)
    }

    /** Never prints the key. */
    override fun toString() = "DataKey(***)"

    companion object {
        const val SIZE = 32

        fun generate(random: SecureRandom): DataKey {
            val bytes = ByteArray(SIZE)
            random.nextBytes(bytes)
            return try {
                DataKey(bytes)
            } finally {
                bytes.fill(0)
            }
        }
    }
}

/**
 * The format of every encrypted blob the app writes:
 *
 * ```
 * "PPE" | version (1 byte, 0x01) | nonce (12 bytes, random) | AES-256-GCM ciphertext | tag (16 bytes)
 * ```
 *
 * The additional authenticated data is the 4-byte header followed by the UTF-8 text
 * `proseplow:<purpose>`, where the purpose names what the blob is (for example `session:<id>`),
 * so a blob moved to another file or purpose fails to decrypt. The magic, the version byte and
 * the prefix are permanent: changing any of them makes everything stored before unreadable.
 *
 * A blob is one message, held in memory whole. Content too large for that has a second form,
 * [SealedStream], with a magic of its own.
 */
object Envelope {
    const val VERSION: Byte = 1
    private val MAGIC = byteArrayOf('P'.code.toByte(), 'P'.code.toByte(), 'E'.code.toByte())
    const val HEADER_SIZE = 4
    const val NONCE_SIZE = 12
    const val TAG_SIZE = 16
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val AAD_PREFIX = "proseplow:"

    /** True if [prefix] starts like an envelope (any version); a text or JSON file never does. */
    fun looksEncrypted(prefix: ByteArray): Boolean =
        prefix.size >= MAGIC.size && MAGIC.indices.all { prefix[it] == MAGIC[it] }

    fun seal(key: ByteArray, purpose: String, plaintext: ByteArray, random: SecureRandom): ByteArray {
        val header = MAGIC + VERSION
        val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_SIZE * 8, nonce))
        cipher.updateAAD(aad(header, purpose))
        return header + nonce + cipher.doFinal(plaintext)
    }

    /** @throws UndecryptableException if [blob] is not an envelope for [purpose] made with [key]. */
    fun open(key: ByteArray, purpose: String, blob: ByteArray): ByteArray {
        if (blob.size < HEADER_SIZE + NONCE_SIZE + TAG_SIZE || !looksEncrypted(blob)) {
            throw UndecryptableException("Not encrypted data")
        }
        if (blob[MAGIC.size] != VERSION) throw UndecryptableException("Unsupported format version ${blob[MAGIC.size]}")
        val header = blob.copyOfRange(0, HEADER_SIZE)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_SIZE * 8, blob, HEADER_SIZE, NONCE_SIZE),
            )
            cipher.updateAAD(aad(header, purpose))
            cipher.doFinal(blob, HEADER_SIZE + NONCE_SIZE, blob.size - HEADER_SIZE - NONCE_SIZE)
        } catch (e: GeneralSecurityException) {
            throw UndecryptableException("Decryption failed", e)
        }
    }

    private fun aad(header: ByteArray, purpose: String) = header + "$AAD_PREFIX$purpose".toByteArray(Charsets.UTF_8)
}

/**
 * Encrypts and decrypts the app's stored blobs; see [Envelope]. This is all a store ever sees of
 * the data key. The purpose is chosen by the caller and names what the blob is; see [Purposes].
 */
interface BlobCipher {
    @Throws(IOException::class)
    fun seal(purpose: String, plaintext: ByteArray): ByteArray

    /** @throws UndecryptableException for anything not sealed for [purpose] with this key. */
    @Throws(IOException::class)
    fun open(purpose: String, blob: ByteArray): ByteArray
}

/**
 * Both ciphers of an unlocked session in one object: what the lock hands every session it opens.
 * A store sees only the half it needs.
 */
interface DataCipher : BlobCipher, ChunkCipher

/**
 * The [BlobCipher] and the [ChunkCipher] of an unlocked session, using the data key. [destroy]
 * waits for operations in progress, zeroes the key, and makes every later operation fail with
 * [KeyDestroyedException], so nothing can be written with a cleared (all-zero) key.
 *
 * An operation is one blob or one chunk of a stream, never a stream: [destroy] waits for the
 * chunk that is being sealed or opened, which is then complete, and the stream it belongs to
 * gets no further chunk.
 */
class DataCrypto(private val key: DataKey, private val random: SecureRandom = SecureRandom()) : DataCipher {
    private val lock = ReentrantReadWriteLock()
    private var destroyed = false

    override fun seal(purpose: String, plaintext: ByteArray): ByteArray = lock.read {
        if (destroyed) throw KeyDestroyedException()
        Envelope.seal(key.bytes, purpose, plaintext, random)
    }

    override fun open(purpose: String, blob: ByteArray): ByteArray = lock.read {
        if (destroyed) throw KeyDestroyedException()
        Envelope.open(key.bytes, purpose, blob)
    }

    override fun newStream(oldestMessageMillis: Long): StreamHeader = lock.read {
        if (destroyed) throw KeyDestroyedException()
        SealedStream.newHeader(oldestMessageMillis, random)
    }

    override fun sealChunk(header: StreamHeader, purpose: String, index: Long, last: Boolean, plaintext: ByteBuffer, sealed: ByteBuffer) = lock.read {
        if (destroyed) throw KeyDestroyedException()
        SealedStream.sealChunk(key.bytes, header, purpose, index, last, plaintext, sealed, random)
    }

    override fun openChunk(header: StreamHeader, purpose: String, index: Long, last: Boolean, sealed: ByteBuffer, plaintext: ByteBuffer): Int = lock.read {
        if (destroyed) throw KeyDestroyedException()
        SealedStream.openChunk(key.bytes, header, purpose, index, last, sealed, plaintext)
    }

    fun destroy() = lock.write {
        destroyed = true
        key.destroy()
    }
}
