package us.theworkmans.proseplow.llama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import us.theworkmans.proseplow.core.model.ModelCatalog
import java.io.File

/**
 * The build and the app agree on what a model's asset is called.
 *
 * The build names an asset after the model's id in `models/manifest.json`
 * (`models/<id>.gguf.so`); the app looks for `models/<ggufFileName>.so` from the catalogue. The
 * two files live in different languages and nothing else ties them together, so this test does:
 * every model the manifest marks as bundled must be a catalogue model whose file name is its id.
 */
class BundledModelNamesTest {

    /** The ids of the manifest entries with `"purpose": "bundled"`, read without a JSON library. */
    private fun bundledIds(): List<String> {
        val manifest = File(checkNotNull(System.getProperty("proseplow.manifest")) { "the test task passes the manifest's path" }).readText()
        val ids = ArrayList<String>()
        // Entries are objects of the "models" array; each has an id before its purpose.
        for (entry in manifest.split(Regex("""\{\s*"id"\s*:\s*""")).drop(1)) {
            val id = entry.substringAfter('"').substringBefore('"')
            if (Regex(""""purpose"\s*:\s*"bundled"""").containsMatchIn(entry.substringBefore("\n    }"))) ids += id
        }
        return ids
    }

    @Test
    fun `every bundled model of the manifest is a catalogue model with the asset name the build writes`() {
        val ids = bundledIds()
        assertTrue("the manifest lists bundled models", ids.isNotEmpty())
        for (id in ids) {
            val model = checkNotNull(ModelCatalog.find(id)) { "$id is bundled by the manifest but unknown to the catalogue" }
            assertEquals("models/$id.gguf.so", ModelFiles.assetPath(model))
        }
    }

    @Test
    fun `every model the catalogue ships is one the manifest can bundle`() {
        assertEquals(ModelCatalog.bundled.map { it.id }.sorted(), bundledIds().sorted())
    }
}
