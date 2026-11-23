package us.theworkmans.proseplow.llama

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.os.ParcelFileDescriptor
import us.theworkmans.proseplow.core.engine.NativeLlamaException
import us.theworkmans.proseplow.core.model.ModelCatalog
import us.theworkmans.proseplow.core.model.ModelSpec
import java.io.File
import java.io.IOException

/** Where the file of a model is. */
internal sealed interface ModelLocation {

    /** An asset of the APK, stored without compression: `assets/[path]`. */
    data class Asset(val path: String) : ModelLocation

    /** A file in the app's private storage; debug builds only (see [SideloadedModels]). */
    data class PrivateFile(val file: File) : ModelLocation
}

/**
 * Finds the model files of this build.
 *
 * A model is an asset named `models/<ggufFileName>.so`, put there by the build
 * (`package<Variant>Models` in this module's build file) from a conversion whose hash the
 * repository pins. The `.so` suffix is there for the packaging tools: they align uncompressed
 * entries of that name to a page, which lets the model be mapped straight out of the APK. Which
 * models a build carries is the builder's choice, so nothing here assumes that any is present.
 *
 * Invariant: no model is ever copied. It is handed to native code as an open descriptor with
 * the offset and length of its bytes, wherever those bytes are.
 */
internal object ModelFiles {

    /** The directory of the model assets. The build file of this module writes the same name. */
    const val ASSET_DIRECTORY: String = "models"

    /** The asset of [model], relative to the assets directory. */
    fun assetPath(model: ModelSpec): String = "$ASSET_DIRECTORY/${model.ggufFileName}.so"

    /**
     * Where [model] is, or null when this build does not have it. A file placed by a developer
     * (debug builds only) wins over the asset: it is there on purpose.
     */
    fun locate(context: Context, model: ModelSpec): ModelLocation? {
        SideloadedModels.find(context, model)?.let { return ModelLocation.PrivateFile(it) }
        val bundled = try {
            context.assets.list(ASSET_DIRECTORY)
        } catch (_: IOException) {
            null
        }
        return if (bundled != null && "${model.ggufFileName}.so" in bundled) ModelLocation.Asset(assetPath(model)) else null
    }

    /**
     * The models this installation offers: those of [ModelCatalog.offered], the bundled models
     * whose files this build has, in catalogue order. A model that is not bundled is left out
     * even when [locate] finds its file; the factory still opens such a file when asked for it
     * directly, as the device tests do.
     */
    fun present(context: Context): List<ModelSpec> = ModelCatalog.offered { locate(context, it) != null }

    /**
     * Opens [location] and passes the descriptor with the offset and length of the model's bytes
     * to [use]; the descriptor is closed when [use] returns.
     *
     * @throws NativeLlamaException with [NativeLlamaException.LOAD_FAILED] when the file cannot
     *   be opened as a descriptor, which for an asset means that it is missing or was compressed
     *   by the build.
     */
    fun <T> open(context: Context, location: ModelLocation, use: (fd: Int, offset: Long, length: Long) -> T): T = when (location) {
        is ModelLocation.Asset -> {
            val descriptor: AssetFileDescriptor = try {
                context.assets.openFd(location.path)
            } catch (_: IOException) {
                throw NativeLlamaException(
                    NativeLlamaException.LOAD_FAILED,
                    "The asset ${location.path} cannot be opened as a file: it is missing, or the build compressed it",
                )
            }
            descriptor.use { use(it.parcelFileDescriptor.fd, it.startOffset, it.length) }
        }
        is ModelLocation.PrivateFile -> {
            val descriptor = try {
                ParcelFileDescriptor.open(location.file, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (_: IOException) {
                throw NativeLlamaException(NativeLlamaException.LOAD_FAILED, "The file ${location.file.name} cannot be opened")
            }
            descriptor.use { use(it.fd, 0L, location.file.length()) }
        }
    }
}
