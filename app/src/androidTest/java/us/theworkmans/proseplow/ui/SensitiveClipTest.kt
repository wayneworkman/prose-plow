package us.theworkmans.proseplow.ui

import android.content.ClipDescription
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** What the app puts on the clipboard: plain text, marked so that the system hides its preview. */
@RunWith(AndroidJUnit4::class)
class SensitiveClipTest {

    @Test
    fun aClipCarriesItsTextAndTheSensitiveMark() {
        val clip = sensitiveClip("Prose Plow", "text from a chat")
        assertEquals(1, clip.itemCount)
        assertEquals("text from a chat", clip.getItemAt(0).text.toString())
        assertEquals("Prose Plow", clip.description.label.toString())
        assertTrue(clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN))
        assertTrue(clip.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            assertTrue("under the platform's own name for it", clip.description.extras!!.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
        }
    }
}
