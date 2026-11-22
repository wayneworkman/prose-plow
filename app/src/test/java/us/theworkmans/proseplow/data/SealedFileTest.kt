package us.theworkmans.proseplow.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import us.theworkmans.proseplow.security.Envelope
import us.theworkmans.proseplow.security.KeyDestroyedException
import us.theworkmans.proseplow.security.Purposes
import us.theworkmans.proseplow.security.TestKeys
import java.io.File
import java.io.IOException

/**
 * One sealed file: what reaches the disk, and the rule that a file is never replaced while it
 * holds something this process has not read completely.
 */
class SealedFileTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val cipher = TestKeys.cipher()
    private val directory by lazy { temp.newFolder("files") }
    private val kept by lazy { File(directory, "kept") }
    private val purpose = Purposes.of("note", "a1")

    private fun sealedFile(name: String = "a1.enc", purpose: String = this.purpose, keptName: String = "note-a1") =
        SealedFile(File(directory, name), purpose, cipher, kept, keptName)

    private fun SealedFile.readText(): String? = (read() as? SealedRead.Opened)?.plaintext?.toString(Charsets.UTF_8)

    private fun contains(haystack: ByteArray, needle: String): Boolean = String(haystack, Charsets.ISO_8859_1).contains(needle)

    @Test
    fun whatReachesTheDiskIsAnEnvelopeAndNeverThePlaintext() {
        val file = sealedFile()
        file.write("the quick brown fox".toByteArray())

        val stored = file.file.readBytes()
        assertTrue(Envelope.looksEncrypted(stored))
        assertFalse(contains(stored, "quick"))
        assertEquals("the quick brown fox", file.readText())
        assertFalse("no temp file is left behind", File(directory, "a1.enc.tmp").exists())
    }

    @Test
    fun aFileThatIsNotThereIsMissing() {
        assertSame(SealedRead.Missing, sealedFile().read())
    }

    @Test
    fun aFileSealedForAnotherPurposeOrDamagedIsUndecryptableAndLeftAlone() {
        sealedFile(purpose = Purposes.of("note", "other")).write("moved".toByteArray())
        val moved = File(directory, "a1.enc").readBytes()
        assertSame(SealedRead.Undecryptable, sealedFile().read())
        assertArrayEquals(moved, File(directory, "a1.enc").readBytes())

        File(directory, "a1.enc").writeBytes(byteArrayOf(1, 2, 3))
        assertSame(SealedRead.Undecryptable, sealedFile().read())
        assertArrayEquals(byteArrayOf(1, 2, 3), File(directory, "a1.enc").readBytes())
        assertFalse("reading keeps nothing by itself", kept.exists())
    }

    @Test
    fun anUnreadableFileIsCopiedExactlyBeforeItIsReplaced() {
        val damaged = ByteArray(64) { it.toByte() }
        File(directory, "a1.enc").writeBytes(damaged)
        val file = sealedFile()
        assertSame(SealedRead.Undecryptable, file.read())

        file.write("new".toByteArray())

        val copies = file.keptCopies()
        assertEquals(1, copies.size)
        assertArrayEquals(damaged, copies.single().readBytes())
        assertTrue("a copy is still encrypted data to the lock", copies.single().name.endsWith(".enc"))
        assertTrue(copies.single().name.startsWith("note-a1."))
        assertEquals("new", file.readText())
    }

    @Test
    fun aFileThatWasNeverReadIsCopiedBeforeItIsReplaced() {
        sealedFile().write("from an earlier run".toByteArray())
        val before = File(directory, "a1.enc").readBytes()

        val file = sealedFile()
        file.write("blind".toByteArray())

        assertArrayEquals(before, file.keptCopies().single().readBytes())
    }

    @Test
    fun aFileThatWasReadButNotUnderstoodIsCopiedBeforeItIsReplaced() {
        sealedFile().write("half of this makes sense".toByteArray())
        val file = sealedFile()
        assertEquals("half of this makes sense", file.readText())

        file.write("replacement".toByteArray())

        assertEquals(1, file.keptCopies().size)
    }

    @Test
    fun aFileThatWasUnderstoodOrWrittenHereIsReplacedWithoutACopy() {
        sealedFile().write("first".toByteArray())
        val file = sealedFile()
        assertEquals("first", file.readText())
        file.contentUnderstood()

        file.write("second".toByteArray())
        file.write("third".toByteArray())

        assertEquals(emptyList<File>(), file.keptCopies())
        assertFalse(kept.exists())
        assertEquals("third", file.readText())
    }

    @Test
    fun keepingTheSameContentAgainMakesNoSecondCopy() {
        File(directory, "a1.enc").writeBytes(byteArrayOf(9, 9, 9))
        val first = sealedFile().keepCopy()
        val second = sealedFile().keepCopy()
        assertEquals(first, second)
        assertEquals(1, sealedFile().keptCopies().size)

        File(directory, "a1.enc").writeBytes(byteArrayOf(8, 8, 8))
        sealedFile().keepCopy()
        assertEquals("another version is another copy", 2, sealedFile().keptCopies().size)
    }

    @Test
    fun thereIsNothingToKeepOfAFileThatIsNotThere() {
        assertNull(sealedFile().keepCopy())
        assertFalse(kept.exists())
    }

    @Test
    fun whenTheCopyCannotBeMadeTheFileIsNotReplaced() {
        val damaged = byteArrayOf(4, 5, 6)
        File(directory, "a1.enc").writeBytes(damaged)
        // Where the copies would go there is a file, so no copy can be written.
        kept.writeText("in the way")
        val file = sealedFile()

        assertThrows(IOException::class.java) { file.write("new".toByteArray()) }

        assertArrayEquals(damaged, File(directory, "a1.enc").readBytes())
    }

    @Test
    fun onceTheKeyIsDestroyedNothingIsWrittenAndNoCopyIsMade() {
        File(directory, "a1.enc").writeBytes(byteArrayOf(4, 5, 6))
        val file = sealedFile()
        cipher.destroy()

        assertThrows(KeyDestroyedException::class.java) { file.write("late".toByteArray()) }
        assertThrows(KeyDestroyedException::class.java) { file.read() }

        assertArrayEquals(byteArrayOf(4, 5, 6), File(directory, "a1.enc").readBytes())
        assertFalse(kept.exists())
    }

    @Test
    fun deletingRemovesTheFileWhatAWriteLeftBesideItAndEveryCopyOfIt() {
        File(directory, "a1.enc").writeBytes(byteArrayOf(1))
        val file = sealedFile()
        file.write("now".toByteArray())
        File(directory, "a1.enc.tmp").writeBytes(byteArrayOf(2))
        // A copy of another file must stay.
        File(directory, "b2.enc").writeBytes(byteArrayOf(3))
        val other = sealedFile(name = "b2.enc", purpose = Purposes.of("note", "b2"), keptName = "note-b2")
        other.keepCopy()
        assertEquals(1, file.keptCopies().size)

        file.delete()

        assertFalse(File(directory, "a1.enc").exists())
        assertFalse(File(directory, "a1.enc.tmp").exists())
        assertEquals(emptyList<File>(), file.keptCopies())
        assertEquals(1, other.keptCopies().size)
        assertTrue(File(directory, "b2.enc").exists())
        file.delete() // Deleting what is not there is not an error.
    }

    @Test
    fun aFileDeletedAndWrittenAgainStartsAfresh() {
        val file = sealedFile()
        file.write("one".toByteArray())
        file.delete()
        file.write("two".toByteArray())
        assertEquals("two", file.readText())
        assertEquals(emptyList<File>(), file.keptCopies())
    }

    @Test
    fun anEncryptedFileMustBeNamedAsOne() {
        assertThrows(IllegalArgumentException::class.java) { sealedFile(name = "a1.json") }
    }

    // --- A directory of them ---

    private val notes by lazy { File(directory, "notes") }

    private fun sealedDirectory() = SealedDirectory(notes, { id -> Purposes.of("note", id) }, cipher, kept, "note")

    @Test
    fun aDirectoryListsTheIdsThatHaveAFileAndNothingElse() {
        val directory = sealedDirectory()
        assertEquals("a directory that is not there is empty", emptySet<String>(), directory.ids())

        directory.file("a1").write("a".toByteArray())
        directory.file("3f2a-77").write("b".toByteArray())
        File(notes, "c3.enc.tmp").writeBytes(byteArrayOf(1))
        File(notes, "notes.txt").writeText("x")
        File(notes, "bad id.enc").writeBytes(byteArrayOf(1))
        File(notes, ".enc").writeBytes(byteArrayOf(1))

        assertEquals(setOf("a1", "3f2a-77"), directory.ids())
    }

    @Test
    fun aDirectoryThatCannotBeListedIsNotAnEmptyOne() {
        notes.writeText("a file where the directory should be")
        assertThrows(IOException::class.java) { sealedDirectory().ids() }
    }

    @Test
    fun aFileOfADirectoryIsBoundToItsId() {
        val directory = sealedDirectory()
        directory.file("a1").write("for a1".toByteArray())
        assertSame("the same object, because it remembers what it read", directory.file("a1"), directory.file("a1"))

        File(notes, "a1.enc").copyTo(File(notes, "b2.enc"))
        assertSame(SealedRead.Undecryptable, directory.file("b2").read())
        // And a copy is named after the id it belongs to.
        assertTrue(directory.file("b2").keepCopy()!!.name.startsWith("note-b2."))
    }

    @Test
    fun idsThatCouldLeaveTheDirectoryAreRefused() {
        for (id in listOf("", "..", "a/b", "a.b", "a b", "x".repeat(65))) {
            assertFalse(id, SealedDirectory.isValidId(id))
            assertThrows(id, IllegalArgumentException::class.java) { sealedDirectory().file(id) }
        }
        for (id in listOf("a", "A-b_9", "3f2a7c1e-5b0d-4e8a-9c1f-0a1b2c3d4e5f")) assertTrue(id, SealedDirectory.isValidId(id))
    }

    @Test
    fun sweepingRemovesOnlyWhatInterruptedWritesLeft() {
        val directory = sealedDirectory()
        directory.file("a1").write("a".toByteArray())
        File(notes, "a1.enc.tmp").writeBytes(byteArrayOf(1))
        File(notes, "gone.enc.tmp").writeBytes(byteArrayOf(1))

        directory.sweepTemporaryFiles()

        assertEquals(listOf("a1.enc"), notes.list()!!.sorted())
    }
}
