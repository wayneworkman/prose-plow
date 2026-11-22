package us.theworkmans.proseplow.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import us.theworkmans.proseplow.BuildConfig
import us.theworkmans.proseplow.R
import us.theworkmans.proseplow.chat.modelName
import us.theworkmans.proseplow.data.SettingsStore
import us.theworkmans.proseplow.lock.SecuritySettingsSection
import us.theworkmans.proseplow.lock.SecurityViewModel
import us.theworkmans.proseplow.ui.NoticeCard
import us.theworkmans.proseplow.ui.SectionTitle

/**
 * Settings: one system prompt for each model that takes one, the lock's settings (which end in
 * "Reset app data", the one way to delete every chat and start again), and the way to the About
 * screen. Everything is saved as it is edited; there is no Save button and nothing to lose by
 * going back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, security: SecurityViewModel, onBack: () -> Unit, onOpenAbout: () -> Unit) {
    val loadIssue by viewModel.loadIssue.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
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
            loadIssue?.let { issue ->
                NoticeCard(
                    title = stringResource(R.string.settings_issue_title),
                    detail = stringResource(
                        when (issue) {
                            SettingsStore.LoadIssue.UNREADABLE -> R.string.settings_issue_unreadable
                            SettingsStore.LoadIssue.PARTLY_READ -> R.string.settings_issue_partly_read
                        },
                    ),
                ) {
                    TextButton(onClick = viewModel::loadIssueShown) { Text(stringResource(R.string.ok)) }
                }
            }

            SectionTitle(stringResource(R.string.section_system_prompts))
            Text(
                stringResource(R.string.system_prompts_support),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (viewModel.models.isEmpty()) {
                Text(stringResource(R.string.about_no_models), style = MaterialTheme.typography.bodyMedium)
            }
            for (model in viewModel.models) {
                val name = modelName(model.id)
                if (model.supportsSystemPrompt) {
                    OutlinedTextField(
                        value = viewModel.systemPrompt(model.id),
                        onValueChange = { viewModel.onSystemPromptChange(model.id, it) },
                        label = { Text(name) },
                        placeholder = { Text(stringResource(R.string.system_prompt_hint)) },
                        minLines = 2,
                        maxLines = 8,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Column {
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.system_prompt_unsupported, name),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            SectionTitle(stringResource(R.string.section_security))
            SecuritySettingsSection(security)

            SectionTitle(stringResource(R.string.section_about))
            Text(
                stringResource(R.string.about_version, stringResource(R.string.app_name), BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodyLarge,
            )
            OutlinedButton(onClick = onOpenAbout) { Text(stringResource(R.string.about_open)) }
        }
    }
}
