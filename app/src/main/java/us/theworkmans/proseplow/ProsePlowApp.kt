package us.theworkmans.proseplow

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.biometric.BiometricManager
import kotlinx.coroutines.Dispatchers
import us.theworkmans.proseplow.security.AndroidKeyVault
import us.theworkmans.proseplow.security.AppFiles
import us.theworkmans.proseplow.security.AppLock
import us.theworkmans.proseplow.security.BiometricStatus
import us.theworkmans.proseplow.security.Clock
import us.theworkmans.proseplow.security.LockConfigStore
import us.theworkmans.proseplow.security.LockManager

/**
 * The application object. It only holds the [container]; nothing is created until the activity
 * first asks for it.
 */
class ProsePlowApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

/**
 * Hand-wired app-wide singletons (no DI framework), which outlive the activity. None of them
 * holds the data key or anything decrypted: that only exists inside an [AppSession], between an
 * unlock and the next lock.
 */
class AppContainer(context: Context) {
    private val app = context.applicationContext

    val files = AppFiles(app.filesDir, app.cacheDir)

    private val layout = DataLayout(files)

    private val clock = object : Clock {
        override fun wallMillis() = System.currentTimeMillis()

        /** Counts the time the phone sleeps, unlike uptimeMillis: a wait or a timeout cannot be slept through. */
        override fun elapsedMillis() = SystemClock.elapsedRealtime()
    }

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
        openSession = { cipher -> AppSession(cipher, layout) },
        biometricStatus = {
            // Only "none enrolled" removes fingerprint unlock; NO_HARDWARE, HW_UNAVAILABLE,
            // UNSUPPORTED, SECURITY_UPDATE_REQUIRED and STATUS_UNKNOWN keep it for later.
            when (BiometricManager.from(app).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
                BiometricManager.BIOMETRIC_SUCCESS -> BiometricStatus.AVAILABLE
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricStatus.NONE_ENROLLED
                else -> BiometricStatus.UNAVAILABLE
            }
        },
        mainDispatcher = Dispatchers.Main.immediate,
        ioDispatcher = Dispatchers.IO,
    )
}
