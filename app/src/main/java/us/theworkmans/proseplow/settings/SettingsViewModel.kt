package us.theworkmans.proseplow.settings

import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow
import us.theworkmans.proseplow.core.model.ModelSpec
import us.theworkmans.proseplow.data.SettingsStore
import java.text.DecimalFormatSymbols

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
 * @param models the models this build offers; each gets a section, with its system prompt (or
 *   the reason why it has none) and its temperature.
 * @param decimalSeparator the decimal mark of the user's locale, asked for whenever a number is
 *   read or shown, so that a change of language while the screen is open is followed.
 */
class SettingsViewModel(
    private val settings: SettingsStore,
    val models: List<ModelSpec>,
    private val decimalSeparator: () -> Char = { DecimalFormatSymbols.getInstance().decimalSeparator },
) : ViewModel() {

    private val prompts = mutableStateMapOf<String, String>().apply { putAll(settings.settings.value.systemPrompts) }

    /** The text of each temperature field that is not empty, by model id. */
    private val temperatures = mutableStateMapOf<String, String>().apply {
        for ((modelId, overrides) in settings.settings.value.modelOverrides) {
            overrides.temperature?.let { put(modelId, TemperatureInput.format(it, decimalSeparator())) }
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

    fun loadIssueShown() = settings.loadIssueShown()
}
