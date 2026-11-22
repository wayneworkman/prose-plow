package us.theworkmans.proseplow.lock

import android.os.Build
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import us.theworkmans.proseplow.R
import us.theworkmans.proseplow.security.AppLock
import us.theworkmans.proseplow.security.LockConfig
import us.theworkmans.proseplow.security.PassphraseCheck
import us.theworkmans.proseplow.security.PassphraseProblem
import us.theworkmans.proseplow.security.PassphraseProof
import us.theworkmans.proseplow.security.PassphraseRules
import us.theworkmans.proseplow.security.ProofExpiredException
import us.theworkmans.proseplow.security.ScreenshotAllowance
import us.theworkmans.proseplow.security.ScreenshotsAllowed
import us.theworkmans.proseplow.security.SecurityStatus
import us.theworkmans.proseplow.security.WindowSecurity
import us.theworkmans.proseplow.security.wipe
import us.theworkmans.proseplow.ui.PassphraseField
import us.theworkmans.proseplow.ui.takeChars
import java.util.Date

/** What a passphrase check in Settings is for. */
enum class ProtectedAction { CHANGE, FINGERPRINT_ON, FINGERPRINT_OFF }

sealed interface SecurityDialog {
    /** The current passphrase, before [action]. */
    data class Confirm(val action: ProtectedAction) : SecurityDialog

    /** The new passphrase (twice) after the current one was confirmed. */
    class ChangePassphrase(val proof: PassphraseProof) : SecurityDialog

    /** "Reset app data": the typed confirmation. */
    data object Reset : SecurityDialog
}

enum class SecurityError { MISMATCH, EMPTY, TOO_SHORT, TOO_LONG, DIGITS_ONLY, WRONG, THROTTLED, EXPIRED, FAILED, SCREENSHOTS_NOT_ALLOWED }

private fun PassphraseProblem.toSecurityError() = when (this) {
    PassphraseProblem.EMPTY -> SecurityError.EMPTY
    PassphraseProblem.TOO_SHORT -> SecurityError.TOO_SHORT
    PassphraseProblem.TOO_LONG -> SecurityError.TOO_LONG
    PassphraseProblem.DIGITS_ONLY -> SecurityError.DIGITS_ONLY
    PassphraseProblem.MISMATCH -> SecurityError.MISMATCH
}

data class SecurityUiState(
    val dialog: SecurityDialog? = null,
    val busy: Boolean = false,
    val error: SecurityError? = null,
    /** The passphrase was just changed; said once, below the section. */
    val passphraseChanged: Boolean = false,
)

/**
 * The Security section of Settings. Passphrases arrive as CharArrays taken out of the text
 * fields and are zeroed here once used; they are never stored in this ViewModel's state. Every
 * call that can fail (the key store, the disk) is caught here and ends as [SecurityError.FAILED]
 * on the screen.
 *
 * It lives in the unlocked session's ViewModel store, so a lock or a reset clears it.
 */
class SecurityViewModel(private val appLock: AppLock<*>) : ViewModel() {
    val status: StateFlow<SecurityStatus> = appLock.status

    /** The screenshot allowance that is running, or null. It ends by itself, and the switch follows. */
    val screenshots: StateFlow<ScreenshotsAllowed?> = appLock.screenshots.allowed

    private val _state = MutableStateFlow(SecurityUiState())
    val state: StateFlow<SecurityUiState> = _state.asStateFlow()

    fun biometricAvailable() = appLock.biometricAvailable()

    fun onChangePassphrase() = open(SecurityDialog.Confirm(ProtectedAction.CHANGE))

    fun onFingerprintSwitch(on: Boolean) =
        open(SecurityDialog.Confirm(if (on) ProtectedAction.FINGERPRINT_ON else ProtectedAction.FINGERPRINT_OFF))

    fun onReset() = open(SecurityDialog.Reset)

    fun dismissDialog() {
        if (!_state.value.busy) _state.value = SecurityUiState()
    }

    private fun open(dialog: SecurityDialog) {
        _state.value = SecurityUiState(dialog = dialog)
    }

    /** "Lock now". The lock clears this ViewModel with the rest of the session's screens. */
    fun lockNow() = appLock.lock()

    /**
     * The confirmation word was typed in [SecurityDialog.Reset]. The lock closes the unlocked
     * session before it deletes anything, and reports what it could not delete on the screen
     * that follows; this ViewModel is gone by then.
     */
    fun confirmReset() {
        if (_state.value.dialog != SecurityDialog.Reset) return
        _state.value = SecurityUiState()
        appLock.reset()
    }

    /** The two new-passphrase fields of [SecurityDialog.ChangePassphrase]. */
    fun submitNewPassphrase(first: CharArray, second: CharArray) {
        val dialog = _state.value.dialog as? SecurityDialog.ChangePassphrase
        val problem = PassphraseRules.checkNew(first, second)?.toSecurityError()
        second.wipe()
        if (dialog == null || problem != null || _state.value.busy) {
            first.wipe()
            _state.update { it.copy(error = problem ?: it.error) }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val changed = appLock.changePassphrase(dialog.proof, first)
                _state.value = SecurityUiState(passphraseChanged = changed)
            } catch (e: CancellationException) {
                throw e
            } catch (_: ProofExpiredException) {
                _state.value = SecurityUiState(error = SecurityError.EXPIRED)
            } catch (_: Exception) {
                _state.update { it.copy(busy = false, error = SecurityError.FAILED) }
            } finally {
                first.wipe()
            }
        }
    }

    /** The current passphrase for a [SecurityDialog.Confirm]. */
    fun submitCurrentPassphrase(passphrase: CharArray) {
        val dialog = _state.value.dialog as? SecurityDialog.Confirm
        if (dialog == null || _state.value.busy) {
            passphrase.wipe()
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                when (val check = appLock.checkPassphrase(passphrase)) {
                    is PassphraseCheck.Ok -> perform(dialog.action, check.proof)
                    is PassphraseCheck.Wrong -> _state.update { it.copy(busy = false, error = SecurityError.WRONG) }
                    is PassphraseCheck.Throttled -> _state.update { it.copy(busy = false, error = SecurityError.THROTTLED) }
                    is PassphraseCheck.Rejected -> _state.update { it.copy(busy = false, error = check.problem.toSecurityError()) }
                    PassphraseCheck.Failed -> _state.update { it.copy(busy = false, error = SecurityError.FAILED) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: ProofExpiredException) {
                _state.value = SecurityUiState(error = SecurityError.EXPIRED)
            } catch (_: Exception) {
                _state.update { it.copy(busy = false, error = SecurityError.FAILED) }
            } finally {
                passphrase.wipe()
            }
        }
    }

    private suspend fun perform(action: ProtectedAction, proof: PassphraseProof) {
        when (action) {
            ProtectedAction.CHANGE -> {
                _state.value = SecurityUiState(dialog = SecurityDialog.ChangePassphrase(proof))
                return
            }
            ProtectedAction.FINGERPRINT_ON -> appLock.startBiometricEnrollment(proof)
            ProtectedAction.FINGERPRINT_OFF -> appLock.disableBiometric(proof)
        }
        _state.value = SecurityUiState()
    }

    /**
     * The "Allow screenshots" switch. On gives an allowance that ends by itself (see
     * [ScreenshotAllowance]); off ends it now. Nothing is stored, and no passphrase is asked
     * for: the app is unlocked, and the allowance opens no key. If the phone's screen cannot be
     * watched nothing is allowed, and the section says so.
     */
    fun setScreenshotsAllowed(allowed: Boolean) {
        if (!allowed) {
            appLock.screenshots.end()
            return
        }
        val granted = appLock.screenshots.grant()
        _state.update { it.copy(error = if (granted) null else SecurityError.SCREENSHOTS_NOT_ALLOWED) }
    }

    fun setLockTimeout(seconds: Int) {
        viewModelScope.launch {
            try {
                appLock.setLockTimeout(seconds)
                _state.update { it.copy(error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The lock configuration could not be written (disk full); the picker shows the stored value.
                _state.update { it.copy(error = SecurityError.FAILED) }
            }
        }
    }
}

/**
 * The rows of the Security section: change passphrase, fingerprint unlock, the background
 * timeout, "Lock now", the temporary screenshot allowance and "Reset app data", with their
 * dialogs. It emits its rows into the column of the Settings screen it is placed in (which
 * supplies the section title and the spacing), and needs nothing but its ViewModel.
 *
 * There is no switch for the lock itself, for the app switcher's preview or for the keyboard:
 * the lock is mandatory and those two protections are always on, which the section says in a
 * line of text. Screenshots are blocked too, except while the user has allowed them: that
 * switch turns itself off again (when its time is up, or the screen turns off), and the row
 * shows it happening.
 */
@Composable
fun SecuritySettingsSection(viewModel: SecurityViewModel) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Asked again on every resume, so enrolling a fingerprint in the system settings and coming
    // back enables the switch.
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose { }
    }
    val biometricAvailable = remember(status, resumes) { viewModel.biometricAvailable() }

    OutlinedButton(onClick = viewModel::onChangePassphrase) { Text(stringResource(R.string.security_change_passphrase)) }
    SwitchRow(
        title = stringResource(R.string.security_fingerprint),
        support = stringResource(
            if (biometricAvailable || status.biometricEnabled) R.string.security_fingerprint_support else R.string.security_fingerprint_unavailable,
        ),
        checked = status.biometricEnabled,
        enabled = biometricAvailable || status.biometricEnabled,
        onCheckedChange = viewModel::onFingerprintSwitch,
    )
    TimeoutPicker(status.lockTimeoutSeconds, viewModel::setLockTimeout)
    OutlinedButton(onClick = viewModel::lockNow) { Text(stringResource(R.string.security_lock_now)) }
    ScreenshotsRow(viewModel)
    Text(
        stringResource(R.string.security_always_on),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        if (state.dialog == null && state.error != null) {
            Text(errorText(state.error), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (state.passphraseChanged) {
            Text(stringResource(R.string.security_passphrase_changed), style = MaterialTheme.typography.bodySmall)
        }
    }
    TextButton(
        onClick = viewModel::onReset,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) { Text(stringResource(R.string.reset_action)) }
    Text(
        stringResource(R.string.security_reset_support),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    when (val dialog = state.dialog) {
        is SecurityDialog.ChangePassphrase -> NewPassphraseDialog(
            state = state,
            onSubmit = viewModel::submitNewPassphrase,
            onDismiss = viewModel::dismissDialog,
        )
        is SecurityDialog.Confirm -> ConfirmPassphraseDialog(
            message = stringResource(
                when (dialog.action) {
                    ProtectedAction.CHANGE -> R.string.confirm_change
                    ProtectedAction.FINGERPRINT_ON -> R.string.confirm_fingerprint_on
                    ProtectedAction.FINGERPRINT_OFF -> R.string.confirm_fingerprint_off
                },
            ),
            state = state,
            onSubmit = viewModel::submitCurrentPassphrase,
            onDismiss = viewModel::dismissDialog,
        )
        SecurityDialog.Reset -> ResetDataDialog(onConfirm = viewModel::confirmReset, onDismiss = viewModel::dismissDialog)
        null -> Unit
    }
}

@Composable
private fun errorText(error: SecurityError?): String = when (error) {
    SecurityError.MISMATCH -> stringResource(R.string.passphrase_mismatch)
    SecurityError.EMPTY -> stringResource(R.string.passphrase_empty)
    SecurityError.TOO_SHORT -> stringResource(R.string.passphrase_too_short)
    SecurityError.TOO_LONG -> stringResource(R.string.passphrase_too_long)
    SecurityError.DIGITS_ONLY -> stringResource(R.string.passphrase_digits_only)
    SecurityError.WRONG -> stringResource(R.string.lock_wrong)
    SecurityError.THROTTLED -> stringResource(R.string.lock_throttled)
    SecurityError.EXPIRED -> stringResource(R.string.passphrase_expired)
    SecurityError.FAILED -> stringResource(R.string.security_failed)
    SecurityError.SCREENSHOTS_NOT_ALLOWED -> stringResource(R.string.security_screenshots_failed)
    null -> ""
}

@Composable
private fun NewPassphraseDialog(state: SecurityUiState, onSubmit: (CharArray, CharArray) -> Unit, onDismiss: () -> Unit) {
    val first = remember { TextFieldState() }
    val second = remember { TextFieldState() }
    val submit = { onSubmit(first.takeChars(), second.takeChars()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.change_passphrase_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.passphrase_rules) + " " + stringResource(R.string.change_passphrase_no_recovery),
                    style = MaterialTheme.typography.bodyMedium,
                )
                PassphraseField(
                    state = first,
                    label = stringResource(R.string.passphrase_new),
                    enabled = !state.busy,
                    isError = state.error != null,
                    imeAction = ImeAction.Next,
                    modifier = Modifier.fillMaxWidth(),
                )
                PassphraseField(
                    state = second,
                    label = stringResource(R.string.passphrase_repeat),
                    enabled = !state.busy,
                    isError = state.error != null,
                    supportingText = state.error?.let { errorText(it) },
                    onAction = submit,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.busy) Working(stringResource(R.string.passphrase_working))
            }
        },
        confirmButton = { TextButton(onClick = submit, enabled = !state.busy) { Text(stringResource(R.string.passphrase_save)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.busy) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ConfirmPassphraseDialog(message: String, state: SecurityUiState, onSubmit: (CharArray) -> Unit, onDismiss: () -> Unit) {
    val field = remember { TextFieldState() }
    val submit = {
        if (field.text.isNotEmpty()) onSubmit(field.takeChars())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.confirm_passphrase_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                PassphraseField(
                    state = field,
                    label = stringResource(R.string.lock_passphrase),
                    enabled = !state.busy,
                    isError = state.error != null,
                    supportingText = state.error?.let { errorText(it) },
                    onAction = submit,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.busy) Working(stringResource(R.string.lock_checking))
            }
        },
        confirmButton = { TextButton(onClick = submit, enabled = !state.busy) { Text(stringResource(R.string.passphrase_continue)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.busy) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * The switch that allows screenshots for a while. On, it says when the allowance ends, as a
 * time of day in the user's own format (the locale's, and 12 or 24 hours as set on the phone).
 * It follows the allowance itself, so it turns off on the screen the moment the allowance ends.
 * Where an allowance cannot be honoured (see [WindowSecurity.screenshotsCanBeAllowed]) the
 * switch is disabled and says why.
 */
@Composable
private fun ScreenshotsRow(viewModel: SecurityViewModel) {
    val allowed by viewModel.screenshots.collectAsStateWithLifecycle()
    val possible = WindowSecurity.screenshotsCanBeAllowed(Build.VERSION.SDK_INT)
    val context = LocalContext.current
    val running = allowed
    SwitchRow(
        title = pluralStringResource(R.plurals.security_screenshots, ScreenshotAllowance.DURATION_MINUTES, ScreenshotAllowance.DURATION_MINUTES),
        support = when {
            !possible -> stringResource(R.string.security_screenshots_unavailable)
            running != null -> stringResource(R.string.security_screenshots_on, DateFormat.getTimeFormat(context).format(Date(running.untilWallMillis)))
            else -> stringResource(R.string.security_screenshots_off)
        },
        checked = possible && running != null,
        enabled = possible,
        onCheckedChange = viewModel::setScreenshotsAllowed,
    )
}

@Composable
private fun SwitchRow(title: String, support: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(support, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeoutPicker(seconds: Int, onSelect: (Int) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val labels = mapOf(
        0 to stringResource(R.string.timeout_immediately),
        60 to stringResource(R.string.timeout_1_minute),
        300 to stringResource(R.string.timeout_5_minutes),
        900 to stringResource(R.string.timeout_15_minutes),
    )
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = labels[seconds].orEmpty(),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.security_lock_after)) },
            supportingText = { Text(stringResource(R.string.security_lock_after_support)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (choice in LockConfig.TIMEOUT_CHOICES_SECONDS) {
                DropdownMenuItem(
                    text = { Text(labels[choice].orEmpty()) },
                    onClick = {
                        expanded = false
                        onSelect(choice)
                    },
                )
            }
        }
    }
}
