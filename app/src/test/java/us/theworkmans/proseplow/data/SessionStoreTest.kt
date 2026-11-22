package us.theworkmans.proseplow.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import us.theworkmans.proseplow.DataLayout
import us.theworkmans.proseplow.core.chat.ChatSession
import us.theworkmans.proseplow.core.chat.FakeClock
import us.theworkmans.proseplow.core.chat.MessageStatus
import us.theworkmans.proseplow.core.chat.SequentialIds
import us.theworkmans.proseplow.core.chat.SessionCodec
import us.theworkmans.proseplow.core.engine.EngineError
import us.theworkmans.proseplow.core.engine.FinishReason
import us.theworkmans.proseplow.core.engine.GenerationEvent
import us.theworkmans.proseplow.security.AppFiles
import us.theworkmans.proseplow.security.Envelope
import us.theworkmans.proseplow.security.TestKeys
import java.io.File
import java.io.IOException

/**
 * The chat sessions on disk: only ciphertext, a round trip for everything a chat holds, the
 * files that cannot be read (or only in part) and what is kept of them, and the index that is
 * put right by the files every time it is opened.
 */
class SessionStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val cipher = TestKeys.cipher()
    private val files by lazy { AppFiles(temp.newFolder("files"), temp.newFolder("cache")) }
    private val layout by lazy { DataLayout(files) }
    private val clock = FakeClock()
    private val ids = SequentialIds("m")

    /** A store as a newly opened session makes it: it remembers nothing of what an earlier one read. */
    private fun store() = SessionStore(
        sessions = SealedDirectory(layout.sessions, DataLayout::sessionPurpose, cipher, layout.kept, "session"),
        indexFile = SealedFile(layout.sessionIndex, DataLayout.SESSION_INDEX_PURPOSE, cipher, layout.kept, "session-index"),
    )

    private fun file(id: String) = File(layout.sessions, "$id.enc")

    private fun conversation(id: String, question: String = "What is a furrow?", answer: String = "A trench made by a plough."): ChatSession {
        val asked = ChatSession(id = id, createdAtMillis = clock.nowMillis(), modelId = "qwen3.5-0.8b").withUserTurn(question, clock, ids)
        val assistantId = asked.streamingMessageId!!
        return asked
            .applyEvent(assistantId, GenerationEvent.TextDelta(answer), clock)
            .finished(assistantId, FinishReason.END_OF_TURN, clock)
            .withDraft("and a ridge?")
    }

    /** Puts [json] where the session [id] is stored, sealed as the store would seal it. */
    private fun plant(id: String, json: String) {
        layout.sessions.mkdirs()
        file(id).writeBytes(cipher.seal(DataLayout.sessionPurpose(id), json.toByteArray()))
    }

    private fun everyByteOnDisk(): String =
        files.filesDir.walkTopDown().filter { it.isFile }.joinToString("") { String(it.readBytes(), Charsets.ISO_8859_1) }

    private fun keptCopies(): List<File> = layout.kept.listFiles()?.toList().orEmpty()

    // --- One session ---

    @Test
    fun aSessionComesBackExactlyAsItWasStored() {
        val session = conversation("s1")
        store().save(session)

        val loaded = store().load("s1")

        assertEquals(SessionStore.Loaded.Readable(session), loaded)
        assertEquals("nothing was left out, so nothing is kept", emptyList<File>(), keptCopies())
    }

    @Test
    fun onlyCiphertextReachesTheDisk() {
        val store = store()
        val session = conversation("s1")
        store.save(session)
        store.saveIndex(SessionIndex(activeId = "s1", sessions = listOf(session.summary())))

        assertTrue(Envelope.looksEncrypted(file("s1").readBytes()))
        assertTrue(Envelope.looksEncrypted(layout.sessionIndex.readBytes()))
        val disk = everyByteOnDisk()
        for (secret in listOf("furrow", "trench", "ridge", "qwen", "messages")) assertFalse(secret, disk.contains(secret))
        assertTrue("the lock sees that data exists", files.encryptedDataExists())
    }

    @Test
    fun aReplyThatWasBeingWrittenWhenTheProcessDiedComesBackStopped() {
        val session = ChatSession(id = "s1", createdAtMillis = 1, modelId = "qwen3.5-0.8b").withUserTurn("Hello", clock, ids)
        val partial = session.applyEvent(session.streamingMessageId!!, GenerationEvent.TextDelta("Hel"), clock)
        store().save(partial)

        val loaded = (store().load("s1") as SessionStore.Loaded.Readable).session

        assertEquals(MessageStatus.STOPPED, loaded.messages.last().status)
        assertEquals("Hel", loaded.messages.last().text)
        assertFalse(loaded.isGenerating)
    }

    @Test
    fun aStoredErrorComesBackWithItsTurn() {
        val asked = ChatSession(id = "s1", createdAtMillis = 1, modelId = "qwen3.5-0.8b").withUserTurn("Hello", clock, ids)
        val failed = asked.failed(asked.streamingMessageId, EngineError.LoadFailed("qwen3.5-0.8b", "no such file"), clock)
        store().save(failed)
        assertEquals(SessionStore.Loaded.Readable(failed), store().load("s1"))
    }

    @Test
    fun whatIsNotThereIsMissing() {
        assertSame(SessionStore.Loaded.Missing, store().load("nothing"))
        assertSame("an id that cannot name a file names no session", SessionStore.Loaded.Missing, store().load("../lock"))
    }

    @Test
    fun aFileThatDoesNotDecryptIsUnreadableAndIsNotTouched() {
        store().save(conversation("s1"))
        val damaged = file("s1").readBytes().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        file("s1").writeBytes(damaged)

        assertSame(SessionStore.Loaded.Unreadable, store().load("s1"))

        assertArrayEquals(damaged, file("s1").readBytes())
    }

    @Test
    fun aFileThatDecryptsToSomethingElseIsUnreadableAndIsNotTouched() {
        for (notASession in listOf("not json", "[]", "{\"schemaVersion\":1}", "{\"schemaVersion\":1,\"session\":{\"title\":\"no id\"}}")) {
            plant("s1", notASession)
            val before = file("s1").readBytes()
            assertSame(notASession, SessionStore.Loaded.Unreadable, store().load("s1"))
            assertArrayEquals(before, file("s1").readBytes())
        }
    }

    @Test
    fun aSessionStoredUnderAnotherIdIsNotThatSession() {
        plant("s2", SessionCodec.encode(conversation("s1")))
        assertSame(SessionStore.Loaded.Unreadable, store().load("s2"))
    }

    @Test
    fun aSessionThatCanOnlyBeReadInPartSaysWhatIsMissingAndItsFileIsKept() {
        val session = conversation("s1")
        // The answer was written by a role this version does not know.
        plant("s1", SessionCodec.encode(session).replace("\"role\":\"ASSISTANT\"", "\"role\":\"TOOL\""))
        val original = file("s1").readBytes()
        val store = store()

        val loaded = store.load("s1") as SessionStore.Loaded.Readable

        assertEquals(SessionStore.Incomplete(skippedMessages = 1, errorDropped = false, fromNewerVersion = false), loaded.incomplete)
        assertEquals(listOf(session.messages.first()), loaded.session.messages)
        assertArrayEquals("the file itself is as it was", original, file("s1").readBytes())
        assertArrayEquals("and an exact copy has been put aside", original, keptCopies().single().readBytes())
        assertTrue(keptCopies().single().name.startsWith("session-s1."))

        // The chat can be used: saving replaces the file, and the copy stays.
        store.save(loaded.session.withDraft("carry on"))
        assertEquals(SessionStore.Loaded.Readable(loaded.session.withDraft("carry on")), store().load("s1"))
        assertArrayEquals(original, keptCopies().single().readBytes())
    }

    @Test
    fun readingThePartlyReadableFileAgainKeepsNoSecondCopy() {
        plant("s1", SessionCodec.encode(conversation("s1")).replace("\"role\":\"ASSISTANT\"", "\"role\":\"TOOL\""))
        store().load("s1")
        store().load("s1")
        store().open()
        assertEquals(1, keptCopies().size)
    }

    @Test
    fun aSessionFromANewerVersionIsReadAsFarAsPossibleAndKept() {
        plant("s1", SessionCodec.encode(conversation("s1")).replace("\"schemaVersion\":1", "\"schemaVersion\":7"))

        val loaded = store().load("s1") as SessionStore.Loaded.Readable

        assertEquals(SessionStore.Incomplete(skippedMessages = 0, errorDropped = false, fromNewerVersion = true), loaded.incomplete)
        assertEquals(2, loaded.session.messages.size)
        assertEquals(1, keptCopies().size)
    }

    @Test
    fun whenNoCopyCanBeMadeThePartlyReadableSessionCannotBeSavedOver() {
        plant("s1", SessionCodec.encode(conversation("s1")).replace("\"role\":\"ASSISTANT\"", "\"role\":\"TOOL\""))
        val original = file("s1").readBytes()
        layout.kept.writeText("in the way")
        val store = store()

        val loaded = store.load("s1") as SessionStore.Loaded.Readable
        assertEquals(1, loaded.incomplete?.skippedMessages)
        assertThrows(IOException::class.java) { store.save(loaded.session.withDraft("x")) }

        assertArrayEquals(original, file("s1").readBytes())
    }

    @Test
    fun deletingASessionRemovesItsFileAndEveryCopyOfIt() {
        plant("s1", SessionCodec.encode(conversation("s1")).replace("\"role\":\"ASSISTANT\"", "\"role\":\"TOOL\""))
        val store = store()
        store.load("s1")
        store.save(conversation("s2"))
        assertEquals(1, keptCopies().size)

        store.delete("s1")

        assertFalse(file("s1").exists())
        assertEquals(emptyList<File>(), keptCopies())
        assertTrue(file("s2").exists())
        store.delete("s1")
        store.delete("../lock")
    }

    @Test
    fun aSessionThatCannotBeReadCanStillBeDeleted() {
        layout.sessions.mkdirs()
        file("s1").writeBytes(byteArrayOf(1, 2, 3))
        val store = store()
        assertSame(SessionStore.Loaded.Unreadable, store.load("s1"))
        store.delete("s1")
        assertFalse(file("s1").exists())
    }

    // --- The index ---

    @Test
    fun aFirstStartHasNoChatsAndWritesNothing() {
        val opened = store().open()

        assertEquals(SessionStore.Opened(SessionIndex(), stale = false), opened)
        assertFalse(files.encryptedDataExists())
        assertFalse(layout.sessions.exists())
    }

    @Test
    fun theIndexComesBackAsItWasStored() {
        val store = store()
        val first = conversation("s1")
        val second = conversation("s2", question = "Another")
        store.save(first)
        store.save(second)
        val index = SessionIndex(activeId = "s2", sessions = listOf(second.summary(), first.summary()))
        store.saveIndex(index)

        val reopened = store()
        assertEquals(SessionStore.Opened(index, stale = false), reopened.open())
        reopened.saveIndex(index.copy(activeId = "s1"))
        assertEquals("an index that was understood is replaced without a copy", emptyList<File>(), keptCopies())
        assertEquals("s1", store().open().index.activeId)
    }

    @Test
    fun aSessionTheIndexDoesNotListIsAdopted() {
        val store = store()
        val listed = conversation("s1")
        val orphan = conversation("s2", question = "Written just before the process died")
        store.save(listed)
        store.saveIndex(SessionIndex(activeId = "s1", sessions = listOf(listed.summary())))
        store.save(orphan)

        val opened = store().open()

        assertEquals(SessionIndex(activeId = "s1", sessions = listOf(listed.summary(), orphan.summary())), opened.index)
        assertTrue(opened.stale)
    }

    @Test
    fun anIndexEntryWithoutAFileIsDropped() {
        val store = store()
        val kept = conversation("s1")
        val deleted = conversation("s2")
        store.save(kept)
        store.save(deleted)
        store.saveIndex(SessionIndex(activeId = "s2", sessions = listOf(deleted.summary(), kept.summary())))
        // The file went, and the process died before the index followed.
        assertTrue(file("s2").delete())

        val opened = store().open()

        assertEquals(SessionIndex(activeId = null, sessions = listOf(kept.summary())), opened.index)
        assertTrue(opened.stale)
    }

    @Test
    fun anIndexThatCannotBeReadIsRebuiltFromTheSessionsAndKeptWhenItIsReplaced() {
        val first = conversation("s1")
        val second = conversation("s2", question = "Another")
        store().save(first)
        store().save(second)
        layout.sessionIndex.writeBytes(byteArrayOf(7, 7, 7))
        val store = store()

        val opened = store.open()

        assertEquals(SessionIndex(activeId = null, sessions = listOf(first.summary(), second.summary())), opened.index)
        assertTrue(opened.stale)
        assertArrayEquals("opening replaces nothing", byteArrayOf(7, 7, 7), layout.sessionIndex.readBytes())

        store.saveIndex(opened.index)
        assertArrayEquals(byteArrayOf(7, 7, 7), keptCopies().single().readBytes())
        assertTrue(keptCopies().single().name.startsWith("session-index."))
        assertEquals(SessionStore.Opened(opened.index, stale = false), store().open())
    }

    @Test
    fun aSessionThatCannotBeReadIsListedSoThatItCanBeSeenAndDeleted() {
        layout.sessions.mkdirs()
        file("s9").writeBytes(byteArrayOf(1, 2, 3))
        file("s9").setLastModified(1_700_000_000_000)

        val opened = store().open()

        val entry = opened.index.sessions.single()
        assertEquals("s9", entry.id)
        assertEquals("", entry.title)
        assertEquals(1_700_000_000_000, entry.updatedAtMillis)
        assertArrayEquals(byteArrayOf(1, 2, 3), file("s9").readBytes())
        assertEquals(emptyList<File>(), keptCopies())
    }

    @Test
    fun openingSweepsWhatInterruptedWritesLeftAndIgnoresWhatIsNotASession() {
        val store = store()
        store.save(conversation("s1"))
        File(layout.sessions, "s1.enc.tmp").writeBytes(byteArrayOf(1))
        File(layout.sessions, "s2.enc.tmp").writeBytes(byteArrayOf(1))
        File(layout.sessions, "readme.txt").writeText("x")

        val opened = store().open()

        assertEquals(listOf("s1"), opened.index.sessions.map { it.id })
        assertEquals(listOf("readme.txt", "s1.enc"), layout.sessions.list()!!.sorted())
    }

    @Test
    fun whenTheSessionsCannotBeListedOpeningFails() {
        layout.sessions.writeText("a file where the directory should be")
        assertThrows(IOException::class.java) { store().open() }
    }
}
