package us.theworkmans.proseplow.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import us.theworkmans.proseplow.R
import us.theworkmans.proseplow.ui.PassphraseField
import us.theworkmans.proseplow.ui.ProtectWindow
import us.theworkmans.proseplow.ui.plainSelectionMenu
import us.theworkmans.proseplow.ui.takeChars

/**
 * The frame of every dialog in which a passphrase is typed while the app is unlocked, and the
 * only place such a dialog is made.
 *
 * Wherever a passphrase is entered or changed, the screen cannot be captured, whatever the
 * screenshot allowance says. On the set-up and lock screens the activity's window sees to that:
 * it is secure whenever the app is not unlocked. A dialog in the unlocked app is a window of its
 * own, which would otherwise take over what the activity's window is at that moment, and during
 * an allowance that is "not secure". So this dialog does not inherit: it is secure by its own
 * setting, always. It also gets the window protections of [ProtectWindow], and its fields the
 * app's short selection menu.
 *
 * A dialog without a passphrase field (the typed confirmation of a reset, for one) has no
 * reason to be here and follows the window.
 */
@Composable
internal fun PassphraseDialog(
    title: String,
    confirmLabel: String,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    /** False while the confirmation must wait (a wait after wrong attempts). */
    confirmEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(title) },
        text = {
            ProtectWindow()
            Column(modifier = Modifier.plainSelectionMenu(), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = !busy && confirmEnabled) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The new passphrase, twice, after the current one was confirmed. */
@Composable
internal fun NewPassphraseDialog(state: SecurityUiState, onSubmit: (CharArray, CharArray) -> Unit, onDismiss: () -> Unit) {
    val first = remember { TextFieldState() }
    val second = remember { TextFieldState() }
    val submit = { onSubmit(first.takeChars(), second.takeChars()) }
    PassphraseDialog(
        title = stringResource(R.string.change_passphrase_title),
        confirmLabel = stringResource(R.string.passphrase_save),
        busy = state.busy,
        onConfirm = submit,
        onDismiss = onDismiss,
    ) {
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
}

/**
 * The current passphrase, asked for before a protected change (see [SecurityDialog.Confirm]).
 * A wait after wrong attempts ([retryAtElapsed], on [elapsedNow]'s clock) is counted down under
 * the field and holds "Continue" back, exactly as on the lock screen and from the same value.
 */
@Composable
internal fun ConfirmPassphraseDialog(
    message: String,
    state: SecurityUiState,
    retryAtElapsed: Long,
    elapsedNow: () -> Long,
    onSubmit: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    val field = remember { TextFieldState() }
    val waitMillis = rememberWaitMillis(retryAtElapsed, elapsedNow)
    val submit = {
        if (waitMillis == 0L && field.text.isNotEmpty()) onSubmit(field.takeChars())
    }
    PassphraseDialog(
        title = stringResource(R.string.confirm_passphrase_title),
        confirmLabel = stringResource(R.string.passphrase_continue),
        busy = state.busy,
        confirmEnabled = waitMillis == 0L,
        onConfirm = submit,
        onDismiss = onDismiss,
    ) {
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
        RetryCountdown(waitMillis)
        if (state.busy) Working(stringResource(R.string.lock_checking))
    }
}
