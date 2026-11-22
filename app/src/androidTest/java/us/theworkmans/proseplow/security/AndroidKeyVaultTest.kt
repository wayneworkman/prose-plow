package us.theworkmans.proseplow.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import us.theworkmans.proseplow.DeviceTests
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/**
 * [AndroidKeyVault] against the phone's real Keystore. The keys are the tests' own (see
 * [DeviceTests]): every alias carries the test namespace, and the aliases the app itself would
 * use are checked to be untouched after every test.
 */
@RunWith(AndroidJUnit4::class)
class AndroidKeyVaultTest {

    private val vault = DeviceTests.vault()
    private val keyStore = DeviceTests.keyStore()
    private lateinit var appAliasesBefore: Set<String>

    private fun alias(kind: KeyKind, generation: Int) = DeviceTests.NAMESPACE + KeyId(kind, generation).alias

    private fun testAliases() = keyStore.aliases().toList().filter { it.startsWith(DeviceTests.NAMESPACE) }.toSet()

    private fun info(alias: String): KeyInfo {
        val key = keyStore.getKey(alias, null) as SecretKey
        return SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
    }

    private fun strongBiometricEnrolled(): Boolean {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    }

    @Before
    fun startWithoutTestKeys() {
        appAliasesBefore = DeviceTests.ownAliases()
        vault.deleteAll()
        assertEquals(emptySet<String>(), testAliases())
    }

    @After
    fun leaveNoTestKeyAndTheAppsOwnKeysAsTheyWere() {
        vault.deleteAll()
        for (alias in testAliases()) keyStore.deleteEntry(alias)
        assertEquals("the app's own aliases were touched", appAliasesBefore, DeviceTests.ownAliases())
    }

    @Test
    fun aBindingKeyIsMadeInTheKeystoreAndCannotLeaveIt() {
        vault.createBindingKey(1)
        assertTrue(vault.contains(KeyId(KeyKind.BINDING, 1)))
        assertEquals(setOf(alias(KeyKind.BINDING, 1)), testAliases())

        val key = keyStore.getKey(alias(KeyKind.BINDING, 1), null) as SecretKey
        assertNull("the key material is not available to the app", key.encoded)
        val info = info(alias(KeyKind.BINDING, 1))
        assertFalse("the binding key works without the user: the passphrase is what it binds", info.isUserAuthenticationRequired)
        assertEquals(KeyProperties.PURPOSE_SIGN, info.purposes)
    }

    @Test
    fun theHmacOfABindingKeyIsStableAndItsOwn() {
        vault.createBindingKey(1)
        vault.createBindingKey(2)
        val input = ByteArray(59) { it.toByte() }
        val first = vault.bind(1, input)
        assertEquals(32, first.size)
        assertArrayEquals("the same key and input give the same result", first, vault.bind(1, input))
        assertArrayEquals("also through a vault made later (a new process)", first, DeviceTests.vault().bind(1, input))
        assertFalse(first.contentEquals(vault.bind(1, input.copyOf().also { it[0] = 1 })))
        assertFalse("another generation is another key", first.contentEquals(vault.bind(2, input)))

        vault.createBindingKey(1)
        assertFalse("making the generation again replaces the key", first.contentEquals(vault.bind(1, input)))
    }

    @Test
    fun generationsAreListedAndDeletedOneByOne() {
        for (generation in listOf(1, 2, 7)) vault.createBindingKey(generation)
        assertEquals(setOf(KeyId(KeyKind.BINDING, 1), KeyId(KeyKind.BINDING, 2), KeyId(KeyKind.BINDING, 7)), vault.keys())

        vault.delete(KeyId(KeyKind.BINDING, 2))
        assertEquals(setOf(KeyId(KeyKind.BINDING, 1), KeyId(KeyKind.BINDING, 7)), vault.keys())
        assertFalse(vault.contains(KeyId(KeyKind.BINDING, 2)))
        vault.delete(KeyId(KeyKind.BINDING, 2)) // Deleting what is not there is not an error.
        assertThrows(KeyUnavailableException::class.java) { vault.bind(2, ByteArray(8)) }
        assertEquals(32, vault.bind(7, ByteArray(8)).size)
    }

    @Test
    fun deleteAllRemovesEveryKeyOfTheVaultInAnyFormAndNoOther() {
        vault.createBindingKey(1)
        vault.createBindingKey(3)
        // A form this version does not know, and a key that is not the vault's at all.
        val unknownForm = DeviceTests.NAMESPACE + KeyId.ALIAS_PREFIX + "something.later"
        val foreign = DeviceTests.NAMESPACE + "unrelated"
        for (alias in listOf(unknownForm, foreign)) {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
            generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN).build())
            generator.generateKey()
        }
        assertEquals("only known forms are listed", setOf(KeyId(KeyKind.BINDING, 1), KeyId(KeyKind.BINDING, 3)), vault.keys())

        vault.deleteAll()
        assertEquals(emptySet<KeyId>(), vault.keys())
        assertEquals("the unknown form went too, the foreign key stayed", setOf(foreign), testAliases())
    }

    @Test
    fun theTestsKeysAndTheAppsOwnKeysNeverMeet() {
        vault.createBindingKey(1)
        assertTrue(testAliases().none { it.startsWith(KeyId.ALIAS_PREFIX) })
        assertEquals("a key made by a test is not one of the app's", appAliasesBefore, DeviceTests.ownAliases())
        assertThrows(IllegalArgumentException::class.java) { AndroidKeyVault(KeyId.ALIAS_PREFIX) }
    }

    @Test
    fun aBiometricKeyNeedsAStrongBiometricForEveryUse() {
        assumeTrue("needs an enrolled fingerprint", strongBiometricEnrolled())
        vault.createBiometricKey(1)
        assertTrue(vault.contains(KeyId(KeyKind.BIOMETRIC, 1)))

        val info = info(alias(KeyKind.BIOMETRIC, 1))
        assertTrue(info.isUserAuthenticationRequired)
        assertTrue(info.isInvalidatedByBiometricEnrollment)
        assertEquals(256, info.keySize)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            assertEquals("a strong biometric only: no device credential", KeyProperties.AUTH_BIOMETRIC_STRONG, info.userAuthenticationType)
        }

        // The cipher can be prepared (the prompt needs it), but it does nothing until a prompt has authorised it.
        val cipher = vault.biometricEncryptCipher(1)
        assertThrows(Exception::class.java) { cipher.doFinal(ByteArray(32)) }

        vault.delete(KeyId(KeyKind.BIOMETRIC, 1))
        assertThrows(KeyUnavailableException::class.java) { vault.biometricEncryptCipher(1) }
    }

    @Test
    fun aBiometricKeyCannotBeMadeWithoutAnEnrolledBiometric() {
        assumeFalse("only where no fingerprint is enrolled", strongBiometricEnrolled())
        assertThrows(Exception::class.java) { vault.createBiometricKey(1) }
        assertFalse(vault.contains(KeyId(KeyKind.BIOMETRIC, 1)))
    }
}
