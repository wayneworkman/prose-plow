package us.theworkmans.proseplow.security

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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

    /** Every run of the locked clean-up: the readable limit and the trusted time it was given. */
    private val sweeps = mutableListOf<Pair<Int, Long>>()

    /** What the lock told the job scheduler, in order: true to schedule, false to cancel. */
    private val scheduled = mutableListOf<Boolean>()

    /** The readable copy of how long messages are kept that the lock handed to each session it opened. */
    private val copiesHandedIn = mutableListOf<Int>()

    /** What the sessions opened from now on say their setting of how long messages are kept is. */
    private var sessionLifetime: Int? = null

    /** Whether the keep-alive (the answer service in the foreground) is running, as the lock is told. */
    private var keepAliveRunning = true

    private lateinit var lock: AppLock<FakeSession>
    private lateinit var manager: LockManager

    /** How often the lock ended the process since the last [startApp], and what had happened by the first time. */
    private var processEnds = 0
    private var logAtProcessEnd: List<String>? = null

    /** What the lock asked of the heavy work beside it, in order; and how many "stand aside" have had no "carry on" yet. */
    private val madeWay = mutableListOf<String>()
    private var standingAside = 0

    /** Uses of the key store while nothing was standing aside: a key derivation the model was not paused for. */
    private var keyStoreUsesInTheWay = 0

    /** Uses of the lock manager's collaborators while nothing held the lock's mutex. */
    private val unserialised = mutableListOf<String>()

    /** Called at every step of every write of the lock configuration, inside the lock manager. */
    private var onConfigWrite: () -> Unit = {}

    /** True while the test itself drives the lock manager, as nothing in the app does. */
    private var testDrivesTheManager = false

    private fun insideTheManager() {
        if (!lock.managerInUse && !testDrivesTheManager) unserialised += Throwable().stackTrace.drop(1).take(8).joinToString("\n")
    }

    @After
    fun everyUseOfTheLockManagerWasSerialised() {
        assertEquals("the lock manager was used outside the mutex", emptyList<String>(), unserialised)
    }

    private class FakeSession(
        val cipher: BlobCipher,
        private val log: MutableList<String>,
        override val messageLifetimeDays: Int? = null,
    ) : UnlockedSession {
        var screensCleared = 0
        var closeStarted = false
        var closed = false

        /** A write that is queued when the session ends; [close] waits until the test completes it. */
        var pendingWrite: CompletableDeferred<Unit>? = null

        /** What that write does once it gets to run (it still needs the cipher). */
        var lateWrite: () -> Unit = {}

        /** A write that was still in the disk when [close] gave up waiting; [awaitStopped] waits until the test completes it. */
        var hungWrite: CompletableDeferred<Unit>? = null

        /** An answer that is being written; the test completes it when the answer is finished. */
        var answer: CompletableDeferred<Unit>? = null
        var finishOnly = 0
        var resumed = 0

        override fun hasUnfinishedWork(): Boolean = answer?.isActive == true

        override suspend fun awaitWorkFinished() {
            answer?.await()
        }

        override fun finishOnly() {
            finishOnly++
            log += "finish only"
        }

        override fun resume() {
            resumed++
            log += "resumed"
        }

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

        override suspend fun awaitStopped() {
            hungWrite?.await()
            log += "session stopped"
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

    /**
     * A new "process" over the same files and keys. [visible]: with an activity that is created
     * with it and reaches the screen, as the app really starts; without one, nothing is ever on
     * screen for this lock.
     */
    private fun TestScope.startApp(visible: Boolean = false, beforeAnythingRuns: () -> Unit = {}): AppLock<FakeSession> {
        processEnds = 0
        logAtProcessEnd = null
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

            override fun bootCount(): Int? {
                insideTheManager()
                return clock.bootCount()
            }
        }
        manager = LockManager(
            store = LockConfigStore(
                files.lockConfig,
                onWriteStep = {
                    insideTheManager()
                    onConfigWrite()
                },
            ),
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
            openSession = { cipher, lockedDays ->
                openFails?.let { throw it }
                copiesHandedIn += lockedDays
                FakeSession(cipher, log, sessionLifetime).also { sessions += it }
            },
            biometricStatus = { biometric },
            screenshots = ScreenshotAllowance(clock, screenWatch, dispatcher),
            mainDispatcher = dispatcher,
            ioDispatcher = dispatcher,
            processEnder = {
                if (processEnds++ == 0) logAtProcessEnd = log.toList()
            },
            heavyWork = object : MakesWay {
                override fun standAside() {
                    standingAside++
                    madeWay += "stand aside"
                }

                override fun carryOn() {
                    standingAside--
                    madeWay += "carry on"
                }
            },
            canFinishOutOfSight = { keepAliveRunning },
            maintenanceScheduling = { enabled -> scheduled += enabled },
            expiredData = { lifetime, now ->
                insideTheManager()
                log += "expired data swept"
                sweeps += lifetime to now
            },
        )
        assertEquals("nothing is known before the configuration was read", LockState.Starting, lock.state.value)
        if (visible) {
            lock.onActivityCreated()
            lock.onForeground()
        }
        beforeAnythingRuns()
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

        // An empty entry is not looked at: nothing checked, counted or said.
        val attempts = storedConfig().failedAttempts
        lock.unlockWithPassphrase(CharArray(0))
        assertFalse(lock.unlockUi.value.busy)
        runCurrent()
        assertNull(lock.unlockUi.value.rejected)
        assertEquals(attempts, storedConfig().failedAttempts)

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
        assertEquals(listOf("screens cleared", "session closed", "session stopped"), log)

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

    /**
     * A session's close is bounded: when its time is up it returns although one write may still
     * be stuck in the disk. The lock does not wait for that write (the key must go, and the
     * owner must be able to come back in); a reset does, however long it takes, because it
     * deletes the files and the write would put one back.
     */
    @Test
    fun aLockDoesNotWaitForASessionThatIsStillStoppingButAResetDoes() = runTest(dispatcher) {
        startApp()
        val session = createPassphrase()
        val stored = someStoredData()
        val hung = CompletableDeferred<Unit>()
        session.hungWrite = hung

        lock.lock()
        runCurrent()
        assertTrue("its close returned", session.closed)
        assertDestroyed(session.cipher)
        assertFalse("nothing holds the lock manager", lock.managerInUse)
        assertFalse("although the session has not stopped", "session stopped" in log)

        // The owner comes back in meanwhile.
        enter(passphrase)
        val next = session()
        assertEquals(2, sessions.size)
        assertArrayEquals(plaintext, next.cipher.open("p", next.cipher.seal("p", plaintext)))

        // A reset now closes the new session, and then waits for the old one's write.
        vault.afterDelete = { log += "key deleted" }
        lock.reset()
        runCurrent()
        assertEquals(LockState.Starting, lock.state.value)
        assertTrue(next.closed)
        assertDestroyed(next.cipher)
        pass(24 * 3_600_000L)
        assertEquals("for as long as it hangs", LockState.Starting, lock.state.value)
        assertTrue(files.lockConfig.exists())
        assertEquals(1, vault.bindingKeys.size)
        for (file in stored) assertTrue(file.path, file.exists())
        assertFalse("key deleted" in log)

        // The write lands at last; only then is everything deleted, that file with the rest.
        val late = File(files.filesDir, "sessions/late.enc").apply { writeBytes(byteArrayOf(1)) }
        hung.complete(Unit)
        runCurrent()
        assertEquals(LockState.SetupRequired, lock.state.value)
        assertEquals(2, log.count { it == "session stopped" })
        assertEquals("nothing was deleted before both had stopped", "key deleted", log.last())
        assertTrue(log.lastIndexOf("session stopped") < log.indexOf("key deleted"))
        assertFalse(late.exists())
        for (file in stored) assertFalse(file.path, file.exists())
        assertNull(lock.resetFailure.value)
        nothingIsPersisted()
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
    fun aFingerprintUnlockEndsAStreakOfWrongPassphrasesAsTheRightPassphraseDoes() = runTest(dispatcher) {
        val launcher = FakeLauncher()
        setUpWithFingerprint(launcher)
        lock.lock()
        runCurrent()
        repeat(5) { enter(wrongPassphrase) }
        assertEquals(lock.elapsedNow() + 30_000, lock.unlockUi.value.retryAtElapsed)
        assertEquals(5, LockConfigStore(files.lockConfig).read()!!.failedAttempts)

        lock.requestBiometricUnlock()
        runCurrent()
        lock.onBiometricSucceeded(launcher.cipher)
        runCurrent()
        assertTrue(lock.state.value is LockState.Unlocked)
        val stored = LockConfigStore(files.lockConfig).read()!!
        assertEquals(0, stored.failedAttempts)
        assertEquals(0L, stored.lastFailureElapsedMillis)
        assertEquals(0L, stored.lastFailureWallMillis)

        // Locked again, nothing is left of the wait, and the free attempts are free again.
        lock.lock()
        runCurrent()
        assertEquals(0L, lock.unlockUi.value.retryAtElapsed)
        repeat(4) {
            enter(wrongPassphrase)
            assertEquals(0L, lock.unlockUi.value.retryAtElapsed)
        }
        enter(passphrase)
        assertTrue(lock.state.value is LockState.Unlocked)

        // The same in a new process: the reset was stored, not only remembered.
        lock.lock()
        runCurrent()
        repeat(5) { enter(wrongPassphrase) }
        lock.requestBiometricUnlock()
        runCurrent()
        lock.onBiometricSucceeded(launcher.cipher)
        runCurrent()
        assertTrue(lock.state.value is LockState.Unlocked)
        startApp()
        assertEquals(0L, lock.unlockUi.value.retryAtElapsed)
        enter(passphrase)
        assertTrue(lock.state.value is LockState.Unlocked)
    }

    @Test
    fun anEnrolmentAuthorisedAfterTheAppLockedWrapsNothing() = runTest(dispatcher) {
        biometric = BiometricStatus.AVAILABLE
        val launcher = FakeLauncher()
        startApp()
        lock.attach(launcher)
        val session = createPassphrase()
        lock.declineFingerprintOffer()
        val proof = (lock.checkPassphrase(passphrase.toCharArray()) as PassphraseCheck.Ok).proof
        assertTrue(lock.startBiometricEnrollment(proof))
        assertEquals(BiometricPurpose.ENROLL, launcher.purpose)

        // The app locks with the prompt open; the fingerprint is accepted a moment later.
        lock.lock()
        lock.onBiometricSucceeded(launcher.cipher)
        runCurrent()

        assertDestroyed(session.cipher)
        assertFalse(lock.status.value.biometricEnabled)
        assertNull("no wrap of a key that is gone", LockConfigStore(files.lockConfig).read()!!.biometric)
        assertTrue(vault.biometricKeys.isEmpty())
        enter(passphrase)
        assertTrue("the passphrase still opens the app", lock.state.value is LockState.Unlocked)
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

    // --- The time limit of fingerprint unlock --------------------------------------------------------

    private val hourMillis = LockConfig.DEFAULT_FINGERPRINT_PERIOD_SECONDS * 1000L

    private fun storedStart(): FingerprintPeriodStart? = LockConfigStore(files.lockConfig).read()!!.fingerprintPeriodStart

    /** The fingerprint prompt from the lock screen's button, and the user's finger on it. */
    private fun TestScope.unlockByFingerprint(launcher: FakeLauncher) {
        lock.requestBiometricUnlock()
        runCurrent()
        lock.onBiometricSucceeded(launcher.cipher)
        runCurrent()
    }

    /** Fingerprint unlock is still set up, its key is there, and nothing says it was invalidated. */
    private fun assertFingerprintStillSetUp() {
        assertTrue(lock.status.value.biometricEnabled)
        assertFalse(lock.status.value.fingerprintInvalidated)
        val stored = LockConfigStore(files.lockConfig).read()!!
        assertNotNull(stored.biometric)
        assertFalse(stored.fingerprintInvalidated)
        assertEquals(1, vault.biometricKeys.size)
    }

    /**
     * Ordinary use: every lock and every fingerprint unlock starts the period again, so the
     * fingerprint goes on working for hours of use with the app locking in between. Catches a
     * lock or a fingerprint unlock that does not move the start.
     */
    @Test
    fun everyLockAndEveryFingerprintUnlockStartsThePeriodAgain() = runTest(dispatcher) {
        val launcher = FakeLauncher()
        setUpWithFingerprint(launcher)
        pass(30 * 60_000L)
        lock.lock()
        runCurrent()
        assertEquals("the moment of the lock", FingerprintPeriodStart(clock.elapsed, 1), storedStart())
        repeat(3) {
            pass(50 * 60_000L)
            assertFalse(lock.status.value.fingerprintPeriodOver)
            unlockByFingerprint(launcher)
            assertTrue(lock.state.value is LockState.Unlocked)
            assertEquals("the moment of the fingerprint unlock", FingerprintPeriodStart(clock.elapsed, 1), storedStart())
            pass(20 * 60_000L)
            lock.lock()
            runCurrent()
            assertEquals("the moment of the lock", FingerprintPeriodStart(clock.elapsed, 1), storedStart())
        }
    }

    /**
     * Once the period has run out the lock screen says so, does not start the prompt by itself
     * and does not offer the fingerprint; the passphrase opens the app, and after it the
     * fingerprint works again. Catches a lock screen state that does not tell "run out" apart,
     * an automatic prompt that is shown anyway, and a passphrase unlock that leaves it run out.
     */
    @Test
    fun afterThePeriodTheLockScreenNeitherPromptsNorOffersTheFingerprintUntilThePassphrase() = runTest(dispatcher) {
        val launcher = FakeLauncher()
        setUpWithFingerprint(launcher)
        lock.lock()
        runCurrent()
        assertFalse(lock.status.value.fingerprintPeriodOver)
        pass(hourMillis)

        // The app was out of sight, so the next start is a cold one.
        startApp()
        lock.attach(launcher)
        val shown = launcher.shown
        assertTrue(lock.status.value.biometricEnabled)
        assertTrue(lock.status.value.fingerprintPeriodOver)
        lock.autoPromptBiometric((lock.state.value as LockState.Locked).generation)
        runCurrent()
        assertEquals("no prompt by itself", shown, launcher.shown)
        lock.requestBiometricUnlock() // Not offered; and refused should it be asked for anyway.
        runCurrent()
        assertEquals(shown, launcher.shown)
        assertFalse(lock.unlockUi.value.biometricPromptShowing)
        assertNull(lock.unlockUi.value.notice)
        assertFingerprintStillSetUp()

        enter(passphrase)
        assertTrue(lock.state.value is LockState.Unlocked)
        assertFalse(lock.status.value.fingerprintPeriodOver)
        assertNull("nothing to offer: fingerprint unlock is still on", lock.fingerprintOffer.value)
        lock.lock()
        runCurrent()
        lock.autoPromptBiometric((lock.state.value as LockState.Locked).generation)
        runCurrent()
        assertEquals("the prompt comes by itself again", shown + 1, launcher.shown)
        lock.onBiometricSucceeded(launcher.cipher)
        runCurrent()
        assertTrue(lock.state.value is LockState.Unlocked)
    }

    /**
     * The lock screen stayed in front while the period ran out, so what it was told at the lock
     * is out of date: the automatic prompt still does not appear, because the lock manager
     * refuses the cipher, and the screen is told. Catches a decision taken from the published
     * state alone.
     */
    @Test
    fun aPeriodThatRunsOutInFrontOfTheLockScreenStartsNoPrompt() = runTest(dispatcher) {
        val launcher = FakeLauncher()
        setUpWithFingerprint(launcher)
        lock.lock()
        runCurrent()
        val shown = launcher.shown
        pass(hourMillis)
        assertFalse("published at the lock", lock.status.value.fingerprintPeriodOver)
        lock.autoPromptBiometric((lock.state.value as LockState.Locked).generation)
        runCurrent()
        assertEquals(shown, launcher.shown)
        assertTrue("and now the screen knows", lock.status.value.fingerprintPeriodOver)
        assertFingerprintStillSetUp()
    }

    /** A restart of the phone needs the passphrase, however soon after the lock. Catches a check of the time alone. */
    @Test
    fun aRestartOfThePhoneNeedsThePassphrase() = runTest(dispatcher) {
        val launcher = FakeLauncher()
        setUpWithFingerprint(launcher)
        lock.lock()
        runCurrent()
        clock.reboot(upMillis = clock.elapsed + 1_000) // A time since boot just after the lock.
        startApp()
        lock.attach(launcher)
        assertTrue(lock.status.value.fingerprintPeriodOver)
        lock.autoPromptBiometric((lock.state.value as LockState.Locked).generation)
        runCurrent()
        assertFalse(lock.unlockUi.value.biometricPromptShowing)
        assertFingerprintStillSetUp()
        enter(passphrase)
        assertTrue(lock.state.value is LockState.Unlocked)
        assertEquals(FingerprintPeriodStart(clock.elapsed, 2), storedStart())
    }

    /**
     * The period runs out while the prompt is showing: the finger on the sensor no longer
     * opens the app, nothing is said to be wrong with fingerprint unlock (no notice, no removal,
     * no offer to set it up again), and the screen now asks for the passphrase. Catches a check
     * made only when the prompt was shown, and a refusal taken for a lost key.
     */
    @Test
    fun aPeriodThatRunsOutWhileThePromptShowsRefusesTheUnlockWithoutRemovingAnything() = runTest(dispatcher) {
        val launcher = FakeLauncher()
        setUpWithFingerprint(launcher)
        lock.lock()
        runCurrent()
        pass(hourMillis - 60_000)
        lock.requestBiometricUnlock()
        runCurrent()
        assertTrue(lock.unlockUi.value.biometricPromptShowing)
        val opened = sessions.size
        pass(60_000)
        lock.onBiometricSucceeded(launcher.cipher)
        runCurrent()
        assertTrue(lock.state.value is LockState.Locked)
        assertEquals("no session was opened", opened, sessions.size)
        assertNull(lock.unlockUi.value.notice)
        assertNull(lock.unlockUi.value.error)
        assertTrue(lock.status.value.fingerprintPeriodOver)
        assertFingerprintStillSetUp()

        enter(passphrase)
        assertTrue(lock.state.value is LockState.Unlocked)
        assertNull("not invalidated, so not offered again", lock.fingerprintOffer.value)
    }

    /**
     * At a lock the start of the period is stored after the session is closed and its key
     * destroyed, and before the process ends. Catches a write that keeps the key in memory
     * longer, and one the end of the process could cut off.
     */
    @Test
    fun theStartOfThePeriodIsStoredAfterTheKeyIsGoneAndBeforeTheProcessEnds() = runTest(dispatcher) {
        val launcher = FakeLauncher()
        val session = setUpWithFingerprint(launcher)
        pass(60_000)
        val lockedAt = clock.elapsed
        val writes = mutableListOf<String>()
        onConfigWrite = {
            val key = try {
                session.cipher.seal("p", plaintext)
                "the key in memory"
            } catch (_: KeyDestroyedException) {
                "the key gone"
            }
            writes += "$key, ${if (session.closed) "closed" else "open"}, $processEnds process ends"
        }
        log.clear()
        lock.lock()
        runCurrent()
        assertTrue("the start was written", writes.isNotEmpty())
        assertEquals(listOf("the key gone, closed, 0 process ends"), writes.distinct())
        assertEquals(FingerprintPeriodStart(lockedAt, 1), storedStart())
        assertEquals(1, processEnds)
        assertEquals(listOf("screens cleared", "session closed", "session stopped"), logAtProcessEnd)
    }

    /**
     * The end of a lease is a lock: the period starts again then, not only when the screen
     * locked. Catches a lease end that does not move the start.
     */
    @Test
    fun theEndOfALeaseStartsThePeriodAgain() = runTest(dispatcher) {
        val launcher = FakeLauncher()
        val session = setUpWithFingerprint(launcher)
        val answer = lockItselfDuringAnAnswer(session)
        val lockedAt = clock.elapsed
        assertEquals(FingerprintPeriodStart(lockedAt, 1), storedStart())
        pass(8 * 60_000L)
        answer.complete(Unit)
        runCurrent()
        assertTrue(session.closed)
        assertEquals("the moment the lease ended", FingerprintPeriodStart(lockedAt + 8 * 60_000L, 1), storedStart())

        // So the fingerprint still works an hour after the screen locked, within the hour after the lease.
        pass(hourMillis - 5 * 60_000L)
        assertFalse(lock.status.value.fingerprintPeriodOver)
        unlockByFingerprint(launcher)
        assertTrue(lock.state.value is LockState.Unlocked)
    }

    // A lock that runs late counts from when it was due --------------------------------------------

    /** The lock manager's own decision, asked by the test directly. */
    private fun cipherResult(): BiometricCipherResult {
        testDrivesTheManager = true
        try {
            return manager.biometricUnlockCipher()
        } finally {
            testDrivesTheManager = false
        }
    }

    /**
     * The app leaves the screen, the process is frozen so the one-minute timer never runs, and
     * the phone lies untouched for hours; then the app is opened and the lock is made up for.
     * The period starts when the lock was due, not when it was made up for, so it has run out:
     * no prompt, no button, the lock manager refuses. Catches a late lock counted as recent use.
     */
    @Test
    fun aLockMadeUpForHoursLateCountsFromWhenItWasDue() = runTest(dispatcher) {
        val launcher = FakeLauncher()
        setUpWithFingerprint(launcher)
        lock.onBackground()
        val dueAt = clock.elapsed + 60_000
        // Frozen: the clock moves on, the timer does not.
        clock.advance(60_000 + 5 * hourMillis)
        assertTrue(lock.state.value is LockState.Unlocked)
        lock.onForeground()
        runCurrent()
        val locked = lock.state.value as LockState.Locked
        assertEquals(FingerprintPeriodStart(dueAt, 1), storedStart())
        assertTrue(lock.status.value.fingerprintPeriodOver)
        val shown = launcher.shown
        lock.autoPromptBiometric(locked.generation)
        runCurrent()
        assertEquals("no prompt by itself", shown, launcher.shown)
        assertEquals(BiometricCipherResult.PeriodOver, cipherResult())
        assertFingerprintStillSetUp()
    }

    /**
     * The same, made up for less than the period after it was due: the fingerprint still works,
     * and the start stored is still the due moment, not the moment the lock ran. Catches a start
     * taken from the late lock even where the outcome happens to be the same.
     */
    @Test
    fun aLockMadeUpForWithinThePeriodStillStartsItWhenItWasDue() = runTest(dispatcher) {
        val launcher = FakeLauncher()
        setUpWithFingerprint(launcher)
        lock.onBackground()
        val dueAt = clock.elapsed + 60_000
        clock.advance(60_000 + 30 * 60_000L)
        lock.onForeground()
        runCurrent()
        val locked = lock.state.value as LockState.Locked
        assertEquals(FingerprintPeriodStart(dueAt, 1), storedStart())
        assertFalse(lock.status.value.fingerprintPeriodOver)
        val shown = launcher.shown
        lock.autoPromptBiometric(locked.generation)
        runCurrent()
        assertEquals("the prompt comes by itself", shown + 1, launcher.shown)
        // Half an hour later it has run out: an hour after the due moment, not after the late lock.
        lock.onBiometricError(AppLock.ERROR_USER_CANCELED)
        runCurrent()
        clock.advance(30 * 60_000L)
        assertEquals(BiometricCipherResult.PeriodOver, cipherResult())
    }

    /**
     * A timer that fires late on its own, without the app coming to the front (the process was
     * frozen and then thawed in the background). Catches a fix made only where the app comes to
     * the front.
     */
    @Test
    fun aTimerThatFiresLateOnItsOwnCountsFromWhenTheLockWasDue() = runTest(dispatcher) {
        val launcher = FakeLauncher()
        setUpWithFingerprint(launcher)
        lock.onBackground()
        val dueAt = clock.elapsed + 60_000
        clock.advance(5 * hourMillis) // The clock moves on while the timer stands still...
        advanceTimeBy(60_000) // ...and then the timer runs, five hours late.
        runCurrent()
        assertTrue(lock.state.value is LockState.Locked)
        assertEquals(FingerprintPeriodStart(dueAt, 1), storedStart())
        assertTrue(lock.status.value.fingerprintPeriodOver)
        assertEquals(BiometricCipherResult.PeriodOver, cipherResult())
    }

    /**
     * A lease whose ten-minute timer could not run, ended hours later when the app comes to the
     * front: the period starts at the end of the ten minutes at the latest. Catches a lease end
     * counted at the moment it was made up for.
     */
    @Test
    fun aLeaseEndedLateCountsFromWhenItWasDueToEnd() = runTest(dispatcher) {
        val launcher = FakeLauncher()
        val session = setUpWithFingerprint(launcher)
        lockItselfDuringAnAnswer(session)
        val lockedAt = clock.elapsed
        assertEquals(FingerprintPeriodStart(lockedAt, 1), storedStart())
        clock.advance(5 * hourMillis) // Frozen: the lease's timer does not run.
        assertFalse(session.closeStarted)
        lock.onForeground()
        runCurrent()
        assertTrue(session.closed)
        assertEquals(FingerprintPeriodStart(lockedAt + AppLock.LEASE_MILLIS, 1), storedStart())
        assertTrue(lock.status.value.fingerprintPeriodOver)
        assertEquals(BiometricCipherResult.PeriodOver, cipherResult())
    }

    /**
     * A lock made up for hours late may still leave a lease (an answer under way, the keep-alive
     * running). That lease lasts its ten minutes as always, but its end starts the period no
     * later than ten minutes after the lock was due. Catches a late lock whose lease end counts
     * as recent use.
     */
    @Test
    fun theLeaseOfALateLockCannotMakeUpForTheHoursEither() = runTest(dispatcher) {
        val launcher = FakeLauncher()
        val session = setUpWithFingerprint(launcher)
        val answer = CompletableDeferred<Unit>()
        session.answer = answer
        lock.onBackground()
        val dueAt = clock.elapsed + 60_000
        clock.advance(60_000 + 5 * hourMillis)
        lock.onForeground()
        runCurrent()
        assertTrue(lock.state.value is LockState.Locked)
        assertEquals("a lease began", 1, session.finishOnly)
        assertFalse(session.closeStarted)
        pass(60_000)
        answer.complete(Unit)
        runCurrent()
        assertTrue(session.closed)
        assertEquals(FingerprintPeriodStart(dueAt + AppLock.LEASE_MILLIS, 1), storedStart())
        assertTrue(lock.status.value.fingerprintPeriodOver)
        assertEquals(BiometricCipherResult.PeriodOver, cipherResult())
    }

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
        assertEquals(listOf("screens cleared", "session closed", "session stopped", "key deleted"), log)
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

    // --- Finishing an answer behind the lock ----------------------------------------------------------

    /** The app leaves the screen with [session] in the middle of an answer, and locks by itself after its minute. */
    private fun TestScope.lockItselfDuringAnAnswer(session: FakeSession): CompletableDeferred<Unit> {
        val answer = CompletableDeferred<Unit>()
        session.answer = answer
        lock.onBackground()
        pass(59_999)
        assertTrue(lock.state.value is LockState.Unlocked)
        pass(1)
        assertTrue(lock.state.value is LockState.Locked)
        return answer
    }

    private fun storedConfig(): LockConfig = LockConfigStore(files.lockConfig).read()!!

    @Test
    fun anAnswerBeingWrittenWhenTheAppLocksItselfIsFinishedBehindTheLock() = runTest(dispatcher) {
        startApp()
        val session = createPassphrase()
        assertTrue("on unless the user turns it off", lock.status.value.finishAnswerAfterLocking)
        val blob = session.cipher.seal("p", plaintext)
        val generation = lock.sessionGeneration.value
        val answer = lockItselfDuringAnAnswer(session)

        // For whoever looks at the phone it is locked exactly as always.
        assertEquals(1, session.screensCleared)
        assertNotEquals(generation, lock.sessionGeneration.value)
        assertTrue(WindowSecurity.mustBeSecure(lock.state.value is LockState.Unlocked, null, clock.elapsed, 33))
        // Behind that, the session goes on, key included, and starts nothing new.
        assertEquals(1, session.finishOnly)
        assertFalse(session.closeStarted)
        assertTrue(lock.sessionEnding.value)
        assertArrayEquals(plaintext, session.cipher.open("p", blob))
        pass(5 * 60_000L)
        assertFalse("for as long as the answer takes, within the ceiling", session.closeStarted)

        // The answer is finished: now the session ends as at any lock.
        answer.complete(Unit)
        runCurrent()
        assertTrue(session.closed)
        assertDestroyed(session.cipher)
        assertFalse(lock.sessionEnding.value)
        assertEquals(listOf("screens cleared", "finish only", "session closed", "session stopped"), log)
        pass(AppLock.LEASE_MILLIS) // And nothing is left that could do anything later.
        assertEquals(4, log.size)

        // The next unlock is an ordinary one, into a new session with the same key.
        enter(passphrase)
        assertEquals(2, sessions.size)
        assertArrayEquals(plaintext, session().cipher.open("p", blob))
    }

    @Test
    fun aLeaseEndsAfterTenMinutesWhateverTheAnswerIsDoing() = runTest(dispatcher) {
        assertEquals(10, AppLock.LEASE_MINUTES)
        assertEquals(600_000L, AppLock.LEASE_MILLIS)
        startApp()
        val session = createPassphrase()
        lockItselfDuringAnAnswer(session)

        pass(AppLock.LEASE_MILLIS - 1)
        assertFalse("a millisecond before", session.closeStarted)
        pass(1)
        assertTrue("the session is closed, which stops the answer and saves it as stopped", session.closed)
        assertDestroyed(session.cipher)
        assertFalse(lock.sessionEnding.value)
        assertTrue(lock.state.value is LockState.Locked)
    }

    /**
     * The security review's A3: without the keep-alive the process may be frozen and the phone
     * asleep, and then no timer runs that would end a lease. So a lease starts only while the
     * keep-alive really runs; otherwise the app's own lock is as with the setting off.
     */
    @Test
    fun noLeaseStartsUnlessTheKeepAliveIsReallyRunning() = runTest(dispatcher) {
        startApp()
        val session = createPassphrase()
        assertTrue(lock.status.value.finishAnswerAfterLocking)
        keepAliveRunning = false // The answer service never reached the foreground.
        session.answer = CompletableDeferred()

        lock.onBackground()
        pass(60_000)
        assertTrue(lock.state.value is LockState.Locked)
        assertEquals("nothing is left to finish behind the lock", 0, session.finishOnly)
        assertTrue(session.closed)
        assertDestroyed(session.cipher)
        assertFalse(lock.sessionEnding.value)
        assertEquals(listOf("screens cleared", "session closed", "session stopped"), log)

        // With the keep-alive running again, the next answer is finished behind the lock as before.
        keepAliveRunning = true
        enter(passphrase)
        val next = session()
        lockItselfDuringAnAnswer(next)
        assertEquals(1, next.finishOnly)
        assertFalse(next.closeStarted)
        lock.lock()
        runCurrent()
    }

    /**
     * The third review's finding 1, the net: what kept the process alive out of sight ends while
     * a lease runs (the answer service stopped from outside). The lease ends at once, by the
     * ceiling's path, long before the ten minutes. Without a lease the same news changes nothing.
     */
    @Test
    fun theEndOfTheKeepAliveEndsALeaseAtOnceAndNothingElse() = runTest(dispatcher) {
        startApp(visible = true)
        val session = createPassphrase()
        lock.onKeepAliveEnded()
        runCurrent()
        assertTrue("an unlocked session is not touched", lock.state.value is LockState.Unlocked)
        assertFalse(session.closeStarted)

        lockItselfDuringAnAnswer(session)
        pass(60_000)
        assertFalse(session.closeStarted)
        assertEquals(0, processEnds)

        lock.onKeepAliveEnded()
        runCurrent()
        assertTrue(session.closed)
        assertDestroyed(session.cipher)
        assertFalse(lock.sessionEnding.value)
        assertEquals(listOf("screens cleared", "finish only", "session closed", "session stopped"), log)
        assertEquals(1, processEnds)
        lock.onKeepAliveEnded() // Told again: nothing is left to end.
        runCurrent()
        assertEquals(4, log.size)
        assertEquals(1, processEnds)
    }

    /**
     * The security review's D1 (LEASE-05): an unlock during a lease gives the leased session back
     * only when the key it yields is that session's key. Here the configuration is replaced
     * behind the app's back by one whose passphrase opens another data key: the unlock opens a
     * new session with that key, and the leased session is closed and its key destroyed.
     */
    @Test
    fun anUnlockDuringALeaseThatYieldsAnotherKeyEndsTheLeaseAndOpensANewSession() = runTest(dispatcher) {
        startApp()
        val leased = createPassphrase()
        val blob = leased.cipher.seal("p", plaintext)
        lockItselfDuringAnAnswer(leased)
        assertFalse(leased.closeStarted)

        // A configuration made elsewhere, with its own data key and its own binding key.
        val foreignVault = SoftwareKeyVault()
        val foreignFiles = AppFiles(temp.newFolder("foreign-files"), temp.newFolder("foreign-cache"))
        val foreign = LockManager(
            store = LockConfigStore(foreignFiles.lockConfig),
            vault = foreignVault,
            clock = clock,
            iterations = 1_000,
            minIterations = 1,
            encryptedDataExists = { false },
        )
        assertEquals(StartResult.SetupRequired, foreign.start())
        foreign.setUp(otherPassphrase.toCharArray()).key.destroy()
        // Swapped in for the app's own: the file, and its binding key in the key store.
        foreignFiles.lockConfig.copyTo(files.lockConfig, overwrite = true)
        for ((id, key) in foreignVault.entries) vault.putKey(id, key)
        // The lock manager reads its configuration only when it starts; this is that read.
        testDrivesTheManager = true
        assertEquals(StartResult.Locked, manager.start())
        testDrivesTheManager = false

        enter(otherPassphrase)
        assertTrue(lock.state.value is LockState.Unlocked)
        assertEquals("a new session, not the leased one", 2, sessions.size)
        assertFalse(session() === leased)
        assertEquals(0, leased.resumed)
        assertTrue(leased.closed)
        assertDestroyed(leased.cipher)
        assertFalse(lock.sessionEnding.value)
        assertThrows("the new session's key is not the old one", UndecryptableException::class.java) { session().cipher.open("p", blob) }
    }

    @Test
    fun aCeilingItsTimerMissedIsEnforcedBeforeTheSessionIsHandedBack() = runTest(dispatcher) {
        startApp()
        val session = createPassphrase()
        lockItselfDuringAnAnswer(session)
        // The process was not allowed to run: the clock moved on, no timer fired.
        clock.advance(AppLock.LEASE_MILLIS)
        assertFalse(session.closeStarted)

        enter(passphrase)
        assertTrue("the right passphrase does not bring an expired lease back", session.closed)
        assertDestroyed(session.cipher)
        assertEquals(0, session.resumed)
        assertEquals(2, sessions.size)
        assertFalse(session() === session)
    }

    @Test
    fun comingToTheFrontEndsALeaseWhoseTimeIsUp() = runTest(dispatcher) {
        startApp()
        val session = createPassphrase()
        lockItselfDuringAnAnswer(session)
        clock.advance(AppLock.LEASE_MILLIS - 1)
        lock.onForeground()
        runCurrent()
        assertFalse(session.closeStarted)
        lock.onBackground()
        clock.advance(1)
        lock.onForeground()
        runCurrent()
        assertTrue(session.closed)
        assertDestroyed(session.cipher)
    }

    @Test
    fun lockNowStopsTheAnswerAndDestroysTheKeyAtOnceWhateverTheSetting() = runTest(dispatcher) {
        startApp()
        val session = createPassphrase()
        assertTrue(lock.status.value.finishAnswerAfterLocking)
        session.answer = CompletableDeferred()

        lock.lock()
        runCurrent()
        assertEquals("nothing is left to finish", 0, session.finishOnly)
        assertTrue(session.closed)
        assertDestroyed(session.cipher)
        assertFalse(lock.sessionEnding.value)
        assertEquals(listOf("screens cleared", "session closed", "session stopped"), log)
    }

    @Test
    fun lockNowAlsoEndsALeaseThatIsRunning() = runTest(dispatcher) {
        startApp()
        val session = createPassphrase()
        lockItselfDuringAnAnswer(session)
        assertFalse(session.closeStarted)
        lock.lock()
        runCurrent()
        assertTrue(session.closed)
        assertDestroyed(session.cipher)
        assertFalse(lock.sessionEnding.value)
    }

    // --- The timed lock on time, and stopping a lease from the lock screen ----------------------------

    /** Records every value [AppLock.lockPending], [AppLock.sessionEnding] and [AppLock.leaseRunning] take, at the moment they take it. */
    private fun TestScope.recordFlows(): MutableList<String> {
        val events = mutableListOf<String>()
        val atOnce = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(atOnce) { lock.lockPending.collect { events += "pending $it" } }
        backgroundScope.launch(atOnce) { lock.sessionEnding.collect { events += "ending $it" } }
        backgroundScope.launch(atOnce) { lock.leaseRunning.collect { events += "lease $it" } }
        events.clear()
        return events
    }

    /**
     * The wish for the keep-alive: pending exactly from leaving the screen unlocked with a timed
     * lock until the lock fires, the app comes back, or it locks otherwise. When the lock fires,
     * the session it ended is said to be ending before the lock is said to be pending no more,
     * so that nothing lets the keep-alive go while the key is in memory.
     */
    @Test
    fun aLockIsPendingExactlyFromLeavingUnlockedUntilItFiresOrTheAppComesBack() = runTest(dispatcher) {
        startApp(visible = true)
        val session = createPassphrase()
        val events = recordFlows()
        assertFalse("not while the app is in front", lock.lockPending.value)

        lock.onBackground()
        assertTrue("at the moment of leaving", lock.lockPending.value)
        pass(30_000)
        assertTrue(lock.lockPending.value)
        lock.onForeground()
        assertFalse("back in front", lock.lockPending.value)
        assertSame(session, session())
        assertEquals(listOf("pending true", "pending false"), events)
        events.clear()

        lock.onBackground()
        pass(59_999)
        assertTrue(lock.lockPending.value)
        pass(1)
        assertTrue(lock.state.value is LockState.Locked)
        assertFalse("fired", lock.lockPending.value)
        assertEquals(listOf("pending true", "ending true", "pending false", "ending false"), events)
        assertDestroyed(session.cipher)

        // Locked, coming and going pends nothing.
        events.clear()
        lock.onForeground()
        lock.onBackground()
        pass(3_600_000)
        assertEquals(emptyList<String>(), events.filter { it.startsWith("pending") })
    }

    @Test
    fun withImmediatelyNoLockIsEverPending() = runTest(dispatcher) {
        startApp(visible = true)
        createPassphrase()
        lock.setLockTimeout(0)
        val events = recordFlows()
        lock.onBackground()
        assertTrue(lock.state.value is LockState.Locked)
        runCurrent()
        assertEquals(emptyList<String>(), events.filter { it.startsWith("pending") })

        // Nor for an unlock that finishes after the app left.
        lock.onForeground()
        lock.unlockWithPassphrase(passphrase.toCharArray())
        lock.onBackground()
        runCurrent()
        assertTrue(lock.state.value is LockState.Locked)
        assertEquals(emptyList<String>(), events.filter { it.startsWith("pending") })
    }

    @Test
    fun anUnlockThatFinishesAfterTheAppLeftPendsItsLockFromThen() = runTest(dispatcher) {
        startApp()
        createPassphrase()
        lock.lock()
        runCurrent()
        lock.unlockWithPassphrase(passphrase.toCharArray())
        lock.onBackground()
        assertFalse("nothing unlocked yet, nothing pending", lock.lockPending.value)
        clock.advance(20_000)
        runCurrent()
        assertTrue(lock.state.value is LockState.Unlocked)
        assertTrue(lock.lockPending.value)
        pass(40_000)
        assertTrue(lock.state.value is LockState.Locked)
        assertFalse(lock.lockPending.value)
    }

    @Test
    fun lockNowAndAResetEndAPendingLock() = runTest(dispatcher) {
        startApp(visible = true)
        createPassphrase()
        lock.onBackground()
        assertTrue(lock.lockPending.value)
        lock.lock() // As from a part of the app that is still shown; nothing pends after it.
        assertFalse(lock.lockPending.value)
        runCurrent()

        lock.onForeground()
        enter(passphrase)
        lock.onBackground()
        assertTrue(lock.lockPending.value)
        lock.reset()
        assertFalse(lock.lockPending.value)
        runCurrent()
        pass(60_000)
        assertFalse(lock.lockPending.value)
    }

    /**
     * The keep-alive never came, or went (the process was frozen): the timer did not run. Nothing
     * of that reaches the lock, which makes the lock up at the return as it always did, from
     * when it was due; the lock stops pending then.
     */
    @Test
    fun aPendingLockWhoseTimerCouldNotRunIsMadeUpForAtTheReturn() = runTest(dispatcher) {
        startApp(visible = true)
        val session = createPassphrase()
        lock.onBackground()
        clock.advance(3_600_000) // Frozen: the clock moves on, the timer does not.
        assertTrue(lock.lockPending.value)
        lock.onKeepAliveEnded() // Told that the keep-alive is gone: no lease, so nothing changes.
        assertSame(session, session())
        lock.onForeground()
        assertEquals(LockState.Locked(1), lock.state.value)
        assertFalse(lock.lockPending.value)
        runCurrent()
        assertDestroyed(session.cipher)
    }

    /** Item of the lock screen: the lock's own state says whether a lease runs, and "Stop the answer" ends it as "Lock now" does. */
    @Test
    fun theLockSaysWhileALeaseRunsAndStoppingTheAnswerEndsIt() = runTest(dispatcher) {
        startApp()
        val session = createPassphrase()
        assertFalse(lock.leaseRunning.value)
        lock.stopAnswerBehindLock() // Unlocked: nothing to stop, and it does not lock.
        runCurrent()
        assertSame(session, session())
        assertFalse(session.closeStarted)

        lockItselfDuringAnAnswer(session)
        assertTrue("an answer is being finished behind the lock", lock.leaseRunning.value)
        assertEquals(1, session.finishOnly)
        val generation = (lock.state.value as LockState.Locked).generation

        lock.stopAnswerBehindLock()
        runCurrent()
        assertFalse(lock.leaseRunning.value)
        assertTrue("closed, which stops the answer and keeps it as stopped", session.closed)
        assertDestroyed(session.cipher)
        assertFalse(lock.sessionEnding.value)
        assertEquals(LockState.Locked(generation), lock.state.value)
        assertEquals(listOf("screens cleared", "finish only", "session closed", "session stopped"), log)

        // Again, and with no lease: nothing changes.
        val stored = files.lockConfig.readBytes().toList()
        lock.stopAnswerBehindLock()
        runCurrent()
        assertEquals(4, log.size)
        assertEquals(stored, files.lockConfig.readBytes().toList())
        assertEquals(LockState.Locked(generation), lock.state.value)
    }

    @Test
    fun theLeaseIsPublishedOnlyWhileItRuns() = runTest(dispatcher) {
        startApp()
        val session = createPassphrase()
        val events = recordFlows()
        // An ordinary lock: a session closing is not a lease.
        lock.lock()
        runCurrent()
        assertEquals(emptyList<String>(), events.filter { it.startsWith("lease") })

        enter(passphrase)
        val next = session()
        val answer = lockItselfDuringAnAnswer(next)
        assertTrue(lock.leaseRunning.value)
        answer.complete(Unit)
        runCurrent()
        assertFalse("over with the answer", lock.leaseRunning.value)
        assertEquals(listOf("lease true", "lease false"), events.filter { it.startsWith("lease") })
        assertTrue(session.closed)
    }

    @Test
    fun withTheSettingOffTheAppsOwnLockStopsTheAnswerAsItAlwaysDid() = runTest(dispatcher) {
        startApp()
        val session = createPassphrase()
        lock.setFinishAnswerAfterLocking(false)
        assertFalse(lock.status.value.finishAnswerAfterLocking)
        assertFalse(storedConfig().finishAnswerAfterLocking)
        session.answer = CompletableDeferred()

        lock.onBackground()
        pass(60_000)
        assertTrue(lock.state.value is LockState.Locked)
        assertEquals(0, session.finishOnly)
        assertTrue(session.closed)
        assertDestroyed(session.cipher)

        // It is in the lock configuration, so a new process knows it while it is still locked.
        startApp()
        assertTrue(lock.state.value is LockState.Locked)
        assertFalse(lock.status.value.finishAnswerAfterLocking)
        enter(passphrase)
        lock.setFinishAnswerAfterLocking(true)
        assertTrue(lock.status.value.finishAnswerAfterLocking)
        assertTrue(storedConfig().finishAnswerAfterLocking)
    }

    @Test
    fun withNoAnswerBeingWrittenTheAppsOwnLockIsAsItAlwaysWas() = runTest(dispatcher) {
        startApp()
        val session = createPassphrase()
        // An answer that was finished before the lock is no reason either.
        session.answer = CompletableDeferred<Unit>().apply { complete(Unit) }
        lock.onBackground()
        pass(60_000)
        assertEquals(0, session.finishOnly)
        assertTrue(session.closed)
        assertDestroyed(session.cipher)
        assertFalse(lock.sessionEnding.value)
    }

    @Test
    fun theCheckOnComingBackToTheFrontLeavesTheAnswerRunningToo() = runTest(dispatcher) {
        startApp()
        val session = createPassphrase()
        session.answer = CompletableDeferred()
        lock.onBackground()
        // The timer could not run; the lock is found to be due when the app comes back.
        clock.advance(60_000)
        lock.onForeground()
        assertTrue(lock.state.value is LockState.Locked)
        runCurrent()
        assertEquals(1, session.finishOnly)
        assertFalse(session.closeStarted)
        // The ten minutes count from that moment.
        pass(AppLock.LEASE_MILLIS - 1)
        assertFalse(session.closeStarted)
        pass(1)
        assertTrue(session.closed)
    }

    @Test
    fun unlockingDuringALeaseChecksThePassphraseInFullAndGivesBackTheSameSession() = runTest(dispatcher) {
        startApp()
        val session = createPassphrase()
        val blob = session.cipher.seal("p", plaintext)
        val answer = lockItselfDuringAnAnswer(session)

        // A wrong passphrase first: it is counted on disk, as at any lock.
        enter(wrongPassphrase)
        assertTrue(lock.unlockUi.value.wrongPassphrase)
        assertEquals(1, storedConfig().failedAttempts)
        assertFalse(session.closeStarted)
        assertEquals(0, session.resumed)

        var keyStoreUses = 0
        vault.onUse = {
            keyStoreUses++
            insideTheManager()
        }
        enter(passphrase)
        assertTrue("the key was derived again, through the key store, for this unlock", keyStoreUses > 0)
        assertEquals("and the success ended the count, as it always does", 0, storedConfig().failedAttempts)
        assertSame("the same session, not a new one", session, session())
        assertEquals(1, sessions.size)
        assertEquals(1, session.resumed)
        assertFalse(session.closeStarted)
        assertFalse(lock.sessionEnding.value)
        assertArrayEquals(plaintext, session.cipher.open("p", blob))
        assertEquals(listOf("screens cleared", "finish only", "resumed"), log)

        // The answer finishing, and the lease's ten minutes passing, end nothing now: it is the session on screen.
        answer.complete(Unit)
        lock.onForeground()
        pass(AppLock.LEASE_MILLIS)
        assertFalse(session.closeStarted)
        assertSame(session, session())

        lock.lock()
        runCurrent()
        assertTrue(session.closed)
        assertDestroyed(session.cipher)
    }

    @Test
    fun wrongPassphrasesDuringALeaseAreSlowedDownAsAlwaysAndLeaveTheLeaseAlone() = runTest(dispatcher) {
        startApp()
        val session = createPassphrase()
        val answer = lockItselfDuringAnAnswer(session)

        repeat(5) { enter(wrongPassphrase) }
        assertEquals(lock.elapsedNow() + 30_000, lock.unlockUi.value.retryAtElapsed)
        assertEquals(5, storedConfig().failedAttempts)
        // The key is in memory, and still the right passphrase is refused unchecked during the wait.
        enter(passphrase)
        assertTrue(lock.state.value is LockState.Locked)
        assertEquals(0, session.resumed)
        assertFalse(session.closeStarted)
        assertTrue(lock.sessionEnding.value)

        pass(30_000)
        enter(passphrase)
        assertSame(session, session())
        assertEquals(0, storedConfig().failedAttempts)
        answer.complete(Unit)
    }

    @Test
    fun aFingerprintUnlockDuringALeaseGivesBackTheSameSessionToo() = runTest(dispatcher) {
        val launcher = FakeLauncher()
        val session = setUpWithFingerprint(launcher)
        log.clear()
        lockItselfDuringAnAnswer(session)

        lock.requestBiometricUnlock()
        runCurrent()
        assertEquals(BiometricPurpose.UNLOCK, launcher.purpose)
        // A prompt that is cancelled changes nothing.
        lock.onBiometricError(AppLock.ERROR_USER_CANCELED)
        runCurrent()
        assertTrue(lock.state.value is LockState.Locked)
        assertFalse(session.closeStarted)

        lock.requestBiometricUnlock()
        runCurrent()
        lock.onBiometricSucceeded(launcher.cipher)
        runCurrent()
        assertSame(session, session())
        assertEquals(1, sessions.size)
        assertEquals(listOf("screens cleared", "finish only", "resumed"), log)
    }

    @Test
    fun aResetDuringALeaseEndsItAndDeletesEverything() = runTest(dispatcher) {
        startApp()
        val session = createPassphrase()
        val stored = someStoredData()
        lockItselfDuringAnAnswer(session)
        vault.afterDelete = { log += "key deleted" }

        lock.reset() // On the lock screen, the passphrase forgotten.
        runCurrent()
        assertEquals(LockState.SetupRequired, lock.state.value)
        assertEquals(listOf("screens cleared", "finish only", "session closed", "session stopped", "key deleted"), log)
        assertDestroyed(session.cipher)
        assertFalse(lock.sessionEnding.value)
        for (file in stored) assertFalse(file.path, file.exists())
        nothingIsPersisted()
        pass(AppLock.LEASE_MILLIS)
        assertEquals("the lease left nothing behind that acts later", 5, log.size)
    }

    @Test
    fun theKeyOfALeasedSessionCannotBeWrappedAnew() = runTest(dispatcher) {
        biometric = BiometricStatus.AVAILABLE
        startApp()
        lock.attach(FakeLauncher())
        val session = createPassphrase()
        lock.declineFingerprintOffer()
        val proof = (lock.checkPassphrase(passphrase.toCharArray()) as PassphraseCheck.Ok).proof
        val before = files.lockConfig.readText()
        lockItselfDuringAnAnswer(session)

        assertFalse(lock.changePassphrase(proof, otherPassphrase.toCharArray()))
        assertFalse(lock.startBiometricEnrollment(proof))
        // The lock itself recorded the trusted time; nothing else was written.
        val after = Json.decodeFromString(LockConfig.serializer(), files.lockConfig.readText())
        assertEquals(Json.decodeFromString(LockConfig.serializer(), before).copy(trustedTime = after.trustedTime), after)
        assertTrue(vault.biometricKeys.isEmpty())
        assertFalse(session.closeStarted)
    }

    // --- Making way for the key derivation ------------------------------------------------------------

    /** Uses of the key store while nothing was standing aside: a key derivation the model was not paused for. */
    private var derivations = 0

    /** From now on every key derivation (seen at its last step, the binding to the key store) is checked against the pause. */
    private fun watchTheKeyDerivation() {
        vault.onBind = {
            derivations++
            if (standingAside != 1) keyStoreUsesInTheWay++
        }
    }

    @Test
    fun theModelStandsAsideForExactlyAsLongAsAPassphraseIsCheckedDuringALease() = runTest(dispatcher) {
        startApp()
        val session = createPassphrase()
        val answer = lockItselfDuringAnAnswer(session)
        madeWay.clear()
        watchTheKeyDerivation()

        enter(wrongPassphrase)
        assertEquals("once for the check, and over when the check is", listOf("stand aside", "carry on"), madeWay)
        assertEquals(0, standingAside)
        assertFalse("standing aside is not ending: the lease goes on", session.closeStarted)
        assertTrue(lock.sessionEnding.value)

        enter(passphrase)
        assertEquals(listOf("stand aside", "carry on", "stand aside", "carry on"), madeWay)
        assertSame(session, session())
        assertEquals("both attempts were really derived", 2, derivations)
        assertEquals("and neither with the model in its way", 0, keyStoreUsesInTheWay)
        assertEquals(listOf("screens cleared", "finish only", "resumed"), log)
        answer.complete(Unit)
    }

    @Test
    fun everyKeyDerivationHasTheModelStandAside() = runTest(dispatcher) {
        watchTheKeyDerivation()
        startApp()
        createPassphrase() // The first set-up derives a key too.
        assertEquals(listOf("stand aside", "carry on"), madeWay)

        // In Settings, while unlocked: the check of the current passphrase, right and wrong, and the change.
        madeWay.clear()
        assertEquals(PassphraseCheck.Wrong(0), lock.checkPassphrase(wrongPassphrase.toCharArray()))
        val proof = (lock.checkPassphrase(passphrase.toCharArray()) as PassphraseCheck.Ok).proof
        assertTrue(lock.changePassphrase(proof, otherPassphrase.toCharArray()))
        assertEquals(List(3) { listOf("stand aside", "carry on") }.flatten(), madeWay)

        // An unlock from an ordinary lock.
        lock.lock()
        runCurrent()
        madeWay.clear()
        enter(otherPassphrase)
        assertTrue(lock.state.value is LockState.Unlocked)
        assertEquals(listOf("stand aside", "carry on"), madeWay)

        assertEquals(0, standingAside)
        assertEquals("set-up, two checks, the change and the unlock", 5, derivations)
        assertEquals("none of them ran with the model in its way", 0, keyStoreUsesInTheWay)
    }

    @Test
    fun whatNeedsNoKeyDerivationAsksNothingToStandAside() = runTest(dispatcher) {
        val launcher = FakeLauncher()
        val session = setUpWithFingerprint(launcher)
        lockItselfDuringAnAnswer(session)
        madeWay.clear()
        watchTheKeyDerivation()

        // A fingerprint unlock derives nothing, and neither does changing a setting.
        lock.requestBiometricUnlock()
        runCurrent()
        lock.onBiometricSucceeded(launcher.cipher)
        runCurrent()
        assertSame(session, session())
        lock.setLockTimeout(300)
        lock.setFinishAnswerAfterLocking(false)
        lock.lock()
        runCurrent()
        assertEquals(0, derivations)
        assertEquals(emptyList<String>(), madeWay)
    }

    @Test
    fun aDerivationThatFailsStillLetsTheModelCarryOn() = runTest(dispatcher) {
        startApp()
        val session = createPassphrase()
        val answer = lockItselfDuringAnAnswer(session)
        madeWay.clear()

        // The attempt cannot be counted: the check throws before it has derived anything.
        val directory = files.lockConfig.parentFile!!
        directory.setWritable(false)
        try {
            enter(passphrase)
        } finally {
            directory.setWritable(true)
        }
        assertTrue(lock.unlockUi.value.error, lock.unlockUi.value.error!!.contains("Exception"))
        assertTrue(lock.state.value is LockState.Locked)
        assertEquals("the pause ended with the failure", listOf("stand aside", "carry on"), madeWay)
        assertEquals(0, standingAside)
        assertFalse("and the answer goes on behind the lock", session.closeStarted)

        // The same in Settings, where the failure reaches the caller.
        enter(passphrase)
        assertSame(session, session())
        madeWay.clear()
        directory.setWritable(false)
        try {
            assertFailsWith<IOException> { lock.checkPassphrase(passphrase.toCharArray()) }
        } finally {
            directory.setWritable(true)
        }
        assertEquals(listOf("stand aside", "carry on"), madeWay)
        assertEquals(0, standingAside)
        answer.complete(Unit)
    }

    /**
     * Standing aside is not time off the ceiling: a check that is still deriving when the lease's
     * ten minutes are up does not get the session back, although its passphrase was right.
     */
    @Test
    fun theTenMinutesGoOnCountingWhileTheModelStandsAside() = runTest(dispatcher) {
        startApp()
        val session = createPassphrase()
        lockItselfDuringAnAnswer(session)
        clock.advance(AppLock.LEASE_MILLIS - 1_000)
        madeWay.clear()
        // The derivation takes two seconds, and the lease has one left.
        vault.onBind = { if (standingAside == 1) clock.advance(2_000) }

        enter(passphrase)
        assertEquals(listOf("stand aside", "carry on"), madeWay)
        assertTrue("the lease was over: its session is closed", session.closed)
        assertDestroyed(session.cipher)
        assertEquals(0, session.resumed)
        assertTrue("and the right passphrase opened a new one", lock.state.value is LockState.Unlocked)
        assertEquals(2, sessions.size)
    }

    @Test
    fun lockNowAndAResetTakeEffectAlthoughTheModelIsStandingAside() = runTest(dispatcher) {
        startApp()
        val session = createPassphrase()
        session.answer = CompletableDeferred()
        var lockedDuringTheDerivation = false
        vault.onBind = {
            // "Lock now" is pressed while the passphrase is being checked in Settings.
            if (standingAside == 1 && !lockedDuringTheDerivation) {
                lockedDuringTheDerivation = true
                lock.lock()
            }
        }
        lock.checkPassphrase(passphrase.toCharArray())
        runCurrent()
        assertTrue(lockedDuringTheDerivation)
        assertTrue(lock.state.value is LockState.Locked)
        assertEquals(0, standingAside)
        assertTrue("the session was closed as soon as the check had returned", session.closed)
        assertDestroyed(session.cipher)
        assertEquals(0, session.finishOnly)

        // And a reset during a check at the lock screen, with a lease running.
        vault.onBind = {}
        enter(passphrase)
        val next = session()
        lockItselfDuringAnAnswer(next)
        var resetDuringTheDerivation = false
        vault.onBind = {
            if (standingAside == 1 && !resetDuringTheDerivation) {
                resetDuringTheDerivation = true
                lock.reset()
            }
        }
        enter(passphrase)
        assertTrue(resetDuringTheDerivation)
        assertEquals(LockState.SetupRequired, lock.state.value)
        assertEquals(0, standingAside)
        assertTrue(next.closed)
        assertDestroyed(next.cipher)
        nothingIsPersisted()
    }

    // --- The end of the process -----------------------------------------------------------------------

    @Test
    fun theProcessEndsOnceTheAppIsLockedHasNothingToFinishAndIsOutOfSight() = runTest(dispatcher) {
        startApp(visible = true)
        val session = createPassphrase()
        lock.onBackground()
        assertEquals("never while unlocked", 0, processEnds)
        pass(59_999)
        assertEquals(0, processEnds)
        pass(1)
        assertTrue(session.closed)
        assertDestroyed(session.cipher)
        assertEquals(1, processEnds)
        assertEquals("only after the session was closed and had stopped", listOf("screens cleared", "session closed", "session stopped"), logAtProcessEnd)
    }

    @Test
    fun theProcessNeverEndsWhileAnActivityIsOnScreen() = runTest(dispatcher) {
        startApp(visible = true)
        createPassphrase()
        lock.lock()
        runCurrent()
        enter(wrongPassphrase)
        pass(3_600_000)
        assertTrue(lock.state.value is LockState.Locked)
        assertEquals("the lock screen is being looked at", 0, processEnds)

        // A rotation, or the activity being built again, is not leaving the screen.
        lock.onActivityDestroyed(finished = false)
        runCurrent()
        assertEquals(0, processEnds)
        lock.onActivityCreated()
        runCurrent()
        assertEquals(0, processEnds)
        lock.onForeground()
        assertEquals(0, processEnds)

        lock.onBackground()
        assertEquals("leaving it is", 1, processEnds)
    }

    @Test
    fun theProcessDoesNotEndDuringALeaseAndEndsWhenTheAnswerIsSaved() = runTest(dispatcher) {
        startApp(visible = true)
        val session = createPassphrase()
        val answer = lockItselfDuringAnAnswer(session)
        pass(5 * 60_000L)
        assertEquals(0, processEnds)

        // A wrong passphrase typed meanwhile, and leaving again, change nothing about that.
        lock.onForeground()
        enter(wrongPassphrase)
        lock.onBackground()
        assertEquals(0, processEnds)

        answer.complete(Unit)
        runCurrent()
        assertTrue(session.closed)
        assertEquals(1, processEnds)
        assertEquals(listOf("screens cleared", "finish only", "session closed", "session stopped"), logAtProcessEnd)
    }

    @Test
    fun theProcessDoesNotEndBeforeTheLastWriteHasReturned() = runTest(dispatcher) {
        startApp(visible = true)
        val session = createPassphrase()
        val hung = CompletableDeferred<Unit>()
        session.hungWrite = hung
        lock.onBackground()
        pass(60_000)
        assertTrue("the session is closed and its key gone", session.closed)
        assertDestroyed(session.cipher)
        pass(3_600_000)
        assertEquals("but a write is still in the disk", 0, processEnds)

        hung.complete(Unit)
        runCurrent()
        assertEquals(1, processEnds)
        assertEquals("session stopped", logAtProcessEnd!!.last())
    }

    @Test
    fun theProcessDoesNotEndWhileAPassphraseIsBeingChecked() = runTest(dispatcher) {
        startApp(visible = true)
        createPassphrase()
        lock.lock()
        runCurrent()
        lock.unlockWithPassphrase(passphrase.toCharArray())
        // The user leaves while the key is being derived.
        lock.onBackground()
        assertEquals(0, processEnds)
        runCurrent()
        assertTrue("the unlock went through, out of sight", lock.state.value is LockState.Unlocked)
        assertEquals(0, processEnds)
        // Its timeout has been running since the app left; then it locks, closes and ends.
        pass(60_000)
        assertTrue(lock.state.value is LockState.Locked)
        assertEquals(1, processEnds)
    }

    /**
     * The security review's A2: a fingerprint prompt whose callback never arrives must not keep
     * the locked, idle process alive once nothing of the app is on screen. A callback that comes
     * after all is ignored.
     */
    @Test
    fun aFingerprintPromptThatNeverAnswersDoesNotKeepTheProcessAliveOutOfSight() = runTest(dispatcher) {
        val launcher = FakeLauncher()
        setUpWithFingerprint(launcher)
        lock.onActivityCreated()
        lock.onForeground()
        lock.lock()
        runCurrent()
        assertEquals("the lock screen is on screen", 0, processEnds)
        lock.requestBiometricUnlock()
        runCurrent()
        assertEquals(BiometricPurpose.UNLOCK, launcher.purpose)
        assertTrue(lock.unlockUi.value.biometricPromptShowing)

        // Home is pressed; the prompt goes, and its callback never comes.
        lock.onBackground()
        runCurrent()
        assertEquals(1, processEnds)
        assertFalse(lock.unlockUi.value.biometricPromptShowing)

        // A callback that arrives late after all opens nothing.
        lock.onBiometricSucceeded(launcher.cipher)
        runCurrent()
        assertTrue(lock.state.value is LockState.Locked)
        assertEquals(1, sessions.size)
    }

    @Test
    fun aFingerprintPromptIsLeftAloneWhileTheAppIsStillOnScreen() = runTest(dispatcher) {
        val launcher = FakeLauncher()
        setUpWithFingerprint(launcher)
        lock.onActivityCreated()
        lock.onForeground()
        lock.lock()
        runCurrent()
        lock.requestBiometricUnlock()
        runCurrent()
        // Another instance of the activity is on screen too; this one leaves.
        lock.onActivityCreated("other")
        lock.onForeground("other")
        lock.onBackground()
        assertTrue("something is still on screen: the prompt is still pending", lock.unlockUi.value.biometricPromptShowing)
        lock.onBiometricSucceeded(launcher.cipher)
        runCurrent()
        assertTrue(lock.state.value is LockState.Unlocked)
    }

    /**
     * The security review's B2: an activity that is created and destroyed by the system without
     * ever reaching the screen must not leave the process believing that one is on its way.
     */
    @Test
    fun anActivityDestroyedWithoutBeingShownDoesNotKeepTheProcessAlive() = runTest(dispatcher) {
        startApp(visible = true)
        createPassphrase()
        lock.lock()
        runCurrent()
        assertEquals(0, processEnds)

        // A second instance is made and thrown away before it is ever shown.
        lock.onActivityCreated("second")
        lock.onActivityDestroyed(finished = false, who = "second")
        lock.onBackground()
        assertEquals(1, processEnds)
    }

    @Test
    fun anActivityOnItsWayToTheScreenKeepsTheProcessUntilItArrivesOrGoes() = runTest(dispatcher) {
        startApp(visible = true)
        createPassphrase()
        lock.lock()
        runCurrent()
        lock.onActivityCreated("second")
        lock.onBackground()
        assertEquals("one is still on its way", 0, processEnds)
        lock.onForeground("second")
        lock.onBackground("second")
        assertEquals("it arrived and left", 1, processEnds)
    }

    /**
     * The third review's finding 3, the "or goes" order: the first activity leaves while a second
     * is on its way, and the second is then destroyed without ever being shown. Its destruction
     * looks again and ends the process.
     */
    @Test
    fun anActivityOnItsWayThatGoesUnseenAfterTheOtherLeftLetsTheProcessEnd() = runTest(dispatcher) {
        startApp(visible = true)
        createPassphrase()
        lock.lock()
        runCurrent()
        lock.onActivityCreated("second")
        lock.onBackground()
        assertEquals("one is still on its way", 0, processEnds)
        lock.onActivityDestroyed(finished = false, who = "second")
        assertEquals("it went without arriving", 1, processEnds)
    }

    /** Finding 3, with an unlock prompt pending: it is dropped once the activity on its way has gone unseen, and a late callback opens nothing. */
    @Test
    fun anUnlockPromptIsDroppedWhenTheActivityOnItsWayGoesUnseen() = runTest(dispatcher) {
        val launcher = FakeLauncher()
        setUpWithFingerprint(launcher)
        lock.onActivityCreated()
        lock.onForeground()
        lock.lock()
        runCurrent()
        lock.requestBiometricUnlock()
        runCurrent()
        lock.onActivityCreated("second")
        lock.onBackground()
        assertTrue("one is on its way: the prompt is left alone", lock.unlockUi.value.biometricPromptShowing)
        assertEquals(0, processEnds)

        lock.onActivityDestroyed(finished = false, who = "second")
        assertFalse(lock.unlockUi.value.biometricPromptShowing)
        assertEquals(1, processEnds)
        lock.onBiometricSucceeded(launcher.cipher)
        runCurrent()
        assertTrue(lock.state.value is LockState.Locked)
    }

    /**
     * What `successors` is for: between the destruction of an activity on screen (a rotation)
     * and the creation of its successor, something else looks whether the process is still
     * needed (here a lease ends as its answer is finished). Nothing is on screen or on its way
     * in that moment, and yet the process must not end.
     */
    @Test
    fun aRotationDuringWhichALeaseEndsDoesNotEndTheProcess() = runTest(dispatcher) {
        startApp(visible = true)
        val session = createPassphrase()
        val answer = lockItselfDuringAnAnswer(session)
        lock.onForeground() // Back at the lock screen while the answer is finished behind it.
        assertFalse(session.closeStarted)

        lock.onActivityDestroyed(finished = false)
        answer.complete(Unit)
        runCurrent()
        assertTrue("the lease ended and its session closed", session.closed)
        assertEquals("the successor is on its way", 0, processEnds)

        lock.onActivityCreated()
        lock.onForeground()
        runCurrent()
        assertEquals(0, processEnds)
        lock.onBackground()
        assertEquals(1, processEnds)
    }

    /**
     * The third review's finding 2: an enrolment prompt is open when the app locks by itself (the
     * user pressed Home on it). The lock drops it, so the locked, idle process still ends, and a
     * callback that arrives late is ignored without a message.
     */
    @Test
    fun anEnrolmentPromptOpenWhenTheAppLocksDoesNotKeepTheProcessAlive() = runTest(dispatcher) {
        biometric = BiometricStatus.AVAILABLE
        val launcher = FakeLauncher()
        startApp(visible = true)
        lock.attach(launcher)
        val session = createPassphrase()
        lock.declineFingerprintOffer()
        val proof = (lock.checkPassphrase(passphrase.toCharArray()) as PassphraseCheck.Ok).proof
        assertTrue(lock.startBiometricEnrollment(proof))
        assertEquals(BiometricPurpose.ENROLL, launcher.purpose)
        assertTrue(lock.unlockUi.value.biometricPromptShowing)

        lock.onBackground()
        pass(60_000)
        assertTrue(lock.state.value is LockState.Locked)
        assertDestroyed(session.cipher)
        assertFalse(lock.unlockUi.value.biometricPromptShowing)
        assertEquals(1, processEnds)
        assertTrue("the new key is gone", vault.biometricKeys.isEmpty())

        lock.onBiometricError(AppLock.ERROR_HW_UNAVAILABLE)
        lock.onBiometricSucceeded(launcher.cipher)
        runCurrent()
        assertNull("no stray message on the lock screen", lock.unlockUi.value.notice)
        assertFalse(lock.status.value.biometricEnabled)
        assertTrue(vault.biometricKeys.isEmpty())
    }

    @Test
    fun leavingTheLockScreenWithoutUnlockingEndsTheProcessToo() = runTest(dispatcher) {
        startApp()
        createPassphrase()
        startApp(visible = true)
        assertTrue(lock.state.value is LockState.Locked)
        assertEquals(0, processEnds)
        enter(wrongPassphrase)
        lock.onBackground()
        assertEquals("whatever was typed there is released with the process", 1, processEnds)
    }

    @Test
    fun firstRunSetUpDoesNotEndTheProcessButTheScreenAfterAResetDoes() = runTest(dispatcher) {
        startApp(visible = true)
        assertEquals(LockState.SetupRequired, lock.state.value)
        lock.onBackground()
        assertEquals("nothing has ever been decrypted in this process", 0, processEnds)
        lock.onForeground()

        createPassphrase()
        lock.reset()
        runCurrent()
        assertEquals(LockState.SetupRequired, lock.state.value)
        assertEquals(0, processEnds)
        lock.onBackground()
        assertEquals("a conversation has been in this process's memory", 1, processEnds)
    }

    // --- The screenshot allowance -------------------------------------------------------------------

    /** Every file the app has, with its content: what "nothing was written" is measured on. */
    private fun everythingOnDisk(): Map<String, List<Byte>> =
        listOf(files.filesDir, files.cacheDir).flatMap { root -> root.walkTopDown().filter { it.isFile }.map { it.path to it.readBytes().toList() } }.toMap()

    /** What the activity makes of its window, on an Android version that can honour an allowance. */
    private fun windowIsSecure(): Boolean =
        WindowSecurity.mustBeSecure(lock.state.value is LockState.Unlocked, lock.screenshots.current(), clock.elapsed, WindowSecurity.FIRST_SDK_HIDING_THE_PREVIEW_SEPARATELY)

    @Test
    fun aScreenshotAllowanceRunsOnUnderALockButTheLockScreenIsNeverCapturable() = runTest(dispatcher) {
        startApp()
        createPassphrase()
        assertTrue(windowIsSecure())
        assertTrue(lock.screenshots.grant())
        val allowed = lock.screenshots.allowed.value
        assertTrue(allowed != null)
        assertFalse("the unlocked app can be captured", windowIsSecure())

        // "Lock now": the allowance goes on counting, but where the passphrase is typed nothing is captured.
        lock.lock()
        assertTrue("from the moment the lock screen is the state, before anything else ran", windowIsSecure())
        runCurrent()
        assertTrue(lock.state.value is LockState.Locked)
        assertEquals(allowed, lock.screenshots.current())
        assertTrue(windowIsSecure())
        // A wrong passphrase, and the wait it may bring, change nothing about that.
        enter(wrongPassphrase)
        assertTrue(windowIsSecure())
        enter(passphrase)
        assertEquals(allowed, lock.screenshots.current())
        assertFalse("and it applies again after the unlock, while its time lasts", windowIsSecure())

        // Nor does the background timeout end it, or coming back.
        lock.onBackground()
        pass(60_000)
        assertTrue(lock.state.value is LockState.Locked)
        assertEquals(allowed, lock.screenshots.current())
        assertTrue(windowIsSecure())
        lock.onForeground()
        assertEquals(allowed, lock.screenshots.current())
        assertTrue(screenWatch.watching)
        assertTrue(windowIsSecure())

        // Only its own time does.
        pass(ScreenshotAllowance.DURATION_MILLIS - 60_000 - 1)
        assertEquals(allowed, lock.screenshots.allowed.value)
        pass(1)
        assertNull(lock.screenshots.allowed.value)
        assertFalse(screenWatch.watching)
        enter(passphrase)
        assertTrue("an unlock after its time finds nothing allowed", windowIsSecure())
    }

    @Test
    fun noScreenThatIsNotTheUnlockedAppCanBeCapturedWhateverTheAllowanceSays() = runTest(dispatcher) {
        // The allowance is granted here without regard to the state, which the screens never do:
        // the rule must hold even so.
        startApp()
        assertEquals(LockState.SetupRequired, lock.state.value)
        lock.screenshots.grant()
        assertTrue("the set-up screen, where a passphrase is typed twice", windowIsSecure())

        createPassphrase()
        lock.screenshots.grant()
        assertFalse(windowIsSecure())

        // The error screen, with Try again.
        lock.lock()
        runCurrent()
        openFails = IOException("disk full")
        enter(passphrase)
        assertTrue(lock.state.value is LockState.Failed)
        assertTrue(lock.screenshots.allowed.value != null)
        assertTrue(windowIsSecure())
        openFails = null

        // While the app starts.
        lock.retry()
        assertEquals(LockState.Starting, lock.state.value)
        assertTrue(windowIsSecure())
        runCurrent()
        assertTrue(lock.state.value is LockState.Locked)
        assertTrue(windowIsSecure())

        // A new process over a damaged configuration: the screen that only offers a reset.
        files.lockConfig.writeText("{ not json")
        startApp()
        assertEquals(LockState.Unrecoverable(UnrecoverableReason.CONFIG_UNREADABLE), lock.state.value)
        lock.screenshots.grant()
        assertTrue(windowIsSecure())
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

    // --- The expiry of messages while locked ------------------------------------------------------

    /**
     * Every start of the lock deletes what has expired without the key, under the lock's mutex
     * and before the lock screen can take a passphrase, with the readable copy of the limit and
     * the trusted time, which it stores. A first start has nothing to clean. Catches a clean-up
     * that runs outside the mutex (the log of unserialised uses), one that runs after an unlock
     * could begin, and a lock that does not record the trusted time.
     */
    @Test
    fun theLockedCleanUpRunsAtEveryStartBeforeAnythingCanBeUnlocked() = runTest(dispatcher) {
        startApp()
        assertEquals("nothing is set up, so nothing is swept", emptyList<Pair<Int, Long>>(), sweeps)
        createPassphrase()
        clock.wall += 5_000
        lock.lock()
        runCurrent()
        assertEquals("a lock records the trusted time", clock.wall, LockConfigStore(files.lockConfig).read()!!.trustedTime!!.wallMillis)

        // The next process; the date was set back meanwhile, which must not take the time back.
        val lockedAt = clock.wall
        clock.wall -= 86_400_000L
        clock.elapsed += 1_000
        log.clear()
        startApp()
        assertEquals(listOf(7 to lockedAt + 1_000), sweeps)
        assertTrue(lock.state.value is LockState.Locked)
        assertEquals(TimeMark(lockedAt + 1_000, clock.elapsed, 1), LockConfigStore(files.lockConfig).read()!!.trustedTime)
        enter(passphrase)
        assertEquals("the sweep came before the unlock", "expired data swept", log.first())
    }

    /**
     * The clean-up deletes nothing while a session is alive: here one whose last write is still
     * stuck in the disk, when Try again starts the lock over. Its files are that session's, and a
     * late write could put back what was deleted. The trusted time is still recorded.
     */
    @Test
    fun theLockedCleanUpIsSkippedWhileASessionIsStillAlive() = runTest(dispatcher) {
        startApp()
        val session = createPassphrase()
        val hung = CompletableDeferred<Unit>()
        session.hungWrite = hung
        lock.lock()
        runCurrent()

        openFails = IOException("disk full")
        enter(passphrase)
        assertTrue(lock.state.value is LockState.Failed)
        openFails = null
        clock.wall += 7_000
        lock.retry()
        runCurrent()
        assertTrue(lock.state.value is LockState.Locked)
        assertEquals("nothing swept while the old session can still write", emptyList<Pair<Int, Long>>(), sweeps)
        assertEquals(clock.wall, LockConfigStore(files.lockConfig).read()!!.trustedTime!!.wallMillis)

        hung.complete(Unit)
        runCurrent()
        startApp()
        assertEquals(1, sweeps.size)
    }

    /**
     * The readable copy follows the session's own setting at every unlock, whatever it said
     * before: the locked app deletes by it. Catches an unlock that leaves a stale copy.
     */
    @Test
    fun everyUnlockOverwritesTheReadableCopyFromTheRealSetting() = runTest(dispatcher) {
        startApp()
        sessionLifetime = 1
        createPassphrase()
        assertEquals(1, LockConfigStore(files.lockConfig).read()!!.messageLifetimeDays)

        lock.lock()
        runCurrent()
        // Changed behind the session's back, as a failed write of the copy would leave it.
        val store = LockConfigStore(files.lockConfig)
        store.write(store.read()!!.copy(messageLifetimeDays = 90))
        startApp()
        assertEquals(90, sweeps.single().first)
        sessionLifetime = LockConfig.MESSAGE_LIFETIME_NEVER
        enter(passphrase)
        assertEquals("the session was handed the copy as it stood, to fall back on", 90, copiesHandedIn.last())
        assertEquals(0, LockConfigStore(files.lockConfig).read()!!.messageLifetimeDays)
    }

    // --- Background maintenance ---------------------------------------------------------------

    /**
     * The job follows the switch: scheduled at every start with it on (the scheduler keeps a job
     * that exists, MaintenanceScheduleTest), cancelled with nothing set up, scheduled at set-up,
     * and cancelled and kept off when it is turned off, at every later start too.
     */
    @Test
    fun theJobIsScheduledWhileTheSwitchIsOnAndCancelledWhenItIsOff() = runTest(dispatcher) {
        startApp()
        assertEquals("nothing is set up", listOf(false), scheduled)
        createPassphrase()
        assertEquals(listOf(false, true), scheduled)
        assertTrue(lock.status.value.backgroundMaintenance)

        lock.setBackgroundMaintenance(false)
        runCurrent()
        assertEquals(listOf(false, true, false), scheduled)
        assertFalse(lock.status.value.backgroundMaintenance)
        assertFalse(LockConfigStore(files.lockConfig).read()!!.backgroundMaintenance)

        lock.lock()
        runCurrent()
        scheduled.clear()
        startApp()
        assertEquals("off stays off at the next start", listOf(false), scheduled)
        lock.setBackgroundMaintenance(true)
        runCurrent()
        assertEquals(listOf(false, true), scheduled)
    }

    /**
     * A process the platform started for the job: the lock's start is the work (one clean-up,
     * not two), the process is held until it is done, the job is told it is finished, and the
     * process ends only when the run is released, which the job service does from a later
     * message of the main thread than the platform's own "finished" message; then it ends once,
     * as it would have. Catches a second sweep, a process ended in the step in which the job is
     * told it is finished (on Android that kills it before the system hears it), and a release
     * that ends it twice.
     */
    @Test
    fun aRunThatStartedTheProcessWaitsForTheStartThenFinishesThenTheProcessEnds() = runTest(dispatcher) {
        startApp()
        createPassphrase()
        lock.lock()
        runCurrent()
        sweeps.clear()
        log.clear()

        var finished = 0
        var release: (() -> Unit)? = null
        startApp {
            lock.startMaintenance(startedForIt = true) { releaseIt ->
                finished++
                log += "job finished"
                release = releaseIt
            }
        }

        assertEquals(1, sweeps.size)
        assertEquals(1, finished)
        // The platform's own "finished" message is still in the queue: nothing may end the
        // process in the step in which the job was told it is finished.
        assertEquals("held until released", 0, processEnds)
        pass(60_000)
        assertEquals("held for as long as the release has not come", 0, processEnds)
        log += "released"
        release!!()
        assertEquals(1, processEnds)
        assertEquals("the process ended after the release", "released", logAtProcessEnd!!.last())
        release!!()
        runCurrent()
        assertEquals("a second release changes nothing", 1, processEnds)
    }

    /**
     * A process that was alive already: the run asks the lock for the clean-up itself, under the
     * mutex; with a session alive that only records the trusted time.
     */
    @Test
    fun aRunInAProcessThatWasAliveRunsTheCleanUpThroughTheLock() = runTest(dispatcher) {
        startApp(visible = true)
        createPassphrase()
        lock.lock()
        runCurrent()
        sweeps.clear()

        var finished = 0
        lock.startMaintenance(startedForIt = false) { release -> finished++; release() }
        runCurrent()
        assertEquals("the app is open and locked: the clean-up runs again", 1, sweeps.size)
        assertEquals(1, finished)
        assertEquals("on screen, so the process stays", 0, processEnds)

        // Unlocked: a session is alive, so only the trusted time is recorded.
        enter(passphrase)
        clock.wall += 9_000
        lock.startMaintenance(startedForIt = false) { release -> finished++; release() }
        runCurrent()
        assertEquals(1, sweeps.size)
        assertEquals(2, finished)
        assertEquals(clock.wall, LockConfigStore(files.lockConfig).read()!!.trustedTime!!.wallMillis)
        assertTrue(lock.state.value is LockState.Unlocked)
    }

    /**
     * The user opens the app while a run that started the process is under way: the run ends
     * as usual, the process is not ended while the app is on screen, and the passphrase opens
     * it once the lock's start (and its clean-up) is done.
     */
    @Test
    fun openingTheAppDuringARunEndsNothing() = runTest(dispatcher) {
        startApp()
        createPassphrase()
        lock.lock()
        runCurrent()
        var finished = 0
        startApp {
            lock.startMaintenance(startedForIt = true) { release -> finished++; release() }
            lock.onActivityCreated()
            lock.onForeground()
        }
        assertEquals(1, finished)
        assertEquals(0, processEnds)
        enter(passphrase)
        assertTrue(lock.state.value is LockState.Unlocked)
    }

    /** A run the platform stops early is not reported finished, and the process still ends when nothing needs it. */
    @Test
    fun aRunThatIsStoppedEarlyLetsGo() = runTest(dispatcher) {
        startApp()
        createPassphrase()
        lock.lock()
        runCurrent()
        var finished = 0
        startApp {
            val run = lock.startMaintenance(startedForIt = true) { release -> finished++; release() }
            run.cancel()
        }
        assertEquals(0, finished)
        assertEquals("a run stopped early holds nothing: the process ends as it would have", 1, processEnds)
    }
}
