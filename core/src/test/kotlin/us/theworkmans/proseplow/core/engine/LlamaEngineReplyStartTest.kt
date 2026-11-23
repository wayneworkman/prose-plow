package us.theworkmans.proseplow.core.engine

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import us.theworkmans.proseplow.core.model.ModelFamily
import us.theworkmans.proseplow.core.prompt.PromptMessage

/**
 * Where the engine tells the sampler that the reply began ([SamplerConfig.replyStart]): where
 * sampling begins for a new reply, and where the partial answer's content begins in memory for
 * "Continue", so that the presence and frequency penalties count the whole answer.
 */
class LlamaEngineReplyStartTest {

    private val partial = "One, two,"

    /** A model of [family] whose turn ends the way its prompt format ends one. */
    private fun model(family: ModelFamily, contextLength: Int = 2000, maxNewTokens: Int = 64) = when (family) {
        ModelFamily.PEACEBELL_CHATML -> testModel(family = family, stopTokenIds = listOf(4), contextLength = contextLength, maxNewTokens = maxNewTokens)
        else -> testModel(family = family, contextLength = contextLength, maxNewTokens = maxNewTokens)
    }

    /**
     * Continues [messages] on a model of [family] and checks that the configured reply start is
     * the memory position of the first token of [answer], which the fake's byte tokenizer spells
     * as the last tokens of the prompt.
     */
    private suspend fun TestScope.assertContinuedFromAnswer(
        family: ModelFamily,
        messages: List<PromptMessage>,
        answer: String,
        contextLength: Int = 2000,
        maxNewTokens: Int = 64,
        systemPrompt: String? = null,
    ): GenerationEvent.Done {
        val test = loadedEngine(model(family, contextLength, maxNewTokens))
        test.fake.enqueueReply(" three.")
        val done = test.engine.generate(request(*messages.toTypedArray(), systemPrompt = systemPrompt, continueFinalMessage = true)).toList().done()
        val answerTokens = test.fake.textTokens(answer)
        val expected = done.stats.promptTokens - answerTokens.size
        assertEquals("$family", expected, test.fake.replyStart)
        // The position is one in the model's memory: the answer is what the memory holds there.
        assertEquals("$family", answerTokens, test.fake.memory.subList(expected, expected + answerTokens.size))
        return done
    }

    @Test
    fun `a new reply begins where sampling begins`() = runTest {
        for (family in listOf(ModelFamily.QWEN35_CHATML, ModelFamily.PEACEBELL_CHATML, ModelFamily.PLAIN_CHATML, ModelFamily.COMPLETION)) {
            val test = loadedEngine(model(family))
            test.engine.generate(request(user("Count to three."))).toList().done()
            assertEquals("$family", SamplerConfig.REPLY_START_AT_SAMPLING, test.fake.replyStart)
        }
    }

    @Test
    fun `continuing an empty answer begins where sampling begins`() = runTest {
        val test = loadedEngine()
        test.engine.generate(request(user("Count."), assistant("  "), continueFinalMessage = true)).toList().done()
        assertEquals(SamplerConfig.REPLY_START_AT_SAMPLING, test.fake.replyStart)
    }

    @Test
    fun `continuing a Qwen answer counts it from its first character`() = runTest {
        assertContinuedFromAnswer(ModelFamily.QWEN35_CHATML, listOf(user("Count to three."), assistant(partial)), partial, systemPrompt = "Be brief.")
    }

    @Test
    fun `continuing a peacebell answer counts it from its first character`() = runTest {
        assertContinuedFromAnswer(ModelFamily.PEACEBELL_CHATML, listOf(user("Count to three."), assistant(partial)), partial)
    }

    @Test
    fun `continuing a plain ChatML answer counts it from its first character, after the whitespace the template trims`() = runTest {
        assertContinuedFromAnswer(ModelFamily.PLAIN_CHATML, listOf(user("Count to three."), assistant("  $partial")), partial, systemPrompt = "Be brief.")
    }

    @Test
    fun `continuing a text continuer's answer counts it from where its text follows the user's`() = runTest {
        assertContinuedFromAnswer(ModelFamily.COMPLETION, listOf(user("Dear Sir,"), assistant(" I write")), " I write")
    }

    @Test
    fun `a continued conversation whose older turns were left out passes the answer's position in the shortened prompt`() = runTest {
        val conversation = (1..6).flatMap { turn ->
            listOf(user("Question number $turn, padded to some length."), assistant("Answer number $turn, also of some length."))
        } + user("The latest question, which must always be sent.") + assistant("The partial answer")
        val done = assertContinuedFromAnswer(
            ModelFamily.PLAIN_CHATML,
            conversation,
            "The partial answer",
            contextLength = 400,
            maxNewTokens = 50,
            systemPrompt = "You answer in one word.",
        )
        assertTrue("older turns were left out", done.stats.droppedMessages > 0)
    }

    @Test
    fun `an answer whose start does not fall between two tokens begins where sampling begins`() = runTest {
        // The user's text ends with the first half of a surrogate pair and the answer begins with
        // the second: the tokenizer spells the character as one run of bytes across the place
        // where the answer begins, so no token boundary is there.
        val emoji = String(Character.toChars(0x1F600))
        val test = loadedEngine(model(ModelFamily.COMPLETION))
        test.engine.generate(
            request(user("Smile " + emoji[0]), assistant(emoji[1] + " and go on"), continueFinalMessage = true),
        ).toList().done()
        assertTrue(test.fake.memoryText.startsWith("Smile $emoji and go on"))
        assertEquals(SamplerConfig.REPLY_START_AT_SAMPLING, test.fake.replyStart)
    }
}
