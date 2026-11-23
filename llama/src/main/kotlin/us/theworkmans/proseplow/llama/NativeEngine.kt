package us.theworkmans.proseplow.llama

import android.content.Context
import android.content.pm.ApplicationInfo
import us.theworkmans.proseplow.core.engine.LoadProgress
import us.theworkmans.proseplow.core.engine.NativeLlama
import us.theworkmans.proseplow.core.engine.NativeLlamaException
import us.theworkmans.proseplow.core.engine.NativeLlamaFactory
import us.theworkmans.proseplow.core.model.ModelSpec
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * The one place the app obtains native inference from: the factory that opens a model of this
 * build in llama.cpp, and the list of models this build actually carries.
 */
object NativeEngine {

    /**
     * The factory for the lifetime of the process. [context] is used for the app's assets, its
     * private files (debug builds) and the names of its native libraries.
     *
     * The factory opens a model's asset as a file descriptor and hands descriptor, offset and
     * length to native code, which maps the model's bytes straight out of the APK: the weights
     * are never copied, on disk or (unless mapping fails) in memory.
     */
    fun factory(context: Context): NativeLlamaFactory = LlamaFactory(context.applicationContext, NativeSettings())

    /**
     * The bundled models of the catalogue whose files are present, in catalogue order
     * ([ModelFiles.present]). Which models an APK carries is chosen when it is built, down to
     * none at all, so the app offers what this returns and nothing it merely knows about, nor a
     * model that is not bundled whose file a developer placed in a debug build. Cheap enough to
     * call when a screen opens; it lists a directory of assets and loads no native code.
     */
    fun availableModels(context: Context): List<ModelSpec> = ModelFiles.present(context.applicationContext)

    /**
     * The length in bytes of the file of [model] as this build carries it, or null when it does
     * not carry it. A saved memory of a model is bound to this length, so that a memory saved
     * with one file of a model is never read into another.
     */
    fun packagedLength(context: Context, model: ModelSpec): Long? = ModelFiles.packagedLength(context.applicationContext, model)

    /**
     * A line about the native side for an About or diagnostics screen: which processor variant
     * of the backend was loaded and the instruction sets it uses. Loads the native libraries if
     * they are not loaded yet. It describes the device, never a conversation.
     *
     * @throws NativeLlamaException with [NativeLlamaException.LOAD_FAILED] when the native
     *   libraries cannot be started.
     */
    fun runtimeDescription(context: Context): String = NativeRuntime.start(context.applicationContext)
}

/**
 * How [LlamaFactory] opens a model. One instance with the defaults is what the app runs; the
 * instrumented tests vary it to measure the alternatives the defaults were chosen from.
 *
 * @property pinThreads true to run one thread on each big core, pinned, whenever the system
 *   allows it (see [ThreadPlanner]); false to always run the plan's unpinned thread count.
 * @property mapModel true to map the model from its file and fall back to reading it into
 *   memory when mapping fails; false to always read. Mapped weights are file-backed pages that
 *   the system can drop under memory pressure and read again; read weights are anonymous memory
 *   that can only be compressed into swap.
 * @property batchTokens the most tokens native code evaluates in one go; the engine's own chunk
 *   size applies below it. It bounds a work buffer, not the context, and that buffer is paid
 *   once for any context: the scores of a whole batch, which for the largest vocabulary bundled
 *   (Qwen3.5) is 242 MiB reserved at 256 tokens, most of it never touched.
 */
internal data class NativeSettings(
    val pinThreads: Boolean = true,
    val mapModel: Boolean = true,
    val batchTokens: Int = 256,
)

/**
 * Loads the native libraries and starts llama.cpp, once per process.
 *
 * Invariant: [start] has returned normally before any other native method is called. The order
 * inside it is fixed: the JNI library first (its dependencies, libllama and the ggml base, come
 * with it), then `pp_runtime_init` with the names of the CPU backend libraries, of which native
 * code loads the best one for this processor.
 *
 * The CPU backend is built once per instruction set, as libraries named `libggml-cpu-*.so`.
 * llama.cpp's own way of choosing between them is to list a directory, but the app's libraries
 * are not in one: they stay inside the APK, stored and page-aligned, and are mapped from there
 * (the packaging that also puts the models on page boundaries, see this module's build file).
 * So their names are read here, from the library directory if the installer did extract them
 * and from the APK otherwise, and the dynamic loader, which searches both places, finds each by
 * its bare name.
 */
internal object NativeRuntime {

    private const val BACKEND_PREFIX = "libggml-cpu-"
    private const val LIBRARY_SUFFIX = ".so"

    private var description: String? = null

    /** Starts the native side if needed and returns its description. Safe from any thread. */
    @Synchronized
    fun start(context: Context): String {
        description?.let { return it }
        try {
            System.loadLibrary("proseplow_jni")
        } catch (e: UnsatisfiedLinkError) {
            throw NativeLlamaException(NativeLlamaException.LOAD_FAILED, "The native library cannot be loaded: ${e.message}")
        }
        val backends = backendLibraries(context.applicationInfo)
        if (backends.isEmpty()) {
            throw NativeLlamaException(NativeLlamaException.LOAD_FAILED, "The app carries no CPU backend library ($BACKEND_PREFIX*$LIBRARY_SUFFIX)")
        }
        val status = LlamaJni.runtimeInit(backends.joinToString("\n").toByteArray(Charsets.UTF_8))
        val text = String(LlamaJni.runtimeDescription(), Charsets.UTF_8)
        if (status != 0) {
            throw NativeLlamaException(NativeLlamaException.LOAD_FAILED, "llama.cpp could not be started: $text")
        }
        description = text
        return text
    }

    /** The file names of the CPU backend variants this installation has, sorted. */
    fun backendLibraries(application: ApplicationInfo): List<String> {
        val extracted = File(application.nativeLibraryDir).list().orEmpty().filter(::isBackend)
        if (extracted.isNotEmpty()) return extracted.sorted()
        val names = sortedSetOf<String>()
        for (apk in listOf(application.sourceDir) + application.splitSourceDirs.orEmpty()) {
            try {
                ZipFile(apk).use { zip ->
                    for (entry in zip.entries()) {
                        val path = entry.name
                        // lib/<abi>/<name>: the app is built for one ABI, so every match is a candidate.
                        if (path.startsWith("lib/") && isBackend(path.substringAfterLast('/'))) names += path.substringAfterLast('/')
                    }
                }
            } catch (_: IOException) {
                // An APK that cannot be read contributes no names; the caller reports none at all.
            }
        }
        return names.toList()
    }

    private fun isBackend(name: String): Boolean = name.startsWith(BACKEND_PREFIX) && name.endsWith(LIBRARY_SUFFIX)
}

/**
 * The app's [NativeLlamaFactory]. Stateless apart from the thread plan, which is read once.
 *
 * @param locate where a model's file is. The app always has [ModelFiles.locate], the APK's own
 *   assets ([NativeEngine.factory] passes nothing else, and the class is internal to this
 *   module). Only this module's device tests pass another, which also finds files they placed in
 *   the test package's private storage, for models too large for the test APK or not bundled.
 */
internal class LlamaFactory(
    private val context: Context,
    private val settings: NativeSettings,
    private val threadPlan: ThreadPlan = ThreadPlanner.plan(CpuSysfs().capacities(), Runtime.getRuntime().availableProcessors()),
    private val locate: (Context, ModelSpec) -> ModelLocation? = ModelFiles::locate,
) : NativeLlamaFactory {

    override fun open(model: ModelSpec, progress: LoadProgress, contextTokens: Int, budgetBytes: Long): NativeLlama {
        NativeRuntime.start(context)
        val location = locate(context, model)
            ?: throw NativeLlamaException(NativeLlamaException.LOAD_FAILED, "This build does not carry the file of ${model.id}")
        val pinned = if (settings.pinThreads) threadPlan.pinnedCpus.toIntArray() else IntArray(0)
        return ModelFiles.open(context, location) { fd, offset, length ->
            fun load(map: Boolean): Long = LlamaJni.openFd(
                fd, offset, length, contextTokens, budgetBytes, settings.batchTokens, threadPlan.unpinnedThreads, pinned, map, progress,
            )
            val session = if (!settings.mapModel) {
                load(map = false)
            } else {
                try {
                    load(map = true)
                } catch (e: NativeLlamaException) {
                    // Mapping needs the model's data at a multiple of 32 bytes in its file, which
                    // the build guarantees for an asset but nothing guarantees for a file the
                    // device tests placed. Reading works at any offset. A file that is no model at
                    // all fails a second time, with the message of that second attempt.
                    if (e.code != NativeLlamaException.LOAD_FAILED) throw e
                    load(map = false)
                }
            }
            LlamaSession(session)
        }
    }
}
