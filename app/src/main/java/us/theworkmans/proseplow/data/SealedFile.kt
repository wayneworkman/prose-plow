package us.theworkmans.proseplow.data

import us.theworkmans.proseplow.security.AppFiles
import us.theworkmans.proseplow.security.BlobCipher
import us.theworkmans.proseplow.security.KeyDestroyedException
import us.theworkmans.proseplow.security.UndecryptableException
import us.theworkmans.proseplow.security.deleteOrThrow
import us.theworkmans.proseplow.security.syncDirectory
import us.theworkmans.proseplow.security.tempFileFor
import us.theworkmans.proseplow.security.writeAtomically
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** What [SealedFile.read] found. */
sealed interface SealedRead {

    /** There is no such file. */
    data object Missing : SealedRead

    /**
     * The file was decrypted. Whether its content was understood is for the caller to say, with
     * [SealedFile.contentUnderstood].
     */
    class Opened(val plaintext: ByteArray) : SealedRead

    /**
     * The file is there but does not open for its purpose with this key: damaged, cut short,
     * moved from somewhere else, or written with another key.
     */
    data object Undecryptable : SealedRead
}

/**
 * One file of the app's data: sealed with a purpose (see `Purposes`), written atomically, and
 * never replaced while it holds something this process has not read completely.
 *
 * That last rule is enforced here and not left to the callers, because breaking it loses data
 * silently. The file counts as understood once this process has written it, or has read it and
 * said so ([contentUnderstood]). A [write] over a file that is not understood (it would not
 * decrypt, it decrypted to something that could only be read in part, or it was never read)
 * first puts an exact copy of the stored bytes into [keptDirectory]; when that copy cannot be
 * made, the write fails and the file stays as it was.
 *
 * Nothing here is specific to chats: a store for anything else is this class with another
 * purpose.
 *
 * All methods block and may be called from any thread; they exclude each other.
 *
 * @param file where the blob lives. Its name ends in [AppFiles.ENCRYPTED_SUFFIX], which is how
 *   the lock knows that encrypted data exists.
 * @param purpose what the blob is sealed for.
 * @param keptDirectory where copies of files that could not be read completely are kept.
 * @param keptName the beginning of the name such a copy gets; it says what the copy is of.
 */
class SealedFile(
    val file: File,
    private val purpose: String,
    private val cipher: BlobCipher,
    private val keptDirectory: File,
    private val keptName: String,
) {
    init {
        require(file.name.endsWith(AppFiles.ENCRYPTED_SUFFIX)) { "An encrypted file's name ends in ${AppFiles.ENCRYPTED_SUFFIX}" }
    }

    /** True once this process knows everything the stored file holds. */
    private var understood = false

    /**
     * Reads and decrypts the file.
     *
     * @throws KeyDestroyedException when the app has locked meanwhile.
     * @throws IOException when the file exists but could not be read from the disk. That says
     *   nothing about its content, so it is not reported as [SealedRead.Undecryptable].
     */
    @Synchronized
    @Throws(IOException::class)
    fun read(): SealedRead {
        if (!file.exists()) return SealedRead.Missing
        val blob = file.readBytes()
        return try {
            SealedRead.Opened(cipher.open(purpose, blob))
        } catch (_: UndecryptableException) {
            SealedRead.Undecryptable
        }
    }

    /**
     * The caller made sense of everything the last [read] returned, so the next [write] may
     * replace the file without keeping a copy.
     */
    @Synchronized
    fun contentUnderstood() {
        understood = true
    }

    /**
     * Seals [plaintext] and puts it in place of the file, keeping a copy of the present file
     * first unless it is understood (see the class comment).
     *
     * @throws IOException when the file was not replaced; it then still has its old content.
     */
    @Synchronized
    @Throws(IOException::class)
    fun write(plaintext: ByteArray) {
        // Sealed first: once the app has locked this throws, and no copy is made for a write
        // that cannot happen.
        val sealed = cipher.seal(purpose, plaintext)
        if (!understood) keepCopy()
        writeAtomically(file, sealed)
        understood = true
    }

    /**
     * Copies the stored bytes, exactly as they are, into [keptDirectory]. The copy's name ends in
     * a digest of those bytes, so keeping the same content twice makes one copy, however often
     * the app is started with it. The copy is still sealed for [purpose] and its name still ends
     * in [AppFiles.ENCRYPTED_SUFFIX]: it remains encrypted data as far as the lock is concerned,
     * and a reset deletes it with everything else. It also keeps the file's date (its
     * last-modified time), which for a chat is the date of its newest message: the copy then
     * expires by the same rule as the file it is a copy of, without being decrypted. When the
     * date cannot be set the copy has the time it was made, which is later, never earlier.
     *
     * @return the copy, or null when there is no file to copy.
     * @throws IOException when the copy could not be written.
     */
    @Synchronized
    @Throws(IOException::class)
    fun keepCopy(): File? {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        val copy = File(keptDirectory, "$keptName.${digest(bytes)}${AppFiles.ENCRYPTED_SUFFIX}")
        if (!copy.exists() || copy.length() != bytes.size.toLong()) {
            val date = file.lastModified()
            writeAtomically(copy, bytes)
            if (date > 0) copy.setLastModified(date)
        }
        return copy
    }

    /** The copies [keepCopy] has made of this file, in no particular order. */
    @Synchronized
    fun keptCopies(): List<File> =
        keptDirectory.listFiles { candidate -> candidate.name.startsWith("$keptName.") && candidate.name.endsWith(AppFiles.ENCRYPTED_SUFFIX) }
            ?.toList().orEmpty()

    /**
     * Deletes the file, what an interrupted write may have left beside it, and every kept copy
     * of it: when the user deletes something, no earlier version of it stays behind.
     *
     * @throws IOException when something could not be deleted.
     */
    @Synchronized
    @Throws(IOException::class)
    fun delete() {
        deleteOrThrow(tempFileFor(file))
        deleteOrThrow(file)
        for (copy in keptCopies()) deleteOrThrow(copy)
        understood = false
        // The names are gone from the page cache; make that true on the disk as well, so that a
        // power loss cannot bring a deleted file back.
        file.absoluteFile.parentFile?.let(::syncDirectory)
        if (keptDirectory.isDirectory) syncDirectory(keptDirectory)
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).take(DIGEST_BYTES).joinToString("") { "%02x".format(it) }

    private companion object {
        /** Enough of the digest to tell the versions of one file apart. */
        const val DIGEST_BYTES = 8
    }
}

/**
 * A directory of [SealedFile]s of one kind, one per id: `<id>.enc`, sealed for the purpose
 * [purposeOf] gives that id. The id is part of what is authenticated, so a file renamed to
 * another id does not open.
 *
 * @param keptPrefix the beginning of the name of a kept copy, before the id.
 */
class SealedDirectory(
    private val directory: File,
    private val purposeOf: (id: String) -> String,
    private val cipher: BlobCipher,
    private val keptDirectory: File,
    private val keptPrefix: String,
) {
    private val files = HashMap<String, SealedFile>()

    /**
     * The ids that have a file. Only names are read; nothing is decrypted.
     *
     * @throws IOException when the directory exists but cannot be listed: that is not "empty".
     */
    @Throws(IOException::class)
    fun ids(): Set<String> {
        val names = directory.list() ?: if (directory.exists()) throw IOException("Could not list ${directory.name}") else return emptySet()
        return names.asSequence()
            .filter { it.endsWith(AppFiles.ENCRYPTED_SUFFIX) }
            .map { it.removeSuffix(AppFiles.ENCRYPTED_SUFFIX) }
            .filter(::isValidId)
            .toSet()
    }

    /** The file of [id]. The same object every time, because it remembers what it has read. */
    @Synchronized
    fun file(id: String): SealedFile {
        require(isValidId(id)) { "Not an id that can name a file" }
        return files.getOrPut(id) {
            SealedFile(File(directory, id + AppFiles.ENCRYPTED_SUFFIX), purposeOf(id), cipher, keptDirectory, "$keptPrefix-$id")
        }
    }

    /**
     * Removes what interrupted writes left behind (`*.enc.tmp`). Only safe while nothing is
     * writing here, which is the case when a session is being opened.
     */
    fun sweepTemporaryFiles() {
        directory.listFiles { candidate -> candidate.name.endsWith(AppFiles.ENCRYPTED_SUFFIX + TEMP_SUFFIX) }?.forEach { it.delete() }
    }

    companion object {
        private const val TEMP_SUFFIX = ".tmp"
        private val VALID_ID = Regex("[A-Za-z0-9_-]{1,64}")

        /** Ids become file names, so only letters, digits, hyphens and underscores are accepted. */
        fun isValidId(id: String): Boolean = VALID_ID.matches(id)
    }
}
