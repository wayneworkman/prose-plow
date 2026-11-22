package us.theworkmans.proseplow.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a passphrase field tells the accessibility framework in place of its content. That the
 * field really hands this out, and not what was typed, is checked on a phone
 * (PassphraseFieldAccessibilityTest) and guarded on the build machine by SourceRulesTest.
 */
class SecureInputTest {

    @Test
    fun accessibilityIsToldOneBulletForEachCharacterAndNothingElse() {
        assertEquals("", obscuredForAccessibility(0).text)
        assertEquals("•••", obscuredForAccessibility(3).text)
        val longest = obscuredForAccessibility(1024)
        assertEquals("the length is all it gives away", 1024, longest.text.length)
        assertTrue(longest.text.all { it == '•' })
        assertTrue("plain text, nothing attached to it", longest.spanStyles.isEmpty() && longest.paragraphStyles.isEmpty())
    }
}
