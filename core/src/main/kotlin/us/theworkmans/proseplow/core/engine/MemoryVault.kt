package us.theworkmans.proseplow.core.engine

import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer

/**
 * What a saved memory image says of itself, beside the image: the part of its header that only
 * the engine knows. Whoever keeps the image adds what only it knows (the build, the length of the
 * model file, which messages the conversation was built from) and binds all of it to the image.
 *
 * Nothing here is text of the conversation. The number of tokens says how long it is, which is
 * what the size of the image says anyway.
 *
 * @property conversationKey which conversation the memory is of, as the engine's caller named it
 *   ([GenerationRequest.conversationKey]). Opaque to the engine: it only compares it.
 * @property modelId the model whose memory it is.
 * @property contextTokens the size the context had when the image was written: the size to give
 *   it again, when the device allows that.
 * @property tokenCount the number of tokens in memory: what a restored memory must hold.
 * @property readingSeconds how long reading those tokens from nothing takes by the model's
 *   reading curve on this device as the engine knew it at the save: the wait the image saves.
 * @property imageBytes the size of the image the engine hands over, the keeper's own first bytes
 *   not counted ([NativeLlama.memoryStateBytes]): for the keeper to check its free space by.
 */
data class SavedMemory(
    val conversationKey: String,
    val modelId: String,
    val contextTokens: Int,
    val tokenCount: Int,
    val readingSeconds: Double,
    val imageBytes: Long,
)

/**
 * Where the engine saves the model's memory and finds it again ([InferenceEngine.saveMemory],
 * [InferenceEngine.restoreMemory]). It holds at most one image. The engine never sees a byte of
 * it: the image goes from the native session through the vault's own direct buffer, piece by
 * piece, to wherever the vault puts it (encrypted, in the app), and back the same way.
 *
 * Every method is called on the engine's native thread, one at a time, while the engine holds
 * its lock; a method may block on storage, and must not call the engine. Any method may throw:
 * the engine treats an exception as a failure of the operation, as described at each method,
 * and never lets it reach its own caller.
 *
 * **What the vault must check itself.** That an image is the one it wrote, whole and unchanged,
 * in this build, for this model file, and that the conversation it was built from is still as it
 * was: the engine compares only the fields of [SavedMemory]. The native session believes what an
 * image says, and a changed one can end the process later, so no unchecked byte may reach it.
 *
 * **Refused, or not known now.** A vault that read its image and refused it says so with any
 * exception, or with -1 from a read, and the engine then deletes the image. A vault that cannot
 * tell now whether its image is good (its key was destroyed because the app locked, its storage
 * did not answer) throws [MemoryVaultUnavailableException] instead, from [saved],
 * [beginRestore] or a read: that says nothing against the image, and the engine keeps it.
 */
interface MemoryVault {

    /**
     * What the vault holds, as its header says once the vault has checked it; null when it holds
     * nothing or nothing that passes its checks (it deletes such a thing itself).
     *
     * @throws MemoryVaultUnavailableException when it cannot tell now; the image is kept.
     */
    fun saved(): SavedMemory?

    /**
     * Begins writing a new image described by [memory]. The vault has put its own header into
     * the first [MemoryWriter.firstPieceOffset] bytes of [MemoryWriter.buffer] when this returns.
     * The image it holds, if any, stays until [MemoryWriter.commit]; whether it survives a save
     * that is abandoned is the vault's decision (it may have had to make room).
     *
     * [stopped] is the engine's own condition for giving the save up (a request, forgetting,
     * unloading or loading, the caller). The engine also asks it between pieces, but a vault
     * whose storage stops answering may keep the engine's thread waiting inside a call of this
     * save ([MemoryWriter.write], [MemoryWriter.commit], [MemoryWriter.close], or this one); a
     * vault that waits there asks [stopped] as it waits, and stops waiting, giving the save up,
     * once it is true. It may be asked from the thread that waits only.
     */
    fun beginSave(memory: SavedMemory, stopped: () -> Boolean = { false }): MemoryWriter

    /**
     * Begins reading the image that [saved] reported. [MemoryReader.memory] is its header as
     * checked again now: the engine goes on only when it equals what [saved] said.
     *
     * @throws MemoryVaultUnavailableException when it cannot tell now; the image is kept.
     */
    fun beginRestore(): MemoryReader

    /**
     * Deletes the image it holds, if any, and one being written. The engine calls this whenever
     * the image can no longer be the beginning of the memory it holds. Needs no key.
     */
    fun delete()
}

/**
 * One save in progress. The engine hands [buffer] and [firstPieceOffset] to
 * [NativeLlama.exportMemory] with this as its sink, so every piece arrives through
 * [MemorySink.write]; a piece must be used (sealed and written) before that returns, and false
 * stops the save. Nothing becomes the vault's image until [commit]. [close] always follows,
 * committed or not, and abandons a save that was not committed.
 */
interface MemoryWriter : MemorySink, Closeable {

    /** The vault's own direct buffer, at least [NativeLlama.MEMORY_PIECE_BYTES_MIN] bytes. */
    val buffer: ByteBuffer

    /** How many bytes at the start of [buffer] are the vault's header; below the buffer's capacity. */
    val firstPieceOffset: Int

    /**
     * Makes what was written the vault's image, in place of the one it held. Called only after
     * the export returned normally, so after the piece marked last. Throws when the vault did
     * not see the whole image; nothing is then kept.
     */
    fun commit()

    /** Ends the save; one that was not committed is abandoned and leaves nothing behind. */
    override fun close()
}

/**
 * Says that a [MemoryVault] cannot tell now whether the image it holds is good: the key it opens
 * images with was destroyed, or its storage did not answer. The engine never deletes an image for
 * this; see the class comment of [MemoryVault].
 */
class MemoryVaultUnavailableException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * One restore in progress: [NativeLlama.importMemory] reads the image through [buffer], with this
 * as its source. Every piece [MemorySource.read] returns has been checked by the vault first, and
 * it returns 0 only after the image's last piece, with nothing after it. A read that cannot go
 * on for a reason that says nothing against the image throws [MemoryVaultUnavailableException];
 * a piece that was read and refused returns -1. [close] always follows.
 */
interface MemoryReader : MemorySource, Closeable {

    /** The image's header as the vault checked it when the restore began. */
    val memory: SavedMemory

    /** The vault's own direct buffer, at least [NativeLlama.MEMORY_PIECE_BYTES_MIN] bytes. */
    val buffer: ByteBuffer

    /** How many bytes at the start of the first piece are the vault's header. */
    val firstPieceOffset: Int
}

/** Why the engine was asked to save the memory: the rules differ ([InferenceEngine.saveMemory]). */
enum class SaveTrigger {
    /** The app left the screen, or an answer ended out of sight: saved only when enough is new. */
    LEFT_SCREEN,

    /** The session is closing: saved whenever the memory is worth keeping at all. */
    LOCK,
}

/** How a call of [InferenceEngine.saveMemory] ended. */
sealed interface MemorySaveResult {

    /** The vault now holds an image of the [tokens] tokens in memory, [bytes] bytes long. */
    data class Saved(val tokens: Int, val bytes: Long) : MemorySaveResult

    /** Nothing was saved, for [reason]. Whatever the vault held before is as the vault left it. */
    data class NotSaved(val reason: Reason) : MemorySaveResult {
        enum class Reason {
            /** No model is loaded. */
            NOT_LOADED,

            /** The engine was busy (a reply, a load) when the app left the screen. */
            BUSY,

            /** The memory is empty, or does not agree with the engine's copy of it. */
            EMPTY,

            /** The memory is not the conversation named, or of none named. */
            OTHER_CONVERSATION,

            /** The memory was marked not to be saved and has not been rebuilt from nothing since. */
            MARKED,

            /** Reading the memory again would take less than the least worth saving, or nobody knows how long. */
            NOT_WORTH_IT,

            /** The app left the screen and too little is new since the last save. */
            LITTLE_NEW,

            /** A request, forgetting, unloading or the caller stopped the save. */
            STOPPED,

            /** The vault or the native session failed. */
            FAILED,
        }
    }
}

/** How a call of [InferenceEngine.restoreMemory] ended. */
sealed interface MemoryRestoreResult {

    /** The memory holds the [tokens] tokens of the saved image; the next request reads only what is new. */
    data class Restored(val tokens: Int) : MemoryRestoreResult

    /** The vault holds nothing; the conversation is read as it always was. */
    data object NothingSaved : MemoryRestoreResult

    /** The memory already is the saved conversation, from the save the vault holds; nothing was done. */
    data object AlreadyHeld : MemoryRestoreResult

    /**
     * Nothing was restored, for [reason]. The image is kept for [Reason.OTHER_CONVERSATION] and
     * [Reason.UNAVAILABLE], and for [Reason.STOPPED] when the engine itself or the caller stopped
     * the restore: it then waits for a restore of its own conversation, unless a request comes
     * first (which deletes it). For every other reason, and when the vault stopped the restore
     * by refusing a piece, it was deleted. The memory is empty whenever the restore had begun.
     */
    data class NotRestored(val reason: Reason) : MemoryRestoreResult {
        enum class Reason {
            /** No model is loaded; nothing was touched. */
            NOT_LOADED,

            /** The image is of another conversation; it waits, and the next request deletes it. */
            OTHER_CONVERSATION,

            /** The image is of another model. */
            OTHER_MODEL,

            /** The memory was marked not to be saved: the image may hold what was to be forgotten. */
            MARKED,

            /** Reading the image's tokens again would take less than the least worth saving. */
            NOT_WORTH_IT,

            /** The memory already holds this conversation, newer than an image the engine cannot place. */
            MEMORY_IN_USE,

            /** The context could not be given a size that holds the image's tokens. */
            CONTEXT_TOO_SMALL,

            /**
             * Forgetting, unloading, loading or the caller stopped the restore (the image is
             * kept), or the vault stopped it by refusing a piece (the image is deleted).
             */
            STOPPED,

            /** The vault could not tell now whether its image is good ([MemoryVaultUnavailableException]); the image is kept. */
            UNAVAILABLE,

            /** The vault, the image or the native session failed, or the counts did not agree. */
            FAILED,
        }
    }
}

/**
 * What a user interface may show of a restore, without any of the conversation: whether one is
 * in progress, and how the last one ended.
 */
sealed interface MemoryRestoreState {

    /** No restore since the model was loaded or the memory was forgotten. */
    data object Idle : MemoryRestoreState

    /** A saved memory of [modelId] is being read back. */
    data class Restoring(val modelId: String) : MemoryRestoreState

    /** It was read back: [tokens] tokens of the conversation need not be read again. */
    data class Restored(val modelId: String, val tokens: Int) : MemoryRestoreState

    /** It could not be used, and was deleted: the conversation is read again at the next answer. */
    data class ReadAgain(val modelId: String) : MemoryRestoreState
}
