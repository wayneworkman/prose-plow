package us.theworkmans.proseplow.security

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue
import javax.crypto.Cipher

sealed interface LockState<out S : UnlockedSession> {
    /** Reading the lock configuration (at start, on Try again, and while a reset runs). */
    data object Starting : LockState<Nothing>

    /** First run, and after every reset: nothing is stored and a passphrase must be created. */
    data object SetupRequired : LockState<Nothing>

    /** [generation] counts locks, so the fingerprint prompt is shown automatically once per lock. */
    data class Locked(val generation: Int) : LockState<Nothing>

    class Unlocked<S : UnlockedSession>(val session: S) : LockState<S>

    data class Unrecoverable(val reason: UnrecoverableReason) : LockState<Nothing>

    /** Nothing is known to be lost, but the lock or the data could not be opened (for example the disk is full). */
    data class Failed(val detail: String) : LockState<Nothing>
}

/** The security settings, readable while locked. */
data class SecurityStatus(
    val biometricEnabled: Boolean = false,
    val lockTimeoutSeconds: Int = LockConfig.DEFAULT_TIMEOUT_SECONDS,
    val fingerprintInvalidated: Boolean = false,
)

enum class BiometricStatus {
    AVAILABLE,

    /** No strong biometric enrolled (any fingerprint wrap is of no further use). */
    NONE_ENROLLED,

    /**
     * Not usable now, for any other reason (no or unavailable hardware, a security update
     * required, unknown, unsupported): the wrap is kept and the passphrase is used meanwhile.
     */
    UNAVAILABLE,
}

enum class BiometricPurpose { UNLOCK, ENROLL }

/** Shows the biometric prompt for [cipher]; implemented by the activity. */
fun interface BiometricLauncher {
    /** False if the prompt could not be shown now (for example the activity's state was already saved). */
    fun authenticate(cipher: Cipher, purpose: BiometricPurpose): Boolean
}

/** Messages about fingerprint use, shown once. */
enum class BiometricNotice {
    /** The wrap did not open with its own key and fingerprint unlock was removed. */
    UNLOCK_FAILED,

    /** It did not work this time; fingerprint unlock stays on. */
    UNLOCK_UNAVAILABLE,
    LOCKED_OUT,
    ENROLLED,
    ENROLL_FAILED,
    REMOVED,
}

/** Why fingerprint unlock is offered right after an unlock. */
enum class FingerprintOffer {
    /** The passphrase was just created. */
    FIRST_SETUP,

    /** It had been set up and was removed because its key was invalidated. */
    AGAIN,
}

/** The lock screen's transient state (kept here so it survives rotation). */
data class UnlockUi(
    val busy: Boolean = false,
    val wrongPassphrase: Boolean = false,
    /** The entered text was not checked: empty or longer than any passphrase. */
    val rejected: PassphraseProblem? = null,
    /** [AppLock.elapsedNow] until which attempts are refused (0: none). */
    val retryAtElapsed: Long = 0,
    val notice: BiometricNotice? = null,
    /** What went wrong unexpectedly, as a technical detail (an exception's name). */
    val error: String? = null,
    /** The fingerprint prompt is showing (its button is disabled meanwhile). */
    val biometricPromptShowing: Boolean = false,
)

/** The first-run screen's transient state (kept here so it survives rotation). */
data class SetupUi(
    /** The key is being derived and stored. */
    val busy: Boolean = false,
    /** Why the entered passphrase was not accepted. */
    val problem: PassphraseProblem? = null,
    /** What went wrong unexpectedly, as a technical detail (an exception's name). */
    val error: String? = null,
)

/** Result of checking the passphrase from Settings. */
sealed interface PassphraseCheck {
    class Ok(val proof: PassphraseProof) : PassphraseCheck

    data class Wrong(val retryInMillis: Long) : PassphraseCheck

    data class Throttled(val retryInMillis: Long) : PassphraseCheck

    /** Not checked (and not counted): empty or too long. */
    data class Rejected(val problem: PassphraseProblem) : PassphraseCheck

    data object Failed : PassphraseCheck
}

/**
 * The app lock as the rest of the app sees it: owns the [LockManager] and the current
 * [UnlockedSession], locks after the configured time in the background, and drives the biometric
 * prompt through the activity. It has no Android dependency: the clock and both dispatchers are
 * passed in, so the unit tests run it on virtual time.
 *
 * Threads:
 * - The public functions that are not `suspend` are called on the main thread, and every change
 *   of [state] happens there, so a state seen on the main thread stays true until that code
 *   returns. (The one exception is the backstop for unexpected failures, [onUnexpected], which
 *   can only turn [LockState.Starting] into [LockState.Failed].)
 * - Every call into the lock manager runs off the main thread, inside [withManager], which holds
 *   one mutex: its calls are never concurrent, and neither are the things that must not overlap
 *   with them (opening a session, closing one, the reset's deletions).
 * - Sessions are closed, and their data keys destroyed, in exactly one place ([closeRetired]),
 *   under that mutex; and whether a session ends is decided in exactly one place ([endSession]).
 */
class AppLock<S : UnlockedSession>(
    private val manager: LockManager,
    private val files: AppFiles,
    private val clock: Clock,
    /** Makes the session of an unlock. Called off the main thread; may read the stores and may fail. */
    private val openSession: (BlobCipher) -> S,
    private val biometricStatus: () -> BiometricStatus,
    /**
     * The temporary permission to take screenshots. It is not part of the lock (locking leaves
     * it alone), but the lock is where two of its ends are: a reset ends it, and coming to the
     * foreground is when an allowance whose timer could not run is found to be over.
     */
    val screenshots: ScreenshotAllowance,
    private val mainDispatcher: CoroutineDispatcher,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val mutex = Mutex()
    private val main = CoroutineScope(SupervisorJob() + mainDispatcher)

    /**
     * Where this class runs its own work. Every job catches what it expects; this handler is for
     * the rest (a key store can throw unexpected runtime exceptions, a disk can fail): it ends
     * up on the screen as an error instead of crashing the app. Nothing about the passphrase or
     * the keys is in such an exception.
     */
    private val work = CoroutineScope(SupervisorJob() + ioDispatcher + CoroutineExceptionHandler { _, e -> onUnexpected(e) })

    private val _state = MutableStateFlow<LockState<S>>(LockState.Starting)
    val state: StateFlow<LockState<S>> = _state.asStateFlow()

    private val _status = MutableStateFlow(SecurityStatus())
    val status: StateFlow<SecurityStatus> = _status.asStateFlow()

    private val _unlockUi = MutableStateFlow(UnlockUi())
    val unlockUi: StateFlow<UnlockUi> = _unlockUi.asStateFlow()

    private val _setupUi = MutableStateFlow(SetupUi())
    val setupUi: StateFlow<SetupUi> = _setupUi.asStateFlow()

    /** Set after an unlock when fingerprint unlock can be offered with that unlock's proof. */
    private val _fingerprintOffer = MutableStateFlow<FingerprintOffer?>(null)
    val fingerprintOffer: StateFlow<FingerprintOffer?> = _fingerprintOffer.asStateFlow()

    /** Incremented by every reset, so the activity can drop state it keeps for the old data. */
    private val _resets = MutableStateFlow(0)
    val resets: StateFlow<Int> = _resets.asStateFlow()

    /** What the last reset could not delete, until the user has been told ([resetFailureShown]). */
    private val _resetFailure = MutableStateFlow<ResetReport?>(null)
    val resetFailure: StateFlow<ResetReport?> = _resetFailure.asStateFlow()

    /**
     * Changes whenever previously saved screen state must not come back: at every lock, every
     * unlock and every reset. The activity keys the unlocked screens' composition with it (see
     * ActivityState.contentKey).
     */
    private val _sessionGeneration = MutableStateFlow(0)
    val sessionGeneration: StateFlow<Int> = _sessionGeneration.asStateFlow()

    /** An open session with the cipher it was given and the key inside that cipher. */
    private class Live<S>(val session: S, val crypto: DataCrypto, val key: DataKey)

    /** The session behind [LockState.Unlocked]. Written on the main thread only. */
    @Volatile private var live: Live<S>? = null

    /** Sessions that have ended and wait to be closed by [closeRetired]. */
    private val retired = ConcurrentLinkedQueue<Live<S>>()

    /**
     * One job for every closed session, which ends when that session has really stopped
     * ([UnlockedSession.awaitStopped]); normally at once. Only touched under the mutex.
     */
    private val stopping = ArrayList<Job>()

    /** Main thread only. */
    private var lockGeneration = 0
    private var autoPromptedGeneration = -1
    private var launcher: BiometricLauncher? = null
    private val backgroundPolicy = BackgroundLockPolicy()
    private var lockTimer: Job? = null

    /** MainActivity instances alive (created and not destroyed). Main thread only. */
    private var activities = 0

    @Volatile private var pendingPurpose: BiometricPurpose? = null
    @Volatile private var enrollmentProof: PassphraseProof? = null
    @Volatile private var offerProof: PassphraseProof? = null

    init {
        start()
    }

    /** The only way to the lock manager: one caller at a time, see the class comment. */
    private suspend inline fun <T> withManager(block: (LockManager) -> T): T = mutex.withLock { block(manager) }

    /** True while something is using the lock manager. For the unit tests, which assert it from inside the manager's calls. */
    internal val managerInUse: Boolean get() = mutex.isLocked

    /** The time [UnlockUi.retryAtElapsed] is measured in. */
    fun elapsedNow(): Long = clock.elapsedMillis()

    private fun start() {
        _state.value = LockState.Starting
        work.launch {
            withManager { m ->
                closeRetired()
                var waitMillis = 0L
                val next: LockState<Nothing> = try {
                    when (val result = m.start()) {
                        StartResult.SetupRequired -> LockState.SetupRequired
                        StartResult.Locked -> {
                            dropBiometricIfGone(m)
                            waitMillis = m.backoffRemainingMillis()
                            LockState.Locked(0)
                        }
                        is StartResult.Unrecoverable -> LockState.Unrecoverable(result.reason)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // For example a key store that is not ready, or files that cannot be read; Try again starts over.
                    LockState.Failed(describe(e))
                }
                publishStatus(m)
                withContext(mainDispatcher) {
                    _setupUi.value = SetupUi()
                    if (next is LockState.Locked) enterLocked(waitMillis) else _state.value = next
                }
            }
        }
    }

    /** Main thread. */
    private fun enterLocked(waitMillis: Long) {
        lockGeneration++
        _unlockUi.value = UnlockUi(retryAtElapsed = retryAt(waitMillis))
        _state.value = LockState.Locked(lockGeneration)
    }

    private fun publishStatus(m: LockManager) {
        val config = m.config
        _status.value = SecurityStatus(
            biometricEnabled = config?.biometric != null,
            lockTimeoutSeconds = config?.lockTimeoutSeconds ?: LockConfig.DEFAULT_TIMEOUT_SECONDS,
            fingerprintInvalidated = config?.fingerprintInvalidated == true,
        )
    }

    /**
     * Fingerprint unlock is removed quietly only when no strong biometric is enrolled any more.
     * Any other reason it is unavailable (no or busy hardware, a security update required,
     * unknown) keeps it: the passphrase is used until it works again.
     */
    private fun dropBiometricIfGone(m: LockManager) {
        if (m.config?.biometric == null) return
        when (biometricStatus()) {
            BiometricStatus.NONE_ENROLLED -> m.biometricLost()
            BiometricStatus.AVAILABLE, BiometricStatus.UNAVAILABLE -> Unit
        }
    }

    fun biometricAvailable(): Boolean = biometricStatus() == BiometricStatus.AVAILABLE

    // --- Sessions -------------------------------------------------------------------------------

    /** Why an open session's screens are going away. */
    private enum class SessionEnd {
        /** The app locks: "Lock now", the background timeout, or the check on coming back. */
        LOCK,

        /** All data is about to be deleted. */
        RESET,

        /** The screen it was unlocked from was gone by the time it opened; it was never shown. */
        NEVER_SHOWN,
    }

    /**
     * The single place where it is decided what becomes of an open session when the app leaves
     * the unlocked state (main thread). Today there is one answer whatever the [reason]: the
     * session ends now. Its screens are cleared at once, it is handed to [closeRetired], and
     * with it goes the data key, which stops anything still working on decrypted data (a
     * generation in progress included).
     *
     * This is the point a future "lease" would hook into: if a background task (a generation
     * that should finish and post a notification) were ever allowed to keep the data key while
     * the screens are locked, this function is where a [SessionEnd.LOCK] would park the session
     * instead of retiring it, to be handed back at the next unlock or retired when the task
     * ends. Whether that is allowed at all is the owner's decision and has not been taken. A
     * [SessionEnd.RESET] must always end the session: the reset deletes the files under it.
     */
    // [reason] is not looked at yet, because every reason has the same answer today. It is part of
    // the signature so that every caller already says why, which is what a lease would decide on.
    private fun endSession(session: Live<S>, @Suppress("UNUSED_PARAMETER") reason: SessionEnd) {
        session.session.clearScreens()
        retired.add(session)
    }

    /**
     * Under the mutex: closes every session that has ended, then destroys its data key. The only
     * place either happens. The session gets a bounded time to finish its queued writes (they
     * still need the key); the key is destroyed whatever the close did and however it ended, so
     * a write that hangs cannot keep the key alive, and anything left running fails from then
     * on. Everything that opens a session, reads the configuration afresh or deletes files calls
     * this first.
     *
     * What a closed session may still be doing (one write that was already on its way to the
     * disk when its time ran out) is remembered in [stopping], for the reset.
     */
    private suspend fun closeRetired() {
        stopping.removeAll { it.isCompleted }
        while (true) {
            val ended = retired.poll() ?: return
            try {
                ended.session.close()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A session that fails to close has still lost its key below.
            } finally {
                ended.crypto.destroy()
            }
            val session = ended.session
            stopping += work.launch {
                try {
                    session.awaitStopped()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Whatever it was doing has ended, if not well.
                }
            }
        }
    }

    /**
     * Under the mutex: returns only when no closed session can touch a file any more, however
     * long that takes. A lock does not wait for this (it must not: the key is to go); a reset
     * does, because it deletes the files, and a write that landed afterwards would put one back.
     */
    private suspend fun awaitSessionsStopped() {
        for (job in stopping.toList()) job.join()
        stopping.clear()
    }

    /**
     * Under the mutex: opens the session for [key] and shows it, unless the screen it was
     * unlocked from ([from]) is no longer the current one (a reset began meanwhile), in which
     * case the session is ended again unseen. [offer] is made with the new session.
     */
    private suspend fun open(key: DataKey, from: LockState<S>, offer: Pair<FingerprintOffer, PassphraseProof>?) {
        // The session of the unlock before may still be closing: its last writes get their
        // (bounded) time to land before this one reads.
        closeRetired()
        val crypto = DataCrypto(key)
        val session = try {
            openSession(crypto)
        } catch (e: CancellationException) {
            crypto.destroy()
            throw e
        } catch (e: Exception) {
            crypto.destroy()
            withContext(mainDispatcher) {
                if (_state.value == from) _state.value = LockState.Failed(describe(e))
            }
            return
        }
        val opened = Live(session, crypto, key)
        withContext(mainDispatcher) {
            if (_state.value != from) {
                endSession(opened, SessionEnd.NEVER_SHOWN)
                return@withContext
            }
            live = opened
            _unlockUi.value = UnlockUi()
            _setupUi.value = SetupUi()
            if (offer != null) {
                offerProof = offer.second
                _fingerprintOffer.value = offer.first
            }
            _sessionGeneration.update { it + 1 }
            _state.value = LockState.Unlocked(session)
            // The activity may have left while the key was being derived: the timeout has been
            // running since then, and with "immediately" this locks again before anything shows.
            backgroundPolicy.remainingMillis(clock.elapsedMillis())?.let(::scheduleLock)
        }
        closeRetired()
    }

    // --- Locking ----------------------------------------------------------------------------

    /**
     * Locks now (main thread): "Lock now" in Settings, the background timer, and the check on
     * coming back to the foreground. The state changes at once, so the activity tears the
     * screens down before this returns; then, off the main thread, the session's queued writes
     * get their (bounded) time to finish, it closes, and the data key is zeroed whether or not
     * they did. No-op unless unlocked.
     */
    fun lock() {
        if (_state.value !is LockState.Unlocked) return
        leaveUnlocked(SessionEnd.LOCK)
        enterLocked(waitMillis = 0)
        work.launch {
            withManager { m ->
                closeRetired()
                // Unless the user is already back in: an enrolment of the new session is not this lock's to cancel.
                if (live == null) m.cancelBiometricEnrollment()
                // Wrong passphrases entered in Settings count too; the lock screen shows their wait.
                val waitMillis = m.backoffRemainingMillis()
                if (waitMillis > 0) _unlockUi.update { it.copy(retryAtElapsed = retryAt(waitMillis)) }
                publishStatus(m)
            }
        }
    }

    /** Main thread: everything that belongs to the unlocked state goes, whatever the state is now. */
    private fun leaveUnlocked(reason: SessionEnd) {
        lockTimer?.cancel()
        lockTimer = null
        backgroundPolicy.reset()
        enrollmentProof = null
        offerProof = null
        _fingerprintOffer.value = null
        // The reference goes before the session is retired: from this line on nothing can pick
        // its key up for a new wrap (a passphrase change, a fingerprint enrolment), whatever
        // order the work that follows runs in.
        val ending = live
        live = null
        ending?.let { endSession(it, reason) }
        _sessionGeneration.update { it + 1 }
    }

    /** The activity went to the background (not for a configuration change). Main thread. */
    fun onBackground() {
        val delayMillis = backgroundPolicy.onBackground(clock.elapsedMillis(), _status.value.lockTimeoutSeconds * 1000L)
        if (_state.value is LockState.Unlocked) scheduleLock(delayMillis)
    }

    /**
     * Main thread. The timer runs on the main dispatcher's clock, which may stand still while the
     * phone sleeps or the process is frozen; [onForeground] makes up for that before anything of
     * the session can be seen again. Until then the key stays in memory longer than the timeout.
     */
    private fun scheduleLock(delayMillis: Long) {
        lockTimer?.cancel()
        if (delayMillis <= 0L) {
            lock()
        } else {
            lockTimer = main.launch {
                delay(delayMillis)
                lock()
            }
        }
    }

    /**
     * The activity is visible again. Also catches the timers that could not run (process frozen,
     * phone asleep): the lock's own, and the one that ends a screenshot allowance.
     */
    fun onForeground() {
        lockTimer?.cancel()
        lockTimer = null
        screenshots.current()
        if (backgroundPolicy.onForeground(clock.elapsedMillis())) lock()
    }

    /** A MainActivity was created. Main thread. */
    fun onActivityCreated() {
        activities++
    }

    /**
     * A MainActivity was destroyed; [finished]: for good (back pressed), not for a configuration
     * change. The screens' state is shared by every instance (it lives in the session), so it is
     * cleared only when the last instance finishes; the session stays until the lock engages.
     */
    fun onActivityDestroyed(finished: Boolean) {
        activities = (activities - 1).coerceAtLeast(0)
        if (finished && activities == 0) {
            // A prompt that was showing went with the activity; none is pending any more.
            if (pendingPurpose != null) {
                if (pendingPurpose == BiometricPurpose.ENROLL) cancelEnrollment()
                pendingPurpose = null
                _unlockUi.update { it.copy(biometricPromptShowing = false) }
            }
            live?.session?.clearScreens()
        }
    }

    // --- First run ----------------------------------------------------------------------------

    /**
     * Creates the passphrase from the two entries of the first-run screen (main thread), then
     * opens the first session. Both arrays are cleared here. The rules are checked before
     * anything is made; while they are not met nothing exists on disk or in the key store.
     */
    fun setUp(first: CharArray, second: CharArray) {
        val from = _state.value
        if (from != LockState.SetupRequired || _setupUi.value.busy) {
            first.wipe()
            second.wipe()
            return
        }
        val problem = PassphraseRules.checkNew(first, second)
        second.wipe()
        if (problem != null) {
            first.wipe()
            _setupUi.value = SetupUi(problem = problem)
            return
        }
        _setupUi.value = SetupUi(busy = true)
        work.launch {
            try {
                withManager { m ->
                    closeRetired()
                    val result = m.setUp(first)
                    publishStatus(m)
                    open(result.key, from, if (biometricAvailable()) FingerprintOffer.FIRST_SETUP to result.proof else null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _setupUi.update { it.copy(error = describe(e)) }
            } finally {
                first.wipe()
                _setupUi.update { it.copy(busy = false) }
            }
        }
    }

    // --- Unlocking ----------------------------------------------------------------------------

    /** Checks [passphrase] off the main thread and clears it afterwards. */
    fun unlockWithPassphrase(passphrase: CharArray) {
        val from = _state.value
        if (_unlockUi.value.busy || from !is LockState.Locked) {
            passphrase.wipe()
            return
        }
        _unlockUi.update { it.copy(busy = true, wrongPassphrase = false, rejected = null, notice = null, error = null) }
        work.launch {
            try {
                withManager { m ->
                    when (val result = m.checkPassphrase(passphrase)) {
                        is PassphraseResult.Success -> {
                            val again = m.config?.fingerprintInvalidated == true && biometricAvailable()
                            publishStatus(m)
                            open(result.key, from, if (again) FingerprintOffer.AGAIN to result.proof else null)
                        }
                        is PassphraseResult.Wrong -> _unlockUi.update {
                            it.copy(wrongPassphrase = true, retryAtElapsed = retryAt(result.retryInMillis))
                        }
                        is PassphraseResult.Throttled -> _unlockUi.update { it.copy(retryAtElapsed = retryAt(result.retryInMillis)) }
                        is PassphraseResult.Rejected -> _unlockUi.update { it.copy(rejected = result.problem) }
                        is PassphraseResult.Unrecoverable -> withContext(mainDispatcher) {
                            if (_state.value == from) _state.value = LockState.Unrecoverable(result.reason)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _unlockUi.update { it.copy(error = describe(e)) }
            } finally {
                passphrase.wipe()
                _unlockUi.update { it.copy(busy = false) }
            }
        }
    }

    /**
     * Shows the fingerprint prompt automatically, once per lock (main thread; called on every
     * resume of the lock screen). If it could not be shown, the next resume tries again.
     */
    fun autoPromptBiometric(forGeneration: Int) {
        if (autoPromptedGeneration == forGeneration) return
        autoPromptedGeneration = forGeneration
        if (_status.value.biometricEnabled) requestBiometricUnlock()
    }

    fun requestBiometricUnlock() {
        if (_unlockUi.value.biometricPromptShowing || _state.value !is LockState.Locked) return
        work.launch {
            try {
                val cipher = withManager { m ->
                    dropBiometricIfGone(m)
                    val result = m.biometricUnlockCipher()
                    publishStatus(m)
                    (result as? BiometricCipherResult.Ready)?.cipher
                }
                if (cipher != null) launchPrompt(BiometricPurpose.UNLOCK, cipher)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _unlockUi.update { it.copy(error = describe(e)) }
            }
        }
    }

    /**
     * Shows the prompt through the attached activity. When that is not possible (no activity, or
     * its state is already saved, in which case androidx.biometric would silently not show it),
     * nothing stays pending: an unlock prompt is offered again on the next resume, an enrolment
     * is cancelled.
     */
    private suspend fun launchPrompt(purpose: BiometricPurpose, cipher: Cipher) {
        val shown = withContext(mainDispatcher) {
            // An unlock prompt is only for the lock screen; the passphrase may have been faster.
            val wanted = purpose != BiometricPurpose.UNLOCK || _state.value is LockState.Locked
            pendingPurpose = purpose
            val ok = wanted && try {
                launcher?.authenticate(cipher, purpose) == true
            } catch (_: Exception) {
                false
            }
            if (ok) {
                _unlockUi.update { it.copy(biometricPromptShowing = true) }
            } else {
                pendingPurpose = null
                if (purpose == BiometricPurpose.UNLOCK) autoPromptedGeneration = -1
            }
            ok
        }
        if (!shown && purpose == BiometricPurpose.ENROLL) {
            enrollmentProof = null
            withManager { m -> m.cancelBiometricEnrollment() }
            _unlockUi.update { it.copy(notice = BiometricNotice.ENROLL_FAILED) }
        }
    }

    /** The activity's prompt callback (main thread): the user authenticated and [cipher] may be used. */
    fun onBiometricSucceeded(cipher: Cipher?) {
        val purpose = pendingPurpose ?: return
        pendingPurpose = null
        _unlockUi.update { it.copy(biometricPromptShowing = false) }
        val from = _state.value
        work.launch {
            try {
                withManager { m ->
                    if (purpose == BiometricPurpose.ENROLL) {
                        val proof = enrollmentProof
                        val key = live?.key
                        enrollmentProof = null
                        val ok = cipher != null && proof != null && key != null && try {
                            m.completeBiometricEnrollment(proof, key, cipher)
                            true
                        } catch (_: Exception) {
                            false
                        }
                        if (!ok) m.cancelBiometricEnrollment()
                        _unlockUi.update { it.copy(notice = if (ok) BiometricNotice.ENROLLED else BiometricNotice.ENROLL_FAILED) }
                    } else if (cipher != null && from is LockState.Locked) {
                        val key = m.unlockWithBiometric(cipher)
                        when {
                            key != null -> open(key, from, offer = null)
                            m.config?.biometric == null -> _unlockUi.update { it.copy(notice = BiometricNotice.UNLOCK_FAILED) }
                            else -> _unlockUi.update { it.copy(notice = BiometricNotice.UNLOCK_UNAVAILABLE) }
                        }
                    }
                    publishStatus(m)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _unlockUi.update { it.copy(error = describe(e)) }
            }
        }
    }

    /** The prompt ended without success (main thread). Codes are androidx.biometric.BiometricPrompt's. */
    fun onBiometricError(code: Int) {
        val purpose = pendingPurpose ?: return
        pendingPurpose = null
        _unlockUi.update { it.copy(biometricPromptShowing = false) }
        work.launch {
            try {
                withManager { m ->
                    when (code) {
                        ERROR_NO_BIOMETRICS, ERROR_HW_NOT_PRESENT, ERROR_HW_UNAVAILABLE, ERROR_SECURITY_UPDATE_REQUIRED -> {
                            if (purpose == BiometricPurpose.UNLOCK) {
                                // Kept unless no strong biometric is enrolled any more; the passphrase is used meanwhile.
                                val hadFingerprint = m.config?.biometric != null
                                dropBiometricIfGone(m)
                                val removed = hadFingerprint && m.config?.biometric == null
                                _unlockUi.update { it.copy(notice = if (removed) BiometricNotice.REMOVED else BiometricNotice.UNLOCK_UNAVAILABLE) }
                            }
                        }
                        ERROR_LOCKOUT, ERROR_LOCKOUT_PERMANENT -> _unlockUi.update { it.copy(notice = BiometricNotice.LOCKED_OUT) }
                        else -> Unit
                    }
                    if (purpose == BiometricPurpose.ENROLL) {
                        enrollmentProof = null
                        // The new key goes; a fingerprint unlock already set up stays as it was.
                        m.cancelBiometricEnrollment()
                        if (code != ERROR_NEGATIVE_BUTTON && code != ERROR_USER_CANCELED) {
                            _unlockUi.update { it.copy(notice = BiometricNotice.ENROLL_FAILED) }
                        }
                    }
                    publishStatus(m)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _unlockUi.update { it.copy(error = describe(e)) }
            }
        }
    }

    fun attach(launcher: BiometricLauncher) {
        this.launcher = launcher
    }

    fun detach(launcher: BiometricLauncher) {
        if (this.launcher === launcher) this.launcher = null
    }

    fun noticeShown() = _unlockUi.update { it.copy(notice = null) }

    /**
     * The activity found no prompt fragment of androidx.biometric although the lock screen
     * thinks a prompt is showing (main thread, on resume): the prompt ended without a callback.
     * Only the "showing" flag is cleared, which re-enables the fingerprint button. The pending
     * purpose stays, so a callback that is merely late (it is posted separately from the
     * fragment's removal) is still handled.
     */
    fun onBiometricPromptAbsent() {
        if (_unlockUi.value.biometricPromptShowing) _unlockUi.update { it.copy(biometricPromptShowing = false) }
    }

    private fun cancelEnrollment() {
        enrollmentProof = null
        work.launch { withManager { m -> m.cancelBiometricEnrollment() } }
    }

    // --- Settings (only while unlocked) -------------------------------------------------------

    /** Checks the passphrase for a change in Settings (counted like a lock screen attempt). */
    suspend fun checkPassphrase(passphrase: CharArray): PassphraseCheck = op { m ->
        when (val result = m.checkPassphrase(passphrase)) {
            is PassphraseResult.Success -> {
                result.key.destroy() // The session already has the key.
                PassphraseCheck.Ok(result.proof)
            }
            is PassphraseResult.Wrong -> PassphraseCheck.Wrong(result.retryInMillis)
            is PassphraseResult.Throttled -> PassphraseCheck.Throttled(result.retryInMillis)
            is PassphraseResult.Rejected -> PassphraseCheck.Rejected(result.problem)
            is PassphraseResult.Unrecoverable -> PassphraseCheck.Failed
        }
    }

    /** False if the app locked meanwhile (nothing was changed). */
    suspend fun changePassphrase(proof: PassphraseProof, newPassphrase: CharArray): Boolean = op { m ->
        val key = live?.key ?: return@op false
        m.changePassphrase(proof, key, newPassphrase)
        true
    }

    suspend fun disableBiometric(proof: PassphraseProof) = op { m -> m.disableBiometric(proof) }

    /** Creates the biometric key and shows the prompt that authorises wrapping the data key. */
    suspend fun startBiometricEnrollment(proof: PassphraseProof): Boolean {
        val cipher = op { m ->
            if (live == null) return@op null
            try {
                enrollmentProof = proof
                m.prepareBiometricEnrollment(proof)
            } catch (e: Exception) {
                enrollmentProof = null
                throw e
            }
        } ?: return false
        launchPrompt(BiometricPurpose.ENROLL, cipher)
        return true
    }

    /** Accepts the offer made after an unlock: set fingerprint unlock up. */
    suspend fun acceptFingerprintOffer(): Boolean {
        _fingerprintOffer.value = null
        val proof = offerProof ?: return false
        offerProof = null
        return try {
            startBiometricEnrollment(proof)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _unlockUi.update { it.copy(notice = BiometricNotice.ENROLL_FAILED) }
            false
        }
    }

    suspend fun declineFingerprintOffer() {
        _fingerprintOffer.value = null
        offerProof = null
        try {
            op { m -> m.dismissFingerprintInvalidated() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Not written: the offer comes again after the next passphrase unlock.
        }
    }

    suspend fun setLockTimeout(seconds: Int) = op { m -> m.setLockTimeout(seconds) }

    private suspend fun <T> op(block: (LockManager) -> T): T = withContext(ioDispatcher) {
        withManager { m ->
            try {
                block(m)
            } finally {
                publishStatus(m)
            }
        }
    }

    // --- Reset and retry ----------------------------------------------------------------------

    /**
     * "Reset app data" (main thread), from the lock screen (the forgotten-passphrase path), the
     * error screens, or Settings while unlocked: deletes every Keystore key of the app, every
     * file it has stored and the lock configuration, then starts again, which leads to the
     * first-run set-up.
     *
     * An unlocked session ends here first, and the deletion only begins once it is closed, its
     * key destroyed, and every session before it has really stopped writing, so no late write
     * can put a file back. Unlike a lock, this waits for a write that hangs for as long as it
     * hangs. What could not be deleted is published in [resetFailure]; the start that follows
     * then finds what is left and says so.
     */
    fun reset() {
        if (_state.value == LockState.Starting) return
        // After a reset the app is as it was when first installed: nothing may be captured.
        screenshots.end()
        leaveUnlocked(SessionEnd.RESET)
        pendingPurpose = null
        _unlockUi.value = UnlockUi()
        _setupUi.value = SetupUi()
        _state.value = LockState.Starting
        work.launch {
            val report = try {
                withManager { m ->
                    closeRetired()
                    awaitSessionsStopped()
                    m.reset { DataReset.deleteAllData(files) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ResetReport(keysDeleted = false, dataDeleted = false, configDeleted = false)
            }
            withContext(mainDispatcher) {
                _resets.update { it + 1 }
                if (!report.complete) _resetFailure.value = report
                start()
            }
        }
    }

    fun resetFailureShown() {
        _resetFailure.value = null
    }

    /** Tries again after [LockState.Failed] or [LockState.Unrecoverable]. */
    fun retry() {
        if (_state.value is LockState.Failed || _state.value is LockState.Unrecoverable) start()
    }

    private fun onUnexpected(e: Throwable) {
        val detail = describe(e)
        _unlockUi.update { it.copy(busy = false, error = detail) }
        _setupUi.update { it.copy(busy = false, error = detail) }
        // The app is never left on its spinner: a start that died this way can be tried again.
        _state.compareAndSet(LockState.Starting, LockState.Failed(detail))
    }

    /** A technical detail for the error screens: the exception's name and, if it has one, its own message. */
    private fun describe(e: Throwable): String = e.message?.let { "${e.javaClass.simpleName}: $it" } ?: e.javaClass.simpleName

    private fun retryAt(retryInMillis: Long) = if (retryInMillis > 0) clock.elapsedMillis() + retryInMillis else 0L

    companion object {
        // androidx.biometric.BiometricPrompt error codes (kept here so this file needs no Android UI library).
        const val ERROR_HW_UNAVAILABLE = 1
        const val ERROR_SECURITY_UPDATE_REQUIRED = 15
        const val ERROR_HW_NOT_PRESENT = 12
        const val ERROR_LOCKOUT = 7
        const val ERROR_LOCKOUT_PERMANENT = 9
        const val ERROR_NO_BIOMETRICS = 11
        const val ERROR_NEGATIVE_BUTTON = 13
        const val ERROR_USER_CANCELED = 10
    }
}
