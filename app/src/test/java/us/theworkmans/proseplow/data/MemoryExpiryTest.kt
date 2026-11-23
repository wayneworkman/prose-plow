package us.theworkmans.proseplow.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import us.theworkmans.proseplow.DataLayout
import us.theworkmans.proseplow.core.chat.ChatMessage
import us.theworkmans.proseplow.core.chat.ChatSession
import us.theworkmans.proseplow.core.chat.MessageLifetime
import us.theworkmans.proseplow.core.chat.MessageLifetime.Companion.DAY_MILLIS
import us.theworkmans.proseplow.core.chat.Role
import us.theworkmans.proseplow.core.chat.SessionCodec
import us.theworkmans.proseplow.security.AppFiles
import us.theworkmans.proseplow.security.SimulatedCrash
import us.theworkmans.proseplow.security.TestKeys
import java.io.File
import java.io.IOException

/**
 * A saved memory never outlives a message it was made from: the clean-up without the key by
 * the memory's date and name and together with its chat, the temporary files, and the order in
 * which the chat store deletes a chat's memory and then writes the chat, with a stop between the
 * two steps.
 */
class MemoryExpiryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val cipher = TestKeys.cipher()
    private val files by lazy { AppFiles(temp.newFolder("files"), temp.newFolder("cache")) }
    private val layout by lazy { DataLayout(files) }
    private val memory by lazy { ModelMemoryFiles(layout.modelMemory, layout.sessions) }

    /** Whole seconds: file systems keep dates to the second at least. */
    private val now = 1_700_000_000_000L

    private fun sweep(days: Int = MessageLifetime.SEVEN_DAYS.storedDays) =
        ExpiredFiles(layout.sessions, layout.kept, layout.sessionIndex, memory).sweep(days, now)

    /** A chat file dated [date]; its content is not read by the clean-up. */
    private fun chatFile(id: String, date: Long) = File(layout.sessions, "$id.enc").apply {
        parentFile!!.mkdirs()
        writeText("a chat")
        setLastModified(date)
    }

    /** A memory file dated [date]; its content is not read by the clean-up. */
    private fun memoryFile(id: String, date: Long, name: String = "$id.enc") = File(layout.modelMemory, name).apply {
        parentFile!!.mkdirs()
        writeText("a memory")
        setLastModified(date)
    }

    private fun left(): List<String> = layout.modelMemory.listFiles().orEmpty().map { it.name }.sorted()

    // --- Without the key ---

    /** Catches the time rule missing, or deleting what is within the limit. */
    @Test
    fun aMemoryPastTheLimitByItsDateGoesAndOneWithinItStays() {
        chatFile("old", now - DAY_MILLIS)
        chatFile("recent", now - DAY_MILLIS)
        memoryFile("old", now - 7 * DAY_MILLIS - 1_000)
        memoryFile("recent", now - 7 * DAY_MILLIS + 1_000)
        sweep()
        assertEquals(listOf("recent.enc"), left())
        assertTrue("the chat of the memory that went stays: its file is within the limit", File(layout.sessions, "old.enc").exists())
    }

    /** Catches the name rule depending on the limit, which "Never" or an unreadable copy of it would switch off. */
    @Test
    fun aMemoryWithoutItsChatGoesWhateverTheLimitSays() {
        for (days in listOf(MessageLifetime.SEVEN_DAYS.storedDays, MessageLifetime.NEVER.storedDays, 5)) {
            chatFile("kept", now - 400 * DAY_MILLIS)
            memoryFile("orphan", now - 1_000)
            memoryFile("kept", now - 400 * DAY_MILLIS)
            memoryFile("x", now, name = "not a chat.enc")
            memoryFile("x", now, name = "notes.txt")
            sweep(days)
            val expected = if (days == MessageLifetime.SEVEN_DAYS.storedDays) listOf("notes.txt") else listOf("kept.enc", "notes.txt")
            assertEquals("days $days", expected, left())
            layout.modelMemory.deleteRecursively()
            layout.sessions.deleteRecursively()
        }
    }

    /** Catches a memory left behind by a chat that the clean-up deletes, though the memory's own date is recent. */
    @Test
    fun aMemoryGoesTogetherWithItsChat() {
        chatFile("c1", now - 30 * DAY_MILLIS)
        memoryFile("c1", now - 1_000)
        sweep()
        assertFalse(File(layout.sessions, "c1.enc").exists())
        assertEquals(emptyList<String>(), left())
    }

    /** Catches temporary files of a save that never ended surviving a start, whatever their date and the limit. */
    @Test
    fun temporaryFilesGoAtEveryStart() {
        for (days in listOf(MessageLifetime.SEVEN_DAYS.storedDays, MessageLifetime.NEVER.storedDays, 5)) {
            chatFile("c1", now)
            memoryFile("c1", now, name = "c1.enc.tmp")
            memoryFile("c1", now, name = "other.tmp")
            sweep(days)
            assertEquals("days $days", emptyList<String>(), left())
        }
    }

    /** Catches a sweep that a save which began before it could outlast: every sweep moves the generation on. */
    @Test
    fun everySweepAndEveryDeletionStopsTheSavesThatBeganBefore() {
        val before = memory.generation
        sweep()
        val afterSweep = memory.generation
        assertTrue(afterSweep > before)
        memory.deleteFor("c1")
        assertTrue(memory.generation > afterSweep)
        val afterDelete = memory.generation
        memory.deleteAll()
        assertTrue(memory.generation > afterDelete)
    }

    // --- With the key: the memory first, then the chat ---

    private fun message(id: String, role: Role, at: Long, text: String) = ChatMessage(id = id, role = role, text = text, createdAtMillis = at)

    private val chat = ChatSession(
        id = "c1",
        title = "Old question",
        createdAtMillis = now - 10 * DAY_MILLIS,
        modelId = "m",
        messages = listOf(
            message("u1", Role.USER, now - 10 * DAY_MILLIS, "Old question"),
            message("a1", Role.ASSISTANT, now - 10 * DAY_MILLIS, "Old answer"),
            message("u2", Role.USER, now - DAY_MILLIS, "New question"),
            message("a2", Role.ASSISTANT, now - DAY_MILLIS, "New answer"),
        ),
    )

    private fun sessionStore(deletion: SavedMemoryDeletion) = SessionStore(
        sessions = SealedDirectory(layout.sessions, DataLayout::sessionPurpose, cipher, layout.kept, "session"),
        indexFile = SealedFile(layout.sessionIndex, DataLayout.SESSION_INDEX_PURPOSE, cipher, layout.kept, "session-index"),
        memory = deletion,
    )

    private fun storedMessages(): List<String>? {
        val file = File(layout.sessions, "c1.enc")
        if (!file.exists()) return null
        val text = cipher.open(DataLayout.sessionPurpose("c1"), file.readBytes()).toString(Charsets.UTF_8)
        return (SessionCodec.decode(text) as SessionCodec.Decoded.Readable).session.messages.map { it.text }
    }

    /**
     * A deletion of the memory that checks the chat has not been written yet, and then stops
     * the process, as a kill between the two steps would.
     */
    private fun crashingAfterTheMemory(expectedChat: List<String>) = SavedMemoryDeletion { id ->
        memory.deleteFor(id)
        assertEquals("the chat is written after its memory is gone", expectedChat, storedMessages())
        throw SimulatedCrash()
    }

    /** Catches a prune, an edit or a deletion that writes the chat before its memory is gone, or forgets the memory. */
    @Test
    fun aPruneAnEditAndADeletionRemoveTheMemoryBeforeTheChatIsWritten() {
        val all = chat.messages.map { it.text }
        val changes: List<Pair<String, (SessionStore) -> Unit>> = listOf(
            "a prune" to { it.expire("c1", MessageLifetime.SEVEN_DAYS, now) },
            "an edit" to { it.save(chat.copy(messages = chat.messages.take(2) + chat.messages[2].copy(text = "Edited")), dropMemoryFirst = true) },
            "a deletion" to { it.delete("c1") },
        )
        for ((case, change) in changes) {
            sessionStore(SavedMemoryDeletion.None).save(chat)
            memoryFile("c1", now - 10 * DAY_MILLIS)
            assertThrows(case, SimulatedCrash::class.java) { change(sessionStore(crashingAfterTheMemory(all))) }
            assertEquals("$case: a stop between the steps leaves no memory", emptyList<String>(), left())
            assertEquals("$case: and the chat as it was", all, storedMessages())

            memoryFile("c1", now - 10 * DAY_MILLIS)
            change(sessionStore(memory))
            assertEquals(case, emptyList<String>(), left())
            assertTrue(case, storedMessages() != all)
        }
    }

    /** Catches a store that deletes the memory at every write: an answer added must not cost it. */
    @Test
    fun aWriteThatChangesNoStoredMessageKeepsTheMemory() {
        val store = sessionStore(memory)
        store.save(chat)
        memoryFile("c1", now - 10 * DAY_MILLIS)
        store.save(chat.copy(messages = chat.messages + message("u3", Role.USER, now, "Another question")))
        assertEquals(listOf("c1.enc"), left())
        assertEquals("nothing had expired: the prune wrote nothing", SessionStore.Expiry.Checked(now - 10 * DAY_MILLIS), store.expire("c1", MessageLifetime.THIRTY_DAYS, now))
        assertEquals(listOf("c1.enc"), left())
    }

    /** A memory that cannot be deleted does not stop the change of its chat. */
    @Test
    fun aMemoryThatCannotBeDeletedDoesNotStopTheChange() {
        val store = sessionStore { throw IOException("cannot delete") }
        store.save(chat)
        store.save(chat.copy(messages = chat.messages.take(2)), dropMemoryFirst = true)
        assertEquals(listOf("Old question", "Old answer"), storedMessages())
        store.delete("c1")
        assertEquals(null, storedMessages())
    }
}
