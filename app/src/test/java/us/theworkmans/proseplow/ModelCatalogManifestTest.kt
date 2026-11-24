package us.theworkmans.proseplow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import us.theworkmans.proseplow.core.model.ModelCatalog
import java.io.File

/**
 * What the catalogue says about the size of each model's context is what the models' manifest
 * says: the context a model was trained with, and the memory one token of context costs. The
 * manifest's numbers are held to the model files themselves by a test of the native build, so
 * through this test the numbers the settings show and the policy plans with are the files' own.
 */
class ModelCatalogManifestTest {

    /** The module directory: Gradle runs the unit tests from it. */
    private val repository = checkNotNull(File("").absoluteFile.parentFile)

    private val entries: Map<String, JsonObject> =
        Json.parseToJsonElement(File(repository, "models/manifest.json").readText()).jsonObject
            .getValue("models").jsonArray.map { it.jsonObject }
            .associateBy { checkNotNull(it["id"]).jsonPrimitive.content }

    private fun JsonObject.number(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

    @Test
    fun everyModelOfTheCatalogueIsInTheManifest() {
        for (model in ModelCatalog.all) assertTrue(model.id, model.id in entries)
    }

    @Test
    fun theCataloguesContextNumbersAreTheManifests() {
        for (model in ModelCatalog.all) {
            val entry = entries.getValue(model.id)
            val trained = checkNotNull(entry.number(TRAINED_CONTEXT)) { "${model.id} has no whole number as $TRAINED_CONTEXT" }
            val bytesPerToken = checkNotNull(entry.number(BYTES_PER_TOKEN)) { "${model.id} has no whole number as $BYTES_PER_TOKEN" }
            assertEquals("${model.id}: the memory a token of context costs", bytesPerToken, model.contextBytesPerToken)
            assertTrue("${model.id}: a context never grows beyond what the model was trained with", model.maxContextLength <= trained)
            // The catalogue may say less than the file allows (it does for a model whose card says less), and says so there; a model that is shipped says the same.
            if (model.bundled) assertEquals("${model.id}: the context it was trained with", trained, model.maxContextLength.toLong())
            assertTrue("${model.id}: it starts no larger than it can become", minOf(model.contextLength, model.maxContextLength) <= model.maxContextLength)
        }
    }

    private companion object {
        const val TRAINED_CONTEXT = "trained_context"
        const val BYTES_PER_TOKEN = "context_bytes_per_token"
    }
}
