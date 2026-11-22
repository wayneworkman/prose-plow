package us.theworkmans.proseplow.llama

import android.content.Context
import us.theworkmans.proseplow.core.model.ModelSpec
import java.io.File

/**
 * The release build's side of the development aid in `src/debug`: a release runs the models its
 * APK carries and nothing else, so there is never a sideloaded file to find.
 */
internal object SideloadedModels {

    @Suppress("UNUSED_PARAMETER") // The same signature as the debug build's object.
    fun find(context: Context, model: ModelSpec): File? = null
}
