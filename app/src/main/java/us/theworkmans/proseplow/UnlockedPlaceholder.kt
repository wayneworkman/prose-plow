package us.theworkmans.proseplow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import us.theworkmans.proseplow.lock.SecuritySettingsSection
import us.theworkmans.proseplow.lock.SecurityViewModel
import us.theworkmans.proseplow.security.AppLock
import us.theworkmans.proseplow.ui.rememberCopyToClipboard

/**
 * What the app shows while unlocked until the chat and the Settings screen exist: a line that
 * says so, a text to copy (the clipboard must keep working), and the Security section that the
 * Settings screen will embed, so that every lock flow can be exercised today. The ViewModel
 * lives in the session's store, like those of the real screens will.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnlockedPlaceholder(session: AppSession, appLock: AppLock<*>) {
    val security: SecurityViewModel = viewModel(factory = viewModelFactory { initializer { SecurityViewModel(appLock) } })
    val writeFailure by session.writeFailure.collectAsStateWithLifecycle()
    val copy = rememberCopyToClipboard()
    val sample = stringResource(R.string.placeholder_sample)
    val copied = stringResource(R.string.copied)

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { innerPadding ->
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
            Text(stringResource(R.string.placeholder_message), style = MaterialTheme.typography.bodyLarge)
            Text(sample, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { copy(sample, copied) }) { Text(stringResource(R.string.placeholder_copy)) }
            writeFailure?.let {
                Text(
                    stringResource(R.string.session_write_failed, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Text(
                stringResource(R.string.section_security),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
            SecuritySettingsSection(security)
        }
    }
}
