package us.theworkmans.proseplow.llama

import android.content.Context
import us.theworkmans.proseplow.core.engine.NativeLlamaException
import us.theworkmans.proseplow.core.engine.NativeLlamaFactory

/**
 * The one place the app obtains the factory that opens a bundled model in native code.
 *
 * Until the JNI binding exists every attempt to open a model fails with
 * [NativeLlamaException.LOAD_FAILED], which the engine reports as an ordinary load failure, so
 * everything above the native seam can be built and run before the native code is there.
 */
object NativeEngine {

    /** The factory for the lifetime of the process. [context] is used for the app's assets only. */
    @Suppress("UNUSED_PARAMETER")
    fun factory(context: Context): NativeLlamaFactory = NativeLlamaFactory { model, _ ->
        throw NativeLlamaException(NativeLlamaException.LOAD_FAILED, "No native engine in this build: ${model.id}")
    }
}
