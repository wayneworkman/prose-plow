package us.theworkmans.proseplow.security

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import javax.crypto.Cipher

/**
 * The lock as the app sees it, on virtual time: one test dispatcher stands for both the main
 * thread and the I/O threads, a fake clock for the time since boot, software keys for the
 * Keystore and a fake session for the stores.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLockTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val vault = SoftwareKeyVault()
    private val clock = FakeClock()
    private val files by lazy { AppFiles(temp.newFolder("files"), temp.newFolder("cache")) }

    private val passphrase = "correct horse battery"
    private val otherPassphrase = "a different one, longer"
    private val wrongPassphrase = "definitely not it at all"
    private val plaintext = "a chat".toByteArray()

    /** Everything the sessions and the key store did, in order. */
    private val log = mutableListOf<String>()
    private val sessions = mutableListOf<FakeSession>()
    private var biometric = BiometricStatus.NONE_ENROLLED
    private var openFails: Exception? = null
    private val screenWatch = FakeScreenOffWatch()

    private lateinit var lock: AppLock<FakeSession>

    /** Uses of the lock manager's collaborators while nothing held the lock's mutex. */
    private val unserialised = mutableListOf<String>()

    private fun insideTheManager() {
        if (!lock.managerInUse) unserialised += Throwable().stackTrace.drop(1).take(8).joinToString("\n")
    }

    @After
    fun everyUseOfTheLockManagerWasSerialised() {
        assertEquals("the lock manager was used outside the mutex", emptyList<String>(), unserialised)
    }

    private class FakeSession(val cipher: BlobCipher, private val log: MutableList<String>) : UnlockedSession {
        var screensCleared = 0
        var closeStarted = false
        var closed = false

        /** A write that is queued when the session ends; [close] waits until the test completes it. */
        var pendingWrite: CompletableDeferred<Unit>? = null

        /** What that write does once it gets to run (it still needs the cipher). */
        var lateWrite: () -> Unit = {}

        override fun clearScreens() {
            screensCleared++
            log += "screens cleared"
        }

        override suspend fun close() {
            closeStarted = true
            pendingWrite?.await()
            lateWrite()
            closed = true
            log += "session closed"
        }
    }

    private class FakeLauncher : BiometricLauncher {
        var canShow = true
        var cipher: Cipher? = null
        var purpose: BiometricPurpose? = null
        var shown = 0

        override fun authenticate(cipher: Cipher, purpose: BiometricPurpose): Boolean {
            if (!canShow) return false
            this.cipher = cipher
            this.purpose = purpose
            shown++
            return true
        }
    }

    /** A new "process" over the same files and keys. */
    private fun TestScope.startApp(): AppLock<FakeSession> {
        vault.onUse = ::insideTheManager
        val managerClock = object : Clock {
            override fun wallMillis(): Long {
                insideTheManager()
                return clock.wall
            }

            override fun elapsedMillis(): Long {
                insideTheManager()
                return clock.elapsed
            }
        }
        val manager = LockManager(
            store = LockConfigStore(files.lockConfig, onWriteStep = { insideTheManager() }),
            vault = vault,
            clock = managerClock,
            iterations = 1_000,
            minIterations = 1,
            encryptedDataExists = {
                insideTheManager()
                files.encryptedDataExists()
            },
        )
        lock = AppLock(
            manager = manager,
            files = files,
            clock = clock,
            openSession = { cipher ->
                openFails?.let { throw it }
                FakeSession(cipher, log).also { sessions += it }
            },
            biometricStatus = { biometric },
            screenshots = ScreenshotAllowance(clock, screenWatch, dispatcher),
            mainDispatcher = dispatcher,
            ioDispatcher = dispatcher,
        )
        assertEquals("nothing is known before the configuration was read", LockState.Starting, lock.state.value)
        runCurrent()
        return lock
    }

    private fun TestScope.createPassphrase(text: String = passphrase): FakeSession {
        lock.setUp(text.toCharArray(), text.toCharArray())
        runCurrent()
        return session()
    }

    private fun TestScope.enter(text: String) {
        lock.unlockWithPassphrase(text.toCharArray())
        runCurrent()
    }

    private fun session(): FakeSession = (lock.state.value as LockState.Unlocked).session

    /** Real time passes: on the clock and for the timers alike. */
    private fun TestScope.pass(millis: Long) {
        clock.advance(millis)
        advanceTimeBy(millis)
        runCurrent()
    }

    private fun assertDestroyed(cipher: BlobCipher) {
        assertThrows(KeyDestroyedException::class.java) { cipher.seal("p", plaintext) }
    }

    private fun nothingIsPersisted() {
        assertFalse(files.lockConfig.exists())
        assertTrue("no key: ${vault.aliases.keys}", vault.aliases.isEmpty())
    }

    /** As assertThrows, for a block that suspends. */
    private inline fun <reified T : Throwable> assertFailsWith(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return
            throw e
        }
        fail("Expected ${T::class.java.simpleName}")
    }

    // --- First run --------------------------------------------------------------------------------

    @Test
    fun aFirstRunAsksForAPassphraseAndNothingExistsUntilOneIsCreated() = runTest(dispatcher) {
        startApp()
        assertEquals(LockState.SetupRequired, lock.state.value)
        nothingIsPersisted()

        // Two entries that differ, then a PIN, then something short: each is refused before anything is made.
        val first = passphrase.toCharArray()
        val second = otherPassphrase.toCharArray()
        lock.setUp(first, second)
        assertEquals(SetupUi(problem = PassphraseProblem.MISMATCH), lock.setupUi.value)
        assertTrue("both entries are wiped", first.all { it == '\u0000' } && second.all { it == '\u0000' })
        lock.setUp("1234567890".toCharArray(), "1234567890".toCharArray())
        assertEquals(PassphraseProblem.DIGITS_ONLY, lock.setupUi.value.problem)
        lock.setUp("too short".toCharArray(), "too short".toCharArray())
        assertEquals(PassphraseProblem.TOO_SHORT, lock.setupUi.value.problem)
        runCurrent()
        assertEquals(LockState.SetupRequired, lock.state.value)
        nothingIsPersisted()
        assertTrue(sessions.isEmpty())

        val a = passphrase.toCharArray()
        val b = passphrase.toCharArray()
        lock.setUp(a, b)
        assertEquals("the key is derived off the main thread", SetupUi(busy = true), lock.setupUi.value)
        runCurrent()
        val session = session()
        assertTrue(a.all { it == '\u0000' } && b.all { it == '\u0000' })
        assertEquals(SetupUi(), lock.setupUi.value)
        assertTrue(files.lockConfig.exists())
        assertEquals(1, vault.bindingKeys.size)
        assertArrayEquals(plaintext, session.cipher.open("p", session.cipher.seal("p", plaintext)))
        assertNull("no fingerprint is enrolled, so none is offered", lock.fingerprintOffer.value)
    }

    @Test
    fun aSecondSetUpWhileTheFirstIsRunningIsIgnored() = runTest(dispatcher) {
        startApp()
        lock.setUp(passphrase.toCharArray(), passphrase.toCharArray())
        lock.setUp(otherPassphrase.toCharArray(), otherPassphrase.toCharArray())
        runCurrent()
        session()
        lock.lock()
        runCurrent()
        enter(otherPassphrase)
        assertTrue(lock.unlockUi.value.wrongPassphrase)
        enter(passphrase)
        assertEquals(2, sessions.size)
    }

    @Test
    fun aSetUpThatFailsIsReportedOnItsScreenAndCanBeRepeated() = runTest(dispatcher) {
        startApp()
        files.filesDir.setWritable(false) // The security directory cannot be made.
        try {
            lock.setUp(passphrase.toCharArray(), passphrase.toCharArray())
            runCurrent()
        } finally {
            files.filesDir.setWritable(true)
        }
        assertEquals(LockState.SetupRequired, lock.state.value)
        assertFalse(lock.setupUi.value.busy)
        assertTrue(lock.setupUi.value.error, lock.setupUi.value.error!!.startsWith("IOException"))
        nothingIsPersisted()
        createPassphrase()
    }

    @Test
    fun aColdStartOfASetUpAppIsLockedAndOnlyThePassphraseOpensIt() = runTest(dispatcher) {
        startApp()
        val first = createPassphrase()
        val blob = first.cipher.seal("p", plaintext)

        startApp()
        assertEquals(LockState.Locked(1), lock.state.value)
        enter(wrongPassphrase)
        assertEquals(LockState.Locked(1), lock.state.value)
        assertTrue(lock.unlockUi.value.wrongPassphrase)
        assertFalse(lock.unlockUi.value.busy)

        lock.unlockWithPassphrase(CharArray(0))
        runCurrent()
        assertEquals(PassphraseProblem.EMPTY, lock.unlockUi.value.rejected)

        val typed = passphrase.toCharArray()
        lock.unlockWithPassphrase(typed)
        assertTrue(lock.unlockUi.value.busy)
        runCurrent()
        assertTrue(typed.all { it == '\u0000' })
        assertEquals(UnlockUi(), lock.unlockUi.value)
        assertArrayEquals("the same data key as before the restart", plaintext, session().cipher.open("p", blob))
    }

    // --- Locking ----------------------------------------------------------------------------------

    @Test
    fun lockNowLocksAtOnceAndTheKeyGoesOnceTheSessionIsClosed() = runTest(dispatcher) {
        startApp()
        val session = createPassphrase()
        val generation = lock.sessionGeneration.value

        lock.lock()
        // Before anything else gets to run: the screens are gone already.
        assertEquals(LockState.Locked(1), lock.state.value)
        assertEquals(1, session.screensCleared)
        assertNotEquals(generation, lock.sessionGeneration.value)
        assertFalse(session.closeStarted)

        runCurrent()
        assertTrue(session.closed)
        assertDestroyed(session.cipher)
        assertEquals(listOf("screens cleared", "session closed"), log)

        lock.lock() // Nothing to lock.
        runCurrent()
        assertEquals(LockState.Locked(1), lock.state.value)
    }

    @Test
    fun aLockWaitsForAPendingWriteBeforeTheKeyIsDestroyed() = runTest(dispatcher) {
        startApp()
        val session = createPassphrase()
        val write = CompletableDeferred<Unit>()
        var written: ByteArray? = null
        session.pendingWrite = write
        session.lateWrite = { written = session.cipher.seal("p", plaintext) }

        lock.lock()
        runCurrent()
        assertEquals("locked for the user at once", LockState.Locked(1), lock.state.value)
        assertTrue(session.closeStarted)
        assertFalse(session.closed)
        assertNull(written)
        // The key is still there for the write that is queued.
        assertArrayEquals(plaintext, session.cipher.open("p", session.cipher.seal("p", plaintext)))

        // Unlocking again meanwhile does not open a second session beside the one still closing.
        lock.unlockWithPassphrase(passphrase.toCharArray())
        runCurrent()
        assertEquals(LockState.Locked(1), lock.state.value)
        assertTrue(lock.unlockUi.value.busy)
        assertEquals(1, sessions.size)

        write.complete(Unit)
        runCurrent()
        assertTrue(session.closed)
        assertDestroyed(session.cipher)
        val next = session()
        assertEquals(2, sessions.size)
        assertFalse(next === session)
        assertArrayEquals("the queued write was sealed with the real key before it was destroyed", plaintext, next.cipher.open("p", written!!))
    }

    @Test
    fun aSessionThatFailsToCloseStillLosesItsKey() = runTest(dispatcher) {
        startApp()
        val session = createPassphrase()
        session.lateWrite = { throw IOException("disk full") }
        lock.lock()
        runCurrent()
        assertEquals(LockState.Locked(1), lock.state.value)
        assertDestroyed(session.cipher)
        enter(passphrase)
        assertEquals(2, sessions.size)
    }

    @Test
    fun theAppLocksAfterTheTimeoutInTheBackground() = runTest(dispatcher) {
        startApp()
        val session = createPassphrase()
        assertEquals("one minute unless changed", 60, lock.status.value.lockTimeoutSeconds)

        // Away for less than the timeout: nothing happens, and the timer does not fire later either.
        lock.onBackground()
        pass(59_999)
        lock.onForeground()
        pass(120_000)
        assertSame(session, session())

        lock.onBackground()
        pass(59_999)
        assertSame(session, session())
        pass(1)
        assertEquals(LockState.Locked(1), lock.state.value)
        assertTrue(session.closed)
        assertDestroyed(session.cipher)

        // Coming back afterwards changes nothing.
        lock.onForeground()
        runCurrent()
        assertEquals(LockState.Locked(1), lock.state.value)
    }

    @Test
    fun everyTimeoutChoiceIsHonoured() = runTest(dispatcher) {
        startApp()
        createPassphrase()
        for (seconds in listOf(300, 900, 60)) {
            lock.setLockTimeout(seconds)
            assertEquals(seconds, lock.status.value.lockTimeoutSeconds)
            lock.onBackground()
            pass(seconds * 1000L - 1)
            assertTrue("$seconds s: still unlocked a millisecond before", lock.state.value is LockState.Unlocked)
            pass(1)
            assertTrue("$seconds s", lock.state.value is LockState.Locked)
            lock.onForeground()
            enter(passphrase)
        }
        assertFailsWith<IllegalArgumentException> { lock.setLockTimeout(42) }
        assertEquals("a refused value changes nothing", 60, lock.status.value.lockTimeoutSeconds)
    }

    @Test
    fun immediatelyLocksTheMomentTheAppLeaves() = runTest(dispatcher) {
        startApp()
        val session = createPassphrase()
        lock.setLockTimeout(0)
        lock.onBackground()
        assertEquals("without waiting for anything", LockState.Locked(1), lock.state.value)
        runCurrent()
        assertDestroyed(session.cipher)
    }

    @Test
    fun aTimerThatCouldNotRunIsMadeUpForBeforeAnythingIsShown() = runTest(dispatcher) {
        startApp()
        val session = createPassphrase()
        lock.onBackground()
        // The process is frozen: the clock moves on, the timer does not.
        clock.advance(60_000)
        assertSame(session, session())
        lock.onForeground()
        assertEquals(LockState.Locked(1), lock.state.value)
        runCurrent()
        assertDestroyed(session.cipher)
    }

    @Test
    fun aConfigurationChangeIsNotTheBackground() = runTest(dispatcher) {
        startApp()
        val session = createPassphrase()
        // A rotation: the activity is destroyed and recreated without onBackground being called.
        lock.onActivityCreated()
        lock.onActivityCreated()
        lock.onActivityDestroyed(finished = false)
        pass(3_600_000)
        assertSame(session, session())
        assertEquals("the screens' state is kept across it", 0, session.screensCleared)
    }

    @Test
    fun anUnlockThatFinishesAfterTheAppLeftStartsItsTimerFromWhenItLeft() = runTest(dispatcher) {
        startApp()
        createPassphrase()
        lock.lock()
        runCurrent()

        lock.unlockWithPassphrase(passphrase.toCharArray())
        lock.onBackground() // The user leaves while the key is still being derived.
        clock.advance(20_000)
        runCurrent()
        val session = session()
        pass(39_999)
        assertSame(session, session())
        pass(1)
        assertTrue(lock.state.value is LockState.Locked)
        assertDestroyed(session.cipher)
    }

    @Test
    fun withImmediatelyAnUnlockThatFinishesAfterTheAppLeftNeverShows() = runTest(dispatcher) {
        startApp()
        createPassphrase()
        lock.setLockTimeout(0)
        lock.lock()
        runCurrent()

        lock.unlockWithPassphrase(passphrase.toCharArray())
        lock.onBackground()
        runCurrent()
        assertTrue("${lock.state.value}", lock.state.value is LockState.Locked)
        assertEquals(2, sessions.size)
        assertTrue(sessions.last().closed)
        assertDestroyed(sessions.last().cipher)
    }

    @Test
    fun wrongPassphrasesAreThrottledAndTheLockScreenKnowsUntilWhen() = runTest(dispatcher) {
        startApp()
        createPassphrase()
        lock.lock()
        runCurrent()

        repeat(4) {
            enter(wrongPassphrase)
            assertEquals(0L, lock.unlockUi.value.retryAtElapsed)
        }
        enter(wrongPassphrase)
        assertEquals(lock.elapsedNow() + 30_000, lock.unlockUi.value.retryAtElapsed)

        // The right passphrase is refused during the wait, unchecked.
        pass(29_999)
        enter(passphrase)
        assertTrue(lock.state.value is LockState.Locked)
        assertFalse(lock.unlockUi.value.wrongPassphrase)
        assertEquals(lock.elapsedNow() + 1, lock.unlockUi.value.retryAtElapsed)
        pass(1)
        enter(passphrase)
        assertTrue(lock.state.value is LockState.Unlocked)

        // A restart in the middle of a wait shows the wait again.
        lock.lock()
        runCurrent()
        repeat(5) { enter(wrongPassphrase) }
        pass(10_000)
        startApp()
        assertEquals(lock.elapsedNow() + 20_000, lock.unlockUi.value.retryAtElapsed)
    }

    @Test
    fun wrongPassphrasesEnteredInSettingsCountOnTheLockScreen() = runTest(dispatcher) {
        startApp()
        createPassphrase()
        repeat(4) { assertEquals(PassphraseCheck.Wrong(0), lock.checkPassphrase(wrongPassphrase.toCharArray())) }
        assertEquals(PassphraseCheck.Wrong(30_000), lock.checkPassphrase(wrongPassphrase.toCharArray()))
        assertEquals(PassphraseCheck.Throttled(30_000), lock.checkPassphrase(passphrase.toCharArray()))
        assertEquals(PassphraseCheck.Rejected(PassphraseProblem.EMPTY), lock.checkPassphrase(CharArray(0)))

        lock.lock()
        runCurrent()
        assertEquals(lock.elapsedNow() + 30_000, lock.unlockUi.value.retryAtElapsed)
    }

    // --- Settings while unlocked --------------------------------------------------------------------

    @Test
    fun thePassphraseCanBeChangedWithAFreshCheckOfTheCurrentOne() = runTest(dispatcher) {
        startApp()
        val session = createPassphrase()
        val blob = session.cipher.seal("p", plaintext)

        val proof = (lock.checkPassphrase(passphrase.toCharArray()) as PassphraseCheck.Ok).proof
        assertTrue(lock.changePassphrase(proof, otherPassphrase.toCharArray()))
        assertSame("the session goes on", session, session())
        assertEquals(1, vault.bindingKeys.size)

        lock.lock()
        runCurrent()
        enter(passphrase)
        assertTrue(lock.unlockUi.value.wrongPassphrase)
        enter(otherPassphrase)
        assertArrayEquals("the data key is the same, only its wrap changed", plaintext, session().cipher.open("p", blob))
    }

    @Test
    fun aChangeAfterTheAppLockedIsRefused() = runTest(dispatcher) {
        startApp()
        createPassphrase()
        val proof = (lock.checkPassphrase(passphrase.toCharArray()) as PassphraseCheck.Ok).proof
        val before = files.lockConfig.readText()
        lock.lock()
        assertFalse(lock.changePassphrase(proof, otherPassphrase.toCharArray()))
        assertFalse(lock.startBiometricEnrollment(proof))
        runCurrent()
        assertEquals(before, files.lockConfig.readText())
        enter(passphrase)
        assertTrue(lock.state.value is LockState.Unlocked)
    }

    @Test
    fun anExpiredCheckDoesNotAllowAChange() = runTest(dispatcher) {
        startApp()
        createPassphrase()
        val proof = (lock.checkPassphrase(passphrase.toCharArray()) as PassphraseCheck.Ok).proof
        clock.advance(LockManager.PROOF_VALIDITY_MILLIS + 1)
        assertFailsWith<ProofExpiredException> { lock.changePassphrase(proof, otherPassphrase.toCharArray()) }
        assertFailsWith<ProofExpiredException> { lock.disableBiometric(proof) }
        assertEquals(1, vault.bindingKeys.size)
    }

    // --- Fingerprint --------------------------------------------------------------------------------

    private suspend fun TestScope.setUpWithFingerprint(launcher: FakeLauncher): FakeSession {
        biometric = BiometricStatus.AVAILABLE
        startApp()
        lock.attach(launcher)
        val session = createPassphrase()
        assertEquals("offered right after the passphrase was created", FingerprintOffer.FIRST_SETUP, lock.fingerprintOffer.value)
        assertTrue(lock.acceptFingerprintOffer())
        assertNull(lock.fingerprintOffer.value)
        assertEquals(BiometricPurpose.ENROLL, launcher.purpose)
        assertTrue(lock.unlockUi.value.biometricPromptShowing)
        lock.onBiometricSucceeded(launcher.cipher)
        runCurrent()
        assertTrue(lock.status.value.biometricEnabled)
        assertEquals(BiometricNotice.ENROLLED, lock.unlockUi.value.notice)
        lock.noticeShown()
        return session
    }

    @Test
    fun fingerprintUnlockIsOfferedAfterSetUpAndUnlocksTheSameKey() = runTest(dispatcher) {
        val launcher = FakeLauncher()
        val session = setUpWithFingerprint(launcher)
        val blob = session.cipher.seal("p", plaintext)
        assertEquals(1, vault.biometricKeys.size)

        lock.lock()
        runCurrent()
        val locked = lock.state.value as LockState.Locked
        lock.autoPromptBiometric(locked.generation)
        lock.autoPromptBiometric(locked.generation) // Every resume asks; the prompt comes once per lock.
        runCurrent()
        assertEquals(BiometricPurpose.UNLOCK, launcher.purpose)
        assertEquals(2, launcher.shown)

        lock.onBiometricSucceeded(launcher.cipher)
        runCurrent()
        assertArrayEquals(plaintext, session().cipher.open("p", blob))
        assertNull("a fingerprint unlock offers nothing", lock.fingerprintOffer.value)
    }

    @Test
    fun usePassphraseOnThePromptLeavesTheLockScreenAsItWas() = runTest(dispatcher) {
        val launcher = FakeLauncher()
        setUpWithFingerprint(launcher)
        lock.lock()
        runCurrent()
        lock.requestBiometricUnlock()
        runCurrent()
        assertTrue(lock.unlockUi.value.biometricPromptShowing)

        lock.onBiometricError(AppLock.ERROR_NEGATIVE_BUTTON)
        runCurrent()
        assertTrue(lock.state.value is LockState.Locked)
        assertEquals(UnlockUi(), lock.unlockUi.value)
        assertTrue("fingerprint unlock stays on", lock.status.value.biometricEnabled)
        enter(passphrase)
        assertTrue(lock.state.value is LockState.Unlocked)
    }

    @Test
    fun decliningTheOfferMakesNoKeyAndCancellingThePromptRemovesItsKey() = runTest(dispatcher) {
        biometric = BiometricStatus.AVAILABLE
        val launcher = FakeLauncher()
        startApp()
        lock.attach(launcher)
        createPassphrase()
        lock.declineFingerprintOffer()
        assertNull(lock.fingerprintOffer.value)
        assertTrue(vault.biometricKeys.isEmpty())
        assertEquals(0, launcher.shown)

        val proof = (lock.checkPassphrase(passphrase.toCharArray()) as PassphraseCheck.Ok).proof
        assertTrue(lock.startBiometricEnrollment(proof))
        assertEquals(1, vault.biometricKeys.size)
        lock.onBiometricError(AppLock.ERROR_USER_CANCELED)
        runCurrent()
        assertTrue(vault.biometricKeys.isEmpty())
        assertFalse(lock.status.value.biometricEnabled)
        assertNull("cancelling is not a failure worth a message", lock.unlockUi.value.notice)
    }

    @Test
    fun aPromptThatCannotBeShownLeavesNothingPending() = runTest(dispatcher) {
        biometric = BiometricStatus.AVAILABLE
        val launcher = FakeLauncher().apply { canShow = false }
        startApp()
        lock.attach(launcher)
        createPassphrase()
        lock.acceptFingerprintOffer()
        runCurrent()
        assertFalse(lock.status.value.biometricEnabled)
        assertTrue("the key made for the prompt is gone again", vault.biometricKeys.isEmpty())
        assertEquals(BiometricNotice.ENROLL_FAILED, lock.unlockUi.value.notice)
        assertFalse(lock.unlockUi.value.biometricPromptShowing)
    }

    @Test
    fun withNoFingerprintEnrolledAnyMoreFingerprintUnlockGoesAndIsOfferedAgainLater() = runTest(dispatcher) {
        val launcher = FakeLauncher()
        setUpWithFingerprint(launcher)

        biometric = BiometricStatus.NONE_ENROLLED
        startApp()
        assertFalse(lock.status.value.biometricEnabled)
        assertTrue(lock.status.value.fingerprintInvalidated)
        assertTrue(vault.biometricKeys.isEmpty())

        // Unavailable for another reason keeps what is there; here there is nothing left to keep.
        biometric = BiometricStatus.AVAILABLE
        enter(passphrase)
        assertEquals(FingerprintOffer.AGAIN, lock.fingerprintOffer.value)
        lock.declineFingerprintOffer()
        assertFalse(lock.status.value.fingerprintInvalidated)
    }

    @Test
    fun hardwareThatIsOnlyUnavailableKeepsFingerprintUnlock() = runTest(dispatcher) {
        val launcher = FakeLauncher()
        setUpWithFingerprint(launcher)
        biometric = BiometricStatus.UNAVAILABLE
        startApp()
        assertTrue(lock.status.value.biometricEnabled)
        assertEquals(1, vault.biometricKeys.size)
    }

    // --- Reset --------------------------------------------------------------------------------------

    private fun someStoredData(): List<File> {
        val stored = listOf(
            File(files.filesDir, "settings.enc"),
            File(files.filesDir, "sessions/one.enc"),
            File(files.filesDir, "notes/deep/two.enc"),
            File(files.cacheDir, "scratch.bin"),
        )
        for (file in stored) {
            file.parentFile!!.mkdirs()
            file.writeBytes(byteArrayOf(1, 2, 3))
        }
        return stored
    }

    @Test
    fun aResetFromTheLockScreenDeletesEverythingAndReturnsToSetUp() = runTest(dispatcher) {
        startApp()
        val old = createPassphrase()
        val blob = old.cipher.seal("p", plaintext)
        val stored = someStoredData()
        startApp() // A cold start: locked, and the passphrase is "forgotten".
        assertTrue(lock.state.value is LockState.Locked)
        val resets = lock.resets.value
        val generation = lock.sessionGeneration.value

        lock.reset()
        assertEquals(LockState.Starting, lock.state.value)
        runCurrent()
        assertEquals(LockState.SetupRequired, lock.state.value)
        assertNull("nothing was left behind", lock.resetFailure.value)
        assertEquals(resets + 1, lock.resets.value)
        assertNotEquals(generation, lock.sessionGeneration.value)
        for (file in stored) assertFalse(file.path, file.exists())
        assertFalse(File(files.filesDir, "sessions").exists())
        nothingIsPersisted()

        // The app starts again from nothing: a new passphrase makes a new key.
        val fresh = createPassphrase(otherPassphrase)
        assertThrows(UndecryptableException::class.java) { fresh.cipher.open("p", blob) }
    }

    @Test
    fun aResetWhileUnlockedClosesTheSessionBeforeAnythingIsDeleted() = runTest(dispatcher) {
        startApp()
        val session = createPassphrase()
        val stored = someStoredData()
        val late = File(files.filesDir, "sessions/late.enc")
        val write = CompletableDeferred<Unit>()
        session.pendingWrite = write
        // A write that was queued before the reset, and lands while the session closes.
        session.lateWrite = { late.writeBytes(session.cipher.seal("p", plaintext)) }
        vault.afterDelete = { log += "key deleted" }

        lock.reset()
        assertEquals("the screens are gone at once", LockState.Starting, lock.state.value)
        assertEquals(1, session.screensCleared)
        runCurrent()
        // Still closing: nothing has been deleted yet.
        assertEquals(LockState.Starting, lock.state.value)
        assertTrue(session.closeStarted)
        assertTrue(files.lockConfig.exists())
        assertEquals(1, vault.bindingKeys.size)
        for (file in stored) assertTrue(file.path, file.exists())

        write.complete(Unit)
        runCurrent()
        assertEquals(LockState.SetupRequired, lock.state.value)
        assertEquals(listOf("screens cleared", "session closed", "key deleted"), log)
        assertFalse("the late write was deleted with the rest, not left behind", late.exists())
        for (file in stored) assertFalse(file.path, file.exists())
        assertFalse(files.encryptedDataExists())
        assertDestroyed(session.cipher)
        assertNull(lock.resetFailure.value)
        nothingIsPersisted()
    }

    @Test
    fun aResetThatCannotDeleteAFileSaysSoAndNeverOffersSetUpOverTheData() = runTest(dispatcher) {
        startApp()
        createPassphrase()
        val stored = someStoredData()
        val stuck = File(files.filesDir, "sessions")
        stuck.setWritable(false)
        try {
            lock.reset()
            runCurrent()
            assertEquals(ResetReport(keysDeleted = true, dataDeleted = false, configDeleted = true), lock.resetFailure.value)
            // The keys are gone, so what is left can never be read; and no new key is made over it.
            assertTrue(vault.aliases.isEmpty())
            assertEquals(LockState.Unrecoverable(UnrecoverableReason.CONFIG_MISSING), lock.state.value)
            assertTrue(stored[1].exists())
            assertFalse("everything else was still deleted", stored[0].exists() || stored[2].exists() || stored[3].exists())
            lock.setUp(passphrase.toCharArray(), passphrase.toCharArray())
            runCurrent()
            assertTrue(vault.aliases.isEmpty())
        } finally {
            stuck.setWritable(true)
        }

        lock.resetFailureShown()
        assertNull(lock.resetFailure.value)
        lock.reset() // From the error screen, once the files can be deleted.
        runCurrent()
        assertEquals(LockState.SetupRequired, lock.state.value)
        assertNull(lock.resetFailure.value)
        assertFalse(stored[1].exists())
    }

    @Test
    fun aResetThatCannotDeleteAKeySaysSoAndSetUpGoesOn() = runTest(dispatcher) {
        startApp()
        createPassphrase()
        val stuck = vault.bindingKeys.single()
        vault.undeletable += stuck
        lock.reset()
        runCurrent()
        assertEquals(ResetReport(keysDeleted = false, dataDeleted = true, configDeleted = true), lock.resetFailure.value)
        assertEquals(LockState.SetupRequired, lock.state.value)
        assertFalse(files.lockConfig.exists())

        createPassphrase(otherPassphrase)
        vault.undeletable.clear()
        startApp()
        assertFalse("deleted at a later start", stuck in vault.entries.keys)
        enter(otherPassphrase)
        assertTrue(lock.state.value is LockState.Unlocked)
    }

    @Test
    fun aResetThatCannotDeleteTheConfigurationSaysSoAndCanBeRepeated() = runTest(dispatcher) {
        startApp()
        createPassphrase()
        files.securityDir.setWritable(false)
        try {
            lock.reset()
            runCurrent()
        } finally {
            files.securityDir.setWritable(true)
        }
        assertEquals(ResetReport(keysDeleted = true, dataDeleted = true, configDeleted = false), lock.resetFailure.value)
        assertEquals("the configuration that is left names a key that is gone", LockState.Unrecoverable(UnrecoverableReason.KEY_LOST), lock.state.value)

        lock.resetFailureShown()
        lock.reset()
        runCurrent()
        assertEquals(LockState.SetupRequired, lock.state.value)
        assertNull(lock.resetFailure.value)
    }

    @Test
    fun anUnlockThatFinishesAfterAResetBeganIsThrownAway() = runTest(dispatcher) {
        startApp()
        createPassphrase()
        lock.lock()
        runCurrent()

        lock.unlockWithPassphrase(passphrase.toCharArray())
        lock.reset()
        runCurrent()
        assertEquals(LockState.SetupRequired, lock.state.value)
        nothingIsPersisted()
        // Whichever ran first, no session is left open and no key survives.
        for (session in sessions) {
            assertTrue(session.closed)
            assertDestroyed(session.cipher)
        }
    }

    // --- Things going wrong -----------------------------------------------------------------------

    @Test
    fun aSessionThatCannotBeOpenedEndsOnTheErrorScreenAndTryAgainStartsOver() = runTest(dispatcher) {
        startApp()
        createPassphrase()
        lock.lock()
        runCurrent()

        openFails = IOException("disk full")
        enter(passphrase)
        assertEquals(LockState.Failed("IOException: disk full"), lock.state.value)
        assertEquals(1, sessions.size)

        openFails = null
        lock.retry()
        runCurrent()
        assertTrue(lock.state.value is LockState.Locked)
        enter(passphrase)
        assertTrue(lock.state.value is LockState.Unlocked)
    }

    @Test
    fun filesThatCannotBeLookedAtAreAFailureNotAFirstRun() = runTest(dispatcher) {
        val unreadable = File(files.filesDir, "sessions").apply { mkdirs() }
        File(unreadable, "one.enc").writeBytes(byteArrayOf(1))
        unreadable.setReadable(false)
        try {
            startApp()
            assertTrue("${lock.state.value}", lock.state.value is LockState.Failed)
            nothingIsPersisted()
        } finally {
            unreadable.setReadable(true)
        }
        lock.retry()
        runCurrent()
        assertEquals("the data is found once it can be looked at", LockState.Unrecoverable(UnrecoverableReason.CONFIG_MISSING), lock.state.value)
    }

    @Test
    fun aDamagedConfigurationIsUnrecoverableAndOnlyAResetLeadsOn() = runTest(dispatcher) {
        startApp()
        createPassphrase()
        files.lockConfig.writeText("{ not json")
        startApp()
        assertEquals(LockState.Unrecoverable(UnrecoverableReason.CONFIG_UNREADABLE), lock.state.value)
        lock.retry()
        runCurrent()
        assertEquals(LockState.Unrecoverable(UnrecoverableReason.CONFIG_UNREADABLE), lock.state.value)
        lock.reset()
        runCurrent()
        assertEquals(LockState.SetupRequired, lock.state.value)
    }

    // --- The screenshot allowance -------------------------------------------------------------------

    /** Every file the app has, with its content: what "nothing was written" is measured on. */
    private fun everythingOnDisk(): Map<String, List<Byte>> =
        listOf(files.filesDir, files.cacheDir).flatMap { root -> root.walkTopDown().filter { it.isFile }.map { it.path to it.readBytes().toList() } }.toMap()

    @Test
    fun aScreenshotAllowanceOutlivesTheAppsOwnLockAndUnlock() = runTest(dispatcher) {
        startApp()
        createPassphrase()
        assertTrue(lock.screenshots.grant())
        val allowed = lock.screenshots.allowed.value
        assertTrue(allowed != null)

        // "Lock now": the lock screen can be captured as well while the allowance runs.
        lock.lock()
        runCurrent()
        assertTrue(lock.state.value is LockState.Locked)
        assertEquals(allowed, lock.screenshots.current())
        enter(passphrase)
        assertEquals(allowed, lock.screenshots.current())

        // Nor does the background timeout end it, or coming back.
        lock.onBackground()
        pass(60_000)
        assertTrue(lock.state.value is LockState.Locked)
        assertEquals(allowed, lock.screenshots.current())
        lock.onForeground()
        assertEquals(allowed, lock.screenshots.current())
        assertTrue(screenWatch.watching)

        // Only its own time does.
        pass(ScreenshotAllowance.DURATION_MILLIS - 60_000 - 1)
        assertEquals(allowed, lock.screenshots.allowed.value)
        pass(1)
        assertNull(lock.screenshots.allowed.value)
        assertFalse(screenWatch.watching)
    }

    @Test
    fun comingToTheForegroundEndsAnAllowanceWhoseTimerCouldNotRun() = runTest(dispatcher) {
        startApp()
        createPassphrase()
        lock.screenshots.grant()
        lock.onBackground()
        // The phone slept: the clock moved on past the deadline, no timer fired.
        clock.advance(ScreenshotAllowance.DURATION_MILLIS)
        assertTrue("nobody has looked yet", lock.screenshots.allowed.value != null)

        lock.onForeground()
        assertNull("over before anything is shown again", lock.screenshots.allowed.value)
        assertFalse(screenWatch.watching)
    }

    @Test
    fun aResetEndsAScreenshotAllowanceFromSettingsAndFromTheLockScreen() = runTest(dispatcher) {
        startApp()
        createPassphrase()
        lock.screenshots.grant()
        lock.reset() // In Settings, unlocked.
        assertNull("at once, before anything is deleted", lock.screenshots.allowed.value)
        assertFalse(screenWatch.watching)
        runCurrent()
        assertEquals(LockState.SetupRequired, lock.state.value)
        pass(ScreenshotAllowance.DURATION_MILLIS)
        assertNull(lock.screenshots.allowed.value)

        createPassphrase()
        lock.screenshots.grant()
        lock.lock()
        runCurrent()
        assertTrue(lock.screenshots.allowed.value != null)
        lock.reset() // On the lock screen, the passphrase forgotten.
        assertNull(lock.screenshots.allowed.value)
        assertFalse(screenWatch.watching)
        runCurrent()
        assertEquals(LockState.SetupRequired, lock.state.value)
        assertNull("and the app that starts again has nothing allowed", lock.screenshots.current())
    }

    @Test
    fun aScreenshotAllowanceIsNeverWrittenToDisk() = runTest(dispatcher) {
        startApp()
        createPassphrase()
        val before = everythingOnDisk()
        assertTrue(before.isNotEmpty())

        lock.screenshots.grant()
        runCurrent()
        assertEquals("giving it", before, everythingOnDisk())
        screenWatch.screenTurnsOff()
        assertEquals("the screen turning off", before, everythingOnDisk())
        lock.screenshots.grant()
        lock.screenshots.end()
        assertEquals("turning it off", before, everythingOnDisk())
        lock.screenshots.grant()
        pass(ScreenshotAllowance.DURATION_MILLIS)
        assertNull(lock.screenshots.allowed.value)
        assertEquals("its time running out", before, everythingOnDisk())
        assertFalse("the lock configuration has no word about it", files.lockConfig.readText().contains("creenshot"))

        // So a new process, which only has the files, starts with nothing allowed.
        lock.screenshots.grant()
        startApp()
        assertNull(lock.screenshots.allowed.value)
    }

    // --- The activity -------------------------------------------------------------------------------

    @Test
    fun theLastActivityFinishingClearsTheScreensButKeepsTheSessionUntilTheLock() = runTest(dispatcher) {
        startApp()
        val session = createPassphrase()
        lock.onActivityCreated()
        lock.onActivityDestroyed(finished = true)
        assertEquals(1, session.screensCleared)
        assertSame(session, session())
        assertFalse(session.closed)
    }

    @Test
    fun theSessionGenerationChangesAtEveryLockUnlockAndReset() = runTest(dispatcher) {
        startApp()
        val seen = mutableListOf(lock.sessionGeneration.value)
        fun changed(what: String) {
            assertFalse("$what: ${lock.sessionGeneration.value} in $seen", lock.sessionGeneration.value in seen)
            seen += lock.sessionGeneration.value
        }
        createPassphrase()
        changed("the first unlock")
        lock.lock()
        changed("a lock")
        runCurrent()
        enter(passphrase)
        changed("an unlock")
        lock.reset()
        changed("a reset")
        runCurrent()
        createPassphrase()
        changed("the set-up after it")
    }
}
