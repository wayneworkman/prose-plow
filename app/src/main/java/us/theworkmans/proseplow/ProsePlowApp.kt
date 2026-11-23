package us.theworkmans.proseplow

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import us.theworkmans.proseplow.background.AndroidKeepAliveProbe
import us.theworkmans.proseplow.background.AndroidReadyNotice
import us.theworkmans.proseplow.background.AnswerService
import us.theworkmans.proseplow.background.AnswerWork
import us.theworkmans.proseplow.background.AndroidMaintenanceSlot
import us.theworkmans.proseplow.background.KeepAliveNotice
import us.theworkmans.proseplow.background.KeepAliveSource
import us.theworkmans.proseplow.background.MaintenanceHost
import us.theworkmans.proseplow.background.MaintenanceSchedule
import us.theworkmans.proseplow.background.MemoryPressure
import us.theworkmans.proseplow.chat.ChatController
import us.theworkmans.proseplow.core.chat.Clock as MessageClock
import us.theworkmans.proseplow.core.engine.ContextPolicy
import us.theworkmans.proseplow.core.engine.DeviceContextLimits
import us.theworkmans.proseplow.core.engine.GenerationGate
import us.theworkmans.proseplow.core.engine.InferenceEngine
import us.theworkmans.proseplow.core.engine.LlamaEngine
import us.theworkmans.proseplow.data.BuildIdentity
import us.theworkmans.proseplow.data.ExpiredFiles
import us.theworkmans.proseplow.data.FreeSpace
import us.theworkmans.proseplow.data.ModelMemoryFiles
import us.theworkmans.proseplow.data.ModelMemoryPlatform
import us.theworkmans.proseplow.data.ModelMemorySupport
import us.theworkmans.proseplow.llama.NativeEngine
import us.theworkmans.proseplow.security.AndroidKeyVault
import us.theworkmans.proseplow.security.AppFiles
import us.theworkmans.proseplow.security.AppLock
import us.theworkmans.proseplow.security.BiometricStatus
import us.theworkmans.proseplow.security.Clock
import us.theworkmans.proseplow.security.LockConfigStore
import us.theworkmans.proseplow.security.LockManager
import us.theworkmans.proseplow.security.MaintenanceScheduling
import us.theworkmans.proseplow.security.MakesWay
import us.theworkmans.proseplow.security.ScreenshotAllowance
import us.theworkmans.proseplow.security.TrustedClock
import java.util.concurrent.Executors

/**
 * The application object. It only holds the [container]; nothing is created until the activity
 * first asks for it. The answer service asks through [keepAliveWanted], which does not create
 * it: a process in which no activity has run (one that only runs tests, for one) never has a
 * container, and so never has a lock that could end it. The background maintenance job is the
 * one other thing that creates it ([startMaintenance]): a process the platform started for that
 * job has the lock start as any start does, and ends when the job is done.
 */
class ProsePlowApp : Application(), KeepAliveSource, MaintenanceHost {
    /** What Android has said about memory being short; see [MemoryPressure]. It exists without the container. */
    val memoryPressure = MemoryPressure { SystemClock.elapsedRealtime() }

    private val lazyContainer = lazy { AppContainer(this, memoryPressure) }
    val container: AppContainer by lazyContainer

    override val keepAliveWanted: StateFlow<Boolean>?
        get() = if (lazyContainer.isInitialized()) container.answerWork.keepAliveWanted else null

    override val keepAliveNotice: StateFlow<KeepAliveNotice>?
        get() = if (lazyContainer.isInitialized()) container.answerWork.notice else null

    override fun onKeepAlive(running: Boolean) {
        if (lazyContainer.isInitialized()) container.answerWork.onKeepAlive(running)
    }

    override fun onKeepAliveRefused() {
        if (lazyContainer.isInitialized()) container.answerWork.onKeepAliveRefused()
    }

    /**
     * A run of the background maintenance job (main thread). Whether the container exists yet
     * says whether this process was started for the run: then the lock's start, made here,
     * already is the work ([AppLock.startMaintenance]).
     */
    override fun startMaintenance(onFinished: (release: () -> Unit) -> Unit): Job {
        val startedForIt = !lazyContainer.isInitialized()
        return container.appLock.startMaintenance(startedForIt, onFinished)
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
     * The files of the model's saved memory, one object for the process: its deletions, by
     * whichever session or by the lock's clean-up, stop every save that began before them.
     */
    private val modelMemory = ModelMemoryFiles(layout.modelMemory, layout.sessions)

    /**
     * What the store of the model's saved memory needs of Android, made afresh at every unlock;
     * null on a version of Android where that memory is never kept ([ModelMemorySupport]).
     *
     * The build is the package's version code with the time it was installed or last updated
     * (a debug build installed again keeps its version code). Free space is what can be
     * allocated on the volume (`StorageManager.getAllocatableBytes`), which counts cached data the
     * system would clear for the app; the volume's size is the directory's total space.
     */
    private fun modelMemoryPlatform(): ModelMemoryPlatform? {
        if (!ModelMemorySupport.supported(Build.VERSION.SDK_INT)) return null
        val packageInfo = app.packageManager.getPackageInfo(app.packageName, 0)
        val models = NativeEngine.availableModels(app)
        val storage = app.getSystemService(StorageManager::class.java)
        return ModelMemoryPlatform(
            build = BuildIdentity(packageInfo.longVersionCode, packageInfo.lastUpdateTime),
            modelFileLength = { id -> models.firstOrNull { it.id == id }?.let { NativeEngine.packagedLength(app, it) } },
            freeSpace = { directory -> FreeSpace(storage.getAllocatableBytes(storage.getUuidForPath(directory)), directory.totalSpace) },
        )
    }

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

    /**
     * The time messages are dated and aged by, for the life of the process: the lock seeds it from
     * and stores it in the lock configuration, and every session dates its messages by it.
     */
    private val trustedClock = TrustedClock(clock)

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
            trustedClock = trustedClock,
        ),
        files = files,
        clock = clock,
        // The models offered are the ones whose files this build really carries, asked at every unlock.
        openSession = { cipher, lockedMessageLifetimeDays ->
            AppSession(
                cipher, layout, engine, NativeEngine.availableModels(app),
                clock = MessageClock { trustedClock.nowMillis() },
                answerEvents = answerWork,
                contextLimits = contextLimits,
                expiryIntervalMillis = ChatController.EXPIRY_INTERVAL_MILLIS,
                lockedMessageLifetimeDays = lockedMessageLifetimeDays,
                memoryFiles = modelMemory,
                memoryPlatform = modelMemoryPlatform(),
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
        // What has expired is deleted at every start of the lock, before anything can be unlocked.
        expiredData = ExpiredFiles(layout.sessions, layout.kept, layout.sessionIndex, modelMemory),
        maintenanceScheduling = MaintenanceScheduling(MaintenanceSchedule(AndroidMaintenanceSlot(app))::apply),
    )

    init {
        // For the life of the process: the answer service stays until a session that is being
        // finished or closed behind the lock has lost its key.
        CoroutineScope(Dispatchers.Main.immediate).launch { appLock.sessionEnding.collect(answerWork::onSessionEnding) }
        // And from the moment the app leaves the screen unlocked until its lock has fired, so
        // that the lock's timer runs on time. Collected on the main thread at once, inside the
        // activity's onStop, which is when Android still lets the service be started.
        CoroutineScope(Dispatchers.Main.immediate).launch { appLock.lockPending.collect(answerWork::onLockPending) }
        // And a session left alive behind the lock does not outlive the keep-alive: its end, told
        // by the service on the main thread, ends the lease at once.
        answerWork.whenKeepAliveEnds(appLock::onKeepAliveEnded)
    }
}
