package us.theworkmans.proseplow.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import us.theworkmans.proseplow.security.KeyDestroyedException
import java.io.IOException

/**
 * The settings that are stored encrypted, which is all of them except the lock's own (those must
 * be readable while the app is locked and live in the lock configuration).
 *
 * A setting that is added later is a new property with a default, and a new field in
 * [SettingsCodec]; files written before it then still load. A secret, such as the access token
 * of a search service, needs nothing more than that: the whole file is sealed. It must only be
 * left out of [toString], as the prompts are.
 *
 * @property systemPrompts the system prompt of each model that has one, by model id. A model
 *   without an entry has none.
 */
data class AppSettings(val systemPrompts: Map<String, String> = emptyMap()) {

    /** The system prompt set for [modelId], or an empty string. */
    fun systemPrompt(modelId: String): String = systemPrompts[modelId].orEmpty()

    /** These settings with [text] as the system prompt of [modelId]; an empty text removes it. */
    fun withSystemPrompt(modelId: String, text: String): AppSettings =
        copy(systemPrompts = if (text.isEmpty()) systemPrompts - modelId else systemPrompts + (modelId to text))

    /** Says which models have a prompt and never what it is: prompts must not end up in a log. */
    override fun toString(): String = "AppSettings(systemPrompts for ${systemPrompts.keys})"
}

/**
 * The stored form of [AppSettings]: JSON with a schema version next to the settings.
 *
 * ```
 * {"schemaVersion": 1, "settings": {"systemPrompts": {"<model id>": "<text>", ...}}}
 * ```
 *
 * Reading is forgiving in the same way as for sessions: fields this version does not know are
 * ignored, a field of the wrong type takes its default, and a system prompt that is not text is
 * left out and counted. [decode] reports whether everything was read, so that the store can keep
 * a copy of the file before it writes over it.
 */
object SettingsCodec {

    /** The version [encode] writes. */
    const val SCHEMA_VERSION: Int = 1

    /** What [decode] made of stored settings. */
    sealed interface Decoded {

        /**
         * @property schemaVersion the version found in the stored text.
         * @property skippedEntries how many stored values were left out because they could not
         *   be read.
         */
        data class Readable(val settings: AppSettings, val schemaVersion: Int, val skippedEntries: Int = 0) : Decoded {
            /** True when [settings] is everything that was stored. */
            val complete: Boolean get() = skippedEntries == 0 && schemaVersion <= SCHEMA_VERSION
        }

        /** The text is not stored settings. */
        data object Unreadable : Decoded
    }

    fun encode(settings: AppSettings): String = buildJsonObject {
        put("schemaVersion", SCHEMA_VERSION)
        putJsonObject("settings") {
            putJsonObject("systemPrompts") {
                for ((modelId, text) in settings.systemPrompts) put(modelId, text)
            }
        }
    }.toString()

    fun decode(text: String): Decoded {
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: IllegalArgumentException) {
            // A SerializationException is one.
            null
        } ?: return Decoded.Unreadable
        val version = (root["schemaVersion"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: return Decoded.Unreadable
        if (version < 1) return Decoded.Unreadable
        val stored = root["settings"] as? JsonObject ?: return Decoded.Unreadable

        var skipped = 0
        val prompts = LinkedHashMap<String, String>()
        when (val storedPrompts = stored["systemPrompts"]) {
            null -> Unit
            is JsonObject -> for ((modelId, value) in storedPrompts) {
                val prompt = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (prompt == null) skipped++ else if (prompt.isNotEmpty()) prompts[modelId] = prompt
            }
            else -> skipped++
        }
        return Decoded.Readable(AppSettings(systemPrompts = prompts), version, skipped)
    }
}

/**
 * The settings of an unlocked session: read once when the session opens, kept in memory, and
 * written back, encrypted, whenever they change. There is no Save: every change is stored.
 *
 * A settings file that cannot be read, or only in part, never stops the app: it starts with what
 * could be read (or the defaults), says so in [loadIssue], and the file as it was is kept as a
 * copy before the first change is written over it.
 */
class SettingsStore private constructor(
    private val file: SealedFile,
    private val writer: CoalescingWriter,
    initial: AppSettings,
    issue: LoadIssue?,
) {
    /** What was wrong with the stored settings when they were loaded. */
    enum class LoadIssue {
        /** The file could not be read at all; the defaults are in use. */
        UNREADABLE,

        /** Some of the file could not be read, or a newer version of the app wrote it. */
        PARTLY_READ,
    }

    private val _settings = MutableStateFlow(initial)
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _loadIssue = MutableStateFlow(issue)

    /** Set when the stored settings could not be read completely, until the user has been told. */
    val loadIssue: StateFlow<LoadIssue?> = _loadIssue.asStateFlow()

    fun loadIssueShown() {
        _loadIssue.value = null
    }

    /**
     * Changes the settings and has them written. May be called from any thread; changes are
     * applied, and written, in the order of the calls.
     *
     * @param urgent false while the user is typing: the file then follows within the writer's
     *   lazy interval instead of being written for every character.
     */
    @Synchronized
    fun update(urgent: Boolean = false, transform: (AppSettings) -> AppSettings) {
        val current = _settings.value
        val updated = transform(current)
        if (updated == current) return
        _settings.value = updated
        writer.submit(WRITER_KEY, urgent) { file.write(SettingsCodec.encode(updated).toByteArray(Charsets.UTF_8)) }
    }

    companion object {
        /** The key of the settings file in the [CoalescingWriter]. */
        const val WRITER_KEY: String = "settings"

        /**
         * Reads the stored settings. Blocking; called when a session is opened.
         *
         * @throws IOException when the file is there but could not be read from the disk.
         */
        @Throws(IOException::class)
        fun open(file: SealedFile, writer: CoalescingWriter): SettingsStore {
            val decoded = when (val read = file.read()) {
                SealedRead.Missing -> return SettingsStore(file, writer, AppSettings(), issue = null)
                SealedRead.Undecryptable -> SettingsCodec.Decoded.Unreadable
                is SealedRead.Opened -> SettingsCodec.decode(read.plaintext.toString(Charsets.UTF_8))
            }
            if (decoded is SettingsCodec.Decoded.Readable && decoded.complete) {
                file.contentUnderstood()
                return SettingsStore(file, writer, decoded.settings, issue = null)
            }
            try {
                file.keepCopy()
            } catch (e: KeyDestroyedException) {
                throw e
            } catch (_: IOException) {
                // No room for the copy now. The file is still not understood, so the first write
                // tries again and fails rather than replace it.
            }
            return when (decoded) {
                is SettingsCodec.Decoded.Readable -> SettingsStore(file, writer, decoded.settings, LoadIssue.PARTLY_READ)
                SettingsCodec.Decoded.Unreadable -> SettingsStore(file, writer, AppSettings(), LoadIssue.UNREADABLE)
            }
        }
    }
}
