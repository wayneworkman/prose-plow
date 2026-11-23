package us.theworkmans.proseplow.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.PersistableBundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.AndroidClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import us.theworkmans.proseplow.DeviceTests
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The clipboard every part of the app is given: whatever is copied through it reaches the
 * platform's clipboard marked as sensitive, including a clip that was made without the mark,
 * as the text library makes them for Copy and Cut in a selection menu.
 *
 * The platform's clipboard is replaced by a recording one in the first two tests, so they put
 * nothing on the phone's real clipboard. The third looks at the composition itself: what a
 * selectable text inside [MarkCopiedTextSensitive] is given to copy with.
 */
@RunWith(AndroidJUnit4::class)
class SensitiveClipboardTest {

    private val sensitive = "android.content.extra.IS_SENSITIVE"

    /** Stands for the platform's clipboard and keeps what it was given. */
    private class RecordingClipboard(private val manager: ClipboardManager) : AndroidClipboard {
        val entries = ArrayList<ClipEntry?>()

        override val clipboardManager: ClipboardManager get() = manager

        override suspend fun getClipEntry(): ClipEntry? = entries.lastOrNull()

        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            entries += clipEntry
        }
    }

    private fun manager(): ClipboardManager =
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(ClipboardManager::class.java)

    @Test
    fun aClipMadeWithoutTheMarkLeavesWithIt() = runBlocking {
        DeviceTests.requireDevelopmentPackage()
        val platform = RecordingClipboard(manager())
        val clipboard = SensitiveClipboard(platform)

        // Exactly what the text library builds for Copy in a selection menu: plain text, no extras.
        val plain = ClipData.newPlainText("plain text", "selected in a chat")
        assertNull(plain.description.extras)
        clipboard.setClipEntry(ClipEntry(plain))

        val out = platform.entries.single()!!.clipData
        assertEquals("selected in a chat", out.getItemAt(0).text.toString())
        assertEquals("plain text", out.description.label.toString())
        assertTrue("the mark was added on the way out", out.description.extras!!.getBoolean(sensitive))
    }

    @Test
    fun whatAClipAlreadyCarriesIsKeptAndClearingStaysClearing() = runBlocking {
        DeviceTests.requireDevelopmentPackage()
        val platform = RecordingClipboard(manager())
        val clipboard = SensitiveClipboard(platform)

        val withExtras = ClipData.newPlainText("label", "text")
        withExtras.description.extras = PersistableBundle().apply { putString("another", "extra") }
        clipboard.setClipEntry(ClipEntry(withExtras))
        val extras = platform.entries.last()!!.clipData.description.extras!!
        assertEquals("extra", extras.getString("another"))
        assertTrue(extras.getBoolean(sensitive))

        // The app's own clips carry the mark already; it stays, once.
        clipboard.setClipEntry(ClipEntry(sensitiveClip("Prose Plow", "from a copy button")))
        assertTrue(platform.entries.last()!!.clipData.description.extras!!.getBoolean(sensitive))

        clipboard.setClipEntry(null)
        assertNull("clearing the clipboard is passed on as it is", platform.entries.last())
        assertEquals(3, platform.entries.size)
        assertSame("the text library is given the system's own manager to ask whether something can be pasted", platform.clipboardManager, clipboard.clipboardManager)
    }

    @Test
    fun everythingComposedInsideIsGivenTheMarkingClipboard() {
        DeviceTests.requireDevelopmentPackage()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            val composed = CountDownLatch(1)
            var outside: Clipboard? = null
            var inside: Clipboard? = null
            var older: Any? = null
            scenario.onActivity { activity ->
                activity.setContent {
                    outside = LocalClipboard.current
                    MarkCopiedTextSensitive {
                        inside = LocalClipboard.current
                        @Suppress("DEPRECATION") // The older interface is replaced as well; that is what is looked at.
                        older = LocalClipboardManager.current
                        // What a chat message is: text that can be selected, and copied from its menu.
                        SelectionContainer { Text("text from a chat") }
                        LaunchedEffect(Unit) { composed.countDown() }
                    }
                }
            }
            assertTrue("the content was composed", composed.await(15, TimeUnit.SECONDS))
            assertNotNull(outside)
            assertTrue("the platform's own clipboard marks nothing", outside !is SensitiveClipboard)
            assertTrue("inside, the clipboard is the one that marks every clip", inside is SensitiveClipboard)
            assertTrue("and it is still the platform's kind, which the text library insists on", inside is AndroidClipboard)
            assertTrue("the older interface is replaced too", older!!.javaClass.name.endsWith("SensitiveClipboardManager"))
        }
    }

    @Test
    fun theOlderInterfaceMarksItsClipsToo() {
        DeviceTests.requireDevelopmentPackage()
        // The label and the text only: nothing is put on the phone's clipboard here either.
        val clip = sensitiveClip("Prose Plow", AnnotatedString("copied the old way").text)
        assertTrue(clip.description.extras!!.getBoolean(sensitive))
        assertEquals("copied the old way", clip.getItemAt(0).text.toString())
    }
}
