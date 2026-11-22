package us.theworkmans.proseplow.core.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import us.theworkmans.proseplow.core.engine.EngineError
import us.theworkmans.proseplow.core.engine.FinishReason
import us.theworkmans.proseplow.core.engine.GenerationEvent
import us.theworkmans.proseplow.core.engine.GenerationStats

class SessionCodecTest {

    private val clock = FakeClock(now = 1_700_000_000_000)
    private val stats = GenerationStats(promptTokens = 1, reusedPromptTokens = 0, generatedTokens = 1, promptMillis = 1, generationMillis = 1)

    private fun richSession(): ChatSession {
        val ids = SequentialIds()
        return ChatSession.create("model-a", clock, SequentialIds("s"))
            .withUserTurn("H\u00E9llo \uD83D\uDE00 \"quoted\" \\ back\nslash\u2028", clock, ids)
            .applyEvent("m2", GenerationEvent.TextDelta("Hi <|im_end|> there"), clock)
            .applyEvent("m2", GenerationEvent.Done(FinishReason.STOP_STRING, "<|im_end|>", "Hi", stats), clock)
            .withModel("model-b")
            .withUserTurn("Second", clock, ids)
            .applyEvent("m4", GenerationEvent.TextDelta("partial"), clock)
            .failed("m4", EngineError.PromptTooLong(promptTokens = 9000, contextSize = 4096), clock)
            .withDraft("unsent draft")
    }

    private fun readable(text: String): SessionCodec.Decoded.Readable {
        val decoded = SessionCodec.decode(text)
        assertTrue("expected a readable session, got $decoded", decoded is SessionCodec.Decoded.Readable)
        return decoded as SessionCodec.Decoded.Readable
    }

    private fun unreadable(text: String): String {
        val decoded = SessionCodec.decode(text)
        assertTrue("expected an unreadable session, got $decoded", decoded is SessionCodec.Decoded.Unreadable)
        return (decoded as SessionCodec.Decoded.Unreadable).reason
    }

    // --- Round trips ---

    @Test
    fun `a session survives the round trip unchanged`() {
        val session = richSession()
        val decoded = readable(SessionCodec.encode(session))
        assertEquals(session, decoded.session)
        assertEquals(SessionCodec.SCHEMA_VERSION, decoded.schemaVersion)
        assertTrue(decoded.complete)
        assertFalse(decoded.fromNewerVersion)
        assertEquals(0, decoded.skippedMessages)
    }

    @Test
    fun `an empty session and a streaming one survive the round trip`() {
        val empty = ChatSession.create("model-a", clock, SequentialIds("s"))
        assertEquals(empty, readable(SessionCodec.encode(empty)).session)
        val streaming = empty.withUserTurn("Hello", clock, SequentialIds())
        // The codec stores what it is given; turning a streaming reply into a stopped one is restored()'s job.
        assertEquals(streaming, readable(SessionCodec.encode(streaming)).session)
        assertEquals(MessageStatus.STOPPED, readable(SessionCodec.encode(streaming)).session.restored().messages[1].status)
    }

    @Test
    fun `encoding is stable`() {
        assertEquals(SessionCodec.encode(richSession()), SessionCodec.encode(richSession()))
    }

    @Test
    fun `the stored form carries the schema version and every field, defaults included`() {
        val root = Json.parseToJsonElement(SessionCodec.encode(ChatSession.create("model-a", clock, SequentialIds("s")))).jsonObject
        assertEquals(setOf("schemaVersion", "session"), root.keys)
        assertEquals(1, root.getValue("schemaVersion").jsonPrimitive.content.toInt())
        assertEquals(
            setOf("id", "title", "createdAtMillis", "updatedAtMillis", "modelId", "draft", "messages", "error"),
            root.getValue("session").jsonObject.keys,
        )
        val message = Json.parseToJsonElement(SessionCodec.encode(richSession())).jsonObject
            .getValue("session").jsonObject.getValue("messages").jsonArray[0].jsonObject
        assertEquals(setOf("id", "role", "text", "status", "finishReason", "modelId", "createdAtMillis"), message.keys)
    }

    // --- Forward compatibility ---

    @Test
    fun `fields added by a later version are ignored everywhere`() {
        val text = """
            {"schemaVersion":1,"futureTopLevel":{"a":1},
             "session":{"id":"s1","title":"T","createdAtMillis":5,"updatedAtMillis":6,"modelId":"model-a","draft":"d",
               "pinned":true,"folder":{"name":"work"},
               "messages":[
                 {"id":"m1","role":"USER","text":"Hello","status":"COMPLETE","createdAtMillis":5,"attachments":["a.jpg"],"edited":true},
                 {"id":"m2","role":"ASSISTANT","text":"Hi","status":"COMPLETE","finishReason":"END_OF_TURN","modelId":"model-a",
                  "createdAtMillis":6,"toolCalls":[{"name":"calc","arguments":{"expression":"1+1"}}]}
               ],
               "error":null}}
        """.trimIndent()
        val decoded = readable(text)
        assertTrue(decoded.complete)
        assertEquals(
            ChatSession(
                id = "s1", title = "T", createdAtMillis = 5, updatedAtMillis = 6, modelId = "model-a", draft = "d",
                messages = listOf(
                    ChatMessage(id = "m1", role = Role.USER, text = "Hello", createdAtMillis = 5),
                    ChatMessage(
                        id = "m2", role = Role.ASSISTANT, text = "Hi", finishReason = FinishReason.END_OF_TURN,
                        modelId = "model-a", createdAtMillis = 6,
                    ),
                ),
            ),
            decoded.session,
        )
    }

    @Test
    fun `a message with a role this version does not know is left out and the session still loads`() {
        val text = """
            {"schemaVersion":1,"session":{"id":"s1","createdAtMillis":5,"updatedAtMillis":6,"modelId":"model-a",
               "messages":[
                 {"id":"m1","role":"USER","text":"What is 17 + 25?"},
                 {"id":"m2","role":"ASSISTANT","text":"17 + 25 = ","status":"COMPLETE"},
                 {"id":"m3","role":"TOOL","text":"42","toolName":"calculator"},
                 {"id":"m4","role":"ASSISTANT","text":"42."}
               ]}}
        """.trimIndent()
        val decoded = readable(text)
        assertEquals(listOf("m1", "m2", "m4"), decoded.session.messages.map { it.id })
        assertEquals(1, decoded.skippedMessages)
        assertFalse("the caller can tell that something was left out", decoded.complete)
    }

    @Test
    fun `an unknown status or finish reason falls back instead of losing the message`() {
        val text = """
            {"schemaVersion":1,"session":{"id":"s1","createdAtMillis":5,"modelId":"model-a",
               "messages":[
                 {"id":"m1","role":"USER","text":"Hello","status":"PINNED"},
                 {"id":"m2","role":"ASSISTANT","text":"calc(1+1)","status":"COMPLETE","finishReason":"TOOL_CALL"}
               ]}}
        """.trimIndent()
        val decoded = readable(text)
        assertEquals(0, decoded.skippedMessages)
        assertEquals(MessageStatus.COMPLETE, decoded.session.messages[0].status)
        assertNull(decoded.session.messages[1].finishReason)
        assertEquals("calc(1+1)", decoded.session.messages[1].text)
    }

    @Test
    fun `an error of an unknown kind is kept as unexpected, an unreadable error is dropped`() {
        val unknownKind = """
            {"schemaVersion":1,"session":{"id":"s1","createdAtMillis":5,"modelId":"model-a","messages":[],
               "error":{"kind":"TOOL_FAILED","detail":"calculator","userMessageId":"m1","retryAfter":3}}}
        """.trimIndent()
        val kept = readable(unknownKind)
        assertEquals(ChatError(ChatErrorKind.UNEXPECTED, "calculator", "m1"), kept.session.error)
        assertFalse(kept.errorDropped)

        val broken = """
            {"schemaVersion":1,"session":{"id":"s1","createdAtMillis":5,"modelId":"model-a","messages":[],"error":["not","an","object"]}}
        """.trimIndent()
        val dropped = readable(broken)
        assertNull(dropped.session.error)
        assertTrue(dropped.errorDropped)
        assertFalse(dropped.complete)
    }

    @Test
    fun `a session from a newer schema version is read and reported as such`() {
        val text = """{"schemaVersion":7,"session":{"id":"s1","createdAtMillis":5,"modelId":"model-a","messages":[{"id":"m1","role":"USER","text":"Hello"}]}}"""
        val decoded = readable(text)
        assertEquals(7, decoded.schemaVersion)
        assertTrue(decoded.fromNewerVersion)
        assertFalse(decoded.complete)
        assertEquals("Hello", decoded.session.messages.single().text)
    }

    // --- Damage ---

    @Test
    fun `missing or mistyped session fields take their defaults`() {
        val text = """{"schemaVersion":1,"session":{"id":"s1","title":42,"createdAtMillis":"yesterday","draft":null,"messages":"none"}}"""
        val decoded = readable(text)
        assertEquals(ChatSession(id = "s1", createdAtMillis = 0, updatedAtMillis = 0, modelId = ""), decoded.session)
    }

    @Test
    fun `a missing update time falls back to the creation time`() {
        val decoded = readable("""{"schemaVersion":1,"session":{"id":"s1","createdAtMillis":77,"modelId":"m"}}""")
        assertEquals(77L, decoded.session.updatedAtMillis)
    }

    @Test
    fun `one damaged message does not take the others with it`() {
        val text = """
            {"schemaVersion":1,"session":{"id":"s1","createdAtMillis":5,"modelId":"model-a",
               "messages":[
                 {"id":"m1","role":"USER","text":"kept"},
                 {"role":"USER","text":"no id"},
                 {"id":"","role":"USER","text":"empty id"},
                 {"id":"m4","role":"USER","text":{"not":"a string"}},
                 "not an object",
                 {"id":"m6","role":"ASSISTANT","text":"also kept","createdAtMillis":null}
               ]}}
        """.trimIndent()
        val decoded = readable(text)
        assertEquals(listOf("m1", "m6"), decoded.session.messages.map { it.id })
        assertEquals(4, decoded.skippedMessages)
    }

    @Test
    fun `text that is not a stored session is unreadable, with a reason that quotes none of it`() {
        assertEquals("not a JSON object", unreadable(""))
        assertEquals("not a JSON object", unreadable("secret text that is not JSON"))
        assertEquals("not a JSON object", unreadable("[1,2,3]"))
        assertEquals("no schema version", unreadable("""{"session":{"id":"s1"}}"""))
        assertEquals("no schema version", unreadable("""{"schemaVersion":"1","session":{"id":"s1"}}"""))
        assertEquals("invalid schema version", unreadable("""{"schemaVersion":0,"session":{"id":"s1"}}"""))
        assertEquals("no session", unreadable("""{"schemaVersion":1}"""))
        assertEquals("no session", unreadable("""{"schemaVersion":1,"session":[]}"""))
        assertEquals("session without an id", unreadable("""{"schemaVersion":1,"session":{"title":"x"}}"""))
        assertEquals("session without an id", unreadable("""{"schemaVersion":1,"session":{"id":""}}"""))
    }

    @Test
    fun `truncated text is unreadable rather than half a session`() {
        val text = SessionCodec.encode(richSession())
        for (length in listOf(1, text.length / 2, text.length - 1)) {
            assertEquals("not a JSON object", unreadable(text.substring(0, length)))
        }
    }

    // --- Summary ---

    @Test
    fun `a session summary can be stored on its own`() {
        val summary = richSession().summary()
        val json = Json { ignoreUnknownKeys = true }
        val text = json.encodeToString(SessionSummary.serializer(), summary)
        assertEquals(summary, json.decodeFromString(SessionSummary.serializer(), text))
        // A later version may add fields to the index entries.
        val extended = JsonObject(Json.parseToJsonElement(text).jsonObject + ("pinned" to Json.parseToJsonElement("true")))
        assertEquals(summary, json.decodeFromJsonElement(SessionSummary.serializer(), extended))
    }
}
