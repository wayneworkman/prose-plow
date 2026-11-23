package us.theworkmans.proseplow.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the screen of the third-party notices shows. */
sealed interface ThirdPartyNoticesState {

    /** The text is being read. It takes a moment at most, so the screen shows nothing for it. */
    data object Loading : ThirdPartyNoticesState

    /** The notices, as the blocks the screen lays out. */
    data class Shown(val blocks: List<NoticesBlock>) : ThirdPartyNoticesState

    /** The text could not be read; the screen says so in place of an empty page. */
    data object Unavailable : ThirdPartyNoticesState
}

/**
 * Reads the third-party notices once, when the screen is opened, and holds them as the blocks
 * the screen shows.
 *
 * The text is fifty kilobytes that have to be read from the app's resources and taken apart
 * ([ThirdPartyNoticesText.blocks]); both happen on [ioDispatcher], so the screen opens at once
 * and the text appears when it is ready. The notices are public text and the same for every
 * user: nothing here touches a chat, a setting or a key.
 *
 * @param read returns the whole text of the notices. It is called once, on [ioDispatcher], and
 *   is not kept afterwards. Whatever it throws ends as [ThirdPartyNoticesState.Unavailable].
 */
class ThirdPartyNoticesViewModel(
    read: () -> String,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow<ThirdPartyNoticesState>(ThirdPartyNoticesState.Loading)
    val state: StateFlow<ThirdPartyNoticesState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = try {
                withContext(ioDispatcher) { ThirdPartyNoticesState.Shown(ThirdPartyNoticesText.blocks(read())) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ThirdPartyNoticesState.Unavailable
            }
        }
    }
}
