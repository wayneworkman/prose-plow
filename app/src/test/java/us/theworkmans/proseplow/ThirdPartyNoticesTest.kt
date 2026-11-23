package us.theworkmans.proseplow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import us.theworkmans.proseplow.core.model.ModelCatalog
import us.theworkmans.proseplow.settings.ThirdPartyNoticesText
import java.io.File

/**
 * The third-party notices are about this build, and their text says what their list says.
 *
 * `notices/notices.json` lists every component of the app that is someone else's work;
 * `notices/NOTICES.txt` is the text the app shows; `notices/licences/` holds each licence once.
 * All three are kept by hand. The build compares the libraries of the list with the runtime
 * classpath (the task `generate<Variant>ThirdPartyNotices`) and fails when they differ. What a
 * classpath cannot show is held here: the models against the manifest and the catalogue,
 * llama.cpp, the NDK and the build tools against the versions the build pins, and the text
 * against the list and the licence files, word for word.
 *
 * A test of this class that fails after a version was changed is not asking for the number in
 * the list to be changed with it. It is asking for the notices of the new version to be read
 * first: README.md, "Licence and third-party notices", says where.
 */
class ThirdPartyNoticesTest {

    /** The module directory: Gradle runs the unit tests from it. */
    private val module = File("").absoluteFile
    private val repository = checkNotNull(module.parentFile)

    private val notices: JsonObject = Json.parseToJsonElement(File(module, "notices/notices.json").readText()).jsonObject
    private val components: List<JsonObject> = notices.getValue("components").jsonArray.map { it.jsonObject }
    private val text: String = File(module, "notices/NOTICES.txt").readText()

    private fun JsonObject.string(key: String): String = checkNotNull(this[key]) { "an entry has no \"$key\": $this" }.jsonPrimitive.content

    private fun JsonObject.strings(key: String): List<String> = getValue(key).jsonArray.map { it.jsonPrimitive.content }

    private fun component(id: String): JsonObject = components.single { it["id"]?.jsonPrimitive?.content == id }

    private fun library(artifact: String): JsonObject = components.single { it["artifact"]?.jsonPrimitive?.content == artifact }

    /** Line breaks and indentation are the text's own business; the words are what must be there. */
    private fun words(value: String): String = value.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    private val shown: String by lazy { words(text) }

    /** The value of `name = "..."` in a Gradle file or a version catalogue. */
    private fun pinned(file: File, name: String): String =
        checkNotNull(Regex("""(?m)^\s*$name\s*=\s*"([^"]+)"""").find(file.readText())) { "$file sets no $name" }.groupValues[1]

    @Test
    fun everyEntrySaysWhatItIsUnderWhichLicenceAndWhereThatWasRead() {
        val kinds = setOf("jvm-library", "code-inside-a-library", "data-inside-a-library", "native-library", "code-added-by-the-build-tools", "model", "artwork", "port")
        val texts = notices.getValue("licence_texts").jsonObject
        for (entry in components) {
            assertTrue("an entry of an unknown kind: $entry", entry.string("kind") in kinds)
            for (key in listOf("name", "version", "licence", "source")) assertTrue("\"$key\" of ${entry.string("name")} is empty", entry.string(key).isNotBlank())
            assertTrue(entry.strings("holders").isNotEmpty())
            entry.strings("copyright_lines")
            assertTrue("${entry.string("name")} must say whether it has a NOTICE", entry.containsKey("notice"))
            // The text it points at is the text of its licence.
            val licenceText = checkNotNull(texts[entry.string("licence_text")]) { "${entry.string("name")} names a licence text that is not listed" }
            assertTrue(
                "${entry.string("name")} is under ${entry.string("licence")} and points at the text of ${licenceText.jsonObject.string("spdx")}",
                licenceText.jsonObject.string("spdx") in entry.string("licence"),
            )
            if (entry.string("kind") == "jvm-library") {
                assertTrue(entry.string("group").isNotBlank() && entry.string("artifact").isNotBlank())
            } else {
                assertTrue("what is not a library says where it is in the APK", entry.string("in_apk").isNotBlank())
            }
        }
        val ids = components.mapNotNull { it["id"]?.jsonPrimitive?.content }
        assertEquals("an id is used twice", ids.distinct(), ids)
        val libraries = components.filter { it.string("kind") == "jvm-library" }.map { it.string("group") + ":" + it.string("artifact") }
        assertEquals("a library is listed twice", libraries.distinct(), libraries)
    }

    /**
     * The notices cover exactly the models a build can contain: the entries of the manifest whose
     * purpose is "bundled", which is also what the catalogue offers. A model that is bundled
     * without an entry here fails this test, and so does an entry for a model that is not
     * bundled (any more, or yet): its notices would be in the app without it.
     */
    @Test
    fun theModelsOfTheNoticesAreExactlyTheModelsABuildCanContain() {
        val manifest = Json.parseToJsonElement(File(repository, "models/manifest.json").readText()).jsonObject
        val bundled = manifest.getValue("models").jsonArray.map { it.jsonObject }
            .filter { it.string("status") == "ready" && it.string("purpose") == "bundled" }
        assertTrue("the manifest lists bundled models", bundled.isNotEmpty())
        val listed = components.filter { it.string("kind") == "model" }

        assertEquals(bundled.map { it.string("id") }.sorted(), listed.map { it.string("model_id") }.sorted())
        assertEquals(ModelCatalog.bundled.map { it.id }.sorted(), listed.map { it.string("model_id") }.sorted())

        for (model in bundled) {
            val id = model.string("id")
            val entry = listed.single { it.string("model_id") == id }
            assertEquals(id, model.string("repository"), entry.string("repository"))
            assertEquals(id, model.string("revision"), entry.string("revision"))
            assertTrue("$id: the version names the repository and the revision", entry.string("version").let { model.string("repository") in it && model.string("revision") in it })
            assertEquals("$id: the licence the About screen shows", checkNotNull(ModelCatalog.find(id)).licence, entry.string("licence"))
            assertEquals("$id: the repository the About screen shows", checkNotNull(ModelCatalog.find(id)).sourceRepository, entry.string("repository"))
            // A converted file is a changed file, and both licences of the models ask for that to be said.
            assertTrue("$id: the statement of changes names the pinned conversion", model.string("output_sha256") in entry.string("changes"))
            assertTrue("$id: the statement of changes is in the text", words(entry.string("changes")) in shown)
            assertTrue("$id: the text names the revision", model.string("revision") in shown)
        }
        for (entry in listed) {
            entry["name_reservation"]?.let { assertTrue("what is said about a model's name is in the text", words(it.jsonPrimitive.content) in shown) }
        }
    }

    @Test
    fun llamaCppTheNdkAndTheBuildToolsAreListedInTheVersionsTheBuildPins() {
        val commit = File(repository, "native/patches/UPSTREAM_COMMIT").readLines().first().trim()
        val llama = component("native:llama.cpp")
        assertEquals(commit, llama.string("commit"))
        assertTrue(commit in llama.string("version") && commit in shown)

        val ndk = pinned(File(module, "build.gradle.kts"), "ndkVersion")
        assertEquals("both modules that package native code name one NDK", ndk, pinned(File(repository, "llama/build.gradle.kts"), "ndkVersion"))
        assertEquals(ndk, component("ndk:libc++_shared").string("ndk_version"))
        assertTrue(ndk in component("ndk:libc++_shared").string("version") && ndk in component("ndk:bionic-crt").string("version"))
        assertTrue("the text names the NDK the C++ runtime is taken from", "Android NDK $ndk" in shown)

        val plugin = pinned(File(repository, "gradle/libs.versions.toml"), "agp")
        assertTrue("the classes the build tools add are those of this Android Gradle plugin", "Android Gradle plugin $plugin" in component("build:r8-support").string("version"))
    }

    /**
     * Code that sits inside a library or inside llama.cpp was found in one version of it. Its
     * entry says which, so that a new version of what carries it cannot be entered without
     * that entry being looked at again.
     */
    @Test
    fun whatSitsInsideALibraryIsListedForTheVersionOfTheLibraryThatIsUsed() {
        val carriers = mapOf(
            "embedded:kotlin-stdlib:" to "as in kotlin-stdlib " + library("kotlin-stdlib").string("version"),
            "embedded:markdown-renderer:" to "as in multiplatform-markdown-renderer " + library("multiplatform-markdown-renderer-android").string("version"),
            "embedded:emoji2:" to "as in emoji2 " + library("emoji2").string("version"),
            "native:" to "as in llama.cpp " + component("native:llama.cpp").string("commit").take(7),
        )
        val inside = components.filter { it.string("kind") in setOf("code-inside-a-library", "data-inside-a-library") }
        for ((prefix, version) in carriers) {
            val entries = inside.filter { it.string("id").startsWith(prefix) }
            assertTrue("something is listed inside $prefix", entries.isNotEmpty())
            for (entry in entries) assertEquals(entry.string("id"), version, entry.string("version"))
        }
        assertEquals(library("multiplatform-markdown-renderer-android").string("version"), library("multiplatform-markdown-renderer-m3-android").string("version"))
    }

    @Test
    fun theLicenceOfLlamaCppInTheNoticesIsTheOneItShipsWith() {
        val licence = File(repository, "third_party/llama.cpp/LICENSE")
        // The library is a submodule; a checkout without it cannot be compared against.
        assumeTrue(licence.isFile)
        val lines = licence.readLines()
        val copyright = lines.single { it.startsWith("Copyright ") }
        assertEquals(listOf(copyright), component("native:llama.cpp").strings("copyright_lines"))
        // The text of the MIT licence in the app is this file, with the placeholder of the licence's own template for the line above.
        val template = lines.joinToString("\n").replace(copyright, "Copyright (c) <year> <copyright holders>")
        assertEquals(words(template), words(File(module, "notices/licences/MIT.txt").readText()))
        assertEquals("licences/MIT.txt", component("native:llama.cpp").string("licence_text"))
    }

    @Test
    fun theTextNamesEveryLibraryWithItsVersionAndCarriesEveryCopyrightLineAndNotice() {
        for (entry in components) {
            for (line in entry.strings("copyright_lines")) assertTrue("\"$line\" is in the text", words(line) in shown)
            val notice = entry.getValue("notice")
            if (notice !is JsonNull) assertTrue("the NOTICE of ${entry.string("name")} is in the text", words(notice.jsonPrimitive.content) in shown)
            entry["attribution"]?.let { assertTrue(words(it.jsonPrimitive.content) in shown) }
        }
        // Part 1 names the artifacts of a group in a line that begins with the group: "<group>: <artifact> <version>, ...".
        // Those lines name every library of the list in its version, and no library that the list does not have.
        val listed = components.filter { it.string("kind") == "jvm-library" }.map { "${it.string("group")}:${it.string("artifact")}:${it.string("version")}" }
        val groups = components.filter { it.string("kind") == "jvm-library" }.map { it.string("group") }.toSet()
        val named = text.lines().map { it.trim() }.filter { line -> groups.any { line.startsWith("$it: ") } }.flatMap { line ->
            line.substringAfter(": ").split(", ").map { "${line.substringBefore(": ")}:${it.substringBeforeLast(' ')}:${it.substringAfterLast(' ')}" }
        }
        assertEquals(listed.sorted(), named.sorted())
    }

    @Test
    fun theTextContainsEveryLicenceTextTheListNamesWordForWordAfterTheHeadingOfTheLicenceTexts() {
        val used = components.map { it.string("licence_text") }.toSortedSet()
        assertEquals("the licence texts that are listed are the ones that are used", notices.getValue("licence_texts").jsonObject.keys.toSortedSet(), used)
        val files = checkNotNull(File(module, "notices/licences").listFiles()).map { "licences/" + it.name }.toSortedSet()
        assertEquals("and the ones that are there", used, files)

        val heading = text.indexOf("\n" + ThirdPartyNoticesText.LICENCE_TEXTS + "\n")
        assertTrue("the text has the heading after which the screen joins the lines of a paragraph", heading > 0)
        for (path in used) {
            val licence = File(module, "notices/$path").readText().trimEnd('\n')
            assertTrue("$path has a text", licence.isNotBlank())
            val at = text.indexOf(licence)
            assertTrue("$path is in the text unchanged, line breaks included", at >= 0)
            assertTrue("$path stands among the licence texts", at > heading)
        }
    }

    /**
     * The build fails on a native library in an APK whose name it does not know
     * (`KNOWN_LIBRARIES` in llama/apk-checks.gradle.kts). That list is only worth something when
     * a name cannot be added to it without the notices saying whose library it is: every name
     * on it is this project's own binding, or is named in the list or the text of the notices
     * (the CPU variants of ggml by their common name).
     */
    @Test
    fun everyNativeLibraryTheBuildAllowsInAnApkIsOneTheNoticesAccountFor() {
        val script = File(repository, "llama/apk-checks.gradle.kts").readText()
        val allowed = Regex(""""(lib[^"]+\.so)"""").findAll(script.substringAfter("val KNOWN_LIBRARIES = setOf(").substringBefore("\n        )"))
            .map { it.groupValues[1] }.toList()
        assertTrue("the build's list of native libraries was found", allowed.size >= 4 && "libllama.so" in allowed)
        val places = components.mapNotNull { it["in_apk"]?.jsonPrimitive?.content }.joinToString(" ") + " " + shown
        for (name in allowed) {
            val accounted = name == "libproseplow_jni.so" || name in places || (name.startsWith("libggml-cpu-android_") && "libggml-cpu-android_*.so" in places)
            assertTrue("$name may be in an APK, and the notices do not say whose it is", accounted)
        }
    }

    /**
     * What is committed is the same on every machine: no path into a home directory, and nothing
     * a licence text does not have, in the list, the text or a licence file.
     */
    @Test
    fun theNoticesNameNoPlaceOnTheMachineTheyWereWrittenOn() {
        val files = listOf(File(module, "notices/notices.json"), File(module, "notices/NOTICES.txt")) + checkNotNull(File(module, "notices/licences").listFiles())
        for (file in files) {
            val content = file.readText()
            for (place in listOf("/home/", "/Users/", ":\\Users\\", "file://")) assertTrue("${file.name} names a place on a machine: $place", place !in content)
        }
    }
}
