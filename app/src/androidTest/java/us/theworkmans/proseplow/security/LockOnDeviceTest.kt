package us.theworkmans.proseplow.security

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import us.theworkmans.proseplow.DeviceTests
import java.io.File

/**
 * The lock core on the phone itself: the real Keystore behind [LockManager], the production key
 * derivation (600,000 iterations), and the phone's file system under the atomic write. Keys and
 * files are the tests' own (see [DeviceTests]).
 */
@RunWith(AndroidJUnit4::class)
class LockOnDeviceTest {

    private val vault = DeviceTests.vault()
    private lateinit var directory: File
    private lateinit var appAliasesBefore: Set<String>
    private val passphrase = "correct horse battery"
    private val otherPassphrase = "a different one, longer"
    private val plaintext = "a chat".toByteArray()

    private val clock = object : Clock {
        override fun wallMillis() = System.currentTimeMillis()

        override fun elapsedMillis() = SystemClock.elapsedRealtime()
    }

    private val configFile get() = File(directory, "security/lock.json")

    /** A new "process": the production parameters over the test's own keys and files. */
    private fun manager() = LockManager(LockConfigStore(configFile), vault, clock, encryptedDataExists = { false })

    @Before
    fun startClean() {
        appAliasesBefore = DeviceTests.ownAliases()
        vault.deleteAll()
        directory = DeviceTests.scratchDirectory("lock")
    }

    @After
    fun cleanUp() {
        vault.deleteAll()
        directory.deleteRecursively()
        assertEquals("the app's own aliases were touched", appAliasesBefore, DeviceTests.ownAliases())
    }

    @Test
    fun anAtomicWriteIsDurableOnThisPhonesFileSystem() {
        val file = File(directory, "a/b/file.bin")
        val steps = mutableListOf<WriteStep>()
        assertTrue("the directory sync works here", writeAtomically(file, plaintext, onStep = { steps += it }))
        assertEquals(WriteStep.entries.toList(), steps)
        assertArrayEquals(plaintext, file.readBytes())
        assertTrue(syncDirectory(directory))
        assertTrue(syncExisting(file, levels = 2))
    }

    @Test
    fun setUpUnlockChangeAndResetWithTheRealKeystore() {
        val first = manager()
        assertEquals(StartResult.SetupRequired, first.start())
        assertFalse(configFile.exists())
        val created = first.setUp(passphrase.toCharArray())
        val blob = DataCrypto(created.key).seal("device-test", plaintext)
        assertEquals(PassphraseWrap.MIN_ITERATIONS, first.config!!.passphrase.iterations)
        assertEquals(setOf(KeyId(KeyKind.BINDING, 1)), vault.keys())

        val second = manager()
        assertEquals(StartResult.Locked, second.start())
        assertEquals(PassphraseResult.Wrong(0), second.checkPassphrase(otherPassphrase.toCharArray()))
        val unlocked = second.checkPassphrase(passphrase.toCharArray()) as PassphraseResult.Success
        assertArrayEquals("the same data key comes back out of the Keystore-bound wrap", plaintext, DataCrypto(unlocked.key).open("device-test", blob))

        second.changePassphrase(unlocked.proof, unlocked.key, otherPassphrase.toCharArray())
        assertEquals("the old binding key is deleted once the new configuration is durable", setOf(KeyId(KeyKind.BINDING, 2)), vault.keys())

        val third = manager()
        assertEquals(StartResult.Locked, third.start())
        assertEquals(PassphraseResult.Wrong(0), third.checkPassphrase(passphrase.toCharArray()))
        val again = third.checkPassphrase(otherPassphrase.toCharArray()) as PassphraseResult.Success
        assertArrayEquals(plaintext, DataCrypto(again.key).open("device-test", blob))

        assertTrue(third.reset { true }.complete)
        assertEquals(emptySet<KeyId>(), vault.keys())
        assertFalse(configFile.exists())
        assertEquals(StartResult.SetupRequired, manager().start())
    }
}
