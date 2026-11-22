package us.theworkmans.proseplow.llama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The thread plan for processors as the kernel describes them, from sysfs content written by the test. */
class ThreadPlannerTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** A directory like /sys/devices/system/cpu with one `cpuN/cpu_capacity` per entry; null leaves the file out. */
    private fun sysfs(vararg capacities: String?): File {
        val root = folder.newFolder()
        capacities.forEachIndexed { cpu, capacity ->
            val directory = File(root, "cpu$cpu").also { it.mkdir() }
            if (capacity != null) File(directory, "cpu_capacity").writeText(capacity)
        }
        // What else lives in that directory on a real system and must be ignored.
        File(root, "cpufreq").mkdir()
        File(root, "cpuidle").mkdir()
        File(root, "possible").writeText("0-${capacities.size - 1}\n")
        return root
    }

    private fun plan(root: File, processors: Int = 8): ThreadPlan = ThreadPlanner.plan(CpuSysfs(root).capacities(), processors)

    @Test
    fun `four little and four big cores`() {
        // The reference phone: cpu0-3 at 453, cpu4-6 at 984, cpu7 at 1024, with the newline the kernel writes.
        val root = sysfs("453\n", "453\n", "453\n", "453\n", "984\n", "984\n", "984\n", "1024\n")
        assertEquals(mapOf(0 to 453, 1 to 453, 2 to 453, 3 to 453, 4 to 984, 5 to 984, 6 to 984, 7 to 1024), CpuSysfs(root).capacities())
        assertEquals(ThreadPlan(pinnedCpus = listOf(4, 5, 6, 7), unpinnedThreads = 3), plan(root))
    }

    @Test
    fun `one prime three performance and four efficiency cores`() {
        // A common flagship layout: the mid cores count as big (over 60 % of the prime core).
        val root = sysfs("1024", "780", "780", "780", "290", "290", "290", "290")
        assertEquals(ThreadPlan(pinnedCpus = listOf(0, 1, 2, 3), unpinnedThreads = 3), plan(root))
    }

    @Test
    fun `two big and six little cores`() {
        val root = sysfs("400", "400", "400", "400", "400", "400", "1024", "1024")
        assertEquals(ThreadPlan(pinnedCpus = listOf(6, 7), unpinnedThreads = 2), plan(root))
    }

    @Test
    fun `eight big cores are capped at six pinned threads, the fastest first`() {
        val root = sysfs("900", "900", "900", "900", "900", "900", "1024", "1024")
        val plan = plan(root)
        assertEquals(listOf(0, 1, 2, 3, 6, 7), plan.pinnedCpus)
        assertEquals(5, plan.unpinnedThreads)
    }

    @Test
    fun `a single big core is not worth pinning to`() {
        val root = sysfs("300", "300", "300", "300", "300", "300", "300", "1024")
        assertEquals(ThreadPlan(pinnedCpus = emptyList(), unpinnedThreads = 2), plan(root))
    }

    @Test
    fun `all cores alike`() {
        val root = sysfs("1024", "1024", "1024", "1024")
        assertEquals(ThreadPlan(pinnedCpus = listOf(0, 1, 2, 3), unpinnedThreads = 3), plan(root, processors = 4))
    }

    @Test
    fun `capacities that cannot be read fall back to the processor count`() {
        val absent = sysfs(null, null, null, null, null, null, null, null)
        assertTrue(CpuSysfs(absent).capacities().isEmpty())
        // Two fewer than the processors, between 2 and 4, and nothing pinned.
        assertEquals(ThreadPlan(emptyList(), 4), plan(absent, processors = 8))
        assertEquals(ThreadPlan(emptyList(), 4), plan(absent, processors = 6))
        assertEquals(ThreadPlan(emptyList(), 2), plan(absent, processors = 4))
        assertEquals(ThreadPlan(emptyList(), 2), plan(absent, processors = 2))
        assertEquals(ThreadPlan(emptyList(), 1), plan(absent, processors = 1))
    }

    @Test
    fun `a directory that does not exist reads as nothing`() {
        val missing = File(folder.root, "no-such-directory")
        assertTrue(CpuSysfs(missing).capacities().isEmpty())
        assertEquals(ThreadPlan(emptyList(), 4), ThreadPlanner.plan(CpuSysfs(missing).capacities(), 8))
    }

    @Test
    fun `garbage and partly readable values`() {
        // cpu1 has no file, cpu2 has text, cpu3 is offline and reports nothing useful.
        val root = sysfs("1024", null, "fast", "0", "1024", "500")
        assertEquals(mapOf(0 to 1024, 3 to 0, 4 to 1024, 5 to 500), CpuSysfs(root).capacities())
        assertEquals(ThreadPlan(pinnedCpus = listOf(0, 4), unpinnedThreads = 2), plan(root, processors = 6))
    }
}
