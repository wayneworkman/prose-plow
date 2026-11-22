package us.theworkmans.proseplow.llama

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import us.theworkmans.proseplow.core.model.ModelCatalog
import java.io.File

/**
 * On the phone: the native libraries load, a CPU variant of the backend is chosen, and the
 * thread plan fits the processor. Needs no model.
 */
@RunWith(AndroidJUnit4::class)
class RuntimeDeviceTest {

    @Test
    fun theBackendVariantForThisProcessorIsLoaded() {
        val extracted = File(appContext.applicationInfo.nativeLibraryDir).list().orEmpty().sorted()
        val candidates = NativeRuntime.backendLibraries(appContext.applicationInfo)
        report("native library directory: ${extracted.size} extracted file(s); CPU backend candidates: ${candidates.joinToString()}")
        assertTrue("the build carries several CPU variants: $candidates", candidates.size >= 2)

        val description = NativeEngine.runtimeDescription(appContext)
        report("runtime: $description")
        assertTrue("a CPU variant library was selected: $description", description.contains("libggml-cpu-android_"))
        assertEquals("starting twice changes nothing", description, NativeEngine.runtimeDescription(appContext))
    }

    @Test
    fun theThreadPlanFitsThisProcessor() {
        val capacities = CpuSysfs().capacities()
        val plan = ThreadPlanner.plan(capacities, Runtime.getRuntime().availableProcessors())
        report("cpu_capacity: ${capacities.toSortedMap()}; ${Runtime.getRuntime().availableProcessors()} processors")
        report("thread plan: pinned to ${plan.pinnedCpus}, otherwise ${plan.unpinnedThreads} unpinned thread(s)")
        assertTrue("cpu_capacity is readable from an app on this phone", capacities.isNotEmpty())
        assertTrue(plan.unpinnedThreads >= 1)
        if (plan.pinnedCpus.isNotEmpty()) {
            assertEquals("one free-running thread fewer than big cores, and at least two", maxOf(2, plan.pinnedCpus.size - 1), plan.unpinnedThreads)
        }
    }

    @Test
    fun theBuildSaysWhichModelsItCarries() {
        val present = NativeEngine.availableModels(appContext)
        report("models present: ${present.joinToString { it.id }.ifEmpty { "none" }} (the catalogue knows ${ModelCatalog.all.joinToString { it.id }})")
        // Whatever the answer, it must be consistent with what can be located.
        for (model in ModelCatalog.all) {
            assertEquals(model in present, ModelFiles.locate(appContext, model) != null)
        }
    }
}
