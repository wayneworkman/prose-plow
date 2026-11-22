package us.theworkmans.proseplow

import android.app.Application

/**
 * The application object, empty for now. It exists from the start because it is named in the
 * manifest and is where the hand-wired app-wide singletons (the lock, the encrypted stores, the
 * inference engine) will be created, so that they outlive the activity.
 */
class ProsePlowApp : Application()
