package us.theworkmans.proseplow.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/**
 * Links that appear in a model's answer. The app itself never opens a connection; a link can
 * only be handed to another app, and only after the user has seen the address and said so.
 */
object Links {

    private val OPENABLE_SCHEMES = setOf("http", "https", "mailto")

    /** True for the kinds of link another app may be asked to open: web addresses and mail. */
    fun canOpen(uri: String): Boolean = uri.substringBefore(':', missingDelimiterValue = "").lowercase() in OPENABLE_SCHEMES

    /**
     * Hands [uri] to whichever app the phone opens such links with.
     *
     * @return false when it is not a link of a kind this app passes on, or no app took it.
     */
    fun open(context: Context, uri: String): Boolean {
        if (!canOpen(uri)) return false
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
