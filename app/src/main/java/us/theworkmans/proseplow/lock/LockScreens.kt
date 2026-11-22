package us.theworkmans.proseplow.lock

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import us.theworkmans.proseplow.R
import us.theworkmans.proseplow.security.AppLock
import us.theworkmans.proseplow.security.FingerprintOffer
import us.theworkmans.proseplow.security.LockState
import us.theworkmans.proseplow.security.PassphraseProblem
import us.theworkmans.proseplow.security.ResetReport
import us.theworkmans.proseplow.security.UnrecoverableReason
import us.theworkmans.proseplow.ui.PassphraseField
import us.theworkmans.proseplow.ui.takeChars

/**
 * Replaces all content while the app is not unlocked: first-run set-up, the lock screen, or why
 * the data cannot be opened. Nothing of the chats or settings is composed.
 */
@Composable
fun LockedContent(appLock: AppLock<*>, state: LockState<*>) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (state) {
                LockState.Starting -> {
                    val starting = stringResource(R.string.lock_starting)
                    CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = starting })
                }
                LockState.SetupRequired -> SetupScreen(appLock)
                is LockState.Locked -> LockScreen(appLock, state)
                is LockState.Unrecoverable -> UnrecoverableScreen(appLock, state.reason)
                is LockState.Failed -> FailedScreen(appLock, state.detail)
                is LockState.Unlocked<*> -> Unit
            }
        }
    }
    // A reset that left something behind says so, on whichever of these screens came after it.
    val resetFailure by appLock.resetFailure.collectAsStateWithLifecycle()
    resetFailure?.let { ResetIncompleteDialog(it, onDismiss = appLock::resetFailureShown) }
}

/** First run, and after every reset: the passphrase is created here, before anything is stored. */
@Composable
private fun SetupScreen(appLock: AppLock<*>) {
    val ui by appLock.setupUi.collectAsStateWithLifecycle()
    val first = remember { TextFieldState() }
    val second = remember { TextFieldState() }
    val submit = {
        if (!ui.busy) appLock.setUp(first.takeChars(), second.takeChars())
    }

    Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineSmall)
    Text(
        stringResource(R.string.setup_message, stringResource(R.string.app_name)),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        stringResource(R.string.setup_no_recovery),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        stringResource(R.string.passphrase_rules),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
    PassphraseField(
        state = first,
        label = stringResource(R.string.passphrase_new),
        enabled = !ui.busy,
        isError = ui.problem != null,
        modifier = Modifier.fillMaxWidth(),
        imeAction = ImeAction.Next,
    )
    PassphraseField(
        state = second,
        label = stringResource(R.string.passphrase_repeat),
        enabled = !ui.busy,
        isError = ui.problem != null,
        modifier = Modifier.fillMaxWidth(),
        imeAction = ImeAction.Done,
        onAction = submit,
    )
    Column(
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ui.problem?.let { Text(passphraseProblemText(it), color = MaterialTheme.colorScheme.error) }
        ui.error?.let { Text(stringResource(R.string.error_detail, it), color = MaterialTheme.colorScheme.error) }
    }
    if (ui.busy) Working(stringResource(R.string.passphrase_working))
    Button(onClick = submit, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.setup_create))
    }
}

@Composable
private fun LockScreen(appLock: AppLock<*>, state: LockState.Locked) {
    val ui by appLock.unlockUi.collectAsStateWithLifecycle()
    val status by appLock.status.collectAsStateWithLifecycle()
    val field = remember { TextFieldState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // The fingerprint prompt, once per lock, as soon as the app is in front.
    LaunchedEffect(state.generation, status.biometricEnabled) {
        if (status.biometricEnabled) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { appLock.autoPromptBiometric(state.generation) }
        }
    }
    val now by produceState(appLock.elapsedNow(), ui.retryAtElapsed) {
        value = appLock.elapsedNow()
        while (value < ui.retryAtElapsed) {
            delay(500)
            value = appLock.elapsedNow()
        }
    }
    val waitMillis = (ui.retryAtElapsed - now).coerceAtLeast(0)
    val canSubmit = !ui.busy && waitMillis == 0L
    val submit = {
        if (canSubmit && field.text.isNotEmpty()) appLock.unlockWithPassphrase(field.takeChars())
    }

    Text(stringResource(R.string.lock_title, stringResource(R.string.app_name)), style = MaterialTheme.typography.headlineSmall)
    PassphraseField(
        state = field,
        label = stringResource(R.string.lock_passphrase),
        enabled = !ui.busy,
        isError = ui.wrongPassphrase,
        modifier = Modifier.fillMaxWidth(),
        imeAction = ImeAction.Done,
        onAction = submit,
    )
    Column(
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (ui.wrongPassphrase) Text(stringResource(R.string.lock_wrong), color = MaterialTheme.colorScheme.error)
        ui.rejected?.let { Text(passphraseProblemText(it), color = MaterialTheme.colorScheme.error) }
        if (waitMillis > 0) {
            Text(
                stringResource(R.string.lock_retry_in, DateUtils.formatElapsedTime((waitMillis + 999) / 1000)),
                color = MaterialTheme.colorScheme.error,
            )
        }
        ui.error?.let { Text(stringResource(R.string.error_detail, it), color = MaterialTheme.colorScheme.error) }
    }
    if (ui.busy) Working(stringResource(R.string.lock_checking))
    Button(onClick = submit, enabled = canSubmit, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.lock_unlock))
    }
    if (status.biometricEnabled) {
        OutlinedButton(onClick = appLock::requestBiometricUnlock, enabled = !ui.busy && !ui.biometricPromptShowing, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.use_fingerprint))
        }
    }
    // The way on for a forgotten passphrase: it needs no passphrase, only the typed confirmation.
    Spacer(Modifier.height(24.dp))
    ResetButton(appLock)
}

@Composable
private fun UnrecoverableScreen(appLock: AppLock<*>, reason: UnrecoverableReason) {
    Text(stringResource(R.string.unrecoverable_title), style = MaterialTheme.typography.headlineSmall)
    Text(
        stringResource(
            when (reason) {
                UnrecoverableReason.CONFIG_UNREADABLE -> R.string.unrecoverable_config_unreadable
                UnrecoverableReason.CONFIG_MISSING -> R.string.unrecoverable_config_missing
                UnrecoverableReason.KEY_LOST -> R.string.unrecoverable_key_lost
            },
        ),
    )
    Text(stringResource(R.string.unrecoverable_hint))
    OutlinedButton(onClick = appLock::retry) { Text(stringResource(R.string.try_again)) }
    ResetButton(appLock)
}

@Composable
private fun FailedScreen(appLock: AppLock<*>, detail: String) {
    Text(stringResource(R.string.failed_title), style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.failed_hint))
    Text(
        stringResource(R.string.failed_detail, detail),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = appLock::retry) { Text(stringResource(R.string.try_again)) }
    // For example a key store that keeps failing to load: the reset is the only way on then.
    Spacer(Modifier.height(24.dp))
    ResetButton(appLock)
}

/** "Reset app data" on the screens shown while the app is not unlocked; see [ResetDataDialog]. */
@Composable
private fun ResetButton(appLock: AppLock<*>) {
    var asking by rememberSaveable { mutableStateOf(false) }
    TextButton(
        onClick = { asking = true },
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) { Text(stringResource(R.string.reset_action)) }

    if (asking) {
        ResetDataDialog(
            onConfirm = {
                asking = false
                appLock.reset()
            },
            onDismiss = { asking = false },
        )
    }
}

/** What a reset could not delete. The start after it has already put the app where that leaves it. */
@Composable
private fun ResetIncompleteDialog(report: ResetReport, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reset_incomplete_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!report.keysDeleted) Text(stringResource(R.string.reset_incomplete_keys))
                if (!report.dataDeleted) Text(stringResource(R.string.reset_incomplete_data))
                if (!report.configDeleted) Text(stringResource(R.string.reset_incomplete_config))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}

/**
 * After an unlock: fingerprint unlock can be set up with the passphrase that was just entered
 * (right after the passphrase was created, or after its key was invalidated).
 */
@Composable
fun FingerprintOfferDialog(offer: FingerprintOffer, onAccept: () -> Unit, onDecline: () -> Unit) {
    val first = offer == FingerprintOffer.FIRST_SETUP
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text(stringResource(if (first) R.string.offer_fingerprint_title else R.string.offer_fingerprint_again_title)) },
        text = { Text(stringResource(if (first) R.string.offer_fingerprint_message else R.string.offer_fingerprint_again_message)) },
        confirmButton = { TextButton(onClick = onAccept) { Text(stringResource(if (first) R.string.use_fingerprint else R.string.turn_on)) } },
        dismissButton = { TextButton(onClick = onDecline) { Text(stringResource(R.string.not_now)) } },
    )
}

/** Why a passphrase was not accepted, in words. */
@Composable
internal fun passphraseProblemText(problem: PassphraseProblem): String = stringResource(
    when (problem) {
        PassphraseProblem.EMPTY -> R.string.passphrase_empty
        PassphraseProblem.TOO_SHORT -> R.string.passphrase_too_short
        PassphraseProblem.TOO_LONG -> R.string.passphrase_too_long
        PassphraseProblem.DIGITS_ONLY -> R.string.passphrase_digits_only
        PassphraseProblem.MISMATCH -> R.string.passphrase_mismatch
    },
)

/** A small spinner with what is being waited for. */
@Composable
internal fun Working(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
