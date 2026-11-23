package us.theworkmans.proseplow.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import us.theworkmans.proseplow.R
import us.theworkmans.proseplow.core.chat.toChatError
import us.theworkmans.proseplow.core.engine.EngineError
import us.theworkmans.proseplow.core.engine.LlamaEngine
import us.theworkmans.proseplow.core.model.ModelCatalog
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** What the app calls its models, checked against their sources. */
class ModelTextTest {

    /** The module directory: Gradle runs the unit tests from it. */
    private val module = File("").absoluteFile

    /** The app's strings, by name, with Android's escapes undone. */
    private val strings: Map<String, String> by lazy {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(module, "src/main/res/values/strings.xml"))
        val nodes = document.getElementsByTagName("string")
        (0 until nodes.length).associate { index ->
            val element = nodes.item(index) as Element
            element.getAttribute("name") to element.textContent.replace("\\n", "\n").replace("\\\"", "\"").replace("\\'", "'")
        }
    }

    @Test
    fun everyModelOfTheCatalogueHasANameAndADescriptionInTheResources() {
        val names = HashSet<Int>()
        val descriptions = HashSet<Int>()
        for (model in ModelCatalog.all) {
            val name = ModelText.nameRes(model.id)
            val description = ModelText.descriptionRes(model.id)
            assertNotNull("${model.id} has no name", name)
            assertNotNull("${model.id} has no description", description)
            assertTrue("${model.id} shares its name with another model", names.add(name!!))
            assertTrue("${model.id} shares its description with another model", descriptions.add(description!!))
        }
    }

    @Test
    fun aModelThisVersionDoesNotKnowHasNoTextOfItsOwn() {
        assertNull(ModelText.nameRes("a-model-from-another-build"))
        assertNull(ModelText.descriptionRes("a-model-from-another-build"))
        assertNull(ModelText.nameRes(""))
    }

    @Test
    fun theNamesShownAreTheModelsProperNames() {
        // The catalogue's English names are the proper names; the resources must not drift from them.
        for (model in ModelCatalog.all) {
            val resource = "model_name_" + model.id.replace(Regex("[^a-z0-9]+"), "_")
            assertEquals(resource, model.displayName, strings[resource])
            assertNotNull(strings["model_description_" + model.id.replace(Regex("[^a-z0-9]+"), "_")])
        }
    }

    @Test
    fun aModelThatContinuesTextIsWordedAsOneAndEveryOtherAsBefore() {
        val shard = ModelCatalog.SHARD_2.id
        assertEquals(R.string.empty_title_completion, ModelText.emptyTitleRes(shard))
        assertEquals(R.string.empty_hint_completion, ModelText.emptyHintRes(shard))
        assertEquals(R.string.message_hint_completion, ModelText.messageHintRes(shard))
        assertEquals(R.string.system_prompt_unsupported_completion, ModelText.noSystemPromptRes(shard))

        // Chat models, a chat without a usable model, and a model of another build keep the wording they had.
        val others = ModelCatalog.all.filter { !it.family.continuesText }.map { it.id } + listOf(null, "a-model-from-another-build")
        assertEquals(ModelCatalog.all.size + 1, others.size)
        for (modelId in others) {
            assertEquals(modelId, R.string.empty_title, ModelText.emptyTitleRes(modelId))
            assertEquals(modelId, R.string.empty_hint, ModelText.emptyHintRes(modelId))
            assertEquals(modelId, R.string.message_hint, ModelText.messageHintRes(modelId))
            assertEquals(modelId, R.string.system_prompt_unsupported, ModelText.noSystemPromptRes(modelId))
        }
    }

    @Test
    fun theWordingForAModelThatContinuesTextTakesTheSameArgumentsAsTheWordingItReplaces() {
        // Each pair is shown by the same line of a screen, which passes the model's name or nothing.
        val pairs = mapOf(
            "empty_title" to "empty_title_completion",
            "empty_hint" to "empty_hint_completion",
            "message_hint" to "message_hint_completion",
            "system_prompt_unsupported" to "system_prompt_unsupported_completion",
        )
        for ((chat, completion) in pairs) {
            val placeholders = { name: String -> Regex("%[0-9]\\$[sd]").findAll(strings.getValue(name)).map { it.value }.toList() }
            assertEquals(completion, placeholders(chat), placeholders(completion))
            assertTrue(completion, strings.getValue(completion).isNotBlank())
        }
        assertFalse("a model that continues text is not said to answer", "answer" in strings.getValue("empty_hint_completion"))
    }

    @Test
    fun aModelThePhoneHasTooLittleMemoryForIsSaidInItsOwnWords() {
        assertEquals(R.string.error_model_too_little_memory_detail, ModelText.loadFailedDetailRes(LlamaEngine.TOO_LITTLE_MEMORY))
        assertTrue(ModelText.isTooLittleMemory(LlamaEngine.TOO_LITTLE_MEMORY))
        for (other in listOf(null, "", "the file is missing", LlamaEngine.TOO_LITTLE_MEMORY + ".")) {
            assertEquals(other, R.string.error_model_load_failed_detail, ModelText.loadFailedDetailRes(other))
            assertFalse(ModelText.isTooLittleMemory(other))
        }
        val words = strings.getValue("error_model_too_little_memory_detail")
        assertTrue("it says what is short, and that it is so now", "too little free memory" in words && "right now" in words)
        // The engine's own failure to open a model carries exactly that detail.
        assertEquals(LlamaEngine.TOO_LITTLE_MEMORY, EngineError.LoadFailed("m", LlamaEngine.TOO_LITTLE_MEMORY).toChatError(null).detail)
    }

    @Test
    fun whatIsSaidAboutShard2IsWhatItIs() {
        val description = strings.getValue("model_description_shard_2")
        for (fact in listOf("Continues the text you write", "not a chat model", "does not answer questions", "take back", "invented")) {
            assertTrue("the description leaves out: $fact", fact in description)
        }
        assertEquals("Shard-2", strings.getValue("model_name_shard_2"))
        assertTrue("not a chat model" in strings.getValue("system_prompt_unsupported_completion"))
    }
}
