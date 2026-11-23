package us.theworkmans.proseplow

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app ends its own process when it is locked and out of sight. The instrumented tests run
 * inside the app's process, so that must never happen to them: the ender recognises a test run
 * and does nothing. This test asks it to end the process, and is still there afterwards.
 */
@RunWith(AndroidJUnit4::class)
class ProcessEndOnDeviceTest {

    @Test
    fun aTestRunIsRecognisedAndItsProcessIsNeverEnded() {
        DeviceTests.requireDevelopmentPackage()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("the app's class loader sees the test libraries in a test run", InstrumentedRun.detected(context.classLoader))
        assertTrue(InstrumentedRun.detected(context.applicationContext.classLoader))

        val pid = Process.myPid()
        AndroidProcessEnder(context).end()
        AndroidProcessEnder(context.applicationContext).end()
        assertEquals("still the same process", pid, Process.myPid())
    }
}
