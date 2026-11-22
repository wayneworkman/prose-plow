package us.theworkmans.proseplow.llama

import android.content.Context
import us.theworkmans.proseplow.core.model.ModelSpec
import java.io.File

/**
 * Debug builds only: a model file that a developer placed in the app's private storage, so that
 * a gigabyte of weights does not have to be built into the APK and installed again for every
 * change to the code. The release build has another object of this name that never finds
 * anything (`src/release`), so this code is not even compiled into a release.
 *
 * The file is `files/models/<ggufFileName>` under the app's data directory, written through
 * `run-as` (the commands are in `native/README.md`). Nothing verifies it against the pinned
 * hash: it is whatever the developer put there, which is the point and the reason it does not
 * exist in a release.
 */
internal object SideloadedModels {

    /** The directory under the app's files directory. */
    const val DIRECTORY: String = "models"

    fun find(context: Context, model: ModelSpec): File? =
        File(File(context.filesDir, DIRECTORY), model.ggufFileName).takeIf { it.isFile && it.canRead() }
}
