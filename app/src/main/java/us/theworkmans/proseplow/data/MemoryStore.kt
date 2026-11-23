package us.theworkmans.proseplow.data

import us.theworkmans.proseplow.core.chat.ChatMessage
import us.theworkmans.proseplow.core.chat.Clock
import us.theworkmans.proseplow.core.chat.MessageExpiry
import us.theworkmans.proseplow.core.chat.MessageLifetime
import us.theworkmans.proseplow.core.engine.MemoryReader
import us.theworkmans.proseplow.core.engine.MemoryVault
import us.theworkmans.proseplow.core.engine.MemoryVaultUnavailableException
import us.theworkmans.proseplow.core.engine.MemoryWriter
import us.theworkmans.proseplow.core.engine.SavedMemory
import us.theworkmans.proseplow.security.AppFiles
import us.theworkmans.proseplow.security.ChunkCipher
import us.theworkmans.proseplow.security.Purposes
import us.theworkmans.proseplow.security.SealedStream
import us.theworkmans.proseplow.security.UndecryptableException
import us.theworkmans.proseplow.security.wipe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * What [MemoryStore] needs to know of the conversation a memory is of. The session implements
 * it, because it holds the chats.
 */
fun interface MemoryConversations {

    /**
     * The chat that [conversationKey] names ([SavedMemory.conversationKey]), with its messages as
     * they are now (as stored, after any pruning), the text besides them that a request of it
     * to the model [modelId] ([SavedMemory.modelId]) would carry now, and the text that the last
     * request of it the engine has read carried; or null when there is no such chat any more, or
     * no such model. Called on the engine's thread; may block on the disk.
     *
     * @throws IOException when it cannot be told now. That says nothing about the memory, which
     *   is then not deleted by the store.
     */
    @Throws(IOException::class)
    fun conversation(conversationKey: String, modelId: String): MemoryConversation?
}

/**
 * A conversation as [MemoryConversations] reports it.
 *
 * @property chatId the chat's id: the name of its file, and of its memory's file.
 * @property messages its messages in order, as they are now.
 * @property oldestMessageMillis the date of its oldest message (the chat's entry in the list
 *   records it). A memory is dated by the earlier of this and the dates of [messages].
 * @property requestText what a request of the conversation to the model asked about carries into
 *   the prompt besides the messages, as the settings are now. A memory is usable only when this
 *   is what it was made with.
 * @property readRequestText what the last request of the conversation that the engine has read
 *   (in this unlocked session) carried besides the messages: the text the engine's memory of it
 *   holds, which a save writes into the header. Null when the engine has read no request of it
 *   in this session; its memory is then one read back from a saved memory whose header was just
 *   found equal to [requestText], and a save writes [requestText]. Recording what was read, and
 *   not what the settings say at the moment of the save, keeps a save from binding the old
 *   text's memory to a new text should the hiding of a changed conversation ever be missed.
 */
data class MemoryConversation(
    val chatId: String,
    val messages: List<ChatMessage>,
    val oldestMessageMillis: Long,
    val requestText: RequestText,
    val readRequestText: RequestText? = null,
)

/**
 * The text a request carries into the model's prompt besides the messages: what a memory was
 * made from beside them, and what it is bound to (see [MemoryStore]). The prompt format is the
 * model's, which the memory is bound to by its model id.
 *
 * @property systemPrompt the system prompt as the request carries it, or null for none.
 * @property extraSystemText the further system text of the request
 *   ([us.theworkmans.proseplow.core.engine.GenerationRequest.extraSystemText]), or null for none.
 */
data class RequestText(val systemPrompt: String?, val extraSystemText: String?)

/**
 * Which build of the app wrote a memory. A memory is only ever read back by the build that wrote
 * it, because the native state it holds is the state of one llama.cpp: an update of the app
 * costs one reading of the conversation, and no state of another llama.cpp is ever read.
 *
 * @property versionCode the app's version code.
 * @property lastUpdateMillis when the package was last installed or updated, as the platform
 *   says (`PackageInfo.lastUpdateTime`): a debug build installed again keeps its version code,
 *   but not this.
 */
data class BuildIdentity(val versionCode: Long, val lastUpdateMillis: Long)

/** The free space of the volume the memory lives on, and its size, in bytes. */
data class FreeSpace(val usableBytes: Long, val totalBytes: Long)

/**
 * The model's saved memory: the [MemoryVault] the engine is given. At most one memory is kept on
 * the device, the memory of the conversation the model last worked on, in
 * `model-memory/<chat id>.enc` as a sealed stream ([SealedStreamFile]) for the purpose
 * `model-memory:<chat id>`. The files themselves, and every deletion that needs no key, are
 * [ModelMemoryFiles]'s.
 *
 * **The store's header.** The first piece the engine hands over begins with the store's own
 * header ([MemoryWriter.firstPieceOffset]), so it sits in the first chunk of the stream and is
 * authenticated with it: the format version, the build ([BuildIdentity]), the model and the
 * length of its packaged file, the chat, the id of the chat's first message, a digest of the
 * messages the memory was made from (their ids, dates and text), a digest of the text the
 * request carried besides them ([RequestText]: the system prompt, and any further system text;
 * the text of the last request the engine read, [MemoryConversation.readRequestText], and the
 * text of now only when it has read none since the unlock),
 * and the fields of [SavedMemory]. The date of the oldest of those messages is in the stream's own header
 * ([us.theworkmans.proseplow.security.StreamHeader.oldestMessageMillis]), and is the file's date.
 *
 * **What makes a memory usable** ([saved], [beginRestore]): its first chunk authenticates and
 * holds a header of this format; the build, the model and the length of the model's file are
 * the ones of now; the chat's file exists; the chat as it is now begins with the very messages
 * the memory was made from (a chat may have more messages since, which the engine reads on top);
 * a request of the chat to the memory's model would carry the very text besides the messages that
 * the memory was made with (a system prompt that was changed or cleared since makes it unusable,
 * even where the deletion at the moment of the change did not happen); the oldest of them has not expired under the limit at the trusted time, judged by the
 * authenticated date in the header and never by the file's date; and the file's date is not
 * later than that, or the clean-up without the key would keep it too long. A memory that fails
 * any of these is deleted; nothing of it is acted on before its chunk has authenticated.
 * A memory is never deleted for what says nothing against it: a key that was destroyed (the app
 * locked) or a file or conversation that could not be read now. The store then throws
 * [MemoryVaultUnavailableException], which the engine answers without a deletion.
 *
 * **How a save is written.** The engine's thread seals each piece as a chunk where the native
 * export left it and hands the ciphertext to a writer thread of the save through a queue of
 * [QUEUE_CHUNKS] chunks; every step that touches the disk (making room, creating the file,
 * appending, the rename) is that thread's. The engine's thread therefore never waits in a disk
 * write: it waits only for a free buffer, or for a step of the writer, and every such wait ends
 * when the save is cancelled ([cancelSave]), the memory deleted, or the engine's own stop
 * condition holds (the one it hands to [beginSave]: a request, forgetting, the caller), within
 * one chunk. A disk that does not answer holds only ciphertext. Nothing of a save is flushed to the disk (see
 * [SealedStreamFile]); a deletion is.
 *
 * **Free space.** A save needs its stream's size plus a reserve free: the larger of 1 GiB and 5%
 * of the volume. When there is room for that beside the memory kept now, the old memory stays
 * until the new one has taken its name; when there is room only without it, it is deleted
 * first; when there is not room even then, it is deleted and nothing is saved.
 *
 * **Deletion wins.** The last step of a save checks the generation of [ModelMemoryFiles] and
 * renames the file under the lock every deletion takes, so a save that ends after a deletion
 * keeps nothing.
 *
 * [saved], [beginSave] and [beginRestore] are called on the engine's thread, one at a time;
 * [delete], [cancelSave] and [awaitStopped] from any thread.
 *
 * @param files the memory's files, shared with the deletions that need no key.
 * @param conversations what the chats are now.
 * @param build the build that is running.
 * @param modelFileLength the length of the packaged file of a model, by its id; null for a model
 *   this build does not carry.
 * @param lifetime how long messages are kept, as the settings say now.
 * @param clock the trusted time messages are aged by.
 * @param streamFile the sealed stream file for a memory file and its purpose.
 * @param freeSpace what the volume of a directory has free.
 * @param setFileDate sets a file's last-modified time and says whether that worked.
 * @param beforeAppend called on the writer thread with each sealed chunk before it is written:
 *   stands for the disk in the tests.
 */
class MemoryStore internal constructor(
    private val files: ModelMemoryFiles,
    private val conversations: MemoryConversations,
    private val build: BuildIdentity,
    private val modelFileLength: (modelId: String) -> Long?,
    private val lifetime: () -> MessageLifetime,
    private val clock: Clock,
    private val streamFile: (file: File, purpose: String) -> SealedStreamFile,
    private val freeSpace: (directory: File) -> FreeSpace,
    private val setFileDate: (File, Long) -> Boolean,
    private val beforeAppend: (sealed: ByteBuffer) -> Unit,
) : MemoryVault {

    /**
     * The store over [files] that seals its streams with [cipher] in chunks of the format's size;
     * see the class comment for the rest. [freeSpace] is the platform's to answer: on Android the
     * space that can be allocated (`StorageManager.getAllocatableBytes`) counts cached data the
     * system would clear, which `File.getUsableSpace` does not.
     */
    constructor(
        files: ModelMemoryFiles,
        cipher: ChunkCipher,
        conversations: MemoryConversations,
        build: BuildIdentity,
        modelFileLength: (modelId: String) -> Long?,
        lifetime: () -> MessageLifetime,
        clock: Clock,
        freeSpace: (directory: File) -> FreeSpace,
    ) : this(
        files, conversations, build, modelFileLength, lifetime, clock,
        streamFile = { file, purpose -> SealedStreamFile(file, purpose, cipher) },
        freeSpace = freeSpace,
        setFileDate = { file, millis -> file.setLastModified(millis) },
        beforeAppend = {},
    )

    /** One file object per chat, because it is what excludes two writes of the same file. */
    private val streams = HashMap<String, SealedStreamFile>()

    /** The save in progress, from [beginSave] until it is closed. */
    @Volatile private var active: Save? = null

    /** The writer threads that may still be running. */
    private val writers = ArrayList<Thread>()

    // --- The vault ---

    /**
     * The memory kept, when it passes every check of the class comment; otherwise null, and
     * whatever was there has been deleted. More than one memory file, or one whose name no chat
     * has, is deleted too: the store never leaves that.
     *
     * @throws MemoryVaultUnavailableException once the app has locked (the key is destroyed), and
     *   when the file or the conversation could not be read: neither says anything about the
     *   memory, which is not deleted for them.
     */
    @Throws(IOException::class)
    override fun saved(): SavedMemory? {
        val chatId = onlyMemory() ?: return null
        val opened = try {
            openChecked(chatId)
        } catch (_: RefusedMemory) {
            deleteQuietly()
            return null
        } catch (e: IOException) {
            throw cannotTell(e)
        } ?: return null
        opened.reader.close()
        return opened.header.memory
    }

    /**
     * Begins a save of the memory described by [memory]; see [MemoryVault.beginSave] and the
     * class comment. Refused (with an [IOException], nothing written and the memory kept as it
     * was) for a conversation that is not known, has no messages, has a message without a usable
     * date (none, or one after now) or whose oldest message has expired already, for a model this
     * build does not carry, and when the key has gone.
     */
    @Throws(IOException::class)
    override fun beginSave(memory: SavedMemory, stopped: () -> Boolean): MemoryWriter {
        check(active == null) { "A save of the memory is under way" }
        val startGeneration = files.generation
        val conversation = conversations.conversation(memory.conversationKey, memory.modelId) ?: throw IOException("The conversation is not known")
        val chatId = conversation.chatId
        if (!SealedDirectory.isValidId(chatId)) throw IOException("Not an id that can name a file")
        val messages = conversation.messages
        if (messages.isEmpty()) throw IOException("A conversation without messages")
        val now = clock.nowMillis()
        if (messages.any { it.createdAtMillis <= 0 || it.createdAtMillis > now }) throw IOException("A message has no usable date")
        val oldest = minOf(conversation.oldestMessageMillis, messages.minOf { it.createdAtMillis })
        if (oldest <= 0 || MessageExpiry.fileExpired(oldest, lifetime(), now)) throw IOException("The oldest message has no usable date, or has expired")
        val modelLength = modelFileLength(memory.modelId) ?: throw IOException("The model is not part of this build")
        if (memory.imageBytes < 0) throw IOException("An image cannot have a negative size")
        val header = try {
            // What the memory was read with, not what the settings say now (see MemoryConversation).
            val requestDigest = requestDigestOf(conversation.readRequestText ?: conversation.requestText)
            MemoryHeader(build, modelLength, chatId, messages.first().id, messages.size, digestOf(messages), requestDigest, memory).encode()
        } catch (e: IllegalArgumentException) {
            throw IOException("The header of the memory is too long", e)
        }
        val stream = streamOf(chatId)
        if (header.size >= stream.chunkBytes) throw IOException("The header of the memory does not fit its first chunk")
        val save = Save(stream, header, header.size + memory.imageBytes, oldest, startGeneration, stopped)
        synchronized(writers) {
            writers.removeAll { !it.isAlive }
            writers += save.thread
        }
        active = save
        try {
            save.open()
        } catch (e: Throwable) {
            save.close()
            throw e
        }
        return save
    }

    /**
     * Begins reading the memory back; see [MemoryVault.beginRestore]. Every check of [saved] is
     * made again on the first chunk, which is then the first piece handed over; every later
     * piece is a chunk that has authenticated, and the last read returns 0 only once the stream
     * has ended with its last chunk and is as long as its header says. A memory that was read and
     * refused, here or in a read, is deleted (a read then returns -1); one that cannot be told
     * now (the key destroyed by a lock, a read of the disk that failed) is kept, and
     * [MemoryVaultUnavailableException] is thrown, here or from the read.
     */
    @Throws(IOException::class)
    override fun beginRestore(): MemoryReader {
        try {
            val chatId = onlyMemory() ?: throw MemoryVaultUnavailableException("No memory is kept now")
            val opened = openChecked(chatId) ?: throw MemoryVaultUnavailableException("No memory is kept now")
            return Restore(opened)
        } catch (e: RefusedMemory) {
            deleteQuietly()
            throw IOException("The kept memory does not pass its checks", e)
        } catch (e: IOException) {
            throw cannotTell(e)
        } catch (e: Exception) {
            deleteQuietly()
            throw e
        }
    }

    /**
     * [e], which says nothing against the memory (a destroyed key, a read of the disk that
     * failed, a conversation that could not be read), as the engine is to hear it. A refusal is
     * never one of these: [openChecked] turns every chunk that does not authenticate into
     * [RefusedMemory] before it gets here, and a read of the stream turns it into a deletion.
     */
    private fun cannotTell(e: IOException): MemoryVaultUnavailableException =
        e as? MemoryVaultUnavailableException ?: MemoryVaultUnavailableException("The kept memory cannot be read now", e)

    /**
     * Deletes the memory kept and stops a save that is under way, which keeps nothing. Needs no
     * key.
     *
     * @throws IOException when a file could not be deleted.
     */
    @Throws(IOException::class)
    override fun delete() {
        active?.cancel()
        files.deleteAll()
    }

    // --- For the session ---

    /**
     * Stops the save in progress, from any thread, without waiting for anything: the engine's
     * thread is let go within one chunk (its sink returns false, or the step it waits for
     * fails), and nothing of the save is kept. The writer thread removes the temporary file when
     * the disk lets it.
     */
    fun cancelSave() {
        active?.cancel()
    }

    /**
     * Waits up to [timeoutMillis] for every writer thread this store started to end: after that,
     * nothing of this store touches a file. Returns whether they all ended.
     */
    fun awaitStopped(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val threads = synchronized(writers) { writers.toList() }
        for (thread in threads) {
            val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (left > 0) thread.join(left)
            if (thread.isAlive) return false
        }
        return true
    }

    // --- Checking ---

    /** The one memory file's chat id; null when there is none. Anything else is deleted. */
    private fun onlyMemory(): String? {
        val stored = files.storedFiles()
        if (stored.isEmpty()) return null
        val id = stored.singleOrNull()?.name?.removeSuffix(AppFiles.ENCRYPTED_SUFFIX)?.takeIf(SealedDirectory::isValidId)
        if (id == null) deleteQuietly()
        return id
    }

    /** What [openChecked] opened: the reader, standing after the first chunk, which it still holds. */
    private class Opened(val reader: SealedStreamReader, val header: MemoryHeader, val first: ByteBuffer, val firstLength: Int)

    /**
     * Opens the memory of [chatId] and makes every check of the class comment on its first
     * chunk. Null when the file has gone meanwhile.
     *
     * @throws RefusedMemory when it does not pass them.
     */
    @Throws(IOException::class, RefusedMemory::class)
    private fun openChecked(chatId: String): Opened? {
        val reader = try {
            streamOf(chatId).read()
        } catch (_: UndecryptableException) {
            throw RefusedMemory()
        } ?: return null
        var kept = false
        try {
            val first = try {
                reader.next()
            } catch (_: UndecryptableException) {
                throw RefusedMemory()
            } ?: throw RefusedMemory()
            // Everything below acts on the chunk that has just authenticated, and on its header.
            val header = MemoryHeader.decode(first) ?: throw RefusedMemory()
            verify(chatId, header, reader.header.oldestMessageMillis)
            kept = true
            return Opened(reader, header, first, first.remaining())
        } finally {
            if (!kept) reader.close()
        }
    }

    @Throws(IOException::class, RefusedMemory::class)
    private fun verify(chatId: String, header: MemoryHeader, oldestMillis: Long) {
        if (header.build != build || header.chatId != chatId) throw RefusedMemory()
        val modelLength = modelFileLength(header.memory.modelId)
        if (modelLength == null || modelLength != header.modelFileLength) throw RefusedMemory()
        if (!files.chatFileFor(chatId).isFile) throw RefusedMemory()
        val conversation = conversations.conversation(header.memory.conversationKey, header.memory.modelId) ?: throw RefusedMemory()
        if (conversation.chatId != chatId) throw RefusedMemory()
        val messages = conversation.messages
        if (header.messageCount < 1 || messages.size < header.messageCount) throw RefusedMemory()
        val builtFrom = messages.subList(0, header.messageCount)
        if (builtFrom.first().id != header.firstMessageId) throw RefusedMemory()
        if (!MessageDigest.isEqual(digestOf(builtFrom), header.digest)) throw RefusedMemory()
        if (!MessageDigest.isEqual(requestDigestOf(conversation.requestText), header.requestDigest)) throw RefusedMemory()
        // The date that decides is the header's, which the chunk has just proven; the file's
        // date is only what the file says.
        if (oldestMillis <= 0 || builtFrom.any { it.createdAtMillis < oldestMillis }) throw RefusedMemory()
        if (MessageExpiry.fileExpired(oldestMillis, lifetime(), clock.nowMillis())) throw RefusedMemory()
        val fileDate = files.fileFor(chatId).lastModified()
        if (fileDate <= 0 || fileDate > oldestMillis) throw RefusedMemory()
    }

    private fun deleteQuietly() {
        try {
            delete()
        } catch (_: IOException) {
            // The rules of ModelMemoryFiles.sweep and these checks catch the file next time.
        }
    }

    @Synchronized
    private fun streamOf(chatId: String): SealedStreamFile =
        streams.getOrPut(chatId) { streamFile(files.fileFor(chatId), purposeOf(chatId)) }

    // --- Restoring ---

    /** One restore; see [beginRestore]. Called on the engine's thread only. */
    private inner class Restore(private val opened: Opened) : MemoryReader {
        override val memory: SavedMemory = opened.header.memory

        /** The reader's own buffer: every chunk is opened into it, and handed over where it lies. */
        override val buffer: ByteBuffer = opened.first
        override val firstPieceOffset: Int = opened.header.size
        private val expected = firstPieceOffset.toLong() + memory.imageBytes
        private var firstPending = true
        private var total = 0L
        private var ended = false
        private var failed = false

        override fun read(): Int {
            if (failed) return -1
            if (ended) return 0
            return try {
                val length = if (firstPending) {
                    firstPending = false
                    opened.firstLength
                } else {
                    val chunk = opened.reader.next()
                    if (chunk == null) {
                        if (total != expected) return fail()
                        ended = true
                        return 0
                    }
                    chunk.remaining()
                }
                total += length
                if (total > expected) fail() else length
            } catch (_: UndecryptableException) {
                fail()
            } catch (e: IOException) {
                // The key was destroyed, or the disk did not answer: nothing against the memory.
                failed = true
                opened.reader.close()
                throw cannotTell(e)
            } catch (_: Exception) {
                fail()
            }
        }

        private fun fail(): Int {
            failed = true
            opened.reader.close()
            deleteQuietly()
            return -1
        }

        override fun close() {
            opened.reader.close()
        }
    }

    // --- Saving ---

    /**
     * One save; see the class comment. [write] and [commit] run on the engine's thread, the
     * steps that touch the disk on [thread].
     */
    private inner class Save(
        private val stream: SealedStreamFile,
        private val header: ByteArray,
        private val expected: Long,
        private val oldestMillis: Long,
        private val startGeneration: Long,
        /** The engine's own stop condition ([MemoryVault.beginSave]); asked on the engine's thread only. */
        private val engineStopped: () -> Boolean,
    ) : MemoryWriter {
        override val buffer: ByteBuffer = ByteBuffer.allocateDirect(stream.chunkBytes)
        override val firstPieceOffset: Int = header.size

        /** The sealed buffers not in use: ciphertext only, ever. */
        private val free = ArrayBlockingQueue<ByteBuffer>(QUEUE_CHUNKS)
        private val tasks = LinkedBlockingQueue<SaveTask>()
        val thread = Thread(::drain, WRITER_NAME).apply { isDaemon = true }

        @Volatile private var cancelRequested = false

        @Volatile private var abandoned = false

        @Volatile private var failure: IOException? = null

        @Volatile private var writer: SealedStreamWriter? = null
        private var pieces = 0
        private var written = 0L
        private var sawLast = false
        private var committed = false
        private var closed = false

        init {
            repeat(QUEUE_CHUNKS) { free.add(ByteBuffer.allocateDirect(stream.chunkBytes + SealedStream.CHUNK_OVERHEAD)) }
            thread.start()
        }

        /** Makes room and creates the temporary file on the writer thread, then puts the header in place. */
        @Throws(IOException::class)
        fun open() {
            perform {
                if (files.generation != startGeneration) throw IOException("The memory was deleted before the save began")
                makeRoom()
                writer = stream.write(oldestMillis)
            }
            for (index in header.indices) buffer.put(index, header[index])
        }

        override fun write(bytes: Int, last: Boolean): Boolean {
            check(!closed && !committed && !sawLast) { "A piece after the last, or after the save ended" }
            if (stopped()) {
                abandon()
                return false
            }
            failure?.let {
                abandon()
                throw it
            }
            if (pieces == 0) {
                for (index in header.indices) if (buffer.get(index) != header[index]) throw IOException("The header of the memory was changed")
            }
            val sealed = takeFree() ?: run {
                abandon()
                return false
            }
            val plaintext = buffer.duplicate()
            plaintext.clear()
            plaintext.limit(bytes)
            try {
                checkNotNull(writer).seal(plaintext, last, sealed)
            } catch (e: Exception) {
                sealed.clear()
                free.add(sealed)
                abandon()
                throw e
            }
            sealed.flip()
            tasks.add(SaveTask.Chunk(sealed))
            pieces++
            written += bytes
            sawLast = last
            return true
        }

        override fun commit() {
            check(!closed && !committed) { "The save has ended" }
            check(sawLast) { "A commit before the last piece" }
            if (written != expected) {
                abandon()
                throw IOException("The memory is not as long as its header says")
            }
            perform {
                failure?.let { throw it }
                val writing = checkNotNull(writer)
                files.exclusive {
                    writing.commit { temp ->
                        if (files.generation != startGeneration) throw IOException("The memory was deleted while it was being saved")
                        if (!setFileDate(temp, oldestMillis)) throw IOException("The date of the memory could not be set")
                        val date = temp.lastModified()
                        if (date <= 0 || date > oldestMillis) throw IOException("The date of the memory did not hold")
                        // The memory of another chat goes now; this chat's own is replaced by the rename.
                        files.deleteOthersLocked(stream.file, temp)
                    }
                }
            }
            committed = true
        }

        override fun close() {
            if (closed) return
            closed = true
            if (!committed) abandon()
            // The writer thread removes the temporary file of a save that was not committed, after
            // whatever it is still writing, and ends.
            tasks.add(
                SaveTask.Step(
                    FutureTask {
                        writer?.close()
                        Unit
                    },
                ),
            )
            tasks.add(SaveTask.End)
            // Waits for that, unless the save was stopped from outside or by the engine's own
            // condition: the writer thread then removes the file when the disk lets it, and
            // awaitStopped waits for it.
            while (thread.isAlive && !cancelRequested && files.generation == startGeneration && !engineStopped()) thread.join(POLL_MILLIS)
            buffer.wipe()
            if (active === this) active = null
        }

        /** From outside: see [cancelSave]. */
        fun cancel() {
            cancelRequested = true
            abandon()
        }

        /**
         * Whether the save is to be given up: cancelled from outside, abandoned, the memory deleted,
         * or the engine's own condition holds. On the engine's thread only, which is where it is asked.
         */
        private fun stopped(): Boolean = cancelRequested || abandoned || files.generation != startGeneration || engineStopped()

        /** Gives the save up: nothing more is sealed, appended or renamed, and [close] removes what was written. */
        private fun abandon() {
            abandoned = true
            writer?.cancel()
        }

        /** A free sealed buffer, waiting while the disk catches up; null when the save was stopped meanwhile. */
        private fun takeFree(): ByteBuffer? {
            while (true) {
                free.poll(POLL_MILLIS, TimeUnit.MILLISECONDS)?.let { return it }
                if (stopped()) return null
            }
        }

        /** Runs [work] on the writer thread, after everything handed to it before, and waits for it as long as the save is not stopped. */
        @Throws(IOException::class)
        private fun perform(work: () -> Unit) {
            val step = FutureTask { work() }
            tasks.add(SaveTask.Step(step))
            while (true) {
                try {
                    step.get(POLL_MILLIS, TimeUnit.MILLISECONDS)
                    return
                } catch (_: TimeoutException) {
                    if (stopped()) {
                        abandon()
                        throw IOException("The save of the memory was stopped")
                    }
                } catch (e: ExecutionException) {
                    abandon()
                    throw e.cause as? IOException ?: e.cause as? RuntimeException ?: IOException(e.cause)
                }
            }
        }

        /** Holding no lock, on the writer thread: makes room for the stream; see the class comment. */
        @Throws(IOException::class)
        private fun makeRoom() {
            val directory = files.directory
            if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) throw IOException("The memory's directory could not be created")
            val space = freeSpace(directory)
            val needed = SealedStream.sealedSize(expected) + maxOf(RESERVE_MIN_BYTES, space.totalBytes / RESERVE_SHARE)
            if (space.usableBytes >= needed) return
            // Everything the deletion below removes: the memory kept, and what writes left beside it.
            val old = directory.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }
            files.exclusive { files.deleteOthersLocked() }
            if (space.usableBytes + old < needed) throw IOException("Too little free space for the memory")
        }

        /** The writer thread: appends the sealed chunks and runs the steps, in the order they came. */
        private fun drain() {
            while (true) {
                when (val task = tasks.take()) {
                    SaveTask.End -> return
                    is SaveTask.Step -> task.work.run()
                    is SaveTask.Chunk -> {
                        try {
                            val writing = writer
                            if (failure == null && !abandoned && writing != null) {
                                beforeAppend(task.sealed)
                                writing.append(task.sealed)
                            }
                        } catch (e: IOException) {
                            failure = e
                        } catch (e: RuntimeException) {
                            failure = IOException("A chunk of the memory could not be written", e)
                        } finally {
                            task.sealed.clear()
                            free.add(task.sealed)
                        }
                    }
                }
            }
        }
    }

    companion object {
        /** The name space of the purpose a memory is sealed for ([Purposes]): permanent. */
        const val PURPOSE_NAMESPACE = "model-memory"

        /** The purpose the memory of the chat [chatId] is sealed for. */
        fun purposeOf(chatId: String): String = Purposes.of(PURPOSE_NAMESPACE, chatId)

        /** How many sealed chunks may wait for the disk. */
        const val QUEUE_CHUNKS = 3

        /** The least free space a save leaves, beside 1/[RESERVE_SHARE] of the volume. */
        const val RESERVE_MIN_BYTES: Long = 1L shl 30

        /** A save leaves 1/20 (5%) of the volume free, or [RESERVE_MIN_BYTES] when that is more. */
        const val RESERVE_SHARE = 20

        private const val POLL_MILLIS = 20L
        private const val WRITER_NAME = "model-memory-writer"

        /**
         * The digest a memory's header carries of the messages it was made from: SHA-256 over a
         * fixed label and, for every message in order, the length and UTF-8 bytes of its id, its
         * date, and the length and UTF-8 bytes of its text.
         *
         * The bytes of each text are a copy of the conversation that the runtime would otherwise
         * keep until it is collected; they are overwritten with zeros as soon as the digest has
         * read them ([textBytesSeen] sees each array just before that, for the tests). The
         * strings themselves cannot be overwritten (MEM-05).
         */
        internal fun digestOf(messages: List<ChatMessage>, textBytesSeen: (ByteArray) -> Unit = {}): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(DIGEST_LABEL.toByteArray(Charsets.UTF_8))
            val number = ByteArray(8)
            fun put(value: Long, size: Int) {
                for (byte in 0 until size) number[byte] = (value ushr (8 * (size - 1 - byte))).toByte()
                digest.update(number, 0, size)
            }
            for (message in messages) {
                val id = message.id.toByteArray(Charsets.UTF_8)
                put(id.size.toLong(), 4)
                digest.update(id)
                put(message.createdAtMillis, 8)
                val text = message.text.toByteArray(Charsets.UTF_8)
                try {
                    put(text.size.toLong(), 4)
                    digest.update(text)
                } finally {
                    text.fill(0)
                    textBytesSeen(text)
                }
            }
            return digest.digest()
        }

        private const val DIGEST_LABEL = "proseplow model memory messages v1"

        /**
         * The digest a memory's header carries of the text the request carried besides the
         * messages ([RequestText]): SHA-256 over a fixed label and, for the system prompt and
         * then the further system text, one byte that is 0 for none and 1 for some, followed for
         * some by its length and its UTF-8 bytes. None and an empty text therefore differ. The
         * bytes are overwritten with zeros as soon as the digest has read them ([textBytesSeen]
         * sees each array just before that, for the tests), as in [digestOf].
         */
        internal fun requestDigestOf(text: RequestText, textBytesSeen: (ByteArray) -> Unit = {}): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(REQUEST_DIGEST_LABEL.toByteArray(Charsets.UTF_8))
            for (part in listOf(text.systemPrompt, text.extraSystemText)) {
                if (part == null) {
                    digest.update(0.toByte())
                    continue
                }
                digest.update(1.toByte())
                val bytes = part.toByteArray(Charsets.UTF_8)
                try {
                    val size = bytes.size
                    digest.update(byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()))
                    digest.update(bytes)
                } finally {
                    bytes.fill(0)
                    textBytesSeen(bytes)
                }
            }
            return digest.digest()
        }

        private const val REQUEST_DIGEST_LABEL = "proseplow model memory request text v1"
    }
}

/** What the writer thread of a save is handed. */
private sealed interface SaveTask {
    class Chunk(val sealed: ByteBuffer) : SaveTask

    class Step(val work: FutureTask<Unit>) : SaveTask

    data object End : SaveTask
}

/** A memory that does not pass the store's checks; it is deleted. Not an [IOException], so that no failure of the disk is taken for one. */
private class RefusedMemory : Exception()

/**
 * The store's own header at the start of a memory's first chunk; see [MemoryStore] and the
 * exact form in FEATURES.md (FMT-22). All numbers have their most significant byte first; a text
 * is its length in UTF-8 bytes (2 bytes) followed by those bytes.
 *
 * @property modelFileLength the length of the model's packaged file in bytes.
 * @property firstMessageId the id of the chat's first message when the memory was made.
 * @property messageCount how many of the chat's messages, from its first, the memory was made from.
 * @property digest [MemoryStore.digestOf] those messages.
 * @property requestDigest [MemoryStore.requestDigestOf] the text the request carried besides them.
 * @property memory the engine's own description of the memory, its model id included.
 */
internal class MemoryHeader(
    val build: BuildIdentity,
    val modelFileLength: Long,
    val chatId: String,
    val firstMessageId: String,
    val messageCount: Int,
    val digest: ByteArray,
    val requestDigest: ByteArray,
    val memory: SavedMemory,
) {
    /** The length of the encoded header in bytes, which is where the image begins. */
    val size: Int by lazy { encode().size }

    /**
     * The header as it is stored.
     *
     * @throws IllegalArgumentException when a text is longer than [MAX_TEXT_BYTES], or a digest
     *   is not [DIGEST_BYTES] long.
     */
    fun encode(): ByteArray {
        require(digest.size == DIGEST_BYTES && requestDigest.size == DIGEST_BYTES) { "A digest has $DIGEST_BYTES bytes" }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeShort(FORMAT_VERSION)
            // The length, set below once it is known.
            out.writeShort(0)
            out.writeLong(build.versionCode)
            out.writeLong(build.lastUpdateMillis)
            out.text(memory.modelId)
            out.writeLong(modelFileLength)
            out.text(chatId)
            out.text(firstMessageId)
            out.writeInt(messageCount)
            out.write(digest)
            out.write(requestDigest)
            out.text(memory.conversationKey)
            out.writeInt(memory.contextTokens)
            out.writeInt(memory.tokenCount)
            out.writeLong(memory.readingSeconds.toRawBits())
            out.writeLong(memory.imageBytes)
        }
        val encoded = bytes.toByteArray()
        encoded[2] = (encoded.size ushr 8).toByte()
        encoded[3] = encoded.size.toByte()
        return encoded
    }

    private fun DataOutputStream.text(value: String) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        require(encoded.size <= MAX_TEXT_BYTES) { "A text of the header is longer than $MAX_TEXT_BYTES bytes" }
        writeShort(encoded.size)
        write(encoded)
    }

    companion object {
        /** The version of this header, its first two bytes: permanent once a memory has been written with it. */
        const val FORMAT_VERSION = 1
        const val DIGEST_BYTES = 32

        /** The longest text a header holds, in UTF-8 bytes. */
        const val MAX_TEXT_BYTES = 256

        /** The longest a header can be: five texts at their longest and the fixed fields. */
        const val MAX_SIZE = 4 + 8 + 8 + 8 + 4 + 2 * DIGEST_BYTES + 4 + 4 + 8 + 8 + 5 * (2 + MAX_TEXT_BYTES)

        /**
         * The header at the start of [chunk] (from index 0 to its limit, which are left as they
         * are), or null when it does not begin with a header of this version that ends exactly
         * where its length says. Only called on a chunk that has authenticated.
         */
        fun decode(chunk: ByteBuffer): MemoryHeader? {
            val available = chunk.limit()
            if (available < 4) return null
            val version = chunk.getShort(0).toInt() and 0xffff
            val length = chunk.getShort(2).toInt() and 0xffff
            if (version != FORMAT_VERSION || length > MAX_SIZE || length > available) return null
            val bytes = ByteArray(length)
            for (index in 0 until length) bytes[index] = chunk.get(index)
            return try {
                val input = DataInputStream(ByteArrayInputStream(bytes))
                input.skipBytes(4)
                val build = BuildIdentity(input.readLong(), input.readLong())
                val modelId = input.text()
                val modelFileLength = input.readLong()
                val chatId = input.text()
                val firstMessageId = input.text()
                val messageCount = input.readInt()
                val digest = ByteArray(DIGEST_BYTES).also(input::readFully)
                val requestDigest = ByteArray(DIGEST_BYTES).also(input::readFully)
                val conversationKey = input.text()
                val memory = SavedMemory(
                    conversationKey = conversationKey,
                    modelId = modelId,
                    contextTokens = input.readInt(),
                    tokenCount = input.readInt(),
                    readingSeconds = Double.fromBits(input.readLong()),
                    imageBytes = input.readLong(),
                )
                if (input.available() != 0) return null
                MemoryHeader(build, modelFileLength, chatId, firstMessageId, messageCount, digest, requestDigest, memory)
            } catch (_: IOException) {
                null
            }
        }

        /** A text as [encode] writes it; one whose bytes are not UTF-8 as written is refused. */
        private fun DataInputStream.text(): String {
            val encoded = ByteArray(readUnsignedShort())
            if (encoded.size > MAX_TEXT_BYTES) throw IOException("A text of the header is too long")
            readFully(encoded)
            val value = String(encoded, Charsets.UTF_8)
            if (!value.toByteArray(Charsets.UTF_8).contentEquals(encoded)) throw IOException("A text of the header is not UTF-8")
            return value
        }
    }
}
