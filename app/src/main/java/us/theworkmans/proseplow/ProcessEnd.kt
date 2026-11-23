package us.theworkmans.proseplow

import android.content.Context
import android.os.Process
import us.theworkmans.proseplow.background.AnswerService
import us.theworkmans.proseplow.security.ProcessEnder

/**
 * Tells a process that was started to run instrumented tests from one a user started. The
 * tests' own code is loaded into the app's process for such a run, and only for such a run: no
 * build of the app contains the test libraries. So finding one of their classes means that this
 * process belongs to a test run, and it must never end itself.
 */
object InstrumentedRun {
    /** A class of the test libraries that every instrumented run loads. */
    const val MARKER = "androidx.test.platform.app.InstrumentationRegistry"

    fun detected(loader: ClassLoader, marker: String = MARKER): Boolean = try {
        Class.forName(marker, false, loader)
        true
    } catch (_: ClassNotFoundException) {
        false
    } catch (_: LinkageError) {
        // It is there, in whatever state: a test run.
        true
    }
}

/**
 * Ends the process, when the lock has found that nothing needs it any more (see
 * [us.theworkmans.proseplow.security.AppLock]). The process is killed, not exited: an exit runs
 * the destructors of the native library, which is not written to be torn down with its threads
 * alive; and nothing is left to save, because the lock only asks for this once every write has
 * returned. The task stays in the list of recent apps, and opening it starts the app afresh,
 * locked.
 *
 * Never in a process that runs instrumented tests ([InstrumentedRun]): the tests live in this
 * process, and the app under them may lock and leave the screen at any time.
 */
class AndroidProcessEnder(context: Context) : ProcessEnder {
    private val app = context.applicationContext

    override fun end() {
        if (InstrumentedRun.detected(app.classLoader)) return
        AnswerService.stop(app)
        Process.killProcess(Process.myPid())
    }
}
