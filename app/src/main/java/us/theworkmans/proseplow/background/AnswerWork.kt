package us.theworkmans.proseplow.background

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What an unlocked session says about the answer it is writing. Main thread. */
interface AnswerEvents {
    /** An answer has begun to be written ([writing] true), or none is being written any more. */
    fun onWriting(writing: Boolean)

    /** The model has finished an answer by itself: not stopped, not failed. */
    fun onReplyFinished()

    /** For a session nobody listens to. */
    object None : AnswerEvents {
        override fun onWriting(writing: Boolean) = Unit

        override fun onReplyFinished() = Unit
    }
}

/** Starts what keeps the process alive and awake while it works out of sight: Android's part of [AnswerWork]. */
fun interface KeepAliveStarter {
    /** False when the system would not start it. The answer is written all the same, as far as the system lets the process run. */
    fun start(): Boolean
}

/** The notification that an answer is ready: Android's part of [AnswerWork]. */
interface ReadyNotice {
    fun show()

    fun clear()
}

/** Where the answer service finds out whether it is still wanted, and says whether it runs, without making anything exist that does not yet. */
interface KeepAliveSource {
    /** Null when nothing in this process has ever written an answer. */
    val keepAliveWanted: StateFlow<Boolean>?

    /**
     * The answer service has reached the foreground ([running] true), or is leaving it or has
     * ended (false). Only a service in the foreground keeps the process alive and awake, so only
     * this says that the keep-alive exists. The service says false before it leaves the
     * foreground, not afterwards, so that nothing believes in a keep-alive that is already gone.
     * Ignored when nothing in this process has written an answer.
     */
    fun onKeepAlive(running: Boolean)

    /**
     * Android refused to put the answer service in the foreground. Nothing keeps the process
     * alive, so nothing may go on believing that something does: the answer is written as far as
     * the system lets the process run, and a lock does not leave its session alive for it.
     */
    fun onKeepAliveRefused()
}

/**
 * What Android says about the keep-alive at this moment, asked rather than remembered: a service
 * can be taken out of the foreground without being told, so a flag set when it got there is not
 * enough. Main thread.
 */
interface KeepAliveProbe {
    /**
     * The user has restricted the app's background use (`ActivityManager.isBackgroundRestricted`).
     * Android then takes the answer service out of the foreground as soon as the app leaves the
     * top, without a callback, and stops it a minute later.
     */
    fun backgroundRestricted(): Boolean

    /** Android has the answer service in the foreground now. */
    fun serviceInForeground(): Boolean
}

/** What the answer service does when it is created, apart from Android: plain Kotlin, so that it is tested. */
object KeepAliveEntry {
    /**
     * Puts the service in the foreground through [startForeground], first and at once, and tells
     * [source] how that went. Returns what the service is to watch, or null when it is to stop
     * at once: when Android refused, and when nothing in the process has ever written an answer
     * (no [source], or one with no flow). A refusal is a [RuntimeException] from
     * [startForeground], and equally a call that returned without effect, which Android does
     * when its app-op is set to ignore or the app's background use is restricted and the app is
     * not on top: so success is only what [inForeground] confirms afterwards.
     */
    fun enter(source: KeepAliveSource?, inForeground: () -> Boolean, startForeground: () -> Unit): StateFlow<Boolean>? {
        val inTheForeground = try {
            startForeground()
            inForeground()
        } catch (_: RuntimeException) {
            false
        }
        if (!inTheForeground) {
            source?.onKeepAliveRefused()
            return null
        }
        val wanted = source?.keepAliveWanted ?: return null
        source.onKeepAlive(true)
        return wanted
    }
}

/**
 * Decides when the process must be kept alive and awake although nobody may be looking, and
 * when to say that an answer is ready. Plain Kotlin, so that the rules are tested; what it
 * decides is carried out by the answer service and the notifications. Main thread only.
 *
 * - **Kept alive exactly while there is something to finish.** [keepAliveWanted] turns true
 *   when an answer begins to be written, and the app is in front (Android lets a foreground
 *   service be started from nowhere else). It turns false when no answer is being written any
 *   more and no session is still ending behind the lock: between the last word of an answer
 *   and the destruction of the data key the session is saved and closed, and a process that
 *   was put to sleep in that moment would keep the key in memory.
 * - **A keep-alive exists only once the service says so, and only while Android agrees.**
 *   [keepAliveRunning] is true from the moment the answer service has confirmed that it is in
 *   the foreground until it says that it is leaving it. A refusal, or the end of the service
 *   while it was still wanted, turns [keepAliveWanted] false at once, and the app asks again
 *   when it next comes to the front with the answer still being written. The lock leaves a
 *   session alive behind it only when [canFinishOutOfSight] holds at that moment: without the
 *   service in the foreground the process may be frozen, and with it every timer that would end
 *   the session. And whoever was told through [whenKeepAliveEnds] hears of every end, so that a
 *   session left alive on the strength of the keep-alive does not outlive it.
 * - **"Your answer is ready" only for someone who is not looking.** It is shown when the model
 *   finished an answer by itself while the app was not in front, and taken away when the app
 *   comes to the front. It never says anything about the answer.
 */
class AnswerWork(
    private val keepAlive: KeepAliveStarter,
    private val readyNotice: ReadyNotice,
    private val probe: KeepAliveProbe,
) : AnswerEvents {
    private var writing = false
    private var sessionEnding = false
    private var inFront = false
    private var keepAliveEnded: () -> Unit = {}

    private val _keepAliveWanted = MutableStateFlow(false)

    /** The answer service runs while this is true, and stops itself when it turns false. */
    val keepAliveWanted: StateFlow<Boolean> = _keepAliveWanted.asStateFlow()

    /** True while the answer service says it is in the foreground: what keeps the process alive and awake runs, as far as the service knows. */
    var keepAliveRunning: Boolean = false
        private set

    /**
     * Whether something keeps the process alive and awake out of sight at this moment: the
     * service has said that it runs, the user has not restricted the app's background use, and
     * Android has the service in the foreground now. The one place where the lock's question is
     * answered (it is asked at the moment the app locks by itself); the flag alone is not
     * enough, because Android takes a restricted app's service out of the foreground when the
     * app leaves the top without telling it.
     */
    fun canFinishOutOfSight(): Boolean = keepAliveRunning && !probe.backgroundRestricted() && probe.serviceInForeground()

    /**
     * [action] is called on every end of the keep-alive (a refusal, or the service leaving the
     * foreground or ending), on the main thread, at once. One listener: the lock, which ends a
     * session it left alive on the strength of the keep-alive.
     */
    fun whenKeepAliveEnds(action: () -> Unit) {
        keepAliveEnded = action
    }

    /**
     * The answer service confirmed the foreground ([running] true), or is leaving it or has ended
     * (false). See [KeepAliveSource.onKeepAlive]. An end while the keep-alive was still wanted
     * means that it was stopped from outside: nothing is wanted any more either, so that the
     * next answer, or coming back to the front, asks again.
     */
    fun onKeepAlive(running: Boolean) {
        keepAliveRunning = running
        if (!running) {
            _keepAliveWanted.value = false
            keepAliveEnded()
        }
    }

    /** Android refused the foreground: nothing is kept alive, and nothing is wanted until it is asked for again. */
    fun onKeepAliveRefused() {
        keepAliveRunning = false
        _keepAliveWanted.value = false
        keepAliveEnded()
    }

    override fun onWriting(writing: Boolean) {
        if (this.writing == writing) return
        this.writing = writing
        if (!writing) settle() else askForKeepAlive()
    }

    /**
     * Starts the keep-alive if an answer is being written, none is wanted yet, and the app is in
     * front (Android lets a foreground service be started from nowhere else).
     */
    private fun askForKeepAlive() {
        if (!writing || _keepAliveWanted.value || !inFront) return
        // Wanted before it is started: the service looks at this the moment it exists.
        _keepAliveWanted.value = true
        if (!keepAlive.start()) _keepAliveWanted.value = false
    }

    /** A session that has left the screen is still alive ([ending] true), or none is. */
    fun onSessionEnding(ending: Boolean) {
        sessionEnding = ending
        settle()
    }

    private fun settle() {
        if (!writing && !sessionEnding) _keepAliveWanted.value = false
    }

    override fun onReplyFinished() {
        if (!inFront) readyNotice.show()
    }

    /**
     * The app's screen came to the front, or left it. Coming to the front with an answer still
     * being written and no keep-alive wanted (it was refused, could not be started, or was
     * stopped from outside) asks for it again: only now may it be started.
     */
    fun onInFront(inFront: Boolean) {
        this.inFront = inFront
        if (inFront) {
            readyNotice.clear()
            askForKeepAlive()
        }
    }
}

/** When the app asks for permission to post notifications. */
object NotificationPermission {
    /** Android 13: the first version on which posting a notification needs the user's permission. */
    const val FIRST_SDK_ASKING = 33

    /**
     * Once, and only where it means something: on a version that asks at all, while the
     * permission is not given, and never a second time, whatever the answer was. Without the
     * permission everything works; the two notifications are simply not shown.
     */
    fun shouldAsk(sdk: Int, granted: Boolean, askedBefore: Boolean): Boolean = sdk >= FIRST_SDK_ASKING && !granted && !askedBefore
}
