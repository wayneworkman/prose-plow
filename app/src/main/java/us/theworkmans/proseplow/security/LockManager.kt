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

    /**
     * The time limit has run out or the phone was restarted ([FingerprintPeriod]): only the
     * passphrase opens the app now. Fingerprint unlock stays set up and works again after it.
     */
    data object PeriodOver : BiometricCipherResult
}

/** The outcome of [LockManager.unlockWithBiometric]. */
sealed interface BiometricUnlockResult {
    class Unlocked(val key: DataKey) : BiometricUnlockResult

    /** Fingerprint unlock is not (or no longer) set up. */
    data object NotEnabled : BiometricUnlockResult

    /**
     * The period ran out before the authorised cipher was used ([FingerprintPeriod]); nothing
     * was decrypted. Fingerprint unlock stays set up and is not marked as invalidated.
     */
    data object PeriodOver : BiometricUnlockResult

    /** The wrap did not open with its own, authorised key and has been removed. */
    data object Removed : BiometricUnlockResult

    /** It did not work this time, for a reason that may pass; fingerprint unlock stays as it is. */
    data object Failed : BiometricUnlockResult
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
    /**
     * The trusted time of the process ([TrustedClock]). It is seeded from the stored
     * configuration at [start], and its mark is stored at every unlock and wherever
     * [recordTrustedTime] is called.
     */
    private val trustedClock: TrustedClock = TrustedClock(clock),
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
        trustedClock.observe(stored.trustedTime)
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
     *
     * [terms] is the agreement to the terms that was given before set-up and held in memory
     * until now; it is written with the first configuration, so that nothing at all is stored
     * before the passphrase is (null: none, as for a lock that asks for no terms).
     * @throws IllegalStateException if a configuration or encrypted data exists (see [start]).
     */
    @Throws(IOException::class, GeneralSecurityException::class)
    fun setUp(passphrase: CharArray, terms: TermsAgreement? = null): PassphraseResult.Success = withNfc(passphrase) { text ->
        check(config == null) { "A passphrase is already set" }
        require(PassphraseRules.check(text) == null) { "The passphrase does not meet the rules" }
        require(terms == null || terms.isValid()) { "The agreement to the terms is not valid" }
        // Looked at again, not only at start: never make a key over a configuration or data that is there.
        check(store.read() == null && !encryptedDataExists()) { "The app is already set up" }
        ignoreFailure { vault.deleteAll() }
        val binding = newBindingKey()
        val key = DataKey.generate(random)
        try {
            commitOrDelete(binding) {
                val durable = save(
                    LockConfig(
                        passphrase = passphraseWrap(key, text, binding.generation),
                        highestKeyGeneration = binding.generation,
                        // Creating the passphrase is the first unlock.
                        fingerprintPeriodStart = periodStartNow(),
                        trustedTime = trustedClock.mark(),
                        acceptedTermsVersion = terms?.version ?: 0,
                        acceptedTerms = terms,
                    ),
                )
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
            // The right passphrase also starts a new period of fingerprint unlock. (A check from
            // Settings, made while unlocked, starts one too; the lock that follows starts it again.)
            save(
                checkNotNull(config).copy(
                    failedAttempts = 0,
                    lastFailureWallMillis = 0,
                    lastFailureElapsedMillis = 0,
                    fingerprintPeriodStart = periodStartNow(),
                    trustedTime = trustedClock.mark(),
                ),
            )
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

    /**
     * The cipher for a fingerprint unlock, or why there is none. When the period has run out
     * ([FingerprintPeriod]) the key store is not even asked: the key stays as it is, and nothing
     * about fingerprint unlock changes.
     */
    fun biometricUnlockCipher(): BiometricCipherResult {
        val wrap = config?.biometric ?: return BiometricCipherResult.NotEnabled
        if (!fingerprintPeriodRunning()) return BiometricCipherResult.PeriodOver
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
     * Unwraps the data key with [cipher] after the biometric prompt authorised it. Only a wrap
     * that its own, authorised key cannot open is removed (it is of no further use); any other
     * failure may be passing and keeps fingerprint unlock as it is.
     *
     * The period of [FingerprintPeriod] is looked at again here, before the cipher is used: it
     * may have run out while the prompt was showing, and the prompt's success says nothing about
     * that. Refused, nothing is decrypted and fingerprint unlock stays set up.
     *
     * A success starts a new period and ends a streak of wrong passphrases exactly as the right
     * passphrase does: the count goes back to zero and no wait is left. Someone who only ever
     * unlocks by fingerprint would otherwise carry failures from months ago, and one more wrong
     * passphrase would start at the long waits.
     *
     * @throws IOException if that could not be stored; the app is not unlocked then.
     */
    @Throws(IOException::class)
    fun unlockWithBiometric(cipher: Cipher): BiometricUnlockResult {
        val wrap = config?.biometric ?: return BiometricUnlockResult.NotEnabled
        if (!fingerprintPeriodRunning()) return BiometricUnlockResult.PeriodOver
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
            return BiometricUnlockResult.Removed
        } catch (_: GeneralSecurityException) {
            return BiometricUnlockResult.Failed
        } catch (_: IllegalStateException) {
            return BiometricUnlockResult.Failed
        } catch (_: IllegalArgumentException) {
            return BiometricUnlockResult.Failed
        }
        val current = checkNotNull(config)
        try {
            save(
                current.copy(
                    failedAttempts = 0,
                    lastFailureWallMillis = 0,
                    lastFailureElapsedMillis = 0,
                    fingerprintPeriodStart = periodStartNow(),
                    trustedTime = trustedClock.mark(),
                ),
            )
        } catch (e: Exception) {
            key.destroy()
            throw e
        }
        return BiometricUnlockResult.Unlocked(key)
    }

    /**
     * Whether fingerprint unlock is set up and its period is running now ([FingerprintPeriod]).
     * False whenever that cannot be decided, a boot count that cannot be read included.
     */
    fun fingerprintPeriodRunning(): Boolean {
        val current = config ?: return false
        if (current.biometric == null) return false
        return FingerprintPeriod.allows(current.fingerprintPeriodStart, bootCount(), clock.elapsedMillis(), current.fingerprintPeriodSeconds)
    }

    /**
     * Starts the period of fingerprint unlock again from [atElapsed] ([Clock.elapsedMillis]):
     * the moment the lock was due, which the caller passes and which is never later than the
     * real lock (a lock that ran late, because a timer could not run, passes the moment it was
     * due, not the moment it ran). Only with fingerprint unlock set up; best effort.
     *
     * A start that is already recorded for this boot and lies between [atElapsed] and now is
     * kept: an unlock that was stored after this lock was decided is the later of the two. If
     * the boot count cannot be read, no start is stored, so the period has run out.
     *
     * If the write fails, the start stored before stays in force: the period then ends earlier
     * than it would have, never later, and the failure is not the lock's to report.
     */
    fun startFingerprintPeriod(atElapsed: Long) {
        val current = config ?: return
        if (current.biometric == null) return
        val now = clock.elapsedMillis()
        val boot = bootCount()
        val existing = current.fingerprintPeriodStart
        val at = atElapsed.coerceIn(0, now.coerceAtLeast(0))
        if (existing != null && boot != null && existing.bootCount == boot && existing.elapsedMillis in at..now) return
        val start = boot?.let { FingerprintPeriodStart(at, it) }
        if (start == existing) return
        try {
            save(current.copy(fingerprintPeriodStart = start))
        } catch (_: IOException) {
            // The stored start stays; see above.
        }
    }

    /** Stores [LockConfig.fingerprintPeriodSeconds]; one of [LockConfig.FINGERPRINT_PERIOD_CHOICES_SECONDS]. */
    @Throws(IOException::class)
    fun setFingerprintPeriod(seconds: Int) {
        require(seconds in LockConfig.FINGERPRINT_PERIOD_CHOICES_SECONDS)
        val current = checkNotNull(config) { "No passphrase has been set up" }
        if (current.fingerprintPeriodSeconds != seconds) save(current.copy(fingerprintPeriodSeconds = seconds))
    }

    /** A start of the period of fingerprint unlock at this moment; null if the boot count cannot be read. */
    private fun periodStartNow(): FingerprintPeriodStart? {
        val boot = bootCount() ?: return null
        return FingerprintPeriodStart(clock.elapsedMillis().coerceAtLeast(0), boot)
    }

    /**
     * [Clock.bootCount], with every failure as "cannot be read": nothing that goes wrong in
     * reading it may reach the lock, and a negative count is not one any phone gives.
     */
    private fun bootCount(): Int? = try {
        clock.bootCount()?.takeIf { it >= 0 }
    } catch (_: Exception) {
        null
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
     * Stores the trusted time now ([TrustedClock.mark]) and returns it. Best effort: when it
     * cannot be written, the mark stored before stays, which only gives less credit for time that
     * has passed (the wall clock is still believed), and the time is still returned. Without a
     * configuration nothing is stored.
     */
    fun recordTrustedTime(): Long {
        val mark = trustedClock.mark()
        val current = config ?: return mark.wallMillis
        if (current.trustedTime != mark) {
            try {
                save(current.copy(trustedTime = mark))
            } catch (_: IOException) {
                // The mark stored before stays in force; see above.
            }
        }
        return mark.wallMillis
    }

    /**
     * The readable copy of how long messages are kept, in days ([LockConfig.messageLifetimeDays]);
     * the default while there is no configuration.
     */
    val messageLifetimeDays: Int get() = config?.messageLifetimeDays ?: LockConfig.DEFAULT_MESSAGE_LIFETIME_DAYS

    /**
     * Stores the readable copy of how long messages are kept; one of
     * [LockConfig.MESSAGE_LIFETIME_CHOICES_DAYS]. Nothing is written when it already is [days].
     */
    @Throws(IOException::class)
    fun setMessageLifetimeDays(days: Int) {
        require(days in LockConfig.MESSAGE_LIFETIME_CHOICES_DAYS)
        val current = checkNotNull(config) { "No passphrase has been set up" }
        if (current.messageLifetimeDays != days) save(current.copy(messageLifetimeDays = days))
    }

    /** The readable copy of the switch that keeps the model's memory ([LockConfig.keepModelMemory]); on while there is no configuration. */
    val keepModelMemory: Boolean get() = config?.keepModelMemory ?: true

    /** Stores [LockConfig.keepModelMemory]. Nothing is written when it already is [keep]. */
    @Throws(IOException::class)
    fun setKeepModelMemory(keep: Boolean) {
        val current = checkNotNull(config) { "No passphrase has been set up" }
        if (current.keepModelMemory != keep) save(current.copy(keepModelMemory = keep))
    }

    /** Stores [LockConfig.backgroundMaintenance]. Nothing is written when it already is [enabled]. */
    @Throws(IOException::class)
    fun setBackgroundMaintenance(enabled: Boolean) {
        val current = checkNotNull(config) { "No passphrase has been set up" }
        if (current.backgroundMaintenance != enabled) save(current.copy(backgroundMaintenance = enabled))
    }

    /** Stores [LockConfig.finishAnswerAfterLocking]. Nothing is written when it already is [enabled]. */
    @Throws(IOException::class)
    fun setFinishAnswerAfterLocking(enabled: Boolean) {
        val current = checkNotNull(config) { "No passphrase has been set up" }
        if (current.finishAnswerAfterLocking != enabled) save(current.copy(finishAnswerAfterLocking = enabled))
    }

    /**
     * Records [agreement] as the agreement to the terms, in place of any before it, and returns
     * only once that is durable (STORE-06 of the features: what a restart finds even after a
     * power loss), so that the lock screen never shows before the agreement it follows is on
     * disk. When the write is in place but could not be made durable, making it durable is tried
     * once more before this fails.
     * @throws IOException if it was not written, or not made durable.
     * @throws IllegalStateException without a configuration.
     */
    @Throws(IOException::class)
    fun acceptTerms(agreement: TermsAgreement) {
        require(agreement.isValid()) { "The agreement to the terms is not valid" }
        val current = checkNotNull(config) { "No passphrase has been set up" }
        if (save(current.copy(acceptedTermsVersion = agreement.version, acceptedTerms = agreement))) return
        configDurable = store.sync()
        if (!configDurable) throw IOException("The agreement to the terms could not be made durable")
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
