package us.theworkmans.proseplow.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import us.theworkmans.proseplow.core.chat.ChatSession
import us.theworkmans.proseplow.core.chat.SessionCodec
import us.theworkmans.proseplow.core.chat.SessionSummary
import us.theworkmans.proseplow.security.KeyDestroyedException
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
 * it is until the user deletes that chat.
 *
 * All methods block; call them off the main thread.
 */
class SessionStore(private val sessions: SealedDirectory, private val indexFile: SealedFile) {

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

    /** Stores [session] in its file. @throws IOException when it was not stored. */
    @Throws(IOException::class)
    fun save(session: ChatSession) {
        sessions.file(session.id).write(SessionCodec.encode(session).toByteArray(Charsets.UTF_8))
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
