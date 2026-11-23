package us.theworkmans.proseplow.security

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * The app's directories, as far as the lock needs to know them. What the stores are (chats,
 * settings, whatever comes later) is none of its business; it only relies on three rules that
 * every store follows:
 *
 * - A file encrypted with the data key has a name ending in [ENCRYPTED_SUFFIX] and lives under
 *   [filesDir], outside [securityDir]. That is how [encryptedDataExists] finds data it has never
 *   heard of, so a store added later is covered without touching this class.
 * - Every blob, and every stream, is sealed with a purpose from [Purposes].
 * - A reset deletes everything in [filesDir] and [cacheDir] ([DataReset]); nothing a store keeps
 *   there survives it.
 */
class AppFiles(val filesDir: File, val cacheDir: File) {
    /** Only the lock writes here. */
    val securityDir = File(filesDir, "security")

    /** The lock configuration (no secrets in the clear). */
    val lockConfig = File(securityDir, "lock.json")

    /**
     * True if anything encrypted with a data key is on disk: any `*.enc` file anywhere under
     * [filesDir] except in [securityDir]. Temp files of unfinished writes (`*.enc.tmp`) are not
     * data. Links are not followed.
     * @throws IOException if a directory cannot be looked into: that is not "no data", and the
     * caller must not go on to make a new key.
     */
    @Throws(IOException::class)
    fun encryptedDataExists(): Boolean {
        if (!filesDir.exists()) return false
        val security = securityDir.toPath()
        var found = false
        Files.walkFileTree(
            filesDir.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                    if (dir == security) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!file.fileName.toString().endsWith(ENCRYPTED_SUFFIX)) return FileVisitResult.CONTINUE
                    found = true
                    return FileVisitResult.TERMINATE
                }
            },
        )
        return found
    }

    companion object {
        /** The end of the name of every file that is encrypted with the data key. */
        const val ENCRYPTED_SUFFIX = ".enc"
    }
}

/**
 * The purposes blobs are sealed with ([BlobCipher]), and streams ([ChunkCipher]). A purpose names
 * what a blob is, and is part of what is authenticated: a blob moved to another file, or handed
 * to another store, does not open. Purposes are namespaced so that stores cannot collide:
 * `<namespace>` for the one blob of a kind (`settings`), `<namespace>:<id>` for one of many
 * (`session:<id>`). A namespace is lower-case letters, digits and hyphens and has no colon, so
 * the first colon always ends it. A purpose is permanent once data has been sealed with it.
 */
object Purposes {
    private val NAMESPACE = Regex("[a-z][a-z0-9-]*")

    /** Taken by the lock for its own wraps of the data key. */
    private const val RESERVED = "dek"

    fun of(namespace: String): String {
        require(NAMESPACE.matches(namespace) && namespace != RESERVED) { "Not a purpose namespace: $namespace" }
        return namespace
    }

    fun of(namespace: String, id: String): String {
        require(id.isNotEmpty()) { "A purpose id cannot be empty" }
        return "${of(namespace)}:$id"
    }
}

/** The "Reset app data" file work: everything in the app's files and cache directories goes. */
object DataReset {
    /**
     * Deletes all app data except the security directory (the lock configuration goes last, see
     * [LockManager.reset]). Everything is tried even if something fails; returns false if
     * anything is left. Links are deleted, not followed.
     */
    fun deleteAllData(files: AppFiles): Boolean {
        var ok = true
        for (dir in listOf(files.filesDir, files.cacheDir)) {
            val children = dir.listFiles()
            if (children == null) {
                // A directory that cannot be listed counts as not deleted; one that is not there is done.
                if (dir.exists()) ok = false
                continue
            }
            for (child in children) {
                if (child == files.securityDir) continue
                if (!deleteTree(child)) ok = false
            }
        }
        return ok
    }

    private fun deleteTree(root: File): Boolean {
        var ok = true
        try {
            Files.walkFileTree(
                root.toPath(),
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = delete(file)

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        ok = false
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult = delete(dir)

                    private fun delete(path: Path): FileVisitResult {
                        try {
                            Files.delete(path)
                        } catch (_: IOException) {
                            ok = false
                        }
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        } catch (_: IOException) {
            ok = false
        }
        return ok
    }
}
