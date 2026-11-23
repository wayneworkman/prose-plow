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
import us.theworkmans.proseplow.AppSession
import us.theworkmans.proseplow.SessionFixture
import us.theworkmans.proseplow.core.engine.DeviceContextLimits
import us.theworkmans.proseplow.core.model.ModelCatalog
import us.theworkmans.proseplow.core.model.ModelSpec
import us.theworkmans.proseplow.data.ModelOverrides
import us.theworkmans.proseplow.data.SettingsStore
import us.theworkmans.proseplow.openSession
import java.util.Locale

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
    fun aModelThatContinuesTextHasNoSystemPromptAndShowsItsOwnTemperature() = runTest {
        // Shard-2 is known to the catalogue and not shipped, so the build that carries it is
        // spelled out here.
        val app = SessionFixture(temp.root, testScheduler).apply { models = ModelCatalog.bundled + ModelCatalog.SHARD_2 }
        val session = app.open()
        val viewModel = SettingsViewModel(session.settings, session.models, decimalSeparator = { '.' })
        val shard = ModelCatalog.SHARD_2

        assertTrue(shard in viewModel.models)
        assertFalse(shard.supportsSystemPrompt)
        viewModel.onSystemPromptChange(shard.id, "Ignored")
        assertEquals("", viewModel.systemPrompt(shard.id))
        assertEquals(emptyMap<String, String>(), session.settings.settings.value.systemPrompts)

        assertEquals("the placeholder of its temperature field", "0.9", viewModel.defaultTemperature(shard))
        assertEquals("0,9", SettingsViewModel(session.settings, session.models, decimalSeparator = { ',' }).defaultTemperature(shard))
        assertFalse(shard.sampling.isGreedy)
        viewModel.onTemperatureChange(shard.id, "1")
        assertEquals(shard.sampling.copy(temperature = 1f), session.settings.settings.value.sampling(shard))
        session.close()
    }

    // --- Temperature: the text as typed, and what is stored of it ---

    // --- The longest reply ---

    private fun viewModelOver(session: AppSession) =
        SettingsViewModel(
            session.settings, session.models,
            decimalSeparator = { '.' }, wholeNumber = { "%,d".format(Locale.ROOT, it) }, contextLimits = session.contextLimits,
        )

    @Test
    fun aLongestReplyIsStoredAsItIsTypedAndAnEntryThatIsNoneIsNotSaved() = runTest {
        val app = SessionFixture(temp.root, testScheduler)
        val session = app.open()
        val viewModel = viewModelOver(session)
        val model = ModelCatalog.QWEN35_0_8B
        fun stored() = session.settings.settings.value.overrides(qwen).maxNewTokens
        assertEquals("", viewModel.replyLength(qwen))
        assertNull(stored())
        assertEquals("the default is named", "1,024", viewModel.defaultReplyLength(model))
        assertEquals("a context that may grow leaves room for the longest reply there is", "16" to "4,096", viewModel.replyLengthLimits(model))
        assertEquals("half of a context that cannot", "16" to "1,024", viewModel.replyLengthLimits(ModelCatalog.BORIS_2_INSTRUCT))

        // "300", one key at a time: "3" is below the least and is not saved, "30" and "300" are.
        viewModel.onReplyLengthChange(qwen, "3")
        assertEquals("the field keeps what was typed", "3", viewModel.replyLength(qwen))
        assertTrue(viewModel.replyLengthInvalid(model))
        assertNull("but nothing is stored for it", stored())
        viewModel.onReplyLengthChange(qwen, "30")
        assertFalse(viewModel.replyLengthInvalid(model))
        assertEquals(30, stored())
        viewModel.onReplyLengthChange(qwen, "300")
        assertEquals(300, stored())

        // One digit too many: the entry is marked and the last valid value stays in use.
        viewModel.onReplyLengthChange(qwen, "30000")
        assertTrue(viewModel.replyLengthInvalid(model))
        assertEquals("30000", viewModel.replyLength(qwen))
        assertEquals(300, stored())
        assertEquals(300, session.settings.settings.value.maxNewTokens(model, viewModel.contextLimit(model)))
        viewModel.onReplyLengthChange(qwen, "1,5")
        assertTrue(viewModel.replyLengthInvalid(model))
        assertEquals(300, stored())

        // Emptied, by hand or by the button in the field: the model's own again.
        viewModel.useDefaultReplyLength(qwen)
        assertEquals("", viewModel.replyLength(qwen))
        assertFalse(viewModel.replyLengthInvalid(model))
        assertNull(stored())
        assertEquals(1024, session.settings.settings.value.maxNewTokens(model, viewModel.contextLimit(model)))

        // It is there after a lock, encrypted like the rest, and a new page shows it.
        viewModel.onReplyLengthChange(qwen, "640")
        session.close()
        assertFalse(app.diskContains("maxNewTokens"))
        val again = app.open()
        assertEquals("640", viewModelOver(again).replyLength(qwen))
        again.close()
    }

    @Test
    fun theLongestReplyAppliesFromTheNextAnswer() = runTest {
        val app = SessionFixture(temp.root, testScheduler)
        val session = app.open()
        runCurrent()
        val viewModel = viewModelOver(session)
        val id = session.openSession.id

        app.engine.enqueueReply(listOf("Hi."))
        session.chat.send(id, "Hello")
        runCurrent()
        assertEquals("the model's own until something is set", 1024, app.engine.requests.last().maxNewTokens)

        viewModel.onReplyLengthChange(qwen, "200")
        app.engine.enqueueReply(listOf("Hi again."))
        session.chat.send(id, "Hello again")
        runCurrent()
        assertEquals(200, app.engine.requests.last().maxNewTokens)

        // An entry that is not valid changes nothing about the answers.
        viewModel.onReplyLengthChange(qwen, "20000")
        app.engine.enqueueReply(listOf("Still here."))
        session.chat.send(id, "And now?")
        runCurrent()
        assertEquals(200, app.engine.requests.last().maxNewTokens)
        session.close()
    }

    // --- The context ---

    /** The phone the app was measured on: 5.5 GB of memory. */
    private val testPhone = DeviceContextLimits(totalBytes = 5_500_000_000L)

    @Test
    fun theSizesToChooseFromAreThoseThisPhoneAllowsEachWithWhatItCosts() = runTest {
        val app = SessionFixture(temp.root, testScheduler).apply {
            contextLimits = testPhone
            models = ModelCatalog.bundled + ModelCatalog.SHARD_2
        }
        val session = app.open()
        val viewModel = viewModelOver(session)

        // Qwen3.5: left to itself as far as two minutes of reading; by choice as far as a quarter of the phone's memory.
        val qwenOptions = viewModel.contextOptions(ModelCatalog.QWEN35_0_8B)
        assertEquals(SettingsViewModel.ContextOption(tokens = null, limitTokens = 10_240, memoryBytes = 125_829_120, rereadSeconds = 114), qwenOptions.first())
        assertEquals(listOf(null, 4096, 8192, 16_384, 32_768, 65_536, 111_616), qwenOptions.map { it.tokens })
        assertEquals("a fixed size is its own limit", qwenOptions.drop(1).map { it.tokens }, qwenOptions.drop(1).map { it.limitTokens })
        assertEquals(SettingsViewModel.ContextOption(4096, 4096, 50_331_648, 46), qwenOptions[1])
        assertEquals(SettingsViewModel.ContextOption(111_616, 111_616, 1_371_537_408, 1241), qwenOptions.last())
        assertEquals("said in seconds below a minute and a half, in minutes from there", listOf(2, null, 2, 3, 6, 12, 21), qwenOptions.map { it.rereadMinutes })

        // Peacebell: the phone's memory is the limit, with or without a choice.
        val peacebellOptions = viewModel.contextOptions(ModelCatalog.PEACEBELL_V1_291M)
        assertEquals(listOf(null, 4096, 8192, 16_384, 20_480), peacebellOptions.map { it.tokens })
        assertEquals(SettingsViewModel.ContextOption(null, 20_480, 1_363_148_800, 112), peacebellOptions.first())

        // Models whose context cannot grow have nothing to choose: one fact to state.
        assertEquals(listOf(SettingsViewModel.ContextOption(null, 2048, 2048L * 63_488, 6)), viewModel.contextOptions(ModelCatalog.BORIS_2_INSTRUCT))
        assertEquals(listOf(null), viewModel.contextOptions(ModelCatalog.SHARD_2).map { it.tokens })
        assertEquals(4096, viewModel.contextOptions(ModelCatalog.SHARD_2).single().limitTokens)

        assertEquals("10,240", viewModel.tokens(10_240))
        session.close()
    }

    @Test
    fun aChosenSizeIsStoredAtOnceAndTheLongestReplyFollowsIt() = runTest {
        val app = SessionFixture(temp.root, testScheduler).apply { contextLimits = testPhone }
        var session = app.open()
        runCurrent()
        var viewModel = viewModelOver(session)
        val model = ModelCatalog.QWEN35_0_8B
        fun stored() = session.settings.settings.value.contextTokens(qwen)
        assertNull("automatic until something is chosen", viewModel.contextChoice(model))
        assertNull(stored())
        assertEquals(10_240, viewModel.contextLimit(model))
        assertFalse(viewModel.hasOwnValues(qwen))

        // A reply as long as the automatic context allows.
        viewModel.onReplyLengthChange(qwen, "3000")
        assertFalse(viewModel.replyLengthInvalid(model))

        // The context is held at the size it starts with: half of that is the longest reply now.
        viewModel.onContextChoice(qwen, 4096)
        assertEquals(4096, viewModel.contextChoice(model))
        assertEquals(4096, stored())
        assertEquals(4096, viewModel.contextLimit(model))
        assertTrue(viewModel.hasOwnValues(qwen))
        assertEquals("16" to "2,048", viewModel.replyLengthLimits(model))
        assertTrue("the entry no longer lies within the limits, and is marked", viewModel.replyLengthInvalid(model))
        assertEquals("3000", viewModel.replyLength(qwen))
        val id = session.openSession.id
        app.engine.enqueueReply(listOf("Hi."))
        session.chat.send(id, "Hello")
        runCurrent()
        assertEquals("the size goes with the request", 4096, app.engine.requests.last().maxContextTokens)
        assertEquals("and a reply longer than its limits is not asked for: the model's own is", 1024, app.engine.requests.last().maxNewTokens)
        assertEquals("1,024", viewModel.summary(model, viewModel.stored.value).replyTokens)

        // A larger size by choice, beyond what the context grows to by itself.
        viewModel.onContextChoice(qwen, 32_768)
        assertEquals(32_768, viewModel.contextLimit(model))
        assertFalse("the entry is within the limits again", viewModel.replyLengthInvalid(model))
        app.engine.enqueueReply(listOf("Hi again."))
        session.chat.send(id, "Hello again")
        runCurrent()
        assertEquals(32_768, app.engine.requests.last().maxContextTokens)
        assertEquals(3000, app.engine.requests.last().maxNewTokens)

        // What is not one of the sizes offered is not taken, and neither is anything for a model that is not here.
        for (other in listOf(5000, 2048, 262_144, 0, -1)) viewModel.onContextChoice(qwen, other)
        viewModel.onContextChoice("no-such-model", 8192)
        viewModel.onContextChoice(ModelCatalog.BORIS_2_INSTRUCT.id, 4096)
        assertEquals(32_768, stored())
        assertEquals(setOf(qwen), session.settings.settings.value.modelOverrides.keys)

        // It is there after a lock, encrypted like the rest.
        session.close()
        assertFalse(app.diskContains("contextTokens"))
        session = app.open()
        runCurrent()
        viewModel = viewModelOver(session)
        assertEquals(32_768, viewModel.contextChoice(model))
        assertEquals("3000", viewModel.replyLength(qwen))

        // Back to automatic: nothing is stored for it, and the request leaves the size to the engine.
        viewModel.onContextChoice(qwen, null)
        assertNull(viewModel.contextChoice(model))
        assertNull(stored())
        app.engine.enqueueReply(listOf("Once more."))
        session.chat.send(session.openSession.id, "And again")
        runCurrent()
        assertNull(app.engine.requests.last().maxContextTokens)
        assertEquals(3000, app.engine.requests.last().maxNewTokens)
        session.close()
    }

    @Test
    fun aStoredSizeThatIsNotOfferedHereCountsAsTheLargestBelowIt() = runTest {
        val app = SessionFixture(temp.root, testScheduler).apply { contextLimits = testPhone }
        var session = app.open()
        // As a phone with more memory, or another build, could have stored them.
        session.settings.update(urgent = true) {
            it.withContextTokens(ModelCatalog.PEACEBELL_V1_291M.id, 32_768).withContextTokens(qwen, 12_000).withContextTokens(ModelCatalog.BORIS_2_INSTRUCT.id, 8192)
        }
        session.close()

        session = app.open()
        val viewModel = viewModelOver(session)
        assertEquals("what the phone's memory allows", 20_480, viewModel.contextChoice(ModelCatalog.PEACEBELL_V1_291M))
        assertEquals(20_480, viewModel.contextLimit(ModelCatalog.PEACEBELL_V1_291M))
        assertEquals(8192, viewModel.contextChoice(ModelCatalog.QWEN35_0_8B))
        assertEquals("what is shown as chosen is what is used", 8192, viewModel.contextLimit(ModelCatalog.QWEN35_0_8B))
        runCurrent()
        app.engine.enqueueReply(listOf("Hi."))
        session.chat.send(session.openSession.id, "Hello")
        runCurrent()
        assertEquals(8192, app.engine.requests.last().maxContextTokens)
        assertNull("a model that cannot grow has no choice to show", viewModel.contextChoice(ModelCatalog.BORIS_2_INSTRUCT))
        assertEquals(2048, viewModel.contextLimit(ModelCatalog.BORIS_2_INSTRUCT))
        assertEquals("16" to "1,024", viewModel.replyLengthLimits(ModelCatalog.BORIS_2_INSTRUCT))
        session.close()
    }

    @Test
    fun restoringAModelsDefaultsEmptiesItsNumbersAndLeavesItsSystemPrompt() = runTest {
        val app = SessionFixture(temp.root, testScheduler)
        val session = app.open()
        val viewModel = viewModelOver(session)
        assertFalse("nothing to restore yet", viewModel.hasOwnValues(qwen))

        viewModel.onSystemPromptChange(qwen, "Be brief.")
        assertFalse("a system prompt is not a value with a default", viewModel.hasOwnValues(qwen))
        viewModel.onTemperatureChange(qwen, "1.2")
        viewModel.onReplyLengthChange(qwen, "99999") // Not valid: only the text is there.
        viewModel.onTemperatureChange(peacebell, "0.3")
        viewModel.onContextChoice(qwen, 8192)
        assertTrue(viewModel.hasOwnValues(qwen))

        viewModel.restoreDefaults(qwen)

        assertEquals("", viewModel.temperature(qwen))
        assertEquals("", viewModel.replyLength(qwen))
        assertNull("the context is automatic again", viewModel.contextChoice(ModelCatalog.QWEN35_0_8B))
        assertFalse(viewModel.hasOwnValues(qwen))
        val stored = session.settings.settings.value
        assertEquals(ModelOverrides(), stored.overrides(qwen))
        assertEquals("the system prompt stays", "Be brief.", stored.systemPrompt(qwen))
        assertEquals("and so does every other model", 0.3f, stored.overrides(peacebell).temperature)
        session.close()
    }

    @Test
    fun theRowOfAModelSaysTheValuesItsNextAnswerWouldHave() = runTest {
        val app = SessionFixture(temp.root, testScheduler).apply { models = ModelCatalog.bundled + ModelCatalog.SHARD_2 }
        val session = app.open()
        val list = viewModelOver(session) // The Settings page.
        val page = viewModelOver(session) // A model's page, with a ViewModel of its own over the same store.
        val model = ModelCatalog.QWEN35_0_8B
        fun row(of: ModelSpec = model) = list.summary(of, list.stored.value)

        val own = row()
        assertEquals("1,024", own.replyTokens)
        assertEquals(TemperatureInput.format(model.sampling.temperature.coerceAtLeast(0f), '.'), own.temperature)
        assertEquals(SettingsViewModel.SystemPromptState.NONE, own.systemPrompt)

        page.onTemperatureChange(qwen, "0.7")
        page.onReplyLengthChange(qwen, "512")
        page.onSystemPromptChange(qwen, "Be brief.")
        assertEquals("what the model's page changed is what the row says", SettingsViewModel.ModelSummary("0.7", "512", SettingsViewModel.SystemPromptState.SET), row())

        // An entry that was not saved is not what the row says.
        page.onReplyLengthChange(qwen, "5")
        assertEquals("512", row().replyTokens)
        page.onSystemPromptChange(qwen, "")
        assertEquals(SettingsViewModel.SystemPromptState.NONE, row().systemPrompt)

        // Models that take no system prompt say nothing about one.
        assertEquals(SettingsViewModel.SystemPromptState.NOT_POSSIBLE, row(ModelCatalog.PEACEBELL_V1_291M).systemPrompt)
        assertEquals(SettingsViewModel.SystemPromptState.NOT_POSSIBLE, row(ModelCatalog.SHARD_2).systemPrompt)
        assertEquals("200", row(ModelCatalog.SHARD_2).replyTokens)
        assertEquals("512", row(ModelCatalog.BORIS_2_INSTRUCT).replyTokens)
        session.close()
    }

    @Test
    fun aStoredLongestReplyTheModelDoesNotAcceptIsNeitherUsedNorShown() = runTest {
        val app = SessionFixture(temp.root, testScheduler)
        var session = app.open()
        // As a build with a larger context for Boris-2 could have stored it.
        session.settings.update(urgent = true) { it.withMaxNewTokens(ModelCatalog.BORIS_2_INSTRUCT.id, 2000).withMaxNewTokens(qwen, 2000) }
        session.close()

        session = app.open()
        val viewModel = viewModelOver(session)
        assertEquals("", viewModel.replyLength(ModelCatalog.BORIS_2_INSTRUCT.id))
        assertFalse(viewModel.replyLengthInvalid(ModelCatalog.BORIS_2_INSTRUCT))
        assertEquals("512", viewModel.summary(ModelCatalog.BORIS_2_INSTRUCT, viewModel.stored.value).replyTokens)
        assertEquals("the model that accepts it shows it", "2000", viewModel.replyLength(qwen))
        session.close()
    }

    // --- The temperature ---

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
