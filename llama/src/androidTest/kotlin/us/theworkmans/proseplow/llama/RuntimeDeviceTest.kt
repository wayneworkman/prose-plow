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
        // Whatever the answer, it must be consistent with what can be located: a bundled model
        // whose file is there, and never a model that is not bundled, whatever file is there.
        for (model in ModelCatalog.all) {
            assertEquals(model.id, model.bundled && ModelFiles.locate(appContext, model) != null, model in present)
        }
    }

    /**
     * The library finds the assets of its APK and nothing else, in every build type: a file
     * placed for the tests is found by the tests' own locator only. Meaningful on a phone where
     * such a file was placed (Qwen3.5, say; see native/README.md, "On the phone"); it
     * reports which models it could check.
     */
    @Test
    fun theLibraryNeverFindsAFilePlacedInPrivateStorage() {
        val placed = ModelCatalog.all.filter { SideloadedModels.find(appContext, it) != null }
        report("files placed for the tests: ${placed.joinToString { it.id }.ifEmpty { "none" }}")
        for (model in placed) {
            assertEquals(model.id, ModelLocation.PrivateFile(checkNotNull(SideloadedModels.find(appContext, model))), locateForTests(model))
            val found = ModelFiles.locate(appContext, model)
            assertTrue("${model.id}: the library found $found", found == null || found is ModelLocation.Asset)
        }
    }
}
