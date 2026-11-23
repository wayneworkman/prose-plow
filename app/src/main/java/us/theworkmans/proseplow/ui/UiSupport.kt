package us.theworkmans.proseplow.ui

import android.content.ClipData
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.AndroidClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import us.theworkmans.proseplow.R

/**
 * `ClipDescription.EXTRA_IS_SENSITIVE`. The constant only exists from API 33; its value is
 * documented for use on older versions, where it is ignored.
 */
private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

/**
 * Marks [clip] as sensitive and returns it: Android 13 and later then hide its content in the
 * clipboard preview they show after a copy. Whatever else its description carries is kept.
 *
 * The mark only hides the preview. The clipboard itself is outside the app: whatever is copied
 * can be read by the keyboard and by the app it is pasted into, and stays until it is replaced.
 */
fun markSensitive(clip: ClipData): ClipData {
    val extras = clip.description.extras ?: PersistableBundle()
    extras.putBoolean(EXTRA_IS_SENSITIVE, true)
    clip.description.extras = extras
    return clip
}

/** A plain-text clip that is marked as sensitive from the start; see [markSensitive]. */
fun sensitiveClip(label: String, text: String): ClipData = markSensitive(ClipData.newPlainText(label, text))

/**
 * The clipboard as everything in the app sees it: the platform's, with every clip marked as
 * sensitive on its way out. Everything that can be copied out of this app is text that is
 * otherwise only stored encrypted, so there is no clip that should not be marked.
 *
 * The app's own copy buttons are one way to the clipboard; Copy and Cut in the menu of a text
 * selection or a text field are another, and those build their clips inside the text library,
 * without the mark. Both reach the clipboard through the composition's clipboard, which
 * [MarkCopiedTextSensitive] replaces with this, so no clip can leave without passing here.
 *
 * It is the platform's kind of clipboard ([AndroidClipboard]) and not only a [Clipboard],
 * because the text library asks exactly that kind for the system's clipboard manager when it
 * wants to know whether there is something to paste, and refuses any other. [clipboardManager]
 * is that system object, which marks nothing; the app must not write through it (a test that
 * reads the sources checks that it does not).
 */
// The library marks its clipboard type as meant for tests. It is used here all the same, because
// no other kind is accepted (see above); SensitiveClipboardTest, on a phone, fails when a later
// version of the library changes what the composition provides.
@Suppress("VisibleForTests")
class SensitiveClipboard(private val platform: AndroidClipboard) : AndroidClipboard {
    override val clipboardManager: android.content.ClipboardManager get() = platform.clipboardManager

    override suspend fun getClipEntry(): ClipEntry? = platform.getClipEntry()

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        if (clipEntry != null) markSensitive(clipEntry.clipData)
        platform.setClipEntry(clipEntry)
    }
}

/**
 * The older clipboard interface, which the composition still offers beside the current one.
 * Nothing in the app or in the libraries it is built with copies through it today; it is
 * wrapped all the same, so that a library that still does cannot become the one way around the
 * mark.
 */
@Suppress("DEPRECATION") // The deprecated interface is wrapped precisely because it still exists.
private class SensitiveClipboardManager(private val platform: ClipboardManager, private val label: String) : ClipboardManager {
    override fun setText(annotatedString: AnnotatedString) = platform.setClip(ClipEntry(sensitiveClip(label, annotatedString.text)))

    override fun getText(): AnnotatedString? = platform.getText()

    override fun hasText(): Boolean = platform.hasText()

    override fun getClip(): ClipEntry? = platform.getClip()

    override fun setClip(clipEntry: ClipEntry?) {
        if (clipEntry != null) markSensitive(clipEntry.clipData)
        platform.setClip(clipEntry)
    }

    @Suppress("OVERRIDE_DEPRECATION") // Deprecated with the interface; handed through so nothing that asks is refused.
    override val nativeClipboard: android.content.ClipboardManager get() = platform.nativeClipboard
}

/**
 * Makes every clip that leaves [content] sensitive, however it was made; see
 * [SensitiveClipboard]. MainActivity wraps the whole app in it, once. Dialogs and menus are
 * composed inside the content that opens them, so what is copied in them is covered as well.
 *
 * @throws IllegalStateException when the composition's clipboard is not one that can be
 *   wrapped, which no version of the library this app is built with provides.
 */
// DEPRECATION: LocalClipboardManager, see SensitiveClipboardManager. VisibleForTests: see SensitiveClipboard.
@Suppress("DEPRECATION", "VisibleForTests")
@Composable
fun MarkCopiedTextSensitive(content: @Composable () -> Unit) {
    // Android's composition provides its own kind of clipboard, and only that kind can be
    // wrapped. Should a later version of the library provide another, the app stops here, at
    // its first frame, where that cannot be overlooked: to carry on would be to let clips out
    // without the mark, and nobody would notice.
    val platform = LocalClipboard.current as? AndroidClipboard ?: error("The composition's clipboard is not the platform's own kind")
    val clipboard = remember(platform) { SensitiveClipboard(platform) }
    val olderPlatform = LocalClipboardManager.current
    val label = stringResource(R.string.app_name)
    val olderClipboard: ClipboardManager = remember(olderPlatform, label) { SensitiveClipboardManager(olderPlatform, label) }
    CompositionLocalProvider(LocalClipboard provides clipboard, LocalClipboardManager provides olderClipboard, content = content)
}

/**
 * Returns a function that copies text to the clipboard, as a [sensitiveClip] (and through the
 * app's [SensitiveClipboard], which would mark it in any case). On API 33+ the
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
                    AppToasts.show(context, failure)
                } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    AppToasts.show(context, confirmation)
                }
            }
        }
    }
}
