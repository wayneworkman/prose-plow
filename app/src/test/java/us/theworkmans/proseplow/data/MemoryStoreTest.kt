package us.theworkmans.proseplow.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import us.theworkmans.proseplow.core.chat.ChatMessage
import us.theworkmans.proseplow.core.chat.FakeClock
import us.theworkmans.proseplow.core.chat.MessageLifetime
import us.theworkmans.proseplow.core.chat.MessageLifetime.Companion.DAY_MILLIS
import us.theworkmans.proseplow.core.chat.Role
import us.theworkmans.proseplow.core.engine.MemoryReader
import us.theworkmans.proseplow.core.engine.MemoryVaultUnavailableException
import us.theworkmans.proseplow.core.engine.MemoryWriter
import us.theworkmans.proseplow.core.engine.NativeLlama
import us.theworkmans.proseplow.core.engine.SavedMemory
import us.theworkmans.proseplow.security.ChunkCipher
import us.theworkmans.proseplow.security.DataCrypto
import us.theworkmans.proseplow.security.DataKey
import us.theworkmans.proseplow.security.KeyDestroyedException
import us.theworkmans.proseplow.security.SealedStream
import us.theworkmans.proseplow.security.TestKeys
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * The store of the model's saved memory over the real sealed stream file: what it writes and
 * hands back, every check that makes it delete a memory instead, the generation that stops a
 * late save, free space, cancelling, the queue between the engine's thread and the disk, and
 * the key going in the middle.
 *
 * The engine's side is played by [export] and [readBack], which hand pieces over and take them
 * back as the native session does. Streams have chunks of [SMALL] bytes (the least the native
 * session takes), so that a memory of several chunks stays small; one test runs at the chunk
 * size of the format.
 */
class MemoryStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val cipher: DataCrypto = TestKeys.cipher()
    private val root by lazy { temp.newFolder("files") }
    private val sessions by lazy { File(root, "sessions").apply { mkdirs() } }
    private val memoryDir by lazy { File(root, "model-memory") }
    private val files by lazy { ModelMemoryFiles(memoryDir, sessions) }

    /** Whole seconds: file systems keep dates to the second at least. */
    private val now = 1_700_000_000_000L
    private val t0 = now - 2 * DAY_MILLIS
    private val clock = FakeClock(now)
    private var lifetime = MessageLifetime.SEVEN_DAYS
    private var build = BuildIdentity(versionCode = 10203, lastUpdateMillis = 1_690_000_000_000L)
    private val modelLengths = mutableMapOf("model-a" to 600_000_000L)
    private var space = FreeSpace(usableBytes = 64L shl 30, totalBytes = 128L shl 30)
    private var setDate: (File, Long) -> Boolean = { file, millis -> file.setLastModified(millis) }
    private var beforeAppend: (ByteBuffer) -> Unit = {}
    private var chunk = SMALL

    /** The conversations the session knows, by conversation key. */
    private val chats = HashMap<String, MemoryConversation>()
    private var conversationsFail = false
    /** What a request to each model carries besides the messages now, by model id; none for a model not named. */
    private val requestTexts = mutableMapOf("model-a" to RequestText("Answer briefly.", null))

    /** What the last request the engine read carried besides the messages, by model id; none for a model not named. */
    private val readTexts = HashMap<String, RequestText>()

    /** The models the store asked about, in order. */
    private val modelsAsked = ArrayList<String>()
    private val conversations = MemoryConversations { key, modelId ->
        if (conversationsFail) throw IOException("The chats cannot be read now")
        modelsAsked += modelId
        chats[key]?.copy(requestText = requestTexts[modelId] ?: RequestText(null, null), readRequestText = readTexts[modelId])
    }

    private fun store(cipher: ChunkCipher = this.cipher) = MemoryStore(
        files, conversations, build, { modelLengths[it] }, { lifetime }, clock,
        streamFile = { file, purpose -> SealedStreamFile(file, purpose, cipher, chunk) },
        freeSpace = { space },
        setFileDate = { file, millis -> setDate(file, millis) },
        beforeAppend = { beforeAppend(it) },
    )

    private fun message(id: String, role: Role, at: Long, text: String) = ChatMessage(id = id, role = role, text = text, createdAtMillis = at)

    private fun messagesOf(id: String) = listOf(
        message("$id-u1", Role.USER, t0, "Question one of $id"),
        message("$id-a1", Role.ASSISTANT, t0 + 60_000, "Answer one of $id"),
        message("$id-u2", Role.USER, t0 + 120_000, "Question two of $id"),
        message("$id-a2", Role.ASSISTANT, t0 + 180_000, "Answer two of $id"),
    )

    /** A chat the session knows under the key `k-<id>`, with a file. */
    private fun chat(id: String = "c1", messages: List<ChatMessage> = messagesOf(id), oldest: Long = messages.minOf { it.createdAtMillis }): MemoryConversation {
        File(sessions, "$id.enc").writeText("a chat")
        return MemoryConversation(id, messages, oldest, RequestText(null, null)).also { chats["k-$id"] = it }
    }

    private fun changeMessages(id: String = "c1", change: (List<ChatMessage>) -> List<ChatMessage>) {
        val known = chats.getValue("k-$id")
        chats["k-$id"] = known.copy(messages = change(known.messages))
    }

    private fun memoryOf(image: ByteArray, id: String = "c1") = SavedMemory("k-$id", "model-a", 4096, 120, 31.25, image.size.toLong())

    private fun image(size: Int, seed: Int = 1) = ByteArray(size) { ((it * 31 + seed) % 251).toByte() }

    private fun memoryFile(id: String = "c1") = File(memoryDir, "$id.enc")

    private fun leftInDirectory(): List<String> = memoryDir.listFiles().orEmpty().map { it.name }.sorted()

    private fun noTemporaryFile() = assertTrue("a temporary file was left: ${leftInDirectory()}", leftInDirectory().none { it.endsWith(".tmp") })

    /**
     * Hands [image] to [writer] as the native export does: one buffer, the store's header left
     * alone at the start of the first piece, every piece full but the last. [beforePiece] runs
     * before each piece is handed over and [afterPiece] after it was taken. Returns false when
     * the store stopped the export. Zeroes the buffer at the end, as the native session does,
     * unless [zeroAtEnd] is false.
     */
    private fun export(
        writer: MemoryWriter,
        image: ByteArray,
        beforePiece: (Int) -> Unit = {},
        afterPiece: (Int) -> Unit = {},
        zeroAtEnd: Boolean = true,
    ): Boolean {
        val buffer = writer.buffer
        val capacity = buffer.capacity()
        try {
            var start = writer.firstPieceOffset
            var offset = 0
            var piece = 0
            while (true) {
                val length = minOf(capacity - start, image.size - offset)
                for (index in 0 until length) buffer.put(start + index, image[offset + index])
                offset += length
                val last = offset == image.size
                beforePiece(piece)
                if (!writer.write(start + length, last)) return false
                afterPiece(piece)
                if (last) return true
                start = 0
                piece++
            }
        } finally {
            if (zeroAtEnd) for (index in 0 until capacity) buffer.put(index, 0)
        }
    }

    /** A whole save as the engine makes one: begin, export, commit, close. */
    private fun MemoryStore.save(memory: SavedMemory, image: ByteArray, beforePiece: (Int) -> Unit = {}): Boolean {
        beginSave(memory).use { writer ->
            if (!export(writer, image, beforePiece)) return false
            writer.commit()
            return true
        }
    }

    /** Reads a restore as the native import does; null when the store stopped it. Also counts the pieces. */
    private fun readBack(reader: MemoryReader, pieces: AtomicInteger = AtomicInteger()): ByteArray? {
        val out = ByteArrayOutputStream()
        var first = true
        while (true) {
            val length = reader.read()
            if (length < 0) return null
            if (length == 0) {
                assertEquals("a read after the end ends again", 0, reader.read())
                return out.toByteArray()
            }
            pieces.incrementAndGet()
            for (index in (if (first) reader.firstPieceOffset else 0) until length) out.write(reader.buffer.get(index).toInt())
            first = false
        }
    }

    private fun MemoryStore.restored(): ByteArray? = beginRestore().use { readBack(it) }

    /** Changes the bytes of [file] and keeps its date, so that only the change is tested. */
    private fun rewrite(file: File, change: (ByteArray) -> ByteArray) {
        val date = file.lastModified()
        file.writeBytes(change(file.readBytes()))
        file.setLastModified(date)
    }

    private fun flipped(bytes: ByteArray, at: Int) = bytes.copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() }

    // --- Round trips ---

    /** Catches anything lost or changed on the way: the image, the header fields, the file's name, date and form. */
    @Test
    fun aMemoryOfManyChunksComesBackWhole() {
        chat()
        val image = image(3 * SMALL + 1234)
        val memory = memoryOf(image)
        assertTrue(store().save(memory, image))
        assertTrue(store().awaitStopped(5_000))

        assertEquals(listOf("c1.enc"), leftInDirectory())
        assertEquals("the file is dated by the oldest message", t0, memoryFile().lastModified())
        val header = SealedStreamFile.readHeader(memoryFile())!!
        assertEquals("and so is the stream's header", t0, header.oldestMessageMillis)
        assertFalse("nothing of the image is in the clear", String(memoryFile().readBytes(), Charsets.ISO_8859_1).contains(String(image.copyOfRange(100, 140), Charsets.ISO_8859_1)))

        val reopened = store()
        assertEquals(memory, reopened.saved())
        val pieces = AtomicInteger()
        val read = reopened.beginRestore().use { reader ->
            assertEquals(memory, reader.memory)
            readBack(reader, pieces)
        }
        assertArrayEquals(image, read)
        assertEquals("one piece per chunk", 4, pieces.get())
    }

    /** Catches an off-by-one at the edges of a chunk, and the header taking the place of image bytes. */
    @Test
    fun imagesOfEverySizeAroundAChunkComeBack() {
        chat()
        val header = MemoryHeader(build, 600_000_000L, "c1", "c1-u1", 4, MemoryStore.digestOf(messagesOf("c1")), MemoryStore.requestDigestOf(RequestText(null, null)), memoryOf(ByteArray(0))).size
        for (size in listOf(0, 1, SMALL - header - 1, SMALL - header, SMALL - header + 1, 2 * SMALL - header, 2 * SMALL)) {
            val image = image(size, seed = size)
            assertTrue("size $size", store().save(memoryOf(image), image))
            assertArrayEquals("size $size", image, store().restored())
        }
    }

    /** The format's own chunk size, once, with a modest image of a few chunks. */
    @Test
    fun aMemoryAtTheFormatsChunkSizeComesBack() {
        chunk = SealedStream.CHUNK_SIZE
        chat()
        val image = image(2 * SealedStream.CHUNK_SIZE + 123_457)
        assertTrue(store().save(memoryOf(image), image))
        assertArrayEquals(image, store().restored())
        assertEquals(SealedStream.sealedSize(image.size + memoryOfHeaderSize(image)), memoryFile().length())
    }

    private fun memoryOfHeaderSize(image: ByteArray) =
        MemoryHeader(build, 600_000_000L, "c1", "c1-u1", 4, MemoryStore.digestOf(messagesOf("c1")), MemoryStore.requestDigestOf(RequestText(null, null)), memoryOf(image)).size.toLong()

    /** Catches a store that keeps two memories, or the old one of another chat after a new one. */
    @Test
    fun aNewMemoryReplacesTheOneOfAnotherChat() {
        chat("c0")
        chat("c1")
        val old = image(SMALL, seed = 7)
        assertTrue(store().save(memoryOf(old, "c0"), old))
        val new = image(2 * SMALL, seed = 8)
        assertTrue(store().save(memoryOf(new, "c1"), new))
        assertEquals(listOf("c1.enc"), leftInDirectory())
        assertEquals(memoryOf(new, "c1"), store().saved())
    }

    // --- What makes a memory unusable ---

    private fun resetWorld() {
        memoryDir.deleteRecursively()
        sessions.deleteRecursively()
        sessions.mkdirs()
        chats.clear()
        clock.now = now
        lifetime = MessageLifetime.SEVEN_DAYS
        build = BuildIdentity(versionCode = 10203, lastUpdateMillis = 1_690_000_000_000L)
        modelLengths.clear()
        modelLengths["model-a"] = 600_000_000L
        requestTexts.clear()
        requestTexts["model-a"] = RequestText("Answer briefly.", null)
        readTexts.clear()
    }

    /**
     * A save binds the memory to the text the last request the engine read carried, and the
     * check compares that with the text of now. Catches a save that writes the text of the
     * moment of the save: a prompt replaced after the engine read the conversation, with the
     * session's hiding of the change missed, would otherwise be bound to the old prompt's memory.
     */
    @Test
    fun aSaveWritesTheTextTheEngineReadAndTheCheckComparesItWithTheTextOfNow() {
        val image = image(2 * SMALL + 17)
        val cases: List<Triple<String, RequestText?, Boolean>> = listOf(
            Triple("read with the prompt of before", RequestText("Answer at length.", null), false),
            Triple("read with the prompt of now", RequestText("Answer briefly.", null), true),
            Triple("nothing read since the unlock: the text of now", null, true),
        )
        for ((case, read, usable) in cases) {
            resetWorld()
            chat()
            if (read != null) readTexts["model-a"] = read
            assertTrue(case, store().save(memoryOf(image), image))
            if (usable) {
                assertNotNull(case, store().saved())
            } else {
                assertNull(case, store().saved())
                assertEquals(case, emptyList<String>(), leftInDirectory())
            }
        }
    }

    /**
     * Every check of the store, one at a time: each case first shows that the memory passes,
     * then changes one thing, and the memory must be refused and its file deleted. A check that
     * stops working lets its case through.
     */
    @Test
    fun everyCheckThatFailsDeletesTheMemory() {
        val cases: List<Pair<String, () -> Unit>> = listOf(
            "another version code" to { build = build.copy(versionCode = 10204) },
            "the same version installed again" to { build = build.copy(lastUpdateMillis = build.lastUpdateMillis + 1) },
            "another length of the model's file" to { modelLengths["model-a"] = 600_000_001L },
            "a model this build does not carry" to { modelLengths.clear() },
            "no chat file" to { File(sessions, "c1.enc").delete() },
            "a conversation the session does not know" to { chats.clear() },
            "a key that names another chat" to {
                chat("c2")
                chats["k-c1"] = chats.getValue("k-c2")
            },
            "the first turn pruned" to { changeMessages { it.drop(2) } },
            "the first message's id changed" to { changeMessages { listOf(it[0].copy(id = "other")) + it.drop(1) } },
            "a message's text edited" to { changeMessages { it.take(1) + it[1].copy(text = "Answer one, edited") + it.drop(2) } },
            "a message's date changed" to { changeMessages { it.take(1) + it[1].copy(createdAtMillis = t0 + 61_000) + it.drop(2) } },
            "a message removed from the middle" to { changeMessages { it.take(2) + it.drop(3) } },
            "the last message removed" to { changeMessages { it.dropLast(1) } },
            "the oldest message expired at the trusted time" to { clock.now = t0 + 7 * DAY_MILLIS + 1 },
            "the file dated later than its header" to { memoryFile().setLastModified(t0 + 1_000) },
            "a changed byte in the first chunk" to { rewrite(memoryFile()) { flipped(it, SealedStream.HEADER_SIZE + SealedStream.NONCE_SIZE + 10) } },
            "a changed date in the stream's header" to { rewrite(memoryFile()) { flipped(it, SealedStream.HEADER_SIZE - 1) } },
            "the file moved to another chat" to {
                chat("c2")
                assertTrue(memoryFile().renameTo(memoryFile("c2")))
                chats["k-c1"] = chats.getValue("k-c2")
            },
            "a second memory file" to { memoryFile().copyTo(memoryFile("c2")) },
            "a memory file whose name no chat has" to { assertTrue(memoryFile().renameTo(File(memoryDir, "not a chat.enc"))) },
            "an empty file" to { rewrite(memoryFile()) { ByteArray(0) } },
            "the system prompt replaced" to { requestTexts["model-a"] = RequestText("Answer at length.", null) },
            "the system prompt cleared" to { requestTexts["model-a"] = RequestText(null, null) },
            "the system prompt made empty" to { requestTexts["model-a"] = RequestText("", null) },
            "further system text added" to { requestTexts["model-a"] = RequestText("Answer briefly.", "A tool.") },
        )
        for ((case, change) in cases) {
            resetWorld()
            chat()
            val image = image(2 * SMALL + 17)
            assertTrue(case, store().save(memoryOf(image), image))
            assertNotNull("before: $case", store().saved())
            change()
            assertNull(case, store().saved())
            assertEquals(case, emptyList<String>(), leftInDirectory())
        }
    }

    /** Catches a header of another format read as this one. */
    @Test
    fun aHeaderOfAnotherFormatVersionIsDeleted() {
        chat()
        val image = image(100)
        val header = MemoryHeader(build, 600_000_000L, "c1", "c1-u1", 4, MemoryStore.digestOf(messagesOf("c1")), MemoryStore.requestDigestOf(RequestText(null, null)), memoryOf(image)).encode()
        header[1] = 2
        memoryDir.mkdirs()
        SealedStreamFile(memoryFile(), MemoryStore.purposeOf("c1"), cipher, SMALL).write(t0).use { writer ->
            val plaintext = ByteBuffer.allocateDirect(header.size + image.size).put(header).put(image)
            plaintext.flip()
            writer.write(plaintext, last = true)
            writer.commit()
        }
        memoryFile().setLastModified(t0)
        assertNull(store().saved())
        assertFalse(memoryFile().exists())
    }

    /** Catches a store that is too strict to be of use: what does not make a memory wrong must not delete it. */
    @Test
    fun aMemoryStaysUsableThroughWhatDoesNotConcernIt() {
        chat()
        val image = image(SMALL + 5)
        val memory = memoryOf(image)
        assertTrue(store().save(memory, image))

        changeMessages { it + message("c1-u3", Role.USER, now, "A question asked after the save") }
        assertEquals("messages after those it was made from are read on top", memory, store().saved())

        memoryFile().setLastModified(t0 - DAY_MILLIS)
        assertEquals("an earlier file date only deletes it earlier while locked", memory, store().saved())

        requestTexts["model-b"] = RequestText("Another model's prompt", null)
        assertEquals("the prompt of another model does not concern it", memory, store().saved())
        assertEquals("the store asks for the request text of the memory's own model", setOf("model-a"), modelsAsked.toSet())

        lifetime = MessageLifetime.NEVER
        clock.now = t0 + 400 * DAY_MILLIS
        assertEquals("nothing expires under Never", memory, store().saved())
        assertArrayEquals(image, store().restored())
    }

    /** The decision is taken by the authenticated date: a file dated as expired with a recent header is kept by the unlocked checks. */
    @Test
    fun theDateThatDecidesIsTheHeadersNeverTheFiles() {
        chat()
        val image = image(SMALL)
        assertTrue(store().save(memoryOf(image), image))
        memoryFile().setLastModified(now - 30 * DAY_MILLIS)
        assertNotNull(store().saved())
        clock.now = t0 + 7 * DAY_MILLIS + 1
        memoryFile().setLastModified(t0)
        assertNull("the header's date has expired, whatever the file says", store().saved())
    }

    /**
     * Catches a store that deletes a memory for a failure that says nothing about it, and one
     * that says so in a way the engine would answer with a deletion: "cannot tell now" is
     * [MemoryVaultUnavailableException], from checking and from beginning a restore alike.
     */
    @Test
    fun aLockOrAFailureToReadDeletesNothingWhenChecking() {
        chat()
        val image = image(SMALL)
        val key = TestKeys.cipher()
        assertTrue(store(key).save(memoryOf(image), image))

        conversationsFail = true
        assertThrows(MemoryVaultUnavailableException::class.java) { store(key).saved() }
        assertThrows(MemoryVaultUnavailableException::class.java) { store(key).beginRestore() }
        assertTrue(memoryFile().exists())
        conversationsFail = false

        key.destroy()
        val checking = assertThrows(MemoryVaultUnavailableException::class.java) { store(key).saved() }
        assertTrue(checking.cause is KeyDestroyedException)
        assertThrows(MemoryVaultUnavailableException::class.java) { store(key).beginRestore() }
        assertTrue(memoryFile().exists())
    }

    // --- Restoring ---

    /** Catches pieces handed to the engine before the end of the stream has proven itself. */
    @Test
    fun aStreamCutBetweenChunksOrWithBytesBehindItNeverEndsProperly() {
        val sealedChunk = SMALL + SealedStream.CHUNK_OVERHEAD
        for ((case, change) in listOf<Pair<String, (ByteArray) -> ByteArray>>(
            "cut after two chunks" to { it.copyOf(SealedStream.HEADER_SIZE + 2 * sealedChunk) },
            "cut inside the last chunk" to { it.copyOf(it.size - 3) },
            "a byte appended" to { it + 0 },
            "a changed byte in the last chunk" to { flipped(it, it.size - 20) },
        )) {
            resetWorld()
            chat()
            val image = image(3 * SMALL + 40)
            assertTrue(case, store().save(memoryOf(image), image))
            rewrite(memoryFile(), change)
            assertNotNull("the first chunk still opens: $case", store().saved())
            assertNull(case, store().restored())
            assertFalse(case, memoryFile().exists())
        }
    }

    /** Catches a restore that goes ahead on a memory that is no longer right. */
    @Test
    fun aRestoreChecksEverythingAgainAndDeletesWhatFails() {
        chat()
        val image = image(SMALL)
        assertTrue(store().save(memoryOf(image), image))
        changeMessages { it.take(1) + it[1].copy(text = "Edited after the check") + it.drop(2) }
        assertThrows(IOException::class.java) { store().beginRestore() }
        assertFalse(memoryFile().exists())
        assertThrows("nothing to restore", IOException::class.java) { store().beginRestore() }
    }

    /**
     * The key gone in the middle of a restore (a lock): nothing more is handed over, the read
     * says "cannot tell now", and the memory, which nothing speaks against, stays for the next
     * unlock. Catches the store's half of the defect of deleting a good memory at a lock.
     */
    @Test
    fun theKeyDestroyedDuringARestoreStopsItAndKeepsTheMemory() {
        chat()
        val image = image(3 * SMALL)
        val keyBytes = ByteArray(DataKey.SIZE).also { SecureRandom().nextBytes(it) }
        val key = DataCrypto(DataKey(keyBytes))
        assertTrue(store(key).save(memoryOf(image), image))
        store(key).beginRestore().use { reader ->
            assertTrue(reader.read() > 0)
            key.destroy()
            assertThrows(MemoryVaultUnavailableException::class.java) { reader.read() }
            assertEquals("and nothing after it", -1, reader.read())
        }
        assertTrue(memoryFile().exists())
        // With the cipher of the next unlock (the same data key) it is whole and restores.
        assertNotNull(store(DataCrypto(DataKey(keyBytes))).restored())
    }

    // --- Saving: what is refused, and the file's date ---

    /** Catches a memory kept for a conversation that has no usable date to expire by. */
    @Test
    fun aConversationWithoutUsableDatesIsNotSaved() {
        val image = image(SMALL)
        for ((case, messages) in listOf(
            "a message without a date" to messagesOf("c1").mapIndexed { index, it -> if (index == 1) it.copy(createdAtMillis = 0) else it },
            "a message dated in the future" to messagesOf("c1").mapIndexed { index, it -> if (index == 3) it.copy(createdAtMillis = now + 1) else it },
            "the oldest message expired already" to messagesOf("c1").mapIndexed { index, it -> if (index == 0) it.copy(createdAtMillis = now - 8 * DAY_MILLIS) else it },
            "no messages" to emptyList(),
        )) {
            resetWorld()
            chat(messages = messages, oldest = messages.minOfOrNull { it.createdAtMillis } ?: t0)
            assertThrows(case, IOException::class.java) { store().beginSave(memoryOf(image)) }
            assertTrue(case, store().awaitStopped(5_000))
            assertEquals(case, emptyList<String>(), leftInDirectory())
        }
        resetWorld()
        chat(oldest = 0)
        assertThrows("the oldest date the session gives is not usable", IOException::class.java) { store().beginSave(memoryOf(image)) }
    }

    /** The earlier of the session's oldest date and the messages' own dates the file. */
    @Test
    fun theFileTakesTheEarlierOfTheOldestDates() {
        chat(oldest = t0 - DAY_MILLIS)
        val image = image(10)
        assertTrue(store().save(memoryOf(image), image))
        assertEquals(t0 - DAY_MILLIS, memoryFile().lastModified())
        assertNotNull(store().saved())
    }

    /** A memory whose date did not hold would escape the clean-up without the key: it is not kept, and the one before stays. */
    @Test
    fun aMemoryWhoseDateCannotBeSetIsNotKept() {
        chat()
        val old = image(SMALL, seed = 3)
        assertTrue(store().save(memoryOf(old), old))
        val before = memoryFile().readBytes()
        for ((case, setting) in listOf<Pair<String, (File, Long) -> Boolean>>(
            "the date could not be set" to { _, _ -> false },
            "a later date was set" to { file, _ -> file.setLastModified(now) },
        )) {
            setDate = setting
            val new = image(2 * SMALL, seed = 4)
            val writer = store().beginSave(memoryOf(new))
            writer.use {
                assertTrue(export(it, new))
                assertThrows(case, IOException::class.java) { it.commit() }
            }
            noTemporaryFile()
            assertArrayEquals(case, before, memoryFile().readBytes())
        }
    }

    // --- Deletion wins over a save ---

    /** Catches a save that puts a memory back after it was deleted, at any piece and just before the commit. */
    @Test
    fun aSaveThatEndsAfterADeletionLeavesNothing() {
        chat()
        val image = image(3 * SMALL)
        for (piece in 0..3) {
            assertTrue(store().save(memoryOf(image), image))
            val store = store()
            assertFalse("deleted at piece $piece", store.save(memoryOf(image), image) { if (it == piece) files.deleteFor("c1") })
            assertTrue(store.awaitStopped(5_000))
            assertEquals("deleted at piece $piece", emptyList<String>(), leftInDirectory())
        }
        for (deletion in listOf<(MemoryStore) -> Unit>({ files.deleteFor("c1") }, { it.delete() }, { files.deleteFor("another chat") })) {
            val store = store()
            store.beginSave(memoryOf(image)).use { writer ->
                assertTrue(export(writer, image))
                deletion(store)
                assertThrows(IOException::class.java) { writer.commit() }
            }
            assertTrue(store.awaitStopped(5_000))
            assertEquals(emptyList<String>(), leftInDirectory())
        }
    }

    // --- Free space ---

    /**
     * The three cases of free space, at their edges, with the reserve at both of its rules: the
     * old memory stays until the rename when there is room for both, goes first when there is
     * room only without it, and goes with nothing saved when there is not room even then.
     */
    @Test
    fun freeSpaceDecidesWhenTheOldMemoryGoes() {
        val image = image(3 * SMALL)
        val needed = SealedStream.sealedSize(image.size + memoryOfHeaderSize(image))
        for (total in listOf(10L shl 30, 100L shl 30)) {
            val reserve = maxOf(1L shl 30, total / 20)
            for ((usable, oldStaysDuringTheSave) in listOf(needed + reserve to true, needed + reserve - 1 to false)) {
                resetWorld()
                chat("c0")
                chat("c1")
                space = FreeSpace(usableBytes = 1L shl 40, totalBytes = total)
                val old = image(SMALL, seed = 9)
                assertTrue(store().save(memoryOf(old, "c0"), old))
                space = FreeSpace(usable, total)
                val seen = AtomicReference<Boolean>()
                assertTrue(store().save(memoryOf(image, "c1"), image) { if (it == 0) seen.set(memoryFile("c0").exists()) })
                assertEquals("total $total, usable $usable", oldStaysDuringTheSave, seen.get())
                assertEquals(listOf("c1.enc"), leftInDirectory())
            }
            resetWorld()
            chat("c0")
            chat("c1")
            space = FreeSpace(usableBytes = 1L shl 40, totalBytes = total)
            val old = image(SMALL, seed = 9)
            assertTrue(store().save(memoryOf(old, "c0"), old))
            space = FreeSpace(needed + reserve - memoryFile("c0").length() - 1, total)
            assertThrows(IOException::class.java) { store().beginSave(memoryOf(image, "c1")) }
            assertTrue(store().awaitStopped(5_000))
            assertEquals("short of room even without it: it goes, and nothing is saved", emptyList<String>(), leftInDirectory())
        }
    }

    // --- Cancelling, and the key ---

    /** Catches a cancelled or abandoned save that leaves a temporary file or harms the memory kept. */
    @Test
    fun aSaveCancelledAtAnyPieceLeavesTheOldMemoryAndNoTemporaryFile() {
        chat()
        val old = image(SMALL + 9, seed = 5)
        assertTrue(store().save(memoryOf(old), old))
        val new = image(3 * SMALL, seed = 6)
        for (piece in 0..3) {
            val store = store()
            assertFalse("cancelled at piece $piece", store.save(memoryOf(new), new) { if (it == piece) store.cancelSave() })
            assertTrue(store.awaitStopped(5_000))
            noTemporaryFile()
            assertArrayEquals("cancelled at piece $piece", old, store().restored())
            assertTrue(store().save(memoryOf(old), old))
        }
        val store = store()
        store.beginSave(memoryOf(new)).use { assertTrue(export(it, new)) }
        assertTrue(store.awaitStopped(5_000))
        noTemporaryFile()
        assertArrayEquals("closed without a commit", old, store().restored())
    }

    /** The key destroyed in the middle of a save: the next piece fails, nothing is kept, the old memory stays. */
    @Test
    fun theKeyDestroyedDuringASaveEndsIt() {
        chat()
        val key = TestKeys.cipher()
        val old = image(SMALL, seed = 1)
        assertTrue(store(key).save(memoryOf(old), old))
        val before = memoryFile().readBytes()
        val store = store(key)
        val new = image(3 * SMALL, seed = 2)
        assertThrows(KeyDestroyedException::class.java) { store.save(memoryOf(new), new) { if (it == 1) key.destroy() } }
        assertTrue(store.awaitStopped(5_000))
        noTemporaryFile()
        assertArrayEquals(before, memoryFile().readBytes())
        assertThrows("no save begins without the key", KeyDestroyedException::class.java) { store.beginSave(memoryOf(new)) }
    }

    /**
     * The queue between the engine's thread and the disk: while the disk does not answer, the
     * engine's thread still seals [MemoryStore.QUEUE_CHUNKS] pieces and returns from each at
     * once, then waits for a buffer; a cancel lets it go at once; what waits for the disk is
     * ciphertext, never the plaintext; and the plaintext buffer is overwritten when the save ends.
     */
    @Test
    fun aStuckDiskHoldsOnlyCiphertextAndNeverTheEnginesThread() {
        chat()
        val stuck = CountDownLatch(1)
        val waiting = ArrayList<ByteArray>()
        beforeAppend = { sealed ->
            synchronized(waiting) { waiting += ByteArray(sealed.remaining()).also { sealed.duplicate().get(it) } }
            stuck.await()
        }
        val text = "the text of the conversation, "
        val image = ByteArray(10 * SMALL) { text[it % text.length].code.toByte() }
        val store = store()
        val writer = store.beginSave(memoryOf(image))
        val handed = AtomicInteger()
        val result = AtomicReference<Boolean>()
        try {
            val engine = thread { result.set(export(writer, image, afterPiece = { handed.incrementAndGet() }, zeroAtEnd = false)) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (handed.get() < MemoryStore.QUEUE_CHUNKS && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals("the engine's thread sealed ahead of the disk", MemoryStore.QUEUE_CHUNKS, handed.get())
            Thread.sleep(200)
            assertEquals("and then waits for a free buffer, not for the disk", MemoryStore.QUEUE_CHUNKS, handed.get())
            assertTrue(engine.isAlive)

            val cancelled = System.nanoTime()
            store.cancelSave()
            engine.join(2_000)
            assertFalse("a cancel lets the engine's thread go", engine.isAlive)
            assertTrue("within one chunk", System.nanoTime() - cancelled < TimeUnit.SECONDS.toNanos(1))
            assertEquals(false, result.get())
            writer.close()
            for (index in 0 until writer.buffer.capacity()) assertEquals("the plaintext buffer is overwritten", 0, writer.buffer.get(index).toInt())
        } finally {
            stuck.countDown()
        }
        assertTrue(store.awaitStopped(5_000))
        synchronized(waiting) {
            assertTrue(waiting.isNotEmpty())
            for (sealed in waiting) assertFalse("plaintext waited for the disk", String(sealed, Charsets.ISO_8859_1).contains(text))
        }
        assertEquals(emptyList<String>(), leftInDirectory())
    }

    /** A disk that fails in the middle: the failure reaches the engine's thread at the next piece or at the commit, and nothing is kept. */
    @Test
    fun aWriteThatFailsEndsTheSave() {
        chat()
        beforeAppend = { throw IOException("No space left on the device") }
        val image = image(3 * SMALL)
        val store = store()
        assertThrows(IOException::class.java) { store.save(memoryOf(image), image) }
        assertTrue(store.awaitStopped(5_000))
        assertEquals(emptyList<String>(), leftInDirectory())
    }

    /** The memory is held to the size its header gave, and its header to the first piece. */
    @Test
    fun aSaveOfAnotherSizeThanAnnouncedOrWithItsHeaderChangedIsRefused() {
        chat()
        val image = image(2 * SMALL)
        val store = store()
        store.beginSave(memoryOf(image).copy(imageBytes = image.size + 1L)).use { writer ->
            assertTrue(export(writer, image))
            assertThrows(IOException::class.java) { writer.commit() }
        }
        store.beginSave(memoryOf(image)).use { writer ->
            writer.buffer.put(0, 9)
            assertThrows(IOException::class.java) { writer.write(writer.buffer.capacity(), last = false) }
        }
        assertTrue(store.awaitStopped(5_000))
        assertEquals(emptyList<String>(), leftInDirectory())
    }

    /** The buffers the engine is given are those it can take, and the header fits in the first piece. */
    @Test
    fun theBuffersAreDirectAndLargeEnoughForTheNativeSession() {
        chat()
        val store = store()
        store.beginSave(memoryOf(ByteArray(0))).use { writer ->
            assertTrue(writer.buffer.isDirect)
            assertEquals(SMALL, writer.buffer.capacity())
            assertTrue(writer.firstPieceOffset in 1 until writer.buffer.capacity())
        }
        assertTrue(MemoryHeader.MAX_SIZE < NativeLlama.MEMORY_PIECE_BYTES_MIN)
    }

    /**
     * A save whose engine thread waits for the disk (every
     * sealed buffer queued behind a chunk the disk does not take) hears the engine's own stop
     * condition (a request, a forgetting that is queued, the caller), not only the store's cancel:
     * the engine's thread is let go within a chunk of it, and the close does not wait for the
     * writer that is still in the disk. Catches a save that holds the engine, and with it a
     * queued forgetting and "Lock now", for as long as the disk does not answer.
     */
    @Test(timeout = 60_000)
    fun theEnginesOwnStopConditionEndsEveryWaitOnTheEnginesThread() {
        chat()
        val disk = CountDownLatch(1)
        val inTheDisk = CountDownLatch(1)
        beforeAppend = {
            inTheDisk.countDown()
            disk.await(30, TimeUnit.SECONDS)
        }
        val stop = AtomicBoolean(false)
        val image = image(6 * SMALL)
        val store = store()
        val writer = store.beginSave(memoryOf(image), stopped = { stop.get() })
        val exported = AtomicReference<Boolean>()
        val engine = thread {
            try {
                exported.set(export(writer, image))
            } finally {
                writer.close()
            }
        }
        try {
            assertTrue("the first chunk reached the disk, which does not answer", inTheDisk.await(20, TimeUnit.SECONDS))
            engine.join(300)
            assertTrue("the engine's thread waits for a free buffer", engine.isAlive)

            stop.set(true)
            engine.join(5_000)
            assertFalse("let go by the engine's own condition, with the disk still stuck", engine.isAlive)
            assertEquals(false, exported.get())
        } finally {
            disk.countDown()
        }
        assertTrue(store.awaitStopped(10_000))
        assertFalse(memoryFile().exists())
        noTemporaryFile()
    }

    /**
     * A writer thread left in the disk by the store of a session
     * that has ended, which finally gives up after the next session's store began a save of the
     * same chat, removes only its own temporary file, and the later save is kept. Catches a
     * temporary name shared by every save of a chat.
     */
    @Test(timeout = 60_000)
    fun aWriterLeftByAnEarlierStoreCannotTakeALaterSavesTemporaryFile() {
        chat()
        val stuck = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstAppend = AtomicBoolean(true)
        beforeAppend = {
            if (firstAppend.compareAndSet(true, false)) {
                stuck.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }
        val old = store()
        val first = image(SMALL + 100, seed = 1)
        val oldWriter = old.beginSave(memoryOf(first))
        assertTrue(export(oldWriter, first))
        val oldEngine = thread {
            try {
                oldWriter.commit()
            } catch (_: IOException) {
                // Given up: the session closed.
            } finally {
                oldWriter.close()
            }
        }
        try {
            assertTrue(stuck.await(20, TimeUnit.SECONDS))
            // The session closes: its save is cancelled, and its writer thread stays in the disk.
            old.cancelSave()
            oldEngine.join(5_000)
            assertFalse(oldEngine.isAlive)
            assertFalse(old.awaitStopped(0))

            // The next session saves the same chat; the old writer returns before the commit.
            val later = image(2 * SMALL + 7, seed = 2)
            val store = store()
            store.beginSave(memoryOf(later)).use { writer ->
                assertTrue(export(writer, later))
                release.countDown()
                assertTrue("the old writer has returned and removed what was its own", old.awaitStopped(10_000))
                writer.commit()
            }
            assertArrayEquals("the later save was kept", later, store.restored())
            noTemporaryFile()
        } finally {
            release.countDown()
        }
    }

    /** The bytes of every text are overwritten once the digest has read them. */
    @Test
    fun theDigestOverwritesTheBytesOfEveryTextItRead() {
        val messages = messagesOf("c1")
        val seen = ArrayList<ByteArray>()
        val digest = MemoryStore.digestOf(messages) { seen += it }
        assertEquals(messages.size, seen.size)
        assertEquals(messages.map { it.text.toByteArray(Charsets.UTF_8).size }, seen.map { it.size })
        assertTrue("every array is zeros", seen.all { bytes -> bytes.all { it == 0.toByte() } })
        assertArrayEquals("and the digest is as before", digest, MemoryStore.digestOf(messages))
    }

    /**
     * A memory made with one system prompt is refused, and deleted, by a restore begun once the
     * conversation would be asked with another: the binding beside the messages' digest. Catches
     * a replaced prompt whose deletion did not happen being read back into the engine.
     */
    @Test
    fun aRestoreOfAMemoryMadeWithAnotherSystemPromptIsRefusedAndDeletes() {
        chat()
        val image = image(SMALL + 9)
        assertTrue(store().save(memoryOf(image), image))
        assertArrayEquals("with the same prompt it comes back", image, store().restored())

        requestTexts["model-a"] = RequestText("Answer at length.", null)
        val refused = assertThrows(IOException::class.java) { store().beginRestore() }
        assertFalse("a refusal, not a memory that cannot be told now", refused is MemoryVaultUnavailableException)
        assertEquals(emptyList<String>(), leftInDirectory())
    }

    /**
     * The digest of the request text: none and empty differ, so do the system prompt and the
     * further text holding the same words, and the bytes of every text are overwritten once read.
     */
    @Test
    fun theRequestDigestTellsItsTextsApartAndOverwritesTheirBytes() {
        val digests = listOf(
            RequestText(null, null),
            RequestText("", null),
            RequestText(null, ""),
            RequestText("Words", null),
            RequestText(null, "Words"),
            RequestText("Words", "Words"),
            RequestText("Word", "sWords"),
        ).map { MemoryStore.requestDigestOf(it).toList() }
        assertEquals("every one differs", digests.size, digests.toSet().size)

        val seen = ArrayList<ByteArray>()
        val text = RequestText("A private system prompt", "Some tool text")
        val digest = MemoryStore.requestDigestOf(text) { seen += it }
        assertEquals(listOf(23, 14), seen.map { it.size })
        assertTrue("every array is zeros", seen.all { bytes -> bytes.all { it == 0.toByte() } })
        assertArrayEquals("and the digest is as before", digest, MemoryStore.requestDigestOf(text))
    }

    /**
     * The close of a save overwrites the buffer the engine was handed, committed or not: the
     * plaintext of the last piece must not stay in it. Catches a close that leaves it.
     */
    @Test
    fun theCloseOfASaveOverwritesItsBuffer() {
        chat()
        for (commit in listOf(true, false)) {
            val image = image(SMALL + 300, seed = if (commit) 3 else 4)
            val writer = store().beginSave(memoryOf(image))
            assertTrue(export(writer, image, zeroAtEnd = false))
            assertTrue("the last piece is in the buffer before the close", (0 until writer.buffer.capacity()).any { writer.buffer.get(it) != 0.toByte() })
            if (commit) writer.commit()
            writer.close()
            assertTrue("commit $commit: every byte is zero", (0 until writer.buffer.capacity()).all { writer.buffer.get(it) == 0.toByte() })
        }
    }

    private companion object {
        /** The least buffer the native session takes, as the chunk of the streams here. */
        const val SMALL = NativeLlama.MEMORY_PIECE_BYTES_MIN
    }
}
