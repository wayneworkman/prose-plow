package us.theworkmans.proseplow.core.chat

import java.util.UUID

/**
 * Where the chat model gets the time from. Injected so that every timestamp in a test is one the
 * test chose.
 */
fun interface Clock {

    /** The current time in milliseconds since the Unix epoch. */
    fun nowMillis(): Long
}

/** The device's wall clock. */
object SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/** Where new sessions and messages get their ids from. Injected for the same reason as [Clock]. */
fun interface IdSource {

    /** An id that no other session or message has. */
    fun newId(): String
}

/** Random UUIDs: unique without any bookkeeping, and they say nothing about order or count. */
object RandomIds : IdSource {
    override fun newId(): String = UUID.randomUUID().toString()
}
