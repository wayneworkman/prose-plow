package us.theworkmans.proseplow.core.terms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TermsTest {

    @Test
    fun `the version is at least one, so that a configuration without an agreement is below it`() {
        assertTrue(Terms.TERMS_VERSION >= 1)
    }

    @Test
    fun `the states of version 1 are the sixteen with chatbot laws, in alphabetical order`() {
        assertEquals(16, Terms.BLOCKED_STATES.size)
        assertEquals(Terms.BLOCKED_STATES.sorted(), Terms.BLOCKED_STATES)
        assertEquals(Terms.BLOCKED_STATES.size, Terms.BLOCKED_STATES.toSet().size)
    }

    @Test
    fun `one name stands alone, two are joined by and, more are listed with and before the last`() {
        assertNull(Terms.statesPhrase(emptyList()))
        assertEquals("Utah", Terms.statesPhrase(listOf("Utah")))
        assertEquals("Iowa and Utah", Terms.statesPhrase(listOf("Utah", "Iowa")))
        assertEquals("Iowa, Maine and Utah", Terms.statesPhrase(listOf("Utah", "Iowa", "Maine")))
        assertEquals(
            "California, Colorado, Connecticut, Georgia, Hawaii, Idaho, Iowa, Maine, Nebraska, New Hampshire, " +
                "New York, Oregon, Rhode Island, Tennessee, Utah and Washington",
            Terms.statesPhrase(),
        )
    }

    @Test
    fun `the names come out in alphabetical order whatever order they are given in`() {
        val expected = Terms.statesPhrase(Terms.BLOCKED_STATES)
        assertEquals(expected, Terms.statesPhrase(Terms.BLOCKED_STATES.reversed()))
        assertEquals(expected, Terms.statesPhrase(Terms.BLOCKED_STATES.shuffled(kotlin.random.Random(7))))
    }

    @Test
    fun `the agreement is the words I agree, give or take blanks and capitals, and nothing else`() {
        for (typed in listOf("I agree", "i agree", "  I   AGREE  ", "I\tagree", "i Agree\n")) assertTrue(typed, Terms.agrees(typed))
        for (typed in listOf("I agree.", "agree", "Iagree", "yes", "", " ", "I agree I agree", "I  agree!")) assertFalse(typed, Terms.agrees(typed))
    }

    @Test
    fun `the buttons respond only once a second has passed on the device clock`() {
        assertFalse(Terms.buttonsRespond(placedAtElapsed = 5_000, nowElapsed = 5_000))
        assertFalse(Terms.buttonsRespond(placedAtElapsed = 5_000, nowElapsed = 5_999))
        assertTrue(Terms.buttonsRespond(placedAtElapsed = 5_000, nowElapsed = 6_000))
        assertTrue(Terms.buttonsRespond(placedAtElapsed = 5_000, nowElapsed = 60_000))
        assertFalse("a clock that reads before the placement", Terms.buttonsRespond(placedAtElapsed = 5_000, nowElapsed = 4_000))
    }
}
