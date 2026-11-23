package us.theworkmans.proseplow.security

import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher

/** Why the data key cannot be recovered; the only way on is the reset. */
enum class UnrecoverableReason {
    /** The lock configuration exists but cannot be read, or holds values out of range. */
    CONFIG_UNREADABLE,

    /** Encrypted data exists but its lock configuration is gone. */
    CONFIG_MISSING,

    /** A key the configuration needs is gone from the key store, or the wrapped key is damaged. */
    KEY_LOST,
}

sealed interface StartResult {
    /** Nothing has been set up and nothing is stored: a passphrase must be created first ([LockManager.setUp]). */
    data object SetupRequired : StartResult

    /** A passphrase or fingerprint is needed. */
    data object Locked : StartResult

    data class Unrecoverable(val reason: UnrecoverableReason) : StartResult
}

/**
 * Proof that the passphrase was just entered correctly. Changing the passphrase and turning
 * fingerprint unlock on or off need one; a fingerprint unlock never gives one. Valid for
 * [LockManager.PROOF_VALIDITY_MILLIS].
 */
class PassphraseProof internal constructor(internal val atElapsedMillis: Long)

class ProofExpiredException : IllegalStateException("Enter the passphrase again")

sealed interface PassphraseResult {
    class Success(val key: DataKey, val proof: PassphraseProof) : PassphraseResult

    /** Wrong passphrase; [retryInMillis] is the delay before the next attempt (0 if none). */
    data class Wrong(val retryInMillis: Long) : PassphraseResult

    /** Refused without checking: too many recent failures. */
    data class Throttled(val retryInMillis: Long) : PassphraseResult

    /** Refused without checking or counting: empty, or longer than any passphrase can be. */
    data class Rejected(val problem: PassphraseProblem) : PassphraseResult

    data class Unrecoverable(val reason: UnrecoverableReason) : PassphraseResult
}

sealed interface BiometricCipherResult {
    class Ready(val cipher: Cipher) : BiometricCipherResult

    data object NotEnabled : BiometricCipherResult

    /** The key was invalidated: fingerprint unlock has been removed (the passphrase still works). */
    data object Invalidated : BiometricCipherResult
}

/** What a reset deleted. Anything false is still there and must be told to the user. */
data class ResetReport(val keysDeleted: Boolean, val dataDeleted: Boolean, val configDeleted: Boolean) {
    val complete: Boolean get() = keysDeleted && dataDeleted && configDeleted
}

/**
 * The app lock: where the data key comes from and how it is wrapped. Plain Kotlin over a
 * [KeyVault], a [LockConfigStore] and a [Clock], so all of it runs in JVM unit tests. Blocking;
 * not thread-safe on its own (the caller serialises calls).
 *
 * The lock is mandatory. There is no data key, no Keystore key and no configuration until
 * [setUp] is given a passphrase, and from then on the data key (DEK) only exists wrapped:
 * - by the passphrase key, always. The passphrase key is
 *   `HMAC-SHA256(bindingKey, LABEL || PBKDF2-HMAC-SHA256(passphrase, salt, iterations))`: the
 *   binding key never leaves the key store, so guesses can only be tried on this device;
 * - optionally by the biometric key as well.
 * Nothing here can turn the lock off or wrap the key under anything that opens without the user.
 *
 * Keys are replaced crash-safely: a new binding or biometric key is made under a new generation
 * (alias), the configuration that refers to it is written and made durable (file synced, renamed
 * into place, directory synced), and only then is the old key deleted. A stop at any point, a
 * power loss included, leaves the configuration on disk referring to a key that exists. Keys
 * nothing refers to are deleted at every [start], once the configuration found there is itself
 * durable. Since every new passphrase makes a new binding key and deletes the old one, an older
 * copy of the configuration (with an older passphrase) no longer opens.
 */
class LockManager(
    private val store: LockConfigStore,
    private val vault: KeyVault,
    private val clock: Clock,
    private val random: SecureRandom = SecureRandom(),
    private val iterations: Int = DEFAULT_ITERATIONS,
    /** The lowest iteration count accepted from the configuration (lower only in unit tests). */
    private val minIterations: Int = PassphraseWrap.MIN_ITERATIONS,
    /** True if encrypted app data exists (see [AppFiles.encryptedDataExists]). */
    private val encryptedDataExists: () -> Boolean,
) {
    /**
     * The current configuration: null until a passphrase has been set up, and whenever [start]
     * did not return [StartResult.Locked].
     */
    var config: LockConfig? = null
        private set

    /** The biometric key generation made by [prepareBiometricEnrollment] and not yet committed. */
    private var pendingBiometric: Int? = null

    /**
     * Whether [config] is known to be what a restart finds even after a power loss. While it is
     * not, the configuration before it may still come back, so no key is deleted: see
     * [deleteUnreferencedKeys].
     */
    private var configDurable = false

    init {
        require(iterations in 1..PassphraseWrap.MAX_ITERATIONS)
    }

    /**
     * Reads the stored configuration and says where the app stands. Never creates a key.
     * @throws IOException if the files could not be looked at (which says nothing about them).
     */
    @Throws(IOException::class)
    fun start(): StartResult {
        pendingBiometric = null
        config = null
        configDurable = false
        val stored = try {
            store.read()
        } catch (_: LockConfigUnreadableException) {
            return StartResult.Unrecoverable(UnrecoverableReason.CONFIG_UNREADABLE)
        }
        if (stored == null) {
            // Never set up over encrypted data: a new key would make that data unreadable for good.
            if (encryptedDataExists()) return StartResult.Unrecoverable(UnrecoverableReason.CONFIG_MISSING)
            // Keys of a set-up that stopped before its configuration was durable, or that a reset
            // could not delete, protect nothing. Tried again by setUp and at every later start.
            ignoreFailure { vault.deleteAll() }
            return StartResult.SetupRequired
        }
        if (!stored.isValid(minIterations)) return StartResult.Unrecoverable(UnrecoverableReason.CONFIG_UNREADABLE)
        // Nothing is deleted while the key the configuration needs is missing: that configuration
        // may be an old or foreign copy, and the keys of the real one must survive it.
        if (!vault.contains(KeyId(KeyKind.BINDING, stored.passphrase.bindingGeneration))) {
            return StartResult.Unrecoverable(UnrecoverableReason.KEY_LOST)
        }
        config = stored
        // The run that wrote this configuration may have stopped between renaming it into place
        // and syncing the directory. It is made durable now, before the keys it no longer refers
        // to are deleted (left over from that stop, or from a reset that could not delete them).
        configDurable = store.sync()
        deleteUnreferencedKeys()
        backoffRemainingMillis()
        return StartResult.Locked
    }

    /**
     * First run: makes the data key and stores it wrapped by [passphrase] (not cleared here: the
     * caller owns it), under a new binding key. Before this nothing is persisted, and it is the
     * only place a data key is ever made. It returns only once the configuration is durable, so
     * no data can be encrypted with a key whose wrap a power loss could still take away.
     *
     * Returns the key and a proof (the passphrase was just set), so fingerprint unlock can be
     * offered at once.
     * @throws IllegalStateException if a configuration or encrypted data exists (see [start]).
     */
    @Throws(IOException::class, GeneralSecurityException::class)
    fun setUp(passphrase: CharArray): PassphraseResult.Success = withNfc(passphrase) { text ->
        check(config == null) { "A passphrase is already set" }
        require(PassphraseRules.check(text) == null) { "The passphrase does not meet the rules" }
        // Looked at again, not only at start: never make a key over a configuration or data that is there.
        check(store.read() == null && !encryptedDataExists()) { "The app is already set up" }
        ignoreFailure { vault.deleteAll() }
        val binding = newBindingKey()
        val key = DataKey.generate(random)
        try {
            commitOrDelete(binding) {
                val durable = save(LockConfig(passphrase = passphraseWrap(key, text, binding.generation), highestKeyGeneration = binding.generation))
                if (!durable) {
                    config = null
                    ignoreFailure { store.delete() }
                    throw IOException("The lock configuration could not be made durable")
                }
            }
        } catch (e: Exception) {
            key.destroy()
            throw e
        }
        PassphraseResult.Success(key, PassphraseProof(clock.elapsedMillis()))
    }

    /**
     * Milliseconds until the next passphrase attempt is allowed. If the phone was restarted
     * during the wait, the full wait starts again now and that is stored (see [Backoff]).
     */
    fun backoffRemainingMillis(): Long {
        val current = config ?: return 0
        val reanchored = Backoff.reanchor(current, clock) ?: return Backoff.remaining(current, clock)
        try {
            save(reanchored)
        } catch (_: IOException) {
            config = reanchored // Still applies to this run.
        }
        return Backoff.remaining(reanchored, clock)
    }

    /**
     * Checks [passphrase] (not cleared here: the caller owns it). The attempt is counted before
     * the check runs, so stopping the app mid-check does not avoid the back-off. Input that cannot
     * be a passphrase (empty, too long) is refused before that and not counted.
     */
    @Throws(IOException::class)
    fun checkPassphrase(passphrase: CharArray): PassphraseResult = withNfc(passphrase) { text -> checkNormalised(text) }

    private fun checkNormalised(text: CharArray): PassphraseResult {
        PassphraseRules.checkEntered(text)?.let { return PassphraseResult.Rejected(it) }
        checkNotNull(config) { "No passphrase has been set up" }
        val wait = backoffRemainingMillis()
        if (wait > 0) return PassphraseResult.Throttled(wait)
        // Read after the back-off was looked at: that may have stored a new start of the wait.
        val current = checkNotNull(config)
        val wrap = current.passphrase
        save(
            current.copy(
                failedAttempts = current.failedAttempts.let { if (it < Int.MAX_VALUE) it + 1 else it },
                // Clamped: a clock before 1970 must not produce a configuration that no longer validates.
                lastFailureWallMillis = clock.wallMillis().coerceAtLeast(0),
                lastFailureElapsedMillis = clock.elapsedMillis().coerceAtLeast(0),
            ),
        )
        val kek = try {
            passphraseKey(text, wrap.salt.unbase64(), wrap.iterations, wrap.bindingGeneration)
        } catch (_: KeyUnavailableException) {
            return PassphraseResult.Unrecoverable(UnrecoverableReason.KEY_LOST)
        }
        val dek = try {
            Envelope.open(kek, PASSPHRASE_PURPOSE, wrap.wrappedKey.unbase64())
        } catch (_: UndecryptableException) {
            return PassphraseResult.Wrong(backoffRemainingMillis())
        } finally {
            kek.fill(0)
        }
        try {
            save(checkNotNull(config).copy(failedAttempts = 0, lastFailureWallMillis = 0, lastFailureElapsedMillis = 0))
            return PassphraseResult.Success(DataKey(dek), PassphraseProof(clock.elapsedMillis()))
        } finally {
            dek.fill(0)
        }
    }

    /**
     * Replaces the passphrase wrap, under a new binding key: the new key is made first, the
     * configuration referring to it is written and made durable, and only then is the old key
     * deleted, so a stop at any point leaves either the old or the new passphrase working. Once
     * the old binding key is gone, an older copy of the configuration no longer opens.
     */
    @Throws(IOException::class, GeneralSecurityException::class)
    fun changePassphrase(proof: PassphraseProof, key: DataKey, newPassphrase: CharArray) {
        requireFresh(proof)
        val current = checkNotNull(config) { "No passphrase has been set up" }
        // Before any key is made: a destroyed key is all zeros, and wrapping those would replace
        // the only wrap of the real key with one of nothing.
        if (key.isDestroyed) throw KeyDestroyedException()
        withNfc(newPassphrase) { text ->
            require(PassphraseRules.check(text) == null) { "The passphrase does not meet the rules" }
            val binding = newBindingKey()
            commitOrDelete(binding) {
                save(
                    current.copy(
                        passphrase = passphraseWrap(key, text, binding.generation),
                        highestKeyGeneration = maxOf(current.highestKeyGeneration, binding.generation),
                    ),
                )
            }
        }
        deleteUnreferencedKeys()
    }

    /**
     * A new biometric key (under a new generation, so a fingerprint unlock already set up keeps
     * working until the new one is committed) and a cipher to authorise with a biometric prompt;
     * see [completeBiometricEnrollment] and [cancelBiometricEnrollment].
     */
    @Throws(GeneralSecurityException::class)
    fun prepareBiometricEnrollment(proof: PassphraseProof): Cipher {
        requireFresh(proof)
        checkNotNull(config) { "No passphrase has been set up" }
        val generation = nextGeneration(KeyKind.BIOMETRIC)
        cancelBiometricEnrollment()
        vault.createBiometricKey(generation)
        pendingBiometric = generation
        return try {
            vault.biometricEncryptCipher(generation)
        } catch (e: Exception) {
            cancelBiometricEnrollment()
            throw e
        }
    }

    /**
     * Wraps [key] with [cipher], which a biometric prompt has authorised, and makes it the
     * fingerprint unlock; the key of the one it replaces is deleted after that is durable.
     */
    @Throws(IOException::class, GeneralSecurityException::class)
    fun completeBiometricEnrollment(proof: PassphraseProof, key: DataKey, cipher: Cipher) {
        requireFresh(proof)
        val current = checkNotNull(config) { "No passphrase has been set up" }
        val generation = checkNotNull(pendingBiometric) { "No fingerprint enrolment is in progress" }
        try {
            // Before the authorised cipher is used up on it: a destroyed key is never wrapped.
            if (key.isDestroyed) throw KeyDestroyedException()
            cipher.updateAAD(BIOMETRIC_AAD)
            val wrapped = cipher.iv + cipher.doFinal(key.bytes)
            save(
                current.copy(
                    biometric = BiometricWrap(wrapped.base64(), generation),
                    fingerprintInvalidated = false,
                    highestKeyGeneration = maxOf(current.highestKeyGeneration, generation),
                ),
            )
        } catch (e: Exception) {
            cancelBiometricEnrollment()
            throw e
        }
        pendingBiometric = null
        deleteUnreferencedKeys()
    }

    /** The enrolment prompt was cancelled or failed: its new key goes; the existing wrap is untouched. */
    fun cancelBiometricEnrollment() {
        val generation = pendingBiometric ?: return
        pendingBiometric = null
        ignoreFailure { vault.delete(KeyId(KeyKind.BIOMETRIC, generation)) }
    }

    @Throws(IOException::class)
    fun disableBiometric(proof: PassphraseProof) {
        requireFresh(proof)
        val current = checkNotNull(config) { "No passphrase has been set up" }
        cancelBiometricEnrollment()
        save(current.copy(biometric = null, fingerprintInvalidated = false))
        deleteUnreferencedKeys()
    }

    /** The cipher for a fingerprint unlock, or why there is none. */
    fun biometricUnlockCipher(): BiometricCipherResult {
        val wrap = config?.biometric ?: return BiometricCipherResult.NotEnabled
        return try {
            val blob = wrap.wrappedKey.unbase64()
            BiometricCipherResult.Ready(vault.biometricDecryptCipher(wrap.keyGeneration, blob.copyOfRange(0, GCM_IV_SIZE)))
        } catch (_: KeyUnavailableException) {
            // Missing, or permanently invalidated (fingerprints enrolled or removed, screen lock removed).
            biometricLost()
            BiometricCipherResult.Invalidated
        }
    }

    /**
     * Unwraps the data key with [cipher] after the biometric prompt authorised it; null if that
     * fails. Only a wrap that its own, authorised key cannot open is removed (it is of no further
     * use); any other failure may be passing and keeps fingerprint unlock as it is.
     *
     * A success ends a streak of wrong passphrases exactly as the right passphrase does: the
     * count goes back to zero and no wait is left. Someone who only ever unlocks by fingerprint
     * would otherwise carry failures from months ago, and one more wrong passphrase would start
     * at the long waits.
     *
     * @throws IOException if that reset could not be stored; the app is not unlocked then.
     */
    @Throws(IOException::class)
    fun unlockWithBiometric(cipher: Cipher): DataKey? {
        val wrap = config?.biometric ?: return null
        val key = try {
            val blob = wrap.wrappedKey.unbase64()
            cipher.updateAAD(BIOMETRIC_AAD)
            val dek = cipher.doFinal(blob, GCM_IV_SIZE, blob.size - GCM_IV_SIZE)
            try {
                DataKey(dek)
            } finally {
                dek.fill(0)
            }
        } catch (_: AEADBadTagException) {
            biometricLost()
            return null
        } catch (_: GeneralSecurityException) {
            return null
        } catch (_: IllegalStateException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        val current = checkNotNull(config)
        if (current.failedAttempts != 0 || current.lastFailureWallMillis != 0L || current.lastFailureElapsedMillis != 0L) {
            try {
                save(current.copy(failedAttempts = 0, lastFailureWallMillis = 0, lastFailureElapsedMillis = 0))
            } catch (e: Exception) {
                key.destroy()
                throw e
            }
        }
        return key
    }

    /**
     * Removes fingerprint unlock quietly because its key is missing or was invalidated, or no
     * strong biometric is enrolled any more; the passphrase still works, and setting it up again
     * is offered. If that cannot be stored, it still applies to this run and is found again at
     * the next start.
     */
    fun biometricLost() {
        val current = config ?: return
        if (current.biometric == null) return
        val updated = current.copy(biometric = null, fingerprintInvalidated = true)
        try {
            save(updated)
        } catch (_: IOException) {
            // The stored configuration still names the key, so nothing is deleted on the strength
            // of what is only in memory.
            config = updated
            configDurable = false
            return
        }
        deleteUnreferencedKeys()
    }

    @Throws(IOException::class)
    fun dismissFingerprintInvalidated() {
        val current = config ?: return
        if (current.fingerprintInvalidated) save(current.copy(fingerprintInvalidated = false))
    }

    @Throws(IOException::class)
    fun setLockTimeout(seconds: Int) {
        require(seconds in LockConfig.TIMEOUT_CHOICES_SECONDS)
        val current = checkNotNull(config) { "No passphrase has been set up" }
        save(current.copy(lockTimeoutSeconds = seconds))
    }

    /**
     * Deletes everything, in the order that leaves the least behind if it is interrupted: every
     * key first (from that moment nothing stored can be decrypted, whatever else still gets
     * deleted), then the data files ([deleteData], which returns false if some were left), and
     * the configuration last. Every step is tried even if one before it failed, and the result
     * says what is still there. Afterwards [start] decides where the app stands: first-run
     * set-up when everything went, otherwise "cannot be unlocked", from where the reset can be
     * tried again; keys that could not be deleted are tried again at every start.
     *
     * The caller makes sure nothing can write a file any more (the unlocked session is closed)
     * before calling this.
     */
    fun reset(deleteData: () -> Boolean): ResetReport {
        pendingBiometric = null
        val keysDeleted = try {
            vault.deleteAll()
            true
        } catch (_: Exception) {
            false
        }
        val dataDeleted = try {
            deleteData()
        } catch (_: Exception) {
            false
        }
        val configDeleted = try {
            store.delete()
            true
        } catch (_: IOException) {
            false
        }
        config = null
        configDurable = false
        return ResetReport(keysDeleted = keysDeleted, dataDeleted = dataDeleted, configDeleted = configDeleted)
    }

    fun isFresh(proof: PassphraseProof): Boolean {
        val age = clock.elapsedMillis() - proof.atElapsedMillis
        return age in 0..PROOF_VALIDITY_MILLIS
    }

    private fun requireFresh(proof: PassphraseProof) {
        if (!isFresh(proof)) throw ProofExpiredException()
    }

    /** A generation of [kind] above every one that exists or is referenced, so nothing in use is replaced. */
    private fun nextGeneration(kind: KeyKind): Int {
        val used = (vault.keys() + config?.referencedKeys.orEmpty() + listOfNotNull(pendingBiometric?.let { KeyId(KeyKind.BIOMETRIC, it) }))
            .filter { it.kind == kind }
            .maxOfOrNull { it.generation } ?: 0
        val highest = maxOf(used, config?.highestKeyGeneration ?: 0)
        // Checked before anything is created or written, so running out changes nothing.
        if (highest >= KeyId.MAX_GENERATION) throw KeyGenerationsExhaustedException()
        return highest + 1
    }

    private fun newBindingKey(): KeyId {
        val id = KeyId(KeyKind.BINDING, nextGeneration(KeyKind.BINDING))
        vault.createBindingKey(id.generation)
        return id
    }

    /**
     * Runs [commit] (which writes a configuration referring to [newKey]); deletes [newKey] if
     * that fails. A write only fails before its rename (see [writeAtomically]), so a failure
     * here always means the stored configuration is still the old one and does not need [newKey].
     */
    private inline fun commitOrDelete(newKey: KeyId, commit: () -> Unit) {
        try {
            commit()
        } catch (e: Exception) {
            ignoreFailure { vault.delete(newKey) }
            throw e
        }
    }

    /**
     * Deletes every key of the app that the stored configuration (and a pending enrolment) does
     * not use, but only while that configuration is durable. A key is superseded by writing a
     * configuration that no longer names it; until that write has reached the disk, directory
     * included, a power loss can bring the previous configuration back, and it must then find
     * its key. So when the last write could not be made durable, both keys stay, and the next
     * start (which syncs first) cleans up.
     */
    private fun deleteUnreferencedKeys() {
        if (!configDurable) return
        val current = config ?: return
        val keep = current.referencedKeys + listOfNotNull(pendingBiometric?.let { KeyId(KeyKind.BIOMETRIC, it) })
        val existing = try {
            vault.keys()
        } catch (_: Exception) {
            return // Tried again at the next start.
        }
        for (id in existing) if (id !in keep) ignoreFailure { vault.delete(id) }
    }

    private fun passphraseWrap(key: DataKey, passphrase: CharArray, bindingGeneration: Int): PassphraseWrap {
        val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
        val kek = passphraseKey(passphrase, salt, iterations, bindingGeneration)
        try {
            val wrapped = Envelope.seal(kek, PASSPHRASE_PURPOSE, key.bytes, random)
            return PassphraseWrap(salt = salt.base64(), iterations = iterations, wrappedKey = wrapped.base64(), bindingGeneration = bindingGeneration)
        } finally {
            kek.fill(0)
        }
    }

    private fun passphraseKey(passphrase: CharArray, salt: ByteArray, iterations: Int, bindingGeneration: Int): ByteArray {
        val password = passphraseBytes(passphrase)
        try {
            val stretched = Pbkdf2.hmacSha256(password, salt, iterations, DataKey.SIZE)
            val input = KEK_LABEL + stretched
            try {
                return vault.bind(bindingGeneration, input)
            } finally {
                stretched.fill(0)
                input.fill(0)
            }
        } finally {
            password.fill(0)
        }
    }

    /**
     * Writes [updated] and makes it the current configuration. Returns whether it is durable
     * (see [configDurable]).
     * @throws IOException if it was not written: the stored and the current configuration are unchanged.
     */
    private fun save(updated: LockConfig): Boolean {
        // Never write what this version would refuse to read: that would lose the data at the next start.
        check(updated.isValid(minIterations)) { "Refusing to write an invalid lock configuration" }
        val durable = store.write(updated)
        config = updated
        configDurable = durable
        return durable
    }

    private inline fun ignoreFailure(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            // Best effort: a key that cannot be deleted protects nothing the configuration uses,
            // and is tried again at the next start.
        }
    }

    companion object {
        const val DEFAULT_ITERATIONS = PassphraseWrap.MIN_ITERATIONS
        const val SALT_SIZE = 16
        const val GCM_IV_SIZE = 12
        const val PROOF_VALIDITY_MILLIS = 2 * 60_000L

        // Permanent: each of these is mixed into what protects the data key.
        const val PASSPHRASE_PURPOSE = "dek:passphrase"
        val BIOMETRIC_AAD = "proseplow:dek:biometric:v1".toByteArray()
        private val KEK_LABEL = "proseplow passphrase key v1".toByteArray()
    }
}
