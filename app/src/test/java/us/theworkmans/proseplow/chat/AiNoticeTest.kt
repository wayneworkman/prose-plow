package us.theworkmans.proseplow.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The one line under the chat screen's top bar that says the app uses AI: its exact wording,
 * that it is a string resource kept once, that the chat screen alone shows it, and that it is
 * there in every state of that screen, with the keyboard open or not. These are rules about the
 * sources and resources, checked by reading them; how the line looks under the bar, and that it
 * stays put while the keyboard opens, can only be seen on a device.
 */
class AiNoticeTest {

    /** The module directory: Gradle runs the unit tests from it. */
    private val module = File("").absoluteFile

    private val name = "ai_notice"
    private val wording = "This app uses AI, and AI can make mistakes."

    /** Every `<string>` of a resource file as name and text, in the file's order, duplicates included. */
    private fun strings(file: File): List<Pair<String, String>> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        return (0 until nodes.length).map { index ->
            val element = nodes.item(index) as Element
            element.getAttribute("name") to element.textContent
        }
    }

    private fun mainSources(): List<File> =
        File(module, "src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private val chatScreen: String by lazy { File(module, "src/main/java/us/theworkmans/proseplow/chat/ChatScreen.kt").readText() }

    /** The notice's composable, from its signature to its closing brace, without the comment above it. */
    private val composable: String by lazy {
        assertTrue(chatScreen.contains("private fun AiNotice(barColor: Color) {"))
        chatScreen.substringAfter("private fun AiNotice(barColor: Color) {").substringBefore("\n}\n")
    }

    @Test
    fun theNoticeIsOneStringWithExactlyTheWordingThatWasAskedFor() {
        val main = strings(File(module, "src/main/res/values/strings.xml"))
        assertEquals(listOf(wording), main.filter { it.first == name }.map { it.second })
        assertEquals("the wording is not kept a second time under another name", 1, main.count { it.second.contains("AI can make mistakes") })
    }

    @Test
    fun noBuildTypeOrLanguageReplacesTheWording() {
        val others = File(module, "src").walkTopDown()
            .filter { it.isFile && it.extension == "xml" && it.parentFile.name.startsWith("values") }
            .filterNot { it == File(module, "src/main/res/values/strings.xml") }
            .toList()
        for (file in others) {
            assertTrue("${file.parentFile.name}/${file.name} has its own $name", strings(file).none { it.first == name })
        }
    }

    @Test
    fun theChatScreenAloneShowsItAndTakesItFromTheResources() {
        val users = mainSources().filter { it.readText().contains("R.string.$name") }
        assertEquals("only the chat screen: not the lock, set-up, settings or About screens", listOf("ChatScreen.kt"), users.map { it.name })
        assertEquals("and it shows it in one place", 1, Regex("R\\.string\\.$name\\b").findAll(chatScreen).count())
        assertTrue(composable.contains("stringResource(R.string.$name)"))
        for (file in mainSources()) {
            assertFalse("${file.name} spells the notice out instead of using the resource", file.readText().contains("AI can make mistakes"))
        }
    }

    @Test
    fun itSitsUnderTheTopBarAboveEverythingElseInEveryStateOfTheChat() {
        val calls = Regex("^ +AiNotice\\(.*\\)$", RegexOption.MULTILINE).findAll(chatScreen).toList()
        assertEquals("composed in exactly one place", 1, calls.size)
        val call = calls.single().range.first
        val positionOf = { marker: String -> chatScreen.indexOf(marker).also { assertTrue("$marker is in the chat screen", it >= 0) } }
        assertTrue("after the top bar", positionOf("TopAppBar(") < call)
        assertTrue("above what the model is doing", call < positionOf("                Banners("))
        assertTrue("above the conversation", call < positionOf("MessageList("))
        assertTrue("above an empty chat's invitation and a chat that cannot be read", call < positionOf("UnreadableChat(") && call < positionOf("EmptyState("))
        assertTrue("above the field to write in", call < positionOf("InputBar("))
        // Directly in the screen's column, as its first child: not inside the choice between the
        // kinds of chat, nor inside the block that an unreadable chat does not have.
        val column = Regex("\\.imePadding\\(\\),\\n +\\) \\{\\n( +)AiNotice\\(").find(chatScreen)
        assertTrue("the first thing in the column that holds the conversation", column != null)
        val indentOfBanners = Regex("^( +)Banners\\($", RegexOption.MULTILINE).find(chatScreen)!!.groupValues[1].length
        assertEquals(indentOfBanners, column!!.groupValues[1].length)
    }

    @Test
    fun itDoesNotDependOnTheKeyboardAndIsPlainStaticText() {
        // Always composed: neither the notice nor the screen around it asks whether the keyboard is open.
        for (keyboard in listOf("isImeVisible", "WindowInsets", "imePadding", "LocalSoftwareKeyboardController", "if (", "return")) {
            assertFalse("the notice depends on $keyboard", composable.contains(keyboard))
        }
        assertFalse("the chat screen asks whether the keyboard is open", chatScreen.contains("isImeVisible"))
        // Static: nothing that makes it a button, or makes a screen reader say it by itself.
        for (forbidden in listOf("liveRegion", "clickable", "selectable", "SelectionContainer", "semantics", "contentDescription", "Animated")) {
            assertFalse("the notice uses $forbidden", composable.contains(forbidden))
        }
        // Small, muted and centred, as the supporting text elsewhere.
        assertTrue(composable.contains("MaterialTheme.typography.bodySmall"))
        assertTrue(composable.contains("MaterialTheme.colorScheme.onSurfaceVariant"))
        assertTrue(composable.contains("TextAlign.Center"))
        assertFalse("it may wrap; it is never cut short", composable.contains("maxLines"))
        assertTrue("slim: four units of room above and below", composable.contains("vertical = 4.dp"))
    }

    @Test
    fun itHasTheTopBarsOwnColourBehindIt() {
        // One set of colours is named, given to the bar, and its container colour to the notice.
        assertTrue(chatScreen.contains("val barColors = TopAppBarDefaults.topAppBarColors()"))
        assertTrue(Regex("TopAppBar\\(\\n +colors = barColors,").containsMatchIn(chatScreen))
        assertTrue(chatScreen.contains("AiNotice(barColors.containerColor)"))
        assertTrue(composable.contains(".background(barColor)"))
    }
}
