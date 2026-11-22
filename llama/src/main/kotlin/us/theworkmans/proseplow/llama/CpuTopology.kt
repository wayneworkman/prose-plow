package us.theworkmans.proseplow.llama

import java.io.File
import java.io.IOException

/**
 * Where the evaluation threads of a model go.
 *
 * @property pinnedCpus the CPUs to run one thread each, pinned, when the system allows all of
 *   them: the big cores. Empty when the processor could not be read or pinning makes no sense
 *   on it.
 * @property unpinnedThreads how many threads to run without any affinity when pinning is not
 *   possible at the moment (an app that is not in front is confined to fewer cores) or not
 *   wanted. Always at least 1 and, when the big cores are known, fewer than there are of them.
 */
data class ThreadPlan(val pinnedCpus: List<Int>, val unpinnedThreads: Int)

/**
 * Decides the threads from what the kernel says about the processor.
 *
 * A phone's processor has fast and slow cores, and llama.cpp neither tells them apart nor
 * copes with the consequences on its own: its thread pool waits by spinning, so as soon as two
 * of its threads have to share a core, every operation stalls (measured on the reference phone,
 * which has four fast cores: a prompt at 7 tokens per second instead of 90). One thread pinned
 * to each fast core cannot end up that way and is also the fastest arrangement measured there:
 * 90 tokens per second for a prompt against 69 with three free threads. The rule that follows:
 *
 * - The **big cores** are those whose `cpu_capacity` (the scheduler's own ranking, 0 to 1024) is
 *   at least [BIG_CORE_SHARE] of the largest. One pinned thread goes on each, up to
 *   [MAX_PINNED]; more threads only fight over the memory bus.
 * - **Without pinning**, one thread fewer than there are big cores, and at least [MIN_THREADS]:
 *   the scheduler may park a big core at any time, and free threads must not then outnumber
 *   the cores they are left with.
 * - When the capacities **cannot be read**, nothing is pinned and the thread count is upstream
 *   llama.cpp's rule for Android: two fewer than the processors, kept between 2 and 4.
 */
internal object ThreadPlanner {

    /** A core counts as big from this share of the largest capacity. */
    const val BIG_CORE_SHARE: Double = 0.6

    /** The most pinned threads; beyond this evaluation gets no faster on any phone measured. */
    const val MAX_PINNED: Int = 6

    /** The fewest threads ever used, except on a processor with a single core. */
    const val MIN_THREADS: Int = 2

    /** The most threads when the processor could not be read. */
    const val MAX_BLIND_THREADS: Int = 4

    /**
     * @param capacities CPU number to `cpu_capacity`, for every CPU whose value could be read.
     * @param processors how many processors the system reports; only used when [capacities] is
     *   empty.
     */
    fun plan(capacities: Map<Int, Int>, processors: Int): ThreadPlan {
        val usable = capacities.filterValues { it > 0 }
        if (usable.isEmpty()) {
            val threads = (processors - 2).coerceIn(MIN_THREADS, MAX_BLIND_THREADS).coerceAtMost(processors.coerceAtLeast(1))
            return ThreadPlan(pinnedCpus = emptyList(), unpinnedThreads = threads)
        }
        val threshold = usable.values.max() * BIG_CORE_SHARE
        // The fastest first when there are more big cores than threads worth running.
        val big = usable.filterValues { it >= threshold }.entries
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            .take(MAX_PINNED)
            .map { it.key }
            .sorted()
        if (big.size < MIN_THREADS) {
            // A single big core: one pinned thread would be slower than two free ones.
            return ThreadPlan(pinnedCpus = emptyList(), unpinnedThreads = MIN_THREADS.coerceAtMost(usable.size))
        }
        return ThreadPlan(pinnedCpus = big, unpinnedThreads = (big.size - 1).coerceAtLeast(MIN_THREADS))
    }
}

/**
 * Reads `cpu_capacity` for every CPU from sysfs.
 *
 * @param root the directory that holds `cpu0`, `cpu1` and so on; replaceable in tests.
 */
internal class CpuSysfs(private val root: File = File("/sys/devices/system/cpu")) {

    /**
     * CPU number to capacity for every `cpuN` directory whose `cpu_capacity` holds a number.
     * Empty when the directory cannot be listed or no value can be read, which is a normal
     * answer (older kernels have no such file), not an error.
     */
    fun capacities(): Map<Int, Int> {
        val directories = try {
            root.listFiles()
        } catch (_: SecurityException) {
            null
        } ?: return emptyMap()
        val capacities = HashMap<Int, Int>()
        for (directory in directories) {
            val cpu = CPU_DIRECTORY.matchEntire(directory.name)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val capacity = try {
                File(directory, "cpu_capacity").readText().trim().toIntOrNull()
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
            if (capacity != null) capacities[cpu] = capacity
        }
        return capacities
    }

    private companion object {
        val CPU_DIRECTORY = Regex("""cpu(\d{1,4})""")
    }
}
