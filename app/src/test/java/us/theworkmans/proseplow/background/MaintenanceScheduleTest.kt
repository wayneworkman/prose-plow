package us.theworkmans.proseplow.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rules of the background maintenance job without Android: scheduled while the setting is
 * on, cancelled when it is off, and a job that is scheduled as it should be is left alone, so
 * that applying the setting at every start does not put its next run off. Catches a schedule
 * that is renewed at every start, one that survives the switch being turned off, and a failure
 * of the platform that escapes into the lock.
 */
class MaintenanceScheduleTest {

    private class FakeSlot : MaintenanceSlot {
        var job: ScheduledMaintenance? = null
        val calls = mutableListOf<String>()
        var failure: RuntimeException? = null

        override fun pending(): ScheduledMaintenance? = job

        override fun schedule(intervalMillis: Long) {
            calls += "schedule"
            failure?.let { throw it }
            job = ScheduledMaintenance(intervalMillis, persisted = true)
        }

        override fun cancel() {
            calls += "cancel"
            job = null
        }
    }

    private val slot = FakeSlot()
    private val schedule = MaintenanceSchedule(slot)

    @Test
    fun onSchedulesEverySixHoursAcrossRestartsAndKeepsWhatIsScheduledAlready() {
        schedule.apply(true)
        assertEquals(ScheduledMaintenance(6 * 60 * 60 * 1000L, persisted = true), slot.job)
        schedule.apply(true)
        schedule.apply(true)
        assertEquals("scheduled once, not again at every start", listOf("schedule"), slot.calls)
    }

    @Test
    fun aJobScheduledDifferentlyIsReplaced() {
        slot.job = ScheduledMaintenance(15 * 60 * 1000L, persisted = false)
        schedule.apply(true)
        assertEquals(listOf("schedule"), slot.calls)
        assertEquals(ScheduledMaintenance(MaintenanceSchedule.INTERVAL_MILLIS, persisted = true), slot.job)
    }

    @Test
    fun offCancels() {
        schedule.apply(true)
        schedule.apply(false)
        assertNull(slot.job)
        assertEquals(listOf("schedule", "cancel"), slot.calls)
    }

    @Test
    fun aPlatformThatRefusesStopsNothing() {
        slot.failure = IllegalStateException("too many jobs")
        schedule.apply(true)
        assertNull(slot.job)
        schedule.apply(false)
        assertEquals(listOf("schedule", "cancel"), slot.calls)
    }
}
