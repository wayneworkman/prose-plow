package us.theworkmans.proseplow.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.Base64

/** The app lock's state machine, with software keys standing in for the Android Keystore. */
class LockManagerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val vault = SoftwareKeyVault()
    private val clock = FakeClock()
    private val configFile: File get() = temp.root.resolve("security/lock.json")
    private var encryptedData = false

    private val passphrase = "correct horse battery"
    private val otherPassphrase = "a different one, longer"

    /** A new "process": a fresh manager over the same configuration file and keys. */
    private fun manager(
        keyVault: KeyVault = vault,
        iterations: Int = 1_000,
        minIterations: Int = 1,
        store: LockConfigStore = LockConfigStore(configFile),
    ) = LockManager(store, keyVault, clock, iterations = iterations, minIterations = minIterations, encryptedDataExists = { encryptedData })

    private fun LockManager.unlock(text: String): PassphraseResult.Success = checkPassphrase(text.toCharArray()) as PassphraseResult.Success

    /** First run with [passphrase]; returns the data key. */
    private fun setUp(): DataKey {
        val m = manager()
        assertEquals(StartResult.SetupRequired, m.start())
        return m.setUp(passphrase.toCharArray()).key
    }

    private fun nothingIsPersisted() {
        assertFalse("no configuration", configFile.exists())
        assertFalse("not even a temp file", tempFileFor(configFile).exists())
        assertTrue("no key: ${vault.aliases.keys}", vault.aliases.isEmpty())
    }

    // --- First run --------------------------------------------------------------------------------

    @Test
    fun aFirstStartAsksForSetUpAndPersistsNothing() {
        val m = manager()
        assertEquals(StartResult.SetupRequired, m.start())
        assertNull(m.config)
        nothingIsPersisted()
        assertFalse("the security directory is not even made", configFile.parentFile!!.exists())

        // However often the app is started before a passphrase exists.
        repeat(3) { assertEquals(StartResult.SetupRequired, manager().start()) }
        nothingIsPersisted()
    }

    @Test
    fun nothingIsPersistedForAPassphraseThatBreaksTheRules() {
        val m = manager()
        m.start()
        for (bad in listOf("too short", "1234567890123", "", "a".repeat(PassphraseRules.MAX_LENGTH + 1))) {
            assertThrows(bad, IllegalArgumentException::class.java) { m.setUp(bad.toCharArray()) }
            nothingIsPersisted()
            assertNull(m.config)
        }
    }

    @Test
    fun setUpStoresTheDataKeyWrappedByThePassphraseAndNothingElse() {
        val key = setUp()
        val config = LockConfigStore(configFile).read()!!
        assertTrue(config.isValid(minIterations = 1))
        assertNull(config.biometric)
        assertTrue(config.passphrase.iterations >= 1_000)
        assertEquals(16, Base64.getDecoder().decode(config.passphrase.salt).size)
        assertEquals("exactly one key, the binding key the wrap names", listOf(KeyId(KeyKind.BINDING, config.passphrase.bindingGeneration)), vault.entries.keys.toList())
        assertEquals(config.passphrase.bindingGeneration, config.highestKeyGeneration)

        val m = manager()
        assertEquals("a cold start is locked", StartResult.Locked, m.start())
        assertArrayEquals(key.bytes, m.unlock(passphrase).key.bytes)
    }

    @Test
    fun setUpGivesAProofSoFingerprintUnlockCanBeOfferedAtOnce() {
        val m = manager()
        m.start()
        val result = m.setUp(passphrase.toCharArray())
        assertTrue(m.isFresh(result.proof))
        m.completeBiometricEnrollment(result.proof, result.key, m.prepareBiometricEnrollment(result.proof))
        assertNotNull(LockConfigStore(configFile).read()!!.biometric)
    }

    @Test
    fun encryptedDataWithoutItsConfigurationIsNeverGivenANewKey() {
        encryptedData = true
        // A key from before, whatever it is: it is not this start's to delete either.
        vault.createBindingKey(3)
        val m = manager()
        assertEquals(StartResult.Unrecoverable(UnrecoverableReason.CONFIG_MISSING), m.start())
        assertFalse(configFile.exists())
        assertEquals(setOf(KeyId(KeyKind.BINDING, 3)), vault.keys())

        // And the set-up cannot be reached around the start.
        assertThrows(IllegalStateException::class.java) { m.setUp(passphrase.toCharArray()) }
        assertThrows(IllegalStateException::class.java) { manager().setUp(passphrase.toCharArray()) }
        assertFalse(configFile.exists())
        assertEquals("no key was made, none deleted", setOf(KeyId(KeyKind.BINDING, 3)), vault.keys())
    }

    @Test
    fun setUpLooksAgainForDataThatAppearedAfterTheStart() {
        val m = manager()
        assertEquals(StartResult.SetupRequired, m.start())
        encryptedData = true
        assertThrows(IllegalStateException::class.java) { m.setUp(passphrase.toCharArray()) }
        nothingIsPersisted()
    }

    @Test
    fun setUpNeverReplacesAnExistingConfiguration() {
        val key = setUp()
        val before = configFile.readText()
        val keysBefore = vault.keys()

        // A manager that was never started does not know there is one; set-up finds out itself.
        assertThrows(IllegalStateException::class.java) { manager().setUp(otherPassphrase.toCharArray()) }
        // A started one knows.
        val started = manager()
        started.start()
        assertThrows(IllegalStateException::class.java) { started.setUp(otherPassphrase.toCharArray()) }

        assertEquals(before, configFile.readText())
        assertEquals(keysBefore, vault.keys())
        assertArrayEquals(key.bytes, started.unlock(passphrase).key.bytes)
    }

    @Test
    fun keysLeftByASetUpThatNeverFinishedAreDeletedAtStart() {
        vault.createBindingKey(1) // The first step of a set-up, then the app stopped.
        assertEquals(StartResult.SetupRequired, manager().start())
        nothingIsPersisted()
    }

    @Test
    fun aSetUpStoppedRightAfterItsKeyWasMadeStartsOverCleanly() {
        val m = manager()
        m.start()
        vault.afterCreate = { throw SimulatedCrash() }
        assertThrows(SimulatedCrash::class.java) { m.setUp(passphrase.toCharArray()) }
        vault.afterCreate = {}
        assertFalse(configFile.exists())

        val next = manager()
        assertEquals(StartResult.SetupRequired, next.start())
        nothingIsPersisted()
        val key = next.setUp(otherPassphrase.toCharArray()).key
        assertArrayEquals(key.bytes, manager().also { it.start() }.unlock(otherPassphrase).key.bytes)
    }

    @Test
    fun aSetUpWhoseConfigurationCannotBeWrittenLeavesNothingBehind() {
        val m = manager()
        m.start()
        temp.root.setWritable(false) // The security directory cannot be made.
        try {
            assertThrows(IOException::class.java) { m.setUp(passphrase.toCharArray()) }
        } finally {
            temp.root.setWritable(true)
        }
        assertNull(m.config)
        nothingIsPersisted()
        // Once the disk works again, so does the set-up.
        assertNotNull(m.setUp(passphrase.toCharArray()))
        assertEquals(StartResult.Locked, manager().start())
    }

    @Test
    fun aSetUpThatCannotBeMadeDurableFailsAndLeavesNothingBehind() {
        val m = manager(store = LockConfigStore(configFile, directorySync = { false }))
        m.start()
        assertThrows(IOException::class.java) { m.setUp(passphrase.toCharArray()) }
        assertNull(m.config)
        nothingIsPersisted()
        assertEquals(StartResult.SetupRequired, manager().start())
    }

    // --- The stored configuration -----------------------------------------------------------------

    @Test
    fun aDamagedOrInconsistentConfigurationIsUnrecoverable() {
        configFile.parentFile!!.mkdirs()
        for (text in listOf(
            "{ not json",
            "",
            "[]",
            """{"version":1}""",
            """{"version":1,"highestKeyGeneration":1}""",
            // The shape a lock that could be turned off would have: no passphrase wrap.
            """{"version":1,"lockEnabled":false,"deviceWrap":"AAAA","highestKeyGeneration":1}""",
        )) {
            configFile.writeText(text)
            assertEquals(text, StartResult.Unrecoverable(UnrecoverableReason.CONFIG_UNREADABLE), manager().start())
            assertEquals("nothing is written or deleted", text, configFile.readText())
        }
    }

    @Test
    fun lostKeystoreKeysAreUnrecoverable() {
        setUp()
        vault.deleteAll()
        val m = manager()
        assertEquals(StartResult.Unrecoverable(UnrecoverableReason.KEY_LOST), m.start())
        assertNull(m.config)
        assertTrue("the configuration is left for a second look", configFile.exists())
    }

    @Test
    fun aMissingBindingKeyIsUnrecoverable() {
        setUp()
        vault.delete(vault.bindingKeys.single())
        assertEquals(StartResult.Unrecoverable(UnrecoverableReason.KEY_LOST), manager().start())
    }

    @Test
    fun theConfigurationHoldsNoSecretInTheClear() {
        val key = setUp()
        val text = configFile.readText()
        assertFalse(text.contains(passphrase))
        assertFalse(text.contains(Base64.getEncoder().encodeToString(key.bytes)))
        assertFalse(text.contains(Base64.getEncoder().encodeToString(passphrase.toByteArray())))
    }

    @Test
    fun theDefaultIterationCountIsAtLeast600000AndStored() {
        assertTrue(LockManager.DEFAULT_ITERATIONS >= 600_000)
        val m = manager(iterations = LockManager.DEFAULT_ITERATIONS)
        m.start()
        val key = m.setUp(passphrase.toCharArray()).key
        assertEquals(LockManager.DEFAULT_ITERATIONS, LockConfigStore(configFile).read()!!.passphrase.iterations)
        assertArrayEquals(key.bytes, manager(iterations = 1).also { it.start() }.unlock(passphrase).key.bytes)
        // The production minimum accepts what the production default writes.
        val strict = LockManager(LockConfigStore(configFile), vault, clock, encryptedDataExists = { encryptedData })
        assertEquals(StartResult.Locked, strict.start())
    }

    // --- The passphrase ---------------------------------------------------------------------------

    @Test
    fun aWrongPassphraseIsRejected() {
        setUp()
        val m = manager()
        m.start()
        assertEquals(PassphraseResult.Wrong(0), m.checkPassphrase(otherPassphrase.toCharArray()))
        assertEquals(PassphraseResult.Wrong(0), m.checkPassphrase(passphrase.uppercase().toCharArray()))
        m.unlock(passphrase)
    }

    @Test
    fun aPassphraseUnlocksHoweverTheKeyboardComposedIt() {
        val m = manager()
        m.start()
        // Set with "é" as one code point; entered with "e" and a combining accent, and the other way round.
        val key = m.setUp("café au lait".toCharArray()).key
        val restarted = manager()
        restarted.start()
        assertArrayEquals(key.bytes, restarted.unlock("café au lait").key.bytes)
        assertArrayEquals(key.bytes, restarted.unlock("café au lait").key.bytes)
        assertEquals(PassphraseResult.Wrong(0), restarted.checkPassphrase("cafe au lait".toCharArray()))

        val proof = restarted.unlock("café au lait").proof
        restarted.changePassphrase(proof, key, "naïve passphrase".toCharArray())
        assertArrayEquals(key.bytes, manager().also { it.start() }.unlock("naïve passphrase").key.bytes)
    }

    @Test
    fun thePassphraseArrayIsLeftForItsOwnerToWipe() {
        val m = manager()
        m.start()
        val typed = "café au lait".toCharArray()
        m.setUp(typed)
        assertEquals("café au lait", String(typed))
        val again = "café au lait".toCharArray()
        m.checkPassphrase(again)
        assertEquals("café au lait", String(again))
    }

    @Test
    fun theWrappedKeyCannotBeOpenedWithoutThisDevicesBindingKey() {
        setUp()
        // The configuration copied to another device (whose key store has another binding key).
        val generation = LockConfigStore(configFile).read()!!.passphrase.bindingGeneration
        val elsewhere = SoftwareKeyVault().apply { createBindingKey(generation) }
        val m = manager(elsewhere)
        assertEquals(StartResult.Locked, m.start())
        assertEquals(PassphraseResult.Wrong(0), m.checkPassphrase(passphrase.toCharArray()))
    }

    @Test
    fun changingThePassphraseNeedsAFreshProofAndReplacesTheOldOne() {
        val key = setUp()
        val m = manager()
        m.start()
        val proof = m.unlock(passphrase).proof
        assertThrows(IllegalArgumentException::class.java) { m.changePassphrase(proof, key, "too short".toCharArray()) }
        assertThrows(IllegalArgumentException::class.java) { m.changePassphrase(proof, key, "1234567890123".toCharArray()) }
        assertEquals("a refused change makes no key", 1, vault.bindingKeys.size)
        m.changePassphrase(proof, key, otherPassphrase.toCharArray())

        val restarted = manager()
        restarted.start()
        assertEquals(PassphraseResult.Wrong(0), restarted.checkPassphrase(passphrase.toCharArray()))
        assertArrayEquals(key.bytes, restarted.unlock(otherPassphrase).key.bytes)

        clock.advance(LockManager.PROOF_VALIDITY_MILLIS + 1)
        assertThrows(ProofExpiredException::class.java) { restarted.changePassphrase(proof, key, "yet another passphrase".toCharArray()) }
    }

    @Test
    fun repeatedFailuresAreThrottledAndTheCountSurvivesARestart() {
        setUp()
        var m = manager()
        m.start()
        repeat(4) { assertEquals(PassphraseResult.Wrong(0), m.checkPassphrase(otherPassphrase.toCharArray())) }
        assertEquals(PassphraseResult.Wrong(30_000), m.checkPassphrase(otherPassphrase.toCharArray()))
        assertEquals(PassphraseResult.Throttled(30_000), m.checkPassphrase(passphrase.toCharArray()))

        // Killing the app does not reset it.
        m = manager()
        m.start()
        clock.advance(10_000)
        assertEquals(PassphraseResult.Throttled(20_000), m.checkPassphrase(passphrase.toCharArray()))
        assertEquals(5, LockConfigStore(configFile).read()!!.failedAttempts)

        clock.advance(20_000)
        assertEquals("the next failure doubles the delay", PassphraseResult.Wrong(60_000), m.checkPassphrase(otherPassphrase.toCharArray()))
        clock.advance(60_000)
        m.unlock(passphrase)
        assertEquals("a success resets the count", 0, LockConfigStore(configFile).read()!!.failedAttempts)
        assertEquals(0L, m.backoffRemainingMillis())
    }

    @Test
    fun theDelayDoublesFromThirtySecondsUpToAnHourAndThenStays() {
        setUp()
        val m = manager()
        m.start()
        repeat(4) { assertEquals(PassphraseResult.Wrong(0), m.checkPassphrase(otherPassphrase.toCharArray())) }
        for (expected in listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 960_000L, 1_920_000L, 3_600_000L, 3_600_000L)) {
            assertEquals(PassphraseResult.Wrong(expected), m.checkPassphrase(otherPassphrase.toCharArray()))
            assertEquals(PassphraseResult.Throttled(expected), m.checkPassphrase(passphrase.toCharArray()))
            clock.advance(expected)
        }
        assertTrue("there is no wipe: the right passphrase still opens", m.checkPassphrase(passphrase.toCharArray()) is PassphraseResult.Success)
    }

    @Test
    fun anAttemptIsCountedBeforeItIsChecked() {
        setUp()
        val m = manager()
        m.start()
        // The app is killed while the key derivation runs.
        vault.onBind = { throw SimulatedCrash() }
        assertThrows(SimulatedCrash::class.java) { m.checkPassphrase(passphrase.toCharArray()) }
        assertEquals(1, LockConfigStore(configFile).read()!!.failedAttempts)
    }

    @Test
    fun aClockBeforeTheEpochCannotMakeTheConfigurationUnreadable() {
        setUp()
        val m = manager()
        m.start()
        clock.wall = -5_000
        assertEquals(PassphraseResult.Wrong(0), m.checkPassphrase(otherPassphrase.toCharArray()))
        assertTrue(LockConfigStore(configFile).read()!!.isValid(minIterations = 1))
        assertEquals(StartResult.Locked, manager().start())
    }

    // --- Fingerprint unlock ---------------------------------------------------------------------

    @Test
    fun anExpiredProofCannotChangeFingerprintUnlock() {
        setUp()
        val m = manager()
        m.start()
        val proof = m.unlock(passphrase).proof
        clock.advance(LockManager.PROOF_VALIDITY_MILLIS + 1)
        assertThrows(ProofExpiredException::class.java) { m.prepareBiometricEnrollment(proof) }
        assertThrows(ProofExpiredException::class.java) { m.disableBiometric(proof) }
        assertTrue(vault.biometricKeys.isEmpty())
    }

    @Test
    fun theFingerprintUnwrapsTheKeyAndThePassphraseStillDoes() {
        val key = setUp()
        val m = manager()
        m.start()
        val proof = m.unlock(passphrase).proof
        m.completeBiometricEnrollment(proof, key, m.prepareBiometricEnrollment(proof))
        assertNotNull(LockConfigStore(configFile).read()!!.biometric)

        val restarted = manager()
        assertEquals(StartResult.Locked, restarted.start())
        val cipher = (restarted.biometricUnlockCipher() as BiometricCipherResult.Ready).cipher
        assertArrayEquals(key.bytes, restarted.unlockWithBiometric(cipher)!!.bytes)
        assertArrayEquals(key.bytes, restarted.unlock(passphrase).key.bytes)
    }

    @Test
    fun anInvalidatedFingerprintKeyIsRemovedQuietlyAndThePassphraseStillWorks() {
        val key = setUp()
        val m = manager()
        m.start()
        val proof = m.unlock(passphrase).proof
        m.completeBiometricEnrollment(proof, key, m.prepareBiometricEnrollment(proof))

        vault.biometricInvalidated = true // e.g. a new fingerprint was enrolled
        val restarted = manager()
        restarted.start()
        assertEquals(BiometricCipherResult.Invalidated, restarted.biometricUnlockCipher())
        val config = LockConfigStore(configFile).read()!!
        assertNull(config.biometric)
        assertTrue("re-enabling is offered", config.fingerprintInvalidated)
        assertNull(vault.biometric)
        assertEquals(BiometricCipherResult.NotEnabled, restarted.biometricUnlockCipher())

        val again = restarted.unlock(passphrase)
        assertArrayEquals(key.bytes, again.key.bytes)
        restarted.completeBiometricEnrollment(again.proof, again.key, restarted.prepareBiometricEnrollment(again.proof))
        assertFalse(LockConfigStore(configFile).read()!!.fingerprintInvalidated)
        assertTrue(restarted.biometricUnlockCipher() is BiometricCipherResult.Ready)
    }

    @Test
    fun aFingerprintWrapThatDoesNotOpenIsRemoved() {
        val key = setUp()
        val m = manager()
        m.start()
        val proof = m.unlock(passphrase).proof
        m.completeBiometricEnrollment(proof, key, m.prepareBiometricEnrollment(proof))
        vault.createBiometricKey(m.config!!.biometric!!.keyGeneration) // a different key behind the same alias
        val wrongCipher = (m.biometricUnlockCipher() as BiometricCipherResult.Ready).cipher
        assertNull(m.unlockWithBiometric(wrongCipher))
        assertNull(m.config!!.biometric)
        assertTrue(m.config!!.fingerprintInvalidated)
    }

    @Test
    fun biometricsGoneFromTheDeviceRemovesFingerprintUnlock() {
        val key = setUp()
        val m = manager()
        m.start()
        val proof = m.unlock(passphrase).proof
        m.completeBiometricEnrollment(proof, key, m.prepareBiometricEnrollment(proof))
        m.biometricLost()
        assertNull(m.config!!.biometric)
        assertTrue(m.config!!.fingerprintInvalidated)
        assertTrue(vault.biometricKeys.isEmpty())
        m.dismissFingerprintInvalidated()
        assertFalse(LockConfigStore(configFile).read()!!.fingerprintInvalidated)
    }

    @Test
    fun turningFingerprintUnlockOffNeedsAProofAndDeletesItsKey() {
        val key = setUp()
        val m = manager()
        m.start()
        val proof = m.unlock(passphrase).proof
        m.completeBiometricEnrollment(proof, key, m.prepareBiometricEnrollment(proof))
        m.disableBiometric(proof)
        assertNull(vault.biometric)
        assertEquals(BiometricCipherResult.NotEnabled, m.biometricUnlockCipher())
        assertEquals("the passphrase is all that is left, and it works", StartResult.Locked, manager().start())
    }

    // --- Settings and reset -----------------------------------------------------------------------

    @Test
    fun theLockTimeoutIsOneOfFourChoicesAndPersisted() {
        setUp()
        val m = manager()
        m.start()
        assertEquals("one minute by default", 60, m.config!!.lockTimeoutSeconds)
        assertEquals(listOf(0, 60, 300, 900), LockConfig.TIMEOUT_CHOICES_SECONDS)
        for (seconds in LockConfig.TIMEOUT_CHOICES_SECONDS) {
            m.setLockTimeout(seconds)
            val restarted = manager()
            restarted.start()
            assertEquals(seconds, restarted.config!!.lockTimeoutSeconds)
        }
        assertThrows(IllegalArgumentException::class.java) { m.setLockTimeout(42) }
        assertThrows("nothing to configure before a passphrase exists", IllegalStateException::class.java) {
            LockManager(LockConfigStore(temp.root.resolve("other/lock.json")), vault, clock, encryptedDataExists = { false }).setLockTimeout(0)
        }
    }

    @Test
    fun resetDeletesEveryKeyAndTheConfigurationAndLeadsBackToSetUpWithANewKey() {
        val key = setUp()
        val m = manager()
        m.start()
        val proof = m.unlock(passphrase).proof
        m.completeBiometricEnrollment(proof, key, m.prepareBiometricEnrollment(proof))

        val report = m.reset { true }
        assertTrue(report.complete)
        assertNull(m.config)
        nothingIsPersisted()

        val fresh = manager()
        assertEquals(StartResult.SetupRequired, fresh.start())
        val newKey = fresh.setUp(passphrase.toCharArray()).key
        assertFalse("the same passphrase, but never the same key", newKey.bytes.contentEquals(key.bytes))
    }
}
