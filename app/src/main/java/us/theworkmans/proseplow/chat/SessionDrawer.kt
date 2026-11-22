package us.theworkmans.proseplow.chat

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import us.theworkmans.proseplow.R
import us.theworkmans.proseplow.core.chat.SessionSummary

/**
 * The list of chats, as the content of the navigation drawer: New chat at the top, every stored
 * chat below it with the most recently changed first, and Settings at the bottom. The chat on
 * screen is marked; a chat that is being answered right now shows a spinner; each chat has its
 * own Delete, which asks before it deletes.
 *
 * @param generatingId the chat a reply is being written in, or null.
 */
@Composable
fun SessionDrawer(
    chats: List<SessionSummary>,
    openId: String,
    generatingId: String?,
    onNewChat: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onSettings: () -> Unit,
) {
    ModalDrawerSheet {
        Text(
            stringResource(R.string.chats),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 16.dp, bottom = 12.dp),
        )
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.new_chat)) },
            icon = { Icon(painterResource(R.drawable.ic_new_chat), contentDescription = null) },
            selected = false,
            onClick = onNewChat,
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        if (chats.isEmpty()) {
            Text(
                stringResource(R.string.chats_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 28.dp, vertical = 8.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(chats, key = { it.id }) { chat ->
                    ChatRow(
                        chat = chat,
                        selected = chat.id == openId,
                        generating = chat.id == generatingId,
                        onOpen = { onOpen(chat.id) },
                        onDelete = { onDelete(chat.id) },
                    )
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.settings)) },
            icon = { Icon(painterResource(R.drawable.ic_settings), contentDescription = null) },
            selected = false,
            onClick = onSettings,
            modifier = Modifier
                .padding(NavigationDrawerItemDefaults.ItemPadding)
                .padding(bottom = 8.dp),
        )
    }
}

@Composable
private fun ChatRow(chat: SessionSummary, selected: Boolean, generating: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
    val title = chat.title.ifEmpty { stringResource(R.string.chat_untitled) }
    val model = modelName(chat.modelId)
    // Worked out when the row is first shown; good enough for a list that is open for seconds.
    val justNow = stringResource(R.string.chat_just_now)
    val changed = remember(chat.updatedAtMillis, justNow) {
        val now = System.currentTimeMillis()
        if (now - chat.updatedAtMillis < DateUtils.MINUTE_IN_MILLIS) {
            justNow
        } else {
            DateUtils.getRelativeTimeSpanString(chat.updatedAtMillis, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
        }
    }
    val answering = stringResource(R.string.chat_answering)
    NavigationDrawerItem(
        label = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = if (chat.modelId.isEmpty()) changed else stringResource(R.string.chat_row_support, model, changed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        selected = selected,
        onClick = onOpen,
        badge = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (generating) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(16.dp)
                            .semantics { contentDescription = answering },
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                IconButton(onClick = onDelete) {
                    Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.delete_chat_named, title))
                }
            }
        },
        modifier = Modifier.padding(start = 12.dp, end = 0.dp),
    )
}
