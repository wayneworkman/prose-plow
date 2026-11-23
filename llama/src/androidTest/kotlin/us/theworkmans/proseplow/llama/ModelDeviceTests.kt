package us.theworkmans.proseplow.llama

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import us.theworkmans.proseplow.core.engine.FinishReason
import us.theworkmans.proseplow.core.engine.GenerationEvent
import us.theworkmans.proseplow.core.engine.GenerationRequest
import us.theworkmans.proseplow.core.engine.LlamaEngine
import us.theworkmans.proseplow.core.engine.NativeLlama
import us.theworkmans.proseplow.core.engine.NativeLlamaException
import us.theworkmans.proseplow.core.model.ModelCatalog
import us.theworkmans.proseplow.core.model.ModelSpec
import us.theworkmans.proseplow.core.model.SamplingParams
import us.theworkmans.proseplow.core.prompt.PeacebellChatMlFormat
import us.theworkmans.proseplow.core.prompt.PromptMessage
import us.theworkmans.proseplow.core.prompt.PromptRole
import us.theworkmans.proseplow.core.prompt.PromptWhitespace
import us.theworkmans.proseplow.core.prompt.Qwen35ChatMlFormat
import java.util.concurrent.Executors

/*
 * The models of the app on the phone's own processor, through the real JNI binding. A test whose
 * model is neither an asset of the test APK nor sideloaded is reported as skipped; how to
 * provide the models is in native/README.md ("On the phone").
 *
 * The references are the committed files of the host tests (native/tests/data), generated from
 * the PyTorch models: what the phone computes with 16-bit weights on ARM is held to the same
 * token ids as the host.
 *
 * Every test runs with an activity of the test APK in front (see ForegroundActivity), because
 * that is how the app runs a model and it decides which cores the process may use. The phone
 * must therefore be awake and unlocked while these tests run.
 */

/** A passage long enough to time a prompt with; written for this project. */
private const val LONG_QUESTION: String =
    "I have been reading about how the seasons work and would like you to check my understanding. " +
        "The Earth goes around the Sun once a year, and its axis is tilted by about twenty-three degrees " +
        "against the plane of that orbit. For half of the year the northern half of the planet leans " +
        "towards the Sun, so the days there are longer and the light arrives more steeply, which makes " +
        "summer; for the other half it leans away, which makes winter, while the southern half has the " +
        "opposite season at the same time. The distance to the Sun changes a little as well, but that is " +
        "not what causes the seasons, because the Earth is in fact closest to the Sun in January. " +
        "Is that right, and is there anything important that I have left out of this picture?"

/** peacebell-v1-291M: attention-only memory, the SentencePiece front-end, marker ids. */
@RunWith(AndroidJUnit4::class)
class PeacebellDeviceTest {

    private val model: ModelSpec = ModelCatalog.PEACEBELL_V1_291M
    private val endId = PeacebellChatMlFormat.IM_END_ID
    private val startId = PeacebellChatMlFormat.IM_START_ID

    @get:Rule
    val inFront = ActivityScenarioRule(ForegroundActivity::class.java)

    @Before
    fun needsTheModel() = assumePresent(model)

    /** The model's chat layout: marker ids around the role line and the content, each encoded on its own. */
    private fun NativeLlama.chat(messages: List<Pair<String, String>>, addGenerationPrompt: Boolean): IntArray {
        val out = ArrayList<Int>()
        for ((role, content) in messages) {
            out += startId
            out += tokens(role + "\n", false).toList()
            out += tokens(content, false).toList()
            out += endId
        }
        if (addGenerationPrompt) {
            out += startId
            out += tokens("assistant\n", false).toList()
        }
        return out.toIntArray()
    }

    @Test
    fun tokenisationEqualsTheHostVectors() {
        val vectors = readTokenVectors("peacebell-v1-tokens.bin")
        val native = open(model)
        try {
            var plain = 0
            var special = 0
            var tokens = 0
            for (vector in vectors.texts) {
                if (!native.tokens(vector.text, false).contentEquals(vector.safe)) plain++
                if (!native.tokens(vector.text, true).contentEquals(vector.exact)) special++
                tokens += vector.exact.size
            }
            val chats = vectors.chats.count { !native.chat(it.messages, it.addGenerationPrompt).contentEquals(it.safe) }
            report("${model.id} tokenisation: ${vectors.texts.size} texts ($tokens tokens), $plain differ as plain text, $special with special parsing; ${vectors.chats.size} conversations, $chats differ")
            assertEquals("texts as plain text", 0, plain)
            assertEquals("texts with special tokens parsed", 0, special)
            assertEquals("conversations", 0, chats)
        } finally {
            native.close()
        }
    }

    @Test
    fun greedyRepliesEqualTheHostReference() {
        val references = readGreedyReferences("${model.id}-logits.bin")
        val native = open(model)
        try {
            var same = 0
            var promptTokens = 0
            var promptNanos = 0L
            var replyTokens = 0
            var replyNanos = 0L
            var firstDivergence = "none"
            references.forEachIndexed { index, reference ->
                // As the reference was made: the token the model must never emit is banned, the reply ends on the end marker within 96 tokens.
                val reply = native.freshReply(reference.prompt, 96, model.bannedTokenIds) { it == endId }
                if (reply.tokens == reference.reply.toList()) {
                    same++
                } else if (firstDivergence == "none") {
                    val common = reply.tokens.zip(reference.reply.toList()).takeWhile { it.first == it.second }.size
                    firstDivergence = "conversation $index, reply token $common"
                }
                promptTokens += reference.prompt.size
                promptNanos += reply.promptNanos
                replyTokens += reply.tokens.size
                replyNanos += reply.generationNanos
            }
            report(
                "${model.id} greedy replies equal to the host reference: $same of ${references.size} (first divergence: $firstDivergence); " +
                    "prompt %.1f tokens/s ($promptTokens tokens), generation %.1f tokens/s ($replyTokens tokens)".format(
                        promptTokens * 1e9 / promptNanos, replyTokens * 1e9 / replyNanos,
                    ),
            )
            assertEquals("greedy replies identical to the reference, token for token ($firstDivergence)", references.size, same)
        } finally {
            native.close()
        }
    }

    @Test
    fun theEndOfTheMemoryCanBeRemoved() {
        val references = readGreedyReferences("${model.id}-logits.bin")
        val native = open(model)
        try {
            checkRewind(native, references[0].prompt, references[1].prompt, references[2].prompt.copyOfRange(0, 12), recurrent = false) { it == endId }
        } finally {
            native.close()
        }
    }

    @Test
    fun aChunkIsInterruptedWhenCancelled() {
        val native = open(model)
        try {
            val prompt = native.chat(listOf("user" to LONG_QUESTION), addGenerationPrompt = true)
            val latency = checkCancellation(native, prompt, recurrent = false) { it == endId }
            report("${model.id} cancellation: returned %.2f ms after the observer first said stop, in the middle of a chunk of ${prompt.size} tokens".format(latency))
            assertTrue("cancellation is prompt (%.1f ms)".format(latency), latency < 250.0)
        } finally {
            native.close()
        }
    }

    @Test
    fun loadTimeMemoryAndSpeed() {
        val probe = open(model)
        val prompt = try {
            probe.chat(listOf("user" to LONG_QUESTION), addGenerationPrompt = true)
        } finally {
            probe.close()
        }
        checkLoadAndMemory("${model.id} mapped", model, NativeSettings(mapModel = true), prompt)
        checkLoadAndMemory("${model.id} read", model, NativeSettings(mapModel = false), prompt)
        report("${model.id} speeds: ${schedulingGroup()}")
        reportSpeeds("${model.id} pinned", measureSpeeds(model, NativeSettings(pinThreads = true), prompt, 48) { it == endId }, prompt.size)
        reportSpeeds("${model.id} unpinned", measureSpeeds(model, NativeSettings(pinThreads = false), prompt, 48) { it == endId }, prompt.size)
    }

    @Test
    fun theEngineHoldsATwoTurnChat() {
        checkEngineChat(model, systemPrompt = null, first = "When did the Second World War end in Europe?", second = "And in the Pacific?")
    }
}

/** Boris-2-Instruct: recurrent state beside the attention, llama.cpp's own tokenizer, ChatML as plain text. */
@RunWith(AndroidJUnit4::class)
class BorisDeviceTest {

    private val model: ModelSpec = ModelCatalog.BORIS_2_INSTRUCT

    @get:Rule
    val inFront = ActivityScenarioRule(ForegroundActivity::class.java)

    @Before
    fun needsTheModel() = assumePresent(model)

    /** The model's chat template as text: one string for the whole conversation. */
    private fun chatText(messages: List<Pair<String, String>>, addGenerationPrompt: Boolean): String {
        val text = StringBuilder()
        for ((role, content) in messages) {
            text.append("<|im_start|>").append(role).append('\n').append(PromptWhitespace.strip(content)).append("<|im_end|>\n")
        }
        if (addGenerationPrompt) text.append("<|im_start|>assistant\n")
        return text.toString()
    }

    private fun isEnd(native: NativeLlama): (Int) -> Boolean = { native.isEndOfGeneration(it) || it in model.stopTokenIds }

    @Test
    fun tokenisationEqualsTheHostVectors() {
        val vectors = readTokenVectors("${model.id}-tokens.bin")
        val native = open(model)
        try {
            var plain = 0
            var special = 0
            var tokens = 0
            for (vector in vectors.texts) {
                if (!native.tokens(vector.text, false).contentEquals(vector.safe)) plain++
                if (!native.tokens(vector.text, true).contentEquals(vector.exact)) special++
                tokens += vector.exact.size
            }
            val chats = vectors.chats.count { !native.tokens(chatText(it.messages, it.addGenerationPrompt), false).contentEquals(it.safe) }
            report("${model.id} tokenisation: ${vectors.texts.size} texts ($tokens tokens), $plain differ as plain text, $special with special parsing; ${vectors.chats.size} conversations, $chats differ")
            assertEquals("texts as plain text", 0, plain)
            assertEquals("texts with special tokens parsed", 0, special)
            assertEquals("conversations", 0, chats)
        } finally {
            native.close()
        }
    }

    @Test
    fun greedyRepliesEqualTheHostReference() {
        val references = readGreedyReferences("${model.id}-logits.bin")
        val native = open(model)
        try {
            var same = 0
            var promptTokens = 0
            var promptNanos = 0L
            var replyTokens = 0
            var replyNanos = 0L
            for (reference in references) {
                // The end marker of this model is text, so the reference reply is compared at its own length.
                val reply = native.freshReply(reference.prompt, reference.reply.size, model.bannedTokenIds) { false }
                if (reply.tokens == reference.reply.toList()) same++
                promptTokens += reference.prompt.size
                promptNanos += reply.promptNanos
                replyTokens += reply.tokens.size
                replyNanos += reply.generationNanos
            }
            report(
                "${model.id} greedy replies equal to the host reference: $same of ${references.size}; " +
                    "prompt %.1f tokens/s ($promptTokens tokens), generation %.1f tokens/s ($replyTokens tokens)".format(
                        promptTokens * 1e9 / promptNanos, replyTokens * 1e9 / replyNanos,
                    ),
            )
            assertEquals("greedy replies identical to the reference, token for token", references.size, same)
        } finally {
            native.close()
        }
    }

    /**
     * The reference's chat session through the engine of `:core`: the Kotlin prompt format and
     * the native tokenizer must give the reference's prompt, the engine must find its way back
     * through checkpoints when a conversation changes, and every reply must be the reference's.
     */
    @Test
    fun theEngineReproducesTheReferenceChatSession() {
        val reference = readChatReference("${model.id}-chat.bin")
        val executor = Executors.newSingleThreadExecutor()
        val engine = LlamaEngine(testFactory(), executor.asCoroutineDispatcher())
        try {
            runBlocking {
                engine.load(model)
                var sameText = 0
                var samePrompt = 0
                var reused = 0
                var evaluated = 0
                reference.requests.forEachIndexed { index, request ->
                    val system = request.messages.firstOrNull { it.first == "system" }?.second
                    val messages = request.messages.filter { it.first != "system" }.map { (role, content) ->
                        PromptMessage(if (role == "user") PromptRole.USER else PromptRole.ASSISTANT, content)
                    }
                    val events = engine.generate(
                        GenerationRequest(model.id, messages, systemPrompt = system, sampling = SamplingParams(temperature = 0f), maxNewTokens = reference.maxNewTokens),
                    ).toList()
                    events.filterIsInstance<GenerationEvent.Error>().firstOrNull()?.let { fail("request $index: the engine reported ${it.error}") }
                    val done = events.last() as GenerationEvent.Done
                    if (done.stats.promptTokens == request.prompt.size) samePrompt++
                    val expectedReason = if (request.ended) FinishReason.STOP_STRING else FinishReason.LENGTH
                    if (done.finishReason == expectedReason && (!request.ended || done.text == request.text)) sameText++
                    reused += done.stats.reusedPromptTokens
                    evaluated += done.stats.evaluatedPromptTokens
                }
                report(
                    "${model.id} reference chat session through the engine: ${reference.requests.size} requests, $samePrompt with the reference's prompt length, " +
                        "$sameText with the reference's reply; $reused prompt tokens reused from memory, $evaluated evaluated",
                )
                assertEquals("prompts of the reference's length", reference.requests.size, samePrompt)
                assertEquals("replies equal to the reference's", reference.requests.size, sameText)
                assertTrue("the memory was reused across requests", reused > 0)
                engine.unload()
            }
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun theMemoryComesBackThroughACheckpoint() {
        val references = readGreedyReferences("${model.id}-logits.bin")
        val native = open(model)
        try {
            checkRewind(native, references[0].prompt, references[4].prompt.copyOfRange(0, 24), references[3].prompt.copyOfRange(0, 12), recurrent = true, isEnd = isEnd(native))
        } finally {
            native.close()
        }
    }

    /** The model's graph ends the process when it is fed its end-of-text token; the session must refuse first. */
    @Test
    fun theEndOfTextTokenIsRefusedAsInput() {
        val native = open(model)
        try {
            val prompt = native.tokens(chatText(listOf("user" to "Hello."), addGenerationPrompt = true), false)
            native.mustEvaluate(prompt)
            expectFailure(NativeLlamaException.INVALID_ARGUMENT, "evaluating the end-of-text token") { native.evaluate(intArrayOf(0), 1, Uninterrupted) }
            assertEquals("the memory is untouched", prompt.size, native.memoryTokenCount())
            native.mustEvaluate(prompt.copyOfRange(0, 4))
        } finally {
            native.close()
        }
    }

    @Test
    fun cancellationWaitsForTheChunk() {
        val native = open(model)
        try {
            val prompt = native.tokens(chatText(listOf("user" to LONG_QUESTION), addGenerationPrompt = true), false)
            val latency = checkCancellation(native, prompt, recurrent = true, isEnd = isEnd(native))
            native.clearMemory()
            val chunkMillis = native.mustEvaluate(prompt.copyOfRange(0, ENGINE_CHUNK)) / 1e6
            report("${model.id} cancellation: between chunks, %.2f ms after the observer said stop; a chunk of $ENGINE_CHUNK tokens takes %.0f ms, the longest a stop can wait".format(latency, chunkMillis))
        } finally {
            native.close()
        }
    }

    @Test
    fun loadTimeMemoryAndSpeed() {
        val probe = open(model)
        val prompt = try {
            probe.tokens(chatText(listOf("user" to LONG_QUESTION), addGenerationPrompt = true), false)
        } finally {
            probe.close()
        }
        checkLoadAndMemory("${model.id} mapped", model, NativeSettings(mapModel = true), prompt)
        report("${model.id} speeds: ${schedulingGroup()}")
        reportSpeeds("${model.id} pinned", measureSpeeds(model, NativeSettings(pinThreads = true), prompt, 48), prompt.size)
        reportSpeeds("${model.id} unpinned", measureSpeeds(model, NativeSettings(pinThreads = false), prompt, 48), prompt.size)
    }
}

/** Qwen3.5-0.8B: recurrent state beside the attention, llama.cpp's own tokenizer, ChatML with control tokens. */
@RunWith(AndroidJUnit4::class)
class QwenDeviceTest {

    private val model: ModelSpec = ModelCatalog.QWEN35_0_8B

    @get:Rule
    val inFront = ActivityScenarioRule(ForegroundActivity::class.java)

    @Before
    fun needsTheModel() = assumePresent(model)

    private fun chatText(question: String): String =
        "<|im_start|>system\nYou are a helpful assistant.<|im_end|>\n<|im_start|>user\n$question<|im_end|>\n" + Qwen35ChatMlFormat.GENERATION_PROMPT

    private fun isEnd(native: NativeLlama): (Int) -> Boolean = { native.isEndOfGeneration(it) || it in model.stopTokenIds }

    @Test
    fun aGreedyReplyIsSaneAndStable() {
        val native = open(model)
        try {
            val prompt = native.tokens(chatText("What is the capital of Australia?"), true)
            val first = native.freshReply(prompt, 24, isEnd = isEnd(native))
            val second = native.freshReply(prompt, 24, isEnd = isEnd(native))
            val text = String(first.tokens.flatMap { native.tokenToBytes(it).toList() }.toByteArray(), Charsets.UTF_8)
            report("${model.id} greedy reply: ${first.tokens.size} tokens, identical in two runs: ${first.tokens == second.tokens}; names the capital: ${"Canberra" in text}")
            assertTrue("the reply is not empty", first.tokens.isNotEmpty())
            assertEquals("the same reply twice", first.tokens, second.tokens)
            assertTrue("the reply names the capital", "Canberra" in text)
            for (marker in listOf("<|im_start|>", "<|im_end|>", "<|endoftext|>")) {
                val control = native.tokens(marker, true)
                assertEquals("$marker is one token when the app writes it", 1, control.size)
                assertTrue("$marker stays text inside a message", native.tokens(marker, false).size > 1)
                assertEquals("a control token has no text", 0, native.tokenToBytes(control[0]).size)
            }
        } finally {
            native.close()
        }
    }

    @Test
    fun theMemoryComesBackThroughACheckpoint() {
        val native = open(model)
        try {
            val a = native.tokens(chatText("What is the capital of Australia?"), true)
            val b = native.tokens(chatText("Name three colours of the rainbow."), true).copyOfRange(0, 24)
            val c = native.tokens(chatText("How many legs does a spider have?"), true).copyOfRange(0, 12)
            checkRewind(native, a, b, c, recurrent = true, isEnd = isEnd(native))
        } finally {
            native.close()
        }
    }

    @Test
    fun cancellationWaitsForTheChunk() {
        val native = open(model)
        try {
            val prompt = native.tokens(chatText(LONG_QUESTION), true)
            val latency = checkCancellation(native, prompt, recurrent = true, isEnd = isEnd(native))
            native.clearMemory()
            val chunkMillis = native.mustEvaluate(prompt.copyOfRange(0, ENGINE_CHUNK)) / 1e6
            val tokenMillis = native.mustEvaluate(intArrayOf(prompt[ENGINE_CHUNK]), 1) / 1e6
            report(
                "${model.id} cancellation: between chunks, %.2f ms after the observer said stop; the longest a stop can wait is one chunk: %.0f ms for $ENGINE_CHUNK prompt tokens, %.0f ms for a generated token"
                    .format(latency, chunkMillis, tokenMillis),
            )
        } finally {
            native.close()
        }
    }

    @Test
    fun loadTimeAndMemory() {
        val probe = open(model)
        val prompt = try {
            probe.tokens(chatText(LONG_QUESTION), true)
        } finally {
            probe.close()
        }
        checkLoadAndMemory("${model.id} mapped", model, NativeSettings(mapModel = true), prompt)
        checkLoadAndMemory("${model.id} read", model, NativeSettings(mapModel = false), prompt)
    }

    /** The comparison the thread defaults were chosen from; it only reports. */
    @Test
    fun speedPinnedAndUnpinned() {
        val probe = open(model)
        val prompt = try {
            probe.tokens(chatText(LONG_QUESTION), true)
        } finally {
            probe.close()
        }
        val plan = ThreadPlanner.plan(CpuSysfs().capacities(), Runtime.getRuntime().availableProcessors())
        report("${model.id} speeds with the test's activity in front: ${schedulingGroup()}")
        reportSpeeds("${model.id} pinned to ${plan.pinnedCpus}", measureSpeeds(model, NativeSettings(pinThreads = true), prompt, 32), prompt.size)
        reportSpeeds("${model.id} unpinned (the plan's ${plan.unpinnedThreads})", measureSpeeds(model, NativeSettings(pinThreads = false), prompt, 32), prompt.size)
        // As many free threads as big cores: what the plan is there to avoid.
        val crowded = ThreadPlan(emptyList(), plan.pinnedCpus.size.coerceAtLeast(plan.unpinnedThreads + 1))
        reportSpeeds("${model.id} unpinned (${crowded.unpinnedThreads}, one per big core)", measureSpeeds(model, NativeSettings(pinThreads = false), prompt, 32, crowded), prompt.size)

        // The same with no activity in front: the process loses cores, pinning is no longer
        // possible, and the default settings must fall back to free threads by themselves.
        inFront.scenario.close()
        Thread.sleep(1500) // the system moves the process to its new set of cores a moment after the activity has gone
        report("${model.id} speeds with nothing of the test in front: ${schedulingGroup()}")
        reportSpeeds("${model.id} default settings, not in front", measureSpeeds(model, NativeSettings(), prompt, 32), prompt.size)
    }

    @Test
    fun theEngineHoldsATwoTurnChat() {
        val first = checkEngineChat(model, systemPrompt = "You are a helpful assistant.", first = "What is the capital of Australia?", second = "And of New Zealand?")
        assertTrue("the first reply names the capital", "Canberra" in first)
    }
}

/**
 * Shard-2: attention only, with windowed layers; llama.cpp's own tokenizer; a model that
 * continues text, so there is no chat layout and a "prompt" is the beginning of a text.
 */
@RunWith(AndroidJUnit4::class)
class ShardDeviceTest {

    private val model: ModelSpec = ModelCatalog.SHARD_2

    /** The window of the model's windowed layers in tokens (`shard2.attention.sliding_window` in the model file). */
    private val window = 256

    @get:Rule
    val inFront = ActivityScenarioRule(ForegroundActivity::class.java)

    @Before
    fun needsTheModel() = assumePresent(model)

    /** The begin and end markers are not text; either ends a reply. */
    private val isEnd: (Int) -> Boolean = { it in model.stopTokenIds }

    @Test
    fun tokenisationEqualsTheHostVectors() {
        val vectors = readTokenVectors("${model.id}-tokens.bin")
        val native = open(model)
        try {
            var plain = 0
            var special = 0
            var tokens = 0
            for (vector in vectors.texts) {
                if (!native.tokens(vector.text, false).contentEquals(vector.safe)) plain++
                if (!native.tokens(vector.text, true).contentEquals(vector.exact)) special++
                tokens += vector.exact.size
            }
            report("${model.id} tokenisation: ${vectors.texts.size} texts ($tokens tokens), $plain differ as plain text, $special with special parsing")
            assertEquals("texts as plain text", 0, plain)
            assertEquals("texts with special tokens parsed", 0, special)
        } finally {
            native.close()
        }
    }

    @Test
    fun greedyRepliesEqualTheHostReference() {
        val references = readGreedyReferences("${model.id}-logits.bin")
        val native = open(model)
        try {
            var same = 0
            var promptTokens = 0
            var promptNanos = 0L
            var replyTokens = 0
            var replyNanos = 0L
            var firstDivergence = "none"
            references.forEachIndexed { index, reference ->
                // The model has no end marker to wait for (the reference writes a fixed number of
                // tokens, its deletion token among them), so a reply is compared at its length.
                val reply = native.freshReply(reference.prompt, reference.reply.size, model.bannedTokenIds) { false }
                if (reply.tokens == reference.reply.toList()) {
                    same++
                } else if (firstDivergence == "none") {
                    val common = reply.tokens.zip(reference.reply.toList()).takeWhile { it.first == it.second }.size
                    firstDivergence = "text $index, reply token $common"
                }
                promptTokens += reference.prompt.size
                promptNanos += reply.promptNanos
                replyTokens += reply.tokens.size
                replyNanos += reply.generationNanos
            }
            report(
                "${model.id} greedy replies equal to the host reference: $same of ${references.size} (first divergence: $firstDivergence); " +
                    "prompt %.1f tokens/s ($promptTokens tokens), generation %.1f tokens/s ($replyTokens tokens)".format(
                        promptTokens * 1e9 / promptNanos, replyTokens * 1e9 / replyNanos,
                    ),
            )
            assertEquals("greedy replies identical to the reference, token for token ($firstDivergence)", references.size, same)
        } finally {
            native.close()
        }
    }

    /** Within the window the model behaves like any attention-only one: the end of its memory can be removed. */
    @Test
    fun theEndOfTheMemoryCanBeRemoved() {
        val references = readGreedyReferences("${model.id}-logits.bin")
        val native = open(model)
        try {
            checkRewind(native, references[0].prompt, references[3].prompt, references[2].prompt.copyOfRange(0, 12), recurrent = false, isEnd = isEnd)
        } finally {
            native.close()
        }
    }

    /**
     * The model's own rule, on a text several windows long. A checkpoint of this model is the
     * window of its windowed layers, so once one was restored those layers hold nothing older:
     * a cut that needs older keys must be refused with the memory untouched (llama.cpp would
     * make it and answer "done", and every prediction after it would be wrong), while a cut the
     * restored window covers is made and is exact. Before any restore the layers hold every
     * key, and a cut many windows back is exact as well.
     */
    @Test
    fun afterARestoreACutBelowTheRestoredWindowIsRefused() {
        val sequences = readGreedyReferences("${model.id}-long-logits.bin").map { it.sequence }.sortedByDescending { it.size }
        val text = sequences[0]
        val other = sequences[1]
        assertTrue("the reference has a text of five windows and one of two", text.size >= 5 * window && other.size >= 2 * window)
        val next = other.copyOfRange(window, window + 48)
        val replyLength = 24
        // Lengths, in windows: a checkpoint where the window is long full, a cut far below it,
        // a cut below the checkpoint by most of a window, and one just below the checkpoint.
        val all = text.size
        val saved = 3 * window + window / 2
        val far = window + window / 6
        val lost = saved - (window - window / 16)
        val near = saved - 1
        val native = open(model)
        try {
            assertTrue("the context holds the text and what follows it", native.contextSize() >= all + next.size + replyLength)
            fun after(length: Int): List<Int> = native.freshReply(text.copyOfRange(0, length) + next, replyLength) { false }.tokens
            val afterAll = after(all)
            val afterFar = after(far)
            val afterSaved = after(saved)
            val afterNear = after(near)
            fun replyAfterNext(): List<Int> {
                native.mustEvaluate(next)
                native.configureSampler(greedy(native.contextSize()))
                return native.continueReply(replyLength) { false }
            }
            // The memory holds the whole text, with a checkpoint taken on the way.
            fun fill(): Int {
                native.clearMemory()
                native.mustEvaluate(text.copyOfRange(0, saved))
                val handle = native.saveCheckpoint()
                assertTrue("a checkpoint can be saved", handle >= 0)
                native.mustEvaluate(text.copyOfRange(saved, all))
                return handle
            }

            // A cut far back, before anything was restored. Whatever the session answers must be
            // true: "removed" and the continuation of a fresh evaluation, or "not removed" and a
            // memory that goes on as if nothing had been asked.
            var checkpoint = fill()
            val removedFar = native.rewindTo(far)
            val countFar = native.memoryTokenCount()
            val replyFar = replyAfterNext()
            if (removedFar) {
                assertEquals(far, countFar)
                assertEquals("a cut from $all to $far tokens is exact", afterFar, replyFar)
            } else {
                assertEquals(all, countFar)
                assertEquals("a refused cut leaves the memory as it was", afterAll, replyFar)
            }

            // Through the checkpoint: always possible, always exact.
            checkpoint = fill()
            assertTrue("the checkpoint restores", native.restoreCheckpoint(checkpoint))
            assertEquals(saved, native.memoryTokenCount())
            assertEquals("the continuation after the restore equals a fresh evaluation", afterSaved, replyAfterNext())

            // Below what the restored window reaches: refused, and nothing changed.
            assertTrue(native.restoreCheckpoint(checkpoint))
            val removedLost = native.rewindTo(lost)
            assertFalse("after a restore a cut to $lost tokens, below what the restored window reaches, is refused", removedLost)
            assertEquals(saved, native.memoryTokenCount())
            assertEquals("and the memory continues as an undisturbed one does", afterSaved, replyAfterNext())

            // Within the restored window: made, and exact.
            assertTrue(native.restoreCheckpoint(checkpoint))
            assertTrue("after a restore a cut to $near tokens, within the restored window, is made", native.rewindTo(near))
            assertEquals(near, native.memoryTokenCount())
            assertEquals("and the continuation equals a fresh evaluation", afterNear, replyAfterNext())
            report(
                "${model.id} windowed layers (window $window): a cut from $all to $far tokens ${if (removedFar) "made and exact" else "refused"}; " +
                    "after a restore at $saved a cut to $lost refused and a cut to $near made, each with the continuation of a fresh evaluation",
            )
        } finally {
            native.close()
        }
    }

    @Test
    fun aChunkIsInterruptedWhenCancelled() {
        val native = open(model)
        try {
            val prompt = native.tokens(LONG_QUESTION, false)
            val latency = checkCancellation(native, prompt, recurrent = false, isEnd = isEnd)
            report("${model.id} cancellation: returned %.2f ms after the observer first said stop, in the middle of a chunk of ${prompt.size} tokens".format(latency))
            assertTrue("cancellation is prompt (%.1f ms)".format(latency), latency < 250.0)
        } finally {
            native.close()
        }
    }

    @Test
    fun loadTimeMemoryAndSpeed() {
        val probe = open(model)
        val prompt = try {
            probe.tokens(LONG_QUESTION, false)
        } finally {
            probe.close()
        }
        checkLoadAndMemory("${model.id} mapped", model, NativeSettings(mapModel = true), prompt)
        report("${model.id} speeds: ${schedulingGroup()}")
        reportSpeeds("${model.id} pinned", measureSpeeds(model, NativeSettings(pinThreads = true), prompt, 48), prompt.size)
        reportSpeeds("${model.id} unpinned", measureSpeeds(model, NativeSettings(pinThreads = false), prompt, 48), prompt.size)
    }
}
