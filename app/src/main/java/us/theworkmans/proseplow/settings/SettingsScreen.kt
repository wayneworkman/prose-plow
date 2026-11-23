package us.theworkmans.proseplow.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import us.theworkmans.proseplow.BuildConfig
import us.theworkmans.proseplow.R
import us.theworkmans.proseplow.chat.modelName
import us.theworkmans.proseplow.core.model.ModelSpec
import us.theworkmans.proseplow.data.SettingsStore
import us.theworkmans.proseplow.lock.SecuritySettingsSection
import us.theworkmans.proseplow.lock.SecurityViewModel
import us.theworkmans.proseplow.ui.NoticeCard
import us.theworkmans.proseplow.ui.SectionTitle

/**
 * Settings, kept short: a row for each model of this build, which says the model's current
 * values and opens the model's own page; then the lock's settings (which end in "Reset app
 * data", the one way to delete every chat and start again); and the way to the About screen.
 * Everything is saved as it is edited; there is no Save button and nothing to lose by going
 * back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    security: SecurityViewModel,
    onBack: () -> Unit,
    onOpenModel: (modelId: String) -> Unit,
    onOpenAbout: () -> Unit,
) {
    val loadIssue by viewModel.loadIssue.collectAsStateWithLifecycle()
    val stored by viewModel.stored.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings), modifier = Modifier.semantics { heading() }) },
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

            SectionTitle(stringResource(R.string.section_models))
            Text(
                stringResource(R.string.models_support),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (viewModel.models.isEmpty()) {
                Text(stringResource(R.string.about_no_models), style = MaterialTheme.typography.bodyMedium)
            }
            for (model in viewModel.models) {
                ModelRow(model, viewModel.summary(model, stored), onOpen = { onOpenModel(model.id) })
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

/**
 * One model in Settings: its name, and under it one line with the values an answer of this
 * model would be written with now. The whole row is one button, which opens the model's page;
 * a screen reader reads the name, the line and what a tap does as one thing.
 */
@Composable
private fun ModelRow(model: ModelSpec, summary: SettingsViewModel.ModelSummary, onOpen: () -> Unit) {
    val line = stringResource(
        when (summary.systemPrompt) {
            SettingsViewModel.SystemPromptState.SET -> R.string.model_summary_prompt_set
            SettingsViewModel.SystemPromptState.NONE -> R.string.model_summary_prompt_none
            SettingsViewModel.SystemPromptState.NOT_POSSIBLE -> R.string.model_summary_no_prompt_possible
        },
        summary.temperature,
        summary.replyTokens,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClickLabel = stringResource(R.string.model_settings_open), role = Role.Button, onClick = onOpen)
            .heightIn(min = 56.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(modelName(model.id), style = MaterialTheme.typography.titleMedium)
            Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
