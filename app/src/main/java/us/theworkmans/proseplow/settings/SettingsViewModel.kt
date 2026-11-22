package us.theworkmans.proseplow.settings

import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow
import us.theworkmans.proseplow.core.model.ModelSpec
import us.theworkmans.proseplow.data.SettingsStore

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
 * @param models the models this build offers; each gets a row, with a system prompt or with the
 *   reason why it has none.
 */
class SettingsViewModel(private val settings: SettingsStore, val models: List<ModelSpec>) : ViewModel() {

    private val prompts = mutableStateMapOf<String, String>().apply { putAll(settings.settings.value.systemPrompts) }

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

    fun loadIssueShown() = settings.loadIssueShown()
}
