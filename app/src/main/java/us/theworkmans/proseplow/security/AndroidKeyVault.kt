package us.theworkmans.proseplow.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [KeyVault] on the Android Keystore. Kept deliberately thin; all logic is in [LockManager].
 * StrongBox is not requested (it is optional hardware); the keys live in the TEE where there is
 * one. The key store is loaded on first use (which is always off the main thread, inside the
 * lock's work), so a key store that fails to load ends up on the lock's error screen instead of
 * crashing the app.
 *
 * - Binding keys: HMAC-SHA256 (`PURPOSE_SIGN`), no user authentication; one alias per generation.
 * - Biometric keys: AES-256-GCM; user authentication for every use, by a strong (Class 3)
 *   biometric only: `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)` on API 30+, and
 *   on API 28-29 the equivalent legacy form, validity duration -1 (every use needs a biometric
 *   authentication through a CryptoObject; the device credential cannot authorise it).
 *   Invalidated when biometrics are enrolled or all removed (`setInvalidatedByBiometricEnrollment`,
 *   true is also the default) and, as every auth-bound key, when the secure lock screen is
 *   removed; using it then throws KeyPermanentlyInvalidatedException at Cipher.init.
 *
 * [namespace] is put in front of every alias and is empty in the app. The instrumented tests
 * pass one, so that they work on real Keystore keys without ever seeing, replacing or deleting
 * the keys of an installed, set-up app: an alias with a namespace does not start with
 * [KeyId.ALIAS_PREFIX], and one without does not start with the namespace.
 */
class AndroidKeyVault(private val namespace: String = "") : KeyVault {

    init {
        require(namespace.isEmpty() || !(namespace + KeyId.ALIAS_PREFIX).startsWith(KeyId.ALIAS_PREFIX)) {
            "A namespace must keep its aliases apart from the app's own"
        }
    }

    private val keyStore: KeyStore by lazy { KeyStore.getInstance(PROVIDER).apply { load(null) } }

    override fun createBindingKey(generation: Int) {
        val alias = alias(KeyId(KeyKind.BINDING, generation))
        deleteAlias(alias)
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, PROVIDER)
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN).build())
        generator.generateKey()
    }

    override fun bind(generation: Int, input: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        try {
            mac.init(secretKey(alias(KeyId(KeyKind.BINDING, generation))))
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw KeyUnavailableException("The binding key was invalidated", e, permanentlyInvalidated = true)
        }
        return mac.doFinal(input)
    }

    override fun createBiometricKey(generation: Int) {
        val alias = alias(KeyId(KeyKind.BIOMETRIC, generation))
        deleteAlias(alias)
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION") // The only form there is before API 30.
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(builder.build())
        generator.generateKey()
    }

    override fun biometricEncryptCipher(generation: Int): Cipher {
        val cipher = Cipher.getInstance(AES_GCM)
        initOrReport(cipher, Cipher.ENCRYPT_MODE, secretKey(alias(KeyId(KeyKind.BIOMETRIC, generation))), null)
        return cipher
    }

    override fun biometricDecryptCipher(generation: Int, iv: ByteArray): Cipher {
        val cipher = Cipher.getInstance(AES_GCM)
        initOrReport(cipher, Cipher.DECRYPT_MODE, secretKey(alias(KeyId(KeyKind.BIOMETRIC, generation))), GCMParameterSpec(TAG_BITS, iv))
        return cipher
    }

    override fun keys(): Set<KeyId> = ownAliases().mapNotNullTo(HashSet()) { KeyId.parse(it.removePrefix(namespace)) }

    override fun contains(key: KeyId): Boolean = keyStore.containsAlias(alias(key))

    override fun delete(key: KeyId) = deleteAlias(alias(key))

    override fun deleteAll() {
        var failure: Exception? = null
        for (alias in ownAliases()) {
            try {
                keyStore.deleteEntry(alias)
            } catch (e: Exception) {
                failure = e
            }
        }
        failure?.let { throw KeyDeletionException("Some keys could not be deleted", it) }
    }

    private fun alias(key: KeyId) = namespace + key.alias

    /** Every alias of this vault, in a known form or not. */
    private fun ownAliases(): List<String> = keyStore.aliases().toList().filter { it.startsWith(namespace + KeyId.ALIAS_PREFIX) }

    private fun secretKey(alias: String): SecretKey = try {
        keyStore.getKey(alias, null) as? SecretKey ?: throw KeyUnavailableException("Key $alias is missing")
    } catch (e: UnrecoverableKeyException) {
        throw KeyUnavailableException("Key $alias cannot be loaded", e)
    }

    private fun initOrReport(cipher: Cipher, mode: Int, key: SecretKey, spec: GCMParameterSpec?) {
        try {
            if (spec == null) cipher.init(mode, key) else cipher.init(mode, key, spec)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw KeyUnavailableException("The key was permanently invalidated", e, permanentlyInvalidated = true)
        }
    }

    private fun deleteAlias(alias: String) {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val AES_GCM = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
