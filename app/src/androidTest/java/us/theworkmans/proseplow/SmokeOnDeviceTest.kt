package us.theworkmans.proseplow

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import us.theworkmans.proseplow.chat.ChatState
import us.theworkmans.proseplow.chat.OpenChat
import us.theworkmans.proseplow.chat.TurnPhase
import us.theworkmans.proseplow.core.chat.ChatMessage
import us.theworkmans.proseplow.core.chat.MessageStatus
import us.theworkmans.proseplow.core.chat.Role
import us.theworkmans.proseplow.core.engine.EngineState
import us.theworkmans.proseplow.core.engine.FinishReason
import us.theworkmans.proseplow.core.model.ModelCatalog
import us.theworkmans.proseplow.core.model.ModelSpec
import us.theworkmans.proseplow.core.terms.Terms
import us.theworkmans.proseplow.data.ModelMemorySupport
import us.theworkmans.proseplow.security.AppFiles
import us.theworkmans.proseplow.security.LockState
import us.theworkmans.proseplow.terms.TermsText
import java.io.File
import java.util.Calendar
import java.util.Locale
import kotlin.concurrent.thread

/**
 * The smoke test: the whole app, as a person uses it, on the phone it is about to be installed
 * on, in one run of a few minutes. It is meant to be run by hand before installing one's own
 * build; README.md, "Tests", has the commands.
 *
 * It is opt-in (the instrumentation argument [SMOKE_ARGUMENT] must be `true`, otherwise it is
 * skipped) because it is slow, needs the four bundled models in the installed debug app, and
 * **resets the debug app's data**: every chat, setting and key of `us.theworkmans.proseplow.debug`
 * goes, through the app's own "Reset app data". It runs only inside a debug build
 * ([DeviceTests.requireDevelopmentPackage]); the release app is another package, another user id
 * and another data directory, which nothing in this process can reach.
 *
 * The screens are driven the way an accessibility service drives them (through the
 * instrumentation's UiAutomation, which is one), as the other instrumented tests of this module
 * do; what the app does underneath is read from its own state, in this process: the lock's state
 * and the chat controller's [ChatState]. Nothing is added to the app for the test. The steps,
 * each printed as a `PROSEPLOW smoke` line with its time, and each failing with its name:
 *
 * 1. **Clean start.** The app is opened from its launcher intent and its data is reset; the lock
 *    must then ask for the terms before the set-up.
 * 2. **Terms.** "I agree" pressed in the first second of the first statement is ignored; then
 *    every statement is agreed to, one by one after its second without response, the words
 *    "I agree" are typed and "Agree and continue" is pressed. The set-up must follow.
 * 3. **Passphrase.** A passphrase is created; the app must unlock. Fingerprint unlock, when it is
 *    offered, is declined ("Not now").
 * 4. **Four models.** Each model gets one message in a chat of its own: Shard-2 the beginning
 *    of a text, Peacebell a question about the Second World War, Boris-2 an everyday question,
 *    and Qwen3.5 a long text with a question about it. Each answer must end by itself or at its
 *    length limit (set short beforehand, as a person would in the model's settings, so that the
 *    run takes minutes) and must not be empty.
 * 5. **Information.** The chat screen's "Information" must show the version of the terms that
 *    was agreed to and the date and time of the agreement.
 * 6. **Saved memory.** The app leaves the screen (Home) and comes back within its lock time: the
 *    model's memory of the Qwen3.5 chat must have been saved meanwhile. It is saved only when
 *    leaving the screen (or a lock by itself), never by "Lock now", and only a memory that would
 *    take the phone at least about 20 seconds to read again; that is why Qwen3.5's message is
 *    long (about 3,000 tokens).
 * 7. **Lock and unlock.** "Lock now" from the chat menu must lock; the passphrase must unlock,
 *    back into the Qwen3.5 chat.
 * 8. **Restored, not read again.** A short message in that chat. The engine reports how far the
 *    prompt has been read ([TurnPhase.ReadingPrompt], whose first report counts the tokens kept
 *    in memory): the reading must begin with the earlier conversation already in memory, and
 *    reading the prompt must take a fraction of the time the first message took.
 * 9. **Locked.** "Lock now" again, and the lock screen must be shown: the app is left locked.
 *
 * Nothing prints the text of a prompt or an answer, only counts and times. The phone must be
 * unlocked and its screen on when the run starts; while it runs, a bare Shift key is sent every
 * [KEEP_AWAKE_MILLIS] so that the screen does not time out (a key event that reaches a window
 * counts as activity; it types nothing).
 */
@RunWith(AndroidJUnit4::class)
class SmokeOnDeviceTest {

    private val instrumentation: Instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val context get() = instrumentation.targetContext
    private val app get() = context.applicationContext as ProsePlowApp
    private val appLock get() = app.container.appLock

    private fun string(id: Int, vararg args: Any): String = context.resources.getString(id, *args)

    // --- Reporting -------------------------------------------------------------------------------

    /** Sends one line to whoever started the instrumentation (`am instrument -w` prints it as it comes). */
    private fun report(line: String) {
        val bundle = Bundle()
        bundle.putString(Instrumentation.REPORT_KEY_STREAMRESULT, "\nPROSEPLOW smoke $line")
        instrumentation.sendStatus(0, bundle)
    }

    private fun secondsSince(startMillis: Long): String = seconds(SystemClock.elapsedRealtime() - startMillis)

    private fun seconds(millis: Long): String = String.format(Locale.ROOT, "%.1f s", millis / 1000.0)

    /** Runs one step: its start and its time are reported, and a failure names the step. */
    private fun <T> step(name: String, block: () -> T): T {
        val start = SystemClock.elapsedRealtime()
        report("$name: started")
        try {
            val result = block()
            report("$name: ok in ${secondsSince(start)}")
            return result
        } catch (e: Throwable) {
            report("$name: FAILED after ${secondsSince(start)}: ${e.message}")
            throw AssertionError("Smoke step \"$name\" failed: ${e.message}", e)
        }
    }

    /** Fails with [message] and what this app's windows show at that moment, which is what a person reading the failure needs. */
    private fun fail(message: String): Nothing {
        val shown = try {
            appRoots().flatMap(::nodes).mapNotNull { (it.text ?: it.contentDescription)?.toString()?.take(60) }.filter { it.isNotBlank() }.take(15)
        } catch (_: RuntimeException) {
            emptyList()
        }
        throw AssertionError(if (shown.isEmpty()) "$message (no window of the app is shown)" else "$message (shown: ${shown.joinToString(" | ")})")
    }

    /** [probe] until it gives a value; a failure that says what was waited for after [timeoutMillis]. */
    private fun <T : Any> waitFor(what: String, timeoutMillis: Long, pollMillis: Long = 100, probe: () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (true) {
            probe()?.let { return it }
            if (SystemClock.elapsedRealtime() > deadline) fail("waited ${seconds(timeoutMillis)} for $what")
            SystemClock.sleep(pollMillis)
        }
    }

    private fun waitUntil(what: String, timeoutMillis: Long, pollMillis: Long = 100, condition: () -> Boolean) {
        waitFor(what, timeoutMillis, pollMillis) { if (condition()) Unit else null }
    }

    // --- The screen, as an accessibility service sees it ------------------------------------------

    /** The roots of this app's windows, the active one first (a dialog or a menu is a window of its own). */
    private fun appRoots(): List<AccessibilityNodeInfo> {
        val ownPackage = context.packageName
        val active = automation.rootInActiveWindow?.takeIf { it.packageName == ownPackage }
        val windows = automation.windows.sortedByDescending { it.layer }.mapNotNull { it.root }.filter { it.packageName == ownPackage }
        return listOfNotNull(active) + windows
    }

    private fun nodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        listOf(root) + (0 until root.childCount).mapNotNull(root::getChild).flatMap(::nodes)

    /**
     * The first node that matches [predicate]. Compose leaves what is scrolled out of sight out of
     * the accessibility tree, so when nothing matches, a scrollable container is scrolled on by one
     * step; the caller's polling then looks again. A node already in sight is found without scrolling.
     */
    private fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val roots = appRoots()
        roots.firstNotNullOfOrNull { root -> nodes(root).firstOrNull(predicate) }?.let { return it }
        roots.firstNotNullOfOrNull { root -> nodes(root).firstOrNull { it.isScrollable } }
            ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        return null
    }

    private fun withText(text: String): (AccessibilityNodeInfo) -> Boolean = { it.text?.toString() == text }

    private fun described(description: String): (AccessibilityNodeInfo) -> Boolean = { it.contentDescription?.toString() == description }

    /** The node that can be pressed for [node]: itself or the button around it. */
    private fun pressable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK } || current.className?.toString() == "android.widget.Button") return current
            current = current.parent
        }
        return null
    }

    private fun isShown(predicate: (AccessibilityNodeInfo) -> Boolean): Boolean = findNode(predicate) != null

    private fun waitForShown(what: String, timeoutMillis: Long = UI_TIMEOUT_MILLIS, predicate: (AccessibilityNodeInfo) -> Boolean) {
        waitFor("$what to be on screen", timeoutMillis) { findNode(predicate) }
    }

    /** Presses [what], once it is on screen and enabled. */
    private fun press(what: String, timeoutMillis: Long = UI_TIMEOUT_MILLIS, predicate: (AccessibilityNodeInfo) -> Boolean) {
        val target = waitFor("\"$what\" to be on screen and enabled", timeoutMillis) {
            findNode(predicate)?.let(::pressable)?.takeIf { it.isEnabled }
        }
        if (!target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) fail("\"$what\" did not take the press")
    }

    private fun setText(field: AccessibilityNodeInfo, value: String): Boolean = field.performAction(
        AccessibilityNodeInfo.ACTION_SET_TEXT,
        Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) },
    )

    /** Types [value] into the message field (the editable field that is not a passphrase). */
    private fun typeMessage(value: String) {
        val field = waitFor("the message field", UI_TIMEOUT_MILLIS) { findNode { it.isEditable && !it.isPassword } }
        if (!setText(field, value)) fail("the message field did not take the text")
    }

    /**
     * Enters [value] into the passphrase field number [index] (in screen order). A passphrase
     * field tells the accessibility framework only bullets, one for each character, which is how
     * the text is checked. Should setting the text be refused, it is typed as key events instead.
     */
    private fun enterPassphrase(index: Int, value: String) {
        fun field() = appRoots().firstNotNullOfOrNull { root -> nodes(root).filter { it.isPassword }.getOrNull(index) }
        fun filled() = field()?.text?.length == value.length
        val target = waitFor("passphrase field ${index + 1}", UI_TIMEOUT_MILLIS) { field() }
        if (setText(target, value) && waitBriefly { filled() }) return
        target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        SystemClock.sleep(300)
        instrumentation.sendStringSync(value)
        if (!waitBriefly { filled() }) fail("passphrase field ${index + 1} did not take the passphrase")
    }

    private fun waitBriefly(condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 2_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(100)
        }
        return condition()
    }

    // --- The app's own state -----------------------------------------------------------------------

    private fun lockState(): LockState<AppSession> = appLock.state.value

    private fun session(): AppSession = (lockState() as? LockState.Unlocked)?.session ?: fail("the app is not unlocked (${lockState().javaClass.simpleName})")

    private fun chatState(): ChatState = session().chat.state.value

    private fun openSession() = (chatState().open as? OpenChat.Ready)?.session ?: fail("the chat on screen cannot be used")

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    /** Opens the app as the launcher does: its task comes to the front when it has one. */
    private fun openApp() {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: fail("the app has no launcher intent")
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // --- The test ----------------------------------------------------------------------------------

    @Test
    fun theWholeAppWorksOnThisPhoneWithEveryModel() {
        assumeTrue(
            "The smoke test resets the debug app's data and is run on purpose only: -e $SMOKE_ARGUMENT true (README.md, \"Tests\")",
            InstrumentationRegistry.getArguments().getString(SMOKE_ARGUMENT) == "true",
        )
        DeviceTests.requireDevelopmentPackage()
        val runStart = SystemClock.elapsedRealtime()
        report("on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), package ${context.packageName}")

        // Menus and dialogs are windows of their own; the list of windows is only given with this flag.
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val awake = keepAwake()
        try {
            run()
        } finally {
            awake.interrupt()
        }
        report("all steps passed in ${secondsSince(runStart)}")
    }

    /** Sends a bare Shift key every [KEEP_AWAKE_MILLIS] until interrupted, so that the screen stays on. */
    private fun keepAwake(): Thread = thread(name = "smoke-keep-awake", isDaemon = true) {
        try {
            while (true) {
                Thread.sleep(KEEP_AWAKE_MILLIS)
                for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                    val now = SystemClock.uptimeMillis()
                    automation.injectInputEvent(KeyEvent(now, now, action, KeyEvent.KEYCODE_SHIFT_LEFT, 0), true)
                }
            }
        } catch (_: InterruptedException) {
            // The run is over.
        }
    }

    private fun run() {
        step("clean start") {
            // A notification permission dialog would cover the app at the first answer.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            }
            openApp()
            // The activity makes the app's objects; nothing of them is touched before it is on screen.
            waitUntil("the app to be on screen", START_TIMEOUT_MILLIS) { appRoots().isNotEmpty() }
            waitUntil("the lock to start", START_TIMEOUT_MILLIS) { lockState() != LockState.Starting }
            report("found the app ${lockState().javaClass.simpleName}; resetting the debug app's data")
            val resets = appLock.resets.value
            onMain { appLock.reset() }
            waitUntil("the reset to finish", START_TIMEOUT_MILLIS) { appLock.resets.value > resets && lockState() != LockState.Starting }
            appLock.resetFailure.value?.let { fail("the reset was not complete: $it") }
            val state = lockState()
            if (state != LockState.TermsRequired(beforeSetup = true)) fail("after a reset the terms must come before the set-up, found $state")
        }

        step("terms") {
            val total = TermsText.statementCount
            fun progress(step: Int) = string(R.string.terms_progress, step, total)
            val agree = string(R.string.terms_agree)
            waitForShown(progress(1)) { withText(progress(1))(it) }
            // Pressed at once, before its second without response is over: nothing happens.
            findNode(withText(agree))?.let(::pressable)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            SystemClock.sleep(300)
            if (!isShown(withText(progress(1)))) fail("\"$agree\" was taken in the first second of the first statement")
            for (step in 1..total) {
                waitForShown(progress(step)) { withText(progress(step))(it) }
                SystemClock.sleep(TERMS_BUTTON_WAIT_MILLIS)
                press(agree) { withText(agree)(it) }
            }
            waitForShown("the confirmation") { withText(string(R.string.terms_confirm_title))(it) }
            typeMessage(TERMS_WORDS)
            press(string(R.string.terms_continue)) { withText(string(R.string.terms_continue))(it) }
            waitUntil("the set-up after the terms", UI_TIMEOUT_MILLIS) { lockState() == LockState.SetupRequired }
            report("agreed to $total statements")
        }

        step("passphrase") {
            waitForShown("the set-up") { withText(string(R.string.setup_title))(it) }
            enterPassphrase(0, PASSPHRASE)
            enterPassphrase(1, PASSPHRASE)
            press(string(R.string.setup_create)) { withText(string(R.string.setup_create))(it) }
            waitUntil("the app to unlock after the set-up", UNLOCK_TIMEOUT_MILLIS) { lockState() is LockState.Unlocked<*> }
            declineFingerprintIfOffered()
            waitForShown("the chat screen") { described(string(R.string.information))(it) }
        }

        val chats = step("four models") {
            val offered = chatState().models.map { it.id }
            val wanted = CASES.map { it.model.id }
            if (!offered.containsAll(wanted)) fail("this build offers $offered; the smoke test needs all of $wanted (build without -Pproseplow.models)")
            onMain {
                session().settings.update(urgent = true) { settings ->
                    CASES.fold(settings) { changed, case -> changed.withMaxNewTokens(case.model.id, case.replyTokens) }
                }
            }
            CASES.mapIndexed { index, case -> step("answer from ${case.model.displayName}") { chatWith(case, newChat = index > 0) } }
        }
        val qwen = chats.last()

        step("information") {
            press(string(R.string.information)) { described(string(R.string.information))(it) }
            val version = string(R.string.terms_record_version, Terms.TERMS_VERSION)
            waitForShown(version) { withText(version)(it) }
            val before = string(R.string.terms_record_when, MARK).substringBefore(MARK)
            val agreed = waitFor("the time of the agreement", UI_TIMEOUT_MILLIS) { findNode { it.text?.startsWith(before) == true }?.text?.toString() }
            val year = Calendar.getInstance().get(Calendar.YEAR).toString()
            if (!agreed.contains(year)) fail("the time of the agreement does not name this year ($year)")
            val first = string(R.string.terms_numbered, 1, TermsText.statements(context.resources).first())
            waitForShown("the first statement") { withText(first)(it) }
            // The system's back action, as a person leaves the screen; the screen's own "Back" does the same.
            if (!automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) fail("Back was not pressed")
            waitForShown("the chat screen") { described(string(R.string.information))(it) }
        }

        val memoryKept = ModelMemorySupport.supported(Build.VERSION.SDK_INT) && session().keepModelMemory
        if (!memoryKept) report("the model's memory is not kept on this phone (Android 12 or later, and the setting on): its restore is not checked")
        val memoryFile = File(DataLayout(app.container.files).modelMemory, qwen.chatId + AppFiles.ENCRYPTED_SUFFIX)

        if (memoryKept) {
            step("saved memory on leaving the screen") {
                val unlocked = session()
                if (!automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)) fail("Home was not pressed")
                waitUntil("the app to leave the screen", UI_TIMEOUT_MILLIS) { appRoots().isEmpty() }
                val left = SystemClock.elapsedRealtime()
                waitUntil(
                    "the model's memory of the ${qwen.model.displayName} chat to be saved (it is saved only when reading it again would take at least " +
                        "about 20 s; the first message's prompt of ${qwen.promptTokens} tokens took ${seconds(qwen.readingMillis)} to read)",
                    SAVE_TIMEOUT_MILLIS,
                ) { memoryFile.isFile }
                report("memory saved ${secondsSince(left)} after leaving the screen, ${memoryFile.length() / (1024 * 1024)} MiB")
                openApp()
                waitForShown("the chat screen again") { described(string(R.string.information))(it) }
                if ((lockState() as? LockState.Unlocked)?.session !== unlocked) fail("the app locked while it was away (${lockState().javaClass.simpleName})")
            }
        }

        step("lock now") { lockNow() }

        step("unlock") {
            enterPassphrase(0, PASSPHRASE)
            press(string(R.string.lock_unlock)) { withText(string(R.string.lock_unlock))(it) }
            waitUntil("the app to unlock", UNLOCK_TIMEOUT_MILLIS) { lockState() is LockState.Unlocked<*> }
            declineFingerprintIfOffered()
            waitForShown("the chat screen") { described(string(R.string.information))(it) }
            waitUntil("the ${qwen.model.displayName} chat to be on screen", UI_TIMEOUT_MILLIS) { chatState().open.id == qwen.chatId }
            val messages = openSession().messages.size
            if (messages != 2) fail("the ${qwen.model.displayName} chat came back with $messages messages instead of 2")
        }

        step("restored, not read again") {
            // The restore starts at the unlock, for the chat on screen; the message waits for it in any case.
            var sawRestoring = waitBriefly { chatState().restoringMemory }
            waitUntil("the restore to end", RESTORE_TIMEOUT_MILLIS) { !chatState().restoringMemory }
            val follow = send(qwen.model, FOLLOW_UP)
            sawRestoring = sawRestoring || follow.sawRestoring
            val firstRead = follow.firstReading
            report(
                "first message: ${qwen.promptTokens} tokens read in ${seconds(qwen.readingMillis)}; after the unlock: " +
                    (firstRead?.let { "reading began at token ${it.evaluatedTokens} of ${it.totalTokens}" } ?: "no reading reported") +
                    ", ${seconds(follow.readingMillis)} to the first word; restore seen: $sawRestoring",
            )
            if (memoryKept) {
                if (firstRead != null && firstRead.evaluatedTokens < qwen.promptTokens * KEPT_SHARE) {
                    fail("the conversation was read again: reading began at token ${firstRead.evaluatedTokens} of ${firstRead.totalTokens}")
                }
                if (follow.readingMillis * READING_SPEED_UP > qwen.readingMillis) {
                    fail("reading took ${seconds(follow.readingMillis)}, not much less than the ${seconds(qwen.readingMillis)} of the first message")
                }
            }
        }

        step("locked") { lockNow() }
    }

    /** "Lock now" from the chat menu; the lock screen must follow. */
    private fun lockNow() {
        press(string(R.string.more_options)) { described(string(R.string.more_options))(it) }
        press(string(R.string.security_lock_now)) { withText(string(R.string.security_lock_now))(it) }
        waitUntil("the app to lock", UI_TIMEOUT_MILLIS) { lockState() is LockState.Locked }
        waitForShown("the lock screen") { withText(string(R.string.lock_unlock))(it) }
    }

    /** Declines fingerprint unlock when the app offers it (only on a phone with an enrolled fingerprint). */
    private fun declineFingerprintIfOffered() {
        if (!waitBriefly { appLock.fingerprintOffer.value != null }) {
            report("fingerprint unlock was not offered")
            return
        }
        press(string(R.string.not_now)) { withText(string(R.string.not_now))(it) }
        waitUntil("the offer to close", UI_TIMEOUT_MILLIS) { appLock.fingerprintOffer.value == null }
        report("fingerprint unlock was offered and declined")
    }

    /** What became of one message: where the chat is, and how its prompt was read. */
    private class Exchange(val model: ModelSpec, val chatId: String, val promptTokens: Int, val readingMillis: Long)

    /** Opens a chat with [case]'s model (a new one unless [newChat] is false), sends its message and checks the answer. */
    private fun chatWith(case: Case, newChat: Boolean): Exchange {
        if (newChat) {
            val previous = chatState().open.id
            press(string(R.string.new_chat)) { described(string(R.string.new_chat))(it) }
            waitUntil("a new chat", UI_TIMEOUT_MILLIS) { chatState().open.id != previous && openSession().messages.isEmpty() }
        }
        val current = openSession().modelId
        if (current != case.model.id) {
            val shown = ModelCatalog.find(current)?.displayName ?: fail("the chat has the unknown model $current")
            press("the model $shown") { withText(shown)(it) }
            press(case.model.displayName) { withText(case.model.displayName)(it) }
            waitUntil("the chat to use ${case.model.displayName}", UI_TIMEOUT_MILLIS) { openSession().modelId == case.model.id }
        }
        val reply = send(case.model, case.prompt)
        val tokens = reply.promptTokens
        report(
            "${case.model.displayName}: prompt of ${tokens ?: "?"} tokens, ${seconds(reply.readingMillis)} to the first word, " +
                "${reply.answer.text.length} characters, ended ${reply.answer.finishReason}, ${seconds(reply.totalMillis)} in all",
        )
        if (case.long) {
            if (tokens == null || tokens < LONG_PROMPT_TOKENS) fail("the long message was read as ${tokens ?: "no"} tokens; at least $LONG_PROMPT_TOKENS are needed for a memory worth saving")
        }
        return Exchange(case.model, reply.chatId, tokens ?: 0, reply.readingMillis)
    }

    /** One answer, as watched through the chat's state. */
    private class Reply(
        val chatId: String,
        val answer: ChatMessage,
        val firstReading: TurnPhase.ReadingPrompt?,
        val promptTokens: Int?,
        val readingMillis: Long,
        val totalMillis: Long,
        val sawRestoring: Boolean,
    )

    /**
     * Types [text], presses "Send" and watches the reply in the chat's state until it has ended:
     * the first report of the prompt's reading (which counts the tokens kept in memory), the
     * length of the prompt, the time to the first word and the time in all. The answer must be
     * complete, have ended by itself or at its length limit, and not be empty.
     */
    private fun send(model: ModelSpec, text: String): Reply {
        val chatId = chatState().open.id
        val before = openSession().messages.size
        // Loaded first, so that the times below are those of reading and writing, not of loading.
        waitUntil("${model.displayName} to be loaded", LOAD_TIMEOUT_MILLIS) {
            val engine = chatState().engine
            if (engine is EngineState.Failed) fail("${model.displayName} could not be loaded: ${engine.error}")
            engine == EngineState.Ready(model.id)
        }
        typeMessage(text)
        press(string(R.string.send)) { described(string(R.string.send))(it) }
        val sentAt = SystemClock.elapsedRealtime()
        var firstReading: TurnPhase.ReadingPrompt? = null
        var promptTokens: Int? = null
        var writingAt: Long? = null
        var sawRestoring = false
        val deadline = SystemClock.elapsedRealtime() + ANSWER_TIMEOUT_MILLIS
        while (true) {
            val state = chatState()
            val now = SystemClock.elapsedRealtime()
            if (state.restoringMemory) sawRestoring = true
            when (val phase = state.turn?.takeIf { it.sessionId == chatId }?.phase) {
                is TurnPhase.ReadingPrompt -> {
                    if (firstReading == null) firstReading = phase
                    promptTokens = maxOf(promptTokens ?: 0, phase.totalTokens)
                }
                TurnPhase.Writing -> if (writingAt == null) writingAt = now
                else -> Unit
            }
            val messages = (state.open as? OpenChat.Ready)?.session?.takeIf { it.id == chatId }?.messages.orEmpty()
            val last = messages.lastOrNull()
            if (state.turn == null && messages.size >= before + 2 && last?.role == Role.ASSISTANT && last.status != MessageStatus.STREAMING) {
                if (last.status != MessageStatus.COMPLETE || last.finishReason !in ENDED) {
                    fail("${model.displayName}'s answer did not end properly: ${last.status}, ${last.finishReason} (engine ${state.engine})")
                }
                if (last.text.isBlank()) fail("${model.displayName}'s answer is empty")
                if (isShown(described(string(R.string.stop)))) fail("\"Stop\" is still shown after the answer ended")
                return Reply(chatId, last, firstReading, promptTokens, (writingAt ?: now) - sentAt, now - sentAt, sawRestoring)
            }
            if (now > deadline) fail("${model.displayName} did not finish its answer within ${seconds(ANSWER_TIMEOUT_MILLIS)} (engine ${state.engine}, turn ${state.turn?.phase})")
            SystemClock.sleep(WATCH_POLL_MILLIS)
        }
    }

    /**
     * One model's message. [replyTokens] is set as the model's longest reply before the chats
     * begin; [long] marks the message whose memory is to be saved and restored.
     */
    private class Case(val model: ModelSpec, val prompt: String, val replyTokens: Int, val long: Boolean = false)

    private companion object {
        /** The instrumentation argument that has to be `true` for the test to run. */
        const val SMOKE_ARGUMENT = "smoke"

        const val PASSPHRASE = "smoke test passphrase"
        const val TERMS_WORDS = "I agree"
        const val MARK = "MARK"

        const val KEEP_AWAKE_MILLIS = 40_000L
        const val UI_TIMEOUT_MILLIS = 15_000L
        const val START_TIMEOUT_MILLIS = 60_000L
        const val UNLOCK_TIMEOUT_MILLIS = 60_000L
        const val LOAD_TIMEOUT_MILLIS = 180_000L
        const val ANSWER_TIMEOUT_MILLIS = 300_000L
        const val RESTORE_TIMEOUT_MILLIS = 60_000L

        /** Within the app's default lock time of a minute, so that the app is still unlocked on return. */
        const val SAVE_TIMEOUT_MILLIS = 40_000L

        /** A little more than the second during which a new statement's buttons do not respond. */
        const val TERMS_BUTTON_WAIT_MILLIS = 1_300L
        const val WATCH_POLL_MILLIS = 20L

        /** Qwen3.5 reads about 2,000 tokens in 30 s on the tested phone; less would hardly be worth saving anywhere. */
        const val LONG_PROMPT_TOKENS = 2_000

        /** The share of the first prompt that must already be in memory when the message after the unlock is read. */
        const val KEPT_SHARE = 0.8

        /** How many times faster the prompt after the unlock must be read than the first, long one. */
        const val READING_SPEED_UP = 4

        val ENDED = setOf(FinishReason.END_OF_TURN, FinishReason.STOP_STRING, FinishReason.LENGTH)

        val PLACES = listOf("old mill", "bakery", "school", "church", "station", "market", "bridge", "harbour")
        val TURNS = listOf("north", "east", "south", "west")

        /**
         * About 3,000 tokens for Qwen3.5: 160 numbered lines of a plain description, and a
         * question whose answer is one word. Reading it takes the tested phone about 45 seconds,
         * well over the 20 seconds below which a memory is not worth saving.
         */
        val LONG_TEXT: String = buildString {
            for (line in 1..160) {
                append("Line ").append(line).append(": the river runs past the ").append(PLACES[line % PLACES.size])
                append(" and turns ").append(TURNS[line % TURNS.size]).append(" before the next field.\n")
            }
            append("\nReply with one word: what runs past the old mill in these lines?")
        }

        const val FOLLOW_UP = "Reply with one word: which way does it turn at the bakery?"

        /** In this order, so that the long Qwen3.5 chat is the conversation the engine holds last. */
        val CASES = listOf(
            Case(ModelCatalog.SHARD_2, "The old lighthouse stood at the edge of the harbour, and every evening", replyTokens = 48),
            Case(ModelCatalog.PEACEBELL_V1_291M, "In which year did the Second World War end?", replyTokens = 64),
            Case(ModelCatalog.BORIS_2_INSTRUCT, "What colour is the sky on a clear day?", replyTokens = 64),
            Case(ModelCatalog.QWEN35_0_8B, LONG_TEXT, replyTokens = 32, long = true),
        )
    }
}
