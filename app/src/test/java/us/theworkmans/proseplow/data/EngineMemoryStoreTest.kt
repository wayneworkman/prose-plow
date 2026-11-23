package us.theworkmans.proseplow.data

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import us.theworkmans.proseplow.core.chat.ChatMessage
import us.theworkmans.proseplow.core.chat.FakeClock
import us.theworkmans.proseplow.core.chat.MessageLifetime
import us.theworkmans.proseplow.core.chat.Role
import us.theworkmans.proseplow.core.engine.FakeNativeLlama
import us.theworkmans.proseplow.core.engine.FakeNativeLlamaFactory
import us.theworkmans.proseplow.core.engine.GenerationEvent
import us.theworkmans.proseplow.core.engine.GenerationRequest
import us.theworkmans.proseplow.core.engine.LlamaEngine
import us.theworkmans.proseplow.core.engine.MemoryRestoreResult
import us.theworkmans.proseplow.core.engine.MemorySaveResult
import us.theworkmans.proseplow.core.engine.SaveTrigger
import us.theworkmans.proseplow.core.model.ModelFamily
import us.theworkmans.proseplow.core.model.ModelSpec
import us.theworkmans.proseplow.core.model.ReadingTimes
import us.theworkmans.proseplow.core.model.SamplingParams
import us.theworkmans.proseplow.core.prompt.PromptMessage
import us.theworkmans.proseplow.core.prompt.PromptRole
import us.theworkmans.proseplow.security.DataCrypto
import us.theworkmans.proseplow.security.TestKeys
import java.io.File

/**
 * The real engine over its native fake, joined with the real store of the model's memory over
 * real files and the real sealed stream: each is otherwise tested only against the other's fake.
 * What the engine exports is sealed, written, authenticated, checked and read back by the store,
 * and the engine then reads only what is new, as if there had been no lock.
 *
 * The model reads 10 tokens a second by its curve, so the first question of 300 characters (one
 * token each in the fake) is worth saving.
 */
class EngineMemoryStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val cipher: DataCrypto = TestKeys.cipher()
    private val root by lazy { temp.newFolder("files") }
    private val sessions by lazy { File(root, "sessions").apply { mkdirs() } }
    private val memoryDir by lazy { File(root, "model-memory") }
    private val files by lazy { ModelMemoryFiles(memoryDir, sessions) }

    private val now = 1_700_000_000_000L
    private val clock = FakeClock(now)

    private val model = ModelSpec(
        id = "test-model",
        displayName = "Test model",
        description = "A model that exists only in tests.",
        family = ModelFamily.PLAIN_CHATML,
        ggufFileName = "test-model.gguf",
        contextLength = 2000,
        defaultMaxNewTokens = 64,
        sampling = SamplingParams(),
        stopStrings = listOf("<|im_end|>"),
        sourceRepository = "example/test-model",
        licence = "Apache-2.0",
        readingTimes = ReadingTimes.flat(10.0),
        deepReadChunk = 128,
    )

    private val key = "k-c1"
    private val question = "Q".repeat(300)

    /** The system prompt the settings hold for the model now; what the store is told a request carries. */
    private var systemPrompt = "Be brief."

    /** The system prompt of the last request the engine read, as the session records it; null before the first. */
    private var readPrompt: String? = null

    /** The chat's stored messages, as the store digests them; the request carries the same conversation. */
    private val messages = listOf(
        ChatMessage(id = "c1-u1", role = Role.USER, text = question, createdAtMillis = now - 120_000),
        ChatMessage(id = "c1-a1", role = Role.ASSISTANT, text = "Fine.", createdAtMillis = now - 60_000),
    )

    private val conversations = MemoryConversations { conversationKey, modelId ->
        if (conversationKey != key || modelId != model.id) null else MemoryConversation("c1", messages, messages.first().createdAtMillis, RequestText(systemPrompt, null), readPrompt?.let { RequestText(it, null) })
    }

    /** A store as a session makes one; a new one stands for the next unlock. */
    private fun store() = MemoryStore(
        files, conversations,
        BuildIdentity(versionCode = 1, lastUpdateMillis = 1_000),
        { id -> if (id == model.id) 1L shl 30 else null },
        { MessageLifetime.SEVEN_DAYS },
        clock,
        streamFile = { file, purpose -> SealedStreamFile(file, purpose, cipher) },
        freeSpace = { FreeSpace(usableBytes = 1L shl 40, totalBytes = 1L shl 40) },
        setFileDate = { file, millis -> file.setLastModified(millis) },
        beforeAppend = {},
    )

    private class Engine(val engine: LlamaEngine, val factory: FakeNativeLlamaFactory) {
        val fake: FakeNativeLlama get() = factory.last
    }

    private suspend fun TestScope.engine(): Engine {
        val factory = FakeNativeLlamaFactory()
        val engine = LlamaEngine(
            factory = factory,
            nativeDispatcher = StandardTestDispatcher(testScheduler),
            nanoTime = { factory.opened.lastOrNull()?.second?.nanos ?: 0L },
            newSeed = { SEED },
        )
        engine.load(model)
        return Engine(engine, factory)
    }

    private fun user(text: String) = PromptMessage(PromptRole.USER, text)

    private fun assistant(text: String) = PromptMessage(PromptRole.ASSISTANT, text)

    private suspend fun Engine.ask(vararg sent: PromptMessage, reply: String): GenerationEvent.Done {
        fake.enqueueReply(reply)
        val events = engine.generate(GenerationRequest(model.id, sent.toList(), systemPrompt = systemPrompt, conversationKey = key)).toList()
        readPrompt = systemPrompt
        return events.last() as GenerationEvent.Done
    }

    private fun memoryFiles(): List<String> = memoryDir.list().orEmpty().sorted()

    /**
     * Save at a lock, forget keeping it, restore at the next unlock and answer: the memory comes
     * back as it was, the next request reads only what is new, and the answer is the one given
     * without a lock; a forgetting that must not keep it then deletes the file. Catches a header,
     * a chunking or an offset on which the two sides disagree, a restore that the store's checks
     * refuse although nothing changed, and a forget that leaves the file.
     */
    @Test
    fun saveForgetRestoreAndTheNextRequestReadsOnlyWhatIsNew() = runTest {
        File(sessions, "c1.enc").writeText("a chat")
        val first = arrayOf(user(question))
        val second = arrayOf(user(question), assistant("Fine."), user("And then?"))

        val control = engine()
        control.ask(*first, reply = "Fine.")
        val controlDone = control.ask(*second, reply = "Then this.")

        val test = engine()
        test.ask(*first, reply = "Fine.")
        val held = test.fake.memory.toList()
        val saved = test.engine.saveMemory(store(), key, SaveTrigger.LOCK)
        assertEquals(held.size, (saved as MemorySaveResult.Saved).tokens)
        assertEquals(listOf("c1.enc"), memoryFiles())
        test.engine.forgetKeepingSavedMemory()
        assertTrue("nothing of the conversation is left in memory", test.fake.memory.isEmpty())

        assertEquals(MemoryRestoreResult.Restored(held.size), test.engine.restoreMemory(store(), key))
        assertEquals(held, test.fake.memory)
        val done = test.ask(*second, reply = "Then this.")
        assertEquals("only what is new was read", held.size, done.stats.reusedPromptTokens)
        assertEquals(controlDone.text, done.text)
        assertEquals(control.fake.memory, test.fake.memory)

        test.engine.forget()
        assertEquals("a forgetting that must not keep it deletes it", emptyList<String>(), memoryFiles())
    }

    /**
     * The same memory after the system prompt was replaced, its deletion never having happened:
     * the store refuses it, deletes it, and the engine reads the conversation in full with the
     * new prompt. Catches the old prompt's tokens read back into the engine.
     */
    @Test
    fun aMemoryMadeWithAnotherSystemPromptIsNotRestored() = runTest {
        File(sessions, "c1.enc").writeText("a chat")
        val test = engine()
        test.ask(user(question), reply = "Fine.")
        assertTrue(test.engine.saveMemory(store(), key, SaveTrigger.LOCK) is MemorySaveResult.Saved)
        test.engine.forgetKeepingSavedMemory()

        assertNotNull("usable with the prompt it was made with", store().saved())

        systemPrompt = "Answer at length."
        assertEquals("the store's check refuses it, so the engine finds nothing saved", MemoryRestoreResult.NothingSaved, test.engine.restoreMemory(store(), key))
        assertTrue(test.fake.memory.isEmpty())
        assertEquals("refused and deleted", emptyList<String>(), memoryFiles())
        val done = test.ask(user(question), assistant("Fine."), user("And then?"), reply = "Then this.")
        assertEquals("everything was read with the new prompt", 0, done.stats.reusedPromptTokens)
    }

    /**
     * A system prompt replaced after the engine read the conversation, with the session's hiding
     * of a changed conversation missed (the store is told of the conversation as before): the
     * save binds the memory to the prompt the engine read, so the check at the next unlock, with
     * the new prompt, refuses and deletes it. Catches a save that writes the prompt of the
     * moment of the save, which would bind the old prompt's memory to the new prompt.
     */
    @Test
    fun aPromptChangedBeforeTheSaveWithTheHidingMissedIsStillRefusedAtTheNextUnlock() = runTest {
        File(sessions, "c1.enc").writeText("a chat")
        val test = engine()
        test.ask(user(question), reply = "Fine.")
        systemPrompt = "Answer at length."
        assertTrue("the miss: the save is made", test.engine.saveMemory(store(), key, SaveTrigger.LOCK) is MemorySaveResult.Saved)
        test.engine.forgetKeepingSavedMemory()

        assertEquals("refused at the next unlock", MemoryRestoreResult.NothingSaved, test.engine.restoreMemory(store(), key))
        assertTrue(test.fake.memory.isEmpty())
        assertEquals("and deleted", emptyList<String>(), memoryFiles())
    }

    /**
     * The ordinary paths are unchanged: a prompt that stays is restored, and so is one changed
     * and then read by the engine before the save. Catches a save that binds the memory to an
     * older prompt than the one the engine last read.
     */
    @Test
    fun aPromptReadByTheEngineBeforeTheSaveIsTheOneTheMemoryIsBoundTo() = runTest {
        File(sessions, "c1.enc").writeText("a chat")
        val test = engine()
        test.ask(user(question), reply = "Fine.")
        systemPrompt = "Answer at length."
        val held = test.ask(user(question), assistant("Fine."), user("And then?"), reply = "Then this.").let { test.fake.memory.size }
        assertTrue(test.engine.saveMemory(store(), key, SaveTrigger.LOCK) is MemorySaveResult.Saved)
        test.engine.forgetKeepingSavedMemory()
        assertEquals(MemoryRestoreResult.Restored(held), test.engine.restoreMemory(store(), key))
    }

    private companion object {
        const val SEED = 4242
    }
}
