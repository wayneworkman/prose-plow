package us.theworkmans.proseplow.core.chat

/** A clock that only moves when a test moves it. */
class FakeClock(var now: Long = 1_000_000L) : Clock {

    override fun nowMillis(): Long = now

    fun advance(millis: Long) {
        now += millis
    }
}

/** Ids that count up, so a test can name the message it means: `m1`, `m2`, ... */
class SequentialIds(private val prefix: String = "m") : IdSource {

    private var next = 1

    override fun newId(): String = prefix + next++
}
