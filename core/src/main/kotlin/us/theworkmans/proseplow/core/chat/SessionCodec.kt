package us.theworkmans.proseplow.core.chat

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The stored form of a [ChatSession]: JSON, with a schema version next to the session.
 *
 * ```
 * {"schemaVersion": 1, "session": {"id": ..., "title": ..., "messages": [...], ...}}
 * ```
 *
 * Writing is strict and complete: every field is written, defaults included, so that a later
 * change of a default cannot change the meaning of what is already on disk.
 *
 * Reading is as forgiving as it can be without inventing a conversation, because a chat that will
 * not open is lost to its owner:
 * - Fields this version does not know are ignored, in the session and in every message.
 * - A field of the session that is missing or has the wrong type takes its default. Only the id
 *   is indispensable.
 * - Messages are read one by one. A message that cannot be read (a role this version does not
 *   know, a missing id) is left out and counted; the others are kept.
 * - A message status or finish reason this version does not know becomes the default (complete,
 *   no reason); an error of an unknown kind becomes [ChatErrorKind.UNEXPECTED]; an error that
 *   cannot be read at all is dropped.
 * - A newer schema version is read by the same rules, and reported.
 *
 * [decode] reports everything it left out. A caller that is about to save over a session that
 * was not read completely should keep a copy of the stored bytes first.
 *
 * This object only converts. Sealing the text, and calling [ChatSession.restored] on what was
 * loaded, are the store's job.
 */
object SessionCodec {

    /** The version [encode] writes. Raise it when the stored form changes in a way old readers would misread. */
    const val SCHEMA_VERSION: Int = 1

    /** What [decode] made of a stored session. */
    sealed interface Decoded {

        /**
         * A session could be read.
         *
         * @property schemaVersion the version found in the stored text.
         * @property skippedMessages how many stored messages were left out because they could
         *   not be read.
         * @property errorDropped true when a stored error could not be read and was left out.
         */
        data class Readable(
            val session: ChatSession,
            val schemaVersion: Int,
            val skippedMessages: Int = 0,
            val errorDropped: Boolean = false,
        ) : Decoded {
            /** True when the text was written by a newer version of the app. */
            val fromNewerVersion: Boolean get() = schemaVersion > SCHEMA_VERSION

            /** True when [session] is everything that was stored. */
            val complete: Boolean get() = skippedMessages == 0 && !errorDropped && !fromNewerVersion
        }

        /** The text is not a stored session; [reason] says why, without quoting any of it. */
        data class Unreadable(val reason: String) : Decoded
    }

    @Serializable
    private class Stored(val schemaVersion: Int, val session: ChatSession)

    private val writer = Json {
        encodeDefaults = true
    }

    private val reader = Json {
        ignoreUnknownKeys = true
        // An enum value this version does not know becomes the property's default, and so does a
        // null where none is allowed.
        coerceInputValues = true
    }

    fun encode(session: ChatSession): String = writer.encodeToString(Stored.serializer(), Stored(SCHEMA_VERSION, session))

    fun decode(text: String): Decoded {
        val root = decodeOrNull { reader.parseToJsonElement(text) } as? JsonObject
            ?: return Decoded.Unreadable("not a JSON object")
        val version = root.long("schemaVersion")?.toInt() ?: return Decoded.Unreadable("no schema version")
        if (version < 1) return Decoded.Unreadable("invalid schema version")
        val stored = root["session"] as? JsonObject ?: return Decoded.Unreadable("no session")
        val id = stored.string("id")?.takeIf { it.isNotEmpty() } ?: return Decoded.Unreadable("session without an id")

        var skipped = 0
        val messages = ArrayList<ChatMessage>()
        for (element in stored["messages"] as? JsonArray ?: JsonArray(emptyList())) {
            val message = decodeOrNull { reader.decodeFromJsonElement(ChatMessage.serializer(), element) }
            if (message == null || message.id.isEmpty()) skipped++ else messages += message
        }

        val storedError = stored["error"]?.takeUnless { it is JsonNull }
        val error = storedError?.let { element -> decodeOrNull { reader.decodeFromJsonElement(ChatError.serializer(), element) } }

        val createdAt = stored.long("createdAtMillis") ?: 0L
        val session = ChatSession(
            id = id,
            title = stored.string("title").orEmpty(),
            createdAtMillis = createdAt,
            updatedAtMillis = stored.long("updatedAtMillis") ?: createdAt,
            modelId = stored.string("modelId").orEmpty(),
            draft = stored.string("draft").orEmpty(),
            messages = messages,
            error = error,
        )
        return Decoded.Readable(session, version, skippedMessages = skipped, errorDropped = storedError != null && error == null)
    }

    /** Null when [decode] rejects its input; a SerializationException is an IllegalArgumentException. */
    private inline fun <T> decodeOrNull(decode: () -> T): T? = try {
        decode()
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
}
