package us.theworkmans.proseplow.core.chat

import us.theworkmans.proseplow.core.engine.GenerationRequest
import us.theworkmans.proseplow.core.model.ModelSpec
import us.theworkmans.proseplow.core.model.SamplingParams
import us.theworkmans.proseplow.core.prompt.PromptMessage
import us.theworkmans.proseplow.core.prompt.PromptRole
import us.theworkmans.proseplow.core.prompt.PromptWhitespace

/**
 * What a session sends to the model.
 *
 * @property messages the conversation, reduced by the history rules of [promptHistory].
 * @property continueFinalMessage true when the last of [messages] is the partial answer that the
 *   model is to continue (see [ChatSession.forContinuation]).
 */
data class PromptHistory(val messages: List<PromptMessage>, val continueFinalMessage: Boolean)

/**
 * The conversation as the model is to see it. The rules:
 *
 * - Every user message is sent, in order, also one whose reply failed. That can put two user
 *   messages in a row, which every bundled model's format accepts.
 * - An assistant message is sent only if it has an answer: a reply that failed, was stopped or
 *   ended before it produced any text is left out entirely. So is the empty placeholder of the
 *   reply that is about to be generated.
 * - Text is trimmed of surrounding whitespace, by the definition the models' templates use
 *   ([PromptWhitespace]). That is what each model's reference code does before encoding, and it
 *   makes the stored text and the rendered text agree for every model.
 * - A reply that is streaming and already has text is being continued: it is sent last, with only
 *   its start trimmed, because the model carries on from its exact end.
 *
 * The system prompt is not part of this: it is a setting of the model, added to the request.
 *
 * @param exactText true for a model that continues text
 *   ([us.theworkmans.proseplow.core.model.ModelFamily.continuesText]). The same messages are
 *   sent, but nothing is trimmed from any of them: such a model writes on from the very
 *   characters it is given, and a space at the end of them decides what comes next.
 */
fun ChatSession.promptHistory(exactText: Boolean = false): PromptHistory {
    val last = messages.lastOrNull()
    val continued = last?.takeIf {
        it.role == Role.ASSISTANT && it.status == MessageStatus.STREAMING && !PromptWhitespace.isBlank(it.text)
    }
    val sent = ArrayList<PromptMessage>(messages.size)
    for (message in messages) {
        // A tool result will be mapped here, to a prompt role of its own.
        when (message.role) {
            Role.USER -> sent += PromptMessage(PromptRole.USER, if (exactText) message.text else PromptWhitespace.strip(message.text))
            Role.ASSISTANT -> when {
                message === continued ->
                    sent += PromptMessage(PromptRole.ASSISTANT, if (exactText) message.text else PromptWhitespace.stripStart(message.text))
                message.status == MessageStatus.STREAMING || PromptWhitespace.isBlank(message.text) -> Unit
                else -> sent += PromptMessage(PromptRole.ASSISTANT, if (exactText) message.text else PromptWhitespace.strip(message.text))
            }
        }
    }
    return PromptHistory(sent, continueFinalMessage = continued != null)
}

/**
 * The request for the reply this session is waiting for. It is addressed to the model the
 * streaming reply was stamped with, so the engine refuses it if another model has been loaded
 * since; without a streaming reply it is addressed to the session's current model.
 *
 * @param model the model that is to write the reply. Its family decides whether the messages
 *   are sent trimmed, as a chat model's own template would have them, or exactly as they are
 *   (see [promptHistory]). It is asked for, rather than left to a default, so that no caller can
 *   send a model that continues text a prompt with its last space missing.
 * @param systemPrompt the system prompt the settings hold for that model, or null.
 * @param maxContextTokens the size the settings hold as the most that model's context may grow
 *   to, or null to leave that to the engine.
 */
fun ChatSession.generationRequest(
    model: ModelSpec,
    systemPrompt: String?,
    sampling: SamplingParams? = null,
    maxNewTokens: Int? = null,
    maxContextTokens: Int? = null,
): GenerationRequest {
    val history = promptHistory(exactText = model.family.continuesText)
    return GenerationRequest(
        modelId = messages.lastOrNull { it.status == MessageStatus.STREAMING }?.modelId ?: modelId,
        messages = history.messages,
        systemPrompt = systemPrompt,
        continueFinalMessage = history.continueFinalMessage,
        sampling = sampling,
        maxNewTokens = maxNewTokens,
        maxContextTokens = maxContextTokens,
    )
}
