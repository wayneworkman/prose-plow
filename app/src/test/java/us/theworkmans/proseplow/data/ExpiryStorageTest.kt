package us.theworkmans.proseplow.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
import us.theworkmans.proseplow.core.chat.SessionSummary
import us.theworkmans.proseplow.security.AppFiles
import us.theworkmans.proseplow.security.BlobCipher
import us.theworkmans.proseplow.security.LockConfig
import us.theworkmans.proseplow.security.TestKeys
import java.io.File

/**
 * The expiry of messages where it meets the disk: the date every save gives a chat's file, the
 * clean-up that needs no key and goes by those dates alone, and the unlocked pass over the stored
 * chats that corrects the list of chats afterwards.
 */
class ExpiryStorageTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val cipher = TestKeys.cipher()
    private val files by lazy { AppFiles(temp.newFolder("files"), temp.newFolder("cache")) }
    private val layout by lazy { DataLayout(files) }

    /** A recent moment, in whole seconds: file systems keep dates to the second at least. */
    private val now = 1_700_000_000_000L
    private val week = 7 * DAY_MILLIS

    private var dateSetting: (File, Long) -> Boolean = { file, millis -> file.setLastModified(millis) }

    /** The chat files decrypted so far, by id: what a pass has read. */
    private val chatsRead = mutableListOf<String>()

    private val counting = object : BlobCipher {
        override fun seal(purpose: String, plaintext: ByteArray): ByteArray = cipher.seal(purpose, plaintext)

        override fun open(purpose: String, blob: ByteArray): ByteArray {
            if (purpose.startsWith("session:")) chatsRead += purpose.removePrefix("session:")
            return cipher.open(purpose, blob)
        }
    }

    private fun store() = SessionStore(
        sessions = SealedDirectory(layout.sessions, DataLayout::sessionPurpose, counting, layout.kept, "session"),
        indexFile = SealedFile(layout.sessionIndex, DataLayout.SESSION_INDEX_PURPOSE, counting, layout.kept, "session-index"),
        setFileDate = { file, millis -> dateSetting(file, millis) },
    )

    private fun sweep(lifetime: MessageLifetime = MessageLifetime.SEVEN_DAYS, at: Long = now) =
        ExpiredFiles(layout.sessions, layout.kept, layout.sessionIndex).sweep(lifetime.storedDays, at)

    private fun file(id: String) = File(layout.sessions, "$id.enc")

    private fun message(id: String, role: Role, at: Long, text: String = "text of $id") =
        ChatMessage(id = id, role = role, text = text, createdAtMillis = at)

    /** A chat of one or more turns; each pair is (question time, answer time). */
    private fun chat(id: String, vararg turns: Pair<Long, Long>): ChatSession {
        val messages = turns.flatMapIndexed { n, (asked, answered) ->
            listOf(message("$id-u$n", Role.USER, asked, "Question $n of $id"), message("$id-a$n", Role.ASSISTANT, answered, "Answer $n of $id"))
        }
        return ChatSession(id = id, title = ChatSession.deriveTitle(messages.first().text), createdAtMillis = turns.first().first, modelId = "m", messages = messages)
    }

    /** A kept copy of the chat [id], dated [at], as [SealedFile.keepCopy] names one. */
    private fun keptCopy(id: String, at: Long, digest: String = "0011223344556677"): File {
        layout.kept.mkdirs()
        return File(layout.kept, "session-$id.$digest.enc").apply {
            writeBytes(cipher.seal(DataLayout.sessionPurpose(id), "old".toByteArray()))
            setLastModified(at)
        }
    }

    private fun stored(id: String): ChatSession =
        (SessionCodec.decode(cipher.open(DataLayout.sessionPurpose(id), file(id).readBytes()).toString(Charsets.UTF_8)) as SessionCodec.Decoded.Readable).session

    // --- The date of a chat's file ---

    @Test
    fun everySaveDatesTheFileByItsNewestMessage() {
        val newest = now - 3 * DAY_MILLIS
        store().save(chat("c", now - 5 * DAY_MILLIS to newest))
        assertEquals(newest, file("c").lastModified())
    }

    @Test
    fun aDateThatCannotBeSetLeavesTheTimeOfWritingWhichIsLater() {
        dateSetting = { _, _ -> false }
        val before = System.currentTimeMillis() / 1000 * 1000
        store().save(chat("c", now - 30 * DAY_MILLIS to now - 30 * DAY_MILLIS))
        assertTrue("the file keeps the time it was written", file("c").lastModified() >= before)
        sweep(at = now)
        assertTrue("so the locked clean-up deletes it later, not earlier", file("c").exists())
    }

    @Test
    fun aKeptCopyKeepsTheDateOfTheFileItWasMadeFrom() {
        store().save(chat("c", now - 2 * DAY_MILLIS to now - 2 * DAY_MILLIS))
        val copy = SealedDirectory(layout.sessions, DataLayout::sessionPurpose, cipher, layout.kept, "session").file("c").keepCopy()
        assertEquals(now - 2 * DAY_MILLIS, copy!!.lastModified())
    }

    // --- The clean-up without the key ---

    @Test
    fun aChatPastTheLimitGoesWithItsKeptCopiesAndARecentOneStays() {
        val s = store()
        s.save(chat("old", now - week - 2_000 to now - week - 1_000))
        s.save(chat("recent", now - week - 2_000 to now - week + 1_000))
        val oldCopy = keptCopy("old", at = now)
        val recentCopy = keptCopy("recent", at = now)
        File(layout.sessions, "old.enc.tmp").writeBytes(byteArrayOf(1))

        sweep()

        assertFalse(file("old").exists())
        assertFalse("its copies go with it, however recent", oldCopy.exists())
        assertFalse(File(layout.sessions, "old.enc.tmp").exists())
        assertTrue(file("recent").exists())
        assertTrue(recentCopy.exists())
    }

    @Test
    fun keptCopiesAndInterruptedWritesGoByTheirOwnDates() {
        store().save(chat("alive", now - DAY_MILLIS to now))
        val oldCopy = keptCopy("alive", at = now - week - 1_000, digest = "aaaaaaaaaaaaaaaa")
        val newCopy = keptCopy("alive", at = now - DAY_MILLIS, digest = "bbbbbbbbbbbbbbbb")
        val indexCopy = File(layout.kept, "session-index.cccccccccccccccc.enc").apply { writeBytes(byteArrayOf(1)); setLastModified(now - week - 1_000) }
        val settingsCopy = File(layout.kept, "settings.dddddddddddddddd.enc").apply { writeBytes(byteArrayOf(1)); setLastModified(now - week - 1_000) }
        val oldTemp = File(layout.sessions, "gone.enc.tmp").apply { writeBytes(byteArrayOf(1)); setLastModified(now - week - 1_000) }
        val newTemp = File(layout.sessions, "busy.enc.tmp").apply { writeBytes(byteArrayOf(1)); setLastModified(now) }
        val indexTemp = File(files.filesDir, "session-index.enc.tmp").apply { writeBytes(byteArrayOf(1)); setLastModified(now - week - 1_000) }

        sweep()

        assertTrue(file("alive").exists())
        assertFalse(oldCopy.exists())
        assertTrue(newCopy.exists())
        assertFalse("a copy of the list of chats holds titles", indexCopy.exists())
        assertTrue("a copy of the settings holds no message", settingsCopy.exists())
        assertFalse(oldTemp.exists())
        assertTrue(newTemp.exists())
        assertFalse(indexTemp.exists())
    }

    @Test
    fun neverDeletesNothingAndAFileWithoutADateIsLeftAlone() {
        store().save(chat("old", 1_000L to 1_000L))
        sweep(MessageLifetime.NEVER)
        assertTrue(file("old").exists())
        file("old").setLastModified(0)
        sweep()
        assertTrue(file("old").exists())
    }

    // --- The unlocked pass ---

    @Test
    fun expiredTurnsAreRemovedAndTheTitleFollowsWhatRemains() {
        store().save(chat("c", now - week - 1_000 to now - week - 1_000, now to now))
        val expiry = store().expire("c", MessageLifetime.SEVEN_DAYS, now)
        val kept = (expiry as SessionStore.Expiry.Pruned).session
        assertEquals(listOf("c-u1", "c-a1"), stored("c").messages.map { it.id })
        assertEquals("Question 1 of c", stored("c").title)
        assertEquals(kept, stored("c"))
        assertEquals(now, file("c").lastModified())
    }

    @Test
    fun aChatLeftWithoutMessagesIsDeletedWithItsKeptCopies() {
        store().save(chat("c", now - week - 1_000 to now - week - 1_000))
        val copy = keptCopy("c", at = now)
        assertSame(SessionStore.Expiry.Deleted, store().expire("c", MessageLifetime.SEVEN_DAYS, now))
        assertFalse(file("c").exists())
        assertFalse(copy.exists())
    }

    @Test
    fun aChatOfDraftOnlyOrUnreadableGoesByItsFileDate() {
        store().save(ChatSession(id = "draft", createdAtMillis = 1, modelId = "m", draft = "half a thought"))
        file("draft").setLastModified(now - DAY_MILLIS)
        assertSame(SessionStore.Expiry.Unchanged, store().expire("draft", MessageLifetime.SEVEN_DAYS, now))
        file("draft").setLastModified(now - week - 1_000)
        assertSame(SessionStore.Expiry.Deleted, store().expire("draft", MessageLifetime.SEVEN_DAYS, now))

        layout.sessions.mkdirs()
        file("bad").writeBytes(byteArrayOf(1, 2, 3))
        file("bad").setLastModified(now - DAY_MILLIS)
        assertSame(SessionStore.Expiry.Unchanged, store().expire("bad", MessageLifetime.SEVEN_DAYS, now))
        file("bad").setLastModified(now - week - 1_000)
        assertSame(SessionStore.Expiry.Deleted, store().expire("bad", MessageLifetime.SEVEN_DAYS, now))
        assertFalse(file("bad").exists())
    }

    @Test
    fun undatedAndFutureMessagesGetUsableDatesThatAreStored() {
        val undated = chat("c", 0L to 0L, now to now)
        store().save(undated)
        file("c").setLastModified(now - 2 * DAY_MILLIS)
        store().expire("c", MessageLifetime.SEVEN_DAYS, now)
        assertEquals(listOf(now - 2 * DAY_MILLIS, now - 2 * DAY_MILLIS, now, now), stored("c").messages.map { it.createdAtMillis })

        store().save(chat("f", now + 365 * DAY_MILLIS to now + 365 * DAY_MILLIS))
        store().expire("f", MessageLifetime.SEVEN_DAYS, now)
        assertEquals(listOf(now, now), stored("f").messages.map { it.createdAtMillis })
        assertEquals(now, file("f").lastModified())
    }

    @Test
    fun theListIsCorrectedQuietlyAfterTheLockedCleanUp() {
        val s = store()
        val old = chat("old", now - week - 2_000 to now - week - 1_000)
        val recent = chat("recent", now - DAY_MILLIS to now)
        s.save(old)
        s.save(recent)
        s.saveIndex(SessionIndex(activeId = "old", sessions = listOf(old.summary(), recent.summary())))
        sweep()
        assertFalse(file("old").exists())

        val opened = store().let { it.expireAll(it.open(), MessageLifetime.SEVEN_DAYS, now) }
        assertEquals(listOf("recent"), opened.index.sessions.map { it.id })
        assertNull("the chat that was on screen is gone, so a new one is shown", opened.index.activeId)
        assertTrue("the list is written again", opened.stale)
    }

    @Test
    fun theUnlockedPassSkipsChatsTheirSummaryRulesOutAndPrunesTheRest() {
        val s = store()
        val trusting = chat("trusting", now - week - 1_000 to now - week - 1_000, now to now)
        val pruned = chat("pruned", now - week - 1_000 to now - week - 1_000, now to now)
        s.save(trusting)
        s.save(pruned)
        // The summary of the first claims nothing in it is old: the pass believes it and reads nothing.
        val claims = trusting.summary().copy(oldestMessageAtMillis = now)
        s.saveIndex(SessionIndex(sessions = listOf(claims, pruned.summary())))

        val opened = store().let { it.expireAll(it.open(), MessageLifetime.SEVEN_DAYS, now) }

        assertEquals(4, stored("trusting").messages.size)
        assertEquals(2, stored("pruned").messages.size)
        val summary: SessionSummary = opened.index.sessions.single { it.id == "pruned" }
        assertEquals("Question 1 of pruned", summary.title)
        assertEquals(now, summary.oldestMessageAtMillis)
        assertSame("with Never nothing is read at all", opened, store().expireAll(opened, MessageLifetime.NEVER, now))
    }

    @Test
    fun aChatReadOnlyInPartIsNotDeletedOnWhatCouldBeReadAlone() {
        val readable = SessionCodec.encode(chat("p", now - week - 1_000 to now - week - 1_000))
        // One more message of a role this version does not know: it may be newer than the rest.
        val partial = readable.replace("\"messages\":[", "\"messages\":[{\"id\":\"x\",\"role\":\"TOOL\",\"text\":\"t\",\"createdAtMillis\":$now},")
        layout.sessions.mkdirs()
        file("p").writeBytes(cipher.seal(DataLayout.sessionPurpose("p"), partial.toByteArray()))
        file("p").setLastModified(now)
        assertTrue("kept, as read", store().expire("p", MessageLifetime.SEVEN_DAYS, now) is SessionStore.Expiry.Checked)
        assertTrue(file("p").exists())
        file("p").setLastModified(now - week - 1_000)
        assertSame(SessionStore.Expiry.Deleted, store().expire("p", MessageLifetime.SEVEN_DAYS, now))
    }

    /**
     * The lock keeps the limit as a number of days it validates by itself, without knowing the
     * chats' choices: the two lists, and the rule of what deletes now, must be the same.
     */
    @Test
    fun theLockKnowsExactlyTheChoicesOfTheChats() {
        assertEquals(MessageLifetime.entries.map { it.storedDays }, LockConfig.MESSAGE_LIFETIME_CHOICES_DAYS)
        assertEquals(MessageLifetime.DEFAULT.storedDays, LockConfig.DEFAULT_MESSAGE_LIFETIME_DAYS)
        assertEquals(MessageLifetime.NEVER_STORED, LockConfig.MESSAGE_LIFETIME_NEVER)
        for (to in MessageLifetime.entries) for (from in MessageLifetime.entries) {
            assertEquals("$from to $to", to.isShorterThan(from), LockConfig.messageLifetimeDeletesNow(to.storedDays, from.storedDays))
        }
    }

    /**
     * A missing file is dropped from the list without a word only when its entry says the whole
     * chat had expired (its last change is past the limit), as the locked clean-up leaves it.
     * One whose entry only says its oldest message is old was not known to have expired: it
     * stays listed, and opening it says it is gone. Catches a pass that hides a lost chat.
     */
    @Test
    fun aMissingFileIsDroppedQuietlyOnlyWhenItsEntrySaysTheWholeChatHadExpired() {
        val expired = SessionSummary(id = "expired", createdAtMillis = now - 9 * DAY_MILLIS, updatedAtMillis = now - week - 1_000, oldestMessageAtMillis = now - 9 * DAY_MILLIS)
        val lost = SessionSummary(id = "lost", createdAtMillis = now - 9 * DAY_MILLIS, updatedAtMillis = now - DAY_MILLIS, oldestMessageAtMillis = now - 9 * DAY_MILLIS)
        val opened = SessionStore.Opened(SessionIndex(activeId = "lost", sessions = listOf(expired, lost)), stale = false)

        val result = store().expireAll(opened, MessageLifetime.SEVEN_DAYS, now)

        assertEquals(listOf("lost"), result.index.sessions.map { it.id })
        assertEquals("lost", result.index.activeId)
        assertTrue(result.stale)
    }

    /**
     * An entry without the date of its oldest message (every chat of an installation from
     * before it existed) is read once, and the date it learns is kept in the list: the next
     * unlock does not decrypt that chat again until its oldest message is past the limit.
     */
    @Test
    fun aChatReadOnceIsNotReadAgainAtTheNextUnlock() {
        val s = store()
        val recent = chat("recent", now - 2 * DAY_MILLIS to now - 2 * DAY_MILLIS)
        s.save(recent)
        s.saveIndex(SessionIndex(sessions = listOf(recent.summary().copy(oldestMessageAtMillis = null))))
        chatsRead.clear()

        val first = store().let { it.expireAll(it.open(), MessageLifetime.SEVEN_DAYS, now) }
        assertEquals(listOf("recent"), chatsRead)
        assertEquals(now - 2 * DAY_MILLIS, first.index.sessions.single().oldestMessageAtMillis)
        assertTrue("the list is written again with what was learned", first.stale)
        store().saveIndex(first.index)

        chatsRead.clear()
        val second = store().let { it.expireAll(it.open(), MessageLifetime.SEVEN_DAYS, now + DAY_MILLIS) }
        assertEquals(emptyList<String>(), chatsRead)
        assertFalse(second.stale)
    }
}
