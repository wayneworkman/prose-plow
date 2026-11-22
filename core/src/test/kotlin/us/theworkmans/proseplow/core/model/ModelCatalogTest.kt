package us.theworkmans.proseplow.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import us.theworkmans.proseplow.core.prompt.PeacebellChatMlFormat
import us.theworkmans.proseplow.core.prompt.PlainChatMlFormat
import us.theworkmans.proseplow.core.prompt.Qwen35ChatMlFormat

/**
 * Pins every catalogue value that was read from a model's own files, so that none changes by
 * accident. The catalogue's comments say which file each number came from.
 */
class ModelCatalogTest {

    @Test
    fun `the catalogue has the four known models in picker order`() {
        assertEquals(
            listOf("qwen3.5-0.8b", "peacebell-v1-291m", "boris-2-instruct", "boris2-peacebell-think"),
            ModelCatalog.all.map { it.id },
        )
    }

    @Test
    fun `ids and file names are unique`() {
        assertEquals(ModelCatalog.all.size, ModelCatalog.all.map { it.id }.toSet().size)
        assertEquals(ModelCatalog.all.size, ModelCatalog.all.map { it.ggufFileName }.toSet().size)
        assertTrue(ModelCatalog.all.all { it.ggufFileName.endsWith(".gguf") })
    }

    @Test
    fun `the model in training is known but not offered`() {
        assertFalse(ModelCatalog.BORIS2_PEACEBELL_THINK.bundled)
        assertEquals(
            listOf("qwen3.5-0.8b", "peacebell-v1-291m", "boris-2-instruct"),
            ModelCatalog.bundled.map { it.id },
        )
        assertSame(ModelCatalog.BORIS2_PEACEBELL_THINK, ModelCatalog.find("boris2-peacebell-think"))
    }

    @Test
    fun `the default model is one that ships`() {
        assertTrue(ModelCatalog.default in ModelCatalog.bundled)
    }

    @Test
    fun `find returns null for an id no build knows`() {
        assertNull(ModelCatalog.find("no-such-model"))
        assertSame(ModelCatalog.QWEN35_0_8B, ModelCatalog.find("qwen3.5-0.8b"))
    }

    @Test
    fun `every bundled model names its source and licence`() {
        for (model in ModelCatalog.bundled) {
            assertTrue(model.id, model.sourceRepository.matches(Regex("[^/ ]+/[^/ ]+")))
            assertNotNull(model.id, model.licence)
            assertTrue(model.id, model.displayName.isNotBlank() && model.description.isNotBlank())
        }
    }

    @Test
    fun `Qwen3_5 samples as its card recommends for text without thinking`() {
        val model = ModelCatalog.QWEN35_0_8B
        assertEquals(
            SamplingParams(temperature = 1.0f, topK = 20, topP = 1.0f, minP = 0.0f, repetitionPenalty = 1.0f, presencePenalty = 2.0f),
            model.sampling,
        )
        assertSame(Qwen35ChatMlFormat, model.promptFormat)
        assertEquals(emptyList<String>(), model.stopStrings)
        assertEquals(listOf(248046, 248044), model.stopTokenIds)
        assertEquals(emptyList<Int>(), model.bannedTokenIds)
        assertTrue(model.supportsSystemPrompt)
        assertEquals("Qwen/Qwen3.5-0.8B", model.sourceRepository)
        assertEquals("Apache-2.0", model.licence)
    }

    @Test
    fun `peacebell is greedy, bans token 0 and stops on token 4`() {
        val model = ModelCatalog.PEACEBELL_V1_291M
        assertTrue(model.sampling.isGreedy)
        assertEquals(SamplingParams(temperature = 0.0f, topK = 50, topP = 0.9f), model.sampling)
        assertSame(PeacebellChatMlFormat, model.promptFormat)
        assertEquals(listOf(0), model.bannedTokenIds)
        assertEquals(listOf(4), model.stopTokenIds)
        assertEquals(emptyList<String>(), model.stopStrings)
        assertFalse(model.supportsSystemPrompt)
        assertEquals("wayneworkman2012/peacebell-v1-291M", model.sourceRepository)
        assertEquals("Apache-2.0", model.licence)
    }

    @Test
    fun `Boris-2-Instruct samples as its generation config says and stops at the marker text`() {
        val model = ModelCatalog.BORIS_2_INSTRUCT
        assertEquals(SamplingParams(temperature = 0.7f, topK = 50, topP = 0.9f, repetitionPenalty = 1.1f), model.sampling)
        assertEquals(2048, model.contextLength)
        assertEquals(512, model.defaultMaxNewTokens)
        assertSame(PlainChatMlFormat, model.promptFormat)
        assertEquals(listOf("<|im_end|>"), model.stopStrings)
        assertEquals(listOf(0), model.stopTokenIds)
        assertEquals(emptyList<Int>(), model.bannedTokenIds)
        assertTrue(model.supportsSystemPrompt)
        assertEquals("opencerebral/Boris-2-Instruct", model.sourceRepository)
        assertEquals("Apache-2.0", model.licence)
    }

    @Test
    fun `boris2-peacebell-think is plain ChatML with a stop string and never emits token 188`() {
        val model = ModelCatalog.BORIS2_PEACEBELL_THINK
        assertSame(PlainChatMlFormat, model.promptFormat)
        assertEquals(listOf("<|im_end|>"), model.stopStrings)
        assertEquals(listOf(188), model.bannedTokenIds)
        assertEquals(listOf(0), model.stopTokenIds)
        assertTrue(model.supportsSystemPrompt)
        assertNull("the licence is not stated until the model is published", model.licence)
    }

    @Test
    fun `the phone context leaves room for a reply and a conversation`() {
        for (model in ModelCatalog.all) {
            assertTrue(model.id, model.contextLength in 2048..8192)
            assertTrue(model.id, model.defaultMaxNewTokens * 2 <= model.contextLength)
        }
    }

    @Test
    fun `a model specification rejects values that cannot work`() {
        val valid = ModelCatalog.QWEN35_0_8B
        assertThrows(IllegalArgumentException::class.java) { valid.copy(id = " ") }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(contextLength = 0) }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(defaultMaxNewTokens = 0) }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(defaultMaxNewTokens = valid.contextLength + 1) }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(stopStrings = listOf("")) }
    }

    @Test
    fun `greedy is temperature zero or below`() {
        assertTrue(SamplingParams().isGreedy)
        assertTrue(SamplingParams(temperature = -1f).isGreedy)
        assertFalse(SamplingParams(temperature = 0.01f).isGreedy)
    }
}
