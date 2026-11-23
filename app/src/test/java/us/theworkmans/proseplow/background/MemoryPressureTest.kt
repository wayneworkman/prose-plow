package us.theworkmans.proseplow.background

import android.content.ComponentCallbacks2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What Android says about memory being short, remembered for a minute. */
class MemoryPressureTest {

    private var now = 5_000_000L
    private val pressure = MemoryPressure { now }

    @Test
    fun nothingIsSetUntilTheSystemSaysSomething() {
        assertFalse(pressure.isSet)
        now += 10 * MemoryPressure.HOLD_MILLIS
        assertFalse(pressure.isSet)
    }

    @Test
    fun theLevelsThatSpeakOfMemoryBeingShortCountAndTheOthersDoNot() {
        for (level in listOf(MemoryPressure.RUNNING_LOW, MemoryPressure.RUNNING_CRITICAL, MemoryPressure.MODERATE, MemoryPressure.COMPLETE)) {
            val fresh = MemoryPressure { now }
            fresh.onTrimLevel(level)
            assertTrue("level $level", fresh.isSet)
        }
        // The interface is out of sight, the process is on the list of those in the background, or memory is only beginning to get short.
        for (level in listOf(RUNNING_MODERATE, MemoryPressure.UI_HIDDEN, MemoryPressure.BACKGROUND, 0, -1)) {
            val fresh = MemoryPressure { now }
            fresh.onTrimLevel(level)
            assertFalse("level $level", fresh.isSet)
        }
    }

    @Test
    fun aSignalHoldsForAMinuteAndAnotherBeginsTheMinuteAgain() {
        assertEquals(60_000L, MemoryPressure.HOLD_MILLIS)
        pressure.onTrimLevel(MemoryPressure.RUNNING_LOW)
        assertTrue(pressure.isSet)
        now += 59_999
        assertTrue(pressure.isSet)
        now += 1
        assertFalse("a minute later it is forgotten", pressure.isSet)

        pressure.onLowMemory()
        assertTrue(pressure.isSet)
        now += 40_000
        pressure.onTrimLevel(MemoryPressure.RUNNING_CRITICAL)
        now += 40_000
        assertTrue("counted from the last signal", pressure.isSet)
        now += 20_000
        assertFalse(pressure.isSet)

        // A level that does not count does not begin it again, and does not end it.
        pressure.onLowMemory()
        now += 30_000
        pressure.onTrimLevel(MemoryPressure.UI_HIDDEN)
        assertTrue(pressure.isSet)
        now += 30_000
        assertFalse(pressure.isSet)
    }

    @Test
    fun aClockThatWentBackwardsDoesNotHoldGrowthBackForEver() {
        pressure.onLowMemory()
        now -= 1_000
        assertFalse("a signal from the future is no signal", pressure.isSet)
    }

    /** The levels are written out so that the class needs no Android; they must be Android's. */
    @Test
    @Suppress("DEPRECATION") // The levels the system no longer sends to a running app are still the levels it can be sent.
    fun theLevelsAreAndroidsOwn() {
        assertEquals(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, MemoryPressure.RUNNING_LOW)
        assertEquals(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, MemoryPressure.RUNNING_CRITICAL)
        assertEquals(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, MemoryPressure.UI_HIDDEN)
        assertEquals(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND, MemoryPressure.BACKGROUND)
        assertEquals(ComponentCallbacks2.TRIM_MEMORY_MODERATE, MemoryPressure.MODERATE)
        assertEquals(ComponentCallbacks2.TRIM_MEMORY_COMPLETE, MemoryPressure.COMPLETE)
        assertEquals(RUNNING_MODERATE, ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
    }

    private companion object {
        const val RUNNING_MODERATE = 5
    }
}
