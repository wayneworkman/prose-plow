import groovy.json.JsonSlurper
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

// Checks on a finished APK that only the module which builds the APK can run, kept here because
// what they protect belongs to this module: the models and the native libraries. The application
// module applies this file with one line (through file(): a bare string makes lint follow it
// into this script and fail, see the end of build.gradle.kts next to this file):
//
//     apply(from = file("../llama/apk-checks.gradle.kts"))
//
// Every task of that module which writes an APK, an app bundle (.aab) or an APK set (.apks) then
// checks what it wrote as its last action, so that no such file leaves the build with a model
// that cannot be mapped or that is not the pinned one: the task itself fails.
//
// The tasks are not looked up by name or by class. Which tasks of the Android Gradle plugin
// write an installable file changes between its versions, and a list kept here would be right
// until it silently was not. Instead every task of the module looks at its own outputs when it
// has run, and checks the archives it finds among them; for nearly all tasks that is a glance at
// a list of file names. (Gradle waits for the work a task action handed to its workers before
// it runs the next action, so the archive is complete when this one looks at it.)

/**
 * The checks. [verify] fails unless, in every archive it is given,
 *
 * - each model is **one that `models/manifest.json` accounts for**: its name is
 *   `assets/models/<id>.gguf.so` for an entry of the manifest that is ready and bundled, and the
 *   SHA-256 of its bytes, **as they are in the archive**, is the one pinned there. The task that
 *   puts the models among the assets verifies the same hash on the file it copies; this verifies
 *   what was packaged, whatever road it took. Anything else under `assets/models/`, and any
 *   file named `*.gguf` or `*.gguf.so` anywhere, is a model that nothing vouches for (an asset
 *   of that name in another source directory would get there) and fails the build;
 * - in an APK, each model is **stored**, not deflated, and its data begins on a **16 KiB
 *   boundary** of the file. Only then can the app open it with `AssetManager.openFd` and map it
 *   in place, on devices with 4 KB and with 16 KB pages alike. Both properties come from the
 *   packaging settings of the application module (`androidResources.noCompress` and
 *   `packaging.jniLibs.useLegacyPackaging = false`), which is why they are checked on the
 *   result instead of trusted;
 * - each native library has all of its loadable segments aligned to at least 16 KiB, the
 *   requirement for devices with 16 KB pages. The NDK links that way from r28 on; a prebuilt
 *   library pulled in by a dependency might not;
 * - each native library is **one of the [KNOWN_LIBRARIES]**, the ones the third-party notices
 *   of the app account for. A library is code with an author and a licence, and one that a new
 *   dependency or a new build target brings along is in the APK before anyone has asked whose
 *   it is. The Java libraries are compared with the notices when they are resolved (the
 *   application module's `generate<Variant>ThirdPartyNotices`); a native library only shows
 *   itself here, in what was packaged.
 *
 * An app bundle is checked for the first and the last of these. Where a model lies in an APK
 * is only decided when APKs are made from the bundle, and it is checked on those: an APK set is
 * unpacked and each APK in it is checked like any other.
 *
 * The findings are written to the report file whether the checks pass or not.
 */
class NativePackaging {

    /** An entry of a ZIP archive with what `java.util.zip` does not tell: where its data begins. */
    class Entry(val name: String, val stored: Boolean, val size: Long, val dataOffset: Long)

    companion object {
        const val MODEL_DIRECTORY = "assets/models/"
        const val MODEL_SUFFIX = ".gguf.so"
        const val PAGE = 16384L

        /** Enough of an ELF file for its header and program headers. */
        const val ELF_HEAD = 4096

        /**
         * The native libraries an APK of this project may contain, by file name: the ones whose
         * authors and licences `app/notices/notices.json` and `app/notices/NOTICES.txt` name.
         * A name is added here only together with its entry there (a test of the application
         * module reads this list and fails for a name the notices do not explain).
         */
        val KNOWN_LIBRARIES = setOf(
            // llama.cpp and ggml, built by this module from the pinned commit: the library, the
            // backend loader, the common code, and the CPU backend once for each instruction set.
            "libllama.so",
            "libggml.so",
            "libggml-base.so",
            "libggml-cpu-android_armv8.0_1.so",
            "libggml-cpu-android_armv8.2_1.so",
            "libggml-cpu-android_armv8.2_2.so",
            "libggml-cpu-android_armv8.6_1.so",
            "libggml-cpu-android_armv9.0_1.so",
            "libggml-cpu-android_armv9.2_1.so",
            "libggml-cpu-android_armv9.2_2.so",
            // This project's own JNI binding.
            "libproseplow_jni.so",
            // The C++ runtime, taken from the NDK.
            "libc++_shared.so",
            // Inside the artifact androidx.graphics:graphics-path, which Compose depends on.
            "libandroidx.graphics.path.so",
        )

        /**
         * The models an app may carry: the id and the pinned SHA-256 of every entry of the
         * manifest that is ready and bundled, which is the set that `package<Variant>Models`
         * of this module chooses from.
         */
        fun pinnedModels(manifest: File): Map<String, String> {
            @Suppress("UNCHECKED_CAST") // The manifest's shape is fixed by models/README.md.
            val models = (JsonSlurper().parse(manifest) as Map<String, Any?>)["models"] as List<Map<String, Any?>>
            return models
                .filter { it["status"] == "ready" && it["purpose"] == "bundled" && it["output_sha256"] is String }
                .associate { (it["id"] as String) to (it["output_sha256"] as String) }
        }

        /**
         * Checks [archives] (APKs, bundles, APK sets) against [manifest]. [scratch] is a directory
         * the caller owns, used for the APKs of an APK set while they are looked at.
         */
        fun verify(archives: List<File>, manifest: File, scratch: File, report: File) {
            val pinned = pinnedModels(manifest)
            val problems = ArrayList<String>()
            val lines = ArrayList<String>()
            for (archive in archives.sortedBy { it.path }) {
                when (archive.extension) {
                    "apk" -> inspectApk(archive, archive.name, pinned, lines, problems)
                    "aab" -> inspectBundle(archive, pinned, lines, problems)
                    "apks" -> inspectApkSet(archive, scratch, pinned, lines, problems)
                }
            }
            report.parentFile.mkdirs()
            report.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
            if (problems.isNotEmpty()) {
                throw GradleException(
                    "What was packaged is not what the native code needs, or not what the notices describe:\n" + problems.joinToString("\n") { "- $it" } +
                        "\nSee native/README.md, \"Models in the APK\", and README.md, \"Licence and third-party notices\".",
                )
            }
        }

        private fun inspectApk(apk: File, label: String, pinned: Map<String, String>, lines: MutableList<String>, problems: MutableList<String>) {
            lines += "$label: ${apk.length()} bytes"
            val placement = entries(apk).associateBy { it.name }
            var models = 0
            ZipFile(apk).use { zip ->
                for (entry in zip.entries().asSequence().filter { !it.isDirectory }.sortedBy { it.name }) {
                    if (isModel(entry.name)) {
                        models++
                        // Where a file lies only matters for one that may be there at all.
                        if (!checkIdentity(zip, entry, entry.name, label, pinned, lines, problems)) continue
                        val placed = placement.getValue(entry.name)
                        val misplaced = placed.dataOffset % PAGE
                        lines += "    ${if (placed.stored) "stored" else "COMPRESSED"}, ${placed.size} bytes at offset ${placed.dataOffset}" +
                            if (misplaced == 0L) " (a multiple of $PAGE)" else " ($misplaced past a multiple of $PAGE)"
                        if (!placed.stored) {
                            problems += "$label: ${entry.name} is compressed. Add its suffix to androidResources.noCompress in the application module."
                        } else if (misplaced != 0L) {
                            problems += "$label: ${entry.name} starts at offset ${placed.dataOffset}, not on a 16 KiB boundary. " +
                                "The application module needs packaging.jniLibs.useLegacyPackaging = false, and the asset's name must end in .so."
                        }
                    } else if (entry.name.startsWith("lib/") && entry.name.endsWith(".so")) {
                        checkLibrary(zip, entry, entry.name, label, lines, problems)
                    }
                }
            }
            if (models == 0) lines += "  no model is bundled"
        }

        /** A bundle holds each module in a directory of its own, laid out inside like an APK. */
        private fun inspectBundle(bundle: File, pinned: Map<String, String>, lines: MutableList<String>, problems: MutableList<String>) {
            lines += "${bundle.name}: ${bundle.length()} bytes (a bundle: where its models lie is decided when APKs are made from it, and checked on those)"
            var models = 0
            ZipFile(bundle).use { zip ->
                for (entry in zip.entries().asSequence().filter { !it.isDirectory && '/' in it.name }.sortedBy { it.name }) {
                    val inModule = entry.name.substringAfter('/')
                    if (isModel(inModule)) {
                        models++
                        checkIdentity(zip, entry, inModule, bundle.name, pinned, lines, problems)
                    } else if (inModule.startsWith("lib/") && inModule.endsWith(".so")) {
                        checkLibrary(zip, entry, entry.name, bundle.name, lines, problems)
                    }
                }
            }
            if (models == 0) lines += "  no model is bundled"
        }

        /** An APK set is an archive of APKs; each is copied out, checked and deleted again. */
        private fun inspectApkSet(set: File, scratch: File, pinned: Map<String, String>, lines: MutableList<String>, problems: MutableList<String>) {
            lines += "${set.name}: ${set.length()} bytes (an APK set)"
            scratch.mkdirs()
            ZipFile(set).use { zip ->
                for (entry in zip.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".apk") }.sortedBy { it.name }) {
                    val copy = File(scratch, "apk-set-member.apk")
                    try {
                        zip.getInputStream(entry).use { input -> copy.outputStream().use { input.copyTo(it, 1 shl 20) } }
                        inspectApk(copy, "${set.name}!${entry.name}", pinned, lines, problems)
                    } finally {
                        copy.delete()
                    }
                }
            }
        }

        /** Whether an entry (named as it is inside an APK) is, or passes itself off as, a model. */
        private fun isModel(name: String): Boolean = name.startsWith(MODEL_DIRECTORY) || name.endsWith(MODEL_SUFFIX) || name.endsWith(".gguf")

        /**
         * The first check: the entry is a model of the manifest, and its bytes are the pinned
         * ones. Returns whether the manifest accounts for a file of this name at all.
         */
        private fun checkIdentity(
            zip: ZipFile,
            entry: ZipEntry,
            name: String,
            label: String,
            pinned: Map<String, String>,
            lines: MutableList<String>,
            problems: MutableList<String>,
        ): Boolean {
            val id = name.removePrefix(MODEL_DIRECTORY).removeSuffix(MODEL_SUFFIX)
            val expected = if (name.startsWith(MODEL_DIRECTORY) && name.endsWith(MODEL_SUFFIX) && '/' !in id) pinned[id] else null
            if (expected == null) {
                lines += "  $name: NOT a model of models/manifest.json"
                problems += "$label: $name is a model file that models/manifest.json does not account for. An app carries only " +
                    "$MODEL_DIRECTORY<id>$MODEL_SUFFIX for the ids it lists as ready and bundled (${pinned.keys.sorted().joinToString()}), " +
                    "put there by the package<Variant>Models task of :llama. Remove the file from the assets it came from."
                return false
            }
            val actual = sha256(zip.getInputStream(entry))
            lines += "  $name: SHA-256 $actual, ${if (actual == expected) "as pinned" else "NOT the pinned $expected"}"
            if (actual != expected) {
                problems += "$label: $name has SHA-256 $actual as packaged, and models/manifest.json pins $expected for $id. It is not the pinned conversion."
            }
            return true
        }

        private fun checkLibrary(zip: ZipFile, entry: ZipEntry, name: String, label: String, lines: MutableList<String>, problems: MutableList<String>) {
            val alignment = zip.getInputStream(entry).use { smallestLoadAlignment(it.readNBytes(ELF_HEAD)) }
            val known = name.substringAfterLast('/') in KNOWN_LIBRARIES
            lines += "  $name: loadable segments aligned to ${alignment ?: "?"}" + if (known) "" else "; NOT a library the notices account for"
            when {
                alignment == null -> problems += "$label: $name is not a 64-bit little-endian ELF file this check can read."
                alignment < PAGE -> problems += "$label: $name has a loadable segment aligned to $alignment bytes; devices with 16 KB pages need $PAGE."
            }
            if (!known) {
                problems += "$label: $name is a native library that the third-party notices do not account for. An APK of this project " +
                    "may carry the ${KNOWN_LIBRARIES.size} libraries of KNOWN_LIBRARIES in llama/apk-checks.gradle.kts and no other; this one " +
                    "came with a new dependency or a new build target, or is another CPU variant of ggml. Find out whose code it is and under " +
                    "which licence (for a dependency: ./gradlew :app:dependencies --configuration releaseRuntimeClasspath shows what brings " +
                    "it), give it its entry in app/notices/notices.json and its lines in app/notices/NOTICES.txt, and only then add its name " +
                    "to KNOWN_LIBRARIES. Another CPU variant of ggml is covered by the entry of llama.cpp and needs its name added, no more."
            }
        }

        private fun sha256(stream: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(1 shl 20)
            stream.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
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

val modelManifest: File = rootProject.layout.projectDirectory.file("models/manifest.json").asFile

// The Android Gradle plugin packages a debug APK by updating the file that is already there,
// and such an update moves nothing: a model that leaves the selection leaves a gap of its own
// size in the file, and a model that comes in goes to the end when no gap takes it. The file is
// valid either way and only too large, up to the 4 GiB at which an APK ends. Gradle runs a task
// from the beginning, after removing what it wrote the last time, when a plain input value of
// the task has changed, or an input file that was not declared for incremental use. So the
// selection of models, and the manifest it is resolved against, are made such inputs of every
// packaging task here: the APK is written afresh whenever the set of models in it may have
// changed, and updated in place as before when it has not.
//
// These tasks are taken by the beginning of their names (package<Variant>, of which the plugin
// has several), unlike the checks below: a task this misses keeps the old behaviour, and a
// task too many only runs once more after the selection changed.
// The default is the one of bundledModels in build.gradle.kts beside this file.
val selectedModels: Provider<String> =
    providers.gradleProperty("proseplow.models").orElse(providers.environmentVariable("PROSEPLOW_MODELS")).orElse("all")
tasks.matching { it.name.startsWith("package") }.configureEach {
    inputs.property("proseplow.models", selectedModels)
    inputs.file(modelManifest).withPropertyName("proseplow.modelManifest").withPathSensitivity(PathSensitivity.NONE)
}

tasks.configureEach {
    val reportFile = layout.buildDirectory.file("reports/native-packaging/$name.txt").get().asFile
    doLast {
        val archives = outputs.files.asFileTree.matching { include("**/*.apk", "**/*.aab", "**/*.apks") }.files.toList()
        if (archives.isNotEmpty()) {
            NativePackaging.verify(archives, modelManifest, temporaryDir, reportFile)
        }
    }
}
