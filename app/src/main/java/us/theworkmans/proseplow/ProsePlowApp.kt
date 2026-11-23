package us.theworkmans.proseplow

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import us.theworkmans.proseplow.background.AndroidKeepAliveProbe
import us.theworkmans.proseplow.background.AndroidReadyNotice
import us.theworkmans.proseplow.background.AnswerService
import us.theworkmans.proseplow.background.AnswerWork
import us.theworkmans.proseplow.background.KeepAliveSource
import us.theworkmans.proseplow.background.MemoryPressure
import us.theworkmans.proseplow.core.engine.ContextPolicy
import us.theworkmans.proseplow.core.engine.DeviceContextLimits
import us.theworkmans.proseplow.core.engine.GenerationGate
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
import us.theworkmans.proseplow.security.MakesWay
import us.theworkmans.proseplow.security.ScreenshotAllowance
import java.util.concurrent.Executors

/**
 * The application object. It only holds the [container]; nothing is created until the activity
 * first asks for it. The answer service asks through [keepAliveWanted], which does not create
 * it: a process in which no activity has run (one that only runs tests, for one) never has a
 * container, and so never has a lock that could end it.
 */
class ProsePlowApp : Application(), KeepAliveSource {
    /** What Android has said about memory being short; see [MemoryPressure]. It exists without the container. */
    val memoryPressure = MemoryPressure { SystemClock.elapsedRealtime() }

    private val lazyContainer = lazy { AppContainer(this, memoryPressure) }
    val container: AppContainer by lazyContainer

    override val keepAliveWanted: StateFlow<Boolean>?
        get() = if (lazyContainer.isInitialized()) container.answerWork.keepAliveWanted else null

    override fun onKeepAlive(running: Boolean) {
        if (lazyContainer.isInitialized()) container.answerWork.onKeepAlive(running)
    }

    override fun onKeepAliveRefused() {
        if (lazyContainer.isInitialized()) container.answerWork.onKeepAliveRefused()
    }

    override fun onCreate() {
        super.onCreate()
        turnSmartSelectionOff()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        memoryPressure.onTrimLevel(level)
    }

    @Suppress("OVERRIDE_DEPRECATION") // Still called on the versions this app runs on, and the one signal that the whole system is short.
    override fun onLowMemory() {
        super.onLowMemory()
        memoryPressure.onLowMemory()
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
class AppContainer(context: Context, memoryPressure: MemoryPressure) {
    private val app = context.applicationContext

    /** The device's memory, asked whenever a model's context is to be given a size; see [DeviceMemoryGauge]. */
    private val deviceMemory = DeviceMemoryGauge(app, memoryPressure)

    /** How large a model's context may be on this phone; the same numbers the engine decides with. */
    private val contextPolicy = ContextPolicy()

    /** What this phone allows a model's context, for the settings and for the longest reply that follows from it. */
    private val contextLimits = DeviceContextLimits(contextPolicy, deviceMemory.totalBytes())

    val files = AppFiles(app.filesDir, app.cacheDir)

    private val layout = DataLayout(files)

    /**
     * The inference engine, for the life of the process: loading a model takes seconds, and its
     * weights are no secret, so the loaded model survives a lock, and an unlock in the same
     * process finds it ready. (The process itself ends once the app is locked and out of sight;
     * the unlock after that loads the model again.) Every call into native code is made on one
     * thread of its own, as the engine requires. Nothing is created, and no thread started,
     * before the first unlock.
     */
    private val engine: InferenceEngine by lazy {
        val nativeThread = Executors.newSingleThreadExecutor { task -> Thread(task, "inference") }
        LlamaEngine(NativeEngine.factory(app), nativeThread.asCoroutineDispatcher(), gate = generationGate, memoryGauge = deviceMemory, contextPolicy = contextPolicy)
    }

    /**
     * Closed by the lock for as long as it derives a key from a passphrase, so that the model
     * does not hold the fast processor cores while someone waits at the lock screen. The
     * engine's generations wait at it; nothing else knows it.
     */
    private val generationGate = GenerationGate()

    private val clock = object : Clock {
        override fun wallMillis() = System.currentTimeMillis()

        /** Counts the time the phone sleeps, unlike uptimeMillis: a wait or a timeout cannot be slept through. */
        override fun elapsedMillis() = SystemClock.elapsedRealtime()

        /**
         * The system's count of boots (Settings.Global.BOOT_COUNT, readable without a permission
         * from Android 7). Null whenever it cannot be read: fingerprint unlock then asks for the
         * passphrase, because a restart cannot be ruled out.
         */
        override fun bootCount(): Int? = try {
            Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT)
        } catch (_: Exception) {
            null
        }
    }

    /** Everything Android has to do for the window policy; see [ScreenSecurity]. */
    val screenSecurity = ScreenSecurity(app)

    /**
     * What keeps the process alive while an answer is written out of sight, and says when one is
     * ready; see [AnswerWork]. It holds nothing of any answer.
     */
    val answerWork = AnswerWork(keepAlive = { AnswerService.start(app) }, readyNotice = AndroidReadyNotice(app), probe = AndroidKeepAliveProbe(app))

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
        openSession = { cipher ->
            AppSession(
                cipher, layout, engine, NativeEngine.availableModels(app),
                answerEvents = answerWork,
                contextLimits = contextLimits,
            )
        },
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
        processEnder = AndroidProcessEnder(app),
        // A session is left alive behind the lock only while the answer service really keeps the process awake, as Android says at that moment.
        canFinishOutOfSight = answerWork::canFinishOutOfSight,
        heavyWork = object : MakesWay {
            override fun standAside() = generationGate.close()

            override fun carryOn() = generationGate.open()
        },
    )

    init {
        // For the life of the process: the answer service stays until a session that is being
        // finished or closed behind the lock has lost its key.
        CoroutineScope(Dispatchers.Main.immediate).launch { appLock.sessionEnding.collect(answerWork::onSessionEnding) }
        // And a session left alive behind the lock does not outlive the keep-alive: its end, told
        // by the service on the main thread, ends the lease at once.
        answerWork.whenKeepAliveEnds(appLock::onKeepAliveEnded)
    }
}
