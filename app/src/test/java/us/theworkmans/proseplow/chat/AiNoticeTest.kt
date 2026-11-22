package us.theworkmans.proseplow.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The one line under the message box that says the app uses AI: its exact wording, that it is a
 * string resource kept once, and that the chat screen alone shows it. These are rules about the
 * sources and resources, checked by reading them; how the line looks, and that it leaves with the
 * keyboard, can only be seen on a device.
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
        assertTrue(chatScreen.contains("private fun AiNotice() {"))
        chatScreen.substringAfter("private fun AiNotice() {").substringBefore("\n}\n")
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
    fun itIsTheLastThingInTheChatsColumnInEveryStateOfTheChat() {
        val calls = Regex("^ +AiNotice\\(\\)$", RegexOption.MULTILINE).findAll(chatScreen).toList()
        assertEquals("composed in exactly one place", 1, calls.size)
        val call = calls.single().range.first
        assertTrue("under the field to write in", chatScreen.indexOf("InputBar(") in 0 until call)
        // Not inside the block that holds the field, which an unreadable chat does not have: the
        // call is indented as that block's `if` is, one level out from the field itself.
        val indentOfCall = calls.single().value.takeWhile { it == ' ' }.length
        val indentOfInputBarBlock = Regex("^( +)if \\(session != null\\) \\{\\n +InputBar\\(", RegexOption.MULTILINE).find(chatScreen)!!.groupValues[1].length
        assertEquals(indentOfInputBarBlock, indentOfCall)
    }

    @Test
    fun itIsPlainStaticTextThatLeavesWhileTheKeyboardIsOpen() {
        assertTrue("not composed while the on-screen keyboard is open", composable.contains("if (WindowInsets.isImeVisible) return"))
        // Static: nothing that makes it a button, or makes a screen reader say it by itself.
        for (forbidden in listOf("liveRegion", "clickable", "selectable", "SelectionContainer", "semantics", "contentDescription", "Animated")) {
            assertFalse("the notice uses $forbidden", composable.contains(forbidden))
        }
        // Small, muted and centred, as the supporting text elsewhere.
        assertTrue(composable.contains("MaterialTheme.typography.bodySmall"))
        assertTrue(composable.contains("MaterialTheme.colorScheme.onSurfaceVariant"))
        assertTrue(composable.contains("TextAlign.Center"))
        assertFalse("it may wrap; it is never cut short", composable.contains("maxLines"))
    }
}
