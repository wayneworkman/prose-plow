package us.theworkmans.proseplow.background

/**
 * The one periodic job of the app as the platform holds it: whether it is scheduled, and with
 * what. Implemented over Android's job scheduler ([AndroidMaintenanceSlot]); the unit tests use
 * a fake.
 */
interface MaintenanceSlot {
    /** The job as it is scheduled now, or null when it is not. */
    fun pending(): ScheduledMaintenance?

    /** Schedules the job, every [intervalMillis] and kept across restarts of the phone, in place of any before it. */
    fun schedule(intervalMillis: Long)

    /** Cancels the job; nothing when it is not scheduled. */
    fun cancel()
}

/**
 * What a scheduled job looks like to [MaintenanceSchedule].
 *
 * @property intervalMillis how often it runs.
 * @property persisted it is kept across restarts of the phone.
 */
data class ScheduledMaintenance(val intervalMillis: Long, val persisted: Boolean)

/**
 * The rules of the background maintenance job, in plain Kotlin: with the setting on it is
 * scheduled, every [INTERVAL_MILLIS] and across restarts; with it off it is cancelled, so that
 * nothing of the app runs in the background. A job that is scheduled already, as it should be,
 * is left alone: scheduling it again would put its next run off by a whole period, and this is
 * applied at every start of the app. One scheduled differently (by an earlier version) is
 * replaced.
 *
 * A failure of the platform's scheduler (it refuses, or throws) is swallowed: the setting stays as
 * it is stored, and the next start tries again. Nothing here may stop the lock.
 */
class MaintenanceSchedule(private val slot: MaintenanceSlot) {

    /** Makes the schedule follow the setting [enabled]. Blocking (a call into the system); never throws. */
    fun apply(enabled: Boolean) {
        try {
            if (!enabled) {
                slot.cancel()
                return
            }
            if (slot.pending() == ScheduledMaintenance(INTERVAL_MILLIS, persisted = true)) return
            slot.schedule(INTERVAL_MILLIS)
        } catch (_: RuntimeException) {
            // See the class comment.
        }
    }

    companion object {
        /** Every six hours. */
        const val INTERVAL_MILLIS: Long = 6 * 60 * 60 * 1000L
    }
}
