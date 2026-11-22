package us.theworkmans.proseplow.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** What counts as encrypted data on disk, and what a reset deletes. */
class AppFilesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val files by lazy { AppFiles(temp.newFolder("files"), temp.newFolder("cache")) }

    private fun write(root: File, path: String): File = File(root, path).apply {
        parentFile!!.mkdirs()
        writeBytes(byteArrayOf(1, 2, 3))
    }

    @Test
    fun theLockConfigurationLivesInTheSecurityDirectory() {
        assertEquals(File(files.filesDir, "security/lock.json"), files.lockConfig)
        assertEquals(File(files.filesDir, "security"), files.securityDir)
    }

    @Test
    fun anyEncFileAnywhereUnderTheFilesDirectoryIsEncryptedData() {
        // Stores that exist, and ones nobody has written yet: the rule is the name, not a list.
        for (path in listOf("settings.enc", "sessions/3f2a.enc", "sessions/index.enc", "notes/2026/deep/er/x.enc", "memory.enc", ".hidden.enc")) {
            val file = write(files.filesDir, path)
            assertTrue(path, files.encryptedDataExists())
            assertTrue(file.delete())
            assertFalse("without $path", files.encryptedDataExists())
        }
    }

    @Test
    fun otherFilesAreNotEncryptedData() {
        assertFalse("an empty files directory", files.encryptedDataExists())
        write(files.filesDir, "security/lock.json")
        write(files.filesDir, "security/anything.enc") // The lock's own directory is not data.
        write(files.filesDir, "sessions/3f2a.enc.tmp") // A write that never finished.
        write(files.filesDir, "sessions/3f2a.enc.unreadable")
        write(files.filesDir, "models/model.gguf")
        write(files.filesDir, "notes.txt")
        File(files.filesDir, "folder.enc").mkdirs() // A directory is not a file.
        write(files.cacheDir, "scratch.enc") // The cache is not where data lives.
        assertFalse(files.encryptedDataExists())
    }

    @Test
    fun aFilesDirectoryThatDoesNotExistHoldsNoData() {
        assertFalse(AppFiles(File(temp.root, "not there"), File(temp.root, "nor this")).encryptedDataExists())
    }

    @Test
    fun aDirectoryThatCannotBeLookedIntoIsAnErrorNotNoData() {
        val hidden = write(files.filesDir, "sessions/3f2a.enc").parentFile!!
        hidden.setReadable(false)
        try {
            assertThrows(IOException::class.java) { files.encryptedDataExists() }
        } finally {
            hidden.setReadable(true)
        }
        assertTrue(files.encryptedDataExists())
    }

    @Test
    fun linksAreNotFollowed() {
        val outside = temp.newFolder("outside")
        write(outside, "elsewhere.enc")
        Files.createSymbolicLink(File(files.filesDir, "link").toPath(), outside.toPath())
        assertFalse("what a link points to is not the app's data", files.encryptedDataExists())

        assertTrue(DataReset.deleteAllData(files))
        assertFalse(File(files.filesDir, "link").exists())
        assertTrue("a reset deletes the link, not what it points to", File(outside, "elsewhere.enc").exists())
    }

    @Test
    fun aResetDeletesEverythingInFilesAndCacheExceptTheSecurityDirectory() {
        write(files.filesDir, "settings.enc")
        write(files.filesDir, "sessions/3f2a.enc")
        write(files.filesDir, "notes/2026/deep/er/x.enc")
        write(files.filesDir, "something/a store added later.bin")
        write(files.cacheDir, "scratch/tmp.bin")
        write(files.filesDir, "security/lock.json")

        assertTrue(DataReset.deleteAllData(files))
        assertEquals("only the lock's directory is left, for the lock to finish", listOf("security"), files.filesDir.list()!!.toList())
        assertTrue(files.lockConfig.exists())
        assertEquals(emptyList<String>(), files.cacheDir.list()!!.toList())
        assertFalse(files.encryptedDataExists())
    }

    @Test
    fun aResetThatCannotDeleteSomethingSaysSoAndStillDeletesTheRest() {
        val stuck = write(files.filesDir, "sessions/3f2a.enc")
        write(files.filesDir, "settings.enc")
        write(files.filesDir, "notes/x.enc")
        write(files.cacheDir, "scratch.bin")
        stuck.parentFile!!.setWritable(false)
        try {
            assertFalse(DataReset.deleteAllData(files))
            assertTrue(stuck.exists())
            assertEquals(listOf("sessions"), files.filesDir.list()!!.toList())
            assertEquals(emptyList<String>(), files.cacheDir.list()!!.toList())
            assertTrue("and what is left still counts as data", files.encryptedDataExists())
        } finally {
            stuck.parentFile!!.setWritable(true)
        }
        assertTrue(DataReset.deleteAllData(files))
        assertFalse(files.encryptedDataExists())
    }

    @Test
    fun aResetOfDirectoriesThatDoNotExistHasNothingToDo() {
        assertTrue(DataReset.deleteAllData(AppFiles(File(temp.root, "not there"), File(temp.root, "nor this"))))
    }
}

/** The whole reset, files and keys together, with things that refuse to be deleted. */
class ResetTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val files by lazy { AppFiles(temp.newFolder("files"), temp.newFolder("cache")) }
    private val vault = SoftwareKeyVault()
    private val clock = FakeClock()
    private val passphrase = "correct horse battery"

    private fun manager() = LockManager(LockConfigStore(files.lockConfig), vault, clock, iterations = 1_000, minIterations = 1, encryptedDataExists = files::encryptedDataExists)

    /** A set-up app with fingerprint unlock and some stored data; returns its unlocked manager. */
    private fun appWithData(): LockManager {
        val m = manager()
        assertEquals(StartResult.SetupRequired, m.start())
        val created = m.setUp(passphrase.toCharArray())
        m.completeBiometricEnrollment(created.proof, created.key, m.prepareBiometricEnrollment(created.proof))
        val cipher = DataCrypto(created.key)
        for (path in listOf("settings.enc", "sessions/one.enc", "notes/two.enc")) {
            File(files.filesDir, path).apply { parentFile!!.mkdirs() }.writeBytes(cipher.seal(Purposes.of("test", path), byteArrayOf(1)))
        }
        File(files.cacheDir, "scratch.bin").writeBytes(byteArrayOf(1))
        return m
    }

    @Test
    fun aResetDeletesKeysThenDataThenTheConfiguration() {
        val m = appWithData()
        val order = mutableListOf<String>()
        vault.afterDelete = { order += "key ${it.kind}" }
        val report = m.reset {
            order += "data"
            assertTrue("every key is gone before the first file is touched", vault.aliases.isEmpty())
            assertTrue("and the configuration goes after the data", files.lockConfig.exists())
            DataReset.deleteAllData(files)
        }
        assertEquals(ResetReport(keysDeleted = true, dataDeleted = true, configDeleted = true), report)
        assertTrue(report.complete)
        assertEquals(listOf("key BINDING", "key BIOMETRIC", "data"), order)
        assertFalse(files.lockConfig.exists())
        assertEquals(listOf("security"), files.filesDir.list()!!.toList())
        assertEquals(StartResult.SetupRequired, manager().start())
    }

    @Test
    fun aFileThatCannotBeDeletedIsReportedAndNoNewKeyIsEverMadeOverIt() {
        val m = appWithData()
        val stuck = File(files.filesDir, "sessions")
        stuck.setWritable(false)
        try {
            val report = m.reset { DataReset.deleteAllData(files) }
            assertEquals(ResetReport(keysDeleted = true, dataDeleted = false, configDeleted = true), report)
            assertFalse(report.complete)
            assertTrue("the keys went first, so what is left can never be read", vault.aliases.isEmpty())

            val next = manager()
            assertEquals(StartResult.Unrecoverable(UnrecoverableReason.CONFIG_MISSING), next.start())
            assertThrows(IllegalStateException::class.java) { next.setUp(passphrase.toCharArray()) }
            assertTrue(vault.aliases.isEmpty())

            // Resetting again from there fails the same way, and says so again.
            assertFalse(next.reset { DataReset.deleteAllData(files) }.dataDeleted)
        } finally {
            stuck.setWritable(true)
        }
        assertTrue(manager().reset { DataReset.deleteAllData(files) }.complete)
        assertEquals(StartResult.SetupRequired, manager().start())
    }

    @Test
    fun aKeyThatCannotBeDeletedIsReportedAndTheRestStillGoes() {
        val m = appWithData()
        val stuck = vault.biometricKeys.single()
        vault.undeletable += stuck
        val report = m.reset { DataReset.deleteAllData(files) }
        assertEquals(ResetReport(keysDeleted = false, dataDeleted = true, configDeleted = true), report)
        assertEquals(setOf(stuck.alias), vault.aliases.keys)
        assertFalse(files.encryptedDataExists())
        assertEquals("a left-over key protects nothing, so set-up goes ahead", StartResult.SetupRequired, manager().start())
    }

    @Test
    fun aConfigurationThatCannotBeDeletedIsReportedAndLeavesNothingThatOpens() {
        val m = appWithData()
        files.securityDir.setWritable(false)
        try {
            val report = m.reset { DataReset.deleteAllData(files) }
            assertEquals(ResetReport(keysDeleted = true, dataDeleted = true, configDeleted = false), report)
        } finally {
            files.securityDir.setWritable(true)
        }
        assertTrue(files.lockConfig.exists())
        assertEquals(StartResult.Unrecoverable(UnrecoverableReason.KEY_LOST), manager().start())
        assertTrue(manager().reset { DataReset.deleteAllData(files) }.complete)
        assertEquals(StartResult.SetupRequired, manager().start())
    }

    @Test
    fun aDeletionThatThrowsIsAFailureNotACrash() {
        val m = appWithData()
        val report = m.reset { throw IOException("the disk went away") }
        assertEquals(ResetReport(keysDeleted = true, dataDeleted = false, configDeleted = true), report)
    }
}
