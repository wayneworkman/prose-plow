package us.theworkmans.proseplow

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import us.theworkmans.proseplow.core.engine.InferenceEngine
import us.theworkmans.proseplow.core.engine.LlamaEngine
import us.theworkmans.proseplow.llama.NativeEngine
import us.theworkmans.proseplow.security.AndroidKeyVault
import us.theworkmans.proseplow.security.AppFiles
import us.theworkmans.proseplow.security.AppLock
import us.theworkmans.proseplow.security.BiometricStatus
import us.theworkmans.proseplow.security.Clock
import us.theworkmans.proseplow.security.LockConfigStore
import us.theworkmans.proseplow.security.LockManager
import us.theworkmans.proseplow.security.ScreenshotAllowance
import java.util.concurrent.Executors

/**
 * The application object. It only holds the [container]; nothing is created until the activity
 * first asks for it.
 */
class ProsePlowApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        turnSmartSelectionOff()
    }

    /**
     * Selecting text in a chat must not hand that text to the system's text classifier. The text
     * library does that by default ("smart selection": it asks the classifier where the
     * selection should end and what could be done with it), and the classifier is another
     * component of the phone, on some phones one that the manufacturer or another app supplies.
     * It is switched off for the whole process, before any screen exists.
     */
    @OptIn(ExperimentalFoundationApi::class)
    private fun turnSmartSelectionOff() {
        ComposeFoundationFlags.isSmartSelectionEnabled = false
    }
}

/**
 * Hand-wired app-wide singletons (no DI framework), which outlive the activity. None of them
 * holds the data key or anything decrypted: that only exists inside an [AppSession], between an
 * unlock and the next lock. The [engine] is no exception: it holds a conversation only while a
 * session is using it, and the session makes it forget when it ends.
 */
class AppContainer(context: Context) {
    private val app = context.applicationContext

    val files = AppFiles(app.filesDir, app.cacheDir)

    private val layout = DataLayout(files)

    /**
     * The inference engine, for the life of the process: loading a model takes seconds, and its
     * weights are no secret, so the loaded model survives a lock and the next unlock finds it
     * ready. Every call into native code is made on one thread of its own, as the engine requires.
     * Nothing is created, and no thread started, before the first unlock.
     */
    private val engine: InferenceEngine by lazy {
        val nativeThread = Executors.newSingleThreadExecutor { task -> Thread(task, "inference") }
        LlamaEngine(NativeEngine.factory(app), nativeThread.asCoroutineDispatcher())
    }

    private val clock = object : Clock {
        override fun wallMillis() = System.currentTimeMillis()

        /** Counts the time the phone sleeps, unlike uptimeMillis: a wait or a timeout cannot be slept through. */
        override fun elapsedMillis() = SystemClock.elapsedRealtime()
    }

    /** Everything Android has to do for the window policy; see [ScreenSecurity]. */
    val screenSecurity = ScreenSecurity(app)

    val appLock: AppLock<AppSession> = AppLock(
        manager = LockManager(
            store = LockConfigStore(files.lockConfig),
            // Loads the Android Keystore lazily, on the lock's I/O thread: a key store that fails
            // to load shows the lock's error screen (Try again / Reset) instead of crashing.
            vault = AndroidKeyVault(),
            clock = clock,
            encryptedDataExists = files::encryptedDataExists,
        ),
        files = files,
        clock = clock,
        // The models offered are the ones whose files this build really carries, asked at every unlock.
        openSession = { cipher -> AppSession(cipher, layout, engine, NativeEngine.availableModels(app)) },
        biometricStatus = {
            // Only "none enrolled" removes fingerprint unlock; NO_HARDWARE, HW_UNAVAILABLE,
            // UNSUPPORTED, SECURITY_UPDATE_REQUIRED and STATUS_UNKNOWN keep it for later.
            when (BiometricManager.from(app).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
                BiometricManager.BIOMETRIC_SUCCESS -> BiometricStatus.AVAILABLE
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricStatus.NONE_ENROLLED
                else -> BiometricStatus.UNAVAILABLE
            }
        },
        screenshots = ScreenshotAllowance(clock, screenSecurity, Dispatchers.Main.immediate),
        mainDispatcher = Dispatchers.Main.immediate,
        ioDispatcher = Dispatchers.IO,
    )
}
