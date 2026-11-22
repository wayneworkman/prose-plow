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

    @Test
    fun aDebugBuildIsAnotherAppSoThatTestsNeverRunInsideARealInstallation() {
        val build = File(module, "build.gradle.kts").readText()
        assertTrue("the debug build type must keep its own application id", Regex("""debug \{[^}]*applicationIdSuffix = "\.debug"""").containsMatchIn(build))
    }
}
