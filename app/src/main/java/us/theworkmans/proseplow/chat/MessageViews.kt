package us.theworkmans.proseplow.chat

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import us.theworkmans.proseplow.R
import us.theworkmans.proseplow.core.chat.ChatError
import us.theworkmans.proseplow.core.chat.ChatErrorKind
import us.theworkmans.proseplow.core.chat.ChatMessage
import us.theworkmans.proseplow.core.chat.MessageStatus
import us.theworkmans.proseplow.core.engine.FinishReason
import us.theworkmans.proseplow.core.prompt.PromptWhitespace
import us.theworkmans.proseplow.ui.NoticeCard
import java.text.NumberFormat

private val BubbleShape = RoundedCornerShape(16.dp)

/**
 * What the user wrote: a bubble at the end side, its text selectable, with Copy and Edit under
 * it. Edit is disabled while a reply is being written, because the conversation cannot be cut
 * then.
 *
 * @param beingEdited true while this message's text is in the input field; the bubble is
 *   outlined so that it is clear which message the edit will replace.
 */
@Composable
fun UserMessage(message: ChatMessage, canEdit: Boolean, beingEdited: Boolean, onCopy: (String) -> Unit, onEdit: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 48.dp, end = 12.dp, top = 6.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .clip(BubbleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .then(if (beingEdited) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, BubbleShape) else Modifier)
                .padding(12.dp),
        ) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onPrimaryContainer) {
                SelectionContainer { Text(message.text, style = MaterialTheme.typography.bodyLarge) }
            }
        }
        Row {
            MessageAction(R.drawable.ic_copy, stringResource(R.string.copy_message), onClick = { onCopy(message.text) })
            MessageAction(R.drawable.ic_edit, stringResource(R.string.edit_message), enabled = canEdit, onClick = onEdit)
        }
    }
}

/**
 * What a model wrote: no bubble, the full width, rendered as Markdown with selectable text.
 * Under it a line names the model, and says how the reply ended if it did not simply finish;
 * beside that are Copy and Regenerate, and Continue when the reply can be carried on.
 *
 * While the reply is being written the line says what it is waiting for instead ([status]), with
 * a bar when that has a measurable progress.
 *
 * @param announce true for the newest reply once it is complete: it is then a polite live
 *   region, so a screen reader reads the answer when it has arrived instead of every word as it
 *   streams.
 */
@Composable
fun AssistantMessage(
    message: ChatMessage,
    status: ReplyStatus?,
    canRegenerate: Boolean,
    canContinue: Boolean,
    announce: Boolean,
    onCopy: (String) -> Unit,
    onRegenerate: () -> Unit,
    onContinue: () -> Unit,
) {
    val streaming = message.status == MessageStatus.STREAMING
    val answer = message.text.trimStart()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 8.dp),
    ) {
        if (answer.isNotEmpty()) {
            SelectionContainer(
                modifier = Modifier
                    .padding(end = 12.dp)
                    .then(if (announce && !streaming) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier),
            ) {
                MarkdownContent(answer, streaming = streaming)
            }
        }
        val model = message.modelId?.let { modelName(it) }
        if (streaming) {
            ReplyProgress(model, status, hasText = answer.isNotEmpty())
        } else {
            val note = when {
                message.status == MessageStatus.STOPPED && answer.isEmpty() -> stringResource(R.string.note_stopped_empty)
                message.status == MessageStatus.STOPPED -> stringResource(R.string.note_stopped)
                message.finishReason == FinishReason.LENGTH -> stringResource(R.string.note_length)
                // A reply that finished normally but says nothing must not look like nothing.
                answer.isEmpty() -> stringResource(R.string.note_empty)
                else -> null
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when {
                        model != null && note != null -> stringResource(R.string.message_footer_with_note, model, note)
                        else -> model ?: note.orEmpty()
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp),
                )
                if (canContinue) TextButton(onClick = onContinue) { Text(stringResource(R.string.continue_reply)) }
                if (answer.isNotEmpty()) {
                    MessageAction(R.drawable.ic_copy, stringResource(R.string.copy_message), onClick = { onCopy(message.text.trim()) })
                    MessageAction(R.drawable.ic_refresh, stringResource(R.string.regenerate), enabled = canRegenerate, onClick = onRegenerate)
                }
            }
        }
    }
}

/**
 * The line under a reply that is being written. Its words change only when the reply moves from
 * one stage to the next, and it is a polite live region: a screen reader says "Loading", then
 * "Reading", then "Writing", not a number for every step. The numbers are in the bar.
 */
@Composable
private fun ReplyProgress(model: String?, status: ReplyStatus?, hasText: Boolean) {
    val stage = when (status) {
        is ReplyStatus.LoadingModel -> stringResource(R.string.status_loading_model)
        is ReplyStatus.Reading -> stringResource(R.string.status_reading)
        ReplyStatus.Writing -> stringResource(R.string.status_writing)
        ReplyStatus.Preparing, null -> stringResource(R.string.status_preparing)
    }
    val progress = when (status) {
        is ReplyStatus.LoadingModel -> status.progress
        is ReplyStatus.Reading -> status.fraction
        ReplyStatus.Preparing, ReplyStatus.Writing, null -> null
    }
    Column(modifier = Modifier.padding(end = 12.dp, top = if (hasText) 0.dp else 4.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            // The spinner is decoration: the words beside it say the same.
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (model != null) stringResource(R.string.message_footer_with_note, model, stage) else stage,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (progress != null) {
            LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** One of the small actions under a message: a full-size touch target around a small icon. */
@Composable
private fun MessageAction(@DrawableRes icon: Int, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
    ) {
        Icon(painterResource(icon), contentDescription = description, modifier = Modifier.size(20.dp))
    }
}

/**
 * The failure of the last request, where its answer would have been: what happened in plain
 * words, the technical detail small, and the one thing to do about it. That is Retry, except for
 * a message that is too long for the model, which no retry can fix: there it is Edit.
 */
@Composable
fun ErrorItem(error: ChatError, onRetry: () -> Unit, onEdit: (() -> Unit)?) {
    val title: String
    val detail: String
    when (error.kind) {
        ChatErrorKind.MODEL_NOT_LOADED -> {
            title = stringResource(R.string.error_model_not_loaded_title)
            detail = stringResource(R.string.error_model_not_loaded_detail)
        }
        ChatErrorKind.MODEL_LOAD_FAILED -> {
            title = stringResource(R.string.error_model_load_failed_title)
            detail = stringResource(ModelText.loadFailedDetailRes(error.detail))
        }
        ChatErrorKind.INVALID_REQUEST -> {
            title = stringResource(R.string.error_invalid_request_title)
            detail = stringResource(R.string.error_invalid_request_detail)
        }
        ChatErrorKind.PROMPT_TOO_LONG -> {
            title = stringResource(R.string.error_prompt_too_long_title)
            val needed = error.promptTokens
            val room = error.contextSize
            detail = if (needed != null && room != null) {
                stringResource(R.string.error_prompt_too_long_detail, needed, room)
            } else {
                stringResource(R.string.error_prompt_too_long_detail_plain)
            }
        }
        ChatErrorKind.CONTEXT_FULL -> {
            title = stringResource(R.string.error_context_full_title)
            detail = stringResource(R.string.error_context_full_detail)
        }
        ChatErrorKind.NATIVE -> {
            title = stringResource(R.string.error_native_title)
            detail = stringResource(R.string.error_native_detail)
        }
        ChatErrorKind.UNEXPECTED -> {
            title = stringResource(R.string.error_unexpected_title)
            detail = stringResource(R.string.error_unexpected_detail)
        }
    }
    NoticeCard(
        title = title,
        detail = detail,
        technical = error.detail.takeUnless { PromptWhitespace.isBlank(it) || ModelText.isTooLittleMemory(it) }?.let { stringResource(R.string.failed_detail, it) },
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        if (error.kind == ChatErrorKind.PROMPT_TOO_LONG && onEdit != null) {
            TextButton(onClick = onEdit) { Text(stringResource(R.string.edit_message)) }
        } else {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.try_again)) }
        }
    }
}

/** Try again, for a last turn that has neither an answer nor an error to show. */
@Composable
fun RetryRow(onRetry: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        TextButton(onClick = onRetry) { Text(stringResource(R.string.try_again)) }
    }
}

/**
 * Says what became of the model's context for the latest reply: that it was made larger for
 * this conversation (and that the conversation was read again for it), and that it could not
 * be made larger still, with the reason. Shown where the note about left-out messages is.
 */
@Composable
fun ContextNoteItem(note: ContextNote) {
    val number = remember { NumberFormat.getIntegerInstance() }
    val grown = note.grownTo?.let { size ->
        stringResource(if (note.reread) R.string.note_context_grown_reread else R.string.note_context_grown, number.format(size.toLong()))
    }
    val limited = note.limitedAt?.let { size ->
        stringResource(if (note.limitedByMemory) R.string.note_context_limited_memory else R.string.note_context_limited_failed, number.format(size.toLong()))
    }
    if (grown == null && limited == null) return
    Text(
        text = listOfNotNull(grown, limited).joinToString(" "),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** Says that the latest reply was written without the oldest [count] messages, and why. */
@Composable
fun TrimmedNote(count: Int) {
    Text(
        text = pluralStringResource(R.plurals.note_trimmed, count, count),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
