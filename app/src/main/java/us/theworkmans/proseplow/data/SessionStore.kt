package us.theworkmans.proseplow.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import us.theworkmans.proseplow.core.chat.ChatSession
import us.theworkmans.proseplow.core.chat.MessageExpiry
import us.theworkmans.proseplow.core.chat.MessageLifetime
import us.theworkmans.proseplow.core.chat.SessionCodec
import us.theworkmans.proseplow.core.chat.SessionSummary
import us.theworkmans.proseplow.security.KeyDestroyedException
import java.io.File
import java.io.IOException

/**
 * What the list of chats is drawn from without opening any of them: a summary of every stored
 * session, and which one was on screen last.
 *
 * It is a convenience, never the truth. The session files are the truth: [SessionStore.open]
 * corrects the index by them every time, so an index that is stale, missing or unreadable costs
 * nothing but the time to read the sessions once.
 *
 * @property activeId the session that was on screen last, or null when that was a chat with
 *   nothing in it yet.
 */
@Serializable
data class SessionIndex(val activeId: String? = null, val sessions: List<SessionSummary> = emptyList())

/**
 * The chat sessions on disk: one sealed file per session, and the sealed [SessionIndex] beside
 * them.
 *
 * What is kept in step with what, and in which order, so that a process that dies at any point
 * leaves something [open] can make sense of:
 * - A session is written to its own file first and listed in the index afterwards. A file the
 *   index does not list is therefore a chat whose listing did not make it, and [open] adopts it.
 * - A session is deleted by deleting its file. An index entry without a file is therefore a
 *   chat that was deleted, and [open] drops the entry.
 *
 * Nothing here ever replaces a file it could not read completely without keeping a copy
 * ([SealedFile]), and a session file that cannot be read at all is never written to: it stays as
 * it is until the user deletes that chat, or it expires by its date ([expire]).
 *
 * A session file is dated by its newest message: every [save] sets the file's last-modified time
 * to that message's date. That is what lets expired chats be deleted while the app is locked,
 * without the key (see [ExpiredFiles]).
 *
 * All methods block; call them off the main thread.
 *
 * @param setFileDate sets a file's last-modified time and says whether that worked; replaced in
 *   the unit tests to make it fail.
 */
class SessionStore(
    private val sessions: SealedDirectory,
    private val indexFile: SealedFile,
    private val setFileDate: (File, Long) -> Boolean = { file, millis -> file.setLastModified(millis) },
) {

    /**
     * What of a stored session could not be read, when some of it could.
     *
     * @property skippedMessages messages that were left out.
     * @property errorDropped the stored error was left out.
     * @property fromNewerVersion the file was written by a newer version of the app, which may
     *   have stored things this version does not know of.
     */
    data class Incomplete(val skippedMessages: Int, val errorDropped: Boolean, val fromNewerVersion: Boolean)

    /** What [load] found. */
    sealed interface Loaded {

        /**
         * The session, as it must look after a restart (a reply that was being written when the
         * process died is a stopped one).
         *
         * @property incomplete what was left out, or null when [session] is everything stored.
         *   A copy of the file as it was has then been kept, if that was possible; saving the
         *   session is refused until it is.
         */
        data class Readable(val session: ChatSession, val incomplete: Incomplete? = null) : Loaded

        /** The file is there but is not a session this app can read. It has not been touched. */
        data object Unreadable : Loaded

        /** There is no such session. */
        data object Missing : Loaded
    }

    /**
     * The index as [open] made it.
     *
     * @property stale the index on disk says something else (or is not there), so it should be
     *   written again.
     */
    data class Opened(val index: SessionIndex, val stale: Boolean)

    /**
     * Reads the index and corrects it by the session files that are really there; see the class
     * comment. Called once, when a session is opened and nothing else is using the files yet.
     *
     * A session that has to be adopted is read for its summary. One that cannot be read is
     * listed all the same, without a title, so that the user can see that it exists and delete
     * it.
     *
     * @throws IOException when the directory cannot be listed or a file cannot be read from the
     *   disk. Without knowing what is stored the app must not go on as if nothing were.
     */
    @Throws(IOException::class)
    fun open(): Opened {
        sessions.sweepTemporaryFiles()
        val onDisk = sessions.ids()
        val stored = readIndex()
        val listed = stored?.sessions.orEmpty().filter { it.id in onDisk }.distinctBy { it.id }
        val known = listed.mapTo(HashSet()) { it.id }
        val adopted = (onDisk - known).sorted().map(::summaryOf)
        val index = SessionIndex(activeId = stored?.activeId?.takeIf { it in onDisk }, sessions = listed + adopted)
        // Nothing stored and nothing to list is not stale: a first start writes no file for it.
        return Opened(index, stale = index != (stored ?: SessionIndex()))
    }

    /**
     * Reads the session [id].
     *
     * @throws KeyDestroyedException when the app has locked meanwhile.
     * @throws IOException when the file could not be read from the disk.
     */
    @Throws(IOException::class)
    fun load(id: String): Loaded {
        if (!SealedDirectory.isValidId(id)) return Loaded.Missing
        val file = sessions.file(id)
        val plaintext = when (val read = file.read()) {
            SealedRead.Missing -> return Loaded.Missing
            SealedRead.Undecryptable -> return Loaded.Unreadable
            is SealedRead.Opened -> read.plaintext
        }
        val decoded = SessionCodec.decode(plaintext.toString(Charsets.UTF_8)) as? SessionCodec.Decoded.Readable
            ?: return Loaded.Unreadable
        // The purpose already ties the file to its id; a session inside that says otherwise was
        // not written by this store.
        if (decoded.session.id != id) return Loaded.Unreadable
        if (decoded.complete) {
            file.contentUnderstood()
            return Loaded.Readable(decoded.session.restored())
        }
        try {
            file.keepCopy()
        } catch (e: KeyDestroyedException) {
            throw e
        } catch (_: IOException) {
            // No room for the copy now. The file is still not understood, so the first save tries
            // again and fails rather than replace it.
        }
        return Loaded.Readable(
            decoded.session.restored(),
            Incomplete(decoded.skippedMessages, decoded.errorDropped, decoded.fromNewerVersion),
        )
    }

    /**
     * Stores [session] in its file, and dates the file by its newest message. When the date
     * cannot be set (or no message has one) the file keeps the time it was written, which is
     * later than its newest message: a deletion by the date then comes later, never earlier.
     *
     * @throws IOException when it was not stored.
     */
    @Throws(IOException::class)
    fun save(session: ChatSession) {
        val file = sessions.file(session.id)
        file.write(SessionCodec.encode(session).toByteArray(Charsets.UTF_8))
        MessageExpiry.newestMessageMillis(session)?.let { setFileDate(file.file, it) }
    }

    /** What [expire] did to a stored session. */
    sealed interface Expiry {
        /** Nothing of it had expired, or it was not looked at; its file is as it was. */
        data object Unchanged : Expiry

        /**
         * It was read and nothing of it had expired; its file is as it was. [oldestMessageAtMillis]
         * is the date of its oldest message, for its entry in the list, so that a later pass need
         * not read it again before that date is past the limit.
         */
        data class Checked(val oldestMessageAtMillis: Long) : Expiry

        /** Messages were removed (or their dates made usable); [session] is what is stored now. */
        data class Pruned(val session: ChatSession) : Expiry

        /** It is no longer stored: everything in it had expired, and it was deleted. */
        data object Deleted : Expiry

        /** There was no file. That is not an expiry in itself; see [expireAll]. */
        data object Missing : Expiry
    }

    /**
     * Applies the expiry of messages ([MessageExpiry]) to the stored session [id] at [nowMillis]
     * (trusted time), for a session that nothing else is using:
     * - a readable session loses its expired turns and is saved, with its title derived again;
     *   one that keeps no message is deleted with its kept copies. A session without messages
     *   (only a draft) has nothing to date it by but its file, and is deleted when that is past
     *   the limit, as the locked clean-up does;
     * - message dates that cannot be used (none, or in the future) are made usable first, from
     *   the file's date and from now, and stored;
     * - a session that cannot be read is deleted when its file is past the limit;
     * - a session that could only be read in part is never deleted on the strength of the
     *   messages that could be read alone, since those that could not may be newer: only when
     *   its file, too, is past the limit.
     *
     * Two calls exclude each other, so that two passes over the same chat (the one that runs
     * every hour, and the one of a chat that is being opened) cannot undo each other's work.
     *
     * @throws IOException when the file could not be read, written or deleted; it is then as it
     *   was, or as far as the deletion got.
     */
    @Synchronized
    @Throws(IOException::class)
    fun expire(id: String, lifetime: MessageLifetime, nowMillis: Long): Expiry {
        if (lifetime.limitMillis == null || !SealedDirectory.isValidId(id)) return Expiry.Unchanged
        val fileDate = sessions.file(id).file.lastModified()
        val fileExpired = MessageExpiry.fileExpired(fileDate, lifetime, nowMillis)
        val loaded = when (val read = load(id)) {
            Loaded.Missing -> return Expiry.Missing
            Loaded.Unreadable -> return if (fileExpired) deleted(id) else Expiry.Unchanged
            is Loaded.Readable -> read
        }
        if (loaded.session.messages.isEmpty()) return if (fileExpired) deleted(id) else Expiry.Unchanged
        val normalised = MessageExpiry.normalised(loaded.session, nowMillis, fileDate)
        val kept = when (val outcome = MessageExpiry.prune(normalised, lifetime, nowMillis)) {
            MessageExpiry.Outcome.Unchanged -> if (normalised === loaded.session) return checked(normalised) else normalised
            is MessageExpiry.Outcome.Pruned -> outcome.session
            MessageExpiry.Outcome.Emptied -> return if (loaded.incomplete == null || fileExpired) deleted(id) else checked(normalised)
        }
        save(kept)
        return Expiry.Pruned(kept)
    }

    /**
     * The expiry of messages applied to every listed session of [opened] whose summary does not
     * rule it out ([MessageExpiry.mayHaveExpired]), for the unlock, before any chat is shown.
     * Entries of deleted sessions are dropped quietly and pruned ones get their new summary; one
     * that was read and kept learns the date of its oldest message, so that the next pass can
     * skip it. A session whose file could not be read, written or deleted now is left as it is
     * (it is tried again at the next pass).
     *
     * A file that is missing is dropped quietly only when its entry says the whole chat had
     * expired (its last change is past the limit), which is what the locked clean-up leaves
     * behind. Otherwise the entry stays: the chat was not known to have expired, and opening it
     * says that it is no longer on the phone. Returns [opened] itself when nothing changed.
     */
    fun expireAll(opened: Opened, lifetime: MessageLifetime, nowMillis: Long): Opened {
        if (lifetime.limitMillis == null) return opened
        var changed = false
        val remaining = opened.index.sessions.mapNotNull { summary ->
            if (!MessageExpiry.mayHaveExpired(summary.oldestMessageAtMillis, lifetime, nowMillis)) return@mapNotNull summary
            val expiry = try {
                expire(summary.id, lifetime, nowMillis)
            } catch (_: IOException) {
                Expiry.Unchanged
            }
            when (expiry) {
                Expiry.Unchanged -> summary
                is Expiry.Checked ->
                    if (summary.oldestMessageAtMillis == expiry.oldestMessageAtMillis) summary
                    else summary.copy(oldestMessageAtMillis = expiry.oldestMessageAtMillis).also { changed = true }
                is Expiry.Pruned -> expiry.session.summary().also { changed = true }
                Expiry.Deleted -> null.also { changed = true }
                Expiry.Missing -> if (wholeChatExpired(summary, lifetime, nowMillis)) null.also { changed = true } else summary
            }
        }
        if (!changed) return opened
        val ids = remaining.mapTo(HashSet()) { it.id }
        val index = SessionIndex(activeId = opened.index.activeId?.takeIf { it in ids }, sessions = remaining)
        return Opened(index, stale = true)
    }

    /** True when [summary] says the whole chat had expired: its last change is past the limit. */
    private fun wholeChatExpired(summary: SessionSummary, lifetime: MessageLifetime, nowMillis: Long): Boolean =
        MessageExpiry.fileExpired(summary.updatedAtMillis, lifetime, nowMillis)

    /** What was learned of [session], which was read and not changed. */
    private fun checked(session: ChatSession): Expiry =
        session.summary().oldestMessageAtMillis?.let(Expiry::Checked) ?: Expiry.Unchanged

    private fun deleted(id: String): Expiry {
        delete(id)
        return Expiry.Deleted
    }

    /**
     * Deletes the session [id] for good, readable or not, with every kept copy of it.
     * @throws IOException when something of it is still there.
     */
    @Throws(IOException::class)
    fun delete(id: String) {
        if (SealedDirectory.isValidId(id)) sessions.file(id).delete()
    }

    /** Stores the index. @throws IOException when it was not stored. */
    @Throws(IOException::class)
    fun saveIndex(index: SessionIndex) {
        indexFile.write(json.encodeToString(StoredIndex.serializer(), StoredIndex(INDEX_SCHEMA_VERSION, index)).toByteArray(Charsets.UTF_8))
    }

    /** The stored index, or null when there is none or it cannot be read. */
    private fun readIndex(): SessionIndex? {
        val plaintext = (indexFile.read() as? SealedRead.Opened)?.plaintext ?: return null
        val stored = try {
            json.decodeFromString(StoredIndex.serializer(), plaintext.toString(Charsets.UTF_8))
        } catch (_: IllegalArgumentException) {
            // A SerializationException is one. The index is rebuilt; the unread file is kept as a
            // copy when it is replaced, like any other.
            return null
        }
        // A newer version's index may hold more than this one knows; it is not understood.
        if (stored.schemaVersion == INDEX_SCHEMA_VERSION) indexFile.contentUnderstood()
        return stored.index
    }

    private fun summaryOf(id: String): SessionSummary {
        val file = sessions.file(id).file
        return when (val loaded = load(id)) {
            is Loaded.Readable -> loaded.session.summary()
            Loaded.Unreadable, Loaded.Missing -> SessionSummary(id = id, createdAtMillis = file.lastModified(), updatedAtMillis = file.lastModified())
        }
    }

    @Serializable
    private class StoredIndex(val schemaVersion: Int, val index: SessionIndex)

    private companion object {
        const val INDEX_SCHEMA_VERSION = 1

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
