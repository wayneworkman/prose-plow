package us.theworkmans.proseplow.core.terms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TermsProgressTest {

    private val count = 10

    @Test
    fun `I agree on each statement in order reaches the confirmation, and nothing else does`() {
        var progress = TermsProgress(count)
        for (step in 0 until count) {
            assertEquals(step, progress.step)
            assertFalse("statement ${step + 1} is not the confirmation", progress.confirming)
            // Typing and going nowhere change nothing on a statement.
            assertSame(progress, progress.type("I agree"))
            assertFalse(progress.canConfirm)
            progress = progress.agree()
        }
        assertTrue(progress.confirming)
        assertEquals("", progress.typed)
        assertSame("agreeing again on the confirmation does nothing", progress, progress.agree())
    }

    @Test
    fun `back returns to the statement before, which needs I agree again`() {
        val third = TermsProgress(count).agree().agree()
        val second = third.back()!!
        assertEquals(1, second.step)
        assertEquals("the place is lost, so the next agreement is needed again", 2, second.agree().step)
        assertNull("back on the first statement leaves the flow", TermsProgress(count).back())
    }

    @Test
    fun `the confirmation is enabled only while the field holds the agreement, and back empties it`() {
        var progress = TermsProgress(count)
        repeat(count) { progress = progress.agree() }
        assertFalse("the field starts empty", progress.canConfirm)
        for (typed in listOf("I agree", "i agree", "  I   AGREE  ")) assertTrue(typed, progress.type(typed).canConfirm)
        for (typed in listOf("I agree.", "agree", "Iagree", "yes", "")) assertFalse(typed, progress.type(typed).canConfirm)
        val back = progress.type("I agree").back()!!
        assertEquals(count - 1, back.step)
        assertEquals("", back.typed)
        assertEquals("", back.agree().typed)
    }

    @Test
    fun `the flow keeps its place until it is restarted, which goes back to the first statement with an empty field`() {
        val flow = TermsFlow(3)
        flow.agree()
        flow.agree()
        flow.agree()
        flow.type("i agree")
        assertTrue(flow.progress.value.canConfirm)
        assertTrue(flow.back())
        assertEquals(2, flow.progress.value.step)
        flow.restart()
        assertEquals(TermsProgress(3), flow.progress.value)
        assertFalse("back on the first statement changes nothing", flow.back())
        assertEquals(TermsProgress(3), flow.progress.value)
    }
}
