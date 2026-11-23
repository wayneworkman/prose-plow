package us.theworkmans.proseplow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Rules about the shape of the code that the compiler does not enforce, checked by reading the
 * sources: which package may know which, that the lock manager is only reached under the lock's
 * mutex, and that the instrumented tests can only ever work on keys and files of their own.
 */
class SourceRulesTest {

    /** The module directory: Gradle runs the unit tests from it. */
    private val module = File("").absoluteFile

    private fun sources(directory: String): List<File> {
        val root = File(module, directory)
        assertTrue("$root is where the sources are", root.isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun imports(file: File): List<String> = file.readLines().filter { it.startsWith("import ") }.map { it.removePrefix("import ").trim() }

    private val main = "src/main/java/us/theworkmans/proseplow"
    private val app = "us.theworkmans.proseplow"

    /** The lines of [file] that are code, trimmed: comments are left out, so that a rule is not met (or broken) by one. */
    private fun code(file: File): List<String> =
        file.readLines().map { it.trim() }.filterNot { it.startsWith("*") || it.startsWith("//") || it.startsWith("/**") }

    /** The names of the files among [files] whose code mentions [word]. */
    private fun users(files: List<File>, word: String): List<String> = files.filter { file -> code(file).any { it.contains(word) } }.map { it.name }.sorted()

    /**
     * The top-level functions of [file], by name, each with its code as one text (from its
     * declaration to the next one). Enough to say which function composes what.
     */
    private fun functions(file: File): Map<String, String> {
        val text = code(file).joinToString("\n")
        val starts = Regex("""(?m)^(?:internal |private |public )?fun (?:[A-Za-z.]+\.)?(\w+)\(""").findAll(text).toList()
        return starts.mapIndexed { index, match ->
            val end = starts.getOrNull(index + 1)?.range?.first ?: text.length
            match.groupValues[1] to text.substring(match.range.first, end)
        }.toMap(LinkedHashMap())
    }

    /** A dialog, a sheet or a menu of its own: something that is shown in another window than the activity's. */
    private val opensAWindow = Regex("""(?<![A-Za-z])(AlertDialog|BasicAlertDialog|Dialog|ModalBottomSheet|Popup|DropdownMenu|ExposedDropdownMenu)\(""")

    @Test
    fun theSecurityPackageKnowsNeitherTheAppNorItsScreens() {
        for (file in sources("$main/security")) {
            val foreign = imports(file).filter { it.startsWith("$app.") && !it.startsWith("$app.security.") }
            assertEquals("${file.name} imports from outside the security package", emptyList<String>(), foreign)
        }
    }

    @Test
    fun theLockCoreHasNoAndroidDependency() {
        // Everything in the security package runs in the JVM unit tests, except the one class
        // that is the Android Keystore.
        for (file in sources("$main/security").filterNot { it.name == "AndroidKeyVault.kt" }) {
            val android = imports(file).filter { it.startsWith("android.") || it.startsWith("androidx.") }
            assertEquals("${file.name} depends on Android", emptyList<String>(), android)
        }
    }

    @Test
    fun packagesDependInOneDirectionOnly() {
        // ui knows nothing of the app's other packages; lock knows security and ui; nothing knows the root.
        for (file in sources("$main/ui")) {
            val upwards = imports(file).filter { it.startsWith("$app.") && !it.startsWith("$app.ui.") && it != "$app.R" }
            assertEquals("${file.name} in ui imports another package of the app", emptyList<String>(), upwards)
        }
        for (file in sources("$main/lock")) {
            val allowed = listOf("$app.security.", "$app.ui.", "$app.lock.")
            val other = imports(file).filter { it.startsWith("$app.") && allowed.none(it::startsWith) && it != "$app.R" }
            assertEquals("${file.name} in lock imports the app root", emptyList<String>(), other)
        }
    }

    @Test
    fun theStoresKnowNeitherTheScreensNorAndroid() {
        // data sits on the lock's primitives and the core, and under everything that shows something.
        for (file in sources("$main/data")) {
            val allowed = listOf("$app.data.", "$app.security.", "$app.core.")
            val other = imports(file).filter { it.startsWith("$app.") && allowed.none(it::startsWith) }
            assertEquals("${file.name} in data imports a package above it", emptyList<String>(), other)
            val android = imports(file).filter { it.startsWith("android.") || it.startsWith("androidx.") }
            assertEquals("${file.name} depends on Android", emptyList<String>(), android)
        }
        // And nothing below the screens knows them.
        for (file in sources("$main/chat")) {
            val upwards = imports(file).filter { it.startsWith("$app.settings.") || it.startsWith("$app.lock.") }
            assertEquals("${file.name} in chat imports a screen that is not its own", emptyList<String>(), upwards)
        }
    }

    /**
     * Chat text, prompts and drafts are only ever stored encrypted. Saved instance state is
     * written by the system, in the clear, so nothing in the packages that handle such text may
     * use it for anything but a flag or a scroll position.
     */
    @Test
    fun nothingDecryptedCanReachTheSavedInstanceState() {
        for (file in sources("$main/chat") + sources("$main/settings") + sources("$main/data")) {
            assertTrue("${file.name} uses a SavedStateHandle", imports(file).none { it.endsWith(".SavedStateHandle") })
            val saved = file.readLines().map { it.trim() }.filter { it.contains("rememberSaveable") && !it.startsWith("import ") && !it.startsWith("*") }
            for (line in saved) {
                val harmless = line.contains("mutableStateOf(true)") || line.contains("mutableStateOf(false)") || line.contains("LazyListState.Saver")
                assertTrue("${file.name} saves something that is not a flag or a scroll position: $line", harmless)
            }
        }
    }

    @Test
    fun theUnlockedAppsSourcesArePlainAscii() {
        val files = sources("$main/chat") + sources("$main/data") + sources("$main/settings") +
            listOf("AppSession.kt", "AppNavigation.kt", "ui/Notices.kt", "ui/Links.kt").map { File(module, "$main/$it") }
        for (file in files) {
            val lines = file.readLines().withIndex().filter { (_, line) -> line.any { it.code > 127 } }.map { it.index + 1 }
            assertEquals("${file.name} has characters outside ASCII on these lines; escape them, or move the text to strings.xml", emptyList<Int>(), lines)
        }
    }

    /**
     * Every Kotlin source of the module, tests included, is plain ASCII. A character outside it
     * is written as an escape: two strings that only differ in how an accent is composed look
     * the same on screen, a combining mark between quotes is invisible, and an editor that
     * normalises what it saves would change either without a trace.
     */
    @Test
    fun everyKotlinSourceIsPlainAscii() {
        for (file in sources("src/main/java") + sources("src/test/java") + sources("src/androidTest/java")) {
            val lines = file.readLines().withIndex().filter { (_, line) -> line.any { it.code > 127 } }.map { it.index + 1 }
            assertEquals("${file.name} has characters outside ASCII on these lines; write them as \\u escapes", emptyList<Int>(), lines)
        }
    }

    @Test
    fun theLockManagerIsOnlyReachedUnderTheLocksMutex() {
        // In AppLock the manager is named in exactly two places: its constructor parameter, and
        // withManager, which holds the mutex. Everything else gets it handed in from there.
        val lines = File(module, "$main/security/AppLock.kt").readLines()
        val uses = lines.map { it.trim() }.filter { Regex("""\bmanager\b""").containsMatchIn(it) && !it.startsWith("*") && !it.startsWith("//") && !it.startsWith("/**") }
        assertEquals(
            listOf(
                "private val manager: LockManager,",
                "private suspend inline fun <T> withManager(block: (LockManager) -> T): T = mutex.withLock { block(manager) }",
            ),
            uses,
        )
    }

    /**
     * What decides whether the app can be captured is carried out in exactly one class: the
     * window's secure flag, the app switcher's preview and the screen-off broadcast that ends a
     * screenshot allowance. Nothing else sets or clears the flag (so nothing can clear it by
     * accident), no dialog makes itself capturable (the only one that does not inherit is the
     * passphrase dialog, which is always secure), the preview is only ever turned off, the
     * receiver is not exported and is unregistered again, and the activity only asks the policy
     * through the call that first ends an allowance whose time is up, with the lock's state as
     * it is at that moment, whenever either of the two changes. And nothing about screenshots
     * is stored: the lock configuration does not know the word.
     */
    @Test
    fun theWindowPolicyIsCarriedOutInExactlyOnePlace() {
        val all = sources(main)
        for (word in listOf("FLAG_SECURE", "setRecentsScreenshotEnabled", "ACTION_SCREEN_OFF")) {
            assertEquals("$word is handled outside the class that owns the window policy", listOf("ScreenSecurity.kt"), users(all, word))
        }
        assertEquals("only the passphrase dialog decides about its own secure flag", listOf("PassphraseDialogs.kt"), users(all, "SecureFlagPolicy"))
        assertEquals(
            "and it decides one thing: always secure",
            listOf("import androidx.compose.ui.window.SecureFlagPolicy", "properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),"),
            code(File(module, "$main/lock/PassphraseDialogs.kt")).filter { it.contains("SecureFlagPolicy") },
        )
        assertEquals("no window opts out of the secure flag", emptyList<String>(), users(all, "SecureOff"))

        val owner = code(File(module, "$main/ScreenSecurity.kt"))
        assertEquals(
            listOf("window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)", "window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)"),
            owner.filter { it.contains("FLAG_SECURE") },
        )
        assertEquals(
            "the app switcher's preview is turned off unconditionally, and never on",
            listOf("if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) activity.setRecentsScreenshotEnabled(false)"),
            owner.filter { it.contains("setRecentsScreenshotEnabled") },
        )
        assertEquals(
            listOf("ContextCompat.registerReceiver(app, receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)"),
            owner.filter { it.contains("registerReceiver(") && !it.contains("unregisterReceiver(") },
        )
        assertEquals(listOf("app.unregisterReceiver(receiver)"), owner.filter { it.contains("unregisterReceiver(") })

        val activity = code(File(module, "$main/MainActivity.kt"))
        assertEquals(
            "the window starts secure, and afterwards is whatever the policy says at that moment",
            listOf("container.screenSecurity.apply(this, secure = true)", "container.screenSecurity.apply(this, secure)"),
            activity.filter { it.contains("screenSecurity.apply(") },
        )
        val policy = File(module, "$main/MainActivity.kt").readText().substringAfter("private fun applyWindowPolicy() {").substringBefore("\n    }\n")
        assertEquals(
            "secure unless the app is unlocked and has an allowance, both asked at this moment; and what was opened under an open window is built again",
            listOf(
                "val appLock = container.appLock",
                "val unlocked = appLock.state.value is LockState.Unlocked",
                "val secure = WindowSecurity.mustBeSecure(unlocked, appLock.screenshots.current(), appLock.elapsedNow(), Build.VERSION.SDK_INT)",
                "val renew = WindowSecurity.mustRenewWindows(wasSecure = windowSecure, secure = secure, unlocked = unlocked)",
                "windowSecure = secure",
                "container.screenSecurity.apply(this, secure)",
                "if (!unlocked) renewWindowsWhenShown = false",
                "if (renew) renewWindows()",
            ),
            policy.lines().map { it.trim() }.filter { it.isNotEmpty() },
        )
        assertEquals(
            "the policy is applied when the lock's state changes, when the allowance changes, and before the activity is shown again",
            listOf("appLock.state.collect { applyWindowPolicy() }", "appLock.screenshots.allowed.collect { applyWindowPolicy() }", "applyWindowPolicy()"),
            activity.filter { it.contains("applyWindowPolicy()") && !it.contains("fun applyWindowPolicy") },
        )
        assertEquals(listOf("private var windowSecure = true"), activity.filter { it.contains("var windowSecure") })
        assertEquals("only the policy may have the activity recreated", listOf("MainActivity.kt"), users(all, "recreate()"))

        for (name in listOf("LockConfig.kt", "LockManager.kt")) {
            assertTrue("$name stores something about screenshots", !File(module, "$main/security/$name").readText().contains("creenshot"))
        }
    }

    @Test
    fun noLoggingAndNoMarkersInMainCode() {
        for (file in sources(main)) {
            val text = file.readText()
            assertTrue("${file.name} logs", imports(file).none { it == "android.util.Log" || it.startsWith("java.util.logging") } && !text.contains("println("))
            assertTrue("${file.name} has a marker", !text.contains("TODO") && !text.contains("FIXME"))
        }
    }

    /**
     * The instrumented tests run on a phone, against the real Keystore. They must never be able
     * to touch the keys or the data of the app they run inside: every vault they use comes from
     * DeviceTests.vault(), which puts the tests' own namespace in front of every alias and
     * refuses to run outside a debug build, and none of them goes near the app's own files, its
     * reset or its lock.
     */
    @Test
    fun instrumentedTestsOnlyEverUseTheirOwnKeysAndFiles() {
        val tests = sources("src/androidTest/java")
        assertTrue(tests.isNotEmpty())
        val support = tests.single { it.name == "DeviceTests.kt" }
        assertTrue(support.readText().contains("AndroidKeyVault(NAMESPACE)"))
        assertTrue(support.readText().contains("fun requireDevelopmentPackage()"))

        for (file in tests) {
            val text = file.readText()
            // The only vault that may be made is the namespaced one; the one other mention makes none
            // (it asserts that the app's own prefix is refused as a namespace).
            val made = Regex("""AndroidKeyVault\(([^)]*)\)""").findAll(text).map { it.groupValues[1] }.toList()
            val expected = when (file.name) {
                "DeviceTests.kt" -> listOf("NAMESPACE")
                "AndroidKeyVaultTest.kt" -> listOf("KeyId.ALIAS_PREFIX")
                else -> emptyList()
            }
            assertEquals("${file.name} makes a vault of its own", expected, made)
            for (forbidden in listOf("DataReset", "AppContainer", "ProsePlowApp", "MainActivity", "filesDir", "AppLock(")) {
                assertTrue("${file.name} reaches for $forbidden", !text.contains(forbidden))
            }
            if (file != support && text.contains("@RunWith")) {
                assertTrue("${file.name} must take its keys and files from DeviceTests", !text.contains("Keystore") || text.contains("DeviceTests."))
            }
        }
    }

    /**
     * A passphrase field must not hand the typed passphrase to accessibility services: the text
     * it publishes to them is replaced by bullets, under both names the text field uses for it.
     * Whether that works is checked on a phone (PassphraseFieldAccessibilityTest, which reads the
     * field the way an accessibility service does); that test is not part of `check`, so this one
     * fails the build as soon as the replacement is taken out or loses one of its two halves.
     */
    @Test
    fun thePassphraseFieldReplacesWhatItTellsAccessibilityServices() {
        val source = File(module, "$main/ui/SecureInput.kt").readText()
        val field = source.substringAfter("fun PassphraseField(").substringBefore("\n}\n")
        val override = Regex("""modifier = modifier\.semantics \{(.*?)\n\s*\},""", RegexOption.DOT_MATCHES_ALL).find(field)?.groupValues?.get(1)
        assertTrue("PassphraseField no longer overrides its accessibility text", override != null)
        val lines = override!!.lines().map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(
            listOf(
                "val obscured = obscuredForAccessibility(state.text.length)",
                "editableText = obscured",
                "inputText = obscured",
            ),
            lines,
        )
        // And every passphrase in the app is entered through that field: nothing else makes a secure text field.
        for (file in sources(main).filterNot { it.name == "SecureInput.kt" }) {
            assertTrue("${file.name} makes a secure text field of its own", !file.readText().contains("SecureTextField("))
        }
    }

    /**
     * A passphrase field shows a bullet for every character from the moment it is typed. The
     * text field's own default shows the last typed character for a moment, which a camera, a
     * screen recording or a glance can read one character at a time; so the fully hidden mode
     * is passed explicitly, and nothing in the app chooses another.
     */
    @Test
    fun thePassphraseFieldNeverShowsACharacterOfWhatIsTyped() {
        val input = File(module, "$main/ui/SecureInput.kt")
        val field = functions(input).getValue("PassphraseField")
        assertEquals(
            "PassphraseField must pass the hidden mode to its one text field",
            listOf("textObfuscationMode = TextObfuscationMode.Hidden,"),
            field.lines().filter { it.contains("textObfuscationMode") },
        )
        assertEquals(1, Regex("""OutlinedSecureTextField\(""").findAll(field).count())
        assertEquals("nothing else chooses how a passphrase is obscured", listOf("SecureInput.kt"), users(sources(main), "TextObfuscationMode"))
        assertEquals(
            listOf("import androidx.compose.foundation.text.input.TextObfuscationMode", "textObfuscationMode = TextObfuscationMode.Hidden,"),
            code(input).filter { it.contains("TextObfuscationMode") },
        )
        for (word in listOf("RevealLastTyped", "TextObfuscationMode.Visible")) {
            assertEquals("$word would show what is typed", emptyList<String>(), users(sources(main), word))
        }
    }

    /**
     * Wherever a passphrase is entered or changed the screen cannot be captured, whatever the
     * screenshot allowance says. Two rules cover every passphrase field, and this test holds
     * every field to one of them:
     *
     * 1. The set-up and lock screens are only composed while the app is not unlocked, and then
     *    the activity's window is always secure (the policy's truth table is tested in
     *    ScreenshotAllowanceTest; that the activity asks it with the lock's state is pinned in
     *    [theWindowPolicyIsCarriedOutInExactlyOnePlace]).
     * 2. In the unlocked app a passphrase is only typed in a PassphraseDialog, which is secure
     *    by its own setting and not by what its window inherits.
     */
    @Test
    fun aPassphraseIsOnlyEverTypedWhereTheScreenCannotBeCaptured() {
        val all = sources(main)
        assertEquals(
            "a passphrase field outside the lock screens and the passphrase dialogs",
            listOf("LockScreens.kt", "PassphraseDialogs.kt"),
            users(all.filterNot { it.name == "SecureInput.kt" }, "PassphraseField("),
        )

        // 1. The screens of the locked app.
        val lockScreens = File(module, "$main/lock/LockScreens.kt")
        val screens = functions(lockScreens)
        assertEquals(listOf("SetupScreen", "LockScreen"), screens.filterValues { it.contains("PassphraseField(") }.keys.toList())
        for (name in listOf("SetupScreen", "LockScreen")) {
            val body = screens.getValue(name)
            assertTrue("$name must stay private to the locked content", body.startsWith("private fun $name("))
            assertTrue("$name puts something into a window of its own", !opensAWindow.containsMatchIn(body))
            val calls = all.flatMap { file -> code(file).filter { Regex("""(?<![A-Za-z])$name\(""").containsMatchIn(it) && !it.contains("fun $name(") } }
            assertEquals("$name is composed in one place", 1, calls.size)
        }
        val lockedContent = screens.getValue("LockedContent")
        assertTrue(lockedContent.contains("LockState.SetupRequired -> SetupScreen(appLock)"))
        assertTrue(lockedContent.contains("is LockState.Locked -> LockScreen(appLock, state)"))
        assertTrue("the locked content shows nothing for the unlocked state", lockedContent.contains("is LockState.Unlocked<*> -> Unit"))
        // The locked content is composed in one place: the branch of the activity's root that is not the unlocked app.
        val composed = all.flatMap { file -> code(file).filter { it.contains("LockedContent(") && !it.contains("fun LockedContent(") }.map { file.name to it } }
        assertEquals(listOf("MainActivity.kt" to "else -> LockedContent(appLock, current)"), composed)
        val root = File(module, "$main/MainActivity.kt").readText().substringAfter("private fun AppRoot() {").substringBefore("\n    }\n")
        val branches = root.lines().map { it.trim() }.filter { it.startsWith("is LockState.") || it.startsWith("else ->") }
        assertEquals(listOf("is LockState.Unlocked -> {", "else -> LockedContent(appLock, current)"), branches)

        // 2. The dialogs of the unlocked app.
        val dialogs = functions(File(module, "$main/lock/PassphraseDialogs.kt"))
        val frame = dialogs.getValue("PassphraseDialog")
        assertEquals("the frame is one dialog", 1, opensAWindow.findAll(frame).count())
        assertTrue(frame.contains("AlertDialog(\nonDismissRequest = onDismiss,\nproperties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),"))
        assertTrue("and gets the window's other protections", frame.contains("ProtectWindow()"))
        val withAField = dialogs.filterValues { it.contains("PassphraseField(") }
        assertEquals(listOf("NewPassphraseDialog", "ConfirmPassphraseDialog"), withAField.keys.toList())
        for ((name, body) in withAField) {
            assertTrue("$name opens a window of its own", !opensAWindow.containsMatchIn(body))
            val framed = body.indexOf("PassphraseDialog(", startIndex = body.indexOf('{'))
            assertTrue("$name must put its fields into a PassphraseDialog", framed >= 0 && framed < body.indexOf("PassphraseField("))
        }
    }

    /**
     * Everything copied out of the app is marked as sensitive, so that Android does not show it
     * in the preview after a copy. The app's own copy buttons mark their clips; Copy and Cut in
     * a selection menu are made inside the text library, without the mark. Both reach the
     * system through the composition's clipboard, so that is replaced, once, around everything
     * the activity composes; and nothing in the app reaches the system's clipboard another way.
     *
     * Whether the mark arrives is checked on a phone (SensitiveClipboardTest, an instrumented
     * test); this one fails the build when the wrapper is taken out or walked around.
     */
    @Test
    fun everyClipLeavesTheAppThroughTheClipboardThatMarksItSensitive() {
        val all = sources(main)
        val activity = code(File(module, "$main/MainActivity.kt"))
        assertEquals("the activity composes in one place", listOf("composeView.setContent {"), activity.filter { it.contains("setContent {") || it.contains("setContent(") })
        assertEquals("and nothing else makes a composition of its own", listOf("MainActivity.kt"), users(all, "setContent"))
        val content = File(module, "$main/MainActivity.kt").readText().substringAfter("private fun setAppContent() {").substringBefore("\n    }\n")
        val nesting = content.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") && it != "}" }
        assertEquals(
            listOf("composeView.setContent {", "ProsePlowTheme {", "NoPersonalizedLearning {", "MarkCopiedTextSensitive {", "PlainSelectionMenu { AppRoot() }"),
            nesting,
        )
        assertEquals(listOf("PlainSelectionMenu { AppRoot() }", "private fun AppRoot() {"), activity.filter { it.contains("AppRoot()") })

        // The composition's clipboard is replaced and read in one file, and the system's own is written to nowhere.
        assertEquals(listOf("UiSupport.kt"), users(all, "LocalClipboard"))
        val support = code(File(module, "$main/ui/UiSupport.kt"))
        assertEquals(
            listOf("CompositionLocalProvider(LocalClipboard provides clipboard, LocalClipboardManager provides olderClipboard, content = content)"),
            support.filter { it.contains(" provides ") },
        )
        for (word in listOf("setPrimaryClip", "CLIPBOARD_SERVICE", "ClipboardManager::class")) {
            assertEquals("$word goes around the clipboard that marks its clips", emptyList<String>(), users(all, word))
        }
        assertEquals(
            "the system's clipboard manager is only handed through, for the text library to ask whether there is something to paste",
            listOf(
                "override val clipboardManager: android.content.ClipboardManager get() = platform.clipboardManager",
                "override val nativeClipboard: android.content.ClipboardManager get() = platform.nativeClipboard",
            ),
            support.filter { it.contains("clipboardManager") || it.contains("nativeClipboard") },
        )
        assertEquals(listOf("UiSupport.kt"), users(all, "nativeClipboard"))
        // Both wrappers mark what they are given before they pass it on.
        val text = support.joinToString("\n")
        assertEquals(2, Regex("""if \(clipEntry != null\) markSensitive\(clipEntry\.clipData\)\nplatform\.setClip(Entry)?\(clipEntry\)""").findAll(text).count())
        assertEquals(listOf("platform.setClipEntry(clipEntry)", "platform.setClip(clipEntry)"), support.filter { it.startsWith("platform.setClip") })
    }

    /**
     * No tap counts while another app's window covers the place that was tapped: the activity's
     * content sits in a frame that discards such touches, and every dialog and menu, which is a
     * window of its own, asks for the same.
     */
    @Test
    fun touchesMadeUnderAnotherAppsOverlayAreDiscarded() {
        val all = sources(main)
        val activity = code(File(module, "$main/MainActivity.kt"))
        assertEquals(
            listOf("val content = ObscuredTouchFilterLayout(this)", "content.onTouchDiscarded = ::tellThatATouchWasDiscarded", "content.addView(composeView)", "setContentView(content)"),
            activity.filter { it.startsWith("val content =") || it.startsWith("content.") || it.contains("setContentView(") },
        )
        val frame = code(File(module, "$main/ui/ObscuredTouches.kt")).joinToString("\n")
        assertTrue(frame.contains("init {\nfilterTouchesWhenObscured = true\n}"))
        val protect = functions(File(module, "$main/ui/SecureInput.kt")).getValue("ProtectWindow")
        assertTrue(protect.contains("view.rootView.filterTouchesWhenObscured = true") && protect.contains("view.filterTouchesWhenObscured = true"))
        assertEquals("the filter is only ever turned on", emptyList<String>(), users(all, "filterTouchesWhenObscured = false"))

        val unprotected = ArrayList<String>()
        for (file in all) {
            for ((name, body) in functions(file)) {
                val windows = opensAWindow.findAll(body).map { it.groupValues[1] }.toList()
                if (windows.isNotEmpty() && !body.contains("ProtectWindow()")) unprotected += "${file.name}: $name (${windows.joinToString()})"
            }
        }
        assertEquals("a dialog or menu that does not protect its window", emptyList<String>(), unprotected)
    }

    /**
     * Selecting text must not hand it to anything outside the app. The text library's smart
     * selection (which gives the selection to the system's text classifier) is switched off
     * before any screen exists, and the selection menu is held to the app's short list around
     * the whole composition and in every dialog that shows a text field or text that can be
     * selected (a dialog is laid out in a window of its own, where the list around the
     * composition does not reach).
     */
    @Test
    fun selectingTextHandsItToNothingOutsideTheApp() {
        val all = sources(main)
        val application = code(File(module, "$main/ProsePlowApp.kt")).joinToString("\n")
        assertTrue(application.contains("override fun onCreate() {\nsuper.onCreate()\nturnSmartSelectionOff()\n}"))
        assertTrue(application.contains("ComposeFoundationFlags.isSmartSelectionEnabled = false"))
        assertEquals("smart selection is only ever turned off", emptyList<String>(), users(all, "isSmartSelectionEnabled = true"))
        assertEquals("nothing adds entries to the selection menu", emptyList<String>(), users(all, "appendTextContextMenuComponents"))
        assertEquals("the menu is filtered in one place", listOf("SelectionMenu.kt"), users(all, "filterTextContextMenuComponents"))

        val unfiltered = ArrayList<String>()
        for (file in all) {
            for ((name, body) in functions(file)) {
                val window = Regex("""(?<![A-Za-z])(AlertDialog|BasicAlertDialog|Dialog|ModalBottomSheet)\(""").containsMatchIn(body)
                val text = body.contains("TextField(") || body.contains("SelectionContainer")
                // PassphraseDialog takes its fields as content; it filters for whatever it is given.
                val takesContent = name == "PassphraseDialog"
                if (window && (text || takesContent) && !body.contains("plainSelectionMenu()")) unfiltered += "${file.name}: $name"
            }
        }
        assertEquals("a dialog with selectable text and the full selection menu", emptyList<String>(), unfiltered)
    }

    /**
     * What protects the app's one activity in the manifest: it belongs to no task but its own
     * (an empty task affinity, so that on Android 9 and 10 another app cannot place an activity
     * in this app's task and show it in place of the lock screen), and it is the only exported
     * component the app itself declares.
     */
    @Test
    fun theActivityBelongsToNoTaskButItsOwn() {
        val manifest = File(module, "src/main/AndroidManifest.xml").readText().replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), "")
        val activities = Regex("""<activity\b[^>]*>""").findAll(manifest).map { it.value }.toList()
        assertEquals(1, activities.size)
        val activity = activities.single()
        assertTrue(activity.contains("android:name=\".MainActivity\""))
        assertTrue("MainActivity must keep its empty task affinity", activity.contains("android:taskAffinity=\"\""))
        assertEquals("the launcher activity is the only exported component", 1, Regex("""android:exported="true"""").findAll(manifest).count())
        for (attribute in listOf("allowTaskReparenting=\"true\"", "launchMode=\"singleTask\"", "launchMode=\"singleInstance\"")) {
            assertTrue("$attribute changes which task the activity can end up in", !manifest.contains(attribute))
        }
        assertTrue("backups stay off", manifest.contains("android:allowBackup=\"false\""))
    }

    /**
     * The merged manifest's permission check is a step of the build, not a task beside it: it is
     * wired in as a transform of the merged manifest, so everything that packages or installs
     * the app consumes what the check let through. And no build type carries the Compose
     * tooling library, which would add an exported activity to it.
     */
    @Test
    fun theBuildCannotPackageAManifestThatWasNotChecked() {
        val build = File(module, "build.gradle.kts").readLines().map { it.trim() }.filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/**") }.joinToString("\n")
        assertTrue(
            "the check must transform the merged manifest",
            build.contains(
                "variant.artifacts.use(verify)\n" +
                    ".wiredWithFiles(VerifyManifestPermissions::mergedManifest, VerifyManifestPermissions::checkedManifest)\n" +
                    ".toTransform(SingleArtifact.MERGED_MANIFEST)",
            ),
        )
        val action = build.substringAfter("fun verify() {").substringBefore("private companion object")
        val refused = action.indexOf("throw GradleException(")
        val handedOn = action.indexOf("manifest.copyTo(checkedManifest.get().asFile, overwrite = true)")
        assertTrue("the manifest is handed on only after it passed", refused in 0 until handedOn)
        assertTrue("and what an earlier run handed on is removed first", action.indexOf("checkedManifest.get().asFile.delete()") in 0 until refused)
        assertTrue(build.contains("tasks.named(\"check\") { dependsOn(verify) }"))
        assertTrue("no Compose tooling in any build type", !build.contains("ui.tooling") && !build.contains("ui-tooling"))
        assertTrue("and nothing is ever previewed", users(sources(main), "@Preview").isEmpty())
    }

    /**
     * A destroyed data key must never be picked up for a new wrap (a passphrase change, a
     * fingerprint enrolment). The lock therefore drops its reference to the session before it
     * hands the session on to be closed, where the key is destroyed; the data key itself refuses
     * to hand out its bytes afterwards (EnvelopeTest), and the lock manager refuses such a key
     * (LockHardeningTest).
     */
    @Test
    fun theLockDropsItsSessionBeforeTheSessionsKeyCanBeDestroyed() {
        val lock = File(module, "$main/security/AppLock.kt").readText()
        val leaving = lock.substringAfter("private fun leaveUnlocked(reason: SessionEnd) {").substringBefore("\n    }\n").lines().map { it.trim() }.filterNot { it.startsWith("//") }
        val dropped = leaving.indexOf("live = null")
        val retired = leaving.indexOfFirst { it.contains("endSession(") }
        assertTrue("leaveUnlocked must clear `live` and then retire the session, in that order: $leaving", dropped in 0 until retired)
        assertEquals("the key is destroyed in one place", 1, Regex("""ended\.crypto\.destroy\(\)""").findAll(lock).count())
        // Every use of the live session's key asks for it at the moment of use, under the mutex.
        val uses = lock.lines().map { it.trim() }.filter { it.contains("live?.key") }
        assertEquals(listOf("val key = live?.key", "val key = live?.key ?: return@op false"), uses)
    }

    @Test
    fun aDebugBuildIsAnotherAppSoThatTestsNeverRunInsideARealInstallation() {
        val build = File(module, "build.gradle.kts").readText()
        assertTrue("the debug build type must keep its own application id", Regex("""debug \{[^}]*applicationIdSuffix = "\.debug"""").containsMatchIn(build))
    }
}
