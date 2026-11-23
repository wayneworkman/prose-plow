package us.theworkmans.proseplow.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import us.theworkmans.proseplow.core.model.ModelCatalog
import us.theworkmans.proseplow.core.model.ModelSpec

/**
 * How large a context may be on a device, in numbers: the phone the app was measured on (5.5 GB
 * of memory, about 2.6 GB available in use) with each model of the catalogue, and the edges.
 */
class ContextPolicyTest {

    private val policy = ContextPolicy()
    private val gib = 1024L * 1024 * 1024
    private val phoneTotal = 5_500_000_000L
    private val phone = DeviceMemory(totalBytes = phoneTotal, availableBytes = 2_600_000_000L, thresholdBytes = 220_000_000L, low = false)

    @Test
    fun `the numbers the policy starts with`() {
        assertEquals(0.25, policy.maxShareOfTotal, 0.0)
        assertEquals(0.20, policy.floorShareOfTotal, 0.0)
        assertEquals(2, policy.floorThresholdMultiple)
        assertEquals(120, policy.rereadSeconds)
        assertEquals(1024, ContextPolicy.GRANULARITY)
    }

    @Test
    fun `a context takes at most a quarter of the device's memory`() {
        assertEquals(1_375_000_000L, policy.capBytes(phoneTotal))
        assertEquals(2 * gib, policy.capBytes(8 * gib))
    }

    @Test
    fun `a fifth of the memory stays available, or twice what the system calls low when that is more`() {
        assertEquals(1_100_000_000L, policy.floorBytes(phone))
        assertEquals("the system's own threshold counts when it is the larger", 1_400_000_000L, policy.floorBytes(phone.copy(thresholdBytes = 700_000_000L)))
    }

    @Test
    fun `the budget is the cap, and no more than what is held plus what is available above the floor`() {
        // 2.6 GB available less the 1.1 GB floor is 1.5 GB of room: the cap of 1.375 GB is the smaller.
        assertEquals(1_375_000_000L, policy.budgetBytes(phone, currentBytes = 0))
        // With less available, the room counts: 1.6 GB less 1.1 GB, on top of the 270 MB held.
        val tighter = phone.copy(availableBytes = 1_600_000_000L)
        assertEquals(500_000_000L, policy.budgetBytes(tighter, currentBytes = 0))
        assertEquals(770_000_000L, policy.budgetBytes(tighter, currentBytes = 270_000_000L))
        // Below the floor nothing may be added: the budget is less than what is held already.
        val short = phone.copy(availableBytes = 900_000_000L)
        assertEquals(70_000_000L, policy.budgetBytes(short, currentBytes = 270_000_000L))
        assertEquals("never less than nothing", 0L, policy.budgetBytes(short, currentBytes = 0))
        // A device nobody asked has room for anything, without overflowing.
        assertEquals(policy.capBytes(DeviceMemory.PLENTY.totalBytes), policy.budgetBytes(DeviceMemory.PLENTY, currentBytes = Long.MAX_VALUE / 2))
        assertTrue(policy.budgetBytes(DeviceMemory.PLENTY, currentBytes = 0) > 1L shl 50)
    }

    @Test
    fun `the device's memory limits each model of the catalogue on the test phone`() {
        fun limit(model: ModelSpec) =
            policy.memoryLimit(model.maxContextLength, model.contextBytesPerToken, phoneTotal)
        // 1.375 GB over 66,560 bytes a token is 20,658 tokens: 20 whole thousands of 1,024.
        assertEquals(20_480, limit(ModelCatalog.PEACEBELL_V1_291M))
        // Over 12,288 bytes it is 111,897: 109 times 1,024. Far below the model's 262,144.
        assertEquals(111_616, limit(ModelCatalog.QWEN35_0_8B))
        assertEquals("a model whose own maximum fits has its own maximum", 2048, limit(ModelCatalog.BORIS_2_INSTRUCT))
        assertEquals(4096, limit(ModelCatalog.SHARD_2))
        // A phone with 12 GB holds all of Peacebell's context.
        assertEquals(32_768, policy.memoryLimit(32_768, 66_560, 12 * gib))
        assertEquals("a cost nobody knows limits nothing", 32_768, policy.memoryLimit(32_768, 0, phoneTotal))
        assertEquals(0, policy.memoryLimit(32_768, 66_560, totalBytes = 200_000_000L))
    }

    @Test
    fun `left to itself a context grows as far as two minutes of reading`() {
        assertEquals("90 tokens a second: 10,800, in whole 1,024s", 10_240, policy.timeLimit(90.0))
        assertEquals(21_504, policy.timeLimit(184.0))
        assertEquals(45_056, policy.timeLimit(380.0))
        assertEquals(0, policy.timeLimit(0.0))
        assertEquals(0, policy.timeLimit(-5.0))
        assertEquals(0, policy.timeLimit(Double.NaN))
        assertEquals(0, policy.timeLimit(8.0)) // 960 tokens: less than one 1,024.
    }

    @Test
    fun `the limit of each model on the test phone, automatic and chosen`() {
        fun limit(model: ModelSpec, chosen: Int? = null, speed: Double = model.readTokensPerSecond.toDouble()) = policy.limit(
            start = model.contextLength, maxContext = model.maxContextLength, bytesPerToken = model.contextBytesPerToken,
            totalBytes = phoneTotal, chosen = chosen, tokensPerSecond = speed,
        )
        val qwen = ModelCatalog.QWEN35_0_8B
        val peacebell = ModelCatalog.PEACEBELL_V1_291M
        val boris = ModelCatalog.BORIS_2_INSTRUCT

        assertEquals("the time decides for Qwen", 10_240, limit(qwen))
        assertEquals("the memory decides for Peacebell: 20,480 is less than two minutes' 21,504", 20_480, limit(peacebell))
        assertEquals("the model decides for Boris-2", 2048, limit(boris))
        assertEquals(4096, limit(ModelCatalog.SHARD_2))

        // The speed measured in the session takes the catalogue's place: out of sight Qwen reads 67 a second.
        assertEquals(7168, limit(qwen, speed = 67.0))
        assertEquals("never below the size it starts with", 4096, limit(qwen, speed = 10.0))
        assertEquals(4096, limit(qwen, speed = 0.0))

        // A size the user chose is held to the model and the memory, not to the time.
        assertEquals(32_768, limit(qwen, chosen = 32_768))
        assertEquals(111_616, limit(qwen, chosen = 262_144))
        assertEquals(20_480, limit(peacebell, chosen = 32_768))
        assertEquals(8192, limit(peacebell, chosen = 8192))
        assertEquals(2048, limit(boris, chosen = 8192))
        assertEquals("and may be smaller than the start", 2048, limit(qwen, chosen = 2048))
    }

    @Test
    fun `a context grows by doubling and its last step is the limit itself`() {
        assertEquals(listOf(4096, 8192, 10_240), policy.steps(start = 4096, limit = 10_240))
        assertEquals(listOf(4096, 8192, 16_384, 20_480), policy.steps(4096, 20_480))
        assertEquals(listOf(4096, 8192, 16_384, 32_768), policy.steps(4096, 32_768))
        assertEquals(listOf(4096), policy.steps(4096, 4096))
        assertEquals("a limit below the start is the only size", listOf(2048), policy.steps(4096, 2048))
        assertEquals(listOf(4096, 4097), policy.steps(4096, 4097))
        val steps = policy.steps(4096, 262_144)
        assertEquals(7, steps.size)
        assertEquals(262_144, steps.last())
        assertTrue(steps.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun `the sizes a user may choose are the steps up to what the memory allows, each with its time`() {
        assertEquals(listOf(4096, 8192, 16_384, 20_480), policy.choices(ModelCatalog.PEACEBELL_V1_291M, phoneTotal))
        assertEquals(listOf(4096, 8192, 16_384, 32_768, 65_536, 111_616), policy.choices(ModelCatalog.QWEN35_0_8B, phoneTotal))
        assertEquals("a model that cannot grow has one size", listOf(2048), policy.choices(ModelCatalog.BORIS_2_INSTRUCT, phoneTotal))
        assertEquals(listOf(4096), policy.choices(ModelCatalog.SHARD_2, phoneTotal))
        assertEquals(10_240, policy.limit(ModelCatalog.QWEN35_0_8B, phoneTotal, chosen = null))
        assertEquals(32_768, policy.limit(ModelCatalog.QWEN35_0_8B, phoneTotal, chosen = 32_768))

        assertEquals(46, policy.rereadSeconds(4096, 90.0))
        assertEquals("six minutes and a bit for 32,768 tokens at 90 a second", 365, policy.rereadSeconds(32_768, 90.0))
        assertEquals(null, policy.rereadSeconds(4096, 0.0))
    }

    @Test
    fun `a device's limits are the policy's with the memory it has, and a stored choice stands for a size it offers`() {
        val limits = DeviceContextLimits(policy, phoneTotal)
        val qwen = ModelCatalog.QWEN35_0_8B
        val peacebell = ModelCatalog.PEACEBELL_V1_291M
        assertEquals(policy.choices(qwen, phoneTotal), limits.choices(qwen))
        assertEquals(10_240, limits.limit(qwen, chosen = null))
        assertEquals(32_768, limits.limit(qwen, chosen = 32_768))
        assertEquals(12_288L * 8192, limits.bytes(qwen, 8192))
        assertEquals(92, limits.rereadSeconds(qwen, 8192))
        assertEquals("a model of which the catalogue says neither", 0L to null, testModel().let { limits.bytes(it, 4096) to limits.rereadSeconds(it, 4096) })

        assertEquals(null, limits.chosen(qwen, stored = null))
        assertEquals(8192, limits.chosen(qwen, 8192))
        assertEquals("not one of the sizes: the largest below it", 8192, limits.chosen(qwen, 12_000))
        assertEquals("more than this phone's memory allows", 20_480, limits.chosen(peacebell, 32_768))
        assertEquals("below the smallest", 4096, limits.chosen(qwen, 1024))
        assertEquals("a model that cannot grow has one size", 2048, limits.chosen(ModelCatalog.BORIS_2_INSTRUCT, 8192))
        assertEquals(2048, limits.limit(ModelCatalog.BORIS_2_INSTRUCT, limits.chosen(ModelCatalog.BORIS_2_INSTRUCT, 8192)))

        // Nobody asked the device: plenty, and only the model and the time are limits.
        val plenty = DeviceContextLimits()
        assertEquals(262_144, plenty.choices(qwen).last())
        assertEquals(10_240, plenty.limit(qwen, chosen = null))
        assertEquals(21_504, plenty.limit(peacebell, chosen = null))
    }

    @Test
    fun `other numbers are other policies`() {
        val cautious = ContextPolicy(maxShareOfTotal = 0.1, floorShareOfTotal = 0.4, floorThresholdMultiple = 3, rereadSeconds = 60)
        assertEquals(550_000_000L, cautious.capBytes(phoneTotal))
        assertEquals(2_200_000_000L, cautious.floorBytes(phone))
        assertEquals(400_000_000L, cautious.budgetBytes(phone, 0))
        assertEquals(5120, cautious.timeLimit(90.0))
    }
}
