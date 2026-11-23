package us.theworkmans.proseplow.ui

import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.inputText
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType

/**
 * Asks the keyboard not to learn from what is typed in any text field inside [content]
 * (`EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING`, "incognito mode" in Gboard and others).
 * Compose has no keyboard option for this flag (its `PlatformImeOptions` only sets
 * `privateImeOptions`, which keyboards do not read as this flag), so the flag is added to the
 * EditorInfo when a text field starts its input session. Keyboards may ignore the request.
 *
 * This is not a setting: MainActivity wraps the whole app in it, once, and nothing turns it off.
 * Dialogs and menus are composed inside the content that opens them, so their fields are covered
 * as well.
 */
@Composable
fun NoPersonalizedLearning(content: @Composable () -> Unit) {
    val interceptor = remember {
        PlatformTextInputInterceptor { request, nextHandler ->
            nextHandler.startInputMethod(
                object : PlatformTextInputMethodRequest {
                    override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                        val connection = request.createInputConnection(outAttributes)
                        outAttributes.imeOptions = outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                        return connection
                    }
                },
            )
        }
    }
    InterceptPlatformTextInput(interceptor, content)
}

/**
 * A passphrase field: obscured, password keyboard, no autocorrect, never learned from by the
 * keyboard (asked for here as well, so the field does not depend on where it is composed), kept
 * from autofill, and its [state] is not saved in the instance state: callers make it with
 * `remember { TextFieldState() }`, never `rememberSaveable`.
 *
 * Every character is a bullet from the moment it is typed. The text field's own default shows
 * the last typed character for a moment, which is enough for a camera, a screen recording or
 * someone watching to read a passphrase one character at a time; so the fully hidden mode is
 * asked for explicitly, and there is no way to reveal what was typed.
 *
 * A passphrase field is only ever shown where the screen cannot be captured: on the set-up and
 * lock screens, whose window is always secure, or in a PassphraseDialog (the lock package),
 * which is secure by itself. A test that reads the sources holds every use to that.
 *
 * What it tells the accessibility framework is obscured as well. The secure text field draws
 * bullets, but on its own it describes itself to that framework with the text that was typed
 * (marked as a password), so any accessibility service the user has enabled could read the
 * passphrase while it is in the field. A platform password field only hands out bullets, and so
 * does this one: its description is replaced by [obscuredForAccessibility]. A screen reader
 * still announces a password field and how much is in it.
 */
@Composable
fun PassphraseField(
    state: TextFieldState,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
    imeAction: ImeAction = ImeAction.Done,
    /** What the keyboard's action key does; null leaves it to the keyboard (Next moves on, Done closes it). */
    onAction: (() -> Unit)? = null,
) {
    ProtectWindow()
    NoPersonalizedLearning {
        OutlinedSecureTextField(
            state = state,
            textObfuscationMode = TextObfuscationMode.Hidden,
            // The outermost semantics of the field's own node, so these values replace the ones
            // the field sets itself (the typed text, under both names it is published as).
            modifier = modifier.semantics {
                val obscured = obscuredForAccessibility(state.text.length)
                editableText = obscured
                inputText = obscured
            },
            enabled = enabled,
            label = { Text(label) },
            isError = isError,
            supportingText = supportingText?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
                imeAction = imeAction,
            ),
            onKeyboardAction = KeyboardActionHandler { performDefaultAction ->
                if (onAction != null) onAction() else performDefaultAction()
            },
        )
    }
}

/**
 * What a passphrase field tells the accessibility framework in place of its content: one bullet
 * for each of its [length] characters, never the characters themselves.
 */
internal fun obscuredForAccessibility(length: Int): AnnotatedString = AnnotatedString("\u2022".repeat(length))

/**
 * Gives the window this is composed in the two protections the activity's own window gets in
 * MainActivity. A Compose dialog is a separate window with its own view tree, so every dialog
 * with a text field, or with a button that must not be pressed by a trick, calls this.
 *
 * - Autofill services are kept away. It sets `IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS` on
 *   the window's root view and on the parent of the Compose view: the platform then treats every
 *   view below as not important for autofill (`View.isImportantForAutofill()` checks the
 *   ancestors first), whatever Compose sets on its own view, and leaves it out of the structure
 *   given to an autofill service.
 * - Touches that arrive while another app's window covers the place that was touched are
 *   discarded (`filterTouchesWhenObscured`), so an overlay drawn over the dialog cannot make
 *   the user press something in it without seeing it.
 */
@Composable
fun ProtectWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        (view.parent as? View)?.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        view.rootView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        view.rootView.filterTouchesWhenObscured = true
        view.filterTouchesWhenObscured = true
        onDispose { }
    }
}

/** Copies the field's text into a new array (no String) and clears the field. The caller zeroes the array. */
fun TextFieldState.takeChars(): CharArray {
    val text = this.text
    val chars = CharArray(text.length) { text[it] }
    clearText()
    return chars
}
