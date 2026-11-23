package us.theworkmans.proseplow.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

/** The address of a model's card: always the model's repository on Hugging Face, or no address at all. */
class ModelCardTest {

    @Test
    fun `every model of the catalogue has a card on Hugging Face and nowhere else`() {
        assertEquals("huggingface.co", ModelCard.HOST)
        assertEquals(4, ModelCatalog.all.size)
        for (model in ModelCatalog.all) {
            val url = checkNotNull(ModelCard.url(model)) { "${model.id} has no card address" }
            assertEquals("https://huggingface.co/${model.sourceRepository}", url)
            assertEquals("huggingface.co/${model.sourceRepository}", ModelCard.address(model))
            // Read back as an address: the scheme, the host and a path of owner and name; nothing more.
            val parsed = URI(url)
            assertEquals("https", parsed.scheme)
            assertEquals("huggingface.co", parsed.host)
            assertEquals(-1, parsed.port)
            assertNull(parsed.userInfo)
            assertNull(parsed.query)
            assertNull(parsed.fragment)
            assertEquals(listOf("", model.sourceRepository.substringBefore('/'), model.sourceRepository.substringAfter('/')), parsed.path.split('/'))
        }
        assertEquals("https://huggingface.co/Qwen/Qwen3.5-0.8B", ModelCard.url(ModelCatalog.QWEN35_0_8B))
    }

    @Test
    fun `a repository written any other way yields no address`() {
        val model = ModelCatalog.QWEN35_0_8B
        val notRepositories = listOf(
            "", " ", "Qwen", "Qwen/", "/Qwen3.5-0.8B", "Qwen//Qwen3.5-0.8B", "Qwen/Qwen3.5-0.8B/", "Qwen/Qwen3.5-0.8B/tree/main",
            "../../evil", "Qwen/..", "./Qwen", "Qwen/.hidden", "-Qwen/model", "Qwen/a..b",
            "evil.example/x?y", "Qwen/model?download=true", "Qwen/model#readme", "Qwen/mo del", " Qwen/model", "Qwen/model ",
            "Qwen/model\n", "Qwen\\model", "user@evil.example/x", "Qwen/model@evil.example", "Qwen:80/model", "Qwen/model:80",
            "https://evil.example/a/b", "//evil.example/a", "evil.example", "Qwen/%2e%2e", "Qwen/mod%2Fel", "Qw\u00E9n/model",
            "a".repeat(97) + "/model",
        )
        for (repository in notRepositories) {
            assertFalse("\"$repository\"", ModelCard.isRepository(repository))
            val odd = model.copy(sourceRepository = repository)
            assertNull("\"$repository\"", ModelCard.url(odd))
            assertNull("\"$repository\"", ModelCard.address(odd))
        }
    }

    @Test
    fun `whatever is accepted stays on the one host`() {
        // Names that look like hosts or paths are still only an owner and a name under huggingface.co.
        for (repository in listOf("evil.example/x", "huggingface.co/evil", "a/b", "A1/b_c-d.e", "0/0")) {
            assertTrue(repository, ModelCard.isRepository(repository))
            val url = checkNotNull(ModelCard.url(ModelCatalog.QWEN35_0_8B.copy(sourceRepository = repository)))
            assertEquals("huggingface.co", URI(url).host)
            assertEquals("/$repository", URI(url).path)
        }
    }
}
