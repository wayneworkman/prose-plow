package us.theworkmans.proseplow.llama

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import java.io.File

/**
 * An empty activity that puts the test process in front for the duration of a test.
 *
 * It matters for every measurement: Android moves a process between sets of cores (cpusets)
 * according to what its app is doing. Only the app in front may use every core; on the reference
 * phone a process without a visible activity loses the fastest core and one in the background
 * all four big ones. The app runs its models while it is in front, so that is the condition the
 * model tests create, and [schedulingGroup] is reported with what they measure.
 */
class ForegroundActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A measurement must not be cut short by the screen timing out.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

/** The cpuset this process is in ("/top-app" for the app in front) and the CPUs it may use. */
internal fun schedulingGroup(): String {
    val group = try {
        File("/proc/self/cpuset").readText().trim()
    } catch (_: java.io.IOException) {
        "unknown"
    }
    val allowed = try {
        File("/proc/self/status").readLines().firstOrNull { it.startsWith("Cpus_allowed_list:") }?.substringAfter(':')?.trim()
    } catch (_: java.io.IOException) {
        null
    }
    return "cpuset $group, this thread may use CPUs ${allowed ?: "unknown"}"
}
