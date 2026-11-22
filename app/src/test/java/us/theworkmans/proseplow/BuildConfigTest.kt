package us.theworkmans.proseplow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The versioning rule stated in app/build.gradle.kts, checked against what was actually built. */
class BuildConfigTest {

    @Test
    fun versionCodeFollowsFromVersionName() {
        val name = BuildConfig.VERSION_NAME
        val parts = name.split(".").map(String::toInt)
        assertEquals("version name is not major.minor.patch: $name", 3, parts.size)
        val (major, minor, patch) = parts
        assertTrue("minor and patch must be 0..99: $name", minor in 0..99 && patch in 0..99)
        assertEquals(major * 10000 + minor * 100 + patch, BuildConfig.VERSION_CODE)
    }

    // The application id is permanent: installed copies, their data and F-Droid all key on it.
    // A debug build is deliberately another app (".debug"), so that development on a phone never
    // touches a real installation.
    @Test
    fun applicationIdIsTheNamespaceAndADebugBuildIsAnotherApp() {
        val expected = if (BuildConfig.DEBUG) "us.theworkmans.proseplow.debug" else "us.theworkmans.proseplow"
        assertEquals(expected, BuildConfig.APPLICATION_ID)
    }
}
