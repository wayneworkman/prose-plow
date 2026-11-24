package us.theworkmans.proseplow.terms

import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import us.theworkmans.proseplow.core.terms.TermsFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * The screens of the terms flow on a phone, driven the way an accessibility service drives them
 * (through the instrumentation's UiAutomation, which is one): the two buttons of a statement are
 * alike in size, neither responds during the first second, "I agree" on each statement in order
 * reaches the confirmation, "Back" undoes one agreement, "I do not agree" is on every screen,
 * and "Agree and continue" is enabled only once the words are typed.
 *
 * The flow is shown on its own, in an empty activity of the debug build, with nothing of the lock
 * behind it: what is recorded, and leaving the app, are the lock's and are tested on the JVM.
 * It needs no input from a person.
 */
@RunWith(AndroidJUnit4::class)
class TermsFlowOnDeviceTest {

    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    private fun nodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        listOf(root) + (0 until root.childCount).mapNotNull(root::getChild).flatMap(::nodes)

    private fun all(): List<AccessibilityNodeInfo> = automation.rootInActiveWindow?.let(::nodes).orEmpty()

    /**
     * The node that shows [text] (or is described by it), once it is on screen; null after 10
     * seconds. What is scrolled out of sight is not in the accessibility tree, so while nothing
     * matches, a scrollable container is scrolled on by one step before looking again.
     */
    private fun find(text: String): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            val shown = all()
            shown.firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }?.let { return it }
            shown.firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(100)
        }
        return null
    }

    /** The node of [text] that can be pressed: the button around the words. */
    private fun button(text: String): AccessibilityNodeInfo {
        var node = find(text)
        assertNotNull("\"$text\" is on screen", node)
        // A disabled button offers no click action, so it is also known by being clickable at all or disabled.
        while (node != null && node.actionList.none { it.id == AccessibilityNodeInfo.ACTION_CLICK } && node.className?.toString() != "android.widget.Button" &&
            !node.isClickable && node.isEnabled
        ) {
            node = node.parent
        }
        assertNotNull("\"$text\" is a button", node)
        return node!!
    }

    private fun bounds(node: AccessibilityNodeInfo): Rect = Rect().also(node::getBoundsInScreen)

    private fun click(text: String) {
        assertTrue("\"$text\" can be pressed", button(text).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    /** Waits out the second during which a new statement's buttons do not respond. */
    private fun waitForTheButtons() = SystemClock.sleep(1_300)

    @Test
    fun theFlowCanOnlyBeCrossedStatementByStatementAndConfirmedByTypingTheWords() {
        DeviceTests.requireDevelopmentPackage()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            val confirmed = AtomicInteger()
            val declined = AtomicInteger()
            lateinit var flow: TermsFlow
            lateinit var statements: List<String>
            scenario.onActivity { activity ->
                statements = TermsText.statements(activity.resources)
                flow = TermsFlow(statements.size)
                activity.setContent {
                    MaterialTheme {
                        val progress by flow.progress.collectAsState()
                        TermsFlowContent(
                            progress = progress,
                            statements = statements,
                            busy = false,
                            error = null,
                            onAgree = flow::agree,
                            onBack = { flow.back() },
                            onType = flow::type,
                            onConfirm = { confirmed.incrementAndGet() },
                            onDecline = { declined.incrementAndGet() },
                        )
                    }
                }
            }
            val total = statements.size

            // The first statement: its buttons are alike, and do not respond at first.
            assertNotNull(find("Statement 1 of $total"))
            assertNotNull(find(statements[0]))
            val agree = button("I agree")
            val disagree = button("I do not agree")
            assertFalse("I agree does not respond during the first second", agree.isEnabled)
            assertFalse("I do not agree does not respond during the first second", disagree.isEnabled)
            assertEquals("the two buttons have the same width", bounds(agree).width(), bounds(disagree).width())
            assertEquals("and the same height", bounds(agree).height(), bounds(disagree).height())
            assertFalse("and they do not overlap", Rect.intersects(bounds(agree), bounds(disagree)))
            agree.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            SystemClock.sleep(300)
            assertEquals("a press in the first second is ignored", 0, flow.progress.value.step)
            waitForTheButtons()
            assertTrue(button("I agree").isEnabled && button("I do not agree").isEnabled)

            // In order, statement by statement; Back on the third undoes the second agreement.
            for (step in 0 until total) {
                assertNotNull("statement ${step + 1}", find("Statement ${step + 1} of $total"))
                assertNotNull("I do not agree on statement ${step + 1}", find("I do not agree"))
                if (step == 2) {
                    click("Back")
                    assertNotNull(find("Statement 2 of $total"))
                    assertEquals(1, flow.progress.value.step)
                    waitForTheButtons()
                    click("I agree")
                    assertNotNull(find("Statement 3 of $total"))
                }
                waitForTheButtons()
                click("I agree")
            }

            // The confirmation: reached only now, its button enabled only by the words.
            assertNotNull(find("Confirm"))
            assertTrue(flow.progress.value.confirming)
            assertNotNull(find("I do not agree"))
            assertFalse("not before the words are typed", button("Agree and continue").isEnabled)
            find("Type the words I agree")
            val field = all().first { it.isEditable }
            field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "I agree.") })
            SystemClock.sleep(300)
            assertFalse("a full stop is too much", button("Agree and continue").isEnabled)
            assertTrue(
                "the field takes the words",
                all().first { it.isEditable }.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "  I   AGREE  ") },
                ),
            )
            val enabledBy = SystemClock.uptimeMillis() + 3_000
            while (!button("Agree and continue").isEnabled && SystemClock.uptimeMillis() < enabledBy) SystemClock.sleep(100)
            assertTrue("the words in any case and spacing enable the button (typed: ${flow.progress.value.typed})", button("Agree and continue").isEnabled)
            assertEquals(0, confirmed.get())
            click("Agree and continue")
            SystemClock.sleep(300)
            assertEquals(1, confirmed.get())

            // I do not agree on the confirmation and on a statement.
            click("I do not agree")
            SystemClock.sleep(300)
            assertEquals(1, declined.get())
            scenario.onActivity { flow.restart() }
            assertNotNull(find("Statement 1 of $total"))
            waitForTheButtons()
            click("I do not agree")
            SystemClock.sleep(300)
            assertEquals(2, declined.get())
        }
    }
}
