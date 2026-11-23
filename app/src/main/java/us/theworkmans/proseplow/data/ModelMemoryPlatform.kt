package us.theworkmans.proseplow.data

import java.io.File
import java.nio.ByteBuffer

/**
 * Whether the model's memory may be saved at all on a version of Android: the one place this is
 * decided.
 *
 * Only from Android 12 (API level 31). The memory is sealed a chunk at a time from a direct
 * buffer that the native export fills and that is overwritten after every chunk. On Android 9,
 * 10 and 11 the platform's cipher copies each chunk of plaintext it is given into heap memory of
 * its own, which nothing can overwrite: up to gigabytes of the model's memory of a conversation
 * (its keys and values, and from the first chunk the ids of its tokens) would be left in memory
 * that cannot be wiped, at every save and every restore. Android 12 and later do not make that
 * copy. So on the older versions the feature does not exist: nothing is saved or restored, any
 * memory file found is deleted, and its switch is shown disabled.
 *
 * The session is given the answer ([us.theworkmans.proseplow.AppSession]'s `memoryPlatform` is
 * null without it), so that both sides are tested on any machine.
 */
object ModelMemorySupport {

    /** Android 12: the first version on which the model's memory is saved. */
    const val FIRST_SDK: Int = 31

    /** True when the model's memory may be saved on a device running API level [sdk]; see the object comment. */
    fun supported(sdk: Int): Boolean = sdk >= FIRST_SDK
}

/**
 * What the store of the model's memory ([MemoryStore]) needs to know of the platform, given to a
 * session only where [ModelMemorySupport.supported] holds.
 *
 * @property build the build that is running, from the package: its version code and the time it
 *   was installed or last updated.
 * @property modelFileLength the length of the packaged file of a model, by its id; null for a
 *   model this build does not carry.
 * @property freeSpace what the volume of a directory has free; on Android the space that can be
 *   allocated, which counts cached data the system would clear (see [MemoryStore]).
 * @property beforeAppend called on a save's writer thread with every sealed chunk before it is
 *   written: does nothing in the app; a test stands for a slow or stuck disk with it.
 */
class ModelMemoryPlatform(
    val build: BuildIdentity,
    val modelFileLength: (modelId: String) -> Long?,
    val freeSpace: (directory: File) -> FreeSpace,
    internal val beforeAppend: (sealed: ByteBuffer) -> Unit = {},
)
