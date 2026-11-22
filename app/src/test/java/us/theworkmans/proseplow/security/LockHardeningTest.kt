package us.theworkmans.proseplow.security

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64
import javax.crypto.Cipher
import kotlin.random.Random

/**
 * The hard edges of the lock core: the back-off across reboots, validation of the stored
 * configuration, rotation of the binding and biometric keys (with stops at the points a process
 * can die), empty input, and a reset that cannot delete every key.
 */
class LockHardeningTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val vault = SoftwareKeyVault()
    private val clock = FakeClock()
    private val configFile: File get() = temp.root.resolve("security/lock.json")
    private val store get() = LockConfigStore(configFile)

    private val passphrase = "correct horse battery"
    private val otherPassphrase = "a different one, longer"
    private val wrongPassphrase = "definitely not it at all"

    private fun manager(minIterations: Int = 1) =
        LockManager(LockConfigStore(configFile), vault, clock, iterations = 1_000, minIterations = minIterations, encryptedDataExists = { false })

    private fun LockManager.unlock(text: String): PassphraseResult.Success = checkPassphrase(text.toCharArray()) as PassphraseResult.Success

    /** First run with [passphrase]; returns the data key. */
    private fun setUp(): DataKey {
        val m = manager()
        assertEquals(StartResult.SetupRequired, m.start())
        return m.setUp(passphrase.toCharArray()).key
    }

    /** A freshly started, unlocked manager (with a fresh proof). */
    private fun unlocked(): Pair<LockManager, PassphraseResult.Success> {
        val m = manager()
        assertEquals(StartResult.Locked, m.start())
        return m to m.unlock(passphrase)
    }

    private fun opens(text: String): Boolean {
        val m = manager()
        if (m.start() != StartResult.Locked) return false
        return m.checkPassphrase(text.toCharArray()) is PassphraseResult.Success
    }

    private fun fail(m: LockManager, times: Int) = repeat(times) { assertTrue(m.checkPassphrase(wrongPassphrase.toCharArray()) is PassphraseResult.Wrong) }

    // --- The back-off across reboots and clock changes ------------------------------------------

    @Test
    fun rebootingAndSettingTheClockForwardDoesNotSkipTheWait() {
        setUp()
        val m = manager()
        m.start()
        fail(m, 5)
        assertEquals(30_000L, m.backoffRemainingMillis())

        clock.advance(10_000)
        clock.reboot(downMillis = 0, upMillis = 1_000) // time since boot now below the stored anchor
        clock.wall += 24 * 3_600_000L // and the clock set a day ahead

        val after = manager()
        assertEquals(StartResult.Locked, after.start())
        assertEquals("the full wait starts again from the new boot", 30_000L, after.backoffRemainingMillis())
        val stored = store.read()!!
        assertEquals("the new anchor is stored", clock.elapsed, stored.lastFailureElapsedMillis)
        assertEquals(5, stored.failedAttempts)
        assertEquals(PassphraseResult.Throttled(30_000), after.checkPassphrase(passphrase.toCharArray()))

        // Killing the app now does not restart it again, nor end it.
        clock.advance(20_000)
        val again = manager()
        again.start()
        assertEquals(10_000L, again.backoffRemainingMillis())
        clock.advance(10_000)
        assertTrue(again.checkPassphrase(passphrase.toCharArray()) is PassphraseResult.Success)
    }

    @Test
    fun aRebootAfterALongUptimeStillDoesNotShortenTheWait() {
        setUp()
        val m = manager()
        m.start()
        fail(m, 5) // anchored at elapsed 50 s
        clock.reboot(downMillis = 0, upMillis = 60_000) // the new boot has run longer than the old one had
        clock.wall += 3_600_000L
        val after = manager()
        after.start()
        // Counted from the stored anchor on the new boot's clock: 20 s more, never less than due.
        assertEquals(20_000L, after.backoffRemainingMillis())
    }

    /**
     * Random reboots, clock jumps (both ways) and app restarts: the time left is never less than
     * the delay minus the real time since the failure.
     */
    @Test
    fun noCombinationOfRebootsAndClockChangesShortensTheWait() {
        val random = Random(42)
        repeat(300) {
            var config = testConfig(
                failedAttempts = 5 + random.nextInt(6),
                lastFailureWallMillis = clock.wall,
                lastFailureElapsedMillis = clock.elapsed,
            )
            val delay = Backoff.delayAfter(config.failedAttempts)
            var realSinceFailure = 0L
            repeat(20) {
                when (random.nextInt(4)) {
                    0 -> random.nextLong(0, delay).let { clock.advance(it); realSinceFailure += it }
                    1 -> {
                        val down = random.nextLong(0, delay)
                        val up = random.nextLong(0, delay * 2)
                        clock.reboot(down, up)
                        realSinceFailure += down + up
                    }
                    2 -> clock.wall += random.nextLong(-delay * 4, delay * 40)
                    else -> Unit
                }
                Backoff.reanchor(config, clock)?.let { config = it }
                val remaining = Backoff.remaining(config, clock)
                assertTrue("remaining $remaining, real $realSinceFailure of $delay", remaining >= delay - realSinceFailure)
                assertTrue(remaining <= delay)
            }
        }
    }

    // --- The stored configuration is validated ------------------------------------------------

    private fun editStored(edit: (MutableMap<String, Any?>) -> Unit) {
        val root = Json.parseToJsonElement(configFile.readText()).jsonObject.toMutableMap<String, Any?>()
        val pass = (root["passphrase"] as? JsonObject)?.toMutableMap<String, Any?>()
        if (pass != null) root["passphrase"] = pass
        edit(root)
        configFile.writeText(toJson(root))
    }

    private fun toJson(value: Any?): String = when (value) {
        null -> "null"
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":${toJson(v)}" }
        is String -> "\"$value\""
        is Number, is Boolean -> value.toString()
        else -> value.toString() // JsonElement
    }

    @Suppress("UNCHECKED_CAST") // The map editStored made of the passphrase object.
    private fun MutableMap<String, Any?>.pass() = this["passphrase"] as MutableMap<String, Any?>

    private fun b64(size: Int) = Base64.getEncoder().encodeToString(ByteArray(size) { 1 })

    @Test
    fun configurationValuesOutOfRangeAreUnrecoverableWithoutAnAttempt() {
        val cases: List<Pair<String, (MutableMap<String, Any?>) -> Unit>> = listOf(
            "another version" to { it["version"] = 2 },
            "no version" to { it.remove("version") },
            "no passphrase wrap" to { it.remove("passphrase") },
            "a passphrase wrap of null" to { it["passphrase"] = null },
            "iterations below the minimum" to { it.pass()["iterations"] = 599_999 },
            "iterations above the ceiling" to { it.pass()["iterations"] = PassphraseWrap.MAX_ITERATIONS + 1 },
            "no iterations" to { it.pass()["iterations"] = 0 },
            "negative iterations" to { it.pass()["iterations"] = -5 },
            "a short salt" to { it.pass()["salt"] = b64(8) },
            "a salt that is not Base64" to { it.pass()["salt"] = "***" },
            "an unknown kdf" to { it.pass()["kdf"] = "PBKDF2-HMAC-SHA1" },
            "no kdf" to { it.pass().remove("kdf") },
            "a wrapped key that is not Base64" to { it.pass()["wrappedKey"] = "not base64!" },
            "a wrapped key of the wrong size" to { it.pass()["wrappedKey"] = b64(40) },
            "a wrapped key without the envelope header" to { it.pass()["wrappedKey"] = b64(PassphraseWrap.WRAPPED_SIZE) },
            "a negative binding generation" to { it.pass()["bindingGeneration"] = -1 },
            "a binding generation of zero" to { it.pass()["bindingGeneration"] = 0 },
            "no binding generation" to { it.pass().remove("bindingGeneration") },
            "a binding generation above the maximum" to { it.pass()["bindingGeneration"] = KeyId.MAX_GENERATION + 1 },
            "a binding generation above the highest" to { it["highestKeyGeneration"] = 1; it.pass()["bindingGeneration"] = 2 },
            "a damaged fingerprint wrap" to { it["biometric"] = mapOf("wrappedKey" to b64(10), "keyGeneration" to 1) },
            "a fingerprint wrap without its generation" to { it["biometric"] = mapOf("wrappedKey" to b64(LockConfig.KEY_WRAP_SIZE)) },
            "a fingerprint generation above the maximum" to {
                it["biometric"] = mapOf("wrappedKey" to b64(LockConfig.KEY_WRAP_SIZE), "keyGeneration" to KeyId.MAX_GENERATION + 1)
            },
            "a fingerprint generation above the highest" to {
                it["biometric"] = mapOf("wrappedKey" to b64(LockConfig.KEY_WRAP_SIZE), "keyGeneration" to 5)
            },
            "a highest generation above the maximum" to { it["highestKeyGeneration"] = KeyId.MAX_GENERATION + 1 },
            "a highest generation of Int.MAX_VALUE" to { it["highestKeyGeneration"] = Int.MAX_VALUE },
            "a negative highest generation" to { it["highestKeyGeneration"] = -1 },
            "no highest generation" to { it.remove("highestKeyGeneration") },
            "a negative failure count" to { it["failedAttempts"] = -3 },
            "a negative failure time" to { it["lastFailureWallMillis"] = -1 },
            "a negative failure time since boot" to { it["lastFailureElapsedMillis"] = -1 },
            "an unknown lock timeout" to { it["lockTimeoutSeconds"] = 42 },
            "a lock timeout that never locks" to { it["lockTimeoutSeconds"] = -1 },
        )
        for ((name, edit) in cases) {
            temp.root.resolve("security").deleteRecursively()
            vault.aliases.clear()
            setUp()
            editStored { it.pass()["iterations"] = 600_000 } // valid for the production minimum
            assertEquals("baseline for $name", StartResult.Locked, manager(minIterations = PassphraseWrap.MIN_ITERATIONS).start())
            editStored(edit)
            val before = configFile.readText()
            val m = manager(minIterations = PassphraseWrap.MIN_ITERATIONS)
            assertEquals(name, StartResult.Unrecoverable(UnrecoverableReason.CONFIG_UNREADABLE), m.start())
            assertEquals("$name: nothing written", before, configFile.readText())
            assertEquals("$name: no key deleted", 1, vault.bindingKeys.size)
        }
    }

    @Test
    fun fieldsThisVersionDoesNotKnowAreIgnored() {
        val key = setUp()
        // Whatever a later version may add, and whatever a field of that name once meant elsewhere:
        // nothing in the file can switch the lock off.
        editStored {
            it["someLaterSetting"] = 3
            it["lockEnabled"] = false
            it["deviceWrap"] = b64(LockConfig.KEY_WRAP_SIZE)
        }
        val (_, success) = unlocked()
        assertArrayEquals(key.bytes, success.key.bytes)
    }

    @Test
    fun everyConfigurationTheManagerWritesIsOneItAccepts() {
        val key = setUp()
        fun stored() = store.read()!!.also { assertTrue(it.toString(), it.isValid(minIterations = 1)) }
        stored()
        val (m, success) = unlocked()
        stored()
        m.changePassphrase(success.proof, key, otherPassphrase.toCharArray())
        stored()
        m.completeBiometricEnrollment(success.proof, key, m.prepareBiometricEnrollment(success.proof))
        assertEquals(stored().biometric!!.keyGeneration, stored().highestKeyGeneration)
        m.setLockTimeout(0)
        stored()
        fail(m, 5)
        assertEquals(5, stored().failedAttempts)
        m.biometricLost()
        stored()
        m.dismissFingerprintInvalidated()
        stored()
        m.completeBiometricEnrollment(success.proof, key, m.prepareBiometricEnrollment(success.proof))
        m.disableBiometric(success.proof)
        stored()
    }

    // --- Empty and over-long input --------------------------------------------------------------

    @Test
    fun emptyOrOverLongInputIsRefusedWithoutCountingAnAttempt() {
        setUp()
        val m = manager()
        m.start()
        assertEquals(PassphraseResult.Rejected(PassphraseProblem.EMPTY), m.checkPassphrase(CharArray(0)))
        val tooLong = CharArray(PassphraseRules.MAX_LENGTH + 1) { 'a' }
        assertEquals(PassphraseResult.Rejected(PassphraseProblem.TOO_LONG), m.checkPassphrase(tooLong))
        assertEquals(0, store.read()!!.failedAttempts)
        assertEquals("the longest allowed input is checked (and counted)", PassphraseResult.Wrong(0), m.checkPassphrase(CharArray(PassphraseRules.MAX_LENGTH) { 'a' }))
        assertEquals(1, store.read()!!.failedAttempts)
        assertEquals(PassphraseProblem.TOO_LONG, PassphraseRules.check(tooLong))
    }

    // --- The binding key is rotated with every passphrase ----------------------------------------

    @Test
    fun anOlderCopyOfTheConfigurationNoLongerOpensAfterAPassphraseChange() {
        val key = setUp()
        val oldCopy = configFile.readBytes()
        val (m, success) = unlocked()
        m.changePassphrase(success.proof, key, otherPassphrase.toCharArray())
        val newCopy = configFile.readBytes()
        assertNotEquals(String(oldCopy), String(newCopy))
        assertEquals("only the new binding key is left", 1, vault.bindingKeys.size)

        configFile.writeBytes(oldCopy) // restored by someone who kept a copy
        assertEquals(StartResult.Unrecoverable(UnrecoverableReason.KEY_LOST), manager().start())
        assertEquals("starting with the old copy deletes no key", 1, vault.bindingKeys.size)

        configFile.writeBytes(newCopy)
        assertTrue(opens(otherPassphrase))
        assertFalse(opens(passphrase))
    }

    @Test
    fun aChangeStoppedRightAfterTheNewKeyWasMadeKeepsTheOldPassphrase() {
        val key = setUp()
        val (m, success) = unlocked()
        vault.afterCreate = { if (it.kind == KeyKind.BINDING) throw SimulatedCrash() }
        assertThrows(SimulatedCrash::class.java) { m.changePassphrase(success.proof, key, otherPassphrase.toCharArray()) }
        vault.afterCreate = {}
        assertEquals("the new key is left over", 2, vault.bindingKeys.size)
        assertTrue(opens(passphrase))
        assertFalse(opens(otherPassphrase))
        assertEquals("and deleted at the next start", 1, vault.bindingKeys.size)
    }

    @Test
    fun aChangeStoppedWhileTheNewWrapWasDerivedKeepsTheOldPassphrase() {
        val key = setUp()
        val (m, success) = unlocked()
        val oldGeneration = store.read()!!.passphrase.bindingGeneration
        vault.onBind = { if (it == oldGeneration + 1) throw SimulatedCrash() }
        assertThrows(SimulatedCrash::class.java) { m.changePassphrase(success.proof, key, otherPassphrase.toCharArray()) }
        vault.onBind = {}
        assertEquals(oldGeneration, store.read()!!.passphrase.bindingGeneration)
        assertTrue(opens(passphrase))
        assertFalse(opens(otherPassphrase))
        assertEquals(listOf(KeyId(KeyKind.BINDING, oldGeneration)), vault.bindingKeys)
    }

    @Test
    fun aChangeWhoseOldKeyCannotBeDeletedStillTakesEffectAndTheNextStartCleansUp() {
        val key = setUp()
        val (m, success) = unlocked()
        val oldKey = KeyId(KeyKind.BINDING, store.read()!!.passphrase.bindingGeneration)
        vault.undeletable += oldKey
        m.changePassphrase(success.proof, key, otherPassphrase.toCharArray())
        vault.undeletable.clear()
        assertTrue(oldKey in vault.keys())
        assertTrue(opens(otherPassphrase))
        assertFalse(opens(passphrase))
        assertFalse("the old key is deleted at the next start", oldKey in vault.keys())
    }

    @Test
    fun aChangeWhoseConfigurationCannotBeWrittenKeepsTheOldPassphrase() {
        val key = setUp()
        val (m, success) = unlocked()
        configFile.parentFile!!.setWritable(false)
        try {
            assertThrows(Exception::class.java) { m.changePassphrase(success.proof, key, otherPassphrase.toCharArray()) }
        } finally {
            configFile.parentFile!!.setWritable(true)
        }
        assertEquals("the new key was removed again", 1, vault.bindingKeys.size)
        assertTrue(opens(passphrase))
    }

    // --- Fingerprint re-enrolment ------------------------------------------------------------------

    private fun enrolled(): DataKey {
        val key = setUp()
        val (m, success) = unlocked()
        m.completeBiometricEnrollment(success.proof, key, m.prepareBiometricEnrollment(success.proof))
        return key
    }

    private fun fingerprintOpens(expected: DataKey): Boolean {
        val m = manager()
        m.start()
        val cipher = (m.biometricUnlockCipher() as? BiometricCipherResult.Ready)?.cipher ?: return false
        return m.unlockWithBiometric(cipher)?.bytes?.contentEquals(expected.bytes) == true
    }

    @Test
    fun cancellingARenewedEnrolmentKeepsTheWorkingFingerprintUnlock() {
        val key = enrolled()
        val before = store.read()!!.biometric
        val (m, success) = unlocked()
        m.prepareBiometricEnrollment(success.proof)
        assertEquals("the new key sits beside the old one", 2, vault.biometricKeys.size)
        m.cancelBiometricEnrollment()
        assertEquals(before, store.read()!!.biometric)
        assertEquals(1, vault.biometricKeys.size)
        assertTrue(fingerprintOpens(key))
    }

    @Test
    fun aRenewedEnrolmentStoppedBeforeItsWrapWasStoredKeepsTheOldOne() {
        val key = enrolled()
        val (m, success) = unlocked()
        m.prepareBiometricEnrollment(success.proof) // then the app is killed with the prompt open
        assertTrue(fingerprintOpens(key))
        assertEquals("the unused key is deleted at start", 1, vault.biometricKeys.size)
    }

    @Test
    fun aCompletedRenewalReplacesTheOldKey() {
        val key = enrolled()
        val oldGeneration = store.read()!!.biometric!!.keyGeneration
        val (m, success) = unlocked()
        val oldKey = KeyId(KeyKind.BIOMETRIC, oldGeneration)
        vault.undeletable += oldKey // the old key outlives the renewal for now
        m.completeBiometricEnrollment(success.proof, key, m.prepareBiometricEnrollment(success.proof))
        vault.undeletable.clear()
        assertNotEquals(oldGeneration, store.read()!!.biometric!!.keyGeneration)
        assertTrue(fingerprintOpens(key))
        assertEquals(listOf(KeyId(KeyKind.BIOMETRIC, store.read()!!.biometric!!.keyGeneration)), vault.biometricKeys)
    }

    @Test
    fun completingWithoutAPreparedEnrolmentFails() {
        val key = enrolled()
        val (m, success) = unlocked()
        val cipher = m.prepareBiometricEnrollment(success.proof)
        m.cancelBiometricEnrollment()
        assertThrows(IllegalStateException::class.java) { m.completeBiometricEnrollment(success.proof, key, cipher) }
        assertTrue(fingerprintOpens(key))
    }

    @Test
    fun aPassingFailureAfterTheFingerprintPromptKeepsFingerprintUnlock() {
        val key = enrolled()
        val m = manager()
        m.start()
        assertTrue(m.biometricUnlockCipher() is BiometricCipherResult.Ready)
        // A cipher the platform did not leave usable (here: never initialised) fails with IllegalStateException.
        val unusable = Cipher.getInstance("AES/GCM/NoPadding")
        assertNull(m.unlockWithBiometric(unusable))
        assertTrue("not removed", m.config!!.biometric != null)
        assertTrue(fingerprintOpens(key))
    }

    // --- A reset that cannot delete every key ----------------------------------------------------

    @Test
    fun aResetTriesEveryKeyAndEveryLaterStartTriesAgain() {
        enrolled()
        val (m, _) = unlocked()
        val stuck = vault.bindingKeys.single()
        vault.undeletable += stuck
        val report = m.reset { true }
        assertFalse("the failure is reported", report.keysDeleted)
        assertFalse(report.complete)
        assertTrue(report.dataDeleted && report.configDeleted)
        assertEquals("every other key was deleted", setOf(stuck), vault.keys())
        assertFalse(configFile.exists())

        // Still stuck at the next start: set-up is offered anyway, and the new key avoids the stuck one.
        val fresh = manager()
        assertEquals(StartResult.SetupRequired, fresh.start())
        assertTrue(stuck in vault.keys())
        fresh.setUp(passphrase.toCharArray())
        assertTrue(store.read()!!.passphrase.bindingGeneration > stuck.generation)
        assertTrue(stuck in vault.keys())
        assertTrue(opens(passphrase))

        // Once it can be deleted, the next start does.
        vault.undeletable.clear()
        manager().start()
        assertFalse(stuck in vault.keys())
        assertTrue(opens(passphrase))
    }

    // --- The highest key generation ----------------------------------------------------------------

    @Test
    fun theLastGenerationStillWorksAndAnOldCopyNoLongerOpensAfterIt() {
        val key = setUp()
        editStored { it["highestKeyGeneration"] = KeyId.MAX_GENERATION - 1 }
        val oldCopy = configFile.readBytes()
        val (m, success) = unlocked()
        m.changePassphrase(success.proof, key, otherPassphrase.toCharArray())
        val last = KeyId(KeyKind.BINDING, KeyId.MAX_GENERATION)
        assertEquals(KeyId.MAX_GENERATION, store.read()!!.passphrase.bindingGeneration)
        assertEquals("the newest key is visible through its alias", listOf(last), vault.bindingKeys)
        assertTrue(last in vault.keys())
        configFile.writeBytes(oldCopy)
        assertEquals(StartResult.Unrecoverable(UnrecoverableReason.KEY_LOST), manager().start())
    }

    @Test
    fun noGenerationLeftFailsCleanlyWithNothingChanged() {
        val key = enrolled()
        editStored { it["highestKeyGeneration"] = KeyId.MAX_GENERATION } // in range, so accepted
        val (m, success) = unlocked()
        val afterUnlock = configFile.readText()
        val keysBefore = vault.keys()
        assertThrows(KeyGenerationsExhaustedException::class.java) { m.changePassphrase(success.proof, key, otherPassphrase.toCharArray()) }
        assertThrows(KeyGenerationsExhaustedException::class.java) { m.prepareBiometricEnrollment(success.proof) }
        assertEquals(afterUnlock, configFile.readText())
        assertEquals(keysBefore, vault.keys())
        assertTrue(opens(passphrase))
        assertTrue(fingerprintOpens(key))
    }

    @Test
    fun aStoredGenerationCanNeverNameAnAliasTheVaultCannotList() {
        // Formatting and parsing agree up to the maximum and nothing above it can be made.
        for (generation in listOf(1, 9, 10, 99_999_999, KeyId.MAX_GENERATION - 1, KeyId.MAX_GENERATION)) {
            for (kind in KeyKind.entries) assertEquals(KeyId(kind, generation), KeyId.parse(KeyId(kind, generation).alias))
        }
        assertThrows(IllegalArgumentException::class.java) { KeyId(KeyKind.BINDING, KeyId.MAX_GENERATION + 1) }
        assertThrows(IllegalArgumentException::class.java) { KeyId(KeyKind.BINDING, 0) }
        assertNull(KeyId.parse("proseplow.passphrase.binding.1000000000"))
        assertNull(KeyId.parse("proseplow.passphrase.binding.9999999999"))
        assertFalse(LockConfig.isKeyGeneration(KeyId.MAX_GENERATION + 1))
        assertFalse(LockConfig.isKeyGeneration(0))
        assertTrue(LockConfig.isKeyGeneration(KeyId.MAX_GENERATION))
    }

    // --- Key aliases -------------------------------------------------------------------------------

    @Test
    fun keyAliasesRoundTripAndKeepTheirPermanentForm() {
        for (id in listOf(KeyId(KeyKind.BINDING, 1), KeyId(KeyKind.BINDING, 7), KeyId(KeyKind.BIOMETRIC, 123))) {
            assertEquals(id, KeyId.parse(id.alias))
            assertTrue(id.alias.startsWith(KeyId.ALIAS_PREFIX))
        }
        assertEquals("proseplow.", KeyId.ALIAS_PREFIX)
        assertEquals("proseplow.passphrase.binding.1", KeyId(KeyKind.BINDING, 1).alias)
        assertEquals("proseplow.dek.biometric.3", KeyId(KeyKind.BIOMETRIC, 3).alias)
        for (alias in listOf(
            "proseplow.passphrase.binding",
            "proseplow.passphrase.binding.",
            "proseplow.passphrase.binding.0",
            "proseplow.passphrase.binding.01",
            "proseplow.dek.biometric.x",
            "proseplow.dek.device",
            "other.key",
        )) {
            assertNull(alias, KeyId.parse(alias))
        }
    }

    @Test
    fun aKeyOfAFormThisVersionDoesNotKnowIsStillDeletedByAReset() {
        setUp()
        vault.aliases["proseplow.something.later"] = vault.binding!!
        vault.aliases["someone.elses.key"] = vault.binding!!
        val (m, _) = unlocked()
        assertTrue(m.reset { true }.complete)
        assertEquals("only what does not carry the app's prefix is left", setOf("someone.elses.key"), vault.aliases.keys)
    }
}
