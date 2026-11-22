package us.theworkmans.proseplow.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import us.theworkmans.proseplow.BuildConfig
import us.theworkmans.proseplow.R
import us.theworkmans.proseplow.chat.modelDescription
import us.theworkmans.proseplow.chat.modelName
import us.theworkmans.proseplow.core.model.ModelSpec
import us.theworkmans.proseplow.ui.SectionTitle

/**
 * About: which version this is, where each model in this build comes from and under which
 * licence, and the licence of llama.cpp, which does the inference. The licence text is in the
 * app because the licence asks for it to accompany every copy.
 *
 * @param models the models this build offers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(models: List<ModelSpec>, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.section_about)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { innerPadding ->
        SelectionContainer {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.about_version, stringResource(R.string.app_name), BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(stringResource(R.string.about_summary), style = MaterialTheme.typography.bodyMedium)

                SectionTitle(stringResource(R.string.about_models))
                if (models.isEmpty()) {
                    Text(stringResource(R.string.about_no_models), style = MaterialTheme.typography.bodyMedium)
                }
                for (model in models) {
                    Column {
                        Text(modelName(model.id), style = MaterialTheme.typography.bodyLarge)
                        modelDescription(model.id)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        Text(
                            stringResource(R.string.about_model_source, model.sourceRepository),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(R.string.about_model_licence, model.licence ?: stringResource(R.string.about_licence_not_stated)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                SectionTitle(stringResource(R.string.about_llama_cpp))
                Text(stringResource(R.string.about_llama_cpp_summary), style = MaterialTheme.typography.bodyMedium)
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(R.string.about_llama_cpp_licence),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
    }
}
