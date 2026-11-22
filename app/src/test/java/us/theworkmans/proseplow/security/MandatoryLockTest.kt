package us.theworkmans.proseplow.security

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The lock is mandatory: nothing the app can do, and nothing in its files, leads to a state in
 * which the data opens without the passphrase or an authorised fingerprint. These tests pin
 * that from the outside, including the shape of the code, so that it cannot come back unnoticed.
 */
class MandatoryLockTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val vault = SoftwareKeyVault()
    private val clock = FakeClock()
    private val configFile: File get() = temp.root.resolve("security/lock.json")
    private val passphrase = "correct horse battery"
    private val otherPassphrase = "a different one, longer"

    private fun manager() = LockManager(LockConfigStore(configFile), vault, clock, iterations = 1_000, minIterations = 1, encryptedDataExists = { false })

    private fun LockManager.unlock(text: String) = checkPassphrase(text.toCharArray()) as PassphraseResult.Success

    /** A cold start right now: always locked, with a passphrase wrap on disk. */
    private fun assertLocked(after: String) {
        assertEquals(after, StartResult.Locked, manager().start())
        val stored = Json.parseToJsonElement(configFile.readText()).jsonObject
        assertTrue("$after: the data key is wrapped by the passphrase", "wrappedKey" in stored.getValue("passphrase").jsonObject)
    }

    @Test
    fun everyStartOfASetUpAppIsLockedWhateverWasDoneBefore() {
        val m = manager()
        assertEquals(StartResult.SetupRequired, m.start())
        val created = m.setUp(passphrase.toCharArray())
        assertLocked("set-up")

        m.completeBiometricEnrollment(created.proof, created.key, m.prepareBiometricEnrollment(created.proof))
        assertLocked("turning fingerprint unlock on")
        for (seconds in LockConfig.TIMEOUT_CHOICES_SECONDS) {
            m.setLockTimeout(seconds)
            assertLocked("lock after $seconds s")
        }
        repeat(7) { m.checkPassphrase("not the passphrase".toCharArray()) }
        assertLocked("wrong attempts")
        clock.advance(Backoff.MAX_DELAY_MILLIS)
        val proof = m.unlock(passphrase).proof
        assertLocked("an unlock")
        m.changePassphrase(proof, created.key, otherPassphrase.toCharArray())
        assertLocked("a new passphrase")
        m.disableBiometric(proof)
        assertLocked("turning fingerprint unlock off")
        m.completeBiometricEnrollment(proof, created.key, m.prepareBiometricEnrollment(proof))
        m.biometricLost()
        m.dismissFingerprintInvalidated()
        assertLocked("losing the fingerprint")

        m.reset { true }
        assertEquals("after a reset there is nothing to open", StartResult.SetupRequired, manager().start())
    }

    @Test
    fun theDataKeyOnlyComesFromThePassphraseOrAnAuthorisedFingerprint() {
        val m = manager()
        m.start()
        val key = m.setUp(passphrase.toCharArray()).key

        val cold = manager()
        assertEquals(StartResult.Locked, cold.start())
        // Without fingerprint unlock there is no cipher to authorise, and no other one opens anything.
        assertEquals(BiometricCipherResult.NotEnabled, cold.biometricUnlockCipher())
        assertNull(cold.unlockWithBiometric(Cipher.getInstance("AES/GCM/NoPadding")))
        assertTrue(cold.checkPassphrase("not the passphrase".toCharArray()) is PassphraseResult.Wrong)
        assertArrayEquals(key.bytes, cold.unlock(passphrase).key.bytes)
    }

    @Test
    fun aStartNeverHandsOutAKey() {
        val results = StartResult::class.java.declaredClasses.associate { it.simpleName to it }
        assertEquals(setOf("SetupRequired", "Locked", "Unrecoverable"), results.keys)
        for ((name, type) in results) {
            assertFalse("$name carries a key", type.declaredFields.any { it.type == DataKey::class.java || it.type == ByteArray::class.java })
        }
    }

    @Test
    fun thereIsNoKeyThatOpensWithoutTheUserAndNoSwitchForTheLock() {
        assertEquals(listOf(KeyKind.BINDING, KeyKind.BIOMETRIC), KeyKind.entries.toList())
        assertFalse(KeyVault::class.java.methods.any { it.name.contains("device", ignoreCase = true) })

        val switches = listOf("enableLock", "disableLock", "setScreenSecurity", "setIncognitoKeyboard")
        assertEquals(emptyList<String>(), LockManager::class.java.declaredMethods.map { it.name }.filter { it in switches })
        assertEquals(emptyList<String>(), AppLock::class.java.declaredMethods.map { it.name }.filter { it in switches })

        val settings = listOf("lockEnabled", "deviceWrap", "screenSecurity", "incognitoKeyboard")
        assertEquals(emptyList<String>(), LockConfig::class.java.declaredFields.map { it.name }.filter { it in settings })
        assertEquals(emptyList<String>(), SecurityStatus::class.java.declaredFields.map { it.name }.filter { it in settings })
    }

    /** Version 1 of `lock.json`, field by field. A change here is a change of the stored format. */
    @Test
    fun theStoredConfigurationHasExactlyTheDocumentedFields() {
        val m = manager()
        m.start()
        val created = m.setUp(passphrase.toCharArray())
        var stored = Json.parseToJsonElement(configFile.readText()).jsonObject
        assertEquals(
            setOf(
                "version",
                "passphrase",
                "biometric",
                "lockTimeoutSeconds",
                "failedAttempts",
                "lastFailureWallMillis",
                "lastFailureElapsedMillis",
                "fingerprintInvalidated",
                "highestKeyGeneration",
            ),
            stored.keys,
        )
        assertEquals("1", stored.getValue("version").toString())
        assertEquals(setOf("kdf", "salt", "iterations", "wrappedKey", "bindingGeneration"), stored.getValue("passphrase").jsonObject.keys)
        assertEquals("\"PBKDF2-HMAC-SHA256+keystore-HMAC-SHA256\"", stored.getValue("passphrase").jsonObject.getValue("kdf").toString())
        assertEquals(JsonNull, stored.getValue("biometric"))
        assertEquals("60", stored.getValue("lockTimeoutSeconds").toString())

        m.completeBiometricEnrollment(created.proof, created.key, m.prepareBiometricEnrollment(created.proof))
        stored = Json.parseToJsonElement(configFile.readText()).jsonObject
        assertEquals(setOf("wrappedKey", "keyGeneration"), stored.getValue("biometric").jsonObject.keys)
    }

    @Test
    fun theProductionKeyDerivationIsTheOneThatWasDecided() {
        assertEquals(600_000, LockManager.DEFAULT_ITERATIONS)
        assertEquals(600_000, PassphraseWrap.MIN_ITERATIONS)
        assertEquals(16, LockManager.SALT_SIZE)
        assertEquals("dek:passphrase", LockManager.PASSPHRASE_PURPOSE)
        assertEquals("proseplow:dek:biometric:v1", String(LockManager.BIOMETRIC_AAD))
        assertEquals(10, PassphraseRules.MIN_LENGTH)
        assertEquals(1024, PassphraseRules.MAX_LENGTH)
        assertEquals(4, Backoff.FREE_ATTEMPTS)
    }

    /**
     * The passphrase key, recomputed here from its definition with nothing but JCA calls and the
     * binding key: HMAC-SHA256(bindingKey, label || PBKDF2-HMAC-SHA256(NFC UTF-8 passphrase,
     * salt, iterations, 32 bytes)). It opens the stored wrap, so the label and the construction
     * are what they are documented to be.
     */
    @Test
    fun thePassphraseKeyIsBoundToTheKeystoreKeyAsDocumented() {
        val m = manager()
        m.start()
        val key = m.setUp(passphrase.toCharArray()).key
        val wrap = LockConfigStore(configFile).read()!!.passphrase

        val stretched = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(passphrase.toCharArray(), wrap.salt.unbase64(), wrap.iterations, 256)).encoded
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(vault.binding)
        val kek = mac.doFinal("proseplow passphrase key v1".toByteArray() + stretched)
        assertArrayEquals(key.bytes, Envelope.open(kek, "dek:passphrase", wrap.wrappedKey.unbase64()))
    }
}
