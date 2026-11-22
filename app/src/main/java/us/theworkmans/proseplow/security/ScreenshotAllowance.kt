package us.theworkmans.proseplow.security

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Tells one listener when the phone's screen turns off, for as long as it is asked to. On the
 * phone this is a broadcast receiver; the unit tests have their own.
 */
interface ScreenOffWatch {
    /**
     * From now on [onScreenOff] is called, on the main thread, every time the screen turns off.
     * Called again while watching, it only replaces the listener.
     */
    fun start(onScreenOff: () -> Unit)

    /** Stops watching: nothing is called any more and nothing stays registered with the system. */
    fun stop()
}

/** Screenshots are allowed until this moment; see [ScreenshotAllowance]. */
data class ScreenshotsAllowed(
    /** The [Clock.elapsedMillis] at which the allowance ends. This is the deadline that counts. */
    val untilElapsedMillis: Long,
    /**
     * The same moment by the wall clock as it stood when the allowance was given. Only for
     * telling the user when it ends; nothing is decided by it.
     */
    val untilWallMillis: Long,
)

/**
 * The user's permission to take screenshots and screen recordings of the app, which is always
 * temporary. Normally the window is secure and nothing can be captured. Turning the allowance
 * on opens it for [DURATION_MILLIS], and it ends at the earliest of:
 *
 * - that time being up;
 * - the phone's screen turning off ([ScreenOffWatch]);
 * - the user turning it off again ([end]);
 * - "Reset app data" (the lock calls [end]);
 * - the app's process ending. Nothing of this is ever stored: the allowance exists in memory
 *   only, so a fresh start is always secure from its first frame, and there is nothing in any
 *   file that could turn screenshots on.
 *
 * The app's own lock does not end it, and that is deliberate: "Lock now" and the background
 * timeout leave a running allowance alone, so the lock screen can be captured during it as
 * well (its passphrase field shows bullets in any case). Giving it again while it runs starts
 * the full time afresh.
 *
 * The time is measured on the clock that counts since boot ([Clock.elapsedMillis]), so changing
 * the phone's date or time cannot stretch it. A timer ends the allowance when it is due, but a
 * timer can be late (it does not run while the phone sleeps), so the deadline itself is looked
 * at wherever it matters: [current] ends an allowance whose time is up before it answers, the
 * activity asks it whenever it comes to the foreground, and [WindowSecurity.mustBeSecure]
 * compares the deadline with the time once more.
 *
 * Not thread-safe: everything here is called on the main thread.
 */
class ScreenshotAllowance(
    private val clock: Clock,
    private val screenOff: ScreenOffWatch,
    mainDispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private var timer: Job? = null

    private val _allowed = MutableStateFlow<ScreenshotsAllowed?>(null)

    /**
     * The running allowance, or null. It turns null by itself when the allowance ends, which is
     * what the window and the switch in Settings follow. Use [current] to decide anything.
     */
    val allowed: StateFlow<ScreenshotsAllowed?> = _allowed.asStateFlow()

    /**
     * Allows screenshots for [DURATION_MILLIS] from now; while an allowance runs, this starts
     * the time afresh. The screen is watched before anything is allowed: if that cannot be set
     * up, nothing is allowed and false is returned.
     */
    fun grant(): Boolean {
        try {
            screenOff.start(::end)
        } catch (_: Exception) {
            end()
            return false
        }
        timer?.cancel()
        _allowed.value = ScreenshotsAllowed(
            untilElapsedMillis = clock.elapsedMillis() + DURATION_MILLIS,
            untilWallMillis = clock.wallMillis() + DURATION_MILLIS,
        )
        timer = scope.launch {
            // When the timer fires the allowance is over, whatever any clock says: ending is
            // always the safe direction. A timer that fires late is what current() is for.
            delay(DURATION_MILLIS)
            end()
        }
        return true
    }

    /**
     * Ends the allowance now, whatever is left of it: the user turned it off, the screen turned
     * off, or the app's data is being reset. Does nothing if none is running.
     */
    fun end() {
        timer?.cancel()
        timer = null
        if (_allowed.value == null) return
        _allowed.value = null
        try {
            screenOff.stop()
        } catch (_: Exception) {
            // Nothing is allowed any more either way; a listener that is still called finds nothing to end.
        }
    }

    /**
     * The allowance that is running right now, or null. One whose time is up is ended here
     * first, so a timer that did not fire in time (the phone slept) cannot keep it alive.
     */
    fun current(): ScreenshotsAllowed? {
        val running = _allowed.value ?: return null
        if (clock.elapsedMillis() < running.untilElapsedMillis) return running
        end()
        return null
    }

    companion object {
        /** How long one allowance lasts. Also named, in minutes, in the switch's title. */
        const val DURATION_MINUTES = 10
        const val DURATION_MILLIS = DURATION_MINUTES * 60_000L
    }
}

/**
 * Whether the app's window must be secure, which is what keeps its content out of screenshots
 * and screen recordings (`FLAG_SECURE` on Android). Plain Kotlin, so the whole rule is unit
 * tested; the Android side only applies the answer.
 *
 * The window is secure unless an allowance is running at this very moment: it is secure from
 * the first frame of every start, before the app is set up, and at every time at which no
 * [ScreenshotAllowance] has been given or the one given is over. The answer is for the whole
 * window, the set-up and lock screens included, and dialogs and menus take it over from the
 * window when they open.
 *
 * The preview that the app switcher shows of every app is a different matter and is never
 * allowed: it appears without the user doing anything, so it stays hidden whatever is allowed
 * here. Android can hide it by itself from version 13. Before that the only thing that hides
 * it is the secure window, so on those versions the window stays secure and an allowance has
 * no effect ([screenshotsCanBeAllowed]).
 */
object WindowSecurity {
    /** Android 13 (API 33): the first version on which the app switcher's preview can be turned off by itself. */
    const val FIRST_SDK_HIDING_THE_PREVIEW_SEPARATELY = 33

    /** Whether an allowance can be honoured on Android version [sdk] without the app showing up in the app switcher. */
    fun screenshotsCanBeAllowed(sdk: Int): Boolean = sdk >= FIRST_SDK_HIDING_THE_PREVIEW_SEPARATELY

    /**
     * Whether the window must be secure at [nowElapsedMillis] ([Clock.elapsedMillis]) on Android
     * version [sdk], given the allowance that is known of ([allowed], null: none).
     */
    fun mustBeSecure(allowed: ScreenshotsAllowed?, nowElapsedMillis: Long, sdk: Int): Boolean =
        !(allowed != null && nowElapsedMillis < allowed.untilElapsedMillis && screenshotsCanBeAllowed(sdk))
}
