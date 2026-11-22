package us.theworkmans.proseplow.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import us.theworkmans.proseplow.SessionFixture
import us.theworkmans.proseplow.core.model.ModelCatalog
import us.theworkmans.proseplow.data.SettingsStore
import us.theworkmans.proseplow.openSession

/** The Settings screen's ViewModel over the real session: edits are saved as they are made. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val qwen = ModelCatalog.QWEN35_0_8B.id
    private val peacebell = ModelCatalog.PEACEBELL_V1_291M.id

    @Test
    fun aSystemPromptIsSavedAsItIsTypedAndIsThereAfterALock() = runTest {
        val app = SessionFixture(temp.root, testScheduler)
        var session = app.open()
        var viewModel = SettingsViewModel(session.settings, session.models)
        assertEquals("", viewModel.systemPrompt(qwen))

        viewModel.onSystemPromptChange(qwen, "Answer in one sen")
        viewModel.onSystemPromptChange(qwen, "Answer in one sentence.")

        assertEquals("the field has it at once", "Answer in one sentence.", viewModel.systemPrompt(qwen))
        assertEquals("and so have the settings, with no Save", "Answer in one sentence.", session.settings.settings.value.systemPrompt(qwen))
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(app.layout.settings.exists())
        assertFalse(app.diskContains("sentence"))

        // Typed just before the screen is left.
        viewModel.onSystemPromptChange(qwen, "Answer in one sentence, please.")
        session.close()

        session = app.open()
        viewModel = SettingsViewModel(session.settings, session.models)
        assertEquals("Answer in one sentence, please.", viewModel.systemPrompt(qwen))
        session.close()
    }

    @Test
    fun thePromptAppliesFromTheNextAnswer() = runTest {
        val app = SessionFixture(temp.root, testScheduler)
        val session = app.open()
        runCurrent()
        val viewModel = SettingsViewModel(session.settings, session.models)

        viewModel.onSystemPromptChange(qwen, "Be brief.")
        app.engine.enqueueReply(listOf("Hi."))
        session.chat.send(session.openSession.id, "Hello")
        runCurrent()
        assertEquals("Be brief.", app.engine.requests.single().systemPrompt)

        viewModel.onSystemPromptChange(qwen, "")
        assertEquals("a prompt that is cleared is removed, not stored as empty", emptyMap<String, String>(), session.settings.settings.value.systemPrompts)
        session.close()
    }

    @Test
    fun everyModelOfThisBuildHasARowAndOneWithoutASystemPromptCannotBeGivenOne() = runTest {
        val app = SessionFixture(temp.root, testScheduler)
        val session = app.open()
        val viewModel = SettingsViewModel(session.settings, session.models)

        assertEquals(ModelCatalog.bundled, viewModel.models)
        assertFalse(ModelCatalog.PEACEBELL_V1_291M.supportsSystemPrompt)
        viewModel.onSystemPromptChange(peacebell, "Ignored")
        viewModel.onSystemPromptChange("not a model of this build", "Ignored")

        assertEquals("", viewModel.systemPrompt(peacebell))
        assertEquals(emptyMap<String, String>(), session.settings.settings.value.systemPrompts)
        session.close()
    }

    // --- Temperature: the text as typed, and what is stored of it ---

    @Test
    fun aTemperatureIsStoredAsItIsTypedAndTheFieldKeepsTheText() = runTest {
        val app = SessionFixture(temp.root, testScheduler)
        val session = app.open()
        val viewModel = SettingsViewModel(session.settings, session.models, decimalSeparator = { '.' })
        fun stored() = session.settings.settings.value.overrides(qwen).temperature
        assertEquals("", viewModel.temperature(qwen))
        assertNull(stored())

        // "0.75", one key at a time: every state on the way is a number, and each is stored.
        val typed = listOf("0" to 0f, "0." to 0f, "0.7" to 0.7f, "0.75" to 0.75f)
        for ((text, value) in typed) {
            viewModel.onTemperatureChange(qwen, text)
            assertEquals("the text is kept exactly as typed", text, viewModel.temperature(qwen))
            assertFalse(text, viewModel.temperatureInvalid(qwen))
            assertEquals(text, value, stored())
        }
        session.close()
        assertFalse(app.diskContains("temperature"))
        val reopened = app.open()
        assertEquals(0.75f, reopened.settings.settings.value.overrides(qwen).temperature)
        reopened.close()
    }

    @Test
    fun anEntryThatIsNotATemperatureIsShownAsTypedAndTheLastValidValueStays() = runTest {
        val app = SessionFixture(temp.root, testScheduler)
        val session = app.open()
        val viewModel = SettingsViewModel(session.settings, session.models, decimalSeparator = { '.' })
        fun stored() = session.settings.settings.value.overrides(qwen).temperature
        viewModel.onTemperatureChange(qwen, "0.7")

        for (text in listOf("0.7x", "3", "2.01", "-1", "1e1", "0,7", "abc")) {
            viewModel.onTemperatureChange(qwen, text)
            assertEquals(text, viewModel.temperature(qwen))
            assertTrue("$text is marked as not valid", viewModel.temperatureInvalid(qwen))
            assertEquals("not saved until valid: $text", 0.7f, stored())
        }

        // Corrected: it is valid again and stored.
        viewModel.onTemperatureChange(qwen, "2")
        assertFalse(viewModel.temperatureInvalid(qwen))
        assertEquals(2f, stored())
        viewModel.onTemperatureChange(qwen, "0")
        assertEquals("the lower end is a value, not an empty field", 0f, stored())
        session.close()
    }

    @Test
    fun anInvalidEntryInAFieldThatNeverHadAValueStoresNothing() = runTest {
        val app = SessionFixture(temp.root, testScheduler)
        val session = app.open()
        val viewModel = SettingsViewModel(session.settings, session.models, decimalSeparator = { '.' })

        viewModel.onTemperatureChange(qwen, "hot")

        assertTrue(viewModel.temperatureInvalid(qwen))
        assertEquals(emptyMap<String, Any>(), session.settings.settings.value.modelOverrides)
        session.close()
        assertFalse("and nothing was written for it", app.layout.settings.exists())
    }

    @Test
    fun emptyingTheFieldOrOneTapGoesBackToTheModelsOwnTemperature() = runTest {
        val app = SessionFixture(temp.root, testScheduler)
        val session = app.open()
        val viewModel = SettingsViewModel(session.settings, session.models, decimalSeparator = { '.' })
        fun stored() = session.settings.settings.value.overrides(qwen).temperature

        viewModel.onTemperatureChange(qwen, "1.5")
        viewModel.onTemperatureChange(qwen, "")
        assertNull(stored())
        assertFalse(viewModel.temperatureInvalid(qwen))

        viewModel.onTemperatureChange(qwen, "1.5")
        viewModel.useDefaultTemperature(qwen)
        assertEquals("", viewModel.temperature(qwen))
        assertNull(stored())

        // Also from an entry that was not valid: the tap clears it and the stored value with it.
        viewModel.onTemperatureChange(qwen, "1.5")
        viewModel.onTemperatureChange(qwen, "1.5.5")
        assertEquals(1.5f, stored())
        viewModel.useDefaultTemperature(qwen)
        assertEquals("", viewModel.temperature(qwen))
        assertFalse(viewModel.temperatureInvalid(qwen))
        assertNull(stored())
        assertEquals(emptyMap<String, Any>(), session.settings.settings.value.modelOverrides)
        session.close()
    }

    @Test
    fun theLocalesDecimalCommaIsReadAndShown() = runTest {
        val app = SessionFixture(temp.root, testScheduler)
        var session = app.open()
        var viewModel = SettingsViewModel(session.settings, session.models, decimalSeparator = { ',' })

        viewModel.onTemperatureChange(qwen, "0,7")
        assertFalse(viewModel.temperatureInvalid(qwen))
        assertEquals(0.7f, session.settings.settings.value.overrides(qwen).temperature)
        viewModel.onTemperatureChange(peacebell, "1.25")
        assertEquals("a point is read everywhere", 1.25f, session.settings.settings.value.overrides(peacebell).temperature)
        val boris = ModelCatalog.BORIS_2_INSTRUCT
        assertEquals("a model's own temperature is shown the same way", TemperatureInput.format(boris.sampling.temperature, ','), viewModel.defaultTemperature(boris))
        session.close()

        // The screen opened again shows what is stored, in the locale's notation.
        session = app.open()
        viewModel = SettingsViewModel(session.settings, session.models, decimalSeparator = { ',' })
        assertEquals("0,7", viewModel.temperature(qwen))
        assertEquals("1,25", viewModel.temperature(peacebell))
        assertEquals("", viewModel.temperature(ModelCatalog.BORIS_2_INSTRUCT.id))
        session.close()

        // And in another locale, the same values with a point.
        session = app.open()
        viewModel = SettingsViewModel(session.settings, session.models, decimalSeparator = { '.' })
        assertEquals("0.7", viewModel.temperature(qwen))
        assertFalse(viewModel.temperatureInvalid(qwen))
        session.close()
    }

    @Test
    fun everyModelOfThisBuildHasATemperatureAlsoOneWithoutASystemPrompt() = runTest {
        val app = SessionFixture(temp.root, testScheduler)
        val session = app.open()
        val viewModel = SettingsViewModel(session.settings, session.models, decimalSeparator = { '.' })
        assertFalse(ModelCatalog.PEACEBELL_V1_291M.supportsSystemPrompt)

        viewModel.onTemperatureChange(peacebell, "0.4")
        viewModel.onTemperatureChange("not a model of this build", "0.4")

        assertEquals(mapOf(peacebell to 0.4f), session.settings.settings.value.modelOverrides.mapValues { it.value.temperature })
        assertEquals("", viewModel.temperature("not a model of this build"))
        // What the screen says each model's own temperature is: the catalogue's, as a field shows a value.
        for (model in ModelCatalog.all) {
            assertEquals(model.id, TemperatureInput.format(model.sampling.temperature, '.'), viewModel.defaultTemperature(model))
        }
        assertEquals("a model that is greedy by default", "0", viewModel.defaultTemperature(ModelCatalog.PEACEBELL_V1_291M))
        assertTrue(ModelCatalog.PEACEBELL_V1_291M.sampling.isGreedy)
        session.close()
    }

    @Test
    fun settingsThatCouldNotBeReadAreSaidUntilAcknowledged() = runTest {
        val app = SessionFixture(temp.root, testScheduler)
        app.layout.settings.writeBytes(byteArrayOf(1, 2, 3))
        val session = app.open()
        val viewModel = SettingsViewModel(session.settings, session.models)

        assertEquals(SettingsStore.LoadIssue.UNREADABLE, viewModel.loadIssue.value)
        viewModel.loadIssueShown()
        assertNull(viewModel.loadIssue.value)
        session.close()
    }
}
