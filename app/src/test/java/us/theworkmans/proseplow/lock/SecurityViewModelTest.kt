package us.theworkmans.proseplow.lock

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import us.theworkmans.proseplow.security.AppFiles
import us.theworkmans.proseplow.security.AppLock
import us.theworkmans.proseplow.security.BiometricLauncher
import us.theworkmans.proseplow.security.BiometricStatus
import us.theworkmans.proseplow.security.FakeClock
import us.theworkmans.proseplow.security.FakeScreenOffWatch
import us.theworkmans.proseplow.security.LockConfigStore
import us.theworkmans.proseplow.security.LockManager
import us.theworkmans.proseplow.security.LockState
import us.theworkmans.proseplow.security.SessionClose
import us.theworkmans.proseplow.security.ScreenshotAllowance
import us.theworkmans.proseplow.security.ScreenshotsAllowed
import us.theworkmans.proseplow.security.SoftwareKeyVault
import us.theworkmans.proseplow.security.UnlockedSession
import javax.crypto.Cipher

/** The typed confirmation of a reset. */
class ResetConfirmationTest {
    @Test
    fun theWordMustBeTypedGiveOrTakeCapitalsAndBlanks() {
        for (typed in listOf("delete", "DELETE", "Delete", "  delete  ", "\tdeLeTe\n")) assertTrue(typed, ResetConfirmation.matches(typed, "delete"))
        for (typed in listOf("", " ", "delet", "deleted", "de lete", "delete!", "reset", "yes")) assertFalse(typed, ResetConfirmation.matches(typed, "delete"))
    }
}

/**
 * The Security section's ViewModel over a real lock (software keys, virtual time): what each
 * action leads to, and what the screen is told when it does not work.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SecurityViewModelTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val vault = SoftwareKeyVault()
    private val clock = FakeClock()
    private val files by lazy { AppFiles(temp.newFolder("files"), temp.newFolder("cache")) }
    private var biometric = BiometricStatus.NONE_ENROLLED
    private var promptCipher: Cipher? = null
    private val screenWatch = FakeScreenOffWatch()

    private val passphrase = "correct horse battery"
    private val otherPassphrase = "a different one, longer"

    private lateinit var lock: AppLock<UnlockedSession>
    private lateinit var viewModel: SecurityViewModel

    /** The session's setting of how long messages are kept, and every change made to it. */
    private val lifetimeSetting = object : MessageLifetimeSetting {
        override var messageLifetimeDays: Int = 7
        val changes = mutableListOf<Int>()

        override fun changeMessageLifetimeDays(days: Int) {
            messageLifetimeDays = days
            changes += days
        }
    }

    /** The session's switch for the model's memory, on a platform that keeps one unless a test says otherwise. */
    private val memorySetting = object : ModelMemorySetting {
        override var modelMemorySupported: Boolean = true
        override var keepModelMemory: Boolean = true
        val changes = mutableListOf<Boolean>()

        override fun changeKeepModelMemory(keep: Boolean) {
            keepModelMemory = keep && modelMemorySupported
            changes += keep
        }
    }

    @Before
    fun viewModelsRunOnTheTestDispatcher() = Dispatchers.setMain(dispatcher)

    @After
    fun restoreTheMainDispatcher() = Dispatchers.resetMain()

    /** A set-up, unlocked app with the Security section open. */
    private fun TestScope.unlockedApp() {
        lock = AppLock(
            manager = LockManager(LockConfigStore(files.lockConfig), vault, clock, iterations = 1_000, minIterations = 1, encryptedDataExists = files::encryptedDataExists),
            files = files,
            clock = clock,
            openSession = { _, _ ->
                object : UnlockedSession {
                    override fun clearScreens() = Unit

                    override suspend fun close(how: SessionClose) = Unit
                }
            },
            biometricStatus = { biometric },
            screenshots = ScreenshotAllowance(clock, screenWatch, dispatcher),
            mainDispatcher = dispatcher,
            ioDispatcher = dispatcher,
        )
        lock.attach(
            BiometricLauncher { cipher, _ ->
                promptCipher = cipher
                true
            },
        )
        runCurrent()
        lock.setUp(passphrase.toCharArray(), passphrase.toCharArray())
        runCurrent()
        assertTrue(lock.state.value is LockState.Unlocked)
        viewModel = SecurityViewModel(lock, lifetimeSetting, memorySetting)
    }

    private fun TestScope.confirmWith(text: String) {
        viewModel.submitCurrentPassphrase(text.toCharArray())
        runCurrent()
    }

    private fun TestScope.unlocksWith(text: String): Boolean {
        lock.lock()
        runCurrent()
        lock.unlockWithPassphrase(text.toCharArray())
        runCurrent()
        return lock.state.value is LockState.Unlocked
    }

    /** Catches a switch that does not reach the session, does not show what it stored, or asks for a passphrase. */
    @Test
    fun theSwitchForTheModelsMemoryChangesTheSessionsSettingAtOnce() = runTest(dispatcher) {
        unlockedApp()
        assertTrue(viewModel.modelMemorySupported)
        assertTrue("on by default", viewModel.keepModelMemory.value)

        viewModel.setKeepModelMemory(false)
        assertEquals(listOf(false), memorySetting.changes)
        assertFalse(viewModel.keepModelMemory.value)
        assertNull("no dialog, no passphrase", viewModel.state.value.dialog)
        viewModel.setKeepModelMemory(true)
        assertTrue(viewModel.keepModelMemory.value)
    }

    /** Below Android 12: catches a switch that can be turned on, or that reaches the session at all. */
    @Test
    fun whereTheMemoryCannotBeKeptTheSwitchIsOffAndDoesNothing() = runTest(dispatcher) {
        memorySetting.modelMemorySupported = false
        memorySetting.keepModelMemory = false
        unlockedApp()
        assertFalse(viewModel.modelMemorySupported)
        assertFalse(viewModel.keepModelMemory.value)

        viewModel.setKeepModelMemory(true)
        assertEquals(emptyList<Boolean>(), memorySetting.changes)
        assertFalse(viewModel.keepModelMemory.value)
    }

    @Test
    fun changingThePassphraseAsksForTheCurrentOneThenTheNewOneTwice() = runTest(dispatcher) {
        unlockedApp()
        viewModel.onChangePassphrase()
        assertEquals(SecurityUiState(dialog = SecurityDialog.Confirm(ProtectedAction.CHANGE)), viewModel.state.value)

        val wrong = "not the passphrase".toCharArray()
        viewModel.submitCurrentPassphrase(wrong)
        assertTrue(viewModel.state.value.busy)
        runCurrent()
        assertEquals(SecurityUiState(dialog = SecurityDialog.Confirm(ProtectedAction.CHANGE), error = SecurityError.WRONG), viewModel.state.value)
        assertTrue("what was typed is wiped", wrong.all { it == '\u0000' })

        confirmWith(passphrase)
        assertTrue(viewModel.state.value.dialog is SecurityDialog.ChangePassphrase)
        assertNull(viewModel.state.value.error)

        // The two entries differ, then break the rules: nothing changes, the dialog stays.
        viewModel.submitNewPassphrase(otherPassphrase.toCharArray(), passphrase.toCharArray())
        assertEquals(SecurityError.MISMATCH, viewModel.state.value.error)
        viewModel.submitNewPassphrase("1234567890".toCharArray(), "1234567890".toCharArray())
        assertEquals(SecurityError.DIGITS_ONLY, viewModel.state.value.error)
        viewModel.submitNewPassphrase("too short".toCharArray(), "too short".toCharArray())
        assertEquals(SecurityError.TOO_SHORT, viewModel.state.value.error)
        assertTrue(viewModel.state.value.dialog is SecurityDialog.ChangePassphrase)
        assertEquals(1, vault.bindingKeys.size)

        val first = otherPassphrase.toCharArray()
        val second = otherPassphrase.toCharArray()
        viewModel.submitNewPassphrase(first, second)
        assertTrue(viewModel.state.value.busy)
        viewModel.dismissDialog() // Not while the key is being secured.
        assertTrue(viewModel.state.value.dialog is SecurityDialog.ChangePassphrase)
        runCurrent()
        assertEquals(SecurityUiState(passphraseChanged = true), viewModel.state.value)
        assertTrue(first.all { it == '\u0000' } && second.all { it == '\u0000' })

        assertFalse(unlocksWith(passphrase))
        lock.unlockWithPassphrase(otherPassphrase.toCharArray())
        runCurrent()
        assertTrue(lock.state.value is LockState.Unlocked)
    }

    @Test
    fun tooManyWrongPassphrasesAreThrottledHereToo() = runTest(dispatcher) {
        unlockedApp()
        viewModel.onChangePassphrase()
        assertEquals("no wait to begin with", 0L, viewModel.unlockUi.value.retryAtElapsed)
        repeat(4) {
            confirmWith("not the passphrase")
            assertEquals(SecurityError.WRONG, viewModel.state.value.error)
            assertEquals("the first four cost nothing", 0L, viewModel.unlockUi.value.retryAtElapsed)
        }
        confirmWith("not the passphrase")
        assertEquals(SecurityError.WRONG, viewModel.state.value.error)
        // The fifth starts a wait, which the dialog counts down from the lock's own value, the
        // one the lock screen counts down.
        assertEquals(clock.elapsed + 30_000, viewModel.unlockUi.value.retryAtElapsed)
        assertEquals(viewModel.elapsedNow() + 30_000, lock.unlockUi.value.retryAtElapsed)

        confirmWith(passphrase)
        assertEquals("the countdown says it; nothing else is said", null, viewModel.state.value.error)
        assertEquals(clock.elapsed + 30_000, viewModel.unlockUi.value.retryAtElapsed)
        assertTrue(viewModel.state.value.dialog is SecurityDialog.Confirm)

        // An empty entry is not looked at: nothing is checked, counted or said.
        viewModel.submitCurrentPassphrase(CharArray(0))
        assertFalse(viewModel.state.value.busy)
        runCurrent()
        assertEquals(null, viewModel.state.value.error)

        // And the lock screen, once the app locks, shows the same wait.
        lock.lock()
        runCurrent()
        assertEquals(clock.elapsed + 30_000, lock.unlockUi.value.retryAtElapsed)
    }

    @Test
    fun aNewPassphraseEnteredTooLateIsRefused() = runTest(dispatcher) {
        unlockedApp()
        viewModel.onChangePassphrase()
        confirmWith(passphrase)
        clock.advance(LockManager.PROOF_VALIDITY_MILLIS + 1)
        viewModel.submitNewPassphrase(otherPassphrase.toCharArray(), otherPassphrase.toCharArray())
        runCurrent()
        assertEquals(SecurityUiState(error = SecurityError.EXPIRED), viewModel.state.value)
        assertTrue("the old passphrase still is the passphrase", unlocksWith(passphrase))
    }

    @Test
    fun fingerprintUnlockIsTurnedOnAndOffWithThePassphrase() = runTest(dispatcher) {
        biometric = BiometricStatus.AVAILABLE
        unlockedApp()
        lock.declineFingerprintOffer()
        assertTrue(viewModel.biometricAvailable())
        assertFalse(viewModel.status.value.biometricEnabled)

        viewModel.onFingerprintSwitch(true)
        assertEquals(SecurityDialog.Confirm(ProtectedAction.FINGERPRINT_ON), viewModel.state.value.dialog)
        confirmWith(passphrase)
        assertEquals("the dialog gives way to the system's prompt", SecurityUiState(), viewModel.state.value)
        lock.onBiometricSucceeded(promptCipher)
        runCurrent()
        assertTrue(viewModel.status.value.biometricEnabled)

        viewModel.onFingerprintSwitch(false)
        assertEquals(SecurityDialog.Confirm(ProtectedAction.FINGERPRINT_OFF), viewModel.state.value.dialog)
        viewModel.dismissDialog()
        assertTrue("dismissing changes nothing", viewModel.status.value.biometricEnabled)
        viewModel.onFingerprintSwitch(false)
        confirmWith(passphrase)
        assertFalse(viewModel.status.value.biometricEnabled)
        assertTrue(vault.biometricKeys.isEmpty())
    }

    @Test
    fun theLockTimeoutIsSavedAndAFailureToSaveIsShown() = runTest(dispatcher) {
        unlockedApp()
        viewModel.setLockTimeout(300)
        runCurrent()
        assertEquals(300, viewModel.status.value.lockTimeoutSeconds)
        assertNull(viewModel.state.value.error)

        files.securityDir.setWritable(false)
        try {
            viewModel.setLockTimeout(0)
            runCurrent()
        } finally {
            files.securityDir.setWritable(true)
        }
        assertEquals(SecurityError.FAILED, viewModel.state.value.error)
        assertEquals("the stored value is what is shown", 300, viewModel.status.value.lockTimeoutSeconds)

        viewModel.setLockTimeout(900)
        runCurrent()
        assertEquals(900, viewModel.status.value.lockTimeoutSeconds)
        assertNull(viewModel.state.value.error)
    }

    /**
     * "Fingerprint works for" is built as "Lock after" is: no passphrase, stored at once,
     * reflected in the status, and a failure to store it shown with the stored value kept.
     * Catches a choice that is not stored or not shown, one that asks for a dialog, and a
     * failure that shows a value that was not stored.
     */
    @Test
    fun theFingerprintPeriodIsSavedWithoutAPassphraseAndAFailureToSaveIsShown() = runTest(dispatcher) {
        unlockedApp()
        assertEquals("an hour by default", 3_600, viewModel.status.value.fingerprintPeriodSeconds)
        viewModel.setFingerprintPeriod(8 * 3_600)
        runCurrent()
        assertNull("no passphrase asked for", viewModel.state.value.dialog)
        assertEquals(8 * 3_600, viewModel.status.value.fingerprintPeriodSeconds)
        assertEquals(8 * 3_600, LockConfigStore(files.lockConfig).read()!!.fingerprintPeriodSeconds)
        assertNull(viewModel.state.value.error)

        files.securityDir.setWritable(false)
        try {
            viewModel.setFingerprintPeriod(15 * 60)
            runCurrent()
        } finally {
            files.securityDir.setWritable(true)
        }
        assertEquals(SecurityError.FAILED, viewModel.state.value.error)
        assertEquals("the stored value is what is shown", 8 * 3_600, viewModel.status.value.fingerprintPeriodSeconds)

        viewModel.setFingerprintPeriod(24 * 3_600)
        runCurrent()
        assertEquals(24 * 3_600, viewModel.status.value.fingerprintPeriodSeconds)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun theScreenshotSwitchGivesAnAllowanceThatEndsByItselfAndTheRowFollows() = runTest(dispatcher) {
        unlockedApp()
        val before = files.lockConfig.readText()
        assertNull("blocked unless allowed", viewModel.screenshots.value)

        viewModel.setScreenshotsAllowed(true)
        assertEquals(
            ScreenshotsAllowed(
                untilElapsedMillis = clock.elapsed + ScreenshotAllowance.DURATION_MILLIS,
                untilWallMillis = clock.wall + ScreenshotAllowance.DURATION_MILLIS,
            ),
            viewModel.screenshots.value,
        )
        assertNull(viewModel.state.value.error)
        assertEquals("no dialog, no passphrase", SecurityUiState(), viewModel.state.value)

        // The time is up while the settings are open: what the row shows turns off without a touch.
        clock.advance(ScreenshotAllowance.DURATION_MILLIS)
        testScheduler.advanceTimeBy(ScreenshotAllowance.DURATION_MILLIS)
        runCurrent()
        assertNull(viewModel.screenshots.value)

        viewModel.setScreenshotsAllowed(true)
        viewModel.setScreenshotsAllowed(false)
        assertNull("off is at once", viewModel.screenshots.value)
        assertFalse(screenWatch.watching)
        assertEquals("the switch is no stored setting: the lock configuration never changed", before, files.lockConfig.readText())
    }

    @Test
    fun anAllowanceThatCannotBeGuardedIsNotGivenAndTheSectionSaysSo() = runTest(dispatcher) {
        unlockedApp()
        screenWatch.refuses = true
        viewModel.setScreenshotsAllowed(true)
        assertNull(viewModel.screenshots.value)
        assertEquals(SecurityError.SCREENSHOTS_NOT_ALLOWED, viewModel.state.value.error)

        screenWatch.refuses = false
        viewModel.setScreenshotsAllowed(true)
        assertTrue(viewModel.screenshots.value != null)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun lockNowLocks() = runTest(dispatcher) {
        unlockedApp()
        viewModel.lockNow()
        assertTrue(lock.state.value is LockState.Locked)
    }

    @Test
    fun resetGoesThroughTheTypedConfirmationAndThenTheLockDoesIt() = runTest(dispatcher) {
        unlockedApp()
        viewModel.confirmReset() // Not without the dialog having been opened.
        assertTrue(lock.state.value is LockState.Unlocked)

        viewModel.onReset()
        assertEquals(SecurityUiState(dialog = SecurityDialog.Reset), viewModel.state.value)
        viewModel.dismissDialog()
        assertTrue(lock.state.value is LockState.Unlocked)
        assertTrue(files.lockConfig.exists())

        viewModel.onReset()
        viewModel.confirmReset()
        assertEquals("the screens are gone at once", LockState.Starting, lock.state.value)
        runCurrent()
        assertEquals(LockState.SetupRequired, lock.state.value)
        assertFalse(files.lockConfig.exists())
        assertTrue(vault.aliases.isEmpty())
    }

    /**
     * "Delete messages after": a longer limit applies at once; a shorter one, or any limit after
     * Never, asks first and changes nothing until confirmed. Both the setting and the readable
     * copy in the lock configuration follow; a copy that cannot be written changes neither.
     * Catches a shortening without confirmation, a confirmation that does not apply, and a
     * picker that shows a value that was not stored.
     */
    @Test
    fun aLimitThatDeletesNowIsConfirmedFirstAndOneThatDoesNotIsAppliedAtOnce() = runTest(dispatcher) {
        unlockedApp()
        fun copy() = LockConfigStore(files.lockConfig).read()!!.messageLifetimeDays
        assertEquals(7, viewModel.messageLifetimeDays.value)

        viewModel.selectMessageLifetime(30)
        runCurrent()
        assertNull("a longer limit deletes nothing now and asks nothing", viewModel.state.value.dialog)
        assertEquals(30, viewModel.messageLifetimeDays.value)
        assertEquals(listOf(30), lifetimeSetting.changes)
        assertEquals(30, copy())

        viewModel.selectMessageLifetime(1)
        runCurrent()
        assertEquals(SecurityDialog.ShortenMessageLifetime(1), viewModel.state.value.dialog)
        assertEquals("nothing changes before it is confirmed", 30, viewModel.messageLifetimeDays.value)
        viewModel.dismissDialog()
        runCurrent()
        assertEquals(listOf(30), lifetimeSetting.changes)
        assertEquals(30, copy())

        viewModel.selectMessageLifetime(1)
        viewModel.confirmMessageLifetime()
        runCurrent()
        assertNull(viewModel.state.value.dialog)
        assertEquals(1, viewModel.messageLifetimeDays.value)
        assertEquals(listOf(30, 1), lifetimeSetting.changes)
        assertEquals(1, copy())

        viewModel.selectMessageLifetime(0)
        runCurrent()
        assertNull("Never deletes nothing", viewModel.state.value.dialog)
        assertEquals(0, copy())
        viewModel.selectMessageLifetime(90)
        runCurrent()
        assertEquals("any limit after Never deletes now", SecurityDialog.ShortenMessageLifetime(90), viewModel.state.value.dialog)
        viewModel.selectMessageLifetime(2)
        assertEquals("not a choice", SecurityDialog.ShortenMessageLifetime(90), viewModel.state.value.dialog)

        val changes = lifetimeSetting.changes.size
        files.securityDir.setWritable(false)
        try {
            viewModel.confirmMessageLifetime()
            runCurrent()
        } finally {
            files.securityDir.setWritable(true)
        }
        assertEquals(SecurityError.FAILED, viewModel.state.value.error)
        assertEquals(0, viewModel.messageLifetimeDays.value)
        assertEquals("the setting is not changed either", changes, lifetimeSetting.changes.size)
        assertEquals(0, copy())
    }

    /** "Background maintenance" needs no passphrase, is stored in the lock configuration, and a failure leaves it as it was. */
    @Test
    fun theBackgroundMaintenanceSwitchIsStoredWithoutAPassphrase() = runTest(dispatcher) {
        unlockedApp()
        assertTrue(viewModel.status.value.backgroundMaintenance)
        viewModel.setBackgroundMaintenance(false)
        runCurrent()
        assertNull(viewModel.state.value.dialog)
        assertFalse(viewModel.status.value.backgroundMaintenance)
        assertFalse(LockConfigStore(files.lockConfig).read()!!.backgroundMaintenance)

        files.securityDir.setWritable(false)
        try {
            viewModel.setBackgroundMaintenance(true)
            runCurrent()
        } finally {
            files.securityDir.setWritable(true)
        }
        assertEquals(SecurityError.FAILED, viewModel.state.value.error)
        assertFalse(viewModel.status.value.backgroundMaintenance)
    }
}
