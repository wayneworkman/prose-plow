package us.theworkmans.proseplow.llama

import android.content.Context
import us.theworkmans.proseplow.core.model.ModelSpec
import java.io.File

/**
 * For the device tests only: a model file placed in the test package's private storage, for a
 * model that is too large to be built into the test APK and installed again for every change to
 * the code (Qwen3.5), or any other model a test APK was built without. This lives in the test
 * sources, so no build of the library or of the app contains it; the library's own
 * [ModelFiles.locate] finds the APK's assets and nothing else.
 *
 * The file is `files/models/<ggufFileName>` under the test package's data directory, written
 * through `run-as` (the commands are in `native/README.md`, "On the phone"). Nothing verifies it
 * against the pinned hash: it is whatever was put there, which is the point and the reason no
 * app looks for it.
 */
internal object SideloadedModels {

    /** The directory under the test package's files directory. */
    const val DIRECTORY: String = "models"

    fun find(context: Context, model: ModelSpec): File? =
        File(File(context.filesDir, DIRECTORY), model.ggufFileName).takeIf { it.isFile && it.canRead() }

    /**
     * Where the tests find [model]: a file placed for them wins over the asset of the test APK,
     * since it was put there on purpose; null when there is neither.
     */
    fun locate(context: Context, model: ModelSpec): ModelLocation? =
        find(context, model)?.let { ModelLocation.PrivateFile(it) } ?: ModelFiles.locate(context, model)
}

/** Where the tests find [model] in the test package; see [SideloadedModels.locate]. */
internal fun locateForTests(model: ModelSpec): ModelLocation? = SideloadedModels.locate(appContext, model)

/** A factory that opens the models the tests find, the files placed for them included. */
internal fun testFactory(
    settings: NativeSettings = NativeSettings(),
    plan: ThreadPlan? = null,
): LlamaFactory = if (plan == null) {
    LlamaFactory(appContext, settings, locate = SideloadedModels::locate)
} else {
    LlamaFactory(appContext, settings, plan, SideloadedModels::locate)
}
