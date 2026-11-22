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
     * accident), no dialog opts out of inheriting it, the preview is only ever turned off, the
     * receiver is not exported and is unregistered again, and the activity only asks the policy
     * through the call that first ends an allowance whose time is up. And nothing about
     * screenshots is stored: the lock configuration does not know the word.
     */
    @Test
    fun theWindowPolicyIsCarriedOutInExactlyOnePlace() {
        fun code(file: File) = file.readLines().map { it.trim() }.filterNot { it.startsWith("*") || it.startsWith("//") || it.startsWith("/**") }
        val all = sources(main)
        for (word in listOf("FLAG_SECURE", "setRecentsScreenshotEnabled", "ACTION_SCREEN_OFF")) {
            val users = all.filter { file -> code(file).any { it.contains(word) } }.map { it.name }
            assertEquals("$word is handled outside the class that owns the window policy", listOf("ScreenSecurity.kt"), users)
        }
        assertEquals("something overrides how its window inherits the secure flag", emptyList<String>(), all.filter { file -> code(file).any { it.contains("SecureFlagPolicy") } }.map { it.name })

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
        assertEquals(
            listOf("val secure = WindowSecurity.mustBeSecure(appLock.screenshots.current(), appLock.elapsedNow(), Build.VERSION.SDK_INT)"),
            activity.filter { it.contains("mustBeSecure(") },
        )

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

    @Test
    fun aDebugBuildIsAnotherAppSoThatTestsNeverRunInsideARealInstallation() {
        val build = File(module, "build.gradle.kts").readText()
        assertTrue("the debug build type must keep its own application id", Regex("""debug \{[^}]*applicationIdSuffix = "\.debug"""").containsMatchIn(build))
    }
}
