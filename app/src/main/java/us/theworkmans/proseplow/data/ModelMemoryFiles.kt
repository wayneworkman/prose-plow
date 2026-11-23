package us.theworkmans.proseplow.data

import us.theworkmans.proseplow.core.chat.MessageExpiry
import us.theworkmans.proseplow.core.chat.MessageLifetime
import us.theworkmans.proseplow.security.AppFiles
import us.theworkmans.proseplow.security.deleteOrThrow
import us.theworkmans.proseplow.security.syncDirectory
import us.theworkmans.proseplow.security.tempFileFor
import java.io.File
import java.io.IOException

/**
 * Deletes the saved memory of a chat ([MemoryStore]) without the data key. A chat whose stored
 * messages are pruned, edited or deleted calls this first and is written afterwards, so that a
 * stop between the two steps leaves no memory rather than a memory of text that is gone.
 */
fun interface SavedMemoryDeletion {

    /**
     * Deletes the saved memory of the chat [chatId], if there is one, and stops a save that is
     * under way from putting one back.
     *
     * @throws IOException when a file could not be deleted. The caller goes on all the same: the
     *   rules of [ModelMemoryFiles.sweep] and the checks of [MemoryStore.saved] catch the file
     *   before it could ever be used.
     */
    @Throws(IOException::class)
    fun deleteFor(chatId: String)

    companion object {
        /** For a store beside which no memory is ever saved. */
        val None = SavedMemoryDeletion { }
    }
}

/**
 * The files of the model's saved memory, as far as they can be handled without the data key:
 * `<directory>/<chat id>.enc`, at most one of them, and what an interrupted write left beside it
 * (`<chat id>.enc.tmp`).
 *
 * The directory follows the three rules of [AppFiles]: it lies under the private files
 * directory and outside `security/`, every file in it that holds data ends in
 * [AppFiles.ENCRYPTED_SUFFIX] (so the lock counts a saved memory as encrypted data and never
 * makes a new key over it), and a reset deletes it with everything else.
 *
 * **The generation.** Every deletion through this object counts up one number, and a save
 * checks, under the same lock as the deletions and just before its file takes the real name,
 * that the number is still the one it began with ([MemoryStore]). A save that ends after a
 * deletion therefore cannot put a memory back. One object serves the whole process (the app
 * makes it once), so that a save left behind by a session that has ended is stopped by the
 * deletions of the next one too.
 *
 * Everything here blocks on the disk; nothing needs the key.
 *
 * @param directory where the memory lives: `files/model-memory`.
 * @param sessionsDir the chats, `files/sessions`: a memory whose chat file is not there goes.
 */
class ModelMemoryFiles(val directory: File, private val sessionsDir: File) : SavedMemoryDeletion {

    private val lock = Any()

    @Volatile private var count = 0L

    /** The number of deletions so far; see the class comment. */
    val generation: Long get() = count

    /** The file of the memory of [chatId], which must be a valid id ([SealedDirectory.isValidId]). */
    fun fileFor(chatId: String): File {
        require(SealedDirectory.isValidId(chatId)) { "Not an id that can name a file" }
        return File(directory, chatId + AppFiles.ENCRYPTED_SUFFIX)
    }

    /** The file of the chat [chatId], which a saved memory of it cannot outlive. */
    fun chatFileFor(chatId: String): File = File(sessionsDir, chatId + AppFiles.ENCRYPTED_SUFFIX)

    /** The memory files there are now (`*.enc`), whatever their names. */
    fun storedFiles(): List<File> =
        directory.listFiles { candidate -> candidate.name.endsWith(AppFiles.ENCRYPTED_SUFFIX) }?.toList().orEmpty()

    /**
     * Runs [block] under the lock every deletion takes: for the last step of a save, which checks
     * the generation and renames its file inside it, so that no deletion can fall between the two.
     */
    internal fun <T> exclusive(block: () -> T): T = synchronized(lock, block)

    override fun deleteFor(chatId: String) {
        synchronized(lock) {
            count++
            if (!SealedDirectory.isValidId(chatId)) return
            val file = fileFor(chatId)
            val temp = tempFileFor(file)
            if (!file.exists() && !temp.exists()) return
            deleteOrThrow(temp)
            deleteOrThrow(file)
            syncDirectory(directory)
        }
    }

    /**
     * Deletes every file in the directory, of whichever chat, and stops a save that is under way
     * from putting one back: for a memory that is no longer wanted at all.
     *
     * @throws IOException when something could not be deleted; everything else was tried.
     */
    @Throws(IOException::class)
    fun deleteAll() {
        synchronized(lock) {
            count++
            deleteEverythingLocked()
        }
    }

    /**
     * Holding the lock (from [exclusive]): deletes every file in the directory except those in [keep],
     * without counting up the generation. For a save that makes room for itself, or replaces
     * the memory of another chat.
     */
    @Throws(IOException::class)
    internal fun deleteOthersLocked(vararg keep: File) {
        check(Thread.holdsLock(lock)) { "Deletions are made under the lock" }
        deleteEverythingLocked(keep.toSet())
    }

    private fun deleteEverythingLocked(keep: Set<File> = emptySet()) {
        var failed = false
        var deleted = false
        for (file in directory.listFiles().orEmpty()) {
            if (file in keep || file.isDirectory) continue
            if (file.delete()) deleted = true else if (file.exists()) failed = true
        }
        if (deleted) syncDirectory(directory)
        if (failed) throw IOException("A saved memory could not be deleted")
    }

    /**
     * The rules by which a saved memory goes without the key, by the names and the dates of the
     * files alone; for the lock's clean-up ([ExpiredFiles]) and for every unlock, before any
     * restore:
     * - every `*.tmp` goes, whatever its date: no write is under way when this runs;
     * - a memory goes when no chat file of the same id is there (`sessions/<id>.enc`), and when
     *   its name is not that of a chat;
     * - under a [lifetime] with a limit, a memory goes when its file's date (the date of the
     *   oldest message it was made from) is past the limit at [nowMillis], or cannot be read.
     *   With [MessageLifetime.NEVER], or a limit that is not known (null), only this rule is off.
     *
     * The generation counts up whatever is found, so that a save left behind by a session that
     * has ended cannot put anything back afterwards. Every deletion is tried even when one before
     * it failed; the directory is flushed after a deletion.
     */
    fun sweep(lifetime: MessageLifetime?, nowMillis: Long) {
        synchronized(lock) {
            count++
            var deleted = false
            for (file in directory.listFiles().orEmpty()) {
                val name = file.name
                val goes = when {
                    file.isDirectory -> false
                    name.endsWith(TEMP_SUFFIX) -> true
                    name.endsWith(AppFiles.ENCRYPTED_SUFFIX) -> {
                        val id = name.removeSuffix(AppFiles.ENCRYPTED_SUFFIX)
                        !SealedDirectory.isValidId(id) || !chatFileFor(id).exists() || expired(file, lifetime, nowMillis)
                    }
                    else -> false
                }
                if (goes && file.delete()) deleted = true
            }
            if (deleted) syncDirectory(directory)
        }
    }

    private fun expired(file: File, lifetime: MessageLifetime?, nowMillis: Long): Boolean {
        if (lifetime?.limitMillis == null) return false
        val date = file.lastModified()
        return date <= 0 || MessageExpiry.fileExpired(date, lifetime, nowMillis)
    }

    private companion object {
        const val TEMP_SUFFIX = ".tmp"
    }
}
