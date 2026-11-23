package us.theworkmans.proseplow.background

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps the process alive and the processor awake while an answer is written out of sight: the
 * app in the background, or the phone's screen off. It does nothing itself. The answer is
 * written by the unlocked session; this service only tells Android that the process is doing
 * something the user asked for, with an ongoing notification that says so and no more, and
 * holds a partial wake lock for the same time.
 *
 * It is started when an answer begins while the app is in front, and it stops itself the moment
 * [KeepAliveSource.keepAliveWanted] turns false. It is started as an ordinary service
 * (`startService`), which Android allows while the app is in front, and puts itself in the
 * foreground at once when it is created. Not with `startForegroundService`: a service started
 * that way must reach the foreground before it may stop, and one whose `startForeground` was
 * refused could then only stop by crashing the app. Started ordinarily, a refusal is only a
 * refusal: the service tells [KeepAliveSource.onKeepAliveRefused] and stops. Only once it is in
 * the foreground does it say that the keep-alive runs ([KeepAliveSource.onKeepAlive]), and then
 * it looks whether it is still wanted, which also covers an answer that was over before the
 * service existed. Nothing stops it from outside except the end of the process.
 *
 * The type is `specialUse` (Android 14 and later demand a type). None of the named types
 * describes running a language model on the device at the user's request: it is not a transfer
 * of data, not media, not a call; and `shortService` ends after about three minutes, which an
 * answer on a phone can exceed. The manifest states the use in the property Android defines
 * for this type. Before Android 14 the type does not exist and none is passed.
 *
 * Not exported, and not restarted by the system: if the process dies, the answer died with it,
 * and the next start of the app is locked.
 */
class AnswerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null

    /** Set once this service has said that it runs in the foreground, so that its end is said too. */
    private var told: KeepAliveSource? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onCreate() {
        super.onCreate()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        val source = application as? KeepAliveSource
        // A refusal (the app had already left the screen) is told to the source: the answer goes on without this service.
        val wanted = KeepAliveEntry.enter(source) {
            ServiceCompat.startForeground(this, AnswerNotifications.WRITING_ID, AnswerNotifications.writing(this), type)
        }
        if (wanted == null) {
            stopSelf()
            return
        }
        told = source
        stayAwake()
        scope.launch {
            wanted.first { !it }
            ServiceCompat.stopForeground(this@AnswerService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * Holds a partial wake lock for as long as the service lives. It is taken with a time limit
     * and taken again before the limit is reached, so that it can never outlive a service that
     * ended without [onDestroy] by more than that limit.
     */
    private fun stayAwake() {
        val lock = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG) ?: return
        lock.setReferenceCounted(false)
        wakeLock = lock
        scope.launch {
            while (true) {
                lock.acquire(WAKE_LOCK_MILLIS)
                delay(WAKE_LOCK_RENEW_MILLIS)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        told?.onKeepAlive(false)
        told = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val WAKE_LOCK_TAG = "proseplow:answer"
        private const val WAKE_LOCK_MILLIS = 120_000L
        private const val WAKE_LOCK_RENEW_MILLIS = 60_000L

        /**
         * Asks Android to start the service, as an ordinary service (see the class comment);
         * false when it will not (the app is not in front). That it is in the foreground is
         * known only when it says so.
         */
        fun start(context: Context): Boolean = try {
            context.startService(Intent(context, AnswerService::class.java))
            true
        } catch (_: RuntimeException) {
            false
        }

        /** For the end of the process: the service is not left for Android to find without its process. */
        fun stop(context: Context) {
            context.stopService(Intent(context, AnswerService::class.java))
        }
    }
}
