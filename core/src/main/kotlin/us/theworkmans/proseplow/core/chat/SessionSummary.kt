package us.theworkmans.proseplow.core.chat

import kotlinx.serialization.Serializable

/**
 * What a list of chats needs to know about one session, without its messages. Small enough to
 * keep for every session in one index, so that the list can be shown without opening any chat.
 *
 * @property title the session's title; empty for a chat without messages, where the app shows
 *   its own placeholder.
 * @property updatedAtMillis when the conversation last changed; the list is ordered by this.
 * @property modelId the session's current model.
 */
@Serializable
data class SessionSummary(
    val id: String,
    val title: String = "",
    val createdAtMillis: Long = 0,
    val updatedAtMillis: Long = createdAtMillis,
    val modelId: String = "",
    val messageCount: Int = 0,
)
