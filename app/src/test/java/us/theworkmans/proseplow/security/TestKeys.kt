package us.theworkmans.proseplow.security

import java.nio.ByteBuffer
import java.security.KeyStoreException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object TestKeys {
    private val random = SecureRandom()

    /** A session cipher with a fresh random data key. */
    fun cipher(): DataCrypto = DataCrypto(DataKey.generate(random))
}

/**
 * Thrown by the test hooks to stand for the process being killed, or the phone losing power, at
 * that point. An Error, not an Exception, on purpose: the code under test cleans up after
 * exceptions (`catch (e: Exception)`), and a process that is killed runs no clean-up at all.
 */
class SimulatedCrash : Error("process killed")

/**
 * [KeyVault] with software keys, standing in for the Android Keystore. Its keys can be lost or
 * the biometric key invalidated, as the platform would do. "Authenticating" a biometric cipher
 * is a no-op here: the prompt cannot be exercised on the JVM.
 */
class SoftwareKeyVault : KeyVault {
    /**
     * Keyed by alias string, exactly like the Android Keystore: [keys] reads the aliases back
     * through [KeyId.parse], as AndroidKeyVault does, so a key whose alias cannot be parsed is
     * invisible to the clean-up here too.
     */
    val aliases = LinkedHashMap<String, SecretKey>()

    /** The keys by id (a view through the alias format). */
    val entries: Map<KeyId, SecretKey> get() = aliases.mapNotNull { (alias, key) -> KeyId.parse(alias)?.let { it to key } }.toMap()

    fun putKey(id: KeyId, key: SecretKey) {
        aliases[id.alias] = key
    }

    fun removeKey(id: KeyId): SecretKey? = aliases.remove(id.alias)

    /** Generations of biometric keys the "platform" has invalidated. */
    val invalidatedBiometric = HashSet<Int>()

    /** Called at the start of every operation; the lock tests assert here that calls are serialised. */
    var onUse: () -> Unit = {}

    /** Calls of [bind]; tests use it to make the derivation fail midway. */
    var onBind: (generation: Int) -> Unit = {}

    /** Called after a key was created; throwing stands for the process stopping right after. */
    var afterCreate: (KeyId) -> Unit = {}

    /** Called after a key was deleted; throwing stands for the process stopping right after. */
    var afterDelete: (KeyId) -> Unit = {}

    /** Keys whose deletion fails. */
    val undeletable = HashSet<KeyId>()

    val bindingKeys: List<KeyId> get() = entries.keys.filter { it.kind == KeyKind.BINDING }
    val biometricKeys: List<KeyId> get() = entries.keys.filter { it.kind == KeyKind.BIOMETRIC }

    /** The newest binding / biometric key; null only if there is none at all. */
    val binding: SecretKey? get() = bindingKeys.maxByOrNull { it.generation }?.let(entries::get)
    val biometric: SecretKey? get() = biometricKeys.maxByOrNull { it.generation }?.let(entries::get)

    /** Invalidates every biometric key there is now (e.g. a new fingerprint was enrolled). */
    var biometricInvalidated: Boolean
        get() = biometricKeys.any { it.generation in invalidatedBiometric }
        set(value) {
            if (value) biometricKeys.forEach { invalidatedBiometric += it.generation } else invalidatedBiometric.clear()
        }

    private fun aes(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun put(id: KeyId, key: SecretKey) {
        onUse()
        aliases[id.alias] = key
        afterCreate(id)
    }

    private fun key(id: KeyId): SecretKey = aliases[id.alias] ?: throw KeyUnavailableException("no key ${id.alias}")

    override fun createBindingKey(generation: Int) = put(KeyId(KeyKind.BINDING, generation), KeyGenerator.getInstance("HmacSHA256").generateKey())

    override fun bind(generation: Int, input: ByteArray): ByteArray {
        onUse()
        onBind(generation)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key(KeyId(KeyKind.BINDING, generation)))
        return mac.doFinal(input)
    }

    override fun createBiometricKey(generation: Int) {
        invalidatedBiometric -= generation
        put(KeyId(KeyKind.BIOMETRIC, generation), aes())
    }

    private fun biometricKey(generation: Int): SecretKey {
        onUse()
        if (generation in invalidatedBiometric) throw KeyUnavailableException("invalidated", permanentlyInvalidated = true)
        return key(KeyId(KeyKind.BIOMETRIC, generation))
    }

    override fun biometricEncryptCipher(generation: Int): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, biometricKey(generation)) }

    override fun biometricDecryptCipher(generation: Int, iv: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, biometricKey(generation), GCMParameterSpec(128, iv)) }

    /** As AndroidKeyVault: every alias read back through [KeyId.parse]. */
    override fun keys(): Set<KeyId> {
        onUse()
        return aliases.keys.mapNotNullTo(HashSet()) { KeyId.parse(it) }
    }

    override fun contains(key: KeyId): Boolean {
        onUse()
        return key.alias in aliases
    }

    override fun delete(key: KeyId) {
        onUse()
        if (key in undeletable) throw KeyStoreException("cannot delete ${key.alias}")
        if (aliases.remove(key.alias) != null) afterDelete(key)
    }

    /** As AndroidKeyVault: every alias with the app's prefix, parsed or not. */
    override fun deleteAll() {
        onUse()
        var failed = false
        for (alias in aliases.keys.toList()) {
            if (!alias.startsWith(KeyId.ALIAS_PREFIX)) continue
            try {
                val id = KeyId.parse(alias)
                if (id != null) delete(id) else aliases.remove(alias)
            } catch (_: Exception) {
                failed = true
            }
        }
        if (failed) throw KeyDeletionException("some keys could not be deleted")
    }
}

class FakeClock(var wall: Long = 1_700_000_000_000L, var elapsed: Long = 50_000L) : Clock {
    /** Called at every reading; the lock tests assert here that calls are serialised. */
    var onUse: () -> Unit = {}

    override fun wallMillis(): Long {
        onUse()
        return wall
    }

    override fun elapsedMillis(): Long {
        onUse()
        return elapsed
    }

    /** The system's count of boots; null stands for one that cannot be read. */
    var boots: Int? = 1

    /** Thrown by [bootCount] instead of answering, when set: reading the count failed. */
    var bootCountFailure: RuntimeException? = null

    override fun bootCount(): Int? {
        onUse()
        bootCountFailure?.let { throw it }
        return boots
    }

    fun advance(millis: Long) {
        wall += millis
        elapsed += millis
    }

    /** The phone restarts after [downMillis] switched off: the time since boot starts again, and the boot count goes up. */
    fun reboot(downMillis: Long = 20_000L, upMillis: Long = 5_000L) {
        wall += downMillis + upMillis
        elapsed = upMillis
        boots = boots?.plus(1)
    }
}

/**
 * [ScreenOffWatch] for the unit tests, in place of the phone's broadcast: it records whether
 * something is watching, and [screenTurnsOff] is the power button.
 */
class FakeScreenOffWatch : ScreenOffWatch {
    private var listener: (() -> Unit)? = null

    /** Whether a listener is registered right now; on the phone, whether the receiver is. */
    val watching: Boolean get() = listener != null
    var starts = 0
    var stops = 0

    /** Makes [start] fail, as a system that refuses the registration would. */
    var refuses = false

    override fun start(onScreenOff: () -> Unit) {
        if (refuses) throw IllegalStateException("the registration was refused")
        if (listener == null) starts++
        listener = onScreenOff
    }

    override fun stop() {
        if (listener != null) stops++
        listener = null
    }

    fun screenTurnsOff() {
        listener?.invoke()
    }
}

/** The key of a fingerprint unlock that succeeded; null for every other outcome. */
fun BiometricUnlockResult.keyOrNull(): DataKey? = (this as? BiometricUnlockResult.Unlocked)?.key

/** A configuration that validates, for tests that only care about some of its fields. */
fun testConfig(
    failedAttempts: Int = 0,
    lastFailureWallMillis: Long = 0,
    lastFailureElapsedMillis: Long = 0,
    bindingGeneration: Int = 1,
    iterations: Int = PassphraseWrap.MIN_ITERATIONS,
): LockConfig = LockConfig(
    passphrase = PassphraseWrap(
        salt = ByteArray(LockManager.SALT_SIZE) { 7 }.base64(),
        iterations = iterations,
        wrappedKey = Envelope.seal(ByteArray(DataKey.SIZE) { 1 }, LockManager.PASSPHRASE_PURPOSE, ByteArray(DataKey.SIZE) { 2 }, SecureRandom()).base64(),
        bindingGeneration = bindingGeneration,
    ),
    failedAttempts = failedAttempts,
    lastFailureWallMillis = lastFailureWallMillis,
    lastFailureElapsedMillis = lastFailureElapsedMillis,
    highestKeyGeneration = bindingGeneration,
)

/**
 * Randomness that was written down in advance, for the tests that pin the exact bytes of what is
 * stored: every draw returns the next of [draws], and a draw that was not written down, or of
 * another length, fails the test.
 */
class ScriptedRandom(vararg draws: ByteArray) : SecureRandom() {
    private val draws = ArrayDeque(draws.toList())

    /** How many of the draws have not been made. */
    val left: Int
        @Synchronized get() = draws.size

    @Synchronized
    override fun nextBytes(bytes: ByteArray) {
        val next = draws.removeFirstOrNull() ?: throw AssertionError("a draw of ${bytes.size} bytes that was not written down")
        if (next.size != bytes.size) throw AssertionError("a draw of ${bytes.size} bytes where one of ${next.size} was written down")
        next.copyInto(bytes)
    }
}

/** A direct buffer that holds [bytes] and is ready to be read from its start. */
fun directBuffer(bytes: ByteArray): ByteBuffer {
    val buffer = ByteBuffer.allocateDirect(bytes.size)
    buffer.put(bytes)
    buffer.flip()
    return buffer
}

/** The bytes of this buffer from its position to its limit; the buffer is left as it is. */
fun ByteBuffer.contents(): ByteArray {
    val bytes = ByteArray(remaining())
    duplicate().get(bytes)
    return bytes
}

/** Every byte this buffer has room for, whatever its position and limit; the buffer is left as it is. */
fun ByteBuffer.everything(): ByteArray {
    val view = duplicate()
    view.clear()
    return view.contents()
}
