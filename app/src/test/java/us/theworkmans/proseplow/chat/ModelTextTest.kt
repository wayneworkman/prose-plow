package us.theworkmans.proseplow.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.w3c.dom.Element
import us.theworkmans.proseplow.core.model.ModelCatalog
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** What the app calls its models, and the licence text it shows, checked against their sources. */
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
    fun theLicenceShownForTheInferenceLibraryIsTheOneItShipsWith() {
        val licence = File(module.parentFile, "third_party/llama.cpp/LICENSE")
        // The library is a submodule; a checkout without it cannot be compared against.
        assumeTrue(licence.isFile)
        val normalise = { text: String -> text.trim().split(Regex("\\s+")).joinToString(" ") }
        assertEquals(normalise(licence.readText()), normalise(strings.getValue("about_llama_cpp_licence")))
    }
}
