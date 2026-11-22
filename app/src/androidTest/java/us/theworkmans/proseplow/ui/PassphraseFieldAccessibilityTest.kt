package us.theworkmans.proseplow.ui

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.text.input.TextFieldState
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import us.theworkmans.proseplow.DeviceTests

/**
 * What a passphrase field hands to the accessibility framework, read here exactly as an
 * accessibility service reads it (through the instrumentation's UiAutomation, which is one).
 * Left to itself the secure text field publishes the typed text; [PassphraseField] must replace
 * it with bullets. This test fails the moment that replacement is gone.
 */
@RunWith(AndroidJUnit4::class)
class PassphraseFieldAccessibilityTest {

    private val passphrase = "correct horse battery"

    private fun nodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        listOf(root) + (0 until root.childCount).mapNotNull(root::getChild).flatMap(::nodes)

    /** The window's nodes once the passphrase field is among them (the activity takes a moment to show). */
    private fun nodesWithThePassphraseField(): List<AccessibilityNodeInfo> {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            val all = automation.rootInActiveWindow?.let(::nodes).orEmpty()
            if (all.any { it.isPassword }) return all
            SystemClock.sleep(200)
        }
        return emptyList()
    }

    @Test
    fun anAccessibilityServiceGetsBulletsAndNeverTheTypedPassphrase() {
        DeviceTests.requireDevelopmentPackage()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            val state = TextFieldState(passphrase)
            scenario.onActivity { activity ->
                activity.setContent { PassphraseField(state = state, label = "Passphrase") }
            }
            val all = nodesWithThePassphraseField()
            val field = all.singleOrNull { it.isPassword }
            assertNotNull("the passphrase field is in the accessibility tree, marked as a password", field)
            assertEquals("one bullet for each character", "•".repeat(passphrase.length), field!!.text.toString())
            assertTrue("it is still an editable field to a screen reader", field.isEditable)

            for (node in all) {
                for (said in listOf(node.text, node.contentDescription, node.hintText, node.stateDescription)) {
                    assertFalse("a node hands out the passphrase: $said", said?.toString().orEmpty().contains("horse"))
                }
            }
            assertEquals("the field itself still holds what was typed", passphrase, state.text.toString())
        }
    }
}
