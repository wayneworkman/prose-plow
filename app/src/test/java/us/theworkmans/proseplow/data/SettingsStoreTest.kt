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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import us.theworkmans.proseplow.DataLayout
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
