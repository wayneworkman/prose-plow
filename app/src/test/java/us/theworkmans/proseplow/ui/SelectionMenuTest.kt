package us.theworkmans.proseplow.ui

import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the menu of a text selection may offer: the four entries that are known, and nothing else. */
class SelectionMenuTest {

    @Test
    fun copySelectAllCutAndPasteStay() {
        assertTrue(SelectionMenu.keeps(TextContextMenuKeys.CopyKey))
        assertTrue(SelectionMenu.keeps(TextContextMenuKeys.SelectAllKey))
        assertTrue(SelectionMenu.keeps(TextContextMenuKeys.CutKey))
        assertTrue(SelectionMenu.keeps(TextContextMenuKeys.PasteKey))
    }

    @Test
    fun everythingElseGoes() {
        assertFalse("autofill", SelectionMenu.keeps(TextContextMenuKeys.AutofillKey))
        // What another app registered for selected text, a suggestion of the text classifier, or
        // an entry of a kind a later version of the text library adds: none of them is on the list.
        assertFalse(SelectionMenu.keeps(Any()))
        assertFalse(SelectionMenu.keeps("Translate"))
        assertFalse(SelectionMenu.keeps(0))
        assertFalse(SelectionMenu.keeps(null))
    }

    @Test
    fun theFourKeysAreFourDifferentThings() {
        val keys = listOf(TextContextMenuKeys.CopyKey, TextContextMenuKeys.SelectAllKey, TextContextMenuKeys.CutKey, TextContextMenuKeys.PasteKey, TextContextMenuKeys.AutofillKey)
        assertTrue("the list would let autofill through if two keys were one object", keys.toSet().size == keys.size)
    }
}
