package us.theworkmans.proseplow.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import us.theworkmans.proseplow.AppSession
import us.theworkmans.proseplow.DataLayout
import us.theworkmans.proseplow.SessionFixture
import us.theworkmans.proseplow.close
import us.theworkmans.proseplow.core.chat.MessageLifetime
import us.theworkmans.proseplow.core.chat.MessageLifetime.Companion.DAY_MILLIS
import us.theworkmans.proseplow.core.chat.MessageLifetime.Companion.HOUR_MILLIS
import us.theworkmans.proseplow.core.chat.SequentialIds
import us.theworkmans.proseplow.core.model.ModelCatalog
import us.theworkmans.proseplow.openSession

/**
 * The expiry of messages in an unlocked session, through the real stores and writer with a
 * scripted engine on virtual time: at the unlock, when a chat is opened, on the timer, when the
 * setting is shortened, and around a reply that is being written.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageExpiryChatTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var app: SessionFixture
    private val week = 7 * DAY_MILLIS

    private fun TestScope.unlocked(expiryIntervalMillis: Long? = null): AppSession {
        if (!::app.isInitialized) app = SessionFixture(temp.root, testScheduler)
        val session = if (expiryIntervalMillis == null) {
            app.open()
        } else {
            AppSession(app.cipher, app.layout, app.engine, app.models, app.dispatcher, app.dispatcher, app.clock, SequentialIds("t-"), expiryIntervalMillis = expiryIntervalMillis)
        }
        runCurrent()
        return session
    }

    private val AppSession.state: ChatState get() = chat.state.value

    private fun TestScope.exchange(session: AppSession, text: String, reply: String = "An answer to $text") {
        app.engine.enqueueReply(listOf(reply))
        assertTrue(session.chat.send(session.state.open.id, text))
        runCurrent()
    }

    private suspend fun AppSession.nextNotice(): ChatNotice? = withTimeoutOrNull(1) { chat.notices.first() }

    @Test
    fun whatExpiredIsGoneAtTheUnlockBeforeAnyChatIsShown() = runTest {
        val first = unlocked()
        exchange(first, "Old question")
        app.clock.advance(2 * DAY_MILLIS)
        exchange(first, "Newer question")
        val id = first.state.open.id
        first.close()

        app.clock.advance(5 * DAY_MILLIS + 1)
        val second = unlocked()
        val shown = second.openSession
        assertEquals(id, shown.id)
        assertEquals(listOf("Newer question", "An answer to Newer question"), shown.messages.map { it.text })
        assertEquals("the title follows what remains", "Newer question", shown.title)
        assertEquals("Newer question", second.state.chats.single().title)
        assertEquals(2, app.stored(id)!!.messages.size)
        second.close()
    }

    @Test
    fun aChatIsNotPrunedWhileItsReplyIsWrittenAndIsAsSoonAsItEnds() = runTest {
        val session = unlocked()
        exchange(session, "Old question")
        app.clock.advance(week + 1)
        val forgotten = app.engine.forgetCount
        app.engine.enqueueReply(listOf("Slow ", "answer"), millisBetween = 100)
        assertTrue(session.chat.send(session.state.open.id, "New question"))
        runCurrent()
        advanceTimeBy(100)
        runCurrent()

        session.chat.expireMessages()
        runCurrent()
        assertEquals("nothing is removed from a chat whose reply is being written", 4, session.openSession.messages.size)
        assertEquals(forgotten, app.engine.forgetCount)

        advanceTimeBy(200)
        runCurrent()
        assertNull(session.state.turn)
        assertEquals(listOf("New question", "Slow answer"), session.openSession.messages.map { it.text })
        assertEquals("the engine forgets the conversation it held, which was pruned", forgotten + 1, app.engine.forgetCount)
        session.close()
    }

    @Test
    fun aChatThatExpiresWholeWhileUnlockedIsDeletedQuietlyAndTheEngineForgetsIt() = runTest {
        val session = unlocked()
        exchange(session, "Soon gone")
        val gone = session.state.open.id
        session.chat.newChat()
        app.clock.advance(week + 1)
        val forgotten = app.engine.forgetCount

        session.chat.expireMessages()
        runCurrent()

        assertTrue(session.state.chats.none { it.id == gone })
        assertNull(app.stored(gone))
        assertNull("nothing is said about it", session.nextNotice())
        assertEquals(forgotten + 1, app.engine.forgetCount)
        session.close()
    }

    @Test
    fun theChatOnScreenThatExpiresWholeGivesWayToANewOne() = runTest {
        val session = unlocked()
        exchange(session, "On screen")
        val gone = session.state.open.id
        app.clock.advance(week + 1)
        session.chat.expireMessages()
        runCurrent()
        assertNotEquals(gone, session.state.open.id)
        assertTrue(session.openSession.messages.isEmpty())
        assertTrue(session.state.chats.isEmpty())
        session.close()
    }

    @Test
    fun theTimerPrunesWhileTheSessionLives() = runTest {
        val session = unlocked(expiryIntervalMillis = HOUR_MILLIS)
        exchange(session, "Hourly")
        val id = session.state.open.id
        app.clock.advance(week + 1)
        advanceTimeBy(HOUR_MILLIS - 1)
        runCurrent()
        assertEquals(1, session.state.chats.size)
        advanceTimeBy(2)
        runCurrent()
        assertTrue(session.state.chats.isEmpty())
        assertNull(app.stored(id))
        session.close()
    }

    @Test
    fun aStoredChatIsPrunedWhenItIsOpened() = runTest {
        val first = unlocked()
        exchange(first, "Old question")
        app.clock.advance(3 * DAY_MILLIS)
        exchange(first, "Newer question")
        val stored = first.state.open.id
        first.chat.newChat()
        exchange(first, "Another chat")
        first.close()

        // Nothing has expired at this unlock; the stored chat is not read.
        val second = unlocked()
        app.clock.advance(4 * DAY_MILLIS + 1)
        second.chat.openChat(stored)
        runCurrent()
        assertEquals(stored, second.state.open.id)
        assertEquals(listOf("Newer question", "An answer to Newer question"), second.openSession.messages.map { it.text })
        assertEquals(2, app.stored(stored)!!.messages.size)
        second.close()
    }

    @Test
    fun aStoredChatNotInMemoryIsPrunedByThePassAndItsEntryFollows() = runTest {
        val first = unlocked()
        exchange(first, "Old question")
        app.clock.advance(3 * DAY_MILLIS)
        exchange(first, "Newer question")
        val stored = first.state.open.id
        first.chat.newChat()
        exchange(first, "Another chat")
        first.close()

        val second = unlocked()
        app.clock.advance(4 * DAY_MILLIS + 1)
        second.chat.expireMessages()
        second.chat.flush()
        runCurrent()
        val entry = second.state.chats.single { it.id == stored }
        assertEquals("Newer question", entry.title)
        assertEquals(2, entry.messageCount)
        assertEquals(2, app.stored(stored)!!.messages.size)
        second.close()
    }

    @Test
    fun shorteningTheSettingDeletesAtOnceAndNeverKeepsEverything() = runTest {
        val session = unlocked()
        exchange(session, "Two days old")
        app.clock.advance(2 * DAY_MILLIS)
        session.changeMessageLifetime(MessageLifetime.NEVER)
        app.clock.advance(400 * DAY_MILLIS)
        session.chat.expireMessages()
        runCurrent()
        assertEquals("Never removes nothing", 2, session.openSession.messages.size)

        session.changeMessageLifetime(MessageLifetime.ONE_DAY)
        runCurrent()
        assertTrue(session.openSession.messages.isEmpty())
        assertEquals(MessageLifetime.ONE_DAY, session.messageLifetime)
        session.close()
    }

    /** A session as the lock opens one, handed the readable copy of the limit, [lockedDays]. */
    private fun TestScope.unlockedWithCopy(lockedDays: Int): AppSession {
        val session = AppSession(
            app.cipher, app.layout, app.engine, app.models, app.dispatcher, app.dispatcher, app.clock, SequentialIds("c$lockedDays-"),
            lockedMessageLifetimeDays = lockedDays,
        )
        runCurrent()
        return session
    }

    /** One chat with one exchange, ten days old by the time this returns; then the session is closed. */
    private suspend fun TestScope.aTenDayOldChat(): String {
        val first = unlocked()
        first.changeMessageLifetime(MessageLifetime.NEVER)
        exchange(first, "Ten days old")
        val id = first.state.open.id
        runCurrent()
        first.close()
        app.clock.advance(10 * DAY_MILLIS)
        return id
    }

    private fun storedSettings(): String =
        app.cipher.open(DataLayout.SETTINGS_PURPOSE, app.layout.settings.readBytes()).toString(Charsets.UTF_8)

    /**
     * Settings that cannot be read must never make the default delete what the user chose to
     * keep: the limit is the lock's readable copy, Never here, which then stays as it was, and
     * the settings get it back with their next change. Catches the data loss of a default of 7
     * days applied because one file could not be read.
     */
    @Test
    fun unreadableSettingsTakeTheLocksCopyAndNeverDeletesNothing() = runTest {
        val id = aTenDayOldChat()
        app.layout.settings.writeBytes(byteArrayOf(1, 2, 3))

        val session = unlockedWithCopy(lockedDays = 0)
        assertEquals(2, session.openSession.messages.size)
        assertEquals("what the lock is told, and so writes back: the copy as it was", 0, session.messageLifetimeDays)
        session.chat.expireMessages()
        runCurrent()
        assertEquals(2, app.stored(id)!!.messages.size)

        session.settings.update(urgent = true) { it.withSystemPrompt(ModelCatalog.default.id, "Be brief.") }
        session.close()
        assertTrue("the settings agree with the copy again", storedSettings().contains("\"messageLifetimeDays\":0"))
    }

    @Test
    fun unreadableSettingsWithACopyOfThirtyDaysUseThirty() = runTest {
        val id = aTenDayOldChat()
        app.layout.settings.writeBytes(byteArrayOf(1, 2, 3))
        val session = unlockedWithCopy(lockedDays = 30)
        assertEquals(2, app.stored(id)!!.messages.size)
        assertEquals(30, session.messageLifetimeDays)
        session.close()
    }

    @Test
    fun aStoredLimitThatIsNotAChoiceIsTreatedAsUnreadable() = runTest {
        val id = aTenDayOldChat()
        val text = """{"schemaVersion":1,"settings":{"systemPrompts":{},"models":{},"messageLifetimeDays":2}}"""
        app.layout.settings.writeBytes(app.cipher.seal(DataLayout.SETTINGS_PURPOSE, text.toByteArray()))
        val session = unlockedWithCopy(lockedDays = 30)
        assertEquals(2, app.stored(id)!!.messages.size)
        assertEquals(30, session.messageLifetimeDays)
        session.close()
    }

    @Test
    fun readableSettingsWithoutALimitMeanSevenDaysWhateverTheCopySays() = runTest {
        val id = aTenDayOldChat()
        val text = """{"schemaVersion":1,"settings":{"systemPrompts":{},"models":{}}}"""
        app.layout.settings.writeBytes(app.cipher.seal(DataLayout.SETTINGS_PURPOSE, text.toByteArray()))
        val session = unlockedWithCopy(lockedDays = 30)
        assertNull("seven days: the ten-day-old chat is gone", app.stored(id))
        assertEquals(7, session.messageLifetimeDays)
        session.close()
    }

    /** What the user is typing in the chat on screen survives that chat's expiry, in the chat that takes its place. */
    @Test
    fun theChatOnScreenThatExpiresWholeHandsItsDraftToItsReplacement() = runTest {
        val session = unlocked()
        exchange(session, "On screen")
        val gone = session.state.open.id
        val model = session.openSession.modelId
        session.chat.setDraft(gone, "half a sentence being typed")
        app.clock.advance(week + 1)
        session.chat.expireMessages()
        runCurrent()
        val replacement = session.openSession
        assertNotEquals(gone, replacement.id)
        assertEquals("half a sentence being typed", replacement.draft)
        assertEquals(model, replacement.modelId)
        assertTrue(replacement.messages.isEmpty())
        assertEquals("it is stored as any chat with a draft is", listOf(replacement.id), session.state.chats.map { it.id })
        session.close()
        assertEquals("half a sentence being typed", app.stored(replacement.id)!!.draft)
    }

    /** How long ago a chat changed is measured on the clock it was dated by, not on the phone's wall clock. */
    @Test
    fun theDrawerMeasuresAgainstTheTrustedTime() = runTest {
        val session = unlocked()
        app.clock.advance(12_345)
        assertEquals(app.clock.now, session.chat.nowMillis())
        session.close()
    }
}
