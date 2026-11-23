package us.theworkmans.proseplow.lock

import android.os.Build
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import us.theworkmans.proseplow.security.SessionClose
import us.theworkmans.proseplow.security.UnlockUi
import us.theworkmans.proseplow.security.WindowSecurity
import us.theworkmans.proseplow.security.wipe
import us.theworkmans.proseplow.ui.ProtectWindow
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

    /**
     * A limit for messages, of [toDays] days, that deletes some now
     * ([LockConfig.messageLifetimeDeletesNow]): asked before it applies.
     */
    data class ShortenMessageLifetime(val toDays: Int) : SecurityDialog
}

/**
 * The setting of how long messages are kept, as the unlocked session holds it (encrypted), in
 * days as [LockConfig.messageLifetimeDays] counts them. The security settings read it and change
 * it through this; the readable copy in the lock configuration is written beside it
 * ([AppLock.setMessageLifetimeDays]).
 */
interface MessageLifetimeSetting {
    /** How long messages are kept now; one of [LockConfig.MESSAGE_LIFETIME_CHOICES_DAYS]. */
    val messageLifetimeDays: Int

    /** Makes [days] the setting and deletes at once what has expired under it. Main thread. */
    fun changeMessageLifetimeDays(days: Int)
}

/**
 * The switch "Keep the model's memory after locking", as the unlocked session holds it
 * (encrypted). The security settings read it and change it through this.
 */
interface ModelMemorySetting {
    /** False where the platform cannot keep the model's memory safely: the switch is then shown disabled, and says why. */
    val modelMemorySupported: Boolean

    /** Whether the model's memory is kept now; always false where it is not [modelMemorySupported]. */
    val keepModelMemory: Boolean

    /** Makes [keep] the setting; off deletes the saved memory at once. Main thread. */
    fun changeKeepModelMemory(keep: Boolean)
}

/** What went wrong, said once in words. A wait after wrong attempts is not one of these: it is counted down ([SecurityViewModel.unlockUi]). */
enum class SecurityError { MISMATCH, TOO_SHORT, TOO_LONG, DIGITS_ONLY, WRONG, EXPIRED, FAILED, SCREENSHOTS_NOT_ALLOWED }

/** What is said about [this]; nothing about an empty entry, which no dialog submits (see [SecurityViewModel.submitCurrentPassphrase]). */
private fun PassphraseProblem.toSecurityError(): SecurityError? = when (this) {
    PassphraseProblem.EMPTY -> null
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
class SecurityViewModel(
    private val appLock: AppLock<*>,
    private val lifetimeSetting: MessageLifetimeSetting,
    private val memorySetting: ModelMemorySetting,
) : ViewModel() {
    val status: StateFlow<SecurityStatus> = appLock.status

    /** Whether the switch "Keep the model's memory after locking" can be used on this device. */
    val modelMemorySupported: Boolean get() = memorySetting.modelMemorySupported

    private val _keepModelMemory = MutableStateFlow(memorySetting.keepModelMemory)

    /** Whether the model's memory is kept across a lock; what that switch shows. */
    val keepModelMemory: StateFlow<Boolean> = _keepModelMemory.asStateFlow()

    /**
     * The switch "Keep the model's memory after locking". No passphrase is asked for: the app is
     * unlocked, the choice opens no key, and off only deletes. Off deletes the saved memory at once.
     */
    fun setKeepModelMemory(keep: Boolean) {
        if (!memorySetting.modelMemorySupported) return
        memorySetting.changeKeepModelMemory(keep)
        _keepModelMemory.value = memorySetting.keepModelMemory
    }

    private val _messageLifetimeDays = MutableStateFlow(lifetimeSetting.messageLifetimeDays)

    /** How long messages are kept, in days, as stored; what "Delete messages after" shows. */
    val messageLifetimeDays: StateFlow<Int> = _messageLifetimeDays.asStateFlow()

    /** The screenshot allowance that is running, or null. It ends by itself, and the switch follows. */
    val screenshots: StateFlow<ScreenshotsAllowed?> = appLock.screenshots.allowed

    private val _state = MutableStateFlow(SecurityUiState())
    val state: StateFlow<SecurityUiState> = _state.asStateFlow()

    /**
     * The lock's own state of attempts: [UnlockUi.retryAtElapsed] is when the next passphrase
     * may be tried, on [elapsedNow]'s clock. The dialogs count it down as the lock screen does,
     * from the same value, whichever screen the wrong attempts were made on.
     */
    val unlockUi: StateFlow<UnlockUi> = appLock.unlockUi

    fun elapsedNow(): Long = appLock.elapsedNow()

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
        // An empty entry is not looked at: nothing to check, count or say. The dialog never submits one.
        if (dialog == null || _state.value.busy || passphrase.isEmpty()) {
            passphrase.wipe()
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                when (val check = appLock.checkPassphrase(passphrase)) {
                    is PassphraseCheck.Ok -> perform(dialog.action, check.proof)
                    is PassphraseCheck.Wrong -> _state.update { it.copy(busy = false, error = SecurityError.WRONG) }
                    // The wait is counted down under the field (unlockUi); there is nothing else to say.
                    is PassphraseCheck.Throttled -> _state.update { it.copy(busy = false, error = null) }
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

    /**
     * "Fingerprint works for". Like "Lock after" it needs no passphrase: the app is unlocked, and
     * the choice opens no key. On a failure the picker shows the stored value, as that one does.
     */
    fun setFingerprintPeriod(seconds: Int) {
        viewModelScope.launch {
            try {
                appLock.setFingerprintPeriod(seconds)
                _state.update { it.copy(error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(error = SecurityError.FAILED) }
            }
        }
    }

    /**
     * "Delete messages after". A choice that deletes messages now (a shorter limit, or any limit
     * after Never) is only applied once the user has confirmed it ([confirmMessageLifetime]);
     * any other is applied at once. Choosing what is set already does nothing.
     */
    fun selectMessageLifetime(days: Int) {
        val current = _messageLifetimeDays.value
        if (days == current || days !in LockConfig.MESSAGE_LIFETIME_CHOICES_DAYS || _state.value.busy) return
        if (LockConfig.messageLifetimeDeletesNow(days, current)) open(SecurityDialog.ShortenMessageLifetime(days)) else applyMessageLifetime(days)
    }

    /** The user confirmed [SecurityDialog.ShortenMessageLifetime]. */
    fun confirmMessageLifetime() {
        val dialog = _state.value.dialog as? SecurityDialog.ShortenMessageLifetime ?: return
        _state.value = SecurityUiState()
        applyMessageLifetime(dialog.toDays)
    }

    /**
     * Writes the readable copy in the lock configuration first, then the setting itself, which
     * also deletes what has expired under it. When the copy cannot be written nothing changes and
     * the picker keeps showing the stored value, as the other choices do. (Should the setting
     * then fail to reach the disk, the next unlock writes the copy back from it.)
     */
    private fun applyMessageLifetime(days: Int) {
        viewModelScope.launch {
            try {
                appLock.setMessageLifetimeDays(days)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(error = SecurityError.FAILED) }
                return@launch
            }
            lifetimeSetting.changeMessageLifetimeDays(days)
            _messageLifetimeDays.value = days
            _state.update { it.copy(error = null) }
        }
    }

    /**
     * "Background maintenance". Like the other settings the lock needs it asks for no passphrase:
     * it opens no key. The switch shows what is stored, so a failure leaves it as it was.
     */
    fun setBackgroundMaintenance(enabled: Boolean) {
        viewModelScope.launch {
            try {
                appLock.setBackgroundMaintenance(enabled)
                _state.update { it.copy(error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(error = SecurityError.FAILED) }
            }
        }
    }

    /** "Finish the answer after locking". The switch shows what is stored, so a failure leaves it as it was. */
    fun setFinishAnswerAfterLocking(enabled: Boolean) {
        viewModelScope.launch {
            try {
                appLock.setFinishAnswerAfterLocking(enabled)
                _state.update { it.copy(error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(error = SecurityError.FAILED) }
            }
        }
    }
}

/**
 * The rows of the Security section: change passphrase, fingerprint unlock and how long the
 * fingerprint works, the background timeout, how long messages are kept and the background
 * maintenance that deletes them while the app is not used, finishing an answer and keeping the
 * model's memory across a lock, "Lock now", the
 * temporary screenshot allowance and "Reset app data", with their dialogs. It emits its rows into the column of the Settings screen it is placed in (which
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
    FingerprintPeriodPicker(status.fingerprintPeriodSeconds, viewModel::setFingerprintPeriod)
    TimeoutPicker(status.lockTimeoutSeconds, viewModel::setLockTimeout)
    val messageLifetimeDays by viewModel.messageLifetimeDays.collectAsStateWithLifecycle()
    MessageLifetimePicker(messageLifetimeDays, viewModel::selectMessageLifetime)
    SwitchRow(
        title = stringResource(R.string.security_background_maintenance),
        support = stringResource(R.string.security_background_maintenance_support),
        checked = status.backgroundMaintenance,
        onCheckedChange = viewModel::setBackgroundMaintenance,
    )
    SwitchRow(
        title = stringResource(R.string.security_finish_answer),
        support = pluralStringResource(
            if (status.finishAnswerAfterLocking) R.plurals.security_finish_answer_on else R.plurals.security_finish_answer_off,
            AppLock.LEASE_MINUTES,
            AppLock.LEASE_MINUTES,
        ),
        checked = status.finishAnswerAfterLocking,
        onCheckedChange = viewModel::setFinishAnswerAfterLocking,
    )
    val keepModelMemory by viewModel.keepModelMemory.collectAsStateWithLifecycle()
    SwitchRow(
        title = stringResource(R.string.security_keep_memory),
        support = when {
            !viewModel.modelMemorySupported -> stringResource(R.string.security_keep_memory_unavailable)
            keepModelMemory -> pluralStringResource(
                R.plurals.security_keep_memory_on,
                SessionClose.KEEPING_WAIT_SECONDS,
                SessionClose.KEEPING_WAIT_SECONDS,
            )
            else -> stringResource(R.string.security_keep_memory_off)
        },
        checked = viewModel.modelMemorySupported && keepModelMemory,
        enabled = viewModel.modelMemorySupported,
        onCheckedChange = viewModel::setKeepModelMemory,
    )
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
            retryAtElapsed = viewModel.unlockUi.collectAsStateWithLifecycle().value.retryAtElapsed,
            elapsedNow = viewModel::elapsedNow,
            onSubmit = viewModel::submitCurrentPassphrase,
            onDismiss = viewModel::dismissDialog,
        )
        SecurityDialog.Reset -> ResetDataDialog(onConfirm = viewModel::confirmReset, onDismiss = viewModel::dismissDialog)
        is SecurityDialog.ShortenMessageLifetime -> ShortenMessageLifetimeDialog(
            toDays = dialog.toDays,
            onConfirm = viewModel::confirmMessageLifetime,
            onDismiss = viewModel::dismissDialog,
        )
        null -> Unit
    }
}

@Composable
internal fun errorText(error: SecurityError?): String = when (error) {
    SecurityError.MISMATCH -> stringResource(R.string.passphrase_mismatch)
    SecurityError.TOO_SHORT -> stringResource(R.string.passphrase_too_short)
    SecurityError.TOO_LONG -> stringResource(R.string.passphrase_too_long)
    SecurityError.DIGITS_ONLY -> stringResource(R.string.passphrase_digits_only)
    SecurityError.WRONG -> stringResource(R.string.lock_wrong)
    SecurityError.EXPIRED -> stringResource(R.string.passphrase_expired)
    SecurityError.FAILED -> stringResource(R.string.security_failed)
    SecurityError.SCREENSHOTS_NOT_ALLOWED -> stringResource(R.string.security_screenshots_failed)
    null -> ""
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

/** What each choice of how long messages are kept ([LockConfig.MESSAGE_LIFETIME_CHOICES_DAYS]) is called. */
@Composable
private fun messageLifetimeLabel(days: Int): String = stringResource(
    when (days) {
        1 -> R.string.message_lifetime_1_day
        7 -> R.string.message_lifetime_7_days
        30 -> R.string.message_lifetime_30_days
        90 -> R.string.message_lifetime_90_days
        else -> R.string.message_lifetime_never
    },
)

@Composable
private fun MessageLifetimePicker(days: Int, onSelect: (Int) -> Unit) {
    val choices = LockConfig.MESSAGE_LIFETIME_CHOICES_DAYS
    ChoicePicker(
        label = stringResource(R.string.security_message_lifetime),
        support = stringResource(R.string.security_message_lifetime_support),
        labels = choices.associateWith { messageLifetimeLabel(it) },
        choices = choices,
        value = days,
        onSelect = onSelect,
    )
}

/**
 * Asked before a limit that deletes messages now. The text names the new limit; nothing is
 * deleted until "Delete" is pressed, and Cancel leaves the setting as it was.
 */
@Composable
private fun ShortenMessageLifetimeDialog(toDays: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.message_lifetime_confirm_title)) },
        text = {
            ProtectWindow()
            Text(stringResource(R.string.message_lifetime_confirm_text, messageLifetimeLabel(toDays)))
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.delete)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun TimeoutPicker(seconds: Int, onSelect: (Int) -> Unit) {
    ChoicePicker(
        label = stringResource(R.string.security_lock_after),
        support = stringResource(R.string.security_lock_after_support),
        labels = mapOf(
            0 to stringResource(R.string.timeout_immediately),
            60 to stringResource(R.string.timeout_1_minute),
            300 to stringResource(R.string.timeout_5_minutes),
            900 to stringResource(R.string.timeout_15_minutes),
        ),
        choices = LockConfig.TIMEOUT_CHOICES_SECONDS,
        value = seconds,
        onSelect = onSelect,
    )
}

@Composable
private fun FingerprintPeriodPicker(seconds: Int, onSelect: (Int) -> Unit) {
    ChoicePicker(
        label = stringResource(R.string.security_fingerprint_period),
        support = stringResource(R.string.security_fingerprint_period_support),
        labels = mapOf(
            15 * 60 to stringResource(R.string.fingerprint_period_15_minutes),
            60 * 60 to stringResource(R.string.fingerprint_period_1_hour),
            8 * 60 * 60 to stringResource(R.string.fingerprint_period_8_hours),
            24 * 60 * 60 to stringResource(R.string.fingerprint_period_24_hours),
        ),
        choices = LockConfig.FINGERPRINT_PERIOD_CHOICES_SECONDS,
        value = seconds,
        onSelect = onSelect,
    )
}

/** A choice among [choices] (numbers such as seconds or days), each shown by its entry in [labels]; [value] is the one chosen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoicePicker(label: String, support: String, labels: Map<Int, String>, choices: List<Int>, value: Int, onSelect: (Int) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = labels[value].orEmpty(),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            supportingText = { Text(support) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // The menu is a window of its own, and what is chosen in it is part of how the app is locked.
            ProtectWindow()
            for (choice in choices) {
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
