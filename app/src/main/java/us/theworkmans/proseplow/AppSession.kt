package us.theworkmans.proseplow

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull
import us.theworkmans.proseplow.security.AppFiles
import us.theworkmans.proseplow.security.BlobCipher
import us.theworkmans.proseplow.security.Purposes
import us.theworkmans.proseplow.security.UnlockedSession
import java.io.File

/**
 * Where the stores of an unlocked session keep their files, and the purposes they seal them
 * with. Every encrypted file's name ends in `.enc` (see [AppFiles]): that is how the lock knows
 * that data exists, and never makes a new key over it.
 */
class DataLayout(files: AppFiles) {
    /** The settings, one blob sealed with [SETTINGS_PURPOSE]. */
    val settings = File(files.filesDir, "settings.enc")

    /** The chat sessions, one `<id>.enc` file each, sealed with [sessionPurpose]. */
    val sessions = File(files.filesDir, "sessions")

    companion object {
        val SETTINGS_PURPOSE = Purposes.of("settings")

        fun sessionPurpose(id: String) = Purposes.of("session", id)
    }
}

/**
 * Everything that holds or reads decrypted data, created when the app is unlocked and ended when
 * it locks. For now that is little: the [cipher] of this unlock, the scope in which the stores
 * will do their writing, and the ViewModels of the screens ([viewModelStore], which outlives a
 * rotation because it lives here and not in the activity). The stores of the chat feature are
 * added here; what [close] promises is already in place for them.
 */
class AppSession(
    val cipher: BlobCipher,
    val layout: DataLayout,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : UnlockedSession {

    private val _writeFailure = MutableStateFlow<String?>(null)

    /**
     * What went wrong in the last write that failed without being handled where it was started
     * (an exception's name), until the screen has shown it ([writeFailureShown]).
     */
    val writeFailure: StateFlow<String?> = _writeFailure.asStateFlow()

    /**
     * Every write to the session's files runs here. [close] waits for what is running before the
     * data key goes. A write that throws (a full disk) must not crash the app: whatever its
     * caller does not catch ends up in [writeFailure], and the other writes go on.
     */
    val writerScope = CoroutineScope(
        SupervisorJob() + ioDispatcher + CoroutineExceptionHandler { _, e -> _writeFailure.value = e.javaClass.simpleName },
    )

    val viewModelStore = ViewModelStore()

    fun writeFailureShown() {
        _writeFailure.value = null
    }

    /**
     * Clears the screens' ViewModels: when the app locks, and when the activity finishes (not for
     * a rotation). That ends what they were doing and queues their last writes in [writerScope].
     * The store can be used again afterwards.
     */
    override fun clearScreens() = viewModelStore.clear()

    /**
     * Waits (bounded) for the queued writes to reach the disk, then stops everything that is
     * still running. After this nothing of the session writes a file any more.
     */
    override suspend fun close() {
        withTimeoutOrNull(WRITE_TIMEOUT_MILLIS) {
            writerScope.coroutineContext.job.children.toList().joinAll()
        }
        writerScope.coroutineContext.job.cancelAndJoin()
    }

    private companion object {
        const val WRITE_TIMEOUT_MILLIS = 10_000L
    }
}
