package us.theworkmans.proseplow.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import us.theworkmans.proseplow.core.prompt.CompletionFormat
import us.theworkmans.proseplow.core.prompt.PeacebellChatMlFormat
import us.theworkmans.proseplow.core.prompt.PlainChatMlFormat
import us.theworkmans.proseplow.core.prompt.Qwen35ChatMlFormat

/**
 * Pins every catalogue value that was read from a model's own files, so that none changes by
 * accident. The catalogue's comments say which file each number came from.
 */
class ModelCatalogTest {

    @Test
    fun `a long conversation is read in 128s by a model with recurrent state and in the session's whole batch otherwise`() {
        assertEquals(256, ModelSpec.MAX_EVALUATION_CHUNK)
        assertEquals(128, ModelCatalog.QWEN35_0_8B.deepReadChunk)
        assertEquals(128, ModelCatalog.BORIS_2_INSTRUCT.deepReadChunk)
        assertEquals(256, ModelCatalog.PEACEBELL_V1_291M.deepReadChunk)
        assertEquals(256, ModelCatalog.SHARD_2.deepReadChunk)
        for (model in ModelCatalog.all) assertTrue("${model.id}: at most the session's batch", model.deepReadChunk in 1..ModelSpec.MAX_EVALUATION_CHUNK)
        assertThrows(IllegalArgumentException::class.java) { ModelCatalog.QWEN35_0_8B.copy(deepReadChunk = 257) }
        assertThrows(IllegalArgumentException::class.java) { ModelCatalog.QWEN35_0_8B.copy(deepReadChunk = 0) }
    }

    @Test
    fun `only the models with recurrent state are marked as unable to cut their memory back`() {
        assertEquals(
            listOf(ModelCatalog.QWEN35_0_8B.id, ModelCatalog.BORIS_2_INSTRUCT.id),
            ModelCatalog.all.filter { !it.cutsMemoryBack }.map { it.id },
        )
    }

    @Test
    fun `the catalogue has the four known models in picker order`() {
        assertEquals(
            listOf("qwen3.5-0.8b", "peacebell-v1-291m", "boris-2-instruct", "shard-2"),
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
    fun `what is offered is what is bundled, and a model can be known without being bundled`() {
        // Every catalogue model ships today, so the rule is shown on a copy of one that does
        // not: a model is bundled unless it says otherwise, and `bundled` alone decides whether
        // the catalogue offers it.
        val notShipped = ModelCatalog.BORIS_2_INSTRUCT.copy(id = "known-but-not-shipped", ggufFileName = "known-but-not-shipped.gguf", bundled = false)
        assertTrue(ModelCatalog.BORIS_2_INSTRUCT.bundled)
        assertFalse(notShipped.bundled)
        assertEquals(ModelCatalog.all.filter { it.bundled }, ModelCatalog.bundled)
        assertEquals(ModelCatalog.bundled, (ModelCatalog.all + notShipped).filter { it.bundled })
        assertEquals(
            listOf("qwen3.5-0.8b", "peacebell-v1-291m", "boris-2-instruct", "shard-2"),
            ModelCatalog.bundled.map { it.id },
        )
        assertEquals(emptyList<String>(), ModelCatalog.all.filterNot { it.bundled }.map { it.id })
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
    fun `Shard-2 continues text with its reference sampling and a deletion token, and is offered`() {
        val model = ModelCatalog.SHARD_2
        assertEquals("shard-2", model.id)
        assertEquals("Shard-2", model.displayName)
        assertEquals(ModelFamily.COMPLETION, model.family)
        assertSame(CompletionFormat, model.promptFormat)
        assertTrue(model.family.continuesText)
        assertEquals("shard-2.gguf", model.ggufFileName)
        assertEquals(4096, model.contextLength)
        assertEquals(200, model.defaultMaxNewTokens)
        assertEquals(SamplingParams(temperature = 0.9f, topK = 100, topP = 1.0f, minP = 0.0f), model.sampling)
        assertEquals("no penalties", SamplingParams().copy(temperature = 0.9f, topK = 100), model.sampling)
        assertFalse(model.sampling.isGreedy)
        assertEquals(listOf(1, 2), model.stopTokenIds)
        assertEquals(emptyList<String>(), model.stopStrings)
        assertEquals(emptyList<Int>(), model.bannedTokenIds)
        assertEquals(4096, model.deletionTokenId)
        assertFalse(model.supportsSystemPrompt)
        assertEquals("Glint-Research/Shard-2", model.sourceRepository)
        assertEquals("MIT", model.licence)
        // Bundled since the licence its model card declares was accepted (FEATURES.md, DEC-57).
        assertTrue(model.bundled)
        assertTrue(model in ModelCatalog.bundled)
        assertSame(model, ModelCatalog.find("shard-2"))
    }

    @Test
    fun `only Shard-2 has a deletion token and only it continues text`() {
        assertEquals(listOf("shard-2"), ModelCatalog.all.filter { it.deletionTokenId != null }.map { it.id })
        assertEquals(listOf("shard-2"), ModelCatalog.all.filter { it.family.continuesText }.map { it.id })
    }

    @Test
    fun `a deletion token cannot also end the reply or be banned`() {
        val valid = ModelCatalog.SHARD_2
        assertThrows(IllegalArgumentException::class.java) { valid.copy(stopTokenIds = listOf(1, 4096)) }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(bannedTokenIds = listOf(4096)) }
        assertEquals(7, valid.copy(deletionTokenId = 7).deletionTokenId)
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

    /** Only a bundled model whose file is present is offered: a model that is not bundled never is, whatever file is there. */
    @Test
    fun theAppOffersOnlyBundledModelsWhoseFilesArePresent() {
        assertEquals("every file there", ModelCatalog.bundled, ModelCatalog.offered { true })
        assertEquals(emptyList<ModelSpec>(), ModelCatalog.offered { false })
        assertEquals(listOf(ModelCatalog.SHARD_2), ModelCatalog.offered { it == ModelCatalog.SHARD_2 })
        // A model that is not bundled is never offered, whatever file is there.
        val notShipped = ModelCatalog.SHARD_2.copy(id = "known-but-not-shipped", ggufFileName = "known-but-not-shipped.gguf", bundled = false)
        assertFalse(notShipped in ModelCatalog.offered { true })
        assertEquals(
            "in catalogue order",
            listOf(ModelCatalog.QWEN35_0_8B, ModelCatalog.BORIS_2_INSTRUCT),
            ModelCatalog.offered { it == ModelCatalog.BORIS_2_INSTRUCT || it == ModelCatalog.QWEN35_0_8B },
        )
    }
}
