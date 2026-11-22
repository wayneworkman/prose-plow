package us.theworkmans.proseplow.core.prompt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The prompt formats against the models' own files.
 *
 * The fixtures under `src/test/resources/golden` hold a set of conversations rendered by each
 * model's real chat template (through Python's jinja2, set up as the transformers library sets it
 * up) and, for peacebell, the call structure of the model's own `build_chatml_ids`. They are
 * produced by `generate_golden.py` in that directory; its header says how to run it.
 *
 * The Kotlin formats must agree byte for byte. Any difference here means the model would be shown
 * a prompt it was not trained on.
 */
class GoldenPromptTest {

    @Serializable
    private class Fixture(
        val format: String,
        val source: Source,
        val cases: List<Case>,
        @SerialName("produced_with") val producedWith: Map<String, String>,
    )

    @Serializable
    private class Source(val repository: String, val revision: String, val file: String, val sha256: String)

    @Serializable
    private class Case(
        val name: String,
        val mode: String,
        val messages: List<Message>,
        val rendered: String? = null,
        val error: String? = null,
        val tokens: List<Token>? = null,
    )

    @Serializable
    private class Message(val role: String, val content: String)

    /** One element of peacebell's token stream: an injected id, or the text of one encode call. */
    @Serializable
    private class Token(val id: Int? = null, val encode: String? = null)

    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): Fixture {
        val resource = checkNotNull(javaClass.getResource("/golden/$name.json")) { "Missing fixture $name.json" }
        return json.decodeFromString(Fixture.serializer(), resource.readText(Charsets.UTF_8))
    }

    /** A fixture conversation as the formats take it: a leading system message is the system prompt. */
    private fun Case.input(): PromptInput {
        val system = messages.firstOrNull()?.takeIf { it.role == "system" }
        val rest = if (system == null) messages else messages.drop(1)
        return PromptInput(
            messages = rest.map { message ->
                val role = when (message.role) {
                    "user" -> PromptRole.USER
                    "assistant" -> PromptRole.ASSISTANT
                    else -> error("Unexpected role ${message.role} in case $name")
                }
                PromptMessage(role, message.content)
            },
            systemPrompt = system?.content,
            continueFinalMessage = when (mode) {
                "generate" -> false
                "continue" -> true
                else -> error("Unexpected mode $mode in case $name")
            },
        )
    }

    /** Runs [check] on every case and reports all failures together, each with its case name. */
    private fun Fixture.forEachCase(check: (Case) -> Unit) {
        val failures = cases.mapNotNull { case ->
            try {
                check(case)
                null
            } catch (e: AssertionError) {
                "[${case.name}] ${e.message}"
            } catch (e: PromptFormatException) {
                "[${case.name}] threw: ${e.message}"
            }
        }
        if (failures.isNotEmpty()) fail("${failures.size} of ${cases.size} cases of $format differ:\n" + failures.joinToString("\n"))
    }

    private fun assertRendersLikeTheTemplate(fixture: Fixture, format: PromptFormat) = fixture.forEachCase { case ->
        if (case.error != null) {
            val thrown = try {
                format.render(case.input())
                null
            } catch (e: PromptFormatException) {
                e
            }
            assertNotNull("the template raises \"${case.error}\" but the format rendered", thrown)
            assertEquals(case.error, thrown?.message)
        } else {
            val expected = checkNotNull(case.rendered) { "Case ${case.name} has neither a rendering nor an error" }
            val actual = format.render(case.input()).text()
            // First as text, for a readable difference; then as bytes, which is the actual claim.
            assertEquals(expected, actual)
            assertArrayEquals(expected.toByteArray(Charsets.UTF_8), actual.toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun `Qwen3_5 format matches the model's chat template byte for byte`() {
        val fixture = fixture("qwen35-chatml")
        assertEquals("Qwen/Qwen3.5-0.8B", fixture.source.repository)
        assertTrue("expected the full set of cases", fixture.cases.size >= 40)
        assertRendersLikeTheTemplate(fixture, Qwen35ChatMlFormat)
    }

    @Test
    fun `plain ChatML format matches the Boris-2-Instruct template byte for byte`() {
        val fixture = fixture("plain-chatml")
        assertEquals("opencerebral/Boris-2-Instruct", fixture.source.repository)
        assertTrue("expected the full set of cases", fixture.cases.size >= 27)
        assertRendersLikeTheTemplate(fixture, PlainChatMlFormat)
    }

    @Test
    fun `peacebell format makes the same encode calls and injects the same ids as build_chatml_ids`() {
        val fixture = fixture("peacebell-chatml")
        assertEquals("tokenization_gpt.py", fixture.source.file)
        assertTrue("expected the full set of cases", fixture.cases.size >= 27)
        fixture.forEachCase { case ->
            val expected = checkNotNull(case.tokens).map { token ->
                when {
                    token.id != null -> PromptSegment.TokenId(token.id)
                    token.encode != null -> PromptSegment.Text(token.encode)
                    else -> error("Empty token in case ${case.name}")
                }
            }
            assertEquals(expected, PeacebellChatMlFormat.render(case.input()).segments)
        }
    }

    @Test
    fun `the fixtures cover the cases the formats are most likely to get wrong`() {
        val required = listOf(
            "single user turn",
            "system prompt and one user turn",
            "multi-turn with a system prompt",
            "unicode",
            "leading and trailing whitespace",
            "whitespace that only Python strips",
            "empty user content",
            "control markers inside content",
            "continue a partial answer",
            "continue keeps whitespace at the end of the partial answer",
        )
        for (name in listOf("qwen35-chatml", "plain-chatml", "peacebell-chatml")) {
            val fixture = fixture(name)
            val names = fixture.cases.map { it.name }.toSet()
            assertEquals("$name has duplicate case names", fixture.cases.size, names.size)
            assertTrue("$name lacks ${required - names}", names.containsAll(required))
            assertEquals("$name: sha256 of the source file", 64, fixture.source.sha256.length)
            assertEquals("$name: revision of the source snapshot", 40, fixture.source.revision.length)
            assertTrue("$name records what produced it", fixture.producedWith.keys.containsAll(listOf("python", "jinja2")))
        }
    }

    @Test
    fun `extra system text is rendered as the template renders a system message that contains it`() {
        // The fixture holds the template's output for the combined text; the formats must reach
        // the same result from the two parts.
        val combined = "system prompt followed by extra system text"
        val parts = PromptInput(
            messages = listOf(PromptMessage(PromptRole.USER, "What can you do?")),
            systemPrompt = "Be brief.",
            extraSystemText = "Tools are described here.",
        )
        val qwen = fixture("qwen35-chatml").cases.single { it.name == combined }
        assertEquals(qwen.rendered, Qwen35ChatMlFormat.render(parts).text())
        val plain = fixture("plain-chatml").cases.single { it.name == combined }
        assertEquals(plain.rendered, PlainChatMlFormat.render(parts).text())
        val peacebell = fixture("peacebell-chatml").cases.single { it.name == combined }
        assertEquals(
            checkNotNull(peacebell.tokens).mapNotNull { it.encode },
            PeacebellChatMlFormat.render(parts).segments.filterIsInstance<PromptSegment.Text>().map { it.text },
        )
    }
}
