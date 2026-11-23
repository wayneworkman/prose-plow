package us.theworkmans.proseplow.settings

import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow
import us.theworkmans.proseplow.core.engine.DeviceContextLimits
import us.theworkmans.proseplow.core.model.ModelSpec
import us.theworkmans.proseplow.core.model.ReplyLength
import us.theworkmans.proseplow.data.AppSettings
import us.theworkmans.proseplow.data.SettingsStore
import java.text.DecimalFormatSymbols
import java.text.NumberFormat

/**
 * The Settings screen's ViewModel, for the settings that are stored encrypted (the lock's own
 * settings have their ViewModel in the lock package).
 *
 * Settings are saved as they are edited: every change goes to the [SettingsStore] at once, which
 * writes it in the background, and there is nothing to save or discard when the screen is left.
 * The text of each field is Compose state that is written synchronously as the user types, so no
 * character is lost to a round trip through a flow; like everything else here it is never put
 * into saved instance state.
 *
 * A field that holds a number keeps its text exactly as typed, valid or not. Only a valid entry
 * is stored; while the text is not valid, the last valid value stays stored and in use, and the
 * screen says so.
 *
 * The Settings page and the page of each model have a ViewModel of this class each, over the
 * same store. What a page shows of the stored values comes from the store ([stored]), so the
 * Settings page says what a model's page has just changed; what is kept here is only the text
 * of the fields that are open.
 *
 * @param models the models this build offers; each gets a row in Settings and a page of its
 *   own, with its system prompt (or the reason why it has none), its temperature, its longest
 *   reply and how far its context may grow.
 * @param decimalSeparator the decimal mark of the user's locale, asked for whenever a number is
 *   read or shown, so that a change of language while the screen is open is followed.
 * @param wholeNumber a whole number as the user's locale writes it in a sentence (1,024 or
 *   1.024); asked for whenever one is shown, for the same reason.
 * @param contextLimits what this device allows a model's context: the sizes to choose from,
 *   what each costs, and with them the limits of the longest reply. A ViewModel that is given
 *   none assumes a device with plenty of memory.
 */
class SettingsViewModel(
    private val settings: SettingsStore,
    val models: List<ModelSpec>,
    private val decimalSeparator: () -> Char = { DecimalFormatSymbols.getInstance().decimalSeparator },
    private val wholeNumber: (Int) -> String = { NumberFormat.getIntegerInstance().format(it.toLong()) },
    private val contextLimits: DeviceContextLimits = DeviceContextLimits(),
) : ViewModel() {

    /** The settings as they are stored now. The rows of the Settings page are made of these. */
    val stored: StateFlow<AppSettings> = settings.settings

    private val prompts = mutableStateMapOf<String, String>().apply { putAll(settings.settings.value.systemPrompts) }

    /** The text of each temperature field that is not empty, by model id. */
    private val temperatures = mutableStateMapOf<String, String>().apply {
        for ((modelId, overrides) in settings.settings.value.modelOverrides) {
            overrides.temperature?.let { put(modelId, TemperatureInput.format(it, decimalSeparator())) }
        }
    }

    /** The size chosen for the context of each model that has one, by model id; a model that is not here is left to itself. */
    private val contexts = mutableStateMapOf<String, Int>().apply {
        for ((modelId, overrides) in settings.settings.value.modelOverrides) overrides.contextTokens?.let { put(modelId, it) }
    }

    /**
     * The text of each longest-reply field that is not empty, by model id. A stored value that
     * lies outside the model's limits is not in use (the model's own is) and is not shown either.
     */
    private val replyLengths = mutableStateMapOf<String, String>().apply {
        for (model in models) {
            val own = settings.settings.value.overrides(model.id).maxNewTokens
            if (own != null && own in replyLengthRange(model)) put(model.id, ReplyLengthInput.format(own))
        }
    }

    /** Set when the stored settings could not be read completely, until the user has been told. */
    val loadIssue: StateFlow<SettingsStore.LoadIssue?> = settings.loadIssue

    /** The text in the system prompt field of [modelId]. */
    fun systemPrompt(modelId: String): String = prompts[modelId].orEmpty()

    /** The field of [modelId] was edited; ignored for a model that takes no system prompt. */
    fun onSystemPromptChange(modelId: String, text: String) {
        if (models.none { it.id == modelId && it.supportsSystemPrompt }) return
        prompts[modelId] = text
        settings.update { it.withSystemPrompt(modelId, text) }
    }

    /** The text in the temperature field of [modelId]; empty when the model's own applies. */
    fun temperature(modelId: String): String = temperatures[modelId].orEmpty()

    /** True when the temperature field of [modelId] holds something that is not a temperature. */
    fun temperatureInvalid(modelId: String): Boolean =
        TemperatureInput.parse(temperature(modelId), decimalSeparator()) == TemperatureInput.Parsed.Invalid

    /** The temperature [model] has in the catalogue, as the field would show it. */
    fun defaultTemperature(model: ModelSpec): String =
        TemperatureInput.format(model.sampling.temperature.coerceAtLeast(0f), decimalSeparator())

    /**
     * The temperature field of [modelId] was edited. The text is kept as typed. A valid number
     * is stored at once and an empty field stores "the model's own"; anything else stores
     * nothing, so the last valid value stays.
     */
    fun onTemperatureChange(modelId: String, text: String) {
        if (models.none { it.id == modelId }) return
        if (text.isEmpty()) temperatures.remove(modelId) else temperatures[modelId] = text
        when (val parsed = TemperatureInput.parse(text, decimalSeparator())) {
            TemperatureInput.Parsed.Default -> settings.update { it.withTemperature(modelId, null) }
            is TemperatureInput.Parsed.Value -> settings.update { it.withTemperature(modelId, parsed.temperature) }
            TemperatureInput.Parsed.Invalid -> Unit
        }
    }

    /** One tap back to the model's own temperature: empties the field, whatever was in it. */
    fun useDefaultTemperature(modelId: String) = onTemperatureChange(modelId, "")

    /** The text in the longest-reply field of [modelId]; empty when the model's own applies. */
    fun replyLength(modelId: String): String = replyLengths[modelId].orEmpty()

    /**
     * The limits of the longest reply of [model] now: those that follow from how long its
     * context may become on this device under the size chosen for it (see [ReplyLength]). They
     * change when another size is chosen.
     */
    private fun replyLengthRange(model: ModelSpec): IntRange = ReplyLength.range(contextLimit(model))

    /** True when the longest-reply field of [model] holds something that is not a length within its limits. */
    fun replyLengthInvalid(model: ModelSpec): Boolean =
        ReplyLengthInput.parse(replyLength(model.id), replyLengthRange(model)) == ReplyLengthInput.Parsed.Invalid

    /** The longest reply [model] writes when none is set: the catalogue's, within the limits; as a sentence shows it. */
    fun defaultReplyLength(model: ModelSpec): String = wholeNumber(model.defaultMaxNewTokens.coerceAtMost(replyLengthRange(model).last))

    /** The shortest and the longest "longest reply" [model] accepts now, as a sentence shows them. */
    fun replyLengthLimits(model: ModelSpec): Pair<String, String> = replyLengthRange(model).let { wholeNumber(it.first) to wholeNumber(it.last) }

    /**
     * The longest-reply field of [modelId] was edited. The text is kept as typed. A number
     * within the model's limits is stored at once and an empty field stores "the model's own";
     * anything else stores nothing, so the last valid value stays.
     */
    fun onReplyLengthChange(modelId: String, text: String) {
        val model = models.firstOrNull { it.id == modelId } ?: return
        if (text.isEmpty()) replyLengths.remove(modelId) else replyLengths[modelId] = text
        when (val parsed = ReplyLengthInput.parse(text, replyLengthRange(model))) {
            ReplyLengthInput.Parsed.Default -> settings.update { it.withMaxNewTokens(modelId, null) }
            is ReplyLengthInput.Parsed.Value -> settings.update { it.withMaxNewTokens(modelId, parsed.tokens) }
            ReplyLengthInput.Parsed.Invalid -> Unit
        }
    }

    /** One tap back to the model's own longest reply: empties the field, whatever was in it. */
    fun useDefaultReplyLength(modelId: String) = onReplyLengthChange(modelId, "")

    /**
     * One size the context of a model may be allowed to grow to.
     *
     * @property tokens the size that is stored for it; null for the automatic one.
     * @property limitTokens how large the context may become with it on this device.
     * @property memoryBytes what a context of that size costs in memory; 0 when that is not known.
     * @property rereadSeconds how long the model takes to read a conversation of that size
     *   again, by the reading times measured on the tested phone; null when they are not known.
     */
    data class ContextOption(val tokens: Int?, val limitTokens: Int, val memoryBytes: Long, val rereadSeconds: Int?) {
        /**
         * The time in whole minutes, for a time that is better said so: from a minute and a half
         * up to an hour and a half. Null outside that, where seconds or hours say it.
         */
        val rereadMinutes: Int? get() = rereadSeconds?.takeIf { it >= SECONDS_SAID_AS_MINUTES && it < SECONDS_SAID_AS_HOURS }?.let { (it + 30) / 60 }

        /**
         * The time in whole hours, for a time that is better said so: from an hour and a half on,
         * which the largest sizes of a model can take to read. Null below that.
         */
        val rereadHours: Int? get() = rereadSeconds?.takeIf { it >= SECONDS_SAID_AS_HOURS }?.let { (it + 1800) / 3600 }
    }

    /**
     * The sizes to choose from for the context of [model]: the automatic one first, then the
     * fixed ones, smallest first. Only the automatic one for a model whose context cannot grow
     * on this device, which is then no choice but a fact to state.
     */
    fun contextOptions(model: ModelSpec): List<ContextOption> {
        val automatic = contextOption(model, null, contextLimits.limit(model, chosen = null))
        val fixed = contextLimits.choices(model)
        return if (fixed.size < 2) listOf(automatic) else listOf(automatic) + fixed.map { contextOption(model, it, it) }
    }

    private fun contextOption(model: ModelSpec, tokens: Int?, limit: Int) =
        ContextOption(tokens, limit, contextLimits.bytes(model, limit), contextLimits.rereadSeconds(model, limit))

    /**
     * The option of [model] that is in force: null for the automatic one, otherwise one of the
     * fixed sizes. A stored size that is not among them (the catalogue or the device changed)
     * counts as the size it stands for here, as it does where it is used (see
     * [DeviceContextLimits.chosen]). A model with nothing to choose has no choice.
     */
    fun contextChoice(model: ModelSpec): Int? =
        if (contextLimits.choices(model).size < 2) null else contextLimits.chosen(model, contexts[model.id])

    /** How long the context of [model] may become now, in tokens: the limit under the size chosen for it. */
    fun contextLimit(model: ModelSpec): Int = contextLimits.limit(model, contextLimits.chosen(model, contexts[model.id]))

    /** A number of tokens as a sentence shows it. */
    fun tokens(count: Int): String = wholeNumber(count)

    /**
     * Another size was chosen for the context of [modelId]: null for the automatic one,
     * otherwise one of the fixed sizes of [contextOptions]; anything else is ignored. Stored
     * at once. The limits of the longest reply follow from it; an entry in that field which no
     * longer lies within them is marked there and is not in use until it does.
     */
    fun onContextChoice(modelId: String, tokens: Int?) {
        val model = models.firstOrNull { it.id == modelId } ?: return
        if (tokens != null && tokens !in contextLimits.choices(model)) return
        if (tokens == null) contexts.remove(modelId) else contexts[modelId] = tokens
        settings.update { it.withContextTokens(modelId, tokens) }
    }

    /** True when [modelId] has a temperature, a longest reply or a context size of its own, or a field holds an entry for one. */
    fun hasOwnValues(modelId: String): Boolean =
        temperature(modelId).isNotEmpty() || replyLength(modelId).isNotEmpty() || contexts.containsKey(modelId)

    /**
     * Back to the model's own temperature and longest reply and to the automatic context: both
     * fields are emptied, whatever was in them. The system prompt is not a value with a default
     * but something the user wrote, and stays.
     */
    fun restoreDefaults(modelId: String) {
        useDefaultTemperature(modelId)
        onContextChoice(modelId, null)
        useDefaultReplyLength(modelId)
    }

    /** Whether a model has a system prompt, for its row in Settings. */
    enum class SystemPromptState { SET, NONE, NOT_POSSIBLE }

    /**
     * What the row of a model says about its settings: the values a reply would be written
     * with now, the model's own where nothing is set.
     */
    data class ModelSummary(val temperature: String, val replyTokens: String, val systemPrompt: SystemPromptState)

    /** The summary of [model] under the settings [stored]. */
    fun summary(model: ModelSpec, stored: AppSettings): ModelSummary = ModelSummary(
        temperature = TemperatureInput.format(stored.sampling(model).temperature.coerceAtLeast(0f), decimalSeparator()),
        replyTokens = wholeNumber(stored.maxNewTokens(model, contextLimits.limit(model, contextLimits.chosen(model, stored.contextTokens(model.id))))),
        systemPrompt = when {
            !model.supportsSystemPrompt -> SystemPromptState.NOT_POSSIBLE
            stored.systemPrompt(model.id).isEmpty() -> SystemPromptState.NONE
            else -> SystemPromptState.SET
        },
    )

    fun loadIssueShown() = settings.loadIssueShown()

    private companion object {
        const val SECONDS_SAID_AS_MINUTES = 90
        const val SECONDS_SAID_AS_HOURS = 90 * 60
    }
}
