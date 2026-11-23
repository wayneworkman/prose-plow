package us.theworkmans.proseplow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import us.theworkmans.proseplow.security.AppLock
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * `FEATURES.md`, at the root of the repository, lists everything the app does that a user or a
 * tester can observe, so that a version for another platform can be held to the same behaviour
 * item by item, wording included. A list like that is only worth having while it is true, and
 * most of it can only be kept true by whoever changes the app. This test holds it to the app in
 * the ways that can be checked without reading it:
 *
 * - every text of `strings.xml` is in the document word for word;
 * - every identifier of an item is defined once, and none is used that is not defined;
 * - every link within the document leads to one of its headings, and every numbered section
 *   is in its list of contents.
 *
 * A failure here is not asking for the test to be changed. It is asking for the document to be
 * brought up to the change that caused it, in the same commit.
 */
class FeaturesDocumentTest {

    /** The module directory: Gradle runs the unit tests from it. */
    private val module = File("").absoluteFile
    private val repository = checkNotNull(module.parentFile)
    private val file = File(repository, "FEATURES.md")

    private val document: String by lazy {
        assertTrue("FEATURES.md is at the root of the repository", file.isFile)
        file.readText()
    }

    /** The document with every run of blanks and line breaks as one blank: its lines are wrapped, a quotation is not. */
    private val flat: String by lazy { document.replace(WHITESPACE, " ") }

    /** One text the app can show: a string, or one form of a plural, under the name the resources give it. */
    private data class Wording(val name: String, val text: String)

    private val wordings: List<Wording> by lazy {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(module, "src/main/res/values/strings.xml")).documentElement
        val found = ArrayList<Wording>()
        for (child in root.elements()) {
            val name = child.getAttribute("name")
            when (child.tagName) {
                "string" -> found += Wording(name, child.textContent)
                "plurals" -> for (form in child.elements()) found += Wording("$name[${form.getAttribute("quantity")}]", form.textContent)
                // A kind of resource this test does not know holds wording it would not look for.
                else -> throw AssertionError("strings.xml has a <${child.tagName}>, which this test cannot read yet: teach it to")
            }
        }
        found
    }

    private fun Element.elements(): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

    /**
     * The text of a resource as a pattern to look for in the document. Android's escapes are
     * undone, line breaks and runs of blanks count as one blank, and a placeholder (`%1$s`,
     * `%2$d`) stands for whatever the document puts in its place, which is a name in braces or
     * an example of up to 200 characters. Everything else must be there character for
     * character.
     */
    private fun pattern(text: String): Regex {
        val pieces = PLACEHOLDER.split(unescaped(text)).map { Regex.escape(it.replace("%%", "%").replace(WHITESPACE, " ")) }
        return Regex(pieces.joinToString(ANYTHING))
    }

    /** [text] with the escapes of an Android string resource undone: a backslash before a quote, `\n`, `\t`, or any other character. */
    private fun unescaped(text: String): String {
        val out = StringBuilder()
        var index = 0
        while (index < text.length) {
            val char = text[index++]
            if (char != '\\' || index == text.length) {
                out.append(char)
                continue
            }
            when (val next = text[index++]) {
                'n' -> out.append('\n')
                't' -> out.append('\t')
                else -> out.append(next)
            }
        }
        return out.toString()
    }

    /**
     * Forms of plurals that no build can show, each with the reason. The document quotes what
     * the app shows, so these are not asked for; its item SECSET-11 says that they exist and
     * what they are. A form may only be listed here while its reason holds, which
     * [theFormsThatAreNotAskedForAreTheOnesThatCannotBeShown] checks.
     */
    private val neverShown = mapOf(
        "security_finish_answer_on[one]" to "the limit it names is AppLock.LEASE_MINUTES, which is not 1",
        "security_finish_answer_off[one]" to "the limit it names is AppLock.LEASE_MINUTES, which is not 1",
    )

    @Test
    fun everyWordingOfTheAppIsInTheDocumentWordForWord() {
        assertTrue("strings.xml holds the app's wording", wordings.size > 100)
        val missing = wordings.filter { it.name !in neverShown && !pattern(it.text).containsMatchIn(flat) }
        assertTrue(
            "FEATURES.md does not have the text of ${missing.size} string(s) word for word:\n" +
                missing.joinToString("\n") { "  ${it.name} = ${it.text}" } + "\n" + WHAT_TO_DO,
            missing.isEmpty(),
        )
    }

    @Test
    fun theFormsThatAreNotAskedForAreTheOnesThatCannotBeShown() {
        val names = wordings.map { it.name }.toSet()
        for ((name, reason) in neverShown) {
            assertTrue("$name is excused from FEATURES.md ($reason) but is no longer in strings.xml: take it off the list in this test", name in names)
        }
        // Both forms are the text for a limit of one minute, and are shown with AppLock.LEASE_MINUTES and nothing else.
        assertNotEquals(
            "With a limit of one minute the singular forms are shown: quote them in FEATURES.md (SECSET-05, SECSET-11) and take them off the list in this test.",
            1,
            AppLock.LEASE_MINUTES,
        )
        val settings = File(module, "src/main/java/us/theworkmans/proseplow/lock/SecuritySettings.kt").readText().replace(WHITESPACE, " ")
        assertTrue(
            "the two texts take their number from AppLock.LEASE_MINUTES",
            settings.contains(
                "if (status.finishAnswerAfterLocking) R.plurals.security_finish_answer_on else R.plurals.security_finish_answer_off, " +
                    "AppLock.LEASE_MINUTES, AppLock.LEASE_MINUTES,",
            ),
        )
        for (wording in wordings.filter { it.name in neverShown }) {
            assertTrue(
                "${wording.name} is in FEATURES.md now, so it need not be excused: take it off the list in this test",
                !pattern(wording.text).containsMatchIn(flat),
            )
        }
    }

    @Test
    fun everyIdentifierIsDefinedOnceAndNoneIsUsedThatIsNotDefined() {
        val defined = DEFINITION.findAll(document).map { it.groupValues[1] }.toList()
        assertTrue("FEATURES.md defines its items as \"- **AREA-00** ...\"", defined.size > 100)
        val twice = defined.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()
        assertEquals(
            "These identifiers are defined more than once in FEATURES.md. An identifier is the stable name of one item: " +
                "a new item takes the next free number of its area, and a number is never used again.",
            emptyList<String>(),
            twice,
        )
        val areas = defined.map { it.substringBefore('-') }.toSet()
        val used = Regex("\\b(?:" + areas.joinToString("|") { Regex.escape(it) } + ")-\\d+\\b").findAll(document).map { it.value }.toSet()
        assertEquals(
            "These identifiers are used in FEATURES.md and defined nowhere in it. If the item was removed, remove or " +
                "replace what refers to it (a number that is retired stays retired); if it was renumbered, do not: " +
                "identifiers are stable.",
            emptyList<String>(),
            (used - defined.toSet()).sorted(),
        )
    }

    @Test
    fun everyLinkWithinTheDocumentLeadsToAHeadingAndEverySectionIsInTheContents() {
        val headings = HEADING.findAll(document).map { it.groupValues[1] }.toList()
        val anchors = headings.map(::anchor).toSet()
        val links = LINK.findAll(document).map { it.groupValues[1] }.toList()
        assertTrue("FEATURES.md has a list of contents", links.size >= 20)
        assertEquals(
            "These links in FEATURES.md lead to no heading of it. A link is \"#\" and the heading in small letters, " +
                "without its punctuation, with a hyphen for every blank: when a heading is reworded, so are the links to it.",
            emptyList<String>(),
            links.filter { it !in anchors }.distinct(),
        )
        val contents = document.substringAfter("\n## Contents\n").substringBefore("\n## ")
        val listed = LINK.findAll(contents).map { it.groupValues[1] }.toSet()
        assertEquals(
            "These sections of FEATURES.md are not in its list of contents.",
            emptyList<String>(),
            headings.filter { NUMBERED.containsMatchIn(it) }.map(::anchor).filter { it !in listed },
        )
    }

    /** The anchor a heading gets where the document is rendered: small letters, no punctuation, a hyphen for every blank. */
    private fun anchor(heading: String): String = heading.trim().lowercase().replace(NOT_IN_AN_ANCHOR, "").replace(' ', '-')

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val PLACEHOLDER = Regex("%(?:\\d+\\$)?[sd]")

        /** What may stand where a placeholder is: something, and not so much that a quotation could be pieced together from far apart. */
        const val ANYTHING = ".{1,200}?"
        val DEFINITION = Regex("(?m)^\\s*- \\*\\*([A-Z0-9]+-\\d+)\\*\\*")
        val HEADING = Regex("(?m)^#{1,6} (.+)$")
        val LINK = Regex("]\\(#([^)]+)\\)")
        val NUMBERED = Regex("^\\d+\\. ")
        val NOT_IN_AN_ANCHOR = Regex("[^\\p{L}\\p{N}_\\- ]")

        const val WHAT_TO_DO =
            "FEATURES.md is the list a version of the app for another platform is held to, and it quotes every text the app " +
                "shows. A text that was added or changed in app/src/main/res/values/strings.xml has to be added or changed " +
                "there in the same commit: find the item that describes the screen (or add one, with the next free number " +
                "of its area) and quote the text in double quotes exactly as it is, with a name in braces such as {n} in " +
                "place of each placeholder. A text that was removed is removed there too. \"How to use this document\" at " +
                "the top of FEATURES.md has the rules."
    }
}
