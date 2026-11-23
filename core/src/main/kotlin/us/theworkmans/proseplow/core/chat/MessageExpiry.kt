package us.theworkmans.proseplow.core.chat

/**
 * How long a message is kept: one age limit for every chat, chosen in the security settings.
 *
 * @property days the limit in days, or null for [NEVER].
 */
enum class MessageLifetime(val days: Int?) {
    ONE_DAY(1),
    SEVEN_DAYS(7),
    THIRTY_DAYS(30),
    NINETY_DAYS(90),
    NEVER(null),
    ;

    /** The limit in milliseconds, or null for [NEVER]. */
    val limitMillis: Long? get() = days?.let { it * DAY_MILLIS }

    /**
     * How far ahead of the limit a pruning reaches once it has to remove something (see
     * [MessageExpiry.prune]): a day for limits of a week and more, three hours for one day.
     * Null for [NEVER].
     */
    val stepMillis: Long? get() = when (this) {
        NEVER -> null
        ONE_DAY -> 3 * HOUR_MILLIS
        SEVEN_DAYS, THIRTY_DAYS, NINETY_DAYS -> DAY_MILLIS
    }

    /** The number stored for this choice: its days, and [NEVER_STORED] for [NEVER]. */
    val storedDays: Int get() = days ?: NEVER_STORED

    /**
     * True when changing from [other] to this deletes messages that [other] would have kept: this
     * has a limit, and [other] has none or a longer one. Such a change asks for confirmation.
     */
    fun isShorterThan(other: MessageLifetime): Boolean {
        val mine = limitMillis ?: return false
        val theirs = other.limitMillis ?: return true
        return mine < theirs
    }

    companion object {
        /** The choice of a new installation, and of a file that does not name one. */
        val DEFAULT: MessageLifetime = SEVEN_DAYS

        /** What [NEVER] is stored as. */
        const val NEVER_STORED: Int = 0

        const val HOUR_MILLIS: Long = 60 * 60 * 1000L
        const val DAY_MILLIS: Long = 24 * HOUR_MILLIS

        /** The choice stored as [days], or null when no choice is stored as that number. */
        fun fromStoredDays(days: Int): MessageLifetime? = entries.firstOrNull { it.storedDays == days }
    }
}

/**
 * The rules by which messages expire, on a chat that has been decrypted. Pure: the caller
 * supplies the trusted time ([TrustedTime]) and whatever it knows of the chat's file.
 *
 * The unit of expiry is the turn: a user message and the replies that follow it up to the next
 * user message (messages before the first user message form a turn of their own). A turn's age
 * is the age of its newest message, so a question never outlives its answer or the other way
 * round, and a turn is removed whole.
 */
object MessageExpiry {

    /** What [prune] made of a chat. */
    sealed interface Outcome {
        /** Nothing has expired. */
        data object Unchanged : Outcome

        /** Turns were removed; [session] is what remains, with its title derived again. */
        data class Pruned(val session: ChatSession) : Outcome

        /** Every message has expired: the chat is to be deleted whole. */
        data object Emptied : Outcome
    }

    /**
     * [session] with every message date made usable for [prune], or the very same object when
     * nothing needed it:
     * - a date of 0 (a message stored without one) becomes [fileMillis], the date of the chat's
     *   file, so that such a message expires once its file is past the limit and not before;
     * - a date after [nowMillis] (stamped by a clock that was ahead, or a file date that is)
     *   becomes [nowMillis], so that it starts to age now instead of living until that date.
     *
     * A caller that stores the result keeps these dates from then on.
     */
    fun normalised(session: ChatSession, nowMillis: Long, fileMillis: Long): ChatSession {
        if (session.messages.none { it.createdAtMillis <= 0 || it.createdAtMillis > nowMillis }) return session
        val fileDate = fileMillis.coerceIn(0, nowMillis.coerceAtLeast(0))
        return session.copy(
            messages = session.messages.map { message ->
                when {
                    message.createdAtMillis <= 0 -> message.copy(createdAtMillis = fileDate)
                    message.createdAtMillis > nowMillis -> message.copy(createdAtMillis = nowMillis)
                    else -> message
                }
            },
        )
    }

    /**
     * Removes the turns of [session] that have expired under [lifetime] at [nowMillis].
     *
     * Pruning goes in steps, so that a long chat does not lose a turn every few minutes (each
     * removal changes the start of the conversation, and the model must then read the rest
     * again): nothing is removed until some turn is older than the limit; then every turn older
     * than the limit less [MessageLifetime.stepMillis] goes. Messages therefore go up to one step
     * early, never late. "Older than" is strict: a turn exactly at the limit stays.
     *
     * A chat whose reply is being generated is never pruned (it is pruned when the reply has
     * ended), and a chat without messages is not judged here: it has no message to date it by.
     * Dates are taken as they are; see [normalised].
     */
    fun prune(session: ChatSession, lifetime: MessageLifetime, nowMillis: Long): Outcome {
        val limit = lifetime.limitMillis ?: return Outcome.Unchanged
        val step = lifetime.stepMillis ?: return Outcome.Unchanged
        if (session.isGenerating || session.messages.isEmpty()) return Outcome.Unchanged
        val turns = turns(session.messages)
        if (turns.none { nowMillis - newest(it) > limit }) return Outcome.Unchanged
        val kept = turns.filter { nowMillis - newest(it) <= limit - step }
        if (kept.isEmpty()) return Outcome.Emptied
        return Outcome.Pruned(session.keepingOnly(kept.flatten()))
    }

    /**
     * True when a chat file dated [fileMillis] is past [lifetime] at [nowMillis]. A chat's file
     * is dated by its newest message ([newestMessageMillis]), so this is the rule by which a
     * whole chat can expire without being decrypted. Never true for [MessageLifetime.NEVER].
     */
    fun fileExpired(fileMillis: Long, lifetime: MessageLifetime, nowMillis: Long): Boolean {
        val limit = lifetime.limitMillis ?: return false
        return nowMillis - fileMillis > limit
    }

    /**
     * True when a chat whose oldest message is dated [oldestMessageMillis] (null: not known) may
     * hold a turn that has expired, so that it has to be read to find out. False only when every
     * message is known to be within the limit, which a turn's age cannot then exceed.
     */
    fun mayHaveExpired(oldestMessageMillis: Long?, lifetime: MessageLifetime, nowMillis: Long): Boolean {
        val limit = lifetime.limitMillis ?: return false
        if (oldestMessageMillis == null || oldestMessageMillis <= 0) return true
        return nowMillis - oldestMessageMillis > limit
    }

    /** The date of the newest message of [session] that has a date, or null when none has. */
    fun newestMessageMillis(session: ChatSession): Long? =
        session.messages.maxOfOrNull { it.createdAtMillis }?.takeIf { it > 0 }

    private fun newest(turn: List<ChatMessage>): Long = turn.maxOf { it.createdAtMillis }

    /** [messages] cut before every user message: the turns, in order. */
    private fun turns(messages: List<ChatMessage>): List<List<ChatMessage>> {
        val turns = ArrayList<MutableList<ChatMessage>>()
        for (message in messages) {
            if (message.role == Role.USER || turns.isEmpty()) turns += ArrayList<ChatMessage>()
            turns.last() += message
        }
        return turns
    }
}
