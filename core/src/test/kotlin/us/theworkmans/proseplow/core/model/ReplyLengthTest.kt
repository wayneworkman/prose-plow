package us.theworkmans.proseplow.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import us.theworkmans.proseplow.core.engine.DeviceContextLimits
import us.theworkmans.proseplow.core.engine.LlamaEngine

/** The limits of a model's longest reply, and that every model's own default lies within them. */
class ReplyLengthTest {

    @Test
    fun `the shortest longest reply is the least room the engine begins a reply with`() {
        assertEquals(16, ReplyLength.MIN_TOKENS)
        assertEquals(ReplyLength.MIN_TOKENS, LlamaEngine.Settings().minReplyTokens)
    }

    @Test
    fun `the longest is half of the context and never more than 4,096`() {
        assertEquals(4096, ReplyLength.MAX_TOKENS)
        assertEquals(2048, ReplyLength.maxTokens(4096))
        assertEquals(1024, ReplyLength.maxTokens(2048))
        assertEquals("rounded down", 2048, ReplyLength.maxTokens(4097))
        assertEquals(0, ReplyLength.maxTokens(1))
        assertEquals(4095, ReplyLength.maxTokens(8191))
        assertEquals(4096, ReplyLength.maxTokens(8192))
        assertEquals("a larger context does not make for longer replies", 4096, ReplyLength.maxTokens(262_144))
        assertEquals(16..4096, ReplyLength.range(10_240))
        assertEquals(16..1024, ReplyLength.range(2048))
    }

    @Test
    fun `each model of the catalogue has a default inside its range, at the size it starts with and on the test phone`() {
        // The model's own default, the most at the size its context starts with, and the most left to itself on the phone the app was measured on.
        val expected = mapOf(
            ModelCatalog.QWEN35_0_8B to Triple(1024, 2048, 3584),
            ModelCatalog.PEACEBELL_V1_291M to Triple(1024, 2048, 4096),
            ModelCatalog.BORIS_2_INSTRUCT to Triple(512, 1024, 1024),
            ModelCatalog.SHARD_2 to Triple(200, 2048, 2048),
        )
        val known = ModelCatalog.all
        assertEquals("every model is looked at", known.toSet(), expected.keys)
        val phone = DeviceContextLimits(totalBytes = 5_500_000_000L)
        for ((model, numbers) in expected) {
            val (default, atStart, onPhone) = numbers
            val start = minOf(model.contextLength, model.maxContextLength)
            assertEquals(model.id, default, model.defaultMaxNewTokens)
            assertEquals("${model.id}: half of the context it starts with", 16..atStart, ReplyLength.range(start))
            assertTrue("${model.id}: the default must be a value the user could have typed, whatever size is chosen", default in ReplyLength.range(start))
            assertEquals("${model.id}: left to itself on the phone", 16..onPhone, ReplyLength.range(phone.limit(model, chosen = null)))
            assertEquals("${model.id}: with the context held at its starting size", 16..atStart, ReplyLength.range(phone.limit(model, chosen = start)))
        }
    }

    @Test
    fun `a length is accepted from the least to the most and nowhere else`() {
        val range = ReplyLength.range(2048)
        assertFalse(15 in range)
        assertTrue(16 in range)
        assertTrue(1024 in range)
        assertFalse(1025 in range)
        assertFalse(0 in range)
        assertFalse(-512 in range)
        assertFalse(Int.MAX_VALUE in range)
        assertTrue("what a larger context accepts", 1025 in ReplyLength.range(4096))
        assertFalse("and the longest of all", 4097 in ReplyLength.range(262_144))
    }
}
