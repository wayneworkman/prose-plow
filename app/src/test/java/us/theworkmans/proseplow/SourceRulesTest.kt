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

    /** The one line that makes the engine: with the app's gate, the device's memory and the app's one policy. */
    private val engineIsMade =
        "LlamaEngine(NativeEngine.factory(app), nativeThread.asCoroutineDispatcher(), gate = generationGate, memoryGauge = deviceMemory, contextPolicy = contextPolicy)"

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

    /**
     * The terms' screens know the core (the rules of the flow), the lock (which records the
     * agreement) and the shared pieces of the interface, and nothing of the chats, the settings or
     * the lock's own screens. The app makes the lock with the version of the terms the core
     * defines, and with no other number.
     */
    @Test
    fun theTermsKnowOnlyTheCoreTheLockAndTheSharedInterface() {
        for (file in sources("$main/terms")) {
            val allowed = listOf("$app.core.", "$app.security.", "$app.ui.", "$app.terms.")
            val other = imports(file).filter { it.startsWith("$app.") && allowed.none(it::startsWith) && it != "$app.R" }
            assertEquals("${file.name} in terms imports a package it must not know", emptyList<String>(), other)
        }
        val container = code(File(module, "$main/ProsePlowApp.kt"))
        assertEquals(listOf("termsVersion = Terms.TERMS_VERSION,"), container.filter { it.contains("termsVersion") })
        assertEquals("one AppLock in the app", 1, container.count { it.contains("AppLock(") })
        val lock = code(File(module, "$main/security/AppLock.kt"))
        assertEquals("the version is only ever passed in", listOf("private val termsVersion: Int = 0,"), lock.filter { it.contains("val termsVersion") })
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
        assertEquals(
            listOf(
                "is LockState.Unlocked -> {",
                "is LockState.TermsRequired -> TermsGate(appLock, container.termsFlow, onLeave = ::leaveAfterDisagreeing)",
                "else -> LockedContent(appLock, current)",
            ),
            branches,
        )
        assertTrue("the locked content shows nothing for the terms", lockedContent.contains("is LockState.TermsRequired -> Unit"))

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
        assertEquals("and nothing else makes a composition of its own", listOf("MainActivity.kt"), users(all, "setContent {"))
        assertEquals(emptyList<String>(), users(all, ".setContent("))
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
     * The third-party notices reach the app in one way: as the raw resource that the build writes
     * after it has compared the list of libraries with what the variant packages. Like the
     * permission check, the comparison is a step of the build: the resource directory is the
     * task's own and one of the variant's, the text is written into it only after the list has
     * passed, and what an earlier run wrote is removed first. No source directory holds a file
     * of the resource's name, which would be merged with the task's or take its place. And the
     * app reads the resource in one function, which the ViewModel of the notices calls once, off
     * the main thread.
     */
    @Test
    fun theThirdPartyNoticesReachTheAppOnlyThroughTheTaskThatComparesThemWithWhatIsPackaged() {
        val build = File(module, "build.gradle.kts").readLines().map { it.trim() }.filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/**") }.joinToString("\n")
        assertTrue(
            "the task's directory must be one of the variant's resource directories",
            build.contains("variant.sources.res?.addGeneratedSourceDirectory(notices, GenerateThirdPartyNotices::output)"),
        )
        assertTrue("what is compared is the variant's runtime classpath", build.contains("variant.runtimeConfiguration.incoming.artifactView {"))
        assertTrue(build.contains("tasks.named(\"check\") { dependsOn(notices) }"))
        val action = build.substringAfter("fun generate() {").substringBefore("private companion object")
        val refused = action.indexOf("throw GradleException(")
        val written = action.indexOf("text.get().asFile.copyTo(raw.resolve(RESOURCE_FILE))")
        assertTrue("the text is written only after the list passed", refused in 0 until written)
        assertTrue("and what an earlier run wrote is removed first", action.indexOf("raw.deleteRecursively()") in 0 until refused)
        assertTrue(build.contains("const val RESOURCE_FILE = \"third_party_notices.txt\""))

        val strays = File(module, "src").walkTopDown().filter { it.isFile && it.nameWithoutExtension == "third_party_notices" }.map { it.relativeTo(module).path }.toList()
        assertEquals("a source directory supplies the resource that only the build may write", emptyList<String>(), strays)

        val all = sources(main)
        assertEquals(listOf("ThirdPartyNoticesScreen.kt"), users(all, "R.raw.third_party_notices"))
        assertEquals("the app has no other raw resource, and no other way to this one", listOf("ThirdPartyNoticesScreen.kt"), users(all, "openRawResource("))
        assertEquals(
            "the text is read where the ViewModel of its screen is made, and nowhere else",
            listOf("AppNavigation.kt", "ThirdPartyNoticesScreen.kt"),
            users(all, "readThirdPartyNotices("),
        )
        val viewModel = code(File(module, "$main/settings/ThirdPartyNoticesViewModel.kt"))
        assertEquals(
            "once, and off the main thread",
            listOf("withContext(ioDispatcher) { ThirdPartyNoticesState.Shown(ThirdPartyNoticesText.blocks(read())) }"),
            viewModel.filter { it.contains("read()") },
        )
    }

    /**
     * A notification is shown outside the app: on the phone's lock screen, and to whatever is
     * allowed to read notifications. So each of the app's two says one fixed sentence from the
     * resources (the ongoing one, one of three, by why it is shown), without a placeholder, and nothing that could carry a word of an answer, the
     * title of a chat or the name of a model is ever set on one. The code that builds them
     * cannot even reach a chat: it imports nothing of the chats, the stores or the models.
     */
    @Test
    fun theNotificationsSayOneFixedSentenceAndNothingAboutAnAnswer() {
        val all = sources(main)
        assertEquals("notifications are built in one file", listOf("AnswerNotifications.kt"), users(all, "NotificationCompat.Builder("))
        assertEquals(listOf("AnswerNotifications.kt"), users(all, ".notify("))
        val notifications = code(File(module, "$main/background/AnswerNotifications.kt"))
        assertEquals(
            listOf(".setContentTitle(title)", ".setContentTitle(context.getString(R.string.answer_ready))"),
            notifications.filter { it.contains("setContentTitle(") },
        )
        // The ongoing notification's title is one of three fixed sentences, one for each reason it is shown.
        assertEquals(
            listOf(
                "KeepAliveNotice.WRITING -> context.getString(R.string.answer_writing)",
                "KeepAliveNotice.LOCK_PENDING -> context.getString(R.string.keep_alive_lock_pending)",
                "KeepAliveNotice.LOCKING -> context.getString(R.string.keep_alive_locking)",
            ),
            notifications.filter { it.contains("KeepAliveNotice.") && it.contains("->") },
        )
        assertEquals(listOf("val title = when (notice) {"), notifications.filter { it.contains("val title") })
        assertEquals(
            "every text a notification can show",
            listOf(
                "R.string.channel_answer_writing", "R.string.channel_answer_ready", "R.string.answer_writing", "R.string.keep_alive_lock_pending",
                "R.string.keep_alive_locking", "R.string.answer_ready",
            ),
            notifications.flatMap { line -> Regex("""R\.string\.[a-z_]+""").findAll(line).map { it.value }.toList() },
        )
        for (word in listOf("setContentText(", "setSubText(", "setTicker(", "setStyle(", "setContentInfo(", "setLargeIcon(", "addAction(", "setExtras(", "addExtras(", "setRemoteInputHistory(")) {
            assertEquals("$word could carry something of an answer into a notification", emptyList<String>(), users(all, word))
        }
        assertTrue("a text with arguments is not a fixed text", notifications.none { Regex("""getString\([^)]*,""").containsMatchIn(it) })
        for (name in listOf("AnswerNotifications.kt", "AnswerService.kt", "AnswerWork.kt", "MaintenanceJob.kt", "MaintenanceSchedule.kt")) {
            val reach = imports(File(module, "$main/background/$name")).filter { import -> listOf("$app.chat.", "$app.core.", "$app.data.", "$app.settings.").any(import::startsWith) }
            assertEquals("$name can reach what an answer is made of", emptyList<String>(), reach)
        }

        val strings = File(module, "src/main/res/values/strings.xml").readText()
        fun text(name: String) = Regex("""<string name="$name">([^<]*)</string>""").find(strings)?.groupValues?.get(1)
        assertEquals("Writing an answer\u2026", text("answer_writing"))
        assertEquals("Unlocked; it locks itself when its time is up.", text("keep_alive_lock_pending"))
        assertEquals("Locking the app\u2026", text("keep_alive_locking"))
        assertEquals("Your answer is ready.", text("answer_ready"))
        assertEquals("Running in the background", text("channel_answer_writing"))
        assertEquals("Answer ready", text("channel_answer_ready"))
        for (name in listOf("answer_writing", "keep_alive_lock_pending", "keep_alive_locking", "answer_ready", "channel_answer_writing", "channel_answer_ready")) {
            assertTrue("$name takes an argument", !text(name)!!.contains("%"))
        }
    }

    /**
     * The answer service keeps the process alive and awake and does nothing else. No other app
     * can start it; Android is told what it is for; it is never restarted without its process;
     * it is started as an ordinary service and puts itself in the foreground first thing (a
     * service started with `startForegroundService` that fails to reach the foreground crashes
     * the app when it stops), and says whether that worked through the one plain-Kotlin entry;
     * it stops only itself; and the wake lock it holds always has a time limit.
     */
    @Test
    fun theAnswerServiceIsNotExportedSaysWhatItIsForAndHoldsNoWakeLockForEver() {
        val manifest = File(module, "src/main/AndroidManifest.xml").readText().replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), "")
        val services = Regex("""<service\b[^>]*>""").findAll(manifest).map { it.value }.toList()
        assertEquals("the answer service and the maintenance job", 2, services.size)
        val service = services.single { it.contains("android:name=\".background.AnswerService\"") }
        assertTrue("the service must stay closed to other apps", service.contains("android:exported=\"false\""))
        assertTrue(service.contains("android:foregroundServiceType=\"specialUse\""))
        val property = Regex("""<property\s+android:name="android\.app\.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"\s+android:value="([^"]+)"""").find(manifest)
        assertTrue("the special use must be stated", property != null && property.groupValues[1].length > 40)
        assertTrue("no receiver, and no provider of the app's own", !manifest.contains("<receiver"))

        val all = sources(main)
        val file = File(module, "$main/background/AnswerService.kt")
        val service_ = code(file)
        assertTrue(service_.contains("override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY"))
        assertTrue(service_.contains("override fun onBind(intent: Intent?): IBinder? = null"))
        assertEquals("never with the obligation to reach the foreground", emptyList<String>(), users(all, "startForegroundService("))
        assertEquals(listOf("AnswerService.kt"), users(all, "startService("))
        assertTrue(service_.contains("context.startService(Intent(context, AnswerService::class.java))"))
        assertEquals(listOf("AnswerService.kt"), users(all, "KeepAliveEntry.enter("))
        assertEquals(listOf("AnswerService.kt"), users(all, "stopService("))
        assertEquals("only the end of the process stops the service from outside", listOf("ProcessEnd.kt"), users(all, "AnswerService.stop("))
        // In the foreground first, through the entry that tells whether it worked, before anything can stop it.
        val created = file.readText().substringAfter("override fun onCreate() {").substringBefore("\n    }\n")
        // Success is what Android confirms after the call, not only a call that did not throw (the third review's finding 1).
        assertTrue(created.indexOf("KeepAliveEntry.enter(source, probe::serviceInForeground) {") in 0 until created.indexOf("ServiceCompat.startForeground("))
        assertTrue(created.indexOf("ServiceCompat.startForeground(") in 0 until created.indexOf("stopSelf()"))
        // And its end is told, so that nothing believes the keep-alive still runs: before it leaves
        // the foreground (finding 4), and in onDestroy when Android stopped it from outside.
        val leave = file.readText().substringAfter("private fun leave() {").substringBefore("\n    }\n").lines().map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(
            listOf(
                // The sentence is changed no more: nothing can post the notification again after it is removed.
                "noticeUpdates?.cancel()",
                "noticeUpdates = null",
                "told?.onKeepAlive(false)",
                "told = null",
                "ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)",
                "stopSelf()",
            ),
            leave,
        )
        assertEquals("the service leaves the foreground in one place", listOf("ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)"), service_.filter { it.contains("stopForeground(") })
        val destroyed = file.readText().substringAfter("override fun onDestroy() {").substringBefore("\n    }\n")
        assertTrue(destroyed.contains("told?.onKeepAlive(false)"))
        // Android is asked, at the moment of asking, through the system's own record of the service and the user's restriction.
        assertTrue(service_.contains("override fun backgroundRestricted(): Boolean = app.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted ?: true"))
        assertTrue(service_.any { it.contains("manager.getRunningServices(Int.MAX_VALUE).any { it.service.className == name && it.pid == pid && it.foreground }") })
        // The lock's question is answered by that, and the end of the keep-alive reaches the lock (finding 1's net).
        val application = code(File(module, "$main/ProsePlowApp.kt"))
        assertTrue(application.contains("canFinishOutOfSight = answerWork::canFinishOutOfSight,"))
        assertTrue(application.contains("answerWork.whenKeepAliveEnds(appLock::onKeepAliveEnded)"))
        // The keep-alive is asked for while the lock's timer waits out of sight, so that the timer runs on time.
        assertTrue(application.any { it.contains("appLock.lockPending.collect(answerWork::onLockPending)") })
        assertTrue(application.contains("val answerWork = AnswerWork(keepAlive = { AnswerService.start(app) }, readyNotice = AndroidReadyNotice(app), probe = AndroidKeepAliveProbe(app))"))

        assertEquals(listOf("AnswerService.kt"), users(all, "newWakeLock("))
        assertEquals(listOf("lock.acquire(WAKE_LOCK_MILLIS)"), service_.filter { it.contains(".acquire(") })
        assertTrue(service_.contains("PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG) ?: return") || service_.any { it.contains("newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)") })
        // The rules of when it runs are plain Kotlin, tested without a phone.
        assertEquals(emptyList<String>(), imports(File(module, "$main/background/AnswerWork.kt")).filter { it.startsWith("android.") || it.startsWith("androidx.") })
        assertEquals(emptyList<String>(), imports(File(module, "$main/background/MaintenanceSchedule.kt")).filter { it.startsWith("android.") || it.startsWith("androidx.") })
    }

    /**
     * The app asks for exactly five permissions: four for finishing an answer out of sight, and
     * RECEIVE_BOOT_COMPLETED, the owner's decision, which keeps the background maintenance job
     * across restarts. The build's allow-list for the merged manifest is those five and what the
     * fingerprint library adds. The network is in neither, and the allow-list is what the build is
     * checked against (see [theBuildCannotPackageAManifestThatWasNotChecked]). The one
     * `android:permission` attribute is the job service's requirement that only the system binds
     * it, which is not a permission the app holds.
     */
    @Test
    fun theAppAsksForFivePermissionsAndNoneOfThemIsTheNetwork() {
        val own = setOf(
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.WAKE_LOCK",
            "android.permission.RECEIVE_BOOT_COMPLETED",
        )
        val manifest = File(module, "src/main/AndroidManifest.xml").readText().replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), "")
        val requested = Regex("""<uses-permission[^>]*android:name="([^"]+)"""").findAll(manifest).map { it.groupValues[1] }.toList()
        assertEquals(own, requested.toSet())
        assertEquals("each once", 5, requested.size)
        val required = Regex("""android:permission="([^"]+)"""").findAll(manifest).map { it.groupValues[1] }.toList()
        assertEquals("only the job scheduler may bind the job", listOf("android.permission.BIND_JOB_SERVICE"), required)
        val job = Regex("""<service\b[^>]*MaintenanceJobService[^>]*>""").find(manifest)!!.value
        assertTrue(job.contains("android:exported=\"false\"") && job.contains("android:permission=\"android.permission.BIND_JOB_SERVICE\""))
        assertTrue(!manifest.contains("INTERNET") && !manifest.contains("ACCESS_NETWORK_STATE") && !manifest.contains("<permission"))
        for (other in listOf("uses-permission-sdk-23", "maxSdkVersion")) assertTrue(other, !manifest.contains(other))

        val build = File(module, "build.gradle.kts").readLines().map { it.trim() }.filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/**") }.joinToString("\n")
        val listed = build.substringAfter("val allowedPermissions = setOf(").substringBefore(")")
        val allowed = Regex(""""([^"]+)"""").findAll(listed).map { it.groupValues[1] }.toSet()
        assertEquals(own + setOf("android.permission.USE_BIOMETRIC", "android.permission.USE_FINGERPRINT"), allowed)
        assertTrue(build.contains("allowed.set(variant.applicationId.map { id -> allowedPermissions + \"\$id.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION\" })"))
        assertTrue("the network is not allowed anywhere in the build", !build.contains("INTERNET") && !build.contains("NETWORK"))
    }

    /**
     * The process is ended in one place, by one call, and that place first makes sure it is not
     * a process that runs instrumented tests. The lock asks for it from one function, which is
     * where every condition is checked (AppLockTest holds it to them).
     */
    @Test
    fun theProcessIsEndedInOnePlaceAndNeverInATestRun() {
        val all = sources(main)
        assertEquals(listOf("ProcessEnd.kt"), users(all, "killProcess("))
        for (word in listOf("exitProcess(", "System.exit(", ".halt(", "finishAffinity(")) {
            assertEquals("$word is another way to end the app", emptyList<String>(), users(all, word))
        }
        // One way of leaving that ends no process: "I do not agree" in the terms finishes the task
        // and takes it out of the recent apps, and the lock ends the process as it always does.
        assertEquals(listOf("MainActivity.kt"), users(all, "finishAndRemoveTask("))
        val leave = File(module, "$main/MainActivity.kt").readText().substringAfter("private fun leaveAfterDisagreeing() {").substringBefore("\n    }\n")
        assertEquals(listOf("finishAndRemoveTask()"), leave.lines().map { it.trim() }.filter { it.isNotEmpty() })
        assertEquals(
            "it is called from the terms and nowhere else",
            listOf("is LockState.TermsRequired -> TermsGate(appLock, container.termsFlow, onLeave = ::leaveAfterDisagreeing)"),
            code(File(module, "$main/MainActivity.kt")).filter { it.contains("leaveAfterDisagreeing") && !it.contains("fun leaveAfterDisagreeing") },
        )
        val end = File(module, "$main/ProcessEnd.kt").readText().substringAfter("override fun end() {").substringBefore("\n    }\n").lines().map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(
            listOf("if (InstrumentedRun.detected(app.classLoader)) return", "AnswerService.stop(app)", "Process.killProcess(Process.myPid())"),
            end,
        )
        assertEquals("the real ender is made once, for the lock", listOf("ProsePlowApp.kt"), users(all, "= AndroidProcessEnder("))
        val lock = code(File(module, "$main/security/AppLock.kt"))
        assertEquals(listOf("if (settled) processEnder.end()"), lock.filter { it.contains("processEnder.") })
        // The application object does not make the lock (and with it the ender) by itself: only an activity asks for the container.
        val application = code(File(module, "$main/ProsePlowApp.kt")).joinToString("\n")
        assertTrue(application.contains("private val lazyContainer = lazy { AppContainer(this, memoryPressure) }"))
        assertTrue(application.contains("get() = if (lazyContainer.isInitialized()) container.answerWork.keepAliveWanted else null"))
        assertEquals("the service must not create the container", emptyList<String>(), users(sources("$main/background"), "container"))
    }

    /**
     * Every call of the lock manager that derives a key from a passphrase is made while the
     * model stands aside, the pause ends in a `finally`, and what stands aside is the very gate
     * the engine's generations wait at.
     */
    @Test
    fun theModelStandsAsideForEveryKeyDerivationAndNeverLongerThanIt() {
        val lock = code(File(module, "$main/security/AppLock.kt"))
        val derivations = lock.filter { line -> listOf("m.checkPassphrase(", "m.changePassphrase(", "m.setUp(").any(line::contains) }
        assertEquals(
            listOf(
                "val result = whileOthersStandAside { m.setUp(first, terms) }",
                "when (val result = whileOthersStandAside { m.checkPassphrase(passphrase) }) {",
                "when (val result = whileOthersStandAside { m.checkPassphrase(passphrase) }) {",
                "whileOthersStandAside { m.changePassphrase(proof, key, newPassphrase) }",
            ),
            derivations,
        )
        val helper = File(module, "$main/security/AppLock.kt").readText().substringAfter("private inline fun <T> whileOthersStandAside(derivation: () -> T): T {").substringBefore("\n    }\n")
        assertEquals(
            listOf("heavyWork.standAside()", "try {", "return derivation()", "} finally {", "heavyWork.carryOn()", "}"),
            helper.lines().map { it.trim() }.filter { it.isNotEmpty() },
        )
        assertEquals(listOf("heavyWork.standAside()", "heavyWork.carryOn()"), lock.filter { it.contains("heavyWork.") })

        val application = code(File(module, "$main/ProsePlowApp.kt"))
        assertTrue(application.contains(engineIsMade))
        assertTrue(application.contains("override fun standAside() = generationGate.close()"))
        assertTrue(application.contains("override fun carryOn() = generationGate.open()"))
        assertEquals("one gate", listOf("private val generationGate = GenerationGate()"), application.filter { it.contains("GenerationGate(") })
        assertEquals("nothing else opens or closes it", listOf("ProsePlowApp.kt"), users(sources(main), "generationGate"))
    }

    /**
     * The longest reply of a model has one definition of its limits, in the core, which the
     * engine itself goes by; the app has no number of its own for them. And it reaches the
     * engine through one place, the place every kind of request goes through, read from the
     * settings at that moment.
     */
    @Test
    fun theLongestReplyHasOneDefinitionAndOneWayToTheEngine() {
        val core = File(module.parentFile, "core/src/main/kotlin/us/theworkmans/proseplow/core")
        val engine = code(File(core, "engine/LlamaEngine.kt"))
        assertTrue(engine.contains("reserve = minOf(requestedNewTokens, ReplyLength.maxTokens(contextSize)),"))
        assertTrue(engine.contains("val minReplyTokens: Int = ReplyLength.MIN_TOKENS,"))
        val limits = code(File(core, "model/ReplyLength.kt"))
        assertTrue(limits.contains("const val MIN_TOKENS: Int = 16"))
        assertTrue(limits.contains("const val MAX_TOKENS: Int = 4096"))
        assertTrue(limits.contains("fun maxTokens(contextTokens: Int): Int = minOf(MAX_TOKENS, contextTokens / 2)"))
        assertTrue(limits.contains("fun range(contextLimit: Int): IntRange = MIN_TOKENS..maxTokens(contextLimit)"))

        val all = sources(main)
        for (word in listOf("contextLength / 2", "contextSize / 2", "contextLength/2", "defaultMaxNewTokens * 2")) {
            assertEquals("$word is a limit of the app's own", emptyList<String>(), users(all, word))
        }
        assertEquals("the least is only ever named, not written out", listOf("SettingsStore.kt"), users(all, "ReplyLength.MIN_TOKENS"))

        val controller = code(File(module, "$main/chat/ChatController.kt"))
        assertEquals(
            "every reply, and every read-ahead for the next, is asked for in one place, with the model's settings as they are then and its chat's conversation key",
            listOf(
                "return session.generationRequest(spec, settings.systemPrompt, settings.sampling, settings.maxNewTokens, settings.maxContextTokens)" +
                    ".copy(conversationKey = conversationKeyOf(session.id))",
            ),
            controller.filter { it.contains("generationRequest(") },
        )
        assertTrue(controller.contains("val settings = requestSettings(spec)"))
        assertEquals(
            "a reply and a read-ahead build their request there",
            listOf("val request = requestOf(sessions[reply.sessionId] ?: return, spec)", "val request = requestOf(session, spec)"),
            controller.filter { it.contains("requestOf(") && !it.contains("fun requestOf(") },
        )
        assertEquals(listOf("ChatController.kt"), users(all, ".generationRequest("))
        val session = code(File(module, "$main/AppSession.kt")).joinToString("\n")
        assertTrue(
            "the settings of a reply are read in one place: the longest reply held to the limit of the context under the size chosen for it",
            session.contains(
                listOf(
                    "val chosen = contextLimits.chosen(model, current.contextTokens(model.id))",
                    "ModelRequestSettings(",
                    "current.systemPrompt(model.id).ifEmpty { null },",
                    "current.sampling(model),",
                    "current.maxNewTokens(model, contextLimits.limit(model, chosen)),",
                    "chosen,",
                    ")",
                ).joinToString("\n"),
            ),
        )
        assertEquals(listOf("AppSession.kt"), users(all, "ModelRequestSettings(").filter { it != "ChatController.kt" })
    }

    /**
     * How large a model's context may become is decided by one policy, in the core, which the
     * engine and the settings share; the app has no number of its own for it. The device's
     * memory is read in one place, afresh every time the engine asks, and what the system says
     * in its callbacks only adds to that. And the penalties are told to look at the whole
     * memory by name, not by a size that changes.
     */
    @Test
    fun theContextHasOnePolicyAndTheDevicesMemoryIsReadInOnePlace() {
        val all = sources(main)
        val application = code(File(module, "$main/ProsePlowApp.kt"))
        assertEquals("one policy", listOf("ProsePlowApp.kt"), users(all, "ContextPolicy("))
        assertEquals(listOf("private val contextPolicy = ContextPolicy()"), application.filter { it.contains("ContextPolicy(") })
        assertTrue(application.contains(engineIsMade))
        assertTrue(application.contains("private val contextLimits = DeviceContextLimits(contextPolicy, deviceMemory.totalBytes())"))
        assertEquals("made once, from the one policy and the memory the phone has", listOf("ProsePlowApp.kt"), users(all, "DeviceContextLimits(contextPolicy"))
        assertTrue("the session has the phone's limits", application.contains("contextLimits = contextLimits,"))
        val navigation = code(File(module, "$main/AppNavigation.kt"))
        assertEquals(
            "and so has every page of the settings",
            2,
            navigation.count { it.contains("SettingsViewModel(session.settings, session.models, contextLimits = session.contextLimits)") },
        )
        assertEquals(2, navigation.count { it.contains("SettingsViewModel(") })
        for (word in listOf("contextBytesPerToken", "readingTimes", "maxShareOfTotal", "floorShareOfTotal", "rereadSeconds =")) {
            assertEquals("$word is the policy's to use", emptyList<String>(), users(all, word))
        }

        assertEquals("the memory is read in one place", listOf("DeviceMemoryGauge.kt"), users(all, "getMemoryInfo("))
        assertEquals(listOf("DeviceMemoryGauge.kt"), users(all, "ActivityManager.MemoryInfo"))
        val gauge = code(File(module, "$main/DeviceMemoryGauge.kt"))
        assertTrue("low is what the system says now or said shortly before", gauge.any { it.contains("low = info.lowMemory || pressure.isSet") })
        assertTrue(application.contains("memoryPressure.onTrimLevel(level)"))
        assertTrue(application.contains("memoryPressure.onLowMemory()"))

        val core = File(module.parentFile, "core/src/main/kotlin/us/theworkmans/proseplow/core")
        val engine = code(File(core, "engine/LlamaEngine.kt"))
        assertEquals(listOf("penaltyWindow = SamplerConfig.NO_PENALTY_WINDOW,"), engine.filter { it.contains("penaltyWindow") })
        assertTrue("the memory is read at the moment of each decision", engine.contains("val device = memoryGauge.read()"))
        assertTrue("the policy budgets what the tokens of the context take", engine.contains("val allowed = contextPolicy.budgetBytes(device, minOf(tokenBytes, cost))"))
        assertTrue(
            "the native side counts what a session reserves at any size as well, so that is added to what it is given",
            engine.contains("val budget = if (allowed > Long.MAX_VALUE - reserved) Long.MAX_VALUE else allowed + reserved"),
        )
        assertTrue(engine.contains("native.resizeContext(affordable, budget)"))
        assertTrue("an opening is held to the budget by the engine itself", engine.contains("while (size.toLong() * model.contextBytesPerToken > budget) {"))
        assertTrue(engine.contains("return factory.open(model, progress, size, Long.MAX_VALUE).also {"))
    }

    /**
     * Settings is a short page of rows, and each model has a page of its own. The headings are
     * headings to a screen reader, a model's row is one button that says what it opens, and the
     * note about the length limit in the settings is the very string the chat shows.
     */
    @Test
    fun theSettingsPagesSayWhatTheyAreToAScreenReader() {
        val heading = code(File(module, "$main/ui/Notices.kt")).joinToString("\n").substringAfter("fun SectionTitle(").substringBefore("\n}")
        assertTrue("a section title must be a heading", heading.contains("semantics { heading() }"))
        val settings = code(File(module, "$main/settings/SettingsScreen.kt"))
        val page = code(File(module, "$main/settings/ModelSettingsScreen.kt"))
        assertTrue(settings.contains("title = { Text(stringResource(R.string.settings), modifier = Modifier.semantics { heading() }) },"))
        assertTrue(page.contains("title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() }) },"))
        assertTrue(settings.contains(".clickable(onClickLabel = stringResource(R.string.model_settings_open), role = Role.Button, onClick = onOpen)"))
        assertTrue("the fields of a model are on its page, not in Settings", settings.none { it.contains("TextField(") })
        assertEquals(3, page.count { it.contains("OutlinedTextField(") })

        val support = page.joinToString("\n").substringAfter("R.string.reply_length_support,").substringBefore(")\n)")
        assertEquals(
            listOf("stringResource(R.string.note_length),", "stringResource(R.string.continue_reply),", "default,", "most,"),
            support.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(4),
        )
        val strings = File(module, "src/main/res/values/strings.xml").readText()
        val text = Regex("""<string name="reply_length_support">([^<]*)</string>""").find(strings)!!.groupValues[1]
        for (placeholder in listOf("%1\$s", "%2\$s", "%3\$s", "%4\$s")) assertTrue(placeholder, text.contains(placeholder))
        assertTrue("the limit note is not written out a second time", !text.contains("Reached the length limit"))
    }

    /**
     * The app hands an address to another app in two places only: a link in an answer, after its
     * confirmation, and a model's card, whose address comes from the one place in the core that
     * builds it and is shown on the row that opens it. Both go through the same opener, which is
     * the only code that starts another app. There is no browser inside the app: no web view,
     * no browser library, and nothing in the manifest that asks which apps are installed.
     */
    @Test
    fun aModelsCardOpensInThePhonesBrowserAndNothingIsLoadedInTheApp() {
        val all = sources(main)
        assertEquals(listOf("ChatScreen.kt", "ModelCardLink.kt"), users(all, "Links.open("))
        assertEquals("only the opener starts another app", listOf("Links.kt"), users(all, "ACTION_VIEW"))
        val row = code(File(module, "$main/settings/ModelCardLink.kt"))
        assertTrue(row.contains("val address = ModelCard.address(model) ?: return"))
        assertTrue(row.contains("val url = ModelCard.url(model) ?: return"))
        assertTrue("what is opened is what the core built, and a failure is said", row.contains("if (!Links.open(context, url)) AppToasts.show(context, noApp)"))
        assertTrue("the address is shown on the row", row.contains("stringResource(R.string.model_card_address, address),"))
        assertEquals("the address is built in the core and nowhere in the app", emptyList<String>(), users(all, "huggingface.co"))
        assertEquals(listOf("AboutScreen.kt", "ModelCardLink.kt", "ModelSettingsScreen.kt"), users(all, "ModelCardLink("))

        for (word in listOf("WebView", "CustomTabs", "androidx.browser", "android.webkit")) {
            assertEquals("$word would be a browser inside the app", emptyList<String>(), users(all, word))
        }
        val build = File(module, "build.gradle.kts").readText()
        assertTrue(!build.contains("browser") && !build.contains("webkit"))
        val manifest = File(module, "src/main/AndroidManifest.xml").readText().replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), "")
        assertTrue("opening an address needs no list of other apps", !manifest.contains("<queries"))

        val strings = File(module, "src/main/res/values/strings.xml").readText()
        assertTrue(strings.contains("<string name=\"model_card_address\">%1\$s \u00B7 opens in your browser</string>"))
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
        val leaving = lock.substringAfter("private fun leaveUnlocked(").substringAfter(") {").substringBefore("\n    }\n").lines().map { it.trim() }.filterNot { it.startsWith("//") }
        val dropped = leaving.indexOf("live = null")
        val retired = leaving.indexOfFirst { it.contains("endSession(") }
        assertTrue("leaveUnlocked must clear `live` and then retire the session, in that order: $leaving", dropped in 0 until retired)
        assertEquals("the key is destroyed in one place", 1, Regex("""ended\.crypto\.destroy\(\)""").findAll(lock).count())
        // Every use of the live session's key asks for it at the moment of use, under the mutex.
        val uses = lock.lines().map { it.trim() }.filter { it.contains("live?.key") }
        assertEquals(listOf("val key = live?.key", "val key = live?.key ?: return@op false"), uses)
    }

    /**
     * A sealed stream is the form for content too large to be held in memory, and three things
     * about it are true only as long as the code keeps its shape:
     *
     * 1. Every chunk is sealed and opened by the data key's own class, inside the exclusion its
     *    destruction waits for and after the question whether the key is still there; and the
     *    chunks of a stream are only ever counted by the two classes that hold a stream to its
     *    form. So no stream goes on after a lock, and none is written out of order.
     * 2. The chunk size is the format's own: nothing in the app names another, so every stream
     *    has the chunks of its version.
     * 3. A chunk never passes through an array of the collected heap in the app's own code, and
     *    a stream is never flushed to the disk when it is written (which would take the seconds
     *    the app does not have when it leaves the screen), while its deletion is. That holds for
     *    the store of the model's memory too, which hands the chunks between the native export
     *    and the file; it takes its chunk size from the file and names none of its own (2.).
     */
    @Test
    fun aStreamIsSealedUnderTheKeysExclusionInChunksOfItsVersionAndBetweenDirectBuffers() {
        val all = sources(main)
        val format = code(File(module, "$main/security/SealedStream.kt"))
        val file = code(File(module, "$main/data/SealedStreamFile.kt"))
        val store = code(File(module, "$main/data/MemoryStore.kt"))

        // 1.
        assertEquals(listOf("Envelope.kt"), users(all, "SealedStream.sealChunk("))
        assertEquals(listOf("Envelope.kt"), users(all, "SealedStream.openChunk("))
        val crypto = code(File(module, "$main/security/Envelope.kt")).joinToString("\n")
        for (operation in listOf(
            "override fun newStream(oldestMessageMillis: Long): StreamHeader = lock.read {\n" +
                "if (destroyed) throw KeyDestroyedException()\n" +
                "SealedStream.newHeader(oldestMessageMillis, random)\n}",
            "override fun sealChunk(header: StreamHeader, purpose: String, index: Long, last: Boolean, plaintext: ByteBuffer, sealed: ByteBuffer) = lock.read {\n" +
                "if (destroyed) throw KeyDestroyedException()\n" +
                "SealedStream.sealChunk(key.bytes, header, purpose, index, last, plaintext, sealed, random)\n}",
            "override fun openChunk(header: StreamHeader, purpose: String, index: Long, last: Boolean, sealed: ByteBuffer, plaintext: ByteBuffer): Int = lock.read {\n" +
                "if (destroyed) throw KeyDestroyedException()\n" +
                "SealedStream.openChunk(key.bytes, header, purpose, index, last, sealed, plaintext)\n}",
        )) {
            assertTrue("the data key's class no longer has: $operation", crypto.contains(operation))
        }
        assertEquals("the key is destroyed under the exclusion the chunks are sealed in", 1, Regex("fun destroy\\(\\) = lock\\.write \\{").findAll(crypto).count())
        assertEquals("only the sealer and the opener of a stream ask for a chunk", listOf("SealedStream.kt"), users(all, "cipher.sealChunk("))
        assertEquals(listOf("SealedStream.kt"), users(all, "cipher.openChunk("))
        assertEquals(listOf("Envelope.kt", "SealedStream.kt"), users(all, ".sealChunk("))
        assertEquals(listOf("Envelope.kt", "SealedStream.kt"), users(all, ".openChunk("))

        // 2.
        assertEquals(listOf("const val CHUNK_SIZE = 4 * 1024 * 1024"), format.filter { it.contains("const val CHUNK_SIZE") })
        assertEquals("a chunk size is named by the format and its file only", listOf("SealedStream.kt", "SealedStreamFile.kt"), users(all, "chunkSize"))
        assertEquals(listOf("SealedStream.kt", "SealedStreamFile.kt"), users(all, "SealedStream.CHUNK_SIZE"))
        assertEquals(
            "and every way in from outside the module takes the format's",
            listOf(
                "constructor(cipher: ChunkCipher, purpose: String, oldestMessageMillis: Long) : this(cipher, purpose, oldestMessageMillis, SealedStream.CHUNK_SIZE)",
                "constructor(cipher: ChunkCipher, purpose: String, header: StreamHeader) : this(cipher, purpose, header, SealedStream.CHUNK_SIZE)",
                "constructor(file: File, purpose: String, cipher: ChunkCipher) : this(file, purpose, cipher, SealedStream.CHUNK_SIZE)",
            ),
            (format + file).filter { it.startsWith("constructor(") },
        )
        assertEquals(
            "whatever takes a chunk size is not reachable from outside the module",
            listOf(
                "class StreamHeader internal constructor(encoded: ByteArray) {",
                "class StreamSealer internal constructor(",
                "class StreamOpener internal constructor(",
                "class SealedStreamFile internal constructor(",
                "class SealedStreamWriter internal constructor(",
                "class SealedStreamReader private constructor(",
            ),
            (format + file).filter { it.startsWith("class Stream") || it.startsWith("class SealedStream") },
        )

        // 3.
        for ((name, lines) in listOf("SealedStream.kt" to format, "SealedStreamFile.kt" to file, "MemoryStore.kt" to store)) {
            for (word in listOf("ByteBuffer.wrap(", "ByteBuffer.allocate(", ".array()", "hasArray()")) {
                assertTrue("$name: $word puts a chunk into an array of the heap, or takes it from one", lines.none { it.contains(word) })
            }
        }
        assertEquals(
            "the buffers of a stream are made in three places, each direct: the header on its way out, one sealed chunk, one chunk of plaintext",
            listOf(
                "val bytes = ByteBuffer.allocateDirect(SealedStream.HEADER_SIZE)",
                "val sealed = buffer ?: ByteBuffer.allocateDirect(sealedChunkSize).also { buffer = it }",
                "private val plain: ByteBuffer = ByteBuffer.allocateDirect(chunkSize + SealedStream.TAG_SIZE)",
                "val sealed = ByteBuffer.allocateDirect(chunkSize + SealedStream.CHUNK_OVERHEAD + 1)",
            ),
            (format + file).filter { it.contains("allocate") },
        )
        for (word in listOf(".force(", ".sync()", "syncExisting(", "writeAtomically(", "syncDirectory(")) {
            assertTrue("$word would flush a stream that is being written", store.none { it.contains(word) })
        }
        for (word in listOf(".force(", ".sync()", "syncExisting(", "writeAtomically(")) {
            assertTrue("$word would flush a stream that is being written", file.none { it.contains(word) })
        }
        assertEquals(
            "only a deletion is flushed",
            listOf("import us.theworkmans.proseplow.security.syncDirectory", "file.absoluteFile.parentFile?.let(::syncDirectory)"),
            file.filter { it.contains("syncDirectory") },
        )
        val delete = File(module, "$main/data/SealedStreamFile.kt").readText().substringAfter("    fun delete() {").substringBefore("\n    }\n").lines().map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(
            listOf("writer?.cancel()", "for (temporary in temporaryFilesOf(file)) deleteOrThrow(temporary)", "deleteOrThrow(file)", "file.absoluteFile.parentFile?.let(::syncDirectory)"),
            delete,
        )
    }

    @Test
    fun aDebugBuildIsAnotherAppSoThatTestsNeverRunInsideARealInstallation() {
        val build = File(module, "build.gradle.kts").readText()
        assertTrue("the debug build type must keep its own application id", Regex("""debug \{[^}]*applicationIdSuffix = "\.debug"""").containsMatchIn(build))
    }
}
