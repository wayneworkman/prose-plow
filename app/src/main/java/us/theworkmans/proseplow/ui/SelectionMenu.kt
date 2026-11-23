package us.theworkmans.proseplow.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * What the menu of a text selection may offer in this app: the four things that stay inside it
 * or go to the clipboard, and nothing else.
 *
 * Left to itself the menu also lists what other apps have registered for selected text
 * ("Translate", "Search" and the like), which hand the selection to that app, the actions the
 * system's text classifier suggests for it, and autofill. Chat text should not leave the app by
 * a tap on a menu entry that another app put there, so the menu is held to a list of what is
 * known, not cleared of a list of what is unwanted: an entry of a kind that a later version of
 * the text library adds is dropped until it is looked at.
 */
object SelectionMenu {
    fun keeps(key: Any?): Boolean =
        key == TextContextMenuKeys.CopyKey ||
            key == TextContextMenuKeys.SelectAllKey ||
            key == TextContextMenuKeys.CutKey ||
            key == TextContextMenuKeys.PasteKey
}

/**
 * Holds the selection menu of every text and text field laid out inside the element this is
 * put on to [SelectionMenu].
 */
fun Modifier.plainSelectionMenu(): Modifier = filterTextContextMenuComponents { SelectionMenu.keeps(it.key) }

/**
 * Holds the selection menu of every text and text field in [content] to [SelectionMenu].
 * MainActivity wraps the whole app in it, once.
 *
 * It reaches what is laid out inside it. A dialog is laid out in a window of its own and is not
 * reached: a dialog that shows a text field or text that can be selected puts
 * [plainSelectionMenu] on its own content (a test that reads the sources holds every such
 * dialog to it).
 */
@Composable
fun PlainSelectionMenu(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().plainSelectionMenu(), propagateMinConstraints = true) {
        content()
    }
}
