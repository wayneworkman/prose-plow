package us.theworkmans.proseplow.data

import us.theworkmans.proseplow.core.chat.MessageExpiry
import us.theworkmans.proseplow.core.chat.MessageLifetime
import us.theworkmans.proseplow.security.AppFiles
import us.theworkmans.proseplow.security.ExpiredDataSweep
import us.theworkmans.proseplow.security.syncDirectory
import us.theworkmans.proseplow.security.tempFileFor
import java.io.File

/**
 * The expiry of messages as far as it can go without the data key, by the dates of the files
 * alone; the lock runs it while the app is locked ([ExpiredDataSweep]).
 *
 * A chat's file is dated by its newest message ([SessionStore.save]), so a chat whose file is
 * past the limit holds nothing that has not expired, and it is deleted whole:
 * - `sessions/<id>.enc` past the limit goes, with what an interrupted write left beside it
 *   and every kept copy of that chat, whatever the copies' own dates;
 * - a kept copy of a chat, or of the list of chats, past the limit by its own date goes (a copy
 *   keeps the date of the file it was made from);
 * - what interrupted writes of chats, of kept copies or of the list of chats left behind
 *   (`*.enc.tmp`) goes once its own date is past the limit.
 *
 * Whatever has not expired as a whole (a chat with recent messages and old ones, the list of
 * chats, which names the chats deleted here until the next unlock corrects it) waits for the
 * unlocked expiry, which can read it. A file whose date cannot be read (0) is left alone. Every
 * deletion is tried even when one before it failed; what is left is tried again at the next run.
 *
 * @param sessionsDir the chats, one `<id>.enc` file each.
 * @param keptDir the kept copies ([SealedFile.keepCopy]).
 * @param indexFile the list of chats.
 */
class ExpiredFiles(private val sessionsDir: File, private val keptDir: File, private val indexFile: File) : ExpiredDataSweep {

    override fun sweep(lifetimeDays: Int, nowMillis: Long) {
        val lifetime = MessageLifetime.fromStoredDays(lifetimeDays) ?: return
        if (lifetime.limitMillis == null) return
        val expired = { file: File -> file.lastModified().let { it > 0 && MessageExpiry.fileExpired(it, lifetime, nowMillis) } }
        var sessionsChanged = false
        var keptChanged = false
        for (file in sessionsDir.listFiles().orEmpty()) {
            val name = file.name
            when {
                name.endsWith(AppFiles.ENCRYPTED_SUFFIX) -> {
                    val id = name.removeSuffix(AppFiles.ENCRYPTED_SUFFIX)
                    if (!SealedDirectory.isValidId(id) || !expired(file)) continue
                    // The copies first: should this stop half-way, no copy outlives its chat unnoticed.
                    for (copy in keptCopiesOf(id)) keptChanged = copy.delete() || keptChanged
                    tempFileFor(file).delete()
                    sessionsChanged = file.delete() || sessionsChanged
                }
                name.endsWith(AppFiles.ENCRYPTED_SUFFIX + TEMP_SUFFIX) && expired(file) -> sessionsChanged = file.delete() || sessionsChanged
            }
        }
        for (file in keptDir.listFiles().orEmpty()) {
            val name = file.name
            val ofMessages = name.startsWith(SESSION_KEPT_PREFIX) &&
                (name.endsWith(AppFiles.ENCRYPTED_SUFFIX) || name.endsWith(AppFiles.ENCRYPTED_SUFFIX + TEMP_SUFFIX))
            if (ofMessages && expired(file)) keptChanged = file.delete() || keptChanged
        }
        val indexTemp = tempFileFor(indexFile)
        if (indexTemp.exists() && expired(indexTemp)) indexTemp.delete()
        // The names are gone from the page cache; a power loss must not bring them back.
        if (sessionsChanged) syncDirectory(sessionsDir)
        if (keptChanged) syncDirectory(keptDir)
    }

    /** The kept copies of the chat [id]: `session-<id>.<digest>.enc`, as [SealedDirectory] names them. */
    private fun keptCopiesOf(id: String): List<File> =
        keptDir.listFiles { candidate -> candidate.name.startsWith("$SESSION_KEPT_PREFIX-$id.") && candidate.name.endsWith(AppFiles.ENCRYPTED_SUFFIX) }
            ?.toList().orEmpty()

    companion object {
        /**
         * The beginning of the name of every kept copy that holds message text: the copies of a
         * chat (`session-<id>.`) and of the list of chats (`session-index.`), as the session's
         * stores are named in `DataLayout`. The copies of the settings hold no message and stay.
         */
        const val SESSION_KEPT_PREFIX = "session"
        private const val TEMP_SUFFIX = ".tmp"
    }
}
