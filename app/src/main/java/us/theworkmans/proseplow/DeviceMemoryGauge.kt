package us.theworkmans.proseplow

import android.app.ActivityManager
import android.content.Context
import us.theworkmans.proseplow.background.MemoryPressure
import us.theworkmans.proseplow.core.engine.DeviceMemory
import us.theworkmans.proseplow.core.engine.MemoryGauge

/**
 * The device's memory as Android reports it (`ActivityManager.MemoryInfo`), read afresh every
 * time it is asked for: the engine asks before every decision about the size of a model's
 * context. Memory counts as low when the system says so at that moment, or said so in a
 * callback shortly before ([MemoryPressure]).
 */
class DeviceMemoryGauge(context: Context, private val pressure: MemoryPressure) : MemoryGauge {
    private val activityManager = context.applicationContext.getSystemService(ActivityManager::class.java)

    override fun read(): DeviceMemory {
        val info = ActivityManager.MemoryInfo()
        // Without the service nothing is known, and nothing is added to a context on a guess.
        val manager = activityManager ?: return DeviceMemory(totalBytes = 0, availableBytes = 0, thresholdBytes = 0, low = true)
        manager.getMemoryInfo(info)
        return DeviceMemory(totalBytes = info.totalMem, availableBytes = info.availMem, thresholdBytes = info.threshold, low = info.lowMemory || pressure.isSet)
    }

    /** The memory the device has, which does not change: for showing what a context size costs against it. */
    fun totalBytes(): Long = read().totalBytes
}
