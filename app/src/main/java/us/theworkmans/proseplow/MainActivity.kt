package us.theworkmans.proseplow

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
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
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import us.theworkmans.proseplow.lock.FingerprintOfferDialog
import us.theworkmans.proseplow.lock.LockedContent
import us.theworkmans.proseplow.security.BiometricLauncher
import us.theworkmans.proseplow.security.BiometricNotice
import us.theworkmans.proseplow.security.BiometricPurpose
import us.theworkmans.proseplow.security.LockState
import us.theworkmans.proseplow.ui.ActivityState
import us.theworkmans.proseplow.ui.NoPersonalizedLearning
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
 */
class MainActivity : FragmentActivity() {

    private val container by lazy { (application as ProsePlowApp).container }
    private val activityState: ActivityState by viewModels {
        viewModelFactory { initializer { ActivityState(createSavedStateHandle()) } }
    }

    private lateinit var composeView: ComposeView
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var biometricLauncher: BiometricLauncher

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Secure from the first frame, before any content exists, and never cleared: nothing the
        // app shows may end up in a screenshot, a screen recording or the recents thumbnail. This
        // is not a setting. Compose dialogs, menus and popups inherit the flag from this window
        // (SecureFlagPolicy.Inherit, their default) when they open.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        // Nothing in this app is offered to, or saved by, an autofill service (passphrases,
        // prompts). Compose dialogs are separate windows; see ExcludeWindowFromAutofill.
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        val appLock = container.appLock
        appLock.onActivityCreated()

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
        setContentView(composeView)
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
                Toast.makeText(this@MainActivity, text, Toast.LENGTH_LONG).show()
                appLock.noticeShown()
            }
        }
        lifecycleScope.launch {
            var seen = appLock.resets.value
            appLock.resets.collect { count ->
                if (count != seen) activityState.clearAll()
                seen = count
            }
        }
    }

    private fun setAppContent() {
        composeView.setContent {
            ProsePlowTheme {
                // Around everything, always: no text field of the app is learned from by the keyboard.
                NoPersonalizedLearning { AppRoot() }
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
        container.appLock.onForeground()
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
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) container.appLock.onBackground()
    }

    override fun onDestroy() {
        container.appLock.detach(biometricLauncher)
        container.appLock.onActivityDestroyed(finished = isFinishing && !isChangingConfigurations)
        super.onDestroy()
    }

    private companion object {
        const val BIOMETRIC_FRAGMENT_TAG = "androidx.biometric.BiometricFragment"
    }
}
