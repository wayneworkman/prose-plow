package us.theworkmans.proseplow.core.engine

import us.theworkmans.proseplow.core.prompt.PromptMessage
import us.theworkmans.proseplow.core.prompt.PromptRole

/**
 * Decides how much of a conversation is sent when it no longer fits the model's context.
 *
 * The rule: the rendered prompt plus the room reserved for the reply must fit. When it does not,
 * the oldest whole turns are left out, one turn being a user message with everything up to the
 * next user message. The system prompt is always kept (it is not part of the messages) and so is
 * the last turn: the latest user message and anything after it, such as the partial answer that
 * is being continued. Whole turns, because a prompt that begins with half an exchange reads to
 * the model like a different conversation.
 *
 * Nothing here shifts or edits the model's memory in place. The shortened conversation is simply
 * rendered again from its new beginning, which every model can do; shifting the context is not
 * possible on all of them.
 */
internal object ContextBudget {

    /** What [fit] found. */
    sealed interface Result {

        /** The prompt to use; [droppedMessages] oldest messages were left out of it. */
        class Fits(val prompt: TokenisedPrompt, val droppedMessages: Int) : Result

        /** Even the last turn alone leaves no room for a reply. */
        class TooLong(val promptTokens: Int) : Result
    }

    /**
     * The message indices a shortened conversation may begin at: 0, and every later user message.
     * The last entry is the latest user message, the start of the turn that is never dropped.
     */
    fun startCandidates(messages: List<PromptMessage>): List<Int> =
        listOf(0) + (1 until messages.size).filter { messages[it].role == PromptRole.USER }

    /**
     * The tail of [starts] from the earliest start whose rendering the tokenizer can take
     * ([acceptable]). A plain-text ChatML model renders its whole conversation as one text,
     * which the native tokenizer refuses beyond a fixed length; the other formats render every
     * message as a text of its own. Either way, leaving more out never makes a text longer, so
     * the earliest acceptable start is searched for by halving, and nothing is tokenised for it.
     * The turns before it are left out as turns are for the context. When not even the last
     * start is acceptable (its latest message alone is too long), that start alone is returned:
     * the tokenizer then refuses it, which is what such a message gets.
     */
    fun tokenisable(starts: List<Int>, acceptable: (start: Int) -> Boolean): List<Int> {
        require(starts.isNotEmpty()) { "Not a list of start candidates" }
        if (acceptable(starts.first())) return starts
        var low = 1
        var high = starts.lastIndex
        var first = starts.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (acceptable(starts[middle])) {
                first = middle
                high = middle - 1
            } else {
                low = middle + 1
            }
        }
        return starts.subList(first, starts.size)
    }

    /**
     * Finds the longest conversation that fits.
     *
     * @param messageCount the number of messages in the conversation.
     * @param starts the result of [startCandidates], or a tail of it ([tokenisable]): the
     *   messages before its first entry are left out in any case.
     * @param contextSize the model's context in tokens.
     * @param reserve tokens to keep free for the reply while older turns can still be dropped.
     * @param minimum tokens that must stay free in any case. Once only the last turn is left, the
     *   reply simply gets whatever room remains, as long as it is at least this much.
     * @param render renders and tokenises the conversation from the given message index on. It
     *   is called as few times as possible: once when everything fits, otherwise about as many
     *   times as the binary logarithm of the number of turns.
     */
    fun fit(
        messageCount: Int,
        starts: List<Int>,
        contextSize: Int,
        reserve: Int,
        minimum: Int,
        render: (start: Int) -> TokenisedPrompt,
    ): Result {
        require(starts.isNotEmpty() && starts.zipWithNext().all { (a, b) -> a < b } && starts.last() < messageCount) { "Not a list of start candidates" }
        val whole = render(starts.first())
        if (whole.tokens.size + reserve <= contextSize) return Result.Fits(whole, droppedMessages = starts.first())

        // Dropping more never makes the prompt longer, so the first start that fits can be
        // searched for by halving.
        var low = 1
        var high = starts.lastIndex
        var best: Result.Fits? = null
        var lastTurn: TokenisedPrompt? = null
        while (low <= high) {
            val middle = (low + high) ushr 1
            val candidate = render(starts[middle])
            if (middle == starts.lastIndex) lastTurn = candidate
            if (candidate.tokens.size + reserve <= contextSize) {
                best = Result.Fits(candidate, droppedMessages = starts[middle])
                high = middle - 1
            } else {
                low = middle + 1
            }
        }
        if (best != null) return best

        // Not even the last turn fits with the full reserve. Send it if any reply is possible.
        val shortest = lastTurn ?: if (starts.size == 1) whole else render(starts.last())
        return if (shortest.tokens.size + minimum <= contextSize) {
            Result.Fits(shortest, droppedMessages = starts.last())
        } else {
            Result.TooLong(shortest.tokens.size)
        }
    }
}
