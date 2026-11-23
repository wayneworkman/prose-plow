package us.theworkmans.proseplow.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import us.theworkmans.proseplow.R
import us.theworkmans.proseplow.core.chat.ChatSession
import us.theworkmans.proseplow.core.chat.Role
import us.theworkmans.proseplow.core.model.ModelSpec
import us.theworkmans.proseplow.data.SessionStore
import us.theworkmans.proseplow.data.SettingsStore
import us.theworkmans.proseplow.ui.Links
import us.theworkmans.proseplow.ui.NoticeCard
import us.theworkmans.proseplow.ui.ProtectWindow
import us.theworkmans.proseplow.ui.plainSelectionMenu
import us.theworkmans.proseplow.ui.rememberCopyToClipboard

/**
 * The chat, which is also the app's home: the conversation, the field to write in, and around
 * them everything that is one tap away. The list of chats is a drawer that slides over the
 * conversation (from the menu button or a swipe), so switching chats never leaves this screen;
 * the top bar names the chat and its model, and tapping it switches the model. Directly under
 * the bar, one line reminds the reader that what a model writes may be wrong.
 *
 * Nothing shown here is put into saved instance state except whether the list follows the newest
 * message and how far it is scrolled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel, onOpenSettings: () -> Unit, onLock: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val session = state.session
    val openId = state.chat.open.id
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    var modelMenuOpen by remember { mutableStateOf(false) }
    // A chat opens at its end: the list starts from its last item instead of jumping there.
    val listState = rememberSaveable(openId, saver = LazyListState.Saver) { LazyListState(firstVisibleItemIndex = session?.messages?.size ?: 0) }
    val followBottom = rememberSaveable(openId) { mutableStateOf(true) }
    val closeDrawer: () -> Unit = { scope.launch { drawerState.close() } }

    LaunchedEffect(viewModel, resources) {
        viewModel.notices.collect { notice ->
            val text = when (notice) {
                ChatNotice.OTHER_REPLY_STOPPED -> resources.getString(R.string.notice_other_reply_stopped)
                ChatNotice.CHAT_GONE -> resources.getString(R.string.notice_chat_gone)
            }
            snackbarHostState.showSnackbar(text)
        }
    }
    LaunchedEffect(state.writeFailure) {
        val failure = state.writeFailure ?: return@LaunchedEffect
        viewModel.writeFailureShown()
        snackbarHostState.showSnackbar(resources.getString(R.string.session_write_failed, failure))
    }
    // The keyboard would cover the bottom of the list of chats.
    LaunchedEffect(drawerState.targetValue) {
        if (drawerState.targetValue == DrawerValue.Open) keyboard?.hide()
    }
    BackHandler(enabled = drawerState.isOpen, onBack = closeDrawer)

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SessionDrawer(
                chats = state.chat.chats,
                openId = openId,
                generatingId = state.chat.turn?.sessionId,
                onNewChat = {
                    viewModel.newChat()
                    closeDrawer()
                },
                onOpen = { id ->
                    viewModel.openChat(id)
                    closeDrawer()
                },
                onDelete = viewModel::requestDelete,
                onSettings = {
                    closeDrawer()
                    onOpenSettings()
                },
            )
        },
    ) {
        // Named once, so that the line under the bar has the very colour the bar has.
        val barColors = TopAppBarDefaults.topAppBarColors()
        Scaffold(
            topBar = {
                TopAppBar(
                    colors = barColors,
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(painterResource(R.drawable.ic_menu), contentDescription = stringResource(R.string.open_chats))
                        }
                    },
                    title = {
                        ChatTitle(
                            title = when (val open = state.chat.open) {
                                is OpenChat.Ready -> open.session.title
                                is OpenChat.Unreadable -> open.title
                            },
                            // Without a model in the build there is none to name or to switch to.
                            modelId = session?.modelId?.takeIf { state.chat.models.isNotEmpty() },
                            models = state.chat.models,
                            enabled = !state.generating,
                            expanded = modelMenuOpen,
                            onExpandedChange = { modelMenuOpen = it },
                            onSelect = viewModel::selectModel,
                        )
                    },
                    actions = {
                        IconButton(onClick = viewModel::newChat) {
                            Icon(painterResource(R.drawable.ic_new_chat), contentDescription = stringResource(R.string.new_chat))
                        }
                        OverflowMenu(
                            canDelete = state.chat.chats.any { it.id == openId } || state.chat.open is OpenChat.Unreadable,
                            onSettings = onOpenSettings,
                            onDelete = { viewModel.requestDelete(openId) },
                            onLock = onLock,
                        )
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .imePadding(),
            ) {
                AiNotice(barColors.containerColor)
                Banners(
                    state = state,
                    onSettingsIssueShown = viewModel::settingsIssueShown,
                    onIncompleteShown = viewModel::incompleteNoticeShown,
                    onReloadModel = viewModel::reloadModel,
                    onChooseModel = { modelMenuOpen = true },
                )
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        session == null -> UnreadableChat(onDelete = { viewModel.requestDelete(openId) }, onNewChat = viewModel::newChat)
                        // The invitation names the model only when the model can in fact answer.
                        session.messages.isEmpty() ->
                            EmptyState(session.modelId.takeIf { state.canWrite && state.modelBanner !is ModelBanner.Failed })
                        else -> {
                            val uriHandler = remember(viewModel) {
                                object : UriHandler {
                                    override fun openUri(uri: String) = viewModel.onLinkTapped(uri)
                                }
                            }
                            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                                MessageList(
                                    state = state,
                                    session = session,
                                    listState = listState,
                                    followBottom = followBottom,
                                    viewModel = viewModel,
                                )
                            }
                        }
                    }
                }
                if (session != null) {
                    InputBar(
                        text = viewModel.inputText,
                        hint = stringResource(ModelText.messageHintRes(session.modelId)),
                        onTextChange = viewModel::onInputChange,
                        editing = state.editingMessageId != null,
                        generating = state.generating,
                        sendEnabled = state.canWrite && viewModel.inputSendable,
                        onSend = {
                            viewModel.send()
                            followBottom.value = true
                        },
                        onStop = viewModel::stop,
                        onCancelEdit = viewModel::cancelEdit,
                    )
                }
            }
        }
    }

    // The dialogs' state is in the ViewModel: they survive a rotation and show once.
    state.deleteRequest?.let { request ->
        DeleteChatDialog(request, onConfirm = viewModel::confirmDelete, onDismiss = viewModel::dismissDelete)
    }
    state.linkRequest?.let { link ->
        val copy = rememberCopyToClipboard()
        val copied = stringResource(R.string.copied)
        LinkDialog(
            link = link,
            onOpen = {
                viewModel.dismissLink()
                if (!Links.open(context, link)) {
                    scope.launch { snackbarHostState.showSnackbar(resources.getString(R.string.link_no_app)) }
                }
            },
            onCopy = {
                viewModel.dismissLink()
                copy(link, copied)
            },
            onDismiss = viewModel::dismissLink,
        )
    }
}

/**
 * The top bar's title: the chat's title over the name of its model, and the two together are the
 * button that opens the model picker. While a reply is being written here the picker is
 * disabled, and says why to a screen reader, because the reply in flight is using the one model
 * that is loaded.
 *
 * @param modelId the chat's model, or null for a chat that cannot be read (there is then
 *   nothing to switch).
 */
@Composable
private fun ChatTitle(
    title: String,
    modelId: String?,
    models: List<ModelSpec>,
    enabled: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
) {
    val busy = stringResource(R.string.switch_model_busy)
    Box {
        Column(
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(8.dp))
                .then(
                    if (modelId == null) {
                        Modifier
                    } else {
                        Modifier
                            .clickable(
                                enabled = enabled,
                                onClickLabel = stringResource(R.string.switch_model),
                                role = SemanticsRole.Button,
                            ) { onExpandedChange(true) }
                            .semantics { if (!enabled) stateDescription = busy }
                    },
                )
                .padding(horizontal = 4.dp),
        ) {
            Text(
                text = title.ifEmpty { stringResource(R.string.new_chat) },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (modelId != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = modelName(modelId),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Icon(
                        painterResource(R.drawable.ic_expand_more),
                        contentDescription = null,
                        tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else LocalContentColor.current.copy(alpha = 0.38f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        DropdownMenu(expanded = expanded && enabled && modelId != null, onDismissRequest = { onExpandedChange(false) }) {
            ProtectWindow()
            for (model in models) {
                val current = model.id == modelId
                DropdownMenuItem(
                    text = {
                        Column(modifier = Modifier.padding(vertical = 6.dp)) {
                            Text(modelName(model.id), fontWeight = if (current) FontWeight.Bold else FontWeight.Normal)
                            modelDescription(model.id)?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    onClick = {
                        onExpandedChange(false)
                        if (!current) onSelect(model.id)
                    },
                    modifier = Modifier.semantics { selected = current },
                )
            }
        }
    }
}

@Composable
private fun OverflowMenu(canDelete: Boolean, onSettings: () -> Unit, onDelete: () -> Unit, onLock: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.more_options))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ProtectWindow()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_settings), contentDescription = null) },
                onClick = {
                    open = false
                    onSettings()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete_chat)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
                enabled = canDelete,
                onClick = {
                    open = false
                    onDelete()
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.security_lock_now)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_lock), contentDescription = null) },
                onClick = {
                    open = false
                    onLock()
                },
            )
        }
    }
}

/**
 * What the user has to be told before the conversation: settings or a chat that could not be
 * read completely, and what the chat's model is doing when that is not "ready". Nothing is
 * shown when there is nothing to say.
 */
@Composable
private fun Banners(
    state: ChatUiState,
    onSettingsIssueShown: () -> Unit,
    onIncompleteShown: () -> Unit,
    onReloadModel: () -> Unit,
    onChooseModel: () -> Unit,
) {
    val cardModifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
    state.settingsIssue?.let { issue ->
        NoticeCard(
            title = stringResource(R.string.settings_issue_title),
            detail = stringResource(
                when (issue) {
                    SettingsStore.LoadIssue.UNREADABLE -> R.string.settings_issue_unreadable
                    SettingsStore.LoadIssue.PARTLY_READ -> R.string.settings_issue_partly_read
                },
            ),
            modifier = cardModifier,
        ) {
            TextButton(onClick = onSettingsIssueShown) { Text(stringResource(R.string.ok)) }
        }
    }
    (state.chat.open as? OpenChat.Ready)?.incomplete?.let { incomplete ->
        NoticeCard(
            title = stringResource(R.string.chat_incomplete_title),
            detail = incompleteText(incomplete),
            modifier = cardModifier,
        ) {
            TextButton(onClick = onIncompleteShown) { Text(stringResource(R.string.ok)) }
        }
    }
    when (val banner = state.modelBanner) {
        is ModelBanner.Loading -> Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.model_loading, modelName(banner.modelId)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            LinearProgressIndicator(progress = { banner.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
        is ModelBanner.Failed -> NoticeCard(
            title = stringResource(R.string.model_failed_title, modelName(banner.modelId)),
            detail = stringResource(ModelText.loadFailedDetailRes(banner.detail)),
            technical = banner.detail.takeIf { it.isNotBlank() && !ModelText.isTooLittleMemory(it) }?.let { stringResource(R.string.failed_detail, it) },
            modifier = cardModifier,
        ) {
            TextButton(onClick = onChooseModel) { Text(stringResource(R.string.choose_model)) }
            TextButton(onClick = onReloadModel) { Text(stringResource(R.string.try_again)) }
        }
        is ModelBanner.NotOffered -> NoticeCard(
            title = stringResource(R.string.model_not_offered_title),
            detail = stringResource(R.string.model_not_offered_detail, modelName(banner.modelId)),
            modifier = cardModifier,
        ) {
            TextButton(onClick = onChooseModel) { Text(stringResource(R.string.choose_model)) }
        }
        ModelBanner.NoModels -> NoticeCard(
            title = stringResource(R.string.no_models_title),
            detail = stringResource(R.string.no_models_detail),
            modifier = cardModifier,
        )
        null -> Unit
    }
}

@Composable
private fun incompleteText(incomplete: SessionStore.Incomplete): String {
    val what = when {
        incomplete.skippedMessages > 0 ->
            pluralStringResource(R.plurals.chat_incomplete_messages, incomplete.skippedMessages, incomplete.skippedMessages)
        incomplete.fromNewerVersion -> stringResource(R.string.chat_incomplete_newer)
        else -> stringResource(R.string.chat_incomplete_other)
    }
    return what + " " + stringResource(R.string.kept_copy)
}

/**
 * In place of a conversation that has not begun: an invitation, and which model will answer.
 *
 * @param modelId the chat's model, or null when it cannot answer (the banner above says why).
 */
@Composable
private fun EmptyState(modelId: String?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(ModelText.emptyTitleRes(modelId)), style = MaterialTheme.typography.titleMedium)
        if (modelId == null) return@Column
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(ModelText.emptyHintRes(modelId), modelName(modelId)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        modelDescription(modelId)?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** In place of a chat whose file cannot be read: what that means, and the two ways on. */
@Composable
private fun UnreadableChat(onDelete: () -> Unit, onNewChat: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.chat_unreadable_title), style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(R.string.chat_unreadable_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onNewChat) { Text(stringResource(R.string.new_chat)) }
        OutlinedButton(
            onClick = onDelete,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text(stringResource(R.string.delete_chat)) }
    }
}

/**
 * The conversation. While [followBottom] holds true it stays scrolled to the end as the reply
 * grows; a drag towards older messages turns that off, and reaching the end again (or the button
 * that appears meanwhile) turns it back on. It is the state itself that is passed in, because it
 * is read at the moment the list has been laid out, which may be before this is composed again.
 *
 * Following is done by looking at the list after every layout rather than at the text of the
 * reply: an answer's Markdown is parsed in the background, so the message grows a moment after
 * its text has, and only the layout knows when.
 */
@Composable
private fun MessageList(
    state: ChatUiState,
    session: ChatSession,
    listState: LazyListState,
    followBottom: MutableState<Boolean>,
    viewModel: ChatViewModel,
) {
    val copy = rememberCopyToClipboard()
    val copied = stringResource(R.string.copied)
    val onCopy: (String) -> Unit = remember(copy, copied) { { text -> copy(text, copied) } }

    val connection = remember(listState, followBottom) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) followBottom.value = false
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && !listState.canScrollForward) followBottom.value = true
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (!listState.canScrollForward) followBottom.value = true
                return Velocity.Zero
            }
        }
    }

    val messages = session.messages
    val error = session.error
    val trimmed = state.trimmedMessages
    val contextNote = state.contextNote
    // What may be done to the conversation now: nothing while a reply is being written.
    val canAct = !session.isGenerating && state.canWrite
    val canContinue = canAct && ChatController.canContinue(session)
    val following = followBottom.value
    LaunchedEffect(listState, followBottom, following) {
        if (!following) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo }.collect { layout ->
            if (followBottom.value && listState.canScrollForward && layout.totalItemsCount > 0) {
                listState.scrollToItem(layout.totalItemsCount - 1)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(connection),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            itemsIndexed(messages, key = { _, message -> message.id }, contentType = { _, message -> message.role }) { index, message ->
                when (message.role) {
                    Role.USER -> UserMessage(
                        message = message,
                        canEdit = canAct,
                        beingEdited = message.id == state.editingMessageId,
                        onCopy = onCopy,
                        onEdit = { viewModel.startEdit(message.id) },
                    )
                    Role.ASSISTANT -> AssistantMessage(
                        message = message,
                        status = state.replyStatus,
                        canRegenerate = canAct,
                        canContinue = canContinue && index == messages.lastIndex,
                        announce = index == messages.lastIndex,
                        onCopy = onCopy,
                        onRegenerate = { viewModel.regenerate(message.id) },
                        onContinue = { viewModel.continueReply(message.id) },
                    )
                }
            }
            if (contextNote != null) {
                item(key = "context", contentType = "note") { ContextNoteItem(contextNote) }
            }
            if (trimmed != null) {
                item(key = "trimmed", contentType = "note") { TrimmedNote(trimmed) }
            }
            if (error != null) {
                item(key = "error", contentType = "error") {
                    ErrorItem(
                        error = error,
                        onRetry = viewModel::retry,
                        onEdit = error.userMessageId?.takeIf { canAct }?.let { id -> { viewModel.startEdit(id) } },
                    )
                }
            } else if (state.offerRetry) {
                item(key = "retry", contentType = "retry") { RetryRow(viewModel::retry) }
            }
            item(key = "bottom", contentType = "bottom") { Spacer(Modifier.height(1.dp)) }
        }
        if (!following && listState.canScrollForward) {
            SmallFloatingActionButton(
                onClick = { followBottom.value = true },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp),
            ) {
                Icon(painterResource(R.drawable.ic_arrow_downward), contentDescription = stringResource(R.string.scroll_to_latest))
            }
        }
    }
}

/**
 * The field to write in, with Send beside it, which is Stop while a reply is being written. The
 * field stays usable meanwhile: the next message can be typed while the answer arrives.
 *
 * When [editing], a line above the field says that an earlier message is being changed and what
 * sending will do to the conversation, with a button to leave the message as it was.
 *
 * [text] comes from the ViewModel and is never saved by this composable. The cursor is the
 * field's own: when the text is replaced from outside (another chat's draft, a message to edit)
 * it goes to the end. [hint] is what the empty field says, which depends on the kind of model
 * the chat has: a model that continues text is not sent a message.
 */
@Composable
private fun InputBar(
    text: String,
    hint: String,
    onTextChange: (String) -> Unit,
    editing: Boolean,
    generating: Boolean,
    sendEnabled: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onCancelEdit: () -> Unit,
) {
    var field by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    if (field.text != text) field = TextFieldValue(text, TextRange(text.length))
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(editing) {
        if (editing) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    Column {
        if (editing) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                ) {
                    Text(stringResource(R.string.editing_title), style = MaterialTheme.typography.labelLarge)
                    Text(
                        stringResource(R.string.editing_support),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onCancelEdit) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.cancel_edit))
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = field,
                onValueChange = { changed ->
                    field = changed
                    if (changed.text != text) onTextChange(changed.text)
                },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                placeholder = { Text(hint) },
                maxLines = 6,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            if (generating) {
                FilledIconButton(
                    onClick = onStop,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Icon(painterResource(R.drawable.ic_stop), contentDescription = stringResource(R.string.stop))
                }
            } else {
                FilledIconButton(
                    onClick = onSend,
                    enabled = sendEnabled,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_send),
                        contentDescription = stringResource(if (editing) R.string.send_edit else R.string.send),
                    )
                }
            }
        }
    }
}

/**
 * The line directly under the top bar: a standing reminder that what a model says may be wrong.
 * It is the first thing in the screen's column, above the model's status and the conversation,
 * so it is there in every state of the chat (an empty one, one that cannot be read, while an
 * answer is written or a message edited) and it never moves: what comes and goes on this screen
 * does so below it. That includes the on-screen keyboard, which takes its room from the bottom
 * of the column; the line is always composed and does not ask whether the keyboard is open.
 *
 * It has the bar's own colour behind it ([barColor]), so that bar and line read as one, and it
 * is kept slim: small type and little padding. At a large font size it wraps rather than being
 * cut short.
 *
 * It is plain text and nothing else: no click, and no live region, so a screen reader says it
 * when the user moves to it and never by itself.
 */
@Composable
private fun AiNotice(barColor: Color) {
    Text(
        text = stringResource(R.string.ai_notice),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .background(barColor)
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun DeleteChatDialog(request: DeleteRequest, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_chat_title)) },
        text = {
            ProtectWindow()
            Text(
                if (request.title.isEmpty()) {
                    stringResource(R.string.delete_chat_message_untitled)
                } else {
                    stringResource(R.string.delete_chat_message, request.title)
                },
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.delete)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Asked when a link in an answer is tapped. The address is shown in full, because the model
 * wrote it and what a link says and where it leads can differ. Only web and mail links can be
 * opened; any link can be copied.
 */
@Composable
private fun LinkDialog(link: String, onOpen: () -> Unit, onCopy: () -> Unit, onDismiss: () -> Unit) {
    val canOpen = Links.canOpen(link)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (canOpen) R.string.link_title else R.string.link_title_cannot_open)) },
        text = {
            ProtectWindow()
            Column(modifier = Modifier.plainSelectionMenu(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (canOpen) R.string.link_message else R.string.link_message_cannot_open))
                SelectionContainer {
                    Text(link, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCopy) { Text(stringResource(R.string.link_copy)) }
            if (canOpen) TextButton(onClick = onOpen) { Text(stringResource(R.string.link_open)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
