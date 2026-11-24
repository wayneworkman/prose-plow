package us.theworkmans.proseplow.terms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import us.theworkmans.proseplow.core.chat.MessageLifetime
import us.theworkmans.proseplow.core.terms.Terms
import java.io.File
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The wording of the terms, read from the string table, held to the rest: the statements are
 * there in order and are the ones the app shows, they and the two lines of the agreement are not
 * to be translated, the number of days in the sixth is the default of how long messages are
 * kept, and none of what an agreement covers changes without a new version of the terms.
 */
class TermsTextTest {

    /** The module directory: Gradle runs the unit tests from it. */
    private val module = File("").absoluteFile

    /** Every string of the table: its name, its text and whether it may be translated. */
    private data class Entry(val name: String, val text: String, val translatable: Boolean)

    private val strings: Map<String, Entry> by lazy {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(module, "src/main/res/values/strings.xml")).documentElement
        val children = root.childNodes
        (0 until children.length).mapNotNull { children.item(it) as? Element }
            .filter { it.tagName == "string" }
            .associate { it.getAttribute("name") to Entry(it.getAttribute("name"), it.textContent, it.getAttribute("translatable") != "false") }
    }

    /** The statements of the table, in the order of their numbers. */
    private val statements: List<Entry> by lazy {
        strings.values.filter { STATEMENT.matches(it.name) }.sortedBy { it.name.removePrefix("terms_statement_").toInt() }
    }

    @Test
    fun theStatementsAreNumberedFromOneWithoutAGapAndTheAppShowsEachInOrder() {
        assertEquals((1..statements.size).map { "terms_statement_$it" }, statements.map { it.name })
        assertEquals(10, statements.size)
        val source = File(module, "src/main/java/us/theworkmans/proseplow/terms/TermsText.kt").readText()
        val listed = Regex("""R\.string\.(terms_statement_\d+),""").findAll(source).map { it.groupValues[1] }.toList()
        assertEquals("TermsText lists every statement, in order", statements.map { it.name }, listed)
        assertEquals(statements.size, TermsText.statementCount)
    }

    @Test
    fun theTermsThemselvesAreNotTranslatedAndTheRestOfTheFlowIs() {
        for (entry in statements + listOf(strings.getValue("terms_build_line"), strings.getValue("terms_confirm_line"))) {
            assertTrue("${entry.name} is part of the terms and stays in English", !entry.translatable)
        }
        for (name in listOf("terms_title", "terms_progress", "terms_instructions", "terms_agree", "terms_disagree", "terms_confirm_title", "terms_field_label", "terms_continue")) {
            assertTrue("$name is ordinary wording of the app", strings.getValue(name).translatable)
        }
    }

    @Test
    fun theSecondStatementNamesTheStatesInAlphabeticalOrder() {
        val second = statements[1].text
        assertEquals(
            "I am not in any of these US states, and I will not use this app there: California, Colorado, Connecticut, Georgia, " +
                "Hawaii, Idaho, Iowa, Maine, Nebraska, New Hampshire, New York, Oregon, Rhode Island, Tennessee, Utah and Washington.",
            second.replace("%1\$s", Terms.statesPhrase()!!),
        )
    }

    /** TERMS-03: the sixth statement names the default of how long messages are kept. */
    @Test
    fun theSixthStatementNamesTheDefaultOfHowLongMessagesAreKept() {
        val days = MessageLifetime.DEFAULT.days
        assertEquals("By default, my messages are deleted after $days days. I can change this in Settings.", statements[5].text)
    }

    /**
     * TERMS-15: a digest of everything an agreement covers (the statements, the two lines above
     * the confirmation field, the states and the default of how long messages are kept) is held
     * to the version of the terms. Changing any of them fails here until the version is raised
     * and the new digest recorded beside it; the digests of earlier versions stay as they were.
     */
    @Test
    fun nothingAnAgreementCoversChangesWithoutANewVersionOfTheTerms() {
        val covered = buildList {
            statements.forEach { add(it.text) }
            add(strings.getValue("terms_build_line").text)
            add(strings.getValue("terms_confirm_line").text)
            add(Terms.BLOCKED_STATES.sorted().joinToString(","))
            add(MessageLifetime.DEFAULT.days.toString())
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(covered.joinToString("\n").toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(
            "The terms changed. Raise Terms.TERMS_VERSION in the core, and record this digest for the new version here.",
            DIGESTS[Terms.TERMS_VERSION],
            digest,
        )
    }

    private companion object {
        val STATEMENT = Regex("terms_statement_\\d+")

        /** The digest of each version of the terms, as it was released. */
        val DIGESTS = mapOf(
            1 to "7bbddbc461f039264a8989e69d30fb81bb3c7247bc115909f5312d223dfccdcd",
        )
    }
}
