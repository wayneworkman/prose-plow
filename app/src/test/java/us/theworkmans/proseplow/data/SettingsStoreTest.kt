package us.theworkmans.proseplow.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import us.theworkmans.proseplow.DataLayout
import us.theworkmans.proseplow.core.model.ModelCatalog
import us.theworkmans.proseplow.security.AppFiles
import us.theworkmans.proseplow.security.BlobCipher
import us.theworkmans.proseplow.security.Envelope
import us.theworkmans.proseplow.security.TestKeys
import java.io.File

/** The stored form of the settings, and the store that keeps them: saved as edited, never lost to a file that could not be read. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val cipher = TestKeys.cipher()
    private val files by lazy { AppFiles(temp.newFolder("files"), temp.newFolder("cache")) }
    private val layout by lazy { DataLayout(files) }
    private val failures = ArrayList<String>()

    /** Counts what is sealed, which is once per write of the file. */
    private var seals = 0
    private val countingCipher = object : BlobCipher {
        override fun seal(purpose: String, plaintext: ByteArray): ByteArray {
            seals++
            return cipher.seal(purpose, plaintext)
        }

        override fun open(purpose: String, blob: ByteArray): ByteArray = cipher.open(purpose, blob)
    }

    private fun TestScope.open(): SettingsStore {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val writer = CoalescingWriter(scope, lazyMillis = 1_000) { key, failure -> failures += "$key: ${failure.javaClass.simpleName}" }
        return SettingsStore.open(SealedFile(layout.settings, DataLayout.SETTINGS_PURPOSE, countingCipher, layout.kept, "settings"), writer)
    }

    private fun plant(json: String) = layout.settings.writeBytes(cipher.seal(DataLayout.SETTINGS_PURPOSE, json.toByteArray()))

    private fun keptCopies(): List<File> = layout.kept.listFiles()?.toList().orEmpty()

    // --- The stored form ---

    @Test
    fun settingsComeBackAsTheyWereEncoded() {
        val settings = AppSettings(systemPrompts = mapOf("qwen3.5-0.8b" to "Answer briefly.\nIn English.", "boris-2-instruct" to "\"quoted\""))
        assertEquals(SettingsCodec.Decoded.Readable(settings, schemaVersion = 1), SettingsCodec.decode(SettingsCodec.encode(settings)))
        assertEquals(SettingsCodec.Decoded.Readable(AppSettings(), schemaVersion = 1), SettingsCodec.decode(SettingsCodec.encode(AppSettings())))
    }

    @Test
    fun fieldsThisVersionDoesNotKnowAreIgnored() {
        val decoded = SettingsCodec.decode(
            """{"schemaVersion":1,"later":true,"settings":{"systemPrompts":{"a":"x"},"searchToken":"secret","theme":{"dark":true}}}""",
        ) as SettingsCodec.Decoded.Readable
        assertEquals(AppSettings(mapOf("a" to "x")), decoded.settings)
        assertTrue(decoded.complete)
    }

    @Test
    fun whatCannotBeReadIsLeftOutAndCounted() {
        val decoded = SettingsCodec.decode(
            """{"schemaVersion":1,"settings":{"systemPrompts":{"a":"x","b":7,"c":null,"d":{"nested":1},"e":""}}}""",
        ) as SettingsCodec.Decoded.Readable
        assertEquals(AppSettings(mapOf("a" to "x")), decoded.settings)
        assertEquals("three values that are not text; an empty prompt is simply no prompt", 3, decoded.skippedEntries)
        assertFalse(decoded.complete)

        val wrongType = SettingsCodec.decode("""{"schemaVersion":1,"settings":{"systemPrompts":"all of them"}}""") as SettingsCodec.Decoded.Readable
        assertEquals(AppSettings(), wrongType.settings)
        assertFalse(wrongType.complete)
    }

    @Test
    fun settingsFromANewerVersionAreReadButNotTakenForComplete() {
        val decoded = SettingsCodec.decode("""{"schemaVersion":3,"settings":{"systemPrompts":{"a":"x"}}}""") as SettingsCodec.Decoded.Readable
        assertEquals(AppSettings(mapOf("a" to "x")), decoded.settings)
        assertFalse(decoded.complete)
    }

    @Test
    fun whatIsNotSettingsIsUnreadable() {
        for (text in listOf("", "not json", "[]", "{}", """{"schemaVersion":"1","settings":{}}""", """{"schemaVersion":0,"settings":{}}""", """{"schemaVersion":1}""", """{"schemaVersion":1,"settings":[]}""")) {
            assertSame(text, SettingsCodec.Decoded.Unreadable, SettingsCodec.decode(text))
        }
    }

    @Test
    fun aSystemPromptIsSetReplacedAndRemoved() {
        val one = AppSettings().withSystemPrompt("a", "first")
        assertEquals("first", one.systemPrompt("a"))
        assertEquals("", one.systemPrompt("b"))
        assertEquals("second", one.withSystemPrompt("a", "second").systemPrompt("a"))
        assertEquals(AppSettings(), one.withSystemPrompt("a", ""))
    }

    @Test
    fun settingsNeverPrintTheirPrompts() {
        val text = AppSettings(mapOf("qwen3.5-0.8b" to "My private instructions")).toString()
        assertFalse(text.contains("private"))
        assertTrue(text.contains("qwen3.5-0.8b"))
    }

    // --- The stored form: a temperature per model ---

    private fun decoded(json: String) = SettingsCodec.decode(json) as SettingsCodec.Decoded.Readable

    @Test
    fun withoutATemperatureOfItsOwnAModelSamplesAsTheCatalogueSays() {
        val settings = AppSettings()
        assertEquals(ModelOverrides(), settings.overrides("qwen3.5-0.8b"))
        assertNull(settings.overrides("qwen3.5-0.8b").temperature)
        for (model in ModelCatalog.all) assertSame(model.id, model.sampling, settings.sampling(model))
        assertFalse("what is not set is not written", SettingsCodec.encode(settings).contains("temperature"))
    }

    @Test
    fun aTemperatureReplacesTheTemperatureOfItsModelAndNothingElse() {
        val qwen = ModelCatalog.QWEN35_0_8B
        val boris = ModelCatalog.BORIS_2_INSTRUCT
        val settings = AppSettings().withTemperature(qwen.id, 0.3f)

        val sampling = settings.sampling(qwen)

        assertEquals(qwen.sampling.copy(temperature = 0.3f), sampling)
        assertEquals(0.3f, sampling.temperature)
        assertEquals(qwen.sampling.topK, sampling.topK)
        assertEquals(qwen.sampling.presencePenalty, sampling.presencePenalty)
        assertSame("another model keeps its own", boris.sampling, settings.sampling(boris))
        assertTrue("0 is the greedy path", settings.withTemperature(qwen.id, 0f).sampling(qwen).isGreedy)
    }

    @Test
    fun aTemperatureIsSetReplacedAndTakenBack() {
        val one = AppSettings(systemPrompts = mapOf("a" to "kept")).withTemperature("a", 0.7f)
        assertEquals(0.7f, one.overrides("a").temperature)
        assertEquals("kept", one.systemPrompt("a"))
        assertEquals(1.5f, one.withTemperature("a", 1.5f).overrides("a").temperature)
        assertEquals("a model with nothing set has no entry", AppSettings(systemPrompts = mapOf("a" to "kept")), one.withTemperature("a", null))
        assertEquals(one, one.withTemperature("b", null))
    }

    @Test
    fun theRangeIsZeroToTwoWithBothEnds() {
        assertEquals(0f, AppSettings().withTemperature("a", 0f).overrides("a").temperature)
        assertEquals(2f, AppSettings().withTemperature("a", 2f).overrides("a").temperature)
        for (outside in listOf(-0.01f, 2.01f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertFalse("$outside", ModelOverrides.isValidTemperature(outside))
            assertThrows("$outside", IllegalArgumentException::class.java) { AppSettings().withTemperature("a", outside) }
        }
    }

    @Test
    fun temperaturesComeBackAsTheyWereEncoded() {
        val settings = AppSettings(systemPrompts = mapOf("qwen3.5-0.8b" to "Answer briefly."))
            .withTemperature("qwen3.5-0.8b", 0.7f)
            .withTemperature("peacebell-v1-291m", 0f)
            .withTemperature("boris-2-instruct", 2f)
            .withTemperature("another", 1.25f)
        assertEquals(SettingsCodec.Decoded.Readable(settings, schemaVersion = 1), SettingsCodec.decode(SettingsCodec.encode(settings)))
    }

    @Test
    fun aFileFromBeforeThereWereTemperaturesReadsCompletely() {
        val old = decoded("""{"schemaVersion":1,"settings":{"systemPrompts":{"a":"x"}}}""")
        assertEquals(AppSettings(systemPrompts = mapOf("a" to "x")), old.settings)
        assertTrue(old.complete)
    }

    @Test
    fun theEndsOfTheRangeAreReadFromAFile() {
        val ends = decoded("""{"schemaVersion":1,"settings":{"models":{"a":{"temperature":0},"b":{"temperature":2},"c":{"temperature":0.0},"d":{"temperature":2.0}}}}""")
        assertEquals(listOf(0f, 2f, 0f, 2f), listOf("a", "b", "c", "d").map { ends.settings.overrides(it).temperature })
        assertTrue(ends.complete)
    }

    @Test
    fun aStoredTemperatureThatCannotBeUsedIsTreatedAsAbsentAndCounted() {
        for (junk in listOf("\"warm\"", "\"0.7\"", "true", "null", "{}", "[0.7]", "-0.1", "-1", "2.01", "3", "1e9")) {
            val read = decoded("""{"schemaVersion":1,"settings":{"models":{"a":{"temperature":$junk},"b":{"temperature":0.5}}}}""")
            assertEquals(junk, AppSettings().withTemperature("b", 0.5f), read.settings)
            assertNull(junk, read.settings.overrides("a").temperature)
            assertEquals(junk, 1, read.skippedEntries)
            assertFalse(junk, read.complete)
        }
    }

    @Test
    fun whatIsNotAModelsObjectIsLeftOutAndCounted() {
        val mixed = decoded("""{"schemaVersion":1,"settings":{"models":{"a":0.7,"b":"x","c":null,"d":{"temperature":1}}}}""")
        assertEquals(AppSettings().withTemperature("d", 1f), mixed.settings)
        assertEquals(3, mixed.skippedEntries)

        val wrongType = decoded("""{"schemaVersion":1,"settings":{"systemPrompts":{"a":"x"},"models":[{"temperature":1}]}}""")
        assertEquals(AppSettings(systemPrompts = mapOf("a" to "x")), wrongType.settings)
        assertFalse(wrongType.complete)
    }

    @Test
    fun whatAModelsObjectHoldsBesidesIsIgnored() {
        val later = decoded("""{"schemaVersion":1,"settings":{"models":{"a":{"temperature":0.7,"topP":0.9,"replyLength":256},"b":{"topK":40},"c":{}}}}""")
        assertEquals(AppSettings().withTemperature("a", 0.7f), later.settings)
        assertTrue(later.complete)
    }

    // --- The store ---

    @Test
    fun withoutAFileTheDefaultsAreUsedAndNothingIsWritten() = runTest {
        val store = open()
        assertEquals(AppSettings(), store.settings.value)
        assertNull(store.loadIssue.value)
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse(layout.settings.exists())
    }

    @Test
    fun aChangeIsStoredWithoutBeingAskedToAndComesBackEncrypted() = runTest {
        val store = open()
        store.update { it.withSystemPrompt("qwen3.5-0.8b", "Answer in one sentence.") }
        assertEquals("the change is there at once", "Answer in one sentence.", store.settings.value.systemPrompt("qwen3.5-0.8b"))
        runCurrent()
        assertFalse("while the user types, the file follows within the lazy interval", layout.settings.exists())

        advanceTimeBy(1_000)
        runCurrent()

        val stored = layout.settings.readBytes()
        assertTrue(Envelope.looksEncrypted(stored))
        assertFalse(String(stored, Charsets.ISO_8859_1).contains("sentence"))
        assertEquals(AppSettings(mapOf("qwen3.5-0.8b" to "Answer in one sentence.")), open().settings.value)
        assertEquals(emptyList<File>(), keptCopies())
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun typingWritesTheFileOncePerIntervalWithTheLatestText() = runTest {
        val store = open()
        for (text in listOf("A", "An", "Ans", "Answ")) {
            store.update { it.withSystemPrompt("m", text) }
            advanceTimeBy(100)
            runCurrent()
        }
        assertEquals(0, seals)

        advanceTimeBy(1_000)
        runCurrent()

        assertEquals("four changes, one write", 1, seals)
        assertEquals("Answ", open().settings.value.systemPrompt("m"))
    }

    @Test
    fun anUrgentChangeIsWrittenAtOnce() = runTest {
        val store = open()
        store.update(urgent = true) { it.withSystemPrompt("m", "now") }
        runCurrent()
        assertEquals("now", open().settings.value.systemPrompt("m"))
    }

    @Test
    fun aChangeThatChangesNothingWritesNothing() = runTest {
        val store = open()
        store.update(urgent = true) { it.withSystemPrompt("m", "") }
        runCurrent()
        assertFalse(layout.settings.exists())
    }

    @Test
    fun aFileThatCannotBeReadIsKeptAndTheDefaultsAreUsed() = runTest {
        val damaged = byteArrayOf(1, 2, 3, 4)
        layout.settings.writeBytes(damaged)

        val store = open()

        assertEquals(AppSettings(), store.settings.value)
        assertEquals(SettingsStore.LoadIssue.UNREADABLE, store.loadIssue.value)
        assertArrayEquals("opening replaces nothing", damaged, layout.settings.readBytes())
        assertArrayEquals("and a copy is put aside straight away", damaged, keptCopies().single().readBytes())
        assertTrue(keptCopies().single().name.startsWith("settings."))

        store.update(urgent = true) { it.withSystemPrompt("m", "new") }
        runCurrent()
        assertEquals("new", open().settings.value.systemPrompt("m"))
        assertArrayEquals(damaged, keptCopies().single().readBytes())

        store.loadIssueShown()
        assertNull(store.loadIssue.value)
    }

    @Test
    fun aFileThatDecryptsToSomethingElseIsTreatedTheSame() = runTest {
        plant("not settings at all")
        val original = layout.settings.readBytes()

        val store = open()

        assertEquals(SettingsStore.LoadIssue.UNREADABLE, store.loadIssue.value)
        assertArrayEquals(original, keptCopies().single().readBytes())
    }

    @Test
    fun aFileThatCanOnlyBeReadInPartIsUsedAsFarAsItGoesAndKept() = runTest {
        plant("""{"schemaVersion":1,"settings":{"systemPrompts":{"a":"kept","b":42}}}""")
        val original = layout.settings.readBytes()

        val store = open()

        assertEquals(AppSettings(mapOf("a" to "kept")), store.settings.value)
        assertEquals(SettingsStore.LoadIssue.PARTLY_READ, store.loadIssue.value)
        assertArrayEquals(original, keptCopies().single().readBytes())

        store.update(urgent = true) { it.withSystemPrompt("a", "changed") }
        runCurrent()
        val reopened = open()
        assertEquals("changed", reopened.settings.value.systemPrompt("a"))
        assertNull("what this version wrote, it reads completely", reopened.loadIssue.value)
        assertEquals(1, keptCopies().size)
    }

    @Test
    fun aTemperatureIsStoredEncryptedAndIsThereAtTheNextUnlock() = runTest {
        val store = open()
        store.update(urgent = true) { it.withSystemPrompt("qwen3.5-0.8b", "Be brief.").withTemperature("qwen3.5-0.8b", 0.35f) }
        runCurrent()

        assertFalse(String(layout.settings.readBytes(), Charsets.ISO_8859_1).contains("temperature"))
        val reopened = open()
        assertEquals(0.35f, reopened.settings.value.overrides("qwen3.5-0.8b").temperature)
        assertEquals("Be brief.", reopened.settings.value.systemPrompt("qwen3.5-0.8b"))
        assertNull(reopened.loadIssue.value)

        // Taking it back is stored as well.
        reopened.update(urgent = true) { it.withTemperature("qwen3.5-0.8b", null) }
        runCurrent()
        assertNull(open().settings.value.overrides("qwen3.5-0.8b").temperature)
        assertEquals(emptyList<File>(), keptCopies())
    }

    @Test
    fun aStoredTemperatureThatCannotBeUsedIsReportedLikeAnyFileReadInPart() = runTest {
        plant("""{"schemaVersion":1,"settings":{"systemPrompts":{"a":"kept"},"models":{"m":{"temperature":7},"n":{"temperature":1.5}}}}""")
        val original = layout.settings.readBytes()

        val store = open()

        assertNull("the model's own applies", store.settings.value.overrides("m").temperature)
        assertEquals(1.5f, store.settings.value.overrides("n").temperature)
        assertEquals("kept", store.settings.value.systemPrompt("a"))
        assertEquals(SettingsStore.LoadIssue.PARTLY_READ, store.loadIssue.value)
        assertArrayEquals(original, keptCopies().single().readBytes())
    }

    @Test
    fun whenNoCopyCanBeMadeTheUnreadableFileIsNotWrittenOver() = runTest {
        val damaged = byteArrayOf(1, 2, 3, 4)
        layout.settings.writeBytes(damaged)
        layout.kept.writeText("in the way")

        val store = open()
        assertEquals(SettingsStore.LoadIssue.UNREADABLE, store.loadIssue.value)
        store.update(urgent = true) { it.withSystemPrompt("m", "new") }
        runCurrent()

        assertArrayEquals(damaged, layout.settings.readBytes())
        assertEquals(listOf("settings: IOException"), failures)
    }
}
