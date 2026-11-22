package us.theworkmans.proseplow.security

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** The points an atomic write passes, in this order; see [writeAtomically]. */
enum class WriteStep {
    /** The new content is in `<name>.tmp`, but only in the page cache. */
    TEMP_WRITTEN,

    /** The temp file's content is on the disk. */
    TEMP_SYNCED,

    /** The temp file has the final name; whether that survives a power loss is not yet settled. */
    RENAMED,

    /** The directory is on the disk too: the new content is what a restart finds, whatever happens. */
    DIRECTORY_SYNCED,
}

/**
 * Writes [bytes] to [file] through `<name>.tmp`: the temp file is fsynced, renamed over [file],
 * and then the directory holding the name is fsynced as well. A stop at any point leaves either
 * the old or the new content, never a mix; and once this returns true, a power loss can no
 * longer bring the old content back.
 *
 * That last step is what the rename alone does not give: the rename changes the directory, and a
 * directory change that was never synced may be undone by a power loss. Anything that must not
 * happen before the new content is certain (deleting a key that only the old content refers to)
 * waits for the true.
 *
 * [onStep] and [syncDirectory] are seams for the unit tests, which stop at a step (by throwing)
 * or make the directory sync fail.
 *
 * @return true if the new content is durable; false if it was renamed into place but a directory
 * could not be synced (the content is there for every reader, but a power loss may still undo it).
 * @throws IOException if [file] was not replaced: it still has its old content.
 */
@Throws(IOException::class)
fun writeAtomically(
    file: File,
    bytes: ByteArray,
    onStep: (WriteStep) -> Unit = {},
    syncDirectory: (File) -> Boolean = ::syncDirectory,
): Boolean {
    val directory = file.absoluteFile.parentFile ?: throw IOException("${file.name} has no directory")
    // Directories this write creates: their own names must reach the disk as well.
    val created = generateSequence(directory) { it.parentFile }.takeWhile { !it.exists() }.toList()
    if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
        throw IOException("Could not create the directory ${directory.name}")
    }
    val temp = tempFileFor(file)
    FileOutputStream(temp).use { out ->
        out.write(bytes)
        onStep(WriteStep.TEMP_WRITTEN)
        out.fd.sync()
    }
    onStep(WriteStep.TEMP_SYNCED)
    try {
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    onStep(WriteStep.RENAMED)
    // From here on nothing may throw: the file has its new content, so the write did not fail.
    var durable = syncDirectory(directory)
    for (made in created) {
        val parent = made.parentFile ?: continue
        if (!syncDirectory(parent)) durable = false
    }
    if (durable) onStep(WriteStep.DIRECTORY_SYNCED)
    return durable
}

/**
 * Flushes [directory] itself (the names in it) to the disk; false if that could not be done.
 * Opening a directory for reading and forcing the channel is fsync(2) on the directory, on Linux
 * and so on Android.
 */
fun syncDirectory(directory: File): Boolean = try {
    FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    true
} catch (_: Exception) {
    false
}

/**
 * Flushes [file] and the directories that name it ([levels] of them, from its own upwards) to the
 * disk; false if any of that could not be done. For a file that some earlier run may have
 * renamed into place without living long enough to sync the directory.
 */
fun syncExisting(file: File, levels: Int = 1, syncDirectory: (File) -> Boolean = ::syncDirectory): Boolean {
    try {
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { it.force(true) }
    } catch (_: Exception) {
        return false
    }
    var durable = true
    var directory = file.absoluteFile.parentFile
    repeat(levels) {
        val current = directory ?: return durable
        if (!syncDirectory(current)) durable = false
        directory = current.parentFile
    }
    return durable
}

fun tempFileFor(file: File) = File(file.parentFile, file.name + ".tmp")

/** Deletes [file] if it exists. @throws IOException if it exists and cannot be deleted. */
@Throws(IOException::class)
fun deleteOrThrow(file: File) {
    if (!file.delete() && file.exists()) throw IOException("Could not delete ${file.name}")
}
