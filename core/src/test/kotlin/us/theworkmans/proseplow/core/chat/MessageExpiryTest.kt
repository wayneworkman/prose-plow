package us.theworkmans.proseplow.core.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import us.theworkmans.proseplow.core.chat.MessageLifetime.Companion.DAY_MILLIS
import us.theworkmans.proseplow.core.chat.MessageLifetime.Companion.HOUR_MILLIS

class MessageExpiryTest {

    private val now = 1_000 * DAY_MILLIS
    private val week = 7 * DAY_MILLIS

    private fun user(id: String, at: Long, text: String = id) = ChatMessage(id = id, role = Role.USER, text = text, createdAtMillis = at)

    private fun reply(id: String, at: Long) = ChatMessage(id = id, role = Role.ASSISTANT, text = id, createdAtMillis = at, modelId = "m")

    private fun chat(vararg messages: ChatMessage, title: String = ChatSession.deriveTitle(messages.firstOrNull { it.role == Role.USER }?.text.orEmpty())) =
        ChatSession(id = "c", title = title, createdAtMillis = 1, modelId = "m", messages = messages.toList())

    private fun prune(session: ChatSession, lifetime: MessageLifetime = MessageLifetime.SEVEN_DAYS, at: Long = now) =
        MessageExpiry.prune(session, lifetime, at)

    // --- The choices ---

    @Test
    fun `the limits and their steps`() {
        assertEquals(DAY_MILLIS, MessageLifetime.ONE_DAY.limitMillis)
        assertEquals(3 * HOUR_MILLIS, MessageLifetime.ONE_DAY.stepMillis)
        for (lifetime in listOf(MessageLifetime.SEVEN_DAYS, MessageLifetime.THIRTY_DAYS, MessageLifetime.NINETY_DAYS)) {
            assertEquals(lifetime.name, DAY_MILLIS, lifetime.stepMillis)
        }
        assertNull(MessageLifetime.NEVER.limitMillis)
        assertEquals(MessageLifetime.SEVEN_DAYS, MessageLifetime.DEFAULT)
    }

    @Test
    fun `stored days name exactly the five choices`() {
        for (lifetime in MessageLifetime.entries) assertEquals(lifetime, MessageLifetime.fromStoredDays(lifetime.storedDays))
        assertEquals(MessageLifetime.NEVER, MessageLifetime.fromStoredDays(0))
        assertNull(MessageLifetime.fromStoredDays(2))
        assertNull(MessageLifetime.fromStoredDays(-7))
    }

    @Test
    fun `shorter is a limit below the one before, or any limit after Never`() {
        assertTrue(MessageLifetime.ONE_DAY.isShorterThan(MessageLifetime.SEVEN_DAYS))
        assertTrue(MessageLifetime.NINETY_DAYS.isShorterThan(MessageLifetime.NEVER))
        assertFalse(MessageLifetime.SEVEN_DAYS.isShorterThan(MessageLifetime.ONE_DAY))
        assertFalse(MessageLifetime.SEVEN_DAYS.isShorterThan(MessageLifetime.SEVEN_DAYS))
        assertFalse(MessageLifetime.NEVER.isShorterThan(MessageLifetime.ONE_DAY))
    }

    // --- Turns and steps ---

    @Test
    fun `nothing is removed while no turn is older than the limit`() {
        // The first turn is a day short of the limit: inside the step, but nothing has passed the limit yet.
        val session = chat(user("u1", now - week + 1), reply("a1", now - week + 1), user("u2", now))
        assertSame(MessageExpiry.Outcome.Unchanged, prune(session))
    }

    @Test
    fun `a turn exactly at the limit stays, one millisecond past it goes`() {
        val atLimit = chat(user("u1", now - week), reply("a1", now - week), user("u2", now))
        assertSame(MessageExpiry.Outcome.Unchanged, prune(atLimit))
        val past = chat(user("u1", now - week - 1), reply("a1", now - week - 1), user("u2", now))
        val pruned = prune(past) as MessageExpiry.Outcome.Pruned
        assertEquals(listOf("u2"), pruned.session.messages.map { it.id })
    }

    @Test
    fun `once a turn has expired, every turn within one step of the limit goes with it`() {
        val session = chat(
            user("u1", now - week - 1), reply("a1", now - week - 1),
            // Older than limit - step: goes, although it has not reached the limit.
            user("u2", now - week + DAY_MILLIS - 1), reply("a2", now - week + DAY_MILLIS - 1),
            // Exactly limit - step old: stays.
            user("u3", now - week + DAY_MILLIS), reply("a3", now - week + DAY_MILLIS),
            user("u4", now),
        )
        val pruned = prune(session) as MessageExpiry.Outcome.Pruned
        assertEquals(listOf("u3", "a3", "u4"), pruned.session.messages.map { it.id })
    }

    @Test
    fun `the one-day limit steps by three hours`() {
        val session = chat(
            user("u1", now - DAY_MILLIS - 1),
            user("u2", now - 21 * HOUR_MILLIS - 1),
            user("u3", now - 21 * HOUR_MILLIS),
        )
        val pruned = prune(session, MessageLifetime.ONE_DAY) as MessageExpiry.Outcome.Pruned
        assertEquals(listOf("u3"), pruned.session.messages.map { it.id })
    }

    @Test
    fun `a turn is aged by its newest message, so a question stays with a recent answer`() {
        val session = chat(user("u1", now - 30 * DAY_MILLIS), reply("a1", now - DAY_MILLIS), user("u2", now - week - 1))
        val pruned = prune(session) as MessageExpiry.Outcome.Pruned
        assertEquals(listOf("u1", "a1"), pruned.session.messages.map { it.id })
    }

    @Test
    fun `replies before the first user message form a turn of their own`() {
        val session = chat(reply("a0", now - week - 1), user("u1", now))
        val pruned = prune(session) as MessageExpiry.Outcome.Pruned
        assertEquals(listOf("u1"), pruned.session.messages.map { it.id })
    }

    @Test
    fun `a chat whose every turn has expired is emptied`() {
        val session = chat(user("u1", now - week - 1), reply("a1", now - week + 1))
        // The reply is within the limit, so the turn is too.
        assertSame(MessageExpiry.Outcome.Unchanged, prune(session))
        assertSame(MessageExpiry.Outcome.Emptied, prune(chat(user("u1", now - week - 2), reply("a1", now - week - 1))))
    }

    @Test
    fun `Never removes nothing however old`() {
        val session = chat(user("u1", 1), reply("a1", 1))
        assertSame(MessageExpiry.Outcome.Unchanged, prune(session, MessageLifetime.NEVER))
        assertFalse(MessageExpiry.fileExpired(0, MessageLifetime.NEVER, now))
        assertFalse(MessageExpiry.mayHaveExpired(null, MessageLifetime.NEVER, now))
    }

    @Test
    fun `a chat whose reply is being generated is not pruned`() {
        val session = chat(user("u1", now - week - 1), reply("a1", now - week - 1).copy(status = MessageStatus.STREAMING))
        assertSame(MessageExpiry.Outcome.Unchanged, prune(session))
    }

    @Test
    fun `a chat without messages is not judged by message rules`() {
        assertSame(MessageExpiry.Outcome.Unchanged, prune(chat().copy(draft = "typed long ago")))
    }

    // --- What remains ---

    @Test
    fun `the title is derived again from what remains`() {
        val session = chat(user("u1", now - week - 1, "Secret first question"), user("u2", now, "Second question"))
        val pruned = (prune(session) as MessageExpiry.Outcome.Pruned).session
        assertEquals("Second question", pruned.title)
    }

    @Test
    fun `an error of a removed turn goes with it, one of a remaining turn stays`() {
        val base = chat(user("u1", now - week - 1), user("u2", now))
        val gone = (prune(base.copy(error = ChatError(ChatErrorKind.NATIVE, userMessageId = "u1"))) as MessageExpiry.Outcome.Pruned).session
        assertNull(gone.error)
        val stays = (prune(base.copy(error = ChatError(ChatErrorKind.NATIVE, userMessageId = "u2"))) as MessageExpiry.Outcome.Pruned).session
        assertEquals("u2", stays.error?.userMessageId)
    }

    @Test
    fun `the summary carries the oldest message date and the skip test follows it`() {
        val session = chat(user("u1", now - 3 * DAY_MILLIS), user("u2", now))
        assertEquals(now - 3 * DAY_MILLIS, session.summary().oldestMessageAtMillis)
        assertFalse(MessageExpiry.mayHaveExpired(now - week, MessageLifetime.SEVEN_DAYS, now))
        assertTrue(MessageExpiry.mayHaveExpired(now - week - 1, MessageLifetime.SEVEN_DAYS, now))
        assertTrue("unknown must be read", MessageExpiry.mayHaveExpired(null, MessageLifetime.SEVEN_DAYS, now))
        assertTrue("an undated message must be read", MessageExpiry.mayHaveExpired(0, MessageLifetime.SEVEN_DAYS, now))
    }

    // --- Dates that cannot be used as they are ---

    @Test
    fun `an undated message takes its file's date, so it expires only with the file`() {
        val session = chat(user("u1", 0), user("u2", now))
        val recentFile = MessageExpiry.normalised(session, now, fileMillis = now - DAY_MILLIS)
        assertEquals(now - DAY_MILLIS, recentFile.messages[0].createdAtMillis)
        assertSame(MessageExpiry.Outcome.Unchanged, prune(recentFile))
        val oldFile = MessageExpiry.normalised(session, now, fileMillis = now - week - 1)
        assertEquals(listOf("u2"), (prune(oldFile) as MessageExpiry.Outcome.Pruned).session.messages.map { it.id })
    }

    @Test
    fun `a message dated in the future counts as written now`() {
        val session = chat(user("u1", now + 365 * DAY_MILLIS))
        val normalised = MessageExpiry.normalised(session, now, fileMillis = now)
        assertEquals(now, normalised.messages[0].createdAtMillis)
        assertSame("nothing to change is the same object", normalised, MessageExpiry.normalised(normalised, now, now))
    }

    @Test
    fun `a file date in the future is no better than now`() {
        val normalised = MessageExpiry.normalised(chat(user("u1", 0)), now, fileMillis = now + DAY_MILLIS)
        assertEquals(now, normalised.messages[0].createdAtMillis)
    }

    @Test
    fun `the file rule is strict at the limit, and the newest date dates the file`() {
        assertFalse(MessageExpiry.fileExpired(now - week, MessageLifetime.SEVEN_DAYS, now))
        assertTrue(MessageExpiry.fileExpired(now - week - 1, MessageLifetime.SEVEN_DAYS, now))
        assertEquals(now, MessageExpiry.newestMessageMillis(chat(user("u1", 5), reply("a1", now))))
        assertNull(MessageExpiry.newestMessageMillis(chat(user("u1", 0))))
    }
}
