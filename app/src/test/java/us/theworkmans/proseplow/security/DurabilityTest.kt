package us.theworkmans.proseplow.security

import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import javax.crypto.SecretKey

/** The atomic write: its order, what it reports, and what a stop at each step leaves on disk. */
class AtomicFilesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val old = "the old content".toByteArray()
    private val new = "the new content, longer".toByteArray()

    @Test
    fun aWriteGoesThroughItsStepsInOrderAndEndsDurable() {
        val file = temp.root.resolve("file.bin").apply { writeBytes(old) }
        val seen = mutableListOf<Triple<WriteStep, String, String?>>()
        val durable = writeAtomically(file, new, onStep = { step ->
            seen += Triple(step, String(file.readBytes()), tempFileFor(file).takeIf { it.exists() }?.let { String(it.readBytes()) })
        })
        assertTrue(durable)
        assertEquals(
            listOf(
                Triple(WriteStep.TEMP_WRITTEN, String(old), String(new)),
                Triple(WriteStep.TEMP_SYNCED, String(old), String(new)),
                Triple(WriteStep.RENAMED, String(new), null),
                Triple(WriteStep.DIRECTORY_SYNCED, String(new), null),
            ),
            seen,
        )
        assertArrayEquals(new, file.readBytes())
        assertFalse(tempFileFor(file).exists())
    }

    @Test
    fun theDirectoryIsSyncedAfterTheRenameAndBeforeTheWriteReturns() {
        val file = temp.root.resolve("file.bin").apply { writeBytes(old) }
        val events = mutableListOf<String>()
        writeAtomically(
            file,
            new,
            onStep = { events += it.name },
            syncDirectory = { directory ->
                events += "sync ${directory.name}"
                assertArrayEquals("the new content is in place when its directory is synced", new, file.readBytes())
                syncDirectory(directory)
            },
        )
        assertEquals(listOf("TEMP_WRITTEN", "TEMP_SYNCED", "RENAMED", "sync ${temp.root.name}", "DIRECTORY_SYNCED"), events)
    }

    @Test
    fun directoriesMadeForTheFileGetTheirOwnNamesSyncedToo() {
        val file = temp.root.resolve("a/b/file.bin")
        val synced = mutableListOf<File>()
        assertTrue(writeAtomically(file, new, syncDirectory = { synced += it; syncDirectory(it) }))
        assertArrayEquals(new, file.readBytes())
        // The file's directory, then the directories that hold the names of the two new ones.
        assertEquals(listOf(temp.root.resolve("a/b"), temp.root.resolve("a"), temp.root).map { it.absoluteFile }, synced.map { it.absoluteFile })

        synced.clear()
        writeAtomically(file, old, syncDirectory = { synced += it; syncDirectory(it) })
        assertEquals("nothing was made the second time", listOf(temp.root.resolve("a/b").absoluteFile), synced.map { it.absoluteFile })
    }

    @Test
    fun aDirectoryThatCannotBeSyncedIsReportedWithTheContentInPlace() {
        val file = temp.root.resolve("file.bin").apply { writeBytes(old) }
        val steps = mutableListOf<WriteStep>()
        val durable = writeAtomically(file, new, onStep = { steps += it }, syncDirectory = { false })
        assertFalse("not durable", durable)
        assertArrayEquals("but written: every reader sees the new content", new, file.readBytes())
        assertEquals(listOf(WriteStep.TEMP_WRITTEN, WriteStep.TEMP_SYNCED, WriteStep.RENAMED), steps)
    }

    @Test
    fun aWriteThatFailsLeavesTheOldContent() {
        val directory = temp.newFolder("locked")
        val file = directory.resolve("file.bin").apply { writeBytes(old) }
        directory.setWritable(false)
        try {
            assertThrows(IOException::class.java) { writeAtomically(file, new) }
        } finally {
            directory.setWritable(true)
        }
        assertArrayEquals(old, file.readBytes())
    }

    @Test
    fun aStopAtAnyStepLeavesTheOldOrTheNewContentNeverAMix() {
        for (stopAt in WriteStep.entries) {
            val file = temp.root.resolve("stop-$stopAt.bin").apply { writeBytes(old) }
            assertThrows(SimulatedCrash::class.java) {
                writeAtomically(file, new, onStep = { if (it == stopAt) throw SimulatedCrash() })
            }
            val expected = if (stopAt < WriteStep.RENAMED) old else new
            assertArrayEquals("stopped at $stopAt", expected, file.readBytes())
            // The next write is not disturbed by what the stopped one left behind.
            assertTrue(writeAtomically(file, new))
            assertArrayEquals(new, file.readBytes())
            assertFalse(tempFileFor(file).exists())
        }
    }

    @Test
    fun aRealDirectoryCanBeSyncedAndAMissingOneIsReported() {
        assertTrue(syncDirectory(temp.root))
        assertFalse(syncDirectory(temp.root.resolve("not there")))
        val file = temp.root.resolve("a/file.bin")
        writeAtomically(file, new)
        assertTrue(syncExisting(file, levels = 2))
        assertFalse(syncExisting(temp.root.resolve("a/missing.bin")))
        val asked = mutableListOf<File>()
        syncExisting(file, levels = 2, syncDirectory = { asked += it; true })
        assertEquals(listOf(temp.root.resolve("a"), temp.root).map { it.absoluteFile }, asked.map { it.absoluteFile })
    }
}

/**
 * The order in which the lock writes its configuration and deletes the keys it supersedes, under
 * a simple model of a power loss: a key store change is permanent the moment it is made, a
 * file's content only once it was synced, and a rename only once the directory was synced. At
 * every point of every change, whatever configuration a power loss could leave must name keys
 * that still exist.
 */
class LockDurabilityTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val vault = SoftwareKeyVault()
    private val clock = FakeClock()
    private val configFile: File get() = temp.root.resolve("security/lock.json")

    private val passphrase = "correct horse battery"
    private val otherPassphrase = "a different one, longer"

    /** Everything that happened, in order: key store changes, the steps of each write, directory syncs. */
    private val events = mutableListOf<String>()

    /** The configuration a power loss falls back to: the content at the last directory sync (null: no file). */
    private var durable: ByteArray? = null

    /** Stops the process after this many more events (the event itself has happened). */
    private var stopAfter = Int.MAX_VALUE
    private var directorySyncWorks = true

    private fun event(name: String) {
        events += name
        assertWhatAPowerLossCouldLeaveStillOpens(name)
        if (--stopAfter == 0) throw SimulatedCrash()
    }

    /** The invariant, checked after every single event. */
    private fun assertWhatAPowerLossCouldLeaveStillOpens(at: String) {
        val current = configFile.takeIf { it.exists() }?.readBytes()
        for ((which, bytes) in listOf("the configuration in place" to current, "the last durable configuration" to durable)) {
            if (bytes == null) continue
            val config = Json.decodeFromString(LockConfig.serializer(), String(bytes))
            for (key in config.referencedKeys) {
                assertTrue("after $at, $which names ${key.alias}, which is gone", key.alias in vault.aliases)
            }
        }
    }

    private fun store() = LockConfigStore(
        configFile,
        onWriteStep = { step ->
            if (step == WriteStep.DIRECTORY_SYNCED) durable = configFile.readBytes()
            event(step.name)
        },
        directorySync = { directory ->
            val done = directorySyncWorks && syncDirectory(directory)
            // A sync of the configuration's own directory makes what is in place durable.
            if (done && directory.absoluteFile == configFile.absoluteFile.parentFile && configFile.exists()) durable = configFile.readBytes()
            events += if (done) "SYNC ${directory.name}" else "SYNC FAILED ${directory.name}"
            done
        },
    )

    private fun manager() = LockManager(store(), vault, clock, iterations = 1_000, minIterations = 1, encryptedDataExists = { false })

    private fun hookTheVault() {
        vault.afterCreate = { event("CREATE ${it.kind} ${it.generation}") }
        vault.afterDelete = { event("DELETE ${it.kind} ${it.generation}") }
    }

    private fun LockManager.unlock(text: String) = checkPassphrase(text.toCharArray()) as PassphraseResult.Success

    /** A fresh disk and key store, set up with [passphrase] and unlocked. */
    private fun freshUnlocked(): Pair<LockManager, PassphraseResult.Success> {
        temp.root.resolve("security").deleteRecursively()
        vault.aliases.clear()
        vault.invalidatedBiometric.clear()
        durable = null
        stopAfter = Int.MAX_VALUE
        directorySyncWorks = true
        hookTheVault()
        val first = manager()
        assertEquals(StartResult.SetupRequired, first.start())
        first.setUp(passphrase.toCharArray())
        val m = manager()
        assertEquals(StartResult.Locked, m.start())
        val success = m.unlock(passphrase)
        events.clear()
        return m to success
    }

    /** The events that the steps of writes and the key store produce (directory syncs are bookkeeping). */
    private fun steps() = events.filterNot { it.startsWith("SYNC") }

    /**
     * Restarts after a power loss, once for each configuration it could have left (what is in
     * place, and what was last made durable), over the key store as the stop left it, and hands
     * each restarted manager to [check].
     */
    private fun afterAPowerLoss(check: (LockManager, LockConfig) -> Unit) {
        val keys = LinkedHashMap<String, SecretKey>(vault.aliases)
        val inPlace = configFile.takeIf { it.exists() }?.readBytes()
        val candidates = listOf(inPlace, durable).distinctBy { it?.let(::String) }
        for (candidate in candidates) {
            vault.aliases.clear()
            vault.aliases.putAll(keys)
            tempFileFor(configFile).delete()
            if (candidate == null) configFile.delete() else configFile.writeBytes(candidate)
            // What a power loss leaves is, from then on, what is durable.
            durable = candidate
            stopAfter = Int.MAX_VALUE
            directorySyncWorks = true
            val restarted = manager()
            assertEquals("the configuration ${candidate?.let(::String)} must open", StartResult.Locked, restarted.start())
            check(restarted, restarted.config!!)
        }
    }

    // --- Changing the passphrase ------------------------------------------------------------------

    @Test
    fun aPassphraseChangeDeletesTheOldKeyOnlyAfterTheNewConfigurationIsDurable() {
        val (m, success) = freshUnlocked()
        m.changePassphrase(success.proof, success.key, otherPassphrase.toCharArray())
        assertEquals(
            listOf("CREATE BINDING 2", "TEMP_WRITTEN", "TEMP_SYNCED", "RENAMED", "DIRECTORY_SYNCED", "DELETE BINDING 1"),
            steps(),
        )
        // The directory sync itself sits between the rename and the deletion.
        assertTrue(events.indexOf("RENAMED") < events.indexOf("SYNC security"))
        assertTrue(events.indexOf("SYNC security") < events.indexOf("DELETE BINDING 1"))
    }

    @Test
    fun aPowerLossAtAnyPointOfAPassphraseChangeLeavesAPassphraseThatOpensTheData() {
        val total = run {
            val (m, success) = freshUnlocked()
            m.changePassphrase(success.proof, success.key, otherPassphrase.toCharArray())
            steps().size
        }
        assertEquals(6, total)
        for (stop in 1..total) {
            val (m, success) = freshUnlocked()
            val dataKey = success.key.bytes.copyOf()
            stopAfter = stop
            // The last event is the deletion of the old key: a stop right after it is still inside the change.
            assertThrows("stop $stop", SimulatedCrash::class.java) { m.changePassphrase(success.proof, success.key, otherPassphrase.toCharArray()) }
            val stoppedAt = steps().last()
            afterAPowerLoss { restarted, config ->
                val changed = config.passphrase.bindingGeneration == 2
                val (right, wrong) = if (changed) otherPassphrase to passphrase else passphrase to otherPassphrase
                assertEquals("stopped at $stoppedAt", PassphraseResult.Wrong(0), restarted.checkPassphrase(wrong.toCharArray()))
                assertArrayEquals("stopped at $stoppedAt", dataKey, restarted.unlock(right).key.bytes)
                assertEquals("the key nothing names any more is gone after the restart", listOf(KeyId(KeyKind.BINDING, config.passphrase.bindingGeneration)), vault.bindingKeys)
            }
        }
    }

    @Test
    fun aChangeThatCannotBeMadeDurableKeepsBothKeysUntilAStartHasSyncedIt() {
        val (m, success) = freshUnlocked()
        val dataKey = success.key.bytes.copyOf()
        val before = configFile.readBytes()
        directorySyncWorks = false
        m.changePassphrase(success.proof, success.key, otherPassphrase.toCharArray())
        assertEquals("no key is deleted while the old configuration can still come back", listOf("CREATE BINDING 2", "TEMP_WRITTEN", "TEMP_SYNCED", "RENAMED"), steps())
        assertEquals(2, vault.bindingKeys.size)
        assertArrayEquals("the last durable configuration is still the old one", before, durable)

        // Whichever of the two a power loss leaves, its passphrase opens the data.
        afterAPowerLoss { restarted, config ->
            val right = if (config.passphrase.bindingGeneration == 2) otherPassphrase else passphrase
            assertArrayEquals(dataKey, restarted.unlock(right).key.bytes)
            assertEquals(1, vault.bindingKeys.size)
        }
    }

    // --- A start after a stop -----------------------------------------------------------------------

    @Test
    fun aStartSyncsTheConfigurationItFoundBeforeItDeletesLeftOverKeys() {
        val (m, success) = freshUnlocked()
        // The process dies between the rename and the directory sync; the phone stays on.
        stopAfter = 4
        assertThrows(SimulatedCrash::class.java) { m.changePassphrase(success.proof, success.key, otherPassphrase.toCharArray()) }
        assertEquals("RENAMED", steps().last())
        assertEquals(2, vault.bindingKeys.size)

        events.clear()
        stopAfter = Int.MAX_VALUE
        assertEquals(StartResult.Locked, manager().start())
        assertTrue("$events", events.indexOf("SYNC security") in 0 until events.indexOf("DELETE BINDING 1"))
        assertEquals(listOf(KeyId(KeyKind.BINDING, 2)), vault.bindingKeys)
    }

    @Test
    fun aStartThatCannotSyncDeletesNothing() {
        val (m, success) = freshUnlocked()
        stopAfter = 4
        assertThrows(SimulatedCrash::class.java) { m.changePassphrase(success.proof, success.key, otherPassphrase.toCharArray()) }
        stopAfter = Int.MAX_VALUE
        directorySyncWorks = false
        events.clear()
        val restarted = manager()
        assertEquals(StartResult.Locked, restarted.start())
        assertEquals("both keys are kept", 2, vault.bindingKeys.size)
        assertTrue(restarted.checkPassphrase(otherPassphrase.toCharArray()) is PassphraseResult.Success)
        assertEquals(2, vault.bindingKeys.size)

        directorySyncWorks = true
        assertEquals(StartResult.Locked, manager().start())
        assertEquals(listOf(KeyId(KeyKind.BINDING, 2)), vault.bindingKeys)
    }

    // --- First run ----------------------------------------------------------------------------------

    @Test
    fun setUpReturnsOnlyOnceItsConfigurationIsDurable() {
        hookTheVault()
        val m = manager()
        m.start()
        events.clear()
        m.setUp(passphrase.toCharArray())
        assertEquals(listOf("CREATE BINDING 1", "TEMP_WRITTEN", "TEMP_SYNCED", "RENAMED", "DIRECTORY_SYNCED"), steps())
        // The new security directory's own name is synced as well, in the directory above it.
        assertTrue("$events", events.containsAll(listOf("SYNC security", "SYNC ${temp.root.name}")))
        assertArrayEquals(configFile.readBytes(), durable)
    }

    @Test
    fun aPowerLossAtAnyPointOfSetUpLeavesEitherNothingOrALockThatOpens() {
        hookTheVault()
        for (stop in 1..5) {
            temp.root.resolve("security").deleteRecursively()
            vault.aliases.clear()
            durable = null
            stopAfter = Int.MAX_VALUE
            val m = manager()
            m.start()
            events.clear()
            stopAfter = stop
            // The fifth and last event is the directory sync: a stop right after it is still inside the set-up.
            assertThrows("stop $stop", SimulatedCrash::class.java) { m.setUp(passphrase.toCharArray()) }
            val stoppedAt = steps().last()
            val keys = LinkedHashMap<String, SecretKey>(vault.aliases)
            val inPlace = configFile.takeIf { it.exists() }?.readBytes()
            for (candidate in listOf(inPlace, durable).distinctBy { it?.let(::String) }) {
                vault.aliases.clear()
                vault.aliases.putAll(keys)
                tempFileFor(configFile).delete()
                if (candidate == null) configFile.delete() else configFile.writeBytes(candidate)
                durable = candidate
                stopAfter = Int.MAX_VALUE
                val restarted = manager()
                when (val result = restarted.start()) {
                    StartResult.SetupRequired -> {
                        assertTrue("stopped at $stoppedAt: nothing is left of the unfinished set-up", vault.aliases.isEmpty())
                        assertTrue(restarted.setUp(otherPassphrase.toCharArray()).key.bytes.any { it != 0.toByte() })
                    }
                    StartResult.Locked -> assertTrue("stopped at $stoppedAt", restarted.checkPassphrase(passphrase.toCharArray()) is PassphraseResult.Success)
                    is StartResult.Unrecoverable -> fail("stopped at $stoppedAt: ${result.reason}")
                }
            }
        }
    }

    // --- Renewing fingerprint unlock --------------------------------------------------------------

    @Test
    fun aPowerLossAtAnyPointOfAFingerprintRenewalLeavesAFingerprintUnlockThatWorks() {
        fun enrolledAndUnlocked(): Pair<LockManager, PassphraseResult.Success> {
            val (first, success) = freshUnlocked()
            first.completeBiometricEnrollment(success.proof, success.key, first.prepareBiometricEnrollment(success.proof))
            events.clear()
            return first to success
        }
        val total = run {
            val (m, success) = enrolledAndUnlocked()
            m.completeBiometricEnrollment(success.proof, success.key, m.prepareBiometricEnrollment(success.proof))
            steps()
        }
        assertEquals(listOf("CREATE BIOMETRIC 3", "TEMP_WRITTEN", "TEMP_SYNCED", "RENAMED", "DIRECTORY_SYNCED", "DELETE BIOMETRIC 2"), total)
        for (stop in 1..total.size) {
            val (m, success) = enrolledAndUnlocked()
            val dataKey = success.key.bytes.copyOf()
            stopAfter = stop
            assertThrows("stop $stop", SimulatedCrash::class.java) {
                m.completeBiometricEnrollment(success.proof, success.key, m.prepareBiometricEnrollment(success.proof))
            }
            val stoppedAt = steps().last()
            afterAPowerLoss { restarted, config ->
                val cipher = (restarted.biometricUnlockCipher() as BiometricCipherResult.Ready).cipher
                assertArrayEquals("stopped at $stoppedAt", dataKey, restarted.unlockWithBiometric(cipher).keyOrNull()!!.bytes)
                assertArrayEquals(dataKey, restarted.unlock(passphrase).key.bytes)
                assertEquals(listOf(KeyId(KeyKind.BIOMETRIC, config.biometric!!.keyGeneration)), vault.biometricKeys)
            }
        }
    }
}
