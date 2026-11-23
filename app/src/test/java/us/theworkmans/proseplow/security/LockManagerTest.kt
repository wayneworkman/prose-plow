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
        // Set with the accented "e" as one code point; entered with "e" and a combining accent, and the other way round.
        val key = m.setUp("caf\u00E9 au lait".toCharArray()).key
        val restarted = manager()
        restarted.start()
        assertArrayEquals(key.bytes, restarted.unlock("cafe\u0301 au lait").key.bytes)
        assertArrayEquals(key.bytes, restarted.unlock("caf\u00E9 au lait").key.bytes)
        assertEquals(PassphraseResult.Wrong(0), restarted.checkPassphrase("cafe au lait".toCharArray()))

        val proof = restarted.unlock("caf\u00E9 au lait").proof
        restarted.changePassphrase(proof, key, "nai\u0308ve passphrase".toCharArray())
        assertArrayEquals(key.bytes, manager().also { it.start() }.unlock("na\u00EFve passphrase").key.bytes)
    }

    @Test
    fun thePassphraseArrayIsLeftForItsOwnerToWipe() {
        val m = manager()
        m.start()
        val typed = "cafe\u0301 au lait".toCharArray()
        m.setUp(typed)
        assertEquals("cafe\u0301 au lait", String(typed))
        val again = "cafe\u0301 au lait".toCharArray()
        m.checkPassphrase(again)
        assertEquals("cafe\u0301 au lait", String(again))
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
        assertArrayEquals(key.bytes, restarted.unlockWithBiometric(cipher).keyOrNull()!!.bytes)
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
        assertEquals(BiometricUnlockResult.Removed, m.unlockWithBiometric(wrongCipher))
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

    // --- The time limit of fingerprint unlock --------------------------------------------------------

    private val hourMillis = LockConfig.DEFAULT_FINGERPRINT_PERIOD_SECONDS * 1000L

    /** Set up with fingerprint unlock; returns the data key. The period starts at the passphrase unlock that turned it on. */
    private fun enrolled(): DataKey {
        val key = setUp()
        val m = manager()
        m.start()
        val proof = m.unlock(passphrase).proof
        m.completeBiometricEnrollment(proof, key, m.prepareBiometricEnrollment(proof))
        return key
    }

    /** A whole fingerprint unlock: the cipher, then the unwrap; null if there was no cipher. */
    private fun LockManager.fingerprint(): BiometricUnlockResult? =
        (biometricUnlockCipher() as? BiometricCipherResult.Ready)?.let { unlockWithBiometric(it.cipher) }

    /** Fingerprint unlock is still set up, in memory and on disk, with its key, and not marked as invalidated. */
    private fun assertStillSetUp(m: LockManager) {
        assertNotNull(m.config!!.biometric)
        assertFalse(m.config!!.fingerprintInvalidated)
        val stored = LockConfigStore(configFile).read()!!
        assertNotNull(stored.biometric)
        assertFalse(stored.fingerprintInvalidated)
        assertEquals(1, vault.biometricKeys.size)
    }

    /**
     * Inside the period the fingerprint works, and every fingerprint unlock starts it again, so
     * ordinary use keeps it working; an hour with no unlock and it is refused without anything
     * being removed. Catches a period that does not end, one that a fingerprint unlock does not
     * restart, and a refusal that removes fingerprint unlock.
     */
    @Test
    fun theFingerprintWorksForThePeriodAfterTheLastUnlockAndThenOnlyThePassphraseDoes() {
        val key = enrolled()
        val m = manager()
        m.start()
        clock.advance(hourMillis - 1)
        assertArrayEquals(key.bytes, m.fingerprint()!!.keyOrNull()!!.bytes)
        assertEquals(FingerprintPeriodStart(clock.elapsed, 1), LockConfigStore(configFile).read()!!.fingerprintPeriodStart)
        clock.advance(hourMillis - 1)
        assertTrue("started again by the fingerprint unlock", m.fingerprintPeriodRunning())
        assertTrue(m.fingerprint() is BiometricUnlockResult.Unlocked)

        clock.advance(hourMillis)
        assertFalse(m.fingerprintPeriodRunning())
        assertEquals(BiometricCipherResult.PeriodOver, m.biometricUnlockCipher())
        assertStillSetUp(m)
        assertEquals("a new process says the same", BiometricCipherResult.PeriodOver, manager().also { it.start() }.biometricUnlockCipher())
    }

    /**
     * The right passphrase starts a new period. Catches a passphrase unlock that leaves the
     * fingerprint locked out after the period ran out.
     */
    @Test
    fun aPassphraseUnlockStartsANewPeriodAndTheFingerprintWorksAgain() {
        val key = enrolled()
        val m = manager()
        m.start()
        clock.advance(3 * hourMillis)
        assertEquals(BiometricCipherResult.PeriodOver, m.biometricUnlockCipher())
        assertTrue("a wrong passphrase starts nothing", m.checkPassphrase("not the passphrase".toCharArray()) is PassphraseResult.Wrong)
        assertEquals(BiometricCipherResult.PeriodOver, m.biometricUnlockCipher())
        m.unlock(passphrase).key.destroy()
        assertEquals(FingerprintPeriodStart(clock.elapsed, 1), LockConfigStore(configFile).read()!!.fingerprintPeriodStart)
        assertArrayEquals(key.bytes, m.fingerprint()!!.keyOrNull()!!.bytes)
    }

    /**
     * A restart means the passphrase, whatever the time since boot shows: a boot that has been
     * up for longer than the old one had (the time looks inside the period) and a young one
     * (the time is below the start). Catches the back-off's "what is left on the new clock" rule
     * applied here, and a check of the time alone.
     */
    @Test
    fun aRestartOfThePhoneAsksForThePassphraseWhateverTheTimeSinceBootSays() {
        val key = enrolled()
        val start = LockConfigStore(configFile).read()!!.fingerprintPeriodStart!!

        clock.reboot(upMillis = start.elapsedMillis + 60_000)
        val longUp = manager()
        assertEquals(StartResult.Locked, longUp.start())
        assertEquals(BiometricCipherResult.PeriodOver, longUp.biometricUnlockCipher())
        assertStillSetUp(longUp)

        clock.reboot(upMillis = 5_000)
        val young = manager()
        young.start()
        assertEquals(BiometricCipherResult.PeriodOver, young.biometricUnlockCipher())
        assertStillSetUp(young)

        // The passphrase on this boot, and the fingerprint works again.
        young.unlock(passphrase).key.destroy()
        assertEquals(FingerprintPeriodStart(5_000, 3), LockConfigStore(configFile).read()!!.fingerprintPeriodStart)
        assertArrayEquals(key.bytes, young.fingerprint()!!.keyOrNull()!!.bytes)
    }

    /**
     * When the boot count cannot be read, a restart cannot be ruled out: the passphrase is
     * needed, and an unlock with it stores no start, so the fingerprint stays refused until the
     * count can be read again. Catches an unreadable count taken as "no restart", and an
     * exception from reading it reaching the caller.
     */
    @Test
    fun aBootCountThatCannotBeReadAsksForThePassphraseAndNeverThrows() {
        val key = enrolled()
        val m = manager()
        m.start()
        assertTrue(m.fingerprintPeriodRunning())

        clock.boots = null
        assertEquals(BiometricCipherResult.PeriodOver, m.biometricUnlockCipher())
        clock.boots = 1
        clock.bootCountFailure = SecurityException("not allowed to read the setting")
        assertFalse(m.fingerprintPeriodRunning())
        assertEquals(BiometricCipherResult.PeriodOver, m.biometricUnlockCipher())
        assertStillSetUp(m)

        assertArrayEquals("the passphrase works as always", key.bytes, m.unlock(passphrase).key.bytes)
        assertNull("no start without a boot count", LockConfigStore(configFile).read()!!.fingerprintPeriodStart)
        clock.bootCountFailure = null
        assertEquals("still refused: nothing was started", BiometricCipherResult.PeriodOver, m.biometricUnlockCipher())
        m.unlock(passphrase).key.destroy()
        assertTrue(m.fingerprint() is BiometricUnlockResult.Unlocked)
    }

    /**
     * The prompt can be showing while the period runs out; its success says nothing about the
     * period. The unwrap is refused before the cipher is used, nothing is stored, and
     * fingerprint unlock is neither removed nor marked as invalidated. Catches a check made only
     * when the prompt is shown, and a refusal handled as a lost key.
     */
    @Test
    fun aPeriodThatRunsOutWhileThePromptShowsRefusesTheUnlockAndKeepsFingerprintUnlock() {
        val key = enrolled()
        val m = manager()
        m.start()
        val cipher = (m.biometricUnlockCipher() as BiometricCipherResult.Ready).cipher
        val before = configFile.readText()
        clock.advance(hourMillis)
        assertEquals(BiometricUnlockResult.PeriodOver, m.unlockWithBiometric(cipher))
        assertEquals("nothing was written", before, configFile.readText())
        assertStillSetUp(m)

        m.unlock(passphrase).key.destroy()
        assertArrayEquals(key.bytes, m.fingerprint()!!.keyOrNull()!!.bytes)
    }

    /**
     * A lock starts the period again from the moment it happened. A start already stored for a
     * later moment of this boot (an unlock that came first) is kept; one from another boot is
     * replaced. Nothing is written without fingerprint unlock. Catches a lock that does not move
     * the start, and a late lock that takes a later unlock's start back.
     */
    @Test
    fun aLockStartsThePeriodAgainButNeverMovesItBack() {
        enrolled()
        val m = manager()
        m.start()
        clock.advance(50 * 60_000L)
        val lockedAt = clock.elapsed
        clock.advance(1_000)
        m.startFingerprintPeriod(lockedAt)
        assertEquals(FingerprintPeriodStart(lockedAt, 1), LockConfigStore(configFile).read()!!.fingerprintPeriodStart)
        clock.advance(50 * 60_000L)
        assertTrue("an hour and 40 minutes after the unlock, 50 minutes after the lock", m.fingerprintPeriodRunning())

        // An unlock recorded after the lock was decided is not undone by it.
        m.unlock(passphrase).key.destroy()
        val unlockedAt = clock.elapsed
        m.startFingerprintPeriod(unlockedAt - 2_000)
        assertEquals(FingerprintPeriodStart(unlockedAt, 1), LockConfigStore(configFile).read()!!.fingerprintPeriodStart)

        // A start from before a restart is replaced.
        clock.reboot(upMillis = unlockedAt + 10_000)
        val restarted = manager()
        restarted.start()
        restarted.startFingerprintPeriod(clock.elapsed)
        assertEquals(FingerprintPeriodStart(clock.elapsed, 2), LockConfigStore(configFile).read()!!.fingerprintPeriodStart)

        // Without a boot count no start is stored at all: run out.
        clock.boots = null
        clock.advance(1_000)
        restarted.startFingerprintPeriod(clock.elapsed)
        assertNull(LockConfigStore(configFile).read()!!.fingerprintPeriodStart)
        clock.boots = 2

        // Without fingerprint unlock nothing is written.
        val proof = restarted.unlock(passphrase).proof
        restarted.disableBiometric(proof)
        val before = configFile.readText()
        clock.advance(1_000)
        restarted.startFingerprintPeriod(clock.elapsed)
        assertEquals(before, configFile.readText())
    }

    /** Catches a choice that is not stored, a value outside the four accepted, and a choice that does not apply. */
    @Test
    fun theFingerprintPeriodIsOneOfFourChoicesAndPersisted() {
        enrolled()
        val m = manager()
        m.start()
        assertEquals("an hour by default", 3_600, m.config!!.fingerprintPeriodSeconds)
        for (seconds in LockConfig.FINGERPRINT_PERIOD_CHOICES_SECONDS) {
            m.setFingerprintPeriod(seconds)
            val restarted = manager()
            restarted.start()
            assertEquals(seconds, restarted.config!!.fingerprintPeriodSeconds)
        }
        for (bad in listOf(0, -1, 60, 42, Int.MAX_VALUE)) {
            assertThrows("$bad", IllegalArgumentException::class.java) { m.setFingerprintPeriod(bad) }
        }
        assertEquals(86_400, LockConfigStore(configFile).read()!!.fingerprintPeriodSeconds)

        m.setFingerprintPeriod(15 * 60)
        m.unlock(passphrase).key.destroy()
        clock.advance(15 * 60_000L - 1)
        assertTrue(m.fingerprintPeriodRunning())
        clock.advance(1)
        assertEquals(BiometricCipherResult.PeriodOver, m.biometricUnlockCipher())
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
