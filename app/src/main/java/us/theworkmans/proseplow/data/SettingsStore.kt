package us.theworkmans.proseplow.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import us.theworkmans.proseplow.core.model.ModelSpec
import us.theworkmans.proseplow.core.model.ReplyLength
import us.theworkmans.proseplow.core.model.SamplingParams
import us.theworkmans.proseplow.security.KeyDestroyedException
import java.io.IOException

/**
 * What the user has set for one model in place of the model's own values. Every property is
 * optional, and null means "as the catalogue has it": a model whose defaults improve in a later
 * build then improves for everyone who never changed them.
 *
 * Another value the user may set per model (top-p, top-k, a repetition penalty, the length of a
 * reply) is another nullable property here, another line in [applyTo] if it is a sampling value,
 * and another field of the model's object in [SettingsCodec]. Nothing else changes shape.
 *
 * @property temperature the sampling temperature, from [MIN_TEMPERATURE] to [MAX_TEMPERATURE].
 *   0 always takes the most likely token; higher values make the choice more varied.
 * @property contextTokens the size the user chose as the most the model's context may grow to,
 *   in tokens; null is automatic. What a model on a device can be given is not known here:
 *   the choice is held to it where it is used.
 * @property maxNewTokens the longest reply, in tokens. What a model accepts depends on the
 *   model (see [ReplyLength]), which this class does not know: it only holds a value that could
 *   be some model's, and [AppSettings.maxNewTokens] is where a model's own limits are applied.
 */
data class ModelOverrides(val temperature: Float? = null, val maxNewTokens: Int? = null, val contextTokens: Int? = null) {
    init {
        require(contextTokens == null || isPossibleContext(contextTokens)) { "A context has at least $MIN_CONTEXT_TOKENS tokens" }
        require(temperature == null || isValidTemperature(temperature)) { "A temperature is a number from $MIN_TEMPERATURE to $MAX_TEMPERATURE" }
        require(maxNewTokens == null || isPossibleReplyLength(maxNewTokens)) { "A reply is at least ${ReplyLength.MIN_TOKENS} tokens long" }
    }

    /** True when nothing is set, so that there is nothing to store for the model. */
    val isDefault: Boolean get() = temperature == null && maxNewTokens == null && contextTokens == null

    /**
     * The sampling a reply is generated with: [defaults], the model's own from the catalogue,
     * with only what the user set replaced.
     */
    fun applyTo(defaults: SamplingParams): SamplingParams = if (temperature == null) defaults else defaults.copy(temperature = temperature)

    companion object {
        const val MIN_TEMPERATURE: Float = 0f
        const val MAX_TEMPERATURE: Float = 2f

        fun isValidTemperature(value: Float): Boolean = value.isFinite() && value >= MIN_TEMPERATURE && value <= MAX_TEMPERATURE

        /** No context is smaller than this, whatever the model. */
        const val MIN_CONTEXT_TOKENS: Int = 1024

        /** True when [tokens] could be a context size of some model. */
        fun isPossibleContext(tokens: Int): Boolean = tokens >= MIN_CONTEXT_TOKENS

        /** True when [tokens] could be the longest reply of some model: not below the least any model accepts. */
        fun isPossibleReplyLength(tokens: Int): Boolean = tokens >= ReplyLength.MIN_TOKENS
    }
}

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
 * @property modelOverrides what the user set in place of a model's own values, by model id. Only
 *   models with something set have an entry.
 * @property notificationsAsked the user has been asked once whether the app may post
 *   notifications, and is not asked again.
 */
data class AppSettings(
    val systemPrompts: Map<String, String> = emptyMap(),
    val modelOverrides: Map<String, ModelOverrides> = emptyMap(),
    val notificationsAsked: Boolean = false,
) {

    /** These settings once the user has been asked whether the app may post notifications. */
    fun withNotificationsAsked(): AppSettings = if (notificationsAsked) this else copy(notificationsAsked = true)

    /** The system prompt set for [modelId], or an empty string. */
    fun systemPrompt(modelId: String): String = systemPrompts[modelId].orEmpty()

    /** These settings with [text] as the system prompt of [modelId]; an empty text removes it. */
    fun withSystemPrompt(modelId: String, text: String): AppSettings =
        copy(systemPrompts = if (text.isEmpty()) systemPrompts - modelId else systemPrompts + (modelId to text))

    /** What the user set for [modelId]; nothing, for a model without an entry. */
    fun overrides(modelId: String): ModelOverrides = modelOverrides[modelId] ?: ModelOverrides()

    /**
     * These settings with [temperature] as the temperature of [modelId]; null goes back to the
     * model's own. A model for which nothing is set any more loses its entry.
     */
    fun withTemperature(modelId: String, temperature: Float?): AppSettings {
        val updated = overrides(modelId).copy(temperature = temperature)
        return copy(modelOverrides = if (updated.isDefault) modelOverrides - modelId else modelOverrides + (modelId to updated))
    }

    /**
     * The sampling a reply from [model] is generated with now: the catalogue's values for that
     * model, with what the user set for it in their place.
     */
    fun sampling(model: ModelSpec): SamplingParams = overrides(model.id).applyTo(model.sampling)

    /**
     * These settings with [tokens] as the longest reply of [modelId]; null goes back to the
     * model's own. A model for which nothing is set any more loses its entry.
     */
    fun withMaxNewTokens(modelId: String, tokens: Int?): AppSettings {
        val updated = overrides(modelId).copy(maxNewTokens = tokens)
        return copy(modelOverrides = if (updated.isDefault) modelOverrides - modelId else modelOverrides + (modelId to updated))
    }

    /**
     * The longest reply [model] writes now, in tokens, when its context may become
     * [contextLimit] tokens long on this device under the user's choice of size: what the user
     * set if that lies within the limits that follow from it (see [ReplyLength.range]), and the
     * catalogue's value otherwise. A stored value outside the limits is not moved to the
     * nearest limit: it is a value nobody chose for this model as it is now (how long its
     * context may become depends on the build, the device and the size chosen, all of which
     * can change), so the model's own applies, itself held to the limits.
     */
    fun maxNewTokens(model: ModelSpec, contextLimit: Int): Int {
        val range = ReplyLength.range(contextLimit)
        return overrides(model.id).maxNewTokens?.takeIf { it in range } ?: model.defaultMaxNewTokens.coerceAtMost(range.last.coerceAtLeast(1))
    }

    /** The size the user chose for the context of [modelId], or null for automatic. */
    fun contextTokens(modelId: String): Int? = overrides(modelId).contextTokens

    /** These settings with [tokens] as the chosen context size of [modelId]; null is automatic. */
    fun withContextTokens(modelId: String, tokens: Int?): AppSettings {
        val updated = overrides(modelId).copy(contextTokens = tokens)
        return copy(modelOverrides = if (updated.isDefault) modelOverrides - modelId else modelOverrides + (modelId to updated))
    }

    /** Says which models have a prompt and never what it is: prompts must not end up in a log. */
    override fun toString(): String =
        "AppSettings(systemPrompts for ${systemPrompts.keys}, modelOverrides=$modelOverrides, notificationsAsked=$notificationsAsked)"
}

/**
 * The stored form of [AppSettings]: JSON with a schema version next to the settings.
 *
 * ```
 * {"schemaVersion": 1, "settings": {
 *     "systemPrompts": {"<model id>": "<text>", ...},
 *     "models": {"<model id>": {"temperature": 0.7, "maxNewTokens": 512, "contextTokens": 8192}, ...},
 *     "notificationsAsked": true}}
 * ```
 *
 * `notificationsAsked` is only written once it is true, and was added, like `models`, without a
 * new schema version: a file without it means what it always meant.
 *
 * `models` holds what the user set per model ([ModelOverrides]); a value that is not set is not
 * written. It was added without a new schema version, because a file without it means exactly
 * what it always meant: nothing set.
 *
 * Reading is forgiving in the same way as for sessions: fields this version does not know are
 * ignored, in the settings and in every model's object; a field of the wrong type takes its
 * default; and a value that cannot be used (a system prompt that is not text, a temperature that
 * is not a number or lies outside its range, a longest reply that is not a whole number or is
 * shorter than any model's shortest) is left out, which for these means the model's own, and
 * counted. [decode] reports whether everything was read, so that the store can
 * keep a copy of the file before it writes over it.
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
            putJsonObject("models") {
                for ((modelId, overrides) in settings.modelOverrides) {
                    putJsonObject(modelId) {
                        overrides.temperature?.let { put("temperature", it) }
                        overrides.maxNewTokens?.let { put("maxNewTokens", it) }
                        overrides.contextTokens?.let { put("contextTokens", it) }
                    }
                }
            }
            if (settings.notificationsAsked) put("notificationsAsked", true)
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

        val overrides = LinkedHashMap<String, ModelOverrides>()
        when (val storedModels = stored["models"]) {
            null -> Unit
            is JsonObject -> for ((modelId, value) in storedModels) {
                val model = value as? JsonObject
                if (model == null) {
                    skipped++
                    continue
                }
                var temperature: Float? = null
                val storedTemperature = model["temperature"]
                if (storedTemperature != null) {
                    temperature = (storedTemperature as? JsonPrimitive)?.takeUnless { it.isString }?.floatOrNull
                        ?.takeIf(ModelOverrides::isValidTemperature)
                    if (temperature == null) skipped++
                }
                var maxNewTokens: Int? = null
                val storedMaxNewTokens = model["maxNewTokens"]
                if (storedMaxNewTokens != null) {
                    // A whole number written as one: 512.0, "512" and 5e2 are not what this app writes.
                    maxNewTokens = (storedMaxNewTokens as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toIntOrNull()
                        ?.takeIf(ModelOverrides::isPossibleReplyLength)
                    if (maxNewTokens == null) skipped++
                }
                var contextTokens: Int? = null
                val storedContext = model["contextTokens"]
                if (storedContext != null) {
                    contextTokens = (storedContext as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toIntOrNull()
                        ?.takeIf(ModelOverrides::isPossibleContext)
                    if (contextTokens == null) skipped++
                }
                val read = ModelOverrides(temperature = temperature, maxNewTokens = maxNewTokens, contextTokens = contextTokens)
                if (!read.isDefault) overrides[modelId] = read
            }
            else -> skipped++
        }
        var asked = false
        val storedAsked = stored["notificationsAsked"]
        if (storedAsked != null) {
            val value = (storedAsked as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            if (value == null) skipped++ else asked = value
        }
        return Decoded.Readable(AppSettings(systemPrompts = prompts, modelOverrides = overrides, notificationsAsked = asked), version, skipped)
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
