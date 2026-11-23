package us.theworkmans.proseplow.background

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Job

/**
 * What runs a maintenance job: the application, which hands the run to the lock. Here so that the
 * job service needs nothing of the app but this.
 */
interface MaintenanceHost {
    /**
     * Starts one run (main thread) and calls [onFinished] on the main thread when its work is
     * done, with `release`, which ends the run's hold on the process and must be called later,
     * from a message of the main thread of its own (see [MaintenanceJobService]). The returned
     * job is cancelled when the platform stops the run early; the hold is then released without
     * [onFinished].
     */
    fun startMaintenance(onFinished: (release: () -> Unit) -> Unit): Job
}

/**
 * Android's job scheduler as a [MaintenanceSlot]: one job, [JOB_ID], run by
 * [MaintenanceJobService]. Being kept across restarts of the phone is what needs
 * `RECEIVE_BOOT_COMPLETED`.
 */
class AndroidMaintenanceSlot(private val context: Context) : MaintenanceSlot {

    private val scheduler: JobScheduler? get() = context.getSystemService(JobScheduler::class.java)

    override fun pending(): ScheduledMaintenance? {
        val job = scheduler?.getPendingJob(JOB_ID) ?: return null
        if (job.service != ComponentName(context, MaintenanceJobService::class.java) || !job.isPeriodic) return null
        return ScheduledMaintenance(job.intervalMillis, job.isPersisted)
    }

    override fun schedule(intervalMillis: Long) {
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, MaintenanceJobService::class.java))
            .setPeriodic(intervalMillis)
            .setPersisted(true)
            .build()
        scheduler?.schedule(job)
    }

    override fun cancel() {
        scheduler?.cancel(JOB_ID)
    }

    private companion object {
        /** The app's only job. Permanent: a job scheduled under it by an earlier version is the same job. */
        const val JOB_ID = 1
    }
}

/**
 * The background maintenance job: a moment every few hours, also after a restart of the phone,
 * in which the app deletes what has expired without the key. It does nothing itself and knows
 * nothing of what the work is: it hands the run to the application ([MaintenanceHost]) and
 * tells the platform when that run is done. It never unlocks anything, loads no model and shows
 * no notification. Only the system can bind it (`BIND_JOB_SERVICE` in the manifest).
 *
 * The end of a run, in order: [jobFinished] queues the platform's own "finished" message on the
 * main looper (the call to the system happens when that message is handled); then the run's
 * `release` is posted to the same looper, behind it. Only `release` lets the app end its
 * process, so the system has heard that the job is finished before the process can go. No
 * delay is involved, only the order of the queue.
 *
 * Nothing can hold the process for ever: a run that the platform stops, or that is still going
 * when the service is destroyed, is cancelled, which releases its hold at once; a `release` that
 * was posted runs with the looper's next messages.
 */
class MaintenanceJobService : JobService() {

    private val mainThread = Handler(Looper.getMainLooper())

    private var run: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val host = application as? MaintenanceHost ?: return false
        run = host.startMaintenance { release ->
            jobFinished(params, false)
            mainThread.post(release)
        }
        return true
    }

    /** Ends a run that has not finished; one that has finished is done already. */
    override fun onDestroy() {
        run?.cancel()
        run = null
        super.onDestroy()
    }

    /** The platform stops the run early: it is let go, and the next one comes at its time. */
    override fun onStopJob(params: JobParameters): Boolean {
        run?.cancel()
        run = null
        return false
    }
}
