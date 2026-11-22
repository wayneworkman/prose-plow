package us.theworkmans.proseplow.ui

import android.content.ClipData
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import us.theworkmans.proseplow.R

/**
 * `ClipDescription.EXTRA_IS_SENSITIVE`. The constant only exists from API 33; its value is
 * documented for use on older versions, where it is ignored.
 */
private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

/**
 * A plain-text clip marked as sensitive: Android 13 and later then hide its content in the
 * clipboard preview they show after a copy. Everything copied out of this app is text from a
 * chat that is otherwise only stored encrypted, so every clip is marked.
 *
 * The mark only hides the preview. The clipboard itself is outside the app: whatever is copied
 * can be read by the keyboard and by the app it is pasted into, and stays until it is replaced.
 */
fun sensitiveClip(label: String, text: String): ClipData {
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
    return clip
}

/**
 * Returns a function that copies text to the clipboard, as a [sensitiveClip]. On API 33+ the
 * system shows its own confirmation, so the toast ([confirmation]) is only shown on older
 * versions. A copy that the system refuses (a text too large to hand over, a clipboard service
 * that is not there) is said in a toast instead of crashing the app.
 */
@Composable
fun rememberCopyToClipboard(): (text: String, confirmation: String) -> Unit {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val label = stringResource(R.string.app_name)
    val failure = stringResource(R.string.copy_failed)
    val scope = rememberCoroutineScope()
    return remember(clipboard, context, label, failure, scope) {
        { text, confirmation ->
            scope.launch {
                val copied = try {
                    clipboard.setClipEntry(ClipEntry(sensitiveClip(label, text)))
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
                if (!copied) {
                    Toast.makeText(context, failure, Toast.LENGTH_SHORT).show()
                } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(context, confirmation, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
