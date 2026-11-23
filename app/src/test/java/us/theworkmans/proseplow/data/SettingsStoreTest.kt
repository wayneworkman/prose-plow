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

    private fun TestScope.open(knownKeepModelMemory: Boolean? = null): SettingsStore {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val writer = CoalescingWriter(scope, lazyMillis = 1_000) { key, failure -> failures += "$key: ${failure.javaClass.simpleName}" }
        return SettingsStore.open(
            SealedFile(layout.settings, DataLayout.SETTINGS_PURPOSE, countingCipher, layout.kept, "settings"), writer,
            knownKeepModelMemory = knownKeepModelMemory,
        )
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

    /**
     * The switch for the model's memory: on unless stored off, written only when off, and read
     * as off when what is stored is not a boolean. Catches a default other than on, a file of an
     * older version read as off, and a value that cannot be read switching saving back on.
     */
    @Test
    fun keepingTheModelsMemoryIsOnUnlessStoredOff() {
        assertTrue(AppSettings().keepModelMemory)
        assertFalse(SettingsCodec.encode(AppSettings()).contains("keepModelMemory"))
        val off = AppSettings(keepModelMemory = false)
        assertTrue(SettingsCodec.encode(off).contains("\"keepModelMemory\":false"))
        assertEquals(SettingsCodec.Decoded.Readable(off, schemaVersion = 1), SettingsCodec.decode(SettingsCodec.encode(off)))

        val older = SettingsCodec.decode("""{"schemaVersion":1,"settings":{"systemPrompts":{}}}""") as SettingsCodec.Decoded.Readable
        assertTrue("a file from before the switch", older.settings.keepModelMemory)
        val unreadable = SettingsCodec.decode("""{"schemaVersion":1,"settings":{"keepModelMemory":"yes"}}""") as SettingsCodec.Decoded.Readable
        assertFalse(unreadable.settings.keepModelMemory)
        assertEquals(1, unreadable.skippedEntries)
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

    // --- The stored form: the size chosen for the context, per model ---

    @Test
    fun aChosenContextSizeIsStoredBesideTheOthersAndOnlyWhenItIsSet() {
        val qwen = ModelCatalog.QWEN35_0_8B
        val fresh = AppSettings()
        assertNull("automatic", fresh.contextTokens(qwen.id))
        assertFalse(SettingsCodec.encode(fresh).contains("contextTokens"))

        val set = fresh.withContextTokens(qwen.id, 8192).withMaxNewTokens(qwen.id, 300)
        assertEquals(8192, set.contextTokens(qwen.id))
        assertEquals(ModelOverrides(maxNewTokens = 300, contextTokens = 8192), set.overrides(qwen.id))
        assertFalse(set.overrides(qwen.id).isDefault)
        val encoded = SettingsCodec.encode(set)
        assertTrue(encoded, encoded.contains("\"contextTokens\":8192"))
        val decoded = SettingsCodec.decode(encoded) as SettingsCodec.Decoded.Readable
        assertTrue(decoded.complete)
        assertEquals(set, decoded.settings)
        assertEquals("the schema version did not change for it", 1, decoded.schemaVersion)

        // Back to automatic: nothing is left to store for it, and a model with nothing set has no entry.
        val reset = set.withContextTokens(qwen.id, null)
        assertEquals(ModelOverrides(maxNewTokens = 300), reset.overrides(qwen.id))
        assertEquals(emptyMap<String, ModelOverrides>(), reset.withMaxNewTokens(qwen.id, null).modelOverrides)
        assertNull("another model is not touched", set.contextTokens(ModelCatalog.BORIS_2_INSTRUCT.id))
        assertEquals(mapOf(qwen.id to ModelOverrides(contextTokens = 8192)), fresh.withContextTokens(qwen.id, 8192).modelOverrides)
    }

    /** Read tolerantly: a value that is not a whole number, or smaller than any context, means automatic. */
    @Test
    fun aStoredContextSizeThatCannotBeOneMeansAutomatic() {
        val qwen = ModelCatalog.QWEN35_0_8B
        fun stored(value: String) = SettingsCodec.decode(
            """{"schemaVersion":1,"settings":{"models":{"${qwen.id}":{"temperature":0.5,"contextTokens":$value}}}}""",
        ) as SettingsCodec.Decoded.Readable

        val good = stored("32768")
        assertTrue(good.complete)
        assertEquals(32_768, good.settings.contextTokens(qwen.id))
        assertEquals("the smallest there is", 1024, stored("1024").settings.contextTokens(qwen.id))
        // Larger than any model's: kept as it is, and held to the model and the phone where it is used.
        assertEquals(1_000_000, stored("1000000").settings.contextTokens(qwen.id))

        for (bad in listOf("8192.5", "\"8192\"", "true", "null", "[8192]", "8e3", "1023", "0", "-8192", "99999999999")) {
            val read = stored(bad)
            assertEquals(bad, 1, read.skippedEntries)
            assertNull(bad, read.settings.contextTokens(qwen.id))
            assertEquals("$bad: what else was set for the model is kept", 0.5f, read.settings.overrides(qwen.id).temperature)
        }

        assertThrows(IllegalArgumentException::class.java) { ModelOverrides(contextTokens = 1023) }
        assertThrows(IllegalArgumentException::class.java) { AppSettings().withContextTokens(qwen.id, 0) }
    }

    /** The limits of the longest reply follow from how long the context may become, and never pass 4,096. */
    @Test
    fun theLongestReplyIsHeldToTheLimitOfTheContext() {
        val qwen = ModelCatalog.QWEN35_0_8B
        val set = AppSettings().withMaxNewTokens(qwen.id, 3000)
        assertEquals(3000, set.maxNewTokens(qwen, 10_240))
        assertEquals(3000, set.maxNewTokens(qwen, 262_144))
        assertEquals("more than half of 4,096: the model's own, not the nearest limit", 1024, set.maxNewTokens(qwen, 4096))
        assertEquals(4096, AppSettings().withMaxNewTokens(qwen.id, 4096).maxNewTokens(qwen, 8192))
        assertEquals("no reply is longer than 4,096, whatever the context", 1024, AppSettings().withMaxNewTokens(qwen.id, 4097).maxNewTokens(qwen, 262_144))
        assertEquals("the model's own is held to the limits as well", 512, AppSettings().maxNewTokens(qwen, 1024))
        assertEquals(1024, AppSettings().maxNewTokens(qwen, 4096))
    }

    // --- The stored form: the longest reply per model ---

    @Test
    fun aLongestReplyIsStoredBesideTheTemperatureAndOnlyWhenItIsSet() {
        val qwen = ModelCatalog.QWEN35_0_8B
        val fresh = AppSettings()
        assertEquals("the model's own", 1024, fresh.maxNewTokens(qwen, 4096))
        assertFalse(SettingsCodec.encode(fresh).contains("maxNewTokens"))

        val set = fresh.withMaxNewTokens(qwen.id, 300).withTemperature(qwen.id, 0.5f)
        assertEquals(300, set.maxNewTokens(qwen, 4096))
        assertEquals(ModelOverrides(temperature = 0.5f, maxNewTokens = 300), set.overrides(qwen.id))
        val encoded = SettingsCodec.encode(set)
        assertTrue(encoded, encoded.contains("\"maxNewTokens\":300"))
        val decoded = SettingsCodec.decode(encoded) as SettingsCodec.Decoded.Readable
        assertTrue(decoded.complete)
        assertEquals(set, decoded.settings)
        assertEquals("the schema version did not change for it", 1, decoded.schemaVersion)

        // Back to the model's own: nothing is left to store for it, and a model with nothing set has no entry.
        val reset = set.withMaxNewTokens(qwen.id, null)
        assertEquals(ModelOverrides(temperature = 0.5f), reset.overrides(qwen.id))
        assertEquals(1024, reset.maxNewTokens(qwen, 4096))
        assertEquals(emptyMap<String, ModelOverrides>(), reset.withTemperature(qwen.id, null).modelOverrides)
        assertEquals("another model is not touched", 512, set.maxNewTokens(ModelCatalog.BORIS_2_INSTRUCT, 2048))
    }

    /**
     * Read tolerantly, and never moved to the nearest limit: a value that is missing, not a whole
     * number, or outside what the model accepts means the model's own longest reply.
     */
    @Test
    fun aStoredLongestReplyThatCannotBeUsedMeansTheModelsOwn() {
        val qwen = ModelCatalog.QWEN35_0_8B
        val boris = ModelCatalog.BORIS_2_INSTRUCT
        fun stored(value: String) = SettingsCodec.decode(
            """{"schemaVersion":1,"settings":{"models":{"${qwen.id}":{"temperature":0.5,"maxNewTokens":$value},"${boris.id}":{"maxNewTokens":$value}}}}""",
        ) as SettingsCodec.Decoded.Readable

        // A whole number both models accept.
        val good = stored("700")
        assertTrue(good.complete)
        assertEquals(700, good.settings.maxNewTokens(qwen, 4096))
        assertEquals(700, good.settings.maxNewTokens(boris, 2048))

        // Not a whole number, or shorter than any model's shortest reply: left out and counted, for both models.
        for (bad in listOf("512.5", "\"512\"", "true", "null", "[512]", "5e2", "15", "0", "-512", "99999999999")) {
            val read = stored(bad)
            assertEquals(bad, 2, read.skippedEntries)
            assertEquals(bad, 1024, read.settings.maxNewTokens(qwen, 4096))
            assertEquals(bad, 512, read.settings.maxNewTokens(boris, 2048))
            assertEquals("$bad: what else was set for the model is kept", 0.5f, read.settings.overrides(qwen.id).temperature)
        }

        // A whole number that only the model with the larger context accepts: the other uses its
        // own, not its largest. The file itself is as the app could have written it.
        val large = stored("2000")
        assertTrue(large.complete)
        assertEquals(2000, large.settings.maxNewTokens(qwen, 4096))
        assertEquals("not clamped to 1,024", 512, large.settings.maxNewTokens(boris, 2048))
        val huge = stored("100000")
        assertEquals(1024, huge.settings.maxNewTokens(qwen, 4096))
        assertEquals(512, huge.settings.maxNewTokens(boris, 2048))

        // And the value itself cannot be made with a length no model could have.
        assertThrows(IllegalArgumentException::class.java) { ModelOverrides(maxNewTokens = 15) }
        assertThrows(IllegalArgumentException::class.java) { AppSettings().withMaxNewTokens(qwen.id, 0) }
    }

    // --- The stored form: whether the notification permission was asked for ---

    @Test
    fun thatTheUserWasAskedAboutNotificationsIsStoredOnceItIsTrue() {
        val fresh = AppSettings()
        assertFalse(fresh.notificationsAsked)
        assertFalse("not written until it is true: an older file means the same", SettingsCodec.encode(fresh).contains("notificationsAsked"))

        val asked = fresh.withNotificationsAsked()
        assertTrue(asked.notificationsAsked)
        assertSame("asked is asked", asked, asked.withNotificationsAsked())
        assertTrue(SettingsCodec.encode(asked).contains("\"notificationsAsked\":true"))
        val decoded = SettingsCodec.decode(SettingsCodec.encode(asked.withSystemPrompt("a", "x"))) as SettingsCodec.Decoded.Readable
        assertTrue(decoded.complete)
        assertEquals(AppSettings(systemPrompts = mapOf("a" to "x"), notificationsAsked = true), decoded.settings)

        // A file written before the field existed, and one with something else in its place.
        val older = SettingsCodec.decode("""{"schemaVersion":1,"settings":{"systemPrompts":{}}}""") as SettingsCodec.Decoded.Readable
        assertFalse(older.settings.notificationsAsked)
        assertTrue(older.complete)
        val odd = SettingsCodec.decode("""{"schemaVersion":1,"settings":{"notificationsAsked":"yes"}}""") as SettingsCodec.Decoded.Readable
        assertFalse(odd.settings.notificationsAsked)
        assertEquals("counted as something that could not be read", 1, odd.skippedEntries)
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

    /**
     * The switch that keeps the model's memory never falls back to on when the settings cannot
     * say what it is: a file that is missing, one that cannot be decrypted, and a value that is
     * not a boolean all take the readable copy the lock keeps. A file that says it plainly is
     * believed over the copy. Catches a switch that fails open.
     */
    @Test
    fun theMemorySwitchTakesTheLocksCopyWheneverTheSettingsCannotSay() = runTest {
        assertFalse("no file", open(knownKeepModelMemory = false).settings.value.keepModelMemory)
        assertTrue("no file, a copy that says on", open(knownKeepModelMemory = true).settings.value.keepModelMemory)

        layout.settings.writeBytes(byteArrayOf(1, 2, 3, 4))
        assertFalse("a file that cannot be decrypted", open(knownKeepModelMemory = false).settings.value.keepModelMemory)
        layout.settings.delete()
        layout.kept.deleteRecursively()

        plant("""{"schemaVersion":1,"settings":{"keepModelMemory":"yes"}}""")
        assertFalse("a value that is not a boolean, a copy that says off", open(knownKeepModelMemory = false).settings.value.keepModelMemory)
        assertTrue("a value that is not a boolean, a copy that says on", open(knownKeepModelMemory = true).settings.value.keepModelMemory)
        assertFalse("a value that is not a boolean and no copy", open().settings.value.keepModelMemory)

        plant("""{"schemaVersion":1,"settings":{"keepModelMemory":false}}""")
        assertFalse("a file that says off", open(knownKeepModelMemory = true).settings.value.keepModelMemory)
        plant("""{"schemaVersion":1,"settings":{}}""")
        assertTrue("a file without the field means on, whatever the copy", open(knownKeepModelMemory = false).settings.value.keepModelMemory)
    }

    /** Catches a change of a system prompt (or its removal) that the session is not told of, and a change of anything else that it is. */
    @Test
    fun everyChangeOfASystemPromptIsToldAndNothingElse() = runTest {
        val store = open()
        val told = ArrayList<String>()
        store.whenSystemPromptChanges { told += it }
        store.update { it.withSystemPrompt("a", "One") }
        store.update { it.withSystemPrompt("a", "One") }
        store.update { it.withSystemPrompt("a", "Two").withSystemPrompt("b", "Other") }
        store.update { it.withTemperature("a", 0.5f) }
        store.update { it.withSystemPrompt("a", "") }
        assertEquals(listOf("a", "a", "b", "a"), told)
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
