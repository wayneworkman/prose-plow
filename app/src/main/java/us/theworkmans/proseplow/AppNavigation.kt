package us.theworkmans.proseplow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import us.theworkmans.proseplow.chat.ChatController
import us.theworkmans.proseplow.chat.ChatScreen
import us.theworkmans.proseplow.chat.ChatViewModel
import us.theworkmans.proseplow.lock.SecurityViewModel
import us.theworkmans.proseplow.security.AppLock
import us.theworkmans.proseplow.settings.AboutScreen
import us.theworkmans.proseplow.settings.SettingsScreen
import us.theworkmans.proseplow.settings.SettingsViewModel
import us.theworkmans.proseplow.settings.ThirdPartyNoticesScreen
import us.theworkmans.proseplow.settings.ThirdPartyNoticesViewModel
import us.theworkmans.proseplow.settings.readThirdPartyNotices

@Serializable
private data object ChatRoute

@Serializable
private data object SettingsRoute

@Serializable
private data object AboutRoute

@Serializable
private data object ThirdPartyNoticesRoute

/**
 * The unlocked app: the chat, with Settings, About and the third-party notices behind it. It is
 * composed only while [session] is open, under a ViewModelStoreOwner that is the session's, so a
 * lock clears every screen's ViewModel. The chats and the reply that may be running are not in
 * any ViewModel: they are the session's ([AppSession.chat]) and carry on whichever screen is in
 * front.
 *
 * Two things here belong to the whole unlocked app rather than to one screen:
 * - The screen stays on while a reply is being written, on any of the screens.
 * - When the app leaves the screen, what is waiting to be written is written at once, because
 *   the process may be killed before the lock gets to close the session.
 */
@Composable
fun AppNavigation(session: AppSession, appLock: AppLock<*>) {
    KeepScreenOnWhileGenerating(session.chat)
    LifecycleStartEffect(session) {
        onStopOrDispose { session.flushWrites() }
    }

    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = ChatRoute) {
        composable<ChatRoute> {
            val chatViewModel: ChatViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        ChatViewModel(
                            controller = session.chat,
                            settings = session.settings,
                            writeFailure = session.writeFailure,
                            onWriteFailureShown = session::writeFailureShown,
                        )
                    }
                },
            )
            ChatScreen(
                viewModel = chatViewModel,
                onOpenSettings = dropUnlessResumed { navController.navigate(SettingsRoute) },
                onLock = appLock::lock,
            )
        }
        composable<SettingsRoute> {
            val settingsViewModel: SettingsViewModel = viewModel(
                factory = viewModelFactory { initializer { SettingsViewModel(session.settings, session.models) } },
            )
            val securityViewModel: SecurityViewModel = viewModel(
                factory = viewModelFactory { initializer { SecurityViewModel(appLock) } },
            )
            SettingsScreen(
                viewModel = settingsViewModel,
                security = securityViewModel,
                onBack = dropUnlessResumed { navController.popBackStack() },
                onOpenAbout = dropUnlessResumed { navController.navigate(AboutRoute) },
            )
        }
        composable<AboutRoute> {
            AboutScreen(
                models = session.models,
                onBack = dropUnlessResumed { navController.popBackStack() },
                onOpenNotices = dropUnlessResumed { navController.navigate(ThirdPartyNoticesRoute) },
            )
        }
        composable<ThirdPartyNoticesRoute> {
            val resources = LocalResources.current
            val noticesViewModel: ThirdPartyNoticesViewModel = viewModel(
                factory = viewModelFactory { initializer { ThirdPartyNoticesViewModel(read = { readThirdPartyNotices(resources) }) } },
            )
            ThirdPartyNoticesScreen(viewModel = noticesViewModel, onBack = dropUnlessResumed { navController.popBackStack() })
        }
    }
}

/**
 * Keeps the screen on for as long as a reply is being written, so that a long answer is not cut
 * off by the phone going to sleep (and, with it, the app locking). A composable of its own, so
 * that the stream of the reply's words recomposes nothing else.
 */
@Composable
private fun KeepScreenOnWhileGenerating(chat: ChatController) {
    val generating by remember(chat) { chat.state.map { it.turn != null }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = false)
    val view = LocalView.current
    DisposableEffect(view, generating) {
        view.keepScreenOn = generating
        onDispose { view.keepScreenOn = false }
    }
}
