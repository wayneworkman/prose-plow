package us.theworkmans.proseplow.settings

import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow
import us.theworkmans.proseplow.core.model.ModelSpec
import us.theworkmans.proseplow.core.model.acceptsReplyLength
import us.theworkmans.proseplow.core.model.replyLengthRange
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
 *   own, with its system prompt (or the reason why it has none), its temperature and its
 *   longest reply.
 * @param decimalSeparator the decimal mark of the user's locale, asked for whenever a number is
 *   read or shown, so that a change of language while the screen is open is followed.
 * @param wholeNumber a whole number as the user's locale writes it in a sentence (1,024 or
 *   1.024); asked for whenever one is shown, for the same reason.
 */
class SettingsViewModel(
    private val settings: SettingsStore,
    val models: List<ModelSpec>,
    private val decimalSeparator: () -> Char = { DecimalFormatSymbols.getInstance().decimalSeparator },
    private val wholeNumber: (Int) -> String = { NumberFormat.getIntegerInstance().format(it.toLong()) },
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

    /**
     * The text of each longest-reply field that is not empty, by model id. A stored value that
     * its model does not accept is not in use (the model's own is) and is not shown either.
     */
    private val replyLengths = mutableStateMapOf<String, String>().apply {
        for (model in models) {
            val own = settings.settings.value.overrides(model.id).maxNewTokens
            if (own != null && model.acceptsReplyLength(own)) put(model.id, ReplyLengthInput.format(own))
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

    /** True when the longest-reply field of [model] holds something that is not a length the model accepts. */
    fun replyLengthInvalid(model: ModelSpec): Boolean = ReplyLengthInput.parse(replyLength(model.id), model) == ReplyLengthInput.Parsed.Invalid

    /** The longest reply [model] has in the catalogue, as a sentence shows it. */
    fun defaultReplyLength(model: ModelSpec): String = wholeNumber(model.defaultMaxNewTokens)

    /** The shortest and the longest "longest reply" [model] accepts, as a sentence shows them. */
    fun replyLengthLimits(model: ModelSpec): Pair<String, String> = wholeNumber(model.replyLengthRange.first) to wholeNumber(model.replyLengthRange.last)

    /**
     * The longest-reply field of [modelId] was edited. The text is kept as typed. A number the
     * model accepts is stored at once and an empty field stores "the model's own"; anything
     * else stores nothing, so the last valid value stays.
     */
    fun onReplyLengthChange(modelId: String, text: String) {
        val model = models.firstOrNull { it.id == modelId } ?: return
        if (text.isEmpty()) replyLengths.remove(modelId) else replyLengths[modelId] = text
        when (val parsed = ReplyLengthInput.parse(text, model)) {
            ReplyLengthInput.Parsed.Default -> settings.update { it.withMaxNewTokens(modelId, null) }
            is ReplyLengthInput.Parsed.Value -> settings.update { it.withMaxNewTokens(modelId, parsed.tokens) }
            ReplyLengthInput.Parsed.Invalid -> Unit
        }
    }

    /** One tap back to the model's own longest reply: empties the field, whatever was in it. */
    fun useDefaultReplyLength(modelId: String) = onReplyLengthChange(modelId, "")

    /** True when [modelId] has a temperature or a longest reply of its own, or a field holds an entry for one. */
    fun hasOwnValues(modelId: String): Boolean = temperature(modelId).isNotEmpty() || replyLength(modelId).isNotEmpty()

    /**
     * Back to the model's own temperature and longest reply: both fields are emptied, whatever
     * was in them. The system prompt is not a value with a default but something the user
     * wrote, and stays.
     */
    fun restoreDefaults(modelId: String) {
        useDefaultTemperature(modelId)
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
        replyTokens = wholeNumber(stored.maxNewTokens(model)),
        systemPrompt = when {
            !model.supportsSystemPrompt -> SystemPromptState.NOT_POSSIBLE
            stored.systemPrompt(model.id).isEmpty() -> SystemPromptState.NONE
            else -> SystemPromptState.SET
        },
    )

    fun loadIssueShown() = settings.loadIssueShown()
}
