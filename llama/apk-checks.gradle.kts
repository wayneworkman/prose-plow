import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

// Checks on a finished APK that only the module which builds the APK can run, kept here because
// what they protect belongs to this module: the models and the native libraries. The application
// module applies this file with one line:
//
//     apply(from = "../llama/apk-checks.gradle.kts")
//
// Every task that packages an APK then checks what it wrote as its last action, so that no APK
// leaves the build with a model that cannot be mapped: the packaging task itself fails.
//
// A script applied this way cannot see the classes of the Android Gradle plugin (Gradle compiles
// it apart from the build file that applies it), so it finds the packaging tasks by the name of
// their class instead of through the plugin's variant API. If a future plugin renames that
// class, the last block of this file notices that nothing would be checked and says so.

/**
 * The checks. [verify] fails unless, in every APK it is given,
 *
 * - each model (`assets/models/` and below) is **stored**, not deflated, and its data begins on a
 *   **16 KiB boundary** of the file. Only then can the app open it with `AssetManager.openFd`
 *   and map it in place, on devices with 4 KB and with 16 KB pages alike. Both properties come
 *   from the packaging settings of the application module (`androidResources.noCompress` and
 *   `packaging.jniLibs.useLegacyPackaging = false`), which is why they are checked on the
 *   result instead of trusted;
 * - each native library (`lib/`) has all of its loadable segments aligned to at least 16 KiB,
 *   the requirement for devices with 16 KB pages. The NDK links that way from r28 on; a
 *   prebuilt library pulled in by a dependency might not.
 *
 * The findings are written to the report file whether the checks pass or not.
 */
class NativePackaging {

    /** An entry of a ZIP archive with what `java.util.zip` does not tell: where its data begins. */
    class Entry(val name: String, val stored: Boolean, val size: Long, val dataOffset: Long)

    companion object {
        const val MODEL_PREFIX = "assets/models/"
        const val PAGE = 16384L

        /** Enough of an ELF file for its header and program headers. */
        const val ELF_HEAD = 4096

        fun verify(apks: List<File>, report: File) {
            if (apks.isEmpty()) throw GradleException("The packaging task wrote no APK that could be checked")
            val problems = ArrayList<String>()
            val lines = ArrayList<String>()
            for (apk in apks.sortedBy { it.name }) {
                inspect(apk, lines, problems)
            }
            report.parentFile.mkdirs()
            report.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
            if (problems.isNotEmpty()) {
                throw GradleException(
                    "The APK is not packaged as the native code needs it:\n" + problems.joinToString("\n") { "- $it" } +
                        "\nSee native/README.md, \"Models in the APK\".",
                )
            }
        }

        private fun inspect(apk: File, lines: MutableList<String>, problems: MutableList<String>) {
            lines += "${apk.name}: ${apk.length()} bytes"
            var models = 0
            for (entry in entries(apk)) {
                if (!entry.name.startsWith(MODEL_PREFIX)) continue
                models++
                val misplaced = entry.dataOffset % PAGE
                lines += "  ${entry.name}: ${if (entry.stored) "stored" else "COMPRESSED"}, ${entry.size} bytes at offset ${entry.dataOffset}" +
                    if (misplaced == 0L) " (a multiple of $PAGE)" else " ($misplaced past a multiple of $PAGE)"
                if (!entry.stored) {
                    problems += "${apk.name}: ${entry.name} is compressed. Add its suffix to androidResources.noCompress in the application module."
                } else if (misplaced != 0L) {
                    problems += "${apk.name}: ${entry.name} starts at offset ${entry.dataOffset}, not on a 16 KiB boundary. " +
                        "The application module needs packaging.jniLibs.useLegacyPackaging = false, and the asset's name must end in .so."
                }
            }
            if (models == 0) lines += "  no model is bundled"
            ZipFile(apk).use { zip ->
                for (entry in zip.entries().asSequence().filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }.sortedBy { it.name }) {
                    val alignment = zip.getInputStream(entry).use { smallestLoadAlignment(it.readNBytes(ELF_HEAD)) }
                    lines += "  ${entry.name}: loadable segments aligned to ${alignment ?: "?"}"
                    when {
                        alignment == null -> problems += "${apk.name}: ${entry.name} is not a 64-bit little-endian ELF file this check can read."
                        alignment < PAGE -> problems += "${apk.name}: ${entry.name} has a loadable segment aligned to $alignment bytes; devices with 16 KB pages need $PAGE."
                    }
                }
            }
        }

        private fun ByteBuffer.u16(at: Int): Int = getShort(at).toInt() and 0xFFFF
        private fun ByteBuffer.u32(at: Int): Long = getInt(at).toLong() and 0xFFFFFFFFL

        /**
         * The entries of [apk], read from its central directory and local headers. An APK is a
         * plain ZIP below 4 GiB (Android installs no other), so the 32-bit fields are the truth.
         */
        fun entries(apk: File): List<Entry> = RandomAccessFile(apk, "r").use { file ->
            // The end record is the last 22 bytes unless a comment follows it; search backwards.
            val tailSize = minOf(file.length(), 65557L).toInt()
            val tail = ByteArray(tailSize)
            file.seek(file.length() - tailSize)
            file.readFully(tail)
            val end = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
            var record = tailSize - 22
            while (record >= 0 && end.getInt(record) != 0x06054b50) record--
            if (record < 0) throw GradleException("${apk.name} is not a ZIP archive")
            val count = end.u16(record + 10)
            val directorySize = end.u32(record + 12)
            val directoryOffset = end.u32(record + 16)

            val directoryBytes = ByteArray(directorySize.toInt())
            file.seek(directoryOffset)
            file.readFully(directoryBytes)
            val directory = ByteBuffer.wrap(directoryBytes).order(ByteOrder.LITTLE_ENDIAN)
            val out = ArrayList<Entry>(count)
            var at = 0
            val local = ByteArray(30)
            repeat(count) {
                if (directory.getInt(at) != 0x02014b50) throw GradleException("${apk.name} has a damaged central directory")
                val method = directory.u16(at + 10)
                val size = directory.u32(at + 24)
                val nameLength = directory.u16(at + 28)
                val extraLength = directory.u16(at + 30)
                val commentLength = directory.u16(at + 32)
                val headerOffset = directory.u32(at + 42)
                val name = String(directoryBytes, at + 46, nameLength, Charsets.UTF_8)
                // The local header repeats the name and has an extra field of its own length:
                // the padding that aligns the data is exactly that extra field.
                file.seek(headerOffset)
                file.readFully(local)
                val header = ByteBuffer.wrap(local).order(ByteOrder.LITTLE_ENDIAN)
                if (header.getInt(0) != 0x04034b50) throw GradleException("${apk.name}: $name has a damaged local header")
                out += Entry(name, stored = method == 0, size = size, dataOffset = headerOffset + 30 + header.u16(26) + header.u16(28))
                at += 46 + nameLength + extraLength + commentLength
            }
            out
        }

        /** The smallest alignment among the loadable segments of an ELF file, or null if [head] is not one this reads. */
        fun smallestLoadAlignment(head: ByteArray): Long? {
            if (head.size < 64 || head[0] != 0x7f.toByte() || head[1] != 'E'.code.toByte() || head[2] != 'L'.code.toByte() || head[3] != 'F'.code.toByte()) return null
            if (head[4].toInt() != 2 || head[5].toInt() != 1) return null // 64-bit, little-endian
            val elf = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            val tableOffset = elf.getLong(32)
            val entrySize = elf.u16(54)
            val entryCount = elf.u16(56)
            if (entrySize < 56 || tableOffset + entrySize.toLong() * entryCount > head.size) return null
            var smallest: Long? = null
            for (index in 0 until entryCount) {
                val at = (tableOffset + index.toLong() * entrySize).toInt()
                if (elf.getInt(at) == 1) { // PT_LOAD
                    val alignment = elf.getLong(at + 48)
                    smallest = if (smallest == null) alignment else minOf(smallest, alignment)
                }
            }
            return smallest
        }
    }
}

// AGP's task that writes an application's APK (package<Variant>) is of this class.
val packagingTaskClass = "com.android.build.gradle.tasks.PackageApplication"

tasks.configureEach {
    if (javaClass.name.startsWith(packagingTaskClass)) {
        val reportFile = layout.buildDirectory.file("reports/native-packaging/$name.txt").get().asFile
        doLast {
            NativePackaging.verify(outputs.files.asFileTree.matching { include("**/*.apk") }.files.toList(), reportFile)
        }
    }
}

// The safety net for the lookup by class name above: an assemble task that is about to run
// without any packaging task of that class means the checks are no longer attached.
gradle.taskGraph.whenReady {
    val assembling = allTasks.any { it.project == project && it.name.startsWith("assemble") && !it.name.contains("Test") }
    if (assembling && allTasks.none { it.project == project && it.javaClass.name.startsWith(packagingTaskClass) }) {
        throw GradleException(
            "llama/apk-checks.gradle.kts found no task of class $packagingTaskClass in $path: the Android Gradle plugin has changed, " +
                "and the checks on the APK's models and native libraries would silently not run. Update the class name in that file.",
        )
    }
}
