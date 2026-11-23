package us.theworkmans.proseplow.security

import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.Base64

/**
 * The lock configuration, stored in plain JSON (`files/security/lock.json`) because it must be
 * readable before the app is unlocked. It holds no secret in the clear: the data key only appears
 * wrapped (by the passphrase key and, optionally, the biometric key), and the salt and iteration
 * count are not secret.
 *
 * The lock is mandatory, and the schema says so: [passphrase] is not optional, so a configuration
 * without a passphrase wrap cannot be read at all, and there is no field that turns the lock off.
 * A configuration only exists once a passphrase has been set up. [isValid] checks every parameter
 * before anything is derived from them.
 */
@Serializable
data class LockConfig(
    @Required val version: Int = CURRENT_VERSION,
    val passphrase: PassphraseWrap,
    val biometric: BiometricWrap? = null,
    /** How long the app may stay in the background before it locks (0: at once). */
    val lockTimeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    /** Passphrase attempts that have not (yet) succeeded since the last success. */
    val failedAttempts: Int = 0,
    /**
     * When the current wait started (the last failure, or the first start after a reboot during a
     * wait), by the wall clock. A record only: the wait is timed by [lastFailureElapsedMillis],
     * because the wall clock can be set by anyone holding the phone (see [Backoff]).
     */
    val lastFailureWallMillis: Long = 0,
    /** The same moment on the clock that counts since boot ([Clock.elapsedMillis]). */
    val lastFailureElapsedMillis: Long = 0,
    /** Fingerprint unlock was removed because its key was invalidated; offer to set it up again. */
    val fingerprintInvalidated: Boolean = false,
    /** The highest binding or biometric key generation committed so far; generations are never reused. */
    val highestKeyGeneration: Int,
) {
    /**
     * Every field is in range and every wrap well formed, so nothing read from the file can make
     * the key derivation fail, hang or run with weakened parameters. Anything else is treated as a
     * damaged configuration ("cannot be unlocked"). [LockManager] also refuses to write a
     * configuration that fails this, so it cannot lock the user out by its own hand.
     */
    fun isValid(minIterations: Int = PassphraseWrap.MIN_ITERATIONS): Boolean {
        if (version != CURRENT_VERSION) return false
        if (lockTimeoutSeconds !in TIMEOUT_CHOICES_SECONDS) return false
        if (failedAttempts < 0 || lastFailureWallMillis < 0 || lastFailureElapsedMillis < 0) return false
        if (!passphrase.isValid(minIterations)) return false
        if (biometric != null && !biometric.isValid()) return false
        // Generations are never reused, so the highest one committed is at least every one in use.
        if (!isKeyGeneration(highestKeyGeneration)) return false
        return passphrase.bindingGeneration <= highestKeyGeneration && (biometric == null || biometric.keyGeneration <= highestKeyGeneration)
    }

    /**
     * The keys this configuration needs; every other key of the app is left over and can go.
     * Only for a configuration that [isValid].
     */
    val referencedKeys: Set<KeyId>
        get() = setOfNotNull(KeyId(KeyKind.BINDING, passphrase.bindingGeneration), biometric?.let { KeyId(KeyKind.BIOMETRIC, it.keyGeneration) })

    companion object {
        const val CURRENT_VERSION = 1
        const val DEFAULT_TIMEOUT_SECONDS = 60
        val TIMEOUT_CHOICES_SECONDS = listOf(0, 60, 5 * 60, 15 * 60)

        /** `iv (12) || ciphertext (32) || tag (16)` of the data key from a Keystore AES-GCM key. */
        const val KEY_WRAP_SIZE = 12 + DataKey.SIZE + 16

        /** A stored key generation: one that [KeyId] accepts (and whose alias it can read back). */
        internal fun isKeyGeneration(value: Int) = value in 1..KeyId.MAX_GENERATION

        /** The decoded length of Base64 [text], or -1 if it is not Base64. */
        internal fun decodedSize(text: String): Int = try {
            text.unbase64().size
        } catch (_: IllegalArgumentException) {
            -1
        }
    }
}

@Serializable
data class PassphraseWrap(
    @Required val kdf: String = KDF_NAME,
    /** Base64, 16 random bytes. */
    val salt: String,
    val iterations: Int,
    /** Base64 of an [Envelope] (purpose `dek:passphrase`) sealed with the passphrase key. */
    val wrappedKey: String,
    /** The binding key this wrap was made with (see [KeyId]). */
    val bindingGeneration: Int,
) {
    fun isValid(minIterations: Int): Boolean =
        kdf == KDF_NAME &&
            iterations in minIterations..MAX_ITERATIONS &&
            LockConfig.isKeyGeneration(bindingGeneration) &&
            LockConfig.decodedSize(salt).let { it in MIN_SALT_SIZE..MAX_SALT_SIZE } &&
            wrappedKey.decodedOrNull()?.let { it.size == WRAPPED_SIZE && Envelope.looksEncrypted(it) } == true

    companion object {
        const val KDF_NAME = "PBKDF2-HMAC-SHA256+keystore-HMAC-SHA256"
        const val MIN_ITERATIONS = 600_000

        /** About 15-30 s on a phone: more is not a setting this app ever writes. */
        const val MAX_ITERATIONS = 10_000_000
        const val MIN_SALT_SIZE = 16
        const val MAX_SALT_SIZE = 64

        /** The envelope of the 32-byte data key: header, nonce, ciphertext, tag. */
        const val WRAPPED_SIZE = Envelope.HEADER_SIZE + Envelope.NONCE_SIZE + DataKey.SIZE + Envelope.TAG_SIZE

        private fun String.decodedOrNull(): ByteArray? = try {
            unbase64()
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/** Base64 of `iv || ciphertext` from the biometric key of [keyGeneration]. */
@Serializable
data class BiometricWrap(val wrappedKey: String, val keyGeneration: Int) {
    fun isValid(): Boolean = LockConfig.isKeyGeneration(keyGeneration) && LockConfig.decodedSize(wrappedKey) == LockConfig.KEY_WRAP_SIZE
}

class LockConfigUnreadableException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Reads and atomically writes [file]. Blocking. [onWriteStep] and [directorySync] are passed on
 * to [writeAtomically]; the unit tests use them to stop a write at a step or to fail its
 * directory sync.
 */
class LockConfigStore(
    private val file: File,
    private val onWriteStep: (WriteStep) -> Unit = {},
    private val directorySync: (File) -> Boolean = ::syncDirectory,
) {
    private val json = Json {
        // Unknown fields (from a newer version) are ignored rather than making the data unrecoverable.
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Null if there is none. @throws LockConfigUnreadableException if it exists but cannot be read. */
    fun read(): LockConfig? {
        if (!file.exists()) return null
        return try {
            json.decodeFromString(LockConfig.serializer(), file.readText())
        } catch (e: SerializationException) {
            throw LockConfigUnreadableException("The lock configuration cannot be read", e)
        } catch (e: IllegalArgumentException) {
            throw LockConfigUnreadableException("The lock configuration cannot be read", e)
        }
    }

    /**
     * Replaces the stored configuration. Returns true once [config] is durable (it is what any
     * restart finds, even after a power loss); false if it is in place but a power loss may
     * still bring its predecessor back. See [writeAtomically].
     * @throws IOException if the stored configuration was not replaced.
     */
    @Throws(IOException::class)
    fun write(config: LockConfig): Boolean =
        writeAtomically(file, json.encodeToString(LockConfig.serializer(), config).toByteArray(), onWriteStep, directorySync)

    /**
     * Makes the configuration that is in place durable, with the directories that name it (the
     * security directory and the files directory); false if that could not be done. An earlier
     * run may have stopped between renaming it into place and syncing the directory.
     */
    fun sync(): Boolean = syncExisting(file, levels = 2, syncDirectory = directorySync)

    @Throws(IOException::class)
    fun delete() {
        deleteOrThrow(tempFileFor(file))
        deleteOrThrow(file)
        file.absoluteFile.parentFile?.let(directorySync)
    }
}

internal fun ByteArray.base64(): String = Base64.getEncoder().encodeToString(this)

internal fun String.unbase64(): ByteArray = Base64.getDecoder().decode(this)

/** Wall-clock and monotonic (since boot) time. */
interface Clock {
    fun wallMillis(): Long

    fun elapsedMillis(): Long
}

/**
 * The delay after failed passphrase attempts: the first [FREE_ATTEMPTS] failures cost nothing,
 * then 30 s, doubling per failure up to one hour. There is no wipe after N failures.
 *
 * The wait is timed with the time since boot and with nothing else. Within one boot that clock
 * runs exactly as fast as real time, asleep or awake, so the wait ends one delay after the
 * failure. A reboot restarts the clock: when the time since boot is below the stored start of
 * the wait, the phone was restarted during the wait, and [reanchor] starts the full delay again
 * from that moment (the caller stores it). When it is not below it (the phone has since been up
 * for longer than it had been at the failure), the stored start stays, and what is left of the
 * delay on the new clock applies. In both cases the time the clock shows as passed is never
 * more than the real time since the failure (a reboot only adds time the clock did not count),
 * so the wait is never shortened; and from whatever moment the app looks at it, it never lasts
 * longer than one full delay. A restart of the phone is the only thing that can put time back
 * onto it.
 *
 * The wall clock is deliberately not consulted. It cannot make the wait any safer, since the
 * clock since boot already never runs ahead of real time; and it can be set by anyone, or
 * corrected by the network, in either direction. A wait that followed it would be cut short by
 * setting the date ahead, or, worse in an app without any recovery, held for as long as the date
 * was set back: the right passphrase refused for days because a clock was corrected.
 */
object Backoff {
    const val FREE_ATTEMPTS = 4
    private const val FIRST_DELAY_MILLIS = 30_000L
    const val MAX_DELAY_MILLIS = 60 * 60_000L

    fun delayAfter(failures: Int): Long {
        if (failures <= FREE_ATTEMPTS) return 0
        val doublings = (failures - FREE_ATTEMPTS - 1).coerceAtMost(8)
        return (FIRST_DELAY_MILLIS shl doublings).coerceAtMost(MAX_DELAY_MILLIS)
    }

    /**
     * A configuration whose wait starts again now, if the phone was restarted during a wait that
     * is not over yet; null if nothing needs to change.
     */
    fun reanchor(config: LockConfig, clock: Clock): LockConfig? {
        if (delayAfter(config.failedAttempts) == 0L) return null
        val now = clock.elapsedMillis()
        if (now >= config.lastFailureElapsedMillis) return null
        return config.copy(lastFailureWallMillis = clock.wallMillis().coerceAtLeast(0), lastFailureElapsedMillis = now.coerceAtLeast(0))
    }

    /** Milliseconds until the next attempt is allowed (the full delay after an unhandled reboot). */
    fun remaining(config: LockConfig, clock: Clock): Long {
        val delay = delayAfter(config.failedAttempts)
        if (delay == 0L) return 0
        val now = clock.elapsedMillis()
        if (now < config.lastFailureElapsedMillis) return delay // A new boot not yet re-anchored.
        return (config.lastFailureElapsedMillis + delay - now).coerceIn(0, delay)
    }
}
