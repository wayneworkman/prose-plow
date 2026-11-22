package us.theworkmans.proseplow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import us.theworkmans.proseplow.security.AppFiles
import us.theworkmans.proseplow.security.TestKeys
import java.io.File
import java.io.IOException

/** The unlocked session as it is today: where its files go, and what its close promises the lock. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppSessionTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val files by lazy { AppFiles(temp.newFolder("files"), temp.newFolder("cache")) }

    private class ScreenModel : ViewModel() {
        var cleared = false

        override fun onCleared() {
            cleared = true
        }
    }

    @Test
    fun theStoresFilesAreOnesTheLockRecognisesAsEncryptedData() {
        val layout = DataLayout(files)
        assertEquals(File(files.filesDir, "settings.enc"), layout.settings)
        assertEquals(File(files.filesDir, "sessions"), layout.sessions)
        assertEquals("settings", DataLayout.SETTINGS_PURPOSE)
        assertEquals("session:3f2a", DataLayout.sessionPurpose("3f2a"))

        assertFalse(files.encryptedDataExists())
        layout.settings.writeBytes(byteArrayOf(1))
        assertTrue(files.encryptedDataExists())
        assertTrue(layout.settings.delete())
        File(layout.sessions, "3f2a.enc").apply { parentFile!!.mkdirs() }.writeBytes(byteArrayOf(1))
        assertTrue(files.encryptedDataExists())
    }

    @Test
    fun closeWaitsForTheWritesThatAreRunning() = runTest {
        val session = AppSession(TestKeys.cipher(), DataLayout(files), StandardTestDispatcher(testScheduler))
        val disk = CompletableDeferred<Unit>()
        var written = false
        session.writerScope.launch {
            disk.await()
            written = true
        }
        val closing = launch { session.close() }
        runCurrent()
        assertFalse("still waiting for the write", closing.isCompleted)

        disk.complete(Unit)
        runCurrent()
        assertTrue(written)
        assertTrue(closing.isCompleted)

        // Nothing can be written through the session any more.
        var late = false
        session.writerScope.launch { late = true }
        runCurrent()
        assertFalse(late)
    }

    @Test
    fun aWriteThatNeverFinishesDoesNotKeepTheKeyAliveForEver() = runTest {
        val session = AppSession(TestKeys.cipher(), DataLayout(files), StandardTestDispatcher(testScheduler))
        var stopped = false
        session.writerScope.launch {
            try {
                awaitCancellation()
            } finally {
                stopped = true
            }
        }
        val closing = launch { session.close() }
        advanceTimeBy(9_999)
        runCurrent()
        assertFalse(closing.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertTrue("after ten seconds the write is stopped and the close goes on", closing.isCompleted && stopped)
    }

    @Test
    fun aWriteThatFailsIsShownAndDoesNotStopTheOthers() = runTest {
        val session = AppSession(TestKeys.cipher(), DataLayout(files), StandardTestDispatcher(testScheduler))
        assertNull(session.writeFailure.value)
        var other = false
        session.writerScope.launch { throw IOException("No space left on device") }
        session.writerScope.launch { other = true }
        runCurrent()
        assertEquals("IOException", session.writeFailure.value)
        assertTrue(other)

        session.writeFailureShown()
        assertNull(session.writeFailure.value)
        session.close()
    }

    @Test
    fun clearingTheScreensClearsTheirViewModelsAndTheStoreCanBeUsedAgain() {
        val session = AppSession(TestKeys.cipher(), DataLayout(files))
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") // The only class this factory is asked for.
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ScreenModel() as T
        }
        val first = ViewModelProvider(session.viewModelStore, factory)[ScreenModel::class.java]
        session.clearScreens()
        assertTrue(first.cleared)

        val second = ViewModelProvider(session.viewModelStore, factory)[ScreenModel::class.java]
        assertFalse(second === first)
        assertFalse(second.cleared)
    }
}
