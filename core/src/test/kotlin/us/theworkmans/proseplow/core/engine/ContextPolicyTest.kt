package us.theworkmans.proseplow.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import us.theworkmans.proseplow.core.model.ModelCatalog
import us.theworkmans.proseplow.core.model.ModelSpec
import us.theworkmans.proseplow.core.model.ReadingTimes

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
        // Qwen3.5 reads 6,144 tokens in 99.6 s and 8,192 in 138.8 s: two minutes reach 7,209, which is 7,168 in whole 1,024s.
        assertEquals(7168, policy.timeLimit(ModelCatalog.QWEN35_0_8B.readingTimes))
        assertEquals("a constant 90 tokens a second: 10,800, in whole 1,024s", 10_240, policy.timeLimit(ReadingTimes.flat(90.0)))
        assertEquals("Peacebell: 9,949 tokens in two minutes", 9216, policy.timeLimit(ModelCatalog.PEACEBELL_V1_291M.readingTimes))
        assertEquals("Boris-2, by its curve beyond its own 2,048 tokens: 9,647", 9216, policy.timeLimit(ModelCatalog.BORIS_2_INSTRUCT.readingTimes))
        assertEquals("times nobody knows", 0, policy.timeLimit(ReadingTimes.UNKNOWN))
        assertEquals("960 tokens: less than one 1,024", 0, policy.timeLimit(ReadingTimes.flat(8.0)))
        assertEquals("a phone that takes half as long again reads 4,096 (96 s) but not 5,120 (123 s)", 4096, policy.timeLimit(ModelCatalog.QWEN35_0_8B.readingTimes.scaled(1.5)))
    }

    @Test
    fun `the limit of each model on the test phone, automatic and chosen`() {
        fun limit(model: ModelSpec, chosen: Int? = null, times: ReadingTimes = model.readingTimes) = policy.limit(
            start = model.contextLength, maxContext = model.maxContextLength, bytesPerToken = model.contextBytesPerToken,
            totalBytes = phoneTotal, chosen = chosen, readingTimes = times,
        )
        val qwen = ModelCatalog.QWEN35_0_8B
        val peacebell = ModelCatalog.PEACEBELL_V1_291M
        val boris = ModelCatalog.BORIS_2_INSTRUCT

        assertEquals("the time decides for Qwen", 7168, limit(qwen))
        assertEquals("and for Peacebell: 9,216 is less than the memory's 20,480", 9216, limit(peacebell))
        assertEquals("the model decides for Boris-2", 2048, limit(boris))
        assertEquals(4096, limit(ModelCatalog.SHARD_2))

        // The curve scaled to what the session measured takes the catalogue's place.
        assertEquals("a faster phone: 10,240 in 110 s, 11,264 in 123 s", 10_240, limit(qwen, times = qwen.readingTimes.scaled(0.6)))
        assertEquals("never below the size it starts with", 4096, limit(qwen, times = qwen.readingTimes.scaled(4.0)))
        assertEquals(4096, limit(qwen, times = ReadingTimes.UNKNOWN))

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
        assertEquals(7168, policy.limit(ModelCatalog.QWEN35_0_8B, phoneTotal, chosen = null))
        assertEquals(32_768, policy.limit(ModelCatalog.QWEN35_0_8B, phoneTotal, chosen = 32_768))

        val qwenTimes = ModelCatalog.QWEN35_0_8B.readingTimes
        assertEquals(64, policy.rereadSeconds(4096, qwenTimes))
        assertEquals("rounded up to the whole second", 82, policy.rereadSeconds(5120, qwenTimes))
        assertEquals("fourteen minutes for 32,768 tokens, far more than eight times the 4,096", 843, policy.rereadSeconds(32_768, qwenTimes))
        assertEquals("an hour and forty minutes for the most this phone's memory allows", 5988, policy.rereadSeconds(111_616, qwenTimes))
        assertEquals(null, policy.rereadSeconds(4096, ReadingTimes.UNKNOWN))
    }

    @Test
    fun `a device's limits are the policy's with the memory it has, and a stored choice stands for a size it offers`() {
        val limits = DeviceContextLimits(policy, phoneTotal)
        val qwen = ModelCatalog.QWEN35_0_8B
        val peacebell = ModelCatalog.PEACEBELL_V1_291M
        assertEquals(policy.choices(qwen, phoneTotal), limits.choices(qwen))
        assertEquals(7168, limits.limit(qwen, chosen = null))
        assertEquals(32_768, limits.limit(qwen, chosen = 32_768))
        assertEquals(12_288L * 8192, limits.bytes(qwen, 8192))
        assertEquals(139, limits.rereadSeconds(qwen, 8192))
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
        assertEquals(7168, plenty.limit(qwen, chosen = null))
        assertEquals(9216, plenty.limit(peacebell, chosen = null))
    }

    @Test
    fun `other numbers are other policies`() {
        val cautious = ContextPolicy(maxShareOfTotal = 0.1, floorShareOfTotal = 0.4, floorThresholdMultiple = 3, rereadSeconds = 60)
        assertEquals(550_000_000L, cautious.capBytes(phoneTotal))
        assertEquals(2_200_000_000L, cautious.floorBytes(phone))
        assertEquals(400_000_000L, cautious.budgetBytes(phone, 0))
        assertEquals("60 s reach 3,879 tokens of Qwen3.5: 3,072 in whole 1,024s", 3072, cautious.timeLimit(ModelCatalog.QWEN35_0_8B.readingTimes))
    }
}
