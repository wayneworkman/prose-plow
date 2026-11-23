package us.theworkmans.proseplow.security

import kotlinx.serialization.Serializable

/**
 * A moment of trusted time ([TrustedTime]) together with where the device clocks stood when it
 * was recorded: the clock that counts since boot, and which boot that was. The time since boot
 * starts again at every restart of the device, so it means nothing without the boot count.
 *
 * @property wallMillis the trusted time, in milliseconds since the Unix epoch.
 * @property elapsedMillis the time since boot at that moment.
 * @property bootCount the device's count of boots at that moment, or null when it could not be
 *   read; a mark without one gives no credit for time that passed after it.
 */
@Serializable
data class TimeMark(val wallMillis: Long, val elapsedMillis: Long, val bootCount: Int? = null) {
    /**
     * No value is negative, and the trusted time is not beyond [TrustedTime.MAX_WALL_MILLIS]: a
     * mark that says otherwise did not come from a device clock and is a damaged record.
     */
    fun isValid(): Boolean =
        wallMillis in 0..TrustedTime.MAX_WALL_MILLIS && elapsedMillis >= 0 && (bootCount == null || bootCount >= 0)
}

/**
 * The time by which messages are dated and by which they expire. It never runs backwards:
 * anyone holding the device can set its date and time, and setting it back must not keep
 * messages alive.
 *
 * Trusted time is the latest time seen: the wall clock now, or the last trusted time plus the
 * time that has passed since it was recorded on the clock that counts since boot, whichever is
 * later. That credit for passing time is only given within the same boot, known by the boot
 * count; after a restart, or when a boot count cannot be read, the last trusted time is carried
 * over as it was, and the wall clock takes over again once it has caught up with it.
 *
 * A wall clock that is set forward is believed, and messages then expire early: that is the
 * accepted direction. Pure; the caller reads the clocks.
 */
object TrustedTime {

    /** The end of the year 9999: no device clock is beyond it, so a mark that is cannot be real. */
    const val MAX_WALL_MILLIS: Long = 253_402_300_799_999L

    /**
     * The trusted time now, given the last mark ([last], null when there is none) and the
     * device's clocks as they read now.
     */
    fun now(last: TimeMark?, wallNow: Long, elapsedNow: Long, bootNow: Int?): Long {
        val wall = wallNow.coerceIn(0, MAX_WALL_MILLIS)
        if (last == null || !last.isValid()) return wall
        val sameBoot = bootNow != null && last.bootCount != null && bootNow == last.bootCount && elapsedNow >= last.elapsedMillis
        val carried = if (sameBoot) (last.wallMillis + (elapsedNow - last.elapsedMillis)).coerceAtMost(MAX_WALL_MILLIS) else last.wallMillis
        return maxOf(wall, carried)
    }

    /** The mark to record now: [now], with the clocks it was read at. */
    fun mark(last: TimeMark?, wallNow: Long, elapsedNow: Long, bootNow: Int?): TimeMark =
        TimeMark(now(last, wallNow, elapsedNow, bootNow), elapsedNow.coerceAtLeast(0), bootNow?.takeIf { it >= 0 })
}
