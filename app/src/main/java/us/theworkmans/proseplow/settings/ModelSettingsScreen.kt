package us.theworkmans.proseplow.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import us.theworkmans.proseplow.R
import us.theworkmans.proseplow.chat.ModelText
import us.theworkmans.proseplow.chat.modelDescription
import us.theworkmans.proseplow.chat.modelName
import us.theworkmans.proseplow.core.model.ModelSpec

/**
 * The settings of one model, on a page of its own under the model's name: what the model is,
 * the way to its card on Hugging Face ([ModelCardLink]), its system prompt (or one line saying why it has none), its temperature, its longest reply,
 * how far its context may grow, and the way back to the model's own values. Everything is saved as it is edited and applies
 * from the next answer.
 *
 * The two number fields keep whatever is typed. While that is not a value the model accepts,
 * the field is marked, its supporting text says that nothing is saved until it is, and the
 * value stored before stays in use. Empty is the model's own value, which the placeholder and
 * the supporting text name; the button at the end of a field empties it in one tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSettingsScreen(viewModel: SettingsViewModel, model: ModelSpec, onBack: () -> Unit) {
    val name = modelName(model.id)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            modelDescription(model.id)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            ModelCardLink(model)
            Text(
                stringResource(R.string.model_settings_support),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SystemPrompt(viewModel, model, name)
            Temperature(viewModel, model)
            ReplyLength(viewModel, model)
            ContextSize(viewModel, model)

            OutlinedButton(onClick = { viewModel.restoreDefaults(model.id) }, enabled = viewModel.hasOwnValues(model.id)) {
                Text(stringResource(R.string.model_restore_defaults))
            }
            Text(
                stringResource(if (model.supportsSystemPrompt) R.string.model_restore_defaults_support else R.string.model_restore_defaults_support_no_prompt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SystemPrompt(viewModel: SettingsViewModel, model: ModelSpec, name: String) {
    if (model.supportsSystemPrompt) {
        OutlinedTextField(
            value = viewModel.systemPrompt(model.id),
            onValueChange = { viewModel.onSystemPromptChange(model.id, it) },
            label = { Text(stringResource(R.string.system_prompt_label)) },
            placeholder = { Text(stringResource(R.string.system_prompt_hint)) },
            supportingText = { Text(stringResource(R.string.system_prompt_support)) },
            minLines = 2,
            maxLines = 8,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        Text(
            stringResource(ModelText.noSystemPromptRes(model.id), name),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Temperature(viewModel: SettingsViewModel, model: ModelSpec) {
    val temperature = viewModel.temperature(model.id)
    val invalid = viewModel.temperatureInvalid(model.id)
    val default = viewModel.defaultTemperature(model)
    OutlinedTextField(
        value = temperature,
        onValueChange = { viewModel.onTemperatureChange(model.id, it) },
        label = { Text(stringResource(R.string.temperature_label)) },
        placeholder = { Text(default) },
        singleLine = true,
        isError = invalid,
        supportingText = {
            if (invalid) {
                // Said as soon as it appears: a screen reader's user must not go on believing the entry was taken.
                Text(
                    stringResource(R.string.temperature_invalid) + " " + stringResource(R.string.invalid_not_saved),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            } else {
                Text(stringResource(if (model.sampling.isGreedy) R.string.temperature_support_greedy else R.string.temperature_support, default))
            }
        },
        trailingIcon = if (temperature.isEmpty()) {
            null
        } else {
            {
                IconButton(onClick = { viewModel.useDefaultTemperature(model.id) }) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.temperature_use_default))
                }
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The longest reply, in the manner of the temperature field. Its supporting text names the note
 * a reply ends with at the limit by the very string the chat shows, so the two cannot drift
 * apart.
 */
@Composable
private fun ReplyLength(viewModel: SettingsViewModel, model: ModelSpec) {
    val length = viewModel.replyLength(model.id)
    val invalid = viewModel.replyLengthInvalid(model)
    val default = viewModel.defaultReplyLength(model)
    val (least, most) = viewModel.replyLengthLimits(model)
    OutlinedTextField(
        value = length,
        onValueChange = { viewModel.onReplyLengthChange(model.id, it) },
        label = { Text(stringResource(R.string.reply_length_label)) },
        placeholder = { Text(default) },
        singleLine = true,
        isError = invalid,
        supportingText = {
            if (invalid) {
                Text(
                    stringResource(R.string.reply_length_invalid, least, most) + " " + stringResource(R.string.invalid_not_saved),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            } else {
                Text(
                    stringResource(
                        R.string.reply_length_support,
                        stringResource(R.string.note_length),
                        stringResource(R.string.continue_reply),
                        default,
                        most,
                    ),
                )
            }
        },
        trailingIcon = if (length.isEmpty()) {
            null
        } else {
            {
                IconButton(onClick = { viewModel.useDefaultReplyLength(model.id) }) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.reply_length_use_default))
                }
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * How far the model's context may grow: the automatic size and the fixed ones, of which one is
 * chosen, each with what it costs in memory and in time. The text above them says what the
 * choice means for a long conversation after the app was locked and left. A model whose context
 * cannot grow on this phone has no choice, and one sentence that says how much it reads and why.
 */
@Composable
private fun ContextSize(viewModel: SettingsViewModel, model: ModelSpec) {
    val options = viewModel.contextOptions(model)
    val chosen = viewModel.contextChoice(model)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.context_label),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        if (options.size == 1) {
            val only = options.single().limitTokens
            Text(
                stringResource(if (only >= model.maxContextLength) R.string.context_single_model else R.string.context_single_phone, viewModel.tokens(only)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                stringResource(R.string.context_support, viewModel.tokens(options[1].limitTokens)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(modifier = Modifier.selectableGroup()) {
                for (option in options) {
                    ContextRow(
                        option = option,
                        tokens = viewModel.tokens(option.limitTokens),
                        selected = option.tokens == chosen,
                        onSelect = { viewModel.onContextChoice(model.id, option.tokens) },
                    )
                }
            }
        }
    }
}

/** One size to choose: the whole row is the radio button, so that its text is what a screen reader says for it. */
@Composable
private fun ContextRow(option: SettingsViewModel.ContextOption, tokens: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column {
            Text(
                stringResource(if (option.tokens == null) R.string.context_automatic else R.string.context_fixed, tokens),
                style = MaterialTheme.typography.bodyLarge,
            )
            contextCost(option)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** What [option] costs when a conversation fills it: the memory, and the time to read it all again where that is known. */
@Composable
private fun contextCost(option: SettingsViewModel.ContextOption): String? {
    if (option.memoryBytes <= 0) return null
    val memory = Formatter.formatShortFileSize(LocalContext.current, option.memoryBytes)
    val seconds = option.rereadSeconds ?: return stringResource(R.string.context_cost_memory_only, memory)
    val minutes = option.rereadMinutes
    val time = if (minutes != null) {
        pluralStringResource(R.plurals.duration_minutes, minutes, minutes)
    } else {
        pluralStringResource(R.plurals.duration_seconds, seconds, seconds)
    }
    return stringResource(R.string.context_cost, memory, time)
}
