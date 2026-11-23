package us.theworkmans.proseplow

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import us.theworkmans.proseplow.background.NotificationPermission
import us.theworkmans.proseplow.lock.FingerprintOfferDialog
import us.theworkmans.proseplow.lock.LockedContent
import us.theworkmans.proseplow.security.BiometricLauncher
import us.theworkmans.proseplow.security.BiometricNotice
import us.theworkmans.proseplow.security.BiometricPurpose
import us.theworkmans.proseplow.security.LockState
import us.theworkmans.proseplow.security.WindowSecurity
import us.theworkmans.proseplow.ui.ActivityState
import us.theworkmans.proseplow.ui.AppToasts
import us.theworkmans.proseplow.ui.DiscardedTouchNotice
import us.theworkmans.proseplow.ui.MarkCopiedTextSensitive
import us.theworkmans.proseplow.ui.NoPersonalizedLearning
import us.theworkmans.proseplow.ui.ObscuredTouchFilterLayout
import us.theworkmans.proseplow.ui.PlainSelectionMenu
import us.theworkmans.proseplow.ui.theme.ProsePlowTheme

/**
 * The only activity. A FragmentActivity because androidx.biometric's prompt needs one. While the
 * app is not unlocked only the lock UI is composed; when it locks, the whole composition is
 * disposed at once (not on the next frame, which Compose would only draw once the activity is
 * visible again), so nothing of the chats or settings stays referenced by the UI.
 *
 * Nothing secret goes into the saved instance state: passphrase fields are never saveable, and
 * whatever the unlocked screens save is filed under a key that every lock replaces (see
 * [ActivityState]).
 *
 * What protects the whole app is put around its content here, once: the window policy (secure
 * unless the unlocked app has a screenshot allowance), a root view that discards touches made
 * under another app's overlay, and, around the composition, the keyboard that does not learn,
 * the clipboard that marks every clip as sensitive, and the selection menu without other apps'
 * entries.
 */
class MainActivity : FragmentActivity() {

    private val container by lazy { (application as ProsePlowApp).container }
    private val activityState: ActivityState by viewModels {
        viewModelFactory { initializer { ActivityState(createSavedStateHandle()) } }
    }

    private lateinit var composeView: ComposeView
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var biometricLauncher: BiometricLauncher

    /** What the window was made when the policy was last applied. It starts secure. */
    private var windowSecure = true

    /** The open dialogs and menus are to be built again the next time the activity is shown. */
    private var renewWindowsWhenShown = false

    /** Set when this instance is being replaced by [renewWindows], not left by the user. */
    private var renewing = false

    /**
     * Asks for the permission to post notifications (Android 13 and later). Whatever the answer
     * is, nothing follows from it here: with the permission the app's notifications are shown
     * (that it is working out of sight, and that an answer is ready), without it they are not,
     * and the app is the same otherwise.
     */
    private val askForNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** When the user was last told that a touch was discarded ([SystemClock.uptimeMillis]); null before the first time. */
    private var touchNoticeAt: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Secure from the first frame, before any content exists and before anything is known:
        // nothing the app shows ends up in a screenshot or a screen recording unless the user has
        // allowed it for a while (see applyWindowPolicy), and the app switcher's preview is hidden
        // in any case.
        container.screenSecurity.apply(this, secure = true)
        // Nothing in this app is offered to, or saved by, an autofill service (passphrases,
        // prompts). Compose dialogs are separate windows; see ProtectWindow.
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        val appLock = container.appLock
        appLock.onActivityCreated(this)

        // Created in onCreate, as the androidx.biometric documentation asks, so a prompt that is
        // showing during a rotation reports to this instance.
        biometricPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    appLock.onBiometricSucceeded(result.cryptoObject?.cipher)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    appLock.onBiometricError(errorCode)
                }
            },
        )
        biometricLauncher = BiometricLauncher { cipher, purpose ->
            // BiometricPrompt.authenticate() does nothing (it only logs) once the state is saved;
            // report that, so the lock does not wait for a prompt that never appears.
            if (isFinishing || isDestroyed || supportFragmentManager.isStateSaved) return@BiometricLauncher false
            val unlock = purpose == BiometricPurpose.UNLOCK
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(if (unlock) getString(R.string.biometric_unlock_title, getString(R.string.app_name)) else getString(R.string.biometric_enroll_title))
                .apply { if (!unlock) setSubtitle(getString(R.string.biometric_enroll_subtitle)) }
                // The prompt's only other way out. With a negative button set, the prompt cannot
                // fall back to the device PIN, pattern or password.
                .setNegativeButtonText(getString(if (unlock) R.string.biometric_use_passphrase else R.string.cancel))
                // Strong (Class 3) biometrics only; never the device credential.
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setConfirmationRequired(false)
                .build()
            biometricPrompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
            true
        }
        appLock.attach(biometricLauncher)

        composeView = ComposeView(this)
        composeView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        // Everything the app shows is inside a frame that lets no touch through while another
        // app's window covers it, so an overlay cannot make the user press what they cannot see.
        val content = ObscuredTouchFilterLayout(this)
        content.onTouchDiscarded = ::tellThatATouchWasDiscarded
        content.addView(composeView)
        setContentView(content)
        setAppContent()

        lifecycleScope.launch {
            var wasUnlocked = appLock.state.value is LockState.Unlocked
            appLock.state.collect { state ->
                val unlocked = state is LockState.Unlocked
                if (wasUnlocked && !unlocked) {
                    composeView.disposeComposition()
                    setAppContent()
                }
                wasUnlocked = unlocked
            }
        }
        lifecycleScope.launch {
            appLock.unlockUi.collect { ui ->
                val notice = ui.notice ?: return@collect
                val text = when (notice) {
                    BiometricNotice.UNLOCK_FAILED -> R.string.biometric_unlock_failed
                    BiometricNotice.UNLOCK_UNAVAILABLE -> R.string.biometric_unlock_unavailable
                    BiometricNotice.LOCKED_OUT -> R.string.biometric_locked_out
                    BiometricNotice.ENROLLED -> R.string.biometric_enrolled
                    BiometricNotice.ENROLL_FAILED -> R.string.biometric_enroll_failed
                    BiometricNotice.REMOVED -> R.string.biometric_removed
                }
                AppToasts.show(this@MainActivity, getString(text), long = true)
                appLock.noticeShown()
            }
        }
        // The first time an answer is started, and never again: may the app say when one is ready?
        lifecycleScope.launch {
            appLock.state.collectLatest { state ->
                val session = (state as? LockState.Unlocked)?.session ?: return@collectLatest
                session.chat.state.map { it.turn != null }.distinctUntilChanged().collect { writing -> if (writing) askForNotificationsOnce(session) }
            }
        }
        lifecycleScope.launch {
            var seen = appLock.resets.value
            appLock.resets.collect { count ->
                if (count != seen) activityState.clearAll()
                seen = count
            }
        }
        // The window follows the lock and the screenshot allowance at once, both ways: it opens
        // when the unlocked app has an allowance, and closes when the app locks or the allowance
        // ends (by itself, by the screen turning off, by the user or by a reset).
        lifecycleScope.launch {
            appLock.state.collect { applyWindowPolicy() }
        }
        lifecycleScope.launch {
            appLock.screenshots.allowed.collect { applyWindowPolicy() }
        }
    }

    /**
     * Makes the window secure or not, as the policy says for this moment. The allowance is asked
     * through `current()`, which first ends one whose time is up, so a timer that could not run
     * while the phone slept never leaves the window open.
     *
     * A dialog or a menu takes the window's state when it opens and keeps it. So when the window
     * turns secure under the unlocked app, whatever is open is built again ([renewWindows]).
     * When the app locks, the composition is torn down in any case.
     */
    private fun applyWindowPolicy() {
        val appLock = container.appLock
        val unlocked = appLock.state.value is LockState.Unlocked
        val secure = WindowSecurity.mustBeSecure(unlocked, appLock.screenshots.current(), appLock.elapsedNow(), Build.VERSION.SDK_INT)
        val renew = WindowSecurity.mustRenewWindows(wasSecure = windowSecure, secure = secure, unlocked = unlocked)
        windowSecure = secure
        container.screenSecurity.apply(this, secure)
        if (!unlocked) renewWindowsWhenShown = false
        if (renew) renewWindows()
    }

    /**
     * Builds every dialog and menu of the unlocked app again, under the window as it is now, by
     * recreating the activity. That is the one way that loses nothing: the screens' ViewModels
     * (a draft, a message that is being edited) belong to the session and stay, and the saved
     * state brings back where the user was. A dialog that was open opens again, as a new
     * window; a menu is closed.
     *
     * While the activity can be seen (started, the screen on) this happens at once. Otherwise
     * (the allowance ended because the screen turned off, or while the app was in the
     * background) it waits until the activity is shown again ([renewWindowsIfDue]): nothing can
     * be captured of a window that is not shown, and the background lock keeps counting
     * undisturbed.
     */
    private fun renewWindows() {
        if (renewing) return
        val screenOn = getSystemService(PowerManager::class.java)?.isInteractive != false
        if (screenOn && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            renewing = true
            recreate()
        } else {
            renewWindowsWhenShown = true
        }
    }

    /** Called whenever the activity is about to be shown again: carries out what [renewWindows] put off. */
    private fun renewWindowsIfDue() {
        if (!renewWindowsWhenShown || renewing) return
        renewWindowsWhenShown = false
        renewing = true
        recreate()
    }

    /**
     * Asks once whether the app may post notifications, at the moment that makes sense of the
     * question: an answer has just been started. That it was asked is stored with the
     * (encrypted) settings before the question is shown, so it is never asked twice.
     */
    private fun askForNotificationsOnce(session: AppSession) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!NotificationPermission.shouldAsk(Build.VERSION.SDK_INT, granted, session.settings.settings.value.notificationsAsked)) return
        session.settings.update(urgent = true) { it.withNotificationsAsked() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) askForNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Says why nothing happens, when that is due; see [ObscuredTouchFilterLayout] and [DiscardedTouchNotice]. */
    private fun tellThatATouchWasDiscarded() {
        val now = SystemClock.uptimeMillis()
        if (!DiscardedTouchNotice.isDue(now, touchNoticeAt, AppToasts.lastShownAt)) return
        touchNoticeAt = now
        AppToasts.show(this, getString(R.string.touch_discarded, getString(R.string.app_name)), long = true)
    }

    private fun setAppContent() {
        composeView.setContent {
            ProsePlowTheme {
                // Around everything, always: no text field of the app is learned from by the
                // keyboard, no clip leaves the app without the sensitive mark, and no selection
                // menu offers to hand the selection to another app.
                NoPersonalizedLearning {
                    MarkCopiedTextSensitive {
                        PlainSelectionMenu { AppRoot() }
                    }
                }
            }
        }
    }

    @Composable
    private fun AppRoot() {
        val appLock = container.appLock
        val state by appLock.state.collectAsStateWithLifecycle()
        when (val current = state) {
            is LockState.Unlocked -> {
                val session = current.session
                val owner = remember(session) {
                    object : ViewModelStoreOwner {
                        override val viewModelStore: ViewModelStore = session.viewModelStore
                    }
                }
                // Saved screen state is keyed by the session: after a lock, an unlock or a reset,
                // nothing saved before matches, while a rotation keeps it.
                val generation by appLock.sessionGeneration.collectAsStateWithLifecycle()
                val contentKey = remember(generation) { activityState.contentKey(generation) }
                key(contentKey) {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                        AppNavigation(session = session, appLock = appLock)
                    }
                }
                val offer by appLock.fingerprintOffer.collectAsStateWithLifecycle()
                val scope = rememberCoroutineScope()
                offer?.let {
                    FingerprintOfferDialog(
                        offer = it,
                        onAccept = { scope.launch { appLock.acceptFingerprintOffer() } },
                        onDecline = { scope.launch { appLock.declineFingerprintOffer() } },
                    )
                }
            }
            else -> LockedContent(appLock, current)
        }
    }

    override fun onStart() {
        super.onStart()
        container.appLock.onForeground(this)
        // Whoever is looking at the app needs no notification that an answer is ready.
        container.answerWork.onInFront(true)
        // Before anything is drawn again: an allowance that ran out while the app was away is over.
        applyWindowPolicy()
        renewWindowsIfDue()
    }

    override fun onResume() {
        // androidx.biometric 1.1.0 shows its prompt through a fragment added under this tag
        // (BiometricPrompt.BIOMETRIC_FRAGMENT_TAG, checked in the resolved artifact) and removes
        // it in BiometricFragment.dismiss(). None present while the lock screen thinks a prompt
        // is showing means the prompt ended without a callback.
        if (supportFragmentManager.findFragmentByTag(BIOMETRIC_FRAGMENT_TAG) == null) container.appLock.onBiometricPromptAbsent()
        super.onResume()
        // The instance in front shows the fingerprint prompt (another one may have attached since).
        container.appLock.attach(biometricLauncher)
        // A screen that was only off for a moment may never have stopped the activity.
        renewWindowsIfDue()
    }

    override fun onStop() {
        super.onStop()
        // Not the background: a rotation, or this instance making way for the one that
        // renewWindows asked for, which comes to the front in its place.
        if (!isChangingConfigurations && !renewing) {
            container.answerWork.onInFront(false)
            // Starts the lock's timer and, within this call, the keep-alive that lets it run on
            // time (AppLock.lockPending): Android still allows a foreground service to be started
            // in the first seconds after the screen was left, and no later.
            container.appLock.onBackground(this)
        }
    }

    override fun onDestroy() {
        container.appLock.detach(biometricLauncher)
        container.appLock.onActivityDestroyed(finished = isFinishing && !isChangingConfigurations, who = this)
        super.onDestroy()
    }

    private companion object {
        const val BIOMETRIC_FRAGMENT_TAG = "androidx.biometric.BiometricFragment"
    }
}
