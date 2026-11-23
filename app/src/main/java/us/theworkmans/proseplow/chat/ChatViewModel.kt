package us.theworkmans.proseplow.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import us.theworkmans.proseplow.core.chat.ChatErrorKind
import us.theworkmans.proseplow.core.chat.ChatSession
import us.theworkmans.proseplow.core.chat.MessageStatus
import us.theworkmans.proseplow.core.chat.Role
import us.theworkmans.proseplow.core.chat.toChatError
import us.theworkmans.proseplow.core.engine.EngineState
import us.theworkmans.proseplow.core.prompt.PromptWhitespace
import us.theworkmans.proseplow.data.SettingsStore

/** What the reply that is being written in the chat on screen is waiting for. */
sealed interface ReplyStatus {

    /** Its model is being loaded; [progress] runs from 0 to 1. */
    data class LoadingModel(val modelId: String, val progress: Float) : ReplyStatus

    /** The engine is getting ready (it may still be ending the reply before). */
    data object Preparing : ReplyStatus

    /** The model's saved memory of the conversation is being read back, in place of reading the conversation again. */
    data object Restoring : ReplyStatus

    /** The conversation is being read into the model's memory; [fraction] runs from 0 to 1. */
    data class Reading(val fraction: Float) : ReplyStatus

    /** The model is writing. */
    data object Writing : ReplyStatus
}

/** What the strip under the top bar says about the model of the chat on screen. */
sealed interface ModelBanner {

    /** A model is being loaded, for no reply in particular. */
    data class Loading(val modelId: String, val progress: Float) : ModelBanner

    /** The chat's model could not be loaded; [detail] is technical. */
    data class Failed(val modelId: String, val detail: String) : ModelBanner

    /** The chat's model is not part of this build, so another has to be chosen before it can go on. */
    data class NotOffered(val modelId: String) : ModelBanner

    /** This build carries no model at all: chats can be read, and nothing can be answered. */
    data object NoModels : ModelBanner
}

/** A chat the user has asked to delete, until they confirm or cancel. */
data class DeleteRequest(val id: String, val title: String)

/**
 * What the chat screen and the list of chats show.
 *
 * @property chat the controller's state: the chats, the one on screen, the reply in flight.
 * @property editingMessageId the user message whose text is in the input field to be changed.
 * @property deleteRequest set while the "Delete chat?" dialog is showing.
 * @property linkRequest a link in an answer that the user tapped, while the app asks what to do
 *   with it.
 * @property writeFailure what went wrong with a write that failed, until it has been said.
 * @property settingsIssue set when the stored settings could not be read completely.
 */
data class ChatUiState(
    val chat: ChatState,
    val editingMessageId: String? = null,
    val deleteRequest: DeleteRequest? = null,
    val linkRequest: String? = null,
    val writeFailure: String? = null,
    val settingsIssue: SettingsStore.LoadIssue? = null,
) {
    /** The chat on screen, or null when it is one that cannot be read. */
    val session: ChatSession? get() = (chat.open as? OpenChat.Ready)?.session

    /** True while a reply is being written in the chat on screen. */
    val generating: Boolean get() = session?.isGenerating == true

    /** True when the chat on screen can take a message: it is readable and its model is in this build. */
    val canWrite: Boolean get() = session?.let { chat.offers(it.modelId) } == true

    /** How many of the oldest messages the latest reply in this chat did not see; null when it saw all. */
    val trimmedMessages: Int? get() = chat.trimmedMessages[chat.open.id]?.takeIf { it > 0 }

    /** What became of the model's context for the latest reply in this chat; null when nothing did. */
    val contextNote: ContextNote? get() = chat.contextNotes[chat.open.id]

    /** What the reply being written here is waiting for; null when none is being written here. */
    val replyStatus: ReplyStatus?
        get() {
            val turn = chat.turn?.takeIf { it.sessionId == chat.open.id } ?: return null
            return when (val phase = turn.phase) {
                TurnPhase.Preparing -> (chat.engine as? EngineState.Loading)?.let { ReplyStatus.LoadingModel(it.modelId, it.progress) }
                    ?: if (chat.restoringMemory) ReplyStatus.Restoring else ReplyStatus.Preparing
                is TurnPhase.ReadingPrompt ->
                    ReplyStatus.Reading(if (phase.totalTokens > 0) (phase.evaluatedTokens.toFloat() / phase.totalTokens).coerceIn(0f, 1f) else 0f)
                TurnPhase.Writing -> ReplyStatus.Writing
            }
        }

    /** What to say about the model under the top bar; null when there is nothing to say. */
    val modelBanner: ModelBanner?
        get() {
            val session = session ?: return null
            if (chat.models.isEmpty()) return ModelBanner.NoModels
            if (!chat.offers(session.modelId)) return ModelBanner.NotOffered(session.modelId)
            return when (val engine = chat.engine) {
                // A reply that is waiting for the model says so itself, where the reply will appear.
                is EngineState.Loading -> if (replyStatus is ReplyStatus.LoadingModel) null else ModelBanner.Loading(engine.modelId, engine.progress)
                // A reply that failed for this reason shows it as its own error, with Retry.
                is EngineState.Failed ->
                    if (engine.modelId == session.modelId && session.error?.kind != ChatErrorKind.MODEL_LOAD_FAILED && !generating) {
                        ModelBanner.Failed(engine.modelId, engine.error.toChatError(userMessageId = null).detail)
                    } else {
                        null
                    }
                EngineState.Unloaded, is EngineState.Ready -> null
            }
        }

    /**
     * True when the last turn has no answer and no error to show for it: the reply was stopped
     * before it wrote anything, or the app was closed before the reply began. The list then
     * offers to try again.
     */
    val offerRetry: Boolean
        get() {
            val session = session ?: return false
            if (session.isGenerating || session.error != null || !canWrite) return false
            val last = session.messages.lastOrNull() ?: return false
            return last.role == Role.USER || (last.status != MessageStatus.STREAMING && PromptWhitespace.isBlank(last.text))
        }
}

/**
 * The chat screen's ViewModel. The chats themselves, and the reply that may be running, live in
 * the [ChatController], which outlives this object; what is kept here is only what belongs to
 * the screen: the text in the input field, which message is being edited, and which dialog is
 * open.
 *
 * The input field's text is Compose state that is written synchronously as the user types, so
 * no character is lost to a round trip through a flow. It is never put into saved instance
 * state: a draft goes to the controller on every change and is stored encrypted with its chat,
 * which is where it comes back from after a lock or a restart.
 *
 * @param writeFailure what the session's writer last failed with.
 * @param onWriteFailureShown tells the session that the user has seen it.
 */
class ChatViewModel(
    private val controller: ChatController,
    private val settings: SettingsStore,
    writeFailure: StateFlow<String?>,
    private val onWriteFailureShown: () -> Unit,
) : ViewModel() {

    private data class Local(val editingMessageId: String? = null, val deleteRequest: DeleteRequest? = null, val linkRequest: String? = null)

    private val local = MutableStateFlow(Local())

    /** The time the chats are dated by ([ChatController.nowMillis]), for showing how long ago something changed. */
    fun nowMillis(): Long = controller.nowMillis()

    val state: StateFlow<ChatUiState> = combine(controller.state, local, writeFailure, settings.loadIssue) { chat, local, failure, issue ->
        ChatUiState(chat, local.editingMessageId, local.deleteRequest, local.linkRequest, failure, issue)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        ChatUiState(controller.state.value, writeFailure = writeFailure.value, settingsIssue = settings.loadIssue.value),
    )

    /** One-off messages for a snackbar. */
    val notices: Flow<ChatNotice> = controller.notices

    /** The text in the input field: the chat's draft, or the message being edited. */
    var inputText: String by mutableStateOf(draftOf(controller.state.value))
        private set

    /** Whether [inputText] is something to send, by the controller's own rule ([ChatController.isBlankMessage]). */
    val inputSendable: Boolean get() = !ChatController.isBlankMessage(inputText)

    private val openId: String get() = controller.state.value.open.id

    /** The chat [inputText] belongs to. */
    private var shownId: String = openId

    init {
        viewModelScope.launch {
            controller.state.collect { chat ->
                val editing = local.value.editingMessageId
                if (chat.open.id != shownId) {
                    // Another chat is on screen: its own draft, and nothing of the chat before.
                    shownId = chat.open.id
                    local.update { it.copy(editingMessageId = null) }
                    inputText = draftOf(chat)
                } else if (editing != null && (chat.open as? OpenChat.Ready)?.session?.messages?.none { it.id == editing } != false) {
                    // The message being edited is no longer there.
                    cancelEdit()
                }
            }
        }
    }

    // --- The input field -----------------------------------------------------------------------

    fun onInputChange(text: String) {
        inputText = text
        if (local.value.editingMessageId == null) controller.setDraft(openId, text)
    }

    /** Sends the input field: as a new message, or as the new text of the message being edited. */
    fun send() {
        val editing = local.value.editingMessageId
        if (editing == null) {
            if (controller.send(openId, inputText)) inputText = ""
        } else if (controller.edit(openId, editing, inputText)) {
            local.update { it.copy(editingMessageId = null) }
            inputText = draftOf(controller.state.value)
        }
    }

    fun stop() = controller.stop(openId)

    // --- Messages ------------------------------------------------------------------------------

    fun retry() {
        controller.retry(openId)
    }

    fun regenerate(assistantMessageId: String) {
        controller.regenerate(openId, assistantMessageId)
    }

    fun continueReply(assistantMessageId: String) {
        controller.continueReply(openId, assistantMessageId)
    }

    /**
     * Puts the text of the user message [messageId] into the input field to be changed. The
     * chat's unsent draft is left alone and comes back when the edit is sent or cancelled.
     */
    fun startEdit(messageId: String) {
        val session = (controller.state.value.open as? OpenChat.Ready)?.session ?: return
        if (session.isGenerating) return
        val message = session.messages.firstOrNull { it.id == messageId && it.role == Role.USER } ?: return
        local.update { it.copy(editingMessageId = messageId) }
        inputText = message.text
    }

    fun cancelEdit() {
        if (local.value.editingMessageId == null) return
        local.update { it.copy(editingMessageId = null) }
        inputText = draftOf(controller.state.value)
    }

    // --- Chats and models ----------------------------------------------------------------------

    fun newChat() = controller.newChat()

    fun openChat(id: String) = controller.openChat(id)

    /** Asks before deleting the chat [id]; nothing is deleted until [confirmDelete]. */
    fun requestDelete(id: String) {
        val chat = controller.state.value
        val title = chat.chats.firstOrNull { it.id == id }?.title
            ?: (chat.open.takeIf { it.id == id } as? OpenChat.Unreadable)?.title
            ?: (chat.open.takeIf { it.id == id } as? OpenChat.Ready)?.session?.title
            ?: return
        local.update { it.copy(deleteRequest = DeleteRequest(id, title)) }
    }

    fun confirmDelete() {
        val request = local.value.deleteRequest ?: return
        local.update { it.copy(deleteRequest = null) }
        controller.deleteChat(request.id)
    }

    fun dismissDelete() = local.update { it.copy(deleteRequest = null) }

    fun selectModel(modelId: String) = controller.selectModel(openId, modelId)

    fun reloadModel() = controller.reloadModel()

    // --- Things said once ----------------------------------------------------------------------

    fun incompleteNoticeShown() = controller.incompleteNoticeShown(openId)

    fun settingsIssueShown() = settings.loadIssueShown()

    fun writeFailureShown() = onWriteFailureShown()

    /** A link in an answer was tapped: the screen asks before anything leaves the app. */
    fun onLinkTapped(uri: String) = local.update { it.copy(linkRequest = uri) }

    fun dismissLink() = local.update { it.copy(linkRequest = null) }

    private fun draftOf(chat: ChatState): String = (chat.open as? OpenChat.Ready)?.session?.draft.orEmpty()
}
