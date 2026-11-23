package us.theworkmans.proseplow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a process that runs instrumented tests is told from one a user started: by a class of the
 * test libraries, which only such a process can load. The process ender asks this before it
 * does anything, so a test run can never be the process that ends.
 */
class ProcessEndTest {

    private val loader = checkNotNull(javaClass.classLoader)

    @Test
    fun aProcessThatCanLoadTheTestLibrariesIsATestRun() {
        // Any class that is certainly there stands for the marker here.
        assertTrue(InstrumentedRun.detected(loader, marker = "org.junit.Test"))
        assertTrue(InstrumentedRun.detected(loader, marker = InstrumentedRun::class.java.name))
    }

    @Test
    fun aProcessThatCannotIsNot() {
        assertFalse(InstrumentedRun.detected(loader, marker = "us.theworkmans.proseplow.NoSuchClass"))
        // The real marker belongs to the instrumented tests' libraries. They are not part of the
        // app, and not of these unit tests either: were this ever true here, they would have
        // found their way into a place from which a build of the app could carry them.
        assertFalse(InstrumentedRun.detected(loader))
    }

    @Test
    fun theMarkerIsTheClassEveryInstrumentedRunLoads() {
        assertEquals("androidx.test.platform.app.InstrumentationRegistry", InstrumentedRun.MARKER)
    }
}
