package us.theworkmans.proseplow

import androidx.test.platform.app.InstrumentationRegistry
import us.theworkmans.proseplow.security.AndroidKeyVault
import us.theworkmans.proseplow.security.KeyId
import java.io.File
import java.security.KeyStore

/**
 * What every instrumented test goes through, so that a test run can never touch a real
 * installation, its keys or its data:
 *
 * - The tests only run inside a debug build, which is another app to the phone (its application
 *   id ends in `.debug`): another user id, another data directory, another set of Keystore keys.
 *   A real installation is out of reach of this process whatever a test does, and it is the
 *   debug app, not the real one, that the test runner installs and removes.
 * - Inside that debug app the tests still keep to themselves: their Keystore aliases carry
 *   [NAMESPACE] in front, so they never see, replace or delete an alias with the app's own
 *   prefix (a developer's set-up debug app survives the tests' key work), and their files live
 *   in a directory of their own under the cache directory.
 *
 * [vault] is the only place a vault is made for a test, and [ownAliases] is compared before and
 * after each test class.
 */
object DeviceTests {
    const val NAMESPACE = "devicetest."

    private const val KEYSTORE = "AndroidKeyStore"

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Fails unless this is a debug build's own package: never run against a real installation. */
    fun requireDevelopmentPackage() {
        val name = context.packageName
        check(BuildConfig.DEBUG && name == BuildConfig.APPLICATION_ID && name.endsWith(".debug")) {
            "Instrumented tests only run inside the debug app, not in $name"
        }
    }

    /** A vault over real Keystore keys that are the tests' own. */
    fun vault(): AndroidKeyVault {
        requireDevelopmentPackage()
        return AndroidKeyVault(NAMESPACE)
    }

    /** A new, empty directory for a test's files, outside everything the app itself stores. */
    fun scratchDirectory(name: String): File {
        requireDevelopmentPackage()
        val directory = File(context.cacheDir, "devicetest/$name-${System.nanoTime()}")
        check(directory.mkdirs()) { "Could not create ${directory.name}" }
        return directory
    }

    fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    /** The aliases the app itself would use: the tests must leave them exactly as they were. */
    fun ownAliases(): Set<String> = keyStore().aliases().toList().filter { it.startsWith(KeyId.ALIAS_PREFIX) }.toSet()
}
