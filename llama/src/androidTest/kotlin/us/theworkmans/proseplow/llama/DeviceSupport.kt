package us.theworkmans.proseplow.llama

import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import us.theworkmans.proseplow.core.engine.EvaluationObserver
import us.theworkmans.proseplow.core.engine.NativeLlama
import us.theworkmans.proseplow.core.engine.SamplerConfig
import us.theworkmans.proseplow.core.model.ModelSpec
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
 * Shared by the on-device tests: opening a model of this build, driving it the way the engine
 * does, measuring, and reading the committed reference files of the host tests
 * (native/tests/data, packaged as assets of the test APK; the formats are described in
 * native/tests/refgen/formats.py).
 *
 * Nothing here prints or logs prompt or reply text. A measurement is a line of numbers sent to
 * the instrumentation, which `am instrument -w` and the Gradle test report show.
 */

internal val appContext: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

/** Sends one line of the test's report to whoever started the instrumentation. */
internal fun report(line: String) {
    val bundle = Bundle()
    bundle.putString(Instrumentation.REPORT_KEY_STREAMRESULT, "\nPROSEPLOW $line")
    InstrumentationRegistry.getInstrumentation().sendStatus(0, bundle)
}

/** Skips the calling test unless this build carries [model] (as an asset or sideloaded). */
internal fun assumePresent(model: ModelSpec) {
    assumeTrue(
        "${model.id} is not in this test APK and was not sideloaded (see native/README.md, \"On the phone\")",
        ModelFiles.locate(appContext, model) != null,
    )
}

/** Opens [model] with [settings] and reports nothing; the caller closes the result. */
internal fun open(model: ModelSpec, settings: NativeSettings = NativeSettings()): LlamaSession =
    LlamaFactory(appContext, settings).open(model) { true } as LlamaSession

/** An observer that never cancels and ignores progress. */
internal object Uninterrupted : EvaluationObserver {
    override fun onProgress(evaluatedTokens: Int) = Unit
    override fun isCancelled(): Boolean = false
}

/** The chunk the engine evaluates prompts in by default. */
internal const val ENGINE_CHUNK: Int = 32

internal fun greedy(contextSize: Int, banned: List<Int> = emptyList()): SamplerConfig = SamplerConfig(
    temperature = 0f, topK = 0, topP = 1f, minP = 0f, repetitionPenalty = 1f, presencePenalty = 0f, frequencyPenalty = 0f,
    penaltyWindow = contextSize, seed = 0, bannedTokenIds = banned,
)

/** Evaluates all of [tokens] or fails the test. Returns the nanoseconds it took. */
internal fun NativeLlama.mustEvaluate(tokens: IntArray, chunk: Int = ENGINE_CHUNK): Long {
    val start = System.nanoTime()
    val evaluated = evaluate(tokens, chunk, Uninterrupted)
    check(evaluated == tokens.size) { "Only $evaluated of ${tokens.size} tokens were evaluated" }
    return System.nanoTime() - start
}

/** The tokens of a reply and what producing them cost. */
internal class Reply(val tokens: List<Int>, val promptTokens: Int, val promptNanos: Long, val generationNanos: Long) {
    val promptPerSecond: Double get() = promptTokens * 1e9 / promptNanos
    val generationPerSecond: Double get() = tokens.size * 1e9 / generationNanos
}

/**
 * Samples and evaluates token by token from the ready prediction, as the engine does: until
 * [isEnd] says a token ends the reply or [maxNew] tokens. The ending token is neither part of
 * the result nor evaluated, and neither is the last token of a reply cut by the limit.
 */
internal fun NativeLlama.continueReply(maxNew: Int, isEnd: (Int) -> Boolean): List<Int> {
    val reply = ArrayList<Int>()
    val one = IntArray(1)
    while (reply.size < maxNew) {
        val token = sampleNext()
        if (isEnd(token)) break
        reply += token
        if (reply.size < maxNew) {
            one[0] = token
            mustEvaluate(one, 1)
        }
    }
    return reply
}

/** An empty memory, [prompt], a greedy reply. */
internal fun NativeLlama.freshReply(prompt: IntArray, maxNew: Int, banned: List<Int> = emptyList(), isEnd: (Int) -> Boolean): Reply {
    clearMemory()
    val promptNanos = mustEvaluate(prompt)
    configureSampler(greedy(contextSize(), banned))
    val start = System.nanoTime()
    val tokens = continueReply(maxNew, isEnd)
    return Reply(tokens, prompt.size, promptNanos, System.nanoTime() - start)
}

internal fun NativeLlama.tokens(text: String, parseSpecial: Boolean): IntArray =
    if (text.isEmpty()) IntArray(0) else tokenize(text.toByteArray(Charsets.UTF_8), parseSpecial)

// --- Memory ---

/** This process's memory as the kernel accounts it, in MiB: proportional set size and its parts. */
internal class Memory(val pss: Double, val anonymous: Double, val file: Double, val swap: Double) {
    override fun toString(): String = "PSS %.0f MiB (anonymous %.0f, file-backed %.0f), swapped %.0f MiB".format(pss, anonymous, file, swap)
}

internal fun memory(): Memory {
    val fields = HashMap<String, Double>()
    File("/proc/self/smaps_rollup").forEachLine { line ->
        val match = SMAPS_FIELD.matchEntire(line.trim()) ?: return@forEachLine
        fields[match.groupValues[1]] = match.groupValues[2].toDouble() / 1024.0
    }
    return Memory(
        pss = fields["Pss"] ?: 0.0,
        anonymous = fields["Pss_Anon"] ?: 0.0,
        file = (fields["Pss_File"] ?: 0.0) + (fields["Pss_Shmem"] ?: 0.0),
        swap = fields["SwapPss"] ?: fields["Swap"] ?: 0.0,
    )
}

private val SMAPS_FIELD = Regex("""(\w+):\s+(\d+) kB""")

// --- The reference files of the host tests ---

/** A little-endian reader over a whole reference file. */
internal class ReferenceReader(assetName: String) {
    private val buffer: ByteBuffer = ByteBuffer.wrap(
        InstrumentationRegistry.getInstrumentation().context.assets.open(assetName).use { it.readBytes() },
    ).order(ByteOrder.LITTLE_ENDIAN)

    fun magic(expected: String) {
        val found = String(bytes(8), Charsets.US_ASCII)
        check(found == expected) { "Not a $expected file" }
    }

    fun u8(): Int = buffer.get().toInt() and 0xFF
    fun u32(): Int = buffer.int
    fun bytes(count: Int): ByteArray = ByteArray(count).also { buffer.get(it) }
    fun text(): String = String(bytes(u32()), Charsets.UTF_8)
    fun idsU16(): IntArray = IntArray(u32()) { buffer.short.toInt() and 0xFFFF }
    fun idsI32(count: Int): IntArray = IntArray(count) { buffer.int }
    fun skip(count: Long) {
        buffer.position((buffer.position() + count).toInt())
    }
    val atEnd: Boolean get() = !buffer.hasRemaining()
}

/** A text of a token vector file with the reference's ids, as plain text and with special tokens parsed. */
internal class TextVector(val text: String, val exact: IntArray, val safe: IntArray)

/** A conversation of a token vector file: role and content per message. */
internal class ChatVector(val messages: List<Pair<String, String>>, val addGenerationPrompt: Boolean, val exact: IntArray, val safe: IntArray)

internal class TokenVectors(val texts: List<TextVector>, val chats: List<ChatVector>)

/** Reads a PPTOKV01 file. */
internal fun readTokenVectors(assetName: String): TokenVectors {
    val reader = ReferenceReader(assetName)
    reader.magic("PPTOKV01")
    reader.bytes(64) // the tokenizer file's hash; the host tests compare it with the model's
    val texts = List(reader.u32()) {
        val text = reader.text()
        val exact = reader.idsU16()
        TextVector(text, exact, if (reader.u8() != 0) reader.idsU16() else exact)
    }
    val chats = List(reader.u32()) {
        val messages = List(reader.u32()) { reader.text() to reader.text() }
        val addGenerationPrompt = reader.u8() != 0
        val exact = reader.idsU16()
        ChatVector(messages, addGenerationPrompt, exact, if (reader.u8() != 0) reader.idsU16() else exact)
    }
    check(reader.atEnd) { "$assetName has trailing bytes" }
    return TokenVectors(texts, chats)
}

/**
 * A conversation of a logit reference: the prompt's tokens, the reference model's greedy reply,
 * and the whole token sequence the reference's logits were computed for (which begins with the
 * prompt and, in the references made for windowed layers, is a long text and nothing else).
 */
internal class GreedyReference(val prompt: IntArray, val reply: IntArray, val sequence: IntArray)

/** Reads the prompts and greedy replies of a PPLOGIT1 file; the logits are skipped. */
internal fun readGreedyReferences(assetName: String): List<GreedyReference> {
    val reader = ReferenceReader(assetName)
    reader.magic("PPLOGIT1")
    val vocabulary = reader.u32()
    val sequences = reader.u32()
    val topK = reader.u32()
    val out = List(sequences) {
        val tokens = reader.u32()
        val promptTokens = reader.u32()
        val greedyTokens = reader.u32()
        val fullRows = reader.u32()
        val ids = reader.idsI32(tokens)
        val greedy = reader.idsI32(greedyTokens)
        reader.skip(8L * tokens * topK + 4L * fullRows + 4L * fullRows * vocabulary)
        GreedyReference(ids.copyOfRange(0, promptTokens), greedy, ids)
    }
    check(reader.atEnd) { "$assetName has trailing bytes" }
    return out
}

/** A request of a chat reference: a whole conversation, its tokens and the reference's reply. */
internal class ChatRequest(val messages: List<Pair<String, String>>, val prompt: IntArray, val reply: IntArray, val ended: Boolean, val text: String)

internal class ChatReference(val maxNewTokens: Int, val requests: List<ChatRequest>)

/** Reads a PPCHAT01 file. */
internal fun readChatReference(assetName: String): ChatReference {
    val reader = ReferenceReader(assetName)
    reader.magic("PPCHAT01")
    reader.bytes(64)
    val maxNewTokens = reader.u32()
    val requests = List(reader.u32()) {
        val messages = List(reader.u32()) { reader.text() to reader.text() }
        val prompt = reader.idsI32(reader.u32())
        val reply = reader.idsI32(reader.u32())
        val ended = reader.u8() != 0
        ChatRequest(messages, prompt, reply, ended, reader.text())
    }
    check(reader.atEnd) { "$assetName has trailing bytes" }
    return ChatReference(maxNewTokens, requests)
}
