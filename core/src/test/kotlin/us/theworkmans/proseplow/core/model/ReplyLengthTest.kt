package us.theworkmans.proseplow.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import us.theworkmans.proseplow.core.engine.LlamaEngine

/** The limits of a model's longest reply, and that every model's own default lies within them. */
class ReplyLengthTest {

    @Test
    fun `the shortest longest reply is the least room the engine begins a reply with`() {
        assertEquals(16, ReplyLength.MIN_TOKENS)
        assertEquals(ReplyLength.MIN_TOKENS, LlamaEngine.Settings().minReplyTokens)
    }

    @Test
    fun `the longest is half of the context`() {
        assertEquals(2048, ReplyLength.maxTokens(4096))
        assertEquals(1024, ReplyLength.maxTokens(2048))
        assertEquals("rounded down", 2048, ReplyLength.maxTokens(4097))
        assertEquals(0, ReplyLength.maxTokens(1))
    }

    @Test
    fun `each model of the catalogue has its range and a default inside it`() {
        val expected = mapOf(
            ModelCatalog.QWEN35_0_8B to Triple(16, 1024, 2048),
            ModelCatalog.PEACEBELL_V1_291M to Triple(16, 1024, 2048),
            ModelCatalog.BORIS_2_INSTRUCT to Triple(16, 512, 1024),
            ModelCatalog.SHARD_2 to Triple(16, 200, 2048),
        )
        val known = ModelCatalog.bundled + ModelCatalog.SHARD_2
        assertEquals("every model is looked at", known.toSet(), expected.keys)
        for ((model, numbers) in expected) {
            val (least, default, most) = numbers
            assertEquals(model.id, least..most, model.replyLengthRange)
            assertEquals(model.id, default, model.defaultMaxNewTokens)
            assertTrue("${model.id}: the default must be a value the user could have typed", model.acceptsReplyLength(model.defaultMaxNewTokens))
            assertEquals("${model.id}: half of the context it runs with on the phone", model.contextLength / 2, most)
        }
    }

    @Test
    fun `a length is accepted from the least to the most and nowhere else`() {
        val model = ModelCatalog.BORIS_2_INSTRUCT
        assertFalse(model.acceptsReplyLength(15))
        assertTrue(model.acceptsReplyLength(16))
        assertTrue(model.acceptsReplyLength(1024))
        assertFalse(model.acceptsReplyLength(1025))
        assertFalse(model.acceptsReplyLength(0))
        assertFalse(model.acceptsReplyLength(-512))
        assertFalse(model.acceptsReplyLength(Int.MAX_VALUE))
        assertTrue("what another model accepts", ModelCatalog.QWEN35_0_8B.acceptsReplyLength(1025))
    }
}
