package us.theworkmans.proseplow.data

import us.theworkmans.proseplow.security.AppFiles
import us.theworkmans.proseplow.security.ChunkCipher
import us.theworkmans.proseplow.security.KeyDestroyedException
import us.theworkmans.proseplow.security.SealedStream
import us.theworkmans.proseplow.security.StreamHeader
import us.theworkmans.proseplow.security.StreamOpener
import us.theworkmans.proseplow.security.StreamSealer
import us.theworkmans.proseplow.security.UndecryptableException
import us.theworkmans.proseplow.security.deleteOrThrow
import us.theworkmans.proseplow.security.syncDirectory
import us.theworkmans.proseplow.security.tempFileFor
import us.theworkmans.proseplow.security.wipe
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.SecureRandom

/**
 * One file of the app's data in the form of a sealed stream ([SealedStream]): content too large
 * to be held in memory at once, sealed for a purpose (see `Purposes`) one chunk at a time. What
 * [SealedFile] is for a blob, this is for a stream, with these differences:
 *
 * - **Nothing is flushed when it is written.** A stream is written to a temporary file beside
 *   it and renamed, as every file of the app is, but neither the temporary file nor the directory is flushed to
 *   the disk: flushing hundreds of megabytes takes seconds that the app does not have when it
 *   leaves the screen. A power loss can therefore leave the old file, no file, or a file with
 *   holes in it under the name. That is safe because a stream is never the only copy of
 *   anything (it can be made again from what it was made from) and because a stream with a hole
 *   does not authenticate. A deletion, in contrast, is flushed like every other: what was
 *   deleted must stay deleted.
 * - **No copy is ever kept.** A stream that does not open is of no use to anyone; its store
 *   deletes it.
 * - **It carries a date in the clear**, the header's ([StreamHeader.oldestMessageMillis]), which
 *   can be read without the key ([readHeader]) and is authenticated with every chunk.
 *
 * One write at a time per object. Each write has a temporary name of its own,
 * `<name>.<16 random hexadecimal digits>.tmp`, so that a write that another object of the same
 * file began (a session that has ended, whose writer thread is still in the disk) can never
 * remove or replace a later write's temporary file when it finally gives up. A stream is read
 * through the file's own name only, so a write that never ended is never read.
 *
 * [write], [delete] and the last step of a write exclude each other; reading needs no exclusion,
 * because a reader keeps the file it opened whatever takes its name afterwards.
 *
 * @param file where the stream lives. Its name ends in [AppFiles.ENCRYPTED_SUFFIX], which is how
 *   the lock knows that encrypted data exists.
 * @param purpose what the stream is sealed for.
 */
class SealedStreamFile internal constructor(
    val file: File,
    private val purpose: String,
    private val cipher: ChunkCipher,
    private val chunkSize: Int,
) {
    constructor(file: File, purpose: String, cipher: ChunkCipher) : this(file, purpose, cipher, SealedStream.CHUNK_SIZE)

    init {
        require(file.name.endsWith(AppFiles.ENCRYPTED_SUFFIX)) { "An encrypted file's name ends in ${AppFiles.ENCRYPTED_SUFFIX}" }
        require(chunkSize > 0) { "A chunk holds at least one byte" }
    }

    /**
     * The plaintext of every chunk of this file's streams but the last, in bytes: the size of the
     * format's version ([SealedStream.CHUNK_SIZE]). A store that fills one chunk at a time sizes
     * its buffer by this, so that it never names a size of its own.
     */
    val chunkBytes: Int get() = chunkSize

    /** The write begun last. While it is open, no other write of this object begins. */
    private var writer: SealedStreamWriter? = null

    /**
     * Begins a new stream in a temporary file of its own ([temporaryFileFor]), with a header that carries [oldestMessageMillis]. The
     * file under the real name is untouched until [SealedStreamWriter.commit]; whatever happens
     * before that, [SealedStreamWriter.close] removes the temporary file again.
     *
     * @throws KeyDestroyedException once the app has locked. Nothing was created.
     * @throws IOException when the temporary file could not be created, and when an earlier
     *   write of this file has not been closed.
     */
    @Synchronized
    @Throws(IOException::class)
    fun write(oldestMessageMillis: Long): SealedStreamWriter {
        if (writer?.isOpen == true) throw IOException("An earlier write of ${file.name} has not ended")
        // The header first: once the app has locked this throws, and nothing is created on the
        // disk for a write that cannot happen.
        val sealer = StreamSealer(cipher, purpose, oldestMessageMillis, chunkSize)
        val directory = file.absoluteFile.parentFile ?: throw IOException("${file.name} has no directory")
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Could not create the directory ${directory.name}")
        }
        val temp = temporaryFileFor(file)
        val channel = FileChannel.open(temp.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        val begun = SealedStreamWriter(this, temp, channel, sealer, chunkSize)
        writer = begun
        try {
            begun.writeHeader()
        } catch (e: IOException) {
            begun.close()
            throw e
        }
        return begun
    }

    /**
     * Opens the file for reading. Only the header is read here, and nothing vouches for it yet:
     * see [SealedStreamReader.header].
     *
     * @return the reader, which the caller closes; or null when there is no such file.
     * @throws UndecryptableException when the file does not begin as a stream of a known version.
     * @throws IOException when the file exists but could not be read from the disk. That says
     *   nothing about its content.
     */
    @Throws(IOException::class)
    fun read(): SealedStreamReader? {
        val channel = try {
            FileChannel.open(file.toPath(), StandardOpenOption.READ)
        } catch (_: NoSuchFileException) {
            return null
        }
        var opened = false
        try {
            val reader = SealedStreamReader.open(channel, purpose, cipher, chunkSize)
            opened = true
            return reader
        } finally {
            if (!opened) closeQuietly(channel)
        }
    }

    /**
     * Deletes the file and every temporary file a write of it may have left beside it (whichever
     * object began that write), and flushes the directory, so that a power loss cannot bring the
     * stream back. A write of this object that is still open is cancelled first: its
     * [SealedStreamWriter.commit] fails, so what was deleted here is not put back by a write that
     * began before. A write of another object whose temporary file was deleted here can no longer
     * be renamed either, since its file is gone.
     *
     * A file that is deleted in another way (by its name, while the app is locked) needs its
     * store to make sure that no write is under way.
     *
     * @throws IOException when something could not be deleted.
     */
    @Synchronized
    @Throws(IOException::class)
    fun delete() {
        writer?.cancel()
        for (temporary in temporaryFilesOf(file)) deleteOrThrow(temporary)
        deleteOrThrow(file)
        file.absoluteFile.parentFile?.let(::syncDirectory)
    }

    /**
     * The last step of [SealedStreamWriter.commit]: gives the complete temporary file the real
     * name, unless the write was cancelled, which [delete] does under the same exclusion.
     */
    @Synchronized
    @Throws(IOException::class)
    internal fun install(from: SealedStreamWriter, temp: File, beforeRename: (File) -> Unit) {
        if (from.isCancelled) throw IOException("The write of ${file.name} was cancelled")
        beforeRename(temp)
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        private const val TEMPORARY_SUFFIX = ".tmp"
        private val random = SecureRandom()

        /**
         * A new temporary name for a write of [file]: `<name>.<16 random hexadecimal digits>.tmp`
         * beside it. It ends in `.tmp` like every temporary file of the app, which is what the
         * clean-ups go by, and never in [AppFiles.ENCRYPTED_SUFFIX], so the lock does not count
         * an unfinished write as data.
         */
        internal fun temporaryFileFor(file: File): File {
            val digits = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return File(file.absoluteFile.parentFile, "${file.name}.$digits$TEMPORARY_SUFFIX")
        }

        /**
         * Every temporary file a write of [file] may have left: those named as [temporaryFileFor]
         * names them, and `<name>.tmp`, the one name that earlier builds used for every write.
         */
        internal fun temporaryFilesOf(file: File): List<File> {
            val prefix = file.name + "."
            val named = file.absoluteFile.parentFile?.listFiles { candidate ->
                candidate.name.startsWith(prefix) && candidate.name.endsWith(TEMPORARY_SUFFIX) && candidate.isFile
            }?.toList().orEmpty()
            return (named + tempFileFor(file)).distinct()
        }

        /**
         * The header of the stream in [file], read without the key: for the app while it is
         * locked, which may delete by the date in it.
         *
         * Nothing vouches for what this returns. The header is stored in the clear, and only a
         * chunk that opens under it proves it ([SealedStreamReader.header]); so it may decide
         * that a file is deleted, and never that one is kept, restored or believed.
         *
         * @return the header, or null when there is no such file or it does not begin as a
         *   stream of a known version (too short, another magic, another version).
         * @throws IOException when the file exists but could not be read from the disk.
         */
        @Throws(IOException::class)
        fun readHeader(file: File): StreamHeader? {
            val bytes = ByteArray(SealedStream.HEADER_SIZE)
            var read = 0
            try {
                Files.newInputStream(file.toPath()).use { input ->
                    while (read < bytes.size) {
                        val count = input.read(bytes, read, bytes.size - read)
                        if (count < 0) break
                        read += count
                    }
                }
            } catch (_: NoSuchFileException) {
                return null
            }
            return SealedStream.parseHeader(bytes.copyOf(read))
        }
    }
}

/**
 * One write of a [SealedStreamFile]: seals the chunks handed to it and appends them to the
 * temporary file, then gives that file the real name.
 *
 * The plaintext of a chunk is the caller's direct buffer (for the memory of a model, the buffer
 * the native export fills, whose capacity is then [SealedStream.CHUNK_SIZE]). It is read by the
 * cipher where it lies and never copied; the writer holds no plaintext of its own, and the
 * buffer remains the caller's to overwrite. All this writer ever holds is ciphertext.
 *
 * A chunk can be sealed and appended in one call ([write]) or in two ([seal], then [append]),
 * for a caller that seals on one thread and writes to the disk on another, so that the thread
 * holding the plaintext never waits for the disk. [seal] may then run while [append] does;
 * everything else excludes each other.
 *
 * The writer must be closed. [close] after [commit] does nothing; before it, it gives the write
 * up and removes the temporary file.
 */
class SealedStreamWriter internal constructor(
    private val streamFile: SealedStreamFile,
    /** The temporary file of this write, its own for as long as the write lasts ([SealedStreamFile.temporaryFileFor]). */
    internal val temp: File,
    private val channel: FileChannel,
    private val sealer: StreamSealer,
    chunkSize: Int,
) : Closeable {

    /** The header of the stream being written. */
    val header: StreamHeader get() = sealer.header

    private val sealedChunkSize = chunkSize + SealedStream.CHUNK_OVERHEAD

    /** One sealed chunk, for [write]; made when first needed. It only ever holds ciphertext. */
    private var buffer: ByteBuffer? = null

    @Volatile private var open = true

    @Volatile private var cancelled = false
    private var appended = 0L
    private var shortAppended = false

    /** True until the write has been committed or closed. */
    internal val isOpen: Boolean get() = open

    /** True once [cancel] was called. */
    internal val isCancelled: Boolean get() = cancelled

    /** Writes the header, before anything else and before any other thread knows this writer. */
    @Throws(IOException::class)
    internal fun writeHeader() {
        val bytes = ByteBuffer.allocateDirect(SealedStream.HEADER_SIZE)
        bytes.put(header.bytes())
        bytes.flip()
        while (bytes.hasRemaining()) channel.write(bytes)
    }

    /**
     * Seals [plaintext], from its position to its limit, as the next chunk and appends it to the
     * temporary file. [plaintext] must be a direct buffer; it is only read, and left at its
     * limit.
     *
     * @param last true for the chunk that ends the stream. Every chunk before it holds exactly
     *   the chunk size ([SealedStream.CHUNK_SIZE]); the last holds from 1 byte up to that, or
     *   none when it is the only chunk.
     * @throws KeyDestroyedException once the app has locked; nothing more can be written.
     * @throws IOException when the chunk could not be written, and after [cancel] or [close].
     * @throws IllegalArgumentException for a chunk of a size the stream cannot hold there, or a
     *   buffer that is not direct.
     */
    @Synchronized
    @Throws(IOException::class)
    fun write(plaintext: ByteBuffer, last: Boolean) {
        val sealed = buffer ?: ByteBuffer.allocateDirect(sealedChunkSize).also { buffer = it }
        sealed.clear()
        seal(plaintext, last, sealed)
        sealed.flip()
        append(sealed)
    }

    /**
     * The first half of [write]: seals [plaintext] as the next chunk into [sealed], from its
     * position on, without touching the disk. [sealed] must be a direct buffer with room for
     * the plaintext and [SealedStream.CHUNK_OVERHEAD] bytes more, and a separate allocation
     * from [plaintext]. It is left at the end of what was written: flip it, and hand it to
     * [append] in the order in which the chunks were sealed.
     *
     * Throws as [write] does.
     */
    @Throws(IOException::class)
    fun seal(plaintext: ByteBuffer, last: Boolean, sealed: ByteBuffer) {
        refuseWhenEnded()
        sealer.seal(plaintext, last, sealed)
    }

    /**
     * The second half of [write]: appends one sealed chunk, [sealed] from its position to its
     * limit, to the temporary file. Chunks are appended in the order in which they were sealed;
     * the writer can count them, but cannot tell their order.
     *
     * @throws IOException when the chunk could not be written (the write is then over, and the
     *   temporary file removed), and after [cancel] or [close].
     * @throws IllegalStateException for more chunks than were sealed, a chunk after one that was
     *   not full, or something that has not the size of a sealed chunk.
     */
    @Synchronized
    @Throws(IOException::class)
    fun append(sealed: ByteBuffer) {
        refuseWhenEnded()
        val length = sealed.remaining()
        check(appended < sealer.chunks && !shortAppended) { "More chunks were appended than this stream has" }
        check(length in SealedStream.CHUNK_OVERHEAD..sealedChunkSize) { "$length bytes are not a sealed chunk" }
        try {
            while (sealed.hasRemaining()) channel.write(sealed)
        } catch (e: IOException) {
            close()
            throw e
        }
        appended++
        shortAppended = length < sealedChunkSize
    }

    /**
     * Ends the write: the temporary file, now complete, takes the real name in place of
     * whatever had it. Nothing is flushed to the disk (see [SealedStreamFile]).
     *
     * [beforeRename] is called with the temporary file when it is complete and closed, just
     * before it is renamed and under the same exclusion as [SealedStreamFile.delete]: the place
     * for a store to give the file its date, and to make sure that it still wants the file. If
     * it throws, nothing is renamed and the temporary file is removed.
     *
     * Whatever the outcome, the write is over when this returns or throws; on any failure the
     * temporary file is removed and the file under the real name is as it was.
     *
     * @throws IOException when the file could not be renamed, after [cancel] or [close], and
     *   when [SealedStreamFile.delete] ran while this write was open.
     * @throws IllegalStateException when the stream is not complete: its last chunk has not been
     *   sealed, or not every sealed chunk has been appended.
     */
    @Synchronized
    @Throws(IOException::class)
    fun commit(beforeRename: (File) -> Unit = {}) {
        refuseWhenEnded()
        var installed = false
        try {
            check(sealer.isFinished && appended == sealer.chunks) {
                "The stream is not complete: $appended of ${sealer.chunks} chunks appended, the last ${if (sealer.isFinished) "sealed" else "not sealed"}"
            }
            channel.close()
            streamFile.install(this, temp, beforeRename)
            installed = true
        } finally {
            if (!installed) {
                closeQuietly(channel)
                temp.delete()
            }
            // Last, because it lets the next write of the same object begin.
            open = false
        }
    }

    /**
     * Gives the write up without waiting for anything: from now on nothing is sealed, appended
     * or committed, each failing with an [IOException]. May be called from any thread, also while
     * a chunk is being written to a disk that does not answer. [close] is still to be called,
     * by whoever does the writing, to remove the temporary file.
     */
    fun cancel() {
        cancelled = true
    }

    /** Ends a write that was not committed and removes its temporary file; does nothing after [commit]. */
    @Synchronized
    override fun close() {
        if (!open) return
        closeQuietly(channel)
        temp.delete()
        // Last, because it lets the next write of the same object begin.
        open = false
    }

    private fun refuseWhenEnded() {
        if (!open) throw IOException("This write of ${streamFile.file.name} has ended")
        if (cancelled) throw IOException("The write of ${streamFile.file.name} was cancelled")
    }
}

/**
 * Reads one sealed stream from its file, a chunk at a time, and hands back only plaintext that
 * has authenticated.
 *
 * Where the stream ends is taken from where the file ends: the chunk after which no byte
 * follows is opened as the last, and its tag proves or refutes that. A stream that was cut off,
 * also exactly between two chunks, therefore fails at the chunk it now ends with, and that
 * chunk is not handed back. Chunks before it were, as with anything that is read in pieces:
 * whoever consumes a stream treats it as whole only once [next] has returned null.
 *
 * The reader owns the one buffer the plaintext is opened into. It is overwritten with zeros
 * before every chunk, when a chunk fails, at the end of the stream and by [close], which must
 * be called.
 *
 * All methods block and exclude each other.
 */
class SealedStreamReader private constructor(
    private val channel: FileChannel,
    /**
     * The header the file begins with. It is stored in the clear: it is proven, with the date in
     * it, once [next] has returned a chunk, and until then it is only what the file says.
     */
    val header: StreamHeader,
    private val opener: StreamOpener,
    /** One sealed chunk and one byte more, which tells whether anything follows the chunk. */
    private val sealed: ByteBuffer,
    chunkSize: Int,
) : Closeable {

    private val sealedChunkSize = chunkSize + SealedStream.CHUNK_OVERHEAD

    /** The plaintext of one chunk, with the room for a tag that the cipher of some versions of the platform asks for. */
    private val plain: ByteBuffer = ByteBuffer.allocateDirect(chunkSize + SealedStream.TAG_SIZE)

    /** How much of [plain], from its start, may hold something. */
    private var used = 0
    private var closed = false
    private var failure: IOException? = null

    /**
     * Opens the next chunk and returns its plaintext: the reader's own buffer, from its position
     * (0) to its limit. It is the same direct, writable buffer every time, so that it can be
     * handed to native code as it is; what it holds is valid until the next call of [next] or
     * [close], which overwrite it.
     *
     * @return the chunk, or null when the last chunk has been returned before: the stream is
     *   then complete, and nothing follows it. Only the single chunk of an empty stream is
     *   empty.
     * @throws UndecryptableException when the next chunk is not the next chunk of this stream,
     *   sealed for this purpose with this key: changed, cut off, moved, or another's. Nothing
     *   of it is handed back, and every later call fails in the same way.
     * @throws KeyDestroyedException once the app has locked, now and at every later call.
     * @throws IOException when the file could not be read, now and at every later call; and at
     *   every call after one that failed in any other way.
     */
    @Synchronized
    @Throws(IOException::class)
    fun next(): ByteBuffer? {
        check(!closed) { "The reader has been closed" }
        failure?.let { throw it }
        wipePlaintext()
        if (opener.isFinished) return null
        try {
            // Whatever was read beyond the chunk before is at the start of the buffer: one byte at most.
            while (sealed.hasRemaining()) {
                if (channel.read(sealed) < 0) break
            }
            val read = sealed.position()
            val last = read <= sealedChunkSize
            val length = minOf(read, sealedChunkSize)
            sealed.position(0)
            sealed.limit(length)
            plain.clear()
            plain.limit(maxOf(length - SealedStream.NONCE_SIZE, 0))
            used = plain.limit()
            // A chunk that does not open is overwritten by the opener before it throws.
            opener.open(sealed, last, plain)
            sealed.clear()
            if (!last) sealed.put(sealed.get(sealedChunkSize))
            plain.flip()
            return plain
        } catch (e: IOException) {
            failure = e
            throw e
        } catch (e: RuntimeException) {
            // Where the file and the count of chunks stand is not known any more.
            failure = IOException("A chunk of the stream could not be opened", e)
            throw e
        }
    }

    /** Overwrites the plaintext buffer and closes the file. What [next] handed back is gone. */
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        plain.wipe()
        closeQuietly(channel)
    }

    private fun wipePlaintext() {
        if (used > 0) plain.wipe(0, used)
        used = 0
    }

    internal companion object {
        /**
         * Reads the header from [channel], which is at the start of the file.
         *
         * @throws UndecryptableException when the file does not begin as a stream of a known version.
         */
        @Throws(IOException::class)
        fun open(channel: FileChannel, purpose: String, cipher: ChunkCipher, chunkSize: Int): SealedStreamReader {
            val sealed = ByteBuffer.allocateDirect(chunkSize + SealedStream.CHUNK_OVERHEAD + 1)
            sealed.limit(SealedStream.HEADER_SIZE)
            while (sealed.hasRemaining()) {
                if (channel.read(sealed) < 0) break
            }
            sealed.flip()
            val bytes = ByteArray(sealed.remaining())
            sealed.get(bytes)
            val header = SealedStream.parseHeader(bytes) ?: throw UndecryptableException("Not a sealed stream")
            sealed.clear()
            return SealedStreamReader(channel, header, StreamOpener(cipher, purpose, header, chunkSize), sealed, chunkSize)
        }
    }
}

private fun closeQuietly(channel: FileChannel) {
    try {
        channel.close()
    } catch (_: IOException) {
        // Nothing depends on it: a file that is being given up, or one that was only read.
    }
}
