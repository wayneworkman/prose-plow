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
