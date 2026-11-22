package us.theworkmans.proseplow.security

import java.security.GeneralSecurityException
import javax.crypto.Cipher

/**
 * A hardware-held key is missing or can no longer be used. [permanentlyInvalidated] is set when
 * the platform invalidated it for good (for example a new fingerprint was enrolled).
 */
class KeyUnavailableException(message: String, cause: Throwable? = null, val permanentlyInvalidated: Boolean = false) :
    GeneralSecurityException(message, cause)

/** No new key generation is left ([KeyId.MAX_GENERATION] reached); nothing was changed. */
class KeyGenerationsExhaustedException : GeneralSecurityException("No key generation is left")

/** Some keys could not be deleted (every key was still tried). */
class KeyDeletionException(message: String, cause: Throwable? = null) : GeneralSecurityException(message, cause)

enum class KeyKind { BINDING, BIOMETRIC }

/**
 * One key of the app. A key is never overwritten: its successor is made under the next
 * generation (a new alias), so the key a stored configuration refers to stays usable until a
 * configuration referring to its successor is durable on disk. Generations start at 1 and every
 * alias carries its number.
 */
data class KeyId(val kind: KeyKind, val generation: Int) {
    init {
        require(generation in 1..MAX_GENERATION)
    }

    val alias: String get() = "${baseAlias(kind)}.$generation"

    companion object {
        /** Every alias of the app starts with this (also used to find keys of unknown form). Permanent. */
        const val ALIAS_PREFIX = "proseplow."

        /**
         * The highest generation there can be. Every generation up to it has an alias that
         * [parse] reads back (at most this many digits), so no key of the app can ever be
         * invisible to [KeyVault.keys] and escape the clean-up. Far beyond any real use (one
         * generation per passphrase change or fingerprint set-up).
         */
        const val MAX_GENERATION = 999_999_999
        private val MAX_DIGITS = MAX_GENERATION.toString().length

        fun baseAlias(kind: KeyKind) = when (kind) {
            KeyKind.BINDING -> "proseplow.passphrase.binding"
            KeyKind.BIOMETRIC -> "proseplow.dek.biometric"
        }

        /** The key an alias names, or null if it is not one of the app's aliases in a known form. */
        fun parse(alias: String): KeyId? {
            for (kind in KeyKind.entries) {
                val base = baseAlias(kind)
                if (!alias.startsWith("$base.")) continue
                val number = alias.substring(base.length + 1)
                if (number.isEmpty() || number.length > MAX_DIGITS || !number.all { it in '0'..'9' } || number.startsWith("0")) continue
                return KeyId(kind, number.toInt())
            }
            return null
        }
    }
}

/**
 * The non-exportable keys the app keeps in the platform key store (the Android Keystore on a
 * phone, software keys in the unit tests). None of them ever leaves the vault; the vault only
 * performs operations with them. All methods block.
 *
 * - The binding key (HMAC-SHA256, no user authentication) ties the passphrase key derivation to
 *   this device, so a copied lock configuration cannot be attacked off the device. A new
 *   generation is made for every new passphrase, so an older copy of the configuration no longer
 *   opens once its binding key is deleted.
 * - The biometric key (AES-256-GCM, strong biometric authentication for every use, invalidated
 *   when biometrics are enrolled or removed) wraps the data key for fingerprint unlock.
 *
 * There is no key that opens the data without the user: the lock is mandatory, and the data key
 * only ever exists wrapped by the passphrase key and, optionally, the biometric key.
 */
interface KeyVault {
    /** Creates (or replaces) the binding key of [generation]. */
    fun createBindingKey(generation: Int)

    /** HMAC-SHA256 of [input] with the binding key of [generation] (32 bytes). */
    fun bind(generation: Int, input: ByteArray): ByteArray

    /** Creates (or replaces) the biometric key of [generation]. Fails if no strong biometric is enrolled. */
    fun createBiometricKey(generation: Int)

    /**
     * A cipher for wrapping, to be authorised by a biometric prompt before use.
     * @throws KeyUnavailableException if the key is gone or permanently invalidated.
     */
    fun biometricEncryptCipher(generation: Int): Cipher

    /** As [biometricEncryptCipher], for unwrapping a blob encrypted with [iv]. */
    fun biometricDecryptCipher(generation: Int, iv: ByteArray): Cipher

    /** The app's keys that exist (in a known alias form). */
    fun keys(): Set<KeyId>

    fun contains(key: KeyId): Boolean = key in keys()

    /** Deletes [key] if it exists. */
    fun delete(key: KeyId)

    /**
     * Deletes every key of this app (used by the reset), including aliases of a form this version
     * does not know. Every key is tried even if deleting one fails.
     * @throws KeyDeletionException if any could not be deleted.
     */
    fun deleteAll()
}
