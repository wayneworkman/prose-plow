package us.theworkmans.proseplow.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import us.theworkmans.proseplow.R
import us.theworkmans.proseplow.ui.ExcludeWindowFromAutofill

/** The typed confirmation of a reset. */
object ResetConfirmation {
    /** True if [typed] is [word], give or take capitals and blanks around it. */
    fun matches(typed: String, word: String): Boolean = typed.trim().equals(word, ignoreCase = true)
}

/**
 * The one confirmation every "Reset app data" goes through, wherever it is offered (the lock
 * screen, the error screens, Settings): what will be deleted, that it cannot be undone, and a
 * field in which the confirmation word must be typed before the destructive button is enabled.
 * It asks for no passphrase, because it is also the way on when the passphrase is forgotten.
 * The caller does the reset in [onConfirm] and takes the dialog away in both callbacks.
 */
@Composable
fun ResetDataDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val word = stringResource(R.string.reset_word)
    // Not saved: after a rotation the word has to be typed again.
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reset_title)) },
        text = {
            ExcludeWindowFromAutofill()
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.reset_message, stringResource(R.string.app_name)))
                Text(stringResource(R.string.reset_confirm_message, word))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.reset_confirm_label)) },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = ResetConfirmation.matches(typed, word),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.reset_confirm_button)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
