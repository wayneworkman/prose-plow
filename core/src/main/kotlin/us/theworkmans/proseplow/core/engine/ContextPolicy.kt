package us.theworkmans.proseplow.core.engine

import us.theworkmans.proseplow.core.model.ModelSpec
import us.theworkmans.proseplow.core.model.ReadingTimes

/**
 * What the device says about its memory at one moment, in bytes: Android's
 * `ActivityManager.MemoryInfo`, without Android.
 *
 * @property totalBytes the memory the device has.
 * @property availableBytes what the system counts as available now.
 * @property thresholdBytes the available memory below which the system itself considers memory
 *   low and begins to end processes.
 * @property low the system's own verdict that memory is low now.
 */
data class DeviceMemory(val totalBytes: Long, val availableBytes: Long, val thresholdBytes: Long, val low: Boolean) {
    companion object {
        /** A device nobody has asked: so much memory that nothing here is ever the limit. For tests and for an engine without a gauge. */
        val PLENTY = DeviceMemory(totalBytes = Long.MAX_VALUE / 4, availableBytes = Long.MAX_VALUE / 4, thresholdBytes = 0, low = false)
    }
}

/** Reads the device's memory. Asked before every decision about the size of the context, never remembered. */
fun interface MemoryGauge {
    fun read(): DeviceMemory
}

/**
 * What this device allows the context of a model, for those who show it or set by it before a
 * model is loaded: the policy, and the memory the device has, which does not change while it
 * runs. A device nobody asked has plenty.
 */
class DeviceContextLimits(val policy: ContextPolicy = ContextPolicy(), val totalBytes: Long = DeviceMemory.PLENTY.totalBytes) {
    /** The limit of [model]'s context under the user's choice of size; null is automatic. */
    fun limit(model: ModelSpec, chosen: Int?): Int = policy.limit(model, totalBytes, chosen)

    /** The fixed sizes the user may choose from for [model], smallest first. */
    fun choices(model: ModelSpec): List<Int> = policy.choices(model, totalBytes)

    /**
     * The size a [stored] choice stands for on this device: itself when it is one of the
     * [choices], otherwise the largest of them below it, or the smallest when there is none
     * (the device or the catalogue was another when it was stored). Null, the automatic size,
     * stays null. What is shown as chosen and what is used are both this, so they agree.
     */
    fun chosen(model: ModelSpec, stored: Int?): Int? {
        stored ?: return null
        val fixed = choices(model)
        return fixed.lastOrNull { it <= stored } ?: fixed.first()
    }

    /** The memory a context of [tokens] costs for [model], in bytes; 0 when the catalogue does not say. */
    fun bytes(model: ModelSpec, tokens: Int): Long = tokens.toLong() * model.contextBytesPerToken

    /**
     * How long [model] takes to read a conversation of [tokens] again, in whole seconds, by the
     * reading times the catalogue gives for the tested phone; null when it gives none.
     */
    fun rereadSeconds(model: ModelSpec, tokens: Int): Int? = policy.rereadSeconds(tokens, model.readingTimes)
}

/**
 * How large a model's context may be on this device, and through which sizes it grows. Pure
 * arithmetic, so that every number can be tested and changed in one place.
 *
 * A context is created at a starting size and grows only when a conversation needs it, so its
 * memory follows the real length of the conversation. Three things bound it:
 *
 * - **The model**: its trained context. Nothing is gained beyond it.
 * - **The device's memory.** The context's memory is allocated in full and is resident at once;
 *   a size the phone cannot hold is not refused by the system, the process is ended while the
 *   memory is being prepared. So the guard is never to ask: the context may take at most
 *   [maxShareOfTotal] of the device's memory, and when it grows only as much as leaves
 *   [floorBytes] available to everything else ([budgetBytes]). A model is opened by the cap
 *   alone ([openingBudgetBytes]).
 * - **Time**, for the automatic setting. Whenever the process has ended (the app was locked and
 *   left), the whole conversation is read again before the next answer. Left to itself the
 *   context therefore grows only as far as can be read again in [rereadSeconds], by the time
 *   this model takes on this phone. That time grows faster than the length, because every
 *   token read looks at all the tokens before it, so it is taken from a measured curve
 *   ([ReadingTimes]) and never from the speed of a short prompt. A larger size is the user's
 *   explicit choice.
 *
 * @param maxShareOfTotal the largest share of the device's memory a context may take.
 * @param floorShareOfTotal the share of the device's memory that must stay available.
 * @param floorThresholdMultiple the multiple of the system's own low-memory threshold that must
 *   stay available as well; the larger of the two floors counts.
 * @param rereadSeconds how long reading a whole conversation again may take under the automatic
 *   setting.
 */
class ContextPolicy(
    val maxShareOfTotal: Double = 0.25,
    val floorShareOfTotal: Double = 0.20,
    val floorThresholdMultiple: Int = 2,
    val rereadSeconds: Int = 120,
) {
    init {
        require(maxShareOfTotal > 0 && maxShareOfTotal <= 1) { "maxShareOfTotal must be a share" }
        require(floorShareOfTotal >= 0 && floorShareOfTotal < 1) { "floorShareOfTotal must be a share" }
        require(floorThresholdMultiple >= 0 && rereadSeconds > 0)
    }

    /** The most bytes a context may ever take on a device with [totalBytes] of memory. */
    fun capBytes(totalBytes: Long): Long = (totalBytes * maxShareOfTotal).toLong()

    /** The available memory that must be left to everything else. */
    fun floorBytes(memory: DeviceMemory): Long =
        maxOf((memory.totalBytes * floorShareOfTotal).toLong(), saturatingTimes(memory.thresholdBytes, floorThresholdMultiple))

    /**
     * The most bytes the context's memory may occupy at any moment from now on, when it holds
     * [currentBytes] already: the cap, and no more than what it has plus what is available
     * above the floor. Never less than nothing; it can be less than [currentBytes], which
     * means that nothing may be added.
     */
    fun budgetBytes(memory: DeviceMemory, currentBytes: Long): Long {
        val room = saturatingPlus(currentBytes, memory.availableBytes - floorBytes(memory))
        return minOf(capBytes(memory.totalBytes), room).coerceAtLeast(0)
    }

    /**
     * The most bytes the tokens of a context may take when a model is opened: the cap alone,
     * [maxShareOfTotal] of the device's memory, whatever is available at that moment. The floor
     * and the system's low-memory verdict play no part here; they hold only when a context grows
     * ([budgetBytes]).
     *
     * The reason: Android makes room for the app in front by closing others, so memory that is
     * taken now is not memory the opening lacks; and a refusal at opening leaves the user with
     * no model at all, while a refused growth step only caps the length of the conversation.
     */
    fun openingBudgetBytes(memory: DeviceMemory): Long = capBytes(memory.totalBytes)

    /**
     * The largest context the device's memory allows for a model: the model's own maximum, or
     * as many tokens as fit the cap, rounded down to a whole number of [GRANULARITY] tokens. It
     * depends on the device and not on the moment. 0 when not even [GRANULARITY] tokens fit.
     */
    fun memoryLimit(maxContext: Int, bytesPerToken: Long, totalBytes: Long): Int {
        if (bytesPerToken <= 0) return maxContext
        val tokens = capBytes(totalBytes) / bytesPerToken
        return if (tokens >= maxContext) maxContext else roundDown(tokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    /**
     * The largest whole multiple of [GRANULARITY] tokens that [times] read from nothing within
     * [rereadSeconds]; 0 for times nobody knows. Since the curve rises strictly, that is the
     * most tokens read in the time, rounded down.
     */
    fun timeLimit(times: ReadingTimes): Int {
        if (!times.isKnown) return 0
        return roundDown(times.tokensWithin(rereadSeconds.toDouble()))
    }

    /**
     * The limit of a model's context on this device.
     *
     * @param start the size a context begins with; the limit is never below it unless the model
     *   or the user's choice is.
     * @param chosen the user's fixed choice, or null for automatic. A choice is held to the
     *   model and the device's memory, not to the time: it is the user's to spend.
     * @param readingTimes how long the model takes to read a conversation on this device, for
     *   the automatic setting; times nobody knows keep the automatic limit at [start].
     */
    fun limit(start: Int, maxContext: Int, bytesPerToken: Long, totalBytes: Long, chosen: Int?, readingTimes: ReadingTimes): Int {
        val byModel = maxContext.coerceAtLeast(1)
        val byMemory = memoryLimit(byModel, bytesPerToken, totalBytes).coerceAtLeast(minOf(start, byModel))
        if (chosen != null) return chosen.coerceIn(1, byMemory)
        val byTime = maxOf(timeLimit(readingTimes), start)
        return minOf(byModel, byMemory, byTime)
    }

    /**
     * The sizes a context goes through on its way from [start] to [limit]: [start], then
     * doubling, and the limit itself as the last. Only [limit] when it is not above [start].
     */
    fun steps(start: Int, limit: Int): List<Int> {
        if (limit <= start) return listOf(limit.coerceAtLeast(1))
        val steps = ArrayList<Int>()
        var size = start.coerceAtLeast(1).toLong()
        while (size < limit) {
            steps += size.toInt()
            size *= 2
        }
        steps += limit
        return steps
    }

    /**
     * The limit of [model]'s context on a device with [totalBytes] of memory, by what the
     * catalogue says of the model: for showing and for the settings, before the model is loaded.
     * The engine decides with what the loaded model says of itself, and with the catalogue's
     * reading times scaled to what it has measured.
     */
    fun limit(model: ModelSpec, totalBytes: Long, chosen: Int?, readingTimes: ReadingTimes = model.readingTimes): Int = limit(
        start = minOf(model.contextLength, model.maxContextLength),
        maxContext = model.maxContextLength,
        bytesPerToken = model.contextBytesPerToken,
        totalBytes = totalBytes,
        chosen = chosen,
        readingTimes = readingTimes,
    )

    /**
     * The fixed sizes a user may choose for [model] on a device with [totalBytes] of memory:
     * the steps from the starting size to the most the device's memory allows. One size only
     * for a model that cannot grow here.
     */
    fun choices(model: ModelSpec, totalBytes: Long): List<Int> {
        val start = minOf(model.contextLength, model.maxContextLength)
        return steps(start, limit(model, totalBytes, chosen = Int.MAX_VALUE))
    }

    /** How long reading [tokens] from nothing takes by [times], rounded up to whole seconds; null for times nobody knows. */
    fun rereadSeconds(tokens: Int, times: ReadingTimes): Int? =
        if (times.isKnown) Math.ceil(times.seconds(tokens)).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt() else null

    private fun roundDown(tokens: Int): Int = tokens / GRANULARITY * GRANULARITY

    private fun saturatingTimes(value: Long, times: Int): Long = if (times != 0 && value > Long.MAX_VALUE / times) Long.MAX_VALUE else value * times

    private fun saturatingPlus(a: Long, b: Long): Long = if (b > 0 && a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

    companion object {
        /** Limits that are not a model's own maximum are whole multiples of this many tokens. */
        const val GRANULARITY: Int = 1024
    }
}
