package us.theworkmans.proseplow.core.model

import kotlinx.serialization.Serializable

/**
 * How the next token is chosen. Every model has its own defaults in the [ModelCatalog]; a
 * request may replace them as a whole.
 *
 * Each field has one value that switches it off, so a model's reference settings can be written
 * down without inventing numbers the model's authors never gave:
 *
 * @property temperature 0 (or less) means greedy decoding: always the most likely token, and
 *   [topK], [topP], [minP] and [seed] then have no effect. The penalties still apply.
 * @property topK keep only the K most likely tokens; 0 keeps all.
 * @property topP keep the smallest set of most likely tokens whose probabilities reach this sum;
 *   1 keeps all.
 * @property minP drop tokens less likely than this fraction of the most likely one; 0 drops none.
 * @property repetitionPenalty lowers the score of every token that is in the conversation so far,
 *   the prompt as much as this reply: a positive score is divided by it, a negative one
 *   multiplied. 1 is off.
 * @property presencePenalty subtracted once from the score of every token already written in
 *   this reply; 0 is off.
 * @property frequencyPenalty subtracted from a token's score once per time it was already written
 *   in this reply; 0 is off.
 * @property seed fixes the random choices so a reply can be reproduced; null draws a new seed for
 *   every reply.
 */
@Serializable
data class SamplingParams(
    val temperature: Float = 0f,
    val topK: Int = 0,
    val topP: Float = 1f,
    val minP: Float = 0f,
    val repetitionPenalty: Float = 1f,
    val presencePenalty: Float = 0f,
    val frequencyPenalty: Float = 0f,
    val seed: Int? = null,
) {
    /** True when the most likely token is always taken. */
    val isGreedy: Boolean get() = temperature <= 0f
}
