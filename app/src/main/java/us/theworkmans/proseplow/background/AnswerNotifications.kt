package us.theworkmans.proseplow.background

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import us.theworkmans.proseplow.R

/**
 * The app's two notifications. Both are one fixed sentence and nothing else: no word of an
 * answer, no title of a chat, no name of a model ever goes into a notification, because a
 * notification is shown outside the app, on the lock screen of the phone and to whatever reads
 * notifications there. A test that reads this file holds it to that.
 */
object AnswerNotifications {
    const val WRITING_ID = 1
    const val READY_ID = 2

    private const val WRITING_CHANNEL = "answer_writing"
    private const val READY_CHANNEL = "answer_ready"

    private fun channels(context: Context) {
        val writing = NotificationChannelCompat.Builder(WRITING_CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(context.getString(R.string.channel_answer_writing))
            .setShowBadge(false)
            .build()
        val ready = NotificationChannelCompat.Builder(READY_CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName(context.getString(R.string.channel_answer_ready))
            .build()
        NotificationManagerCompat.from(context).createNotificationChannelsCompat(listOf(writing, ready))
    }

    /** Opens the app as its icon does: the task that is there comes to the front, at its lock screen if it is locked. */
    private fun openApp(context: Context): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** The ongoing notification of the answer service: silent, and gone with the service. */
    fun writing(context: Context): Notification {
        channels(context)
        return NotificationCompat.Builder(context, WRITING_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.answer_writing))
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openApp(context))
            .build()
    }

    private fun ready(context: Context): Notification {
        channels(context)
        return NotificationCompat.Builder(context, READY_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.answer_ready))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
    }

    private fun mayPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Shows "Your answer is ready." unless the user has not allowed notifications, in which case nothing happens. */
    fun showReady(context: Context) {
        if (!mayPost(context)) return
        try {
            NotificationManagerCompat.from(context).notify(READY_ID, ready(context))
        } catch (_: SecurityException) {
            // The permission went away between the check and the call. Nothing depends on this notification.
        }
    }

    fun clearReady(context: Context) {
        NotificationManagerCompat.from(context).cancel(READY_ID)
    }
}

/** [ReadyNotice] as Android shows it. */
class AndroidReadyNotice(context: Context) : ReadyNotice {
    private val app = context.applicationContext

    override fun show() = AnswerNotifications.showReady(app)

    override fun clear() = AnswerNotifications.clearReady(app)
}
