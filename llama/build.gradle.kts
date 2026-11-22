import groovy.json.JsonSlurper
import java.security.MessageDigest
import javax.inject.Inject
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The Android library around the native code. It implements the native seam that :core defines
// and is the only module that knows about JNI, llama.cpp and the bundled model files.
//
// Three things happen here that an ordinary library module does not have (native/README.md,
// "The Android build", describes them for a reader; the comments below are for a maintainer):
// 1. prepareLlamaSource writes the patched copy of llama.cpp into this module's build directory;
// 2. CMake (src/main/cpp/CMakeLists.txt) compiles it, our own libraries and the JNI binding;
// 3. package<Variant>Models puts the selected, hash-verified model files among the assets.
plugins {
    // AGP 9 has built-in Kotlin support; org.jetbrains.kotlin.android must not be applied.
    alias(libs.plugins.android.library)
}

// --- What a build can be told ---

/** A Gradle property, or the environment variable that stands in for it when it is not given. */
fun setting(property: String, environment: String): Provider<String> =
    providers.gradleProperty(property).orElse(providers.environmentVariable(environment))

// Which models go into the APK: "none", "all", or manifest ids separated by commas.
val bundledModels: Provider<String> = setting("proseplow.models", "PROSEPLOW_MODELS").orElse("none")
// Where the converted models are: <directory>/<id>/<output>, as models/convert_models.py writes them.
val convertedModels: Provider<String> = setting("proseplow.modelDir", "PROSEPLOW_MODEL_OUT")

val repository: Directory = rootProject.layout.projectDirectory
val llamaSource: Provider<Directory> = layout.buildDirectory.dir("llama-src")

android {
    namespace = "us.theworkmans.proseplow.llama"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    // The NDK that upstream llama.cpp releases its own Android build with. r28 and later link
    // every library for 16 KB pages without being asked.
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 28
        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += "arm64-v8a"
        }
        consumerProguardFiles("consumer-rules.pro")

        // For src/androidTest: the checks that need the phone's processor and a real model.
        // They run with connectedDebugAndroidTest or by hand and are not part of check.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                // Everything else about the native build is in src/main/cpp/CMakeLists.txt.
                // ANDROID_PLATFORM follows minSdk (android-28) without being passed.
                arguments += listOf(
                    // One C++ runtime for the eleven libraries of this build. With the static
                    // runtime every library would carry a private copy, and exceptions and
                    // objects that cross from one library into another would meet two
                    // runtimes; the NDK only allows the static one for a single library.
                    "-DANDROID_STL=c++_shared",
                    // Optimised for every variant: an unoptimised llama.cpp is not usable for
                    // development. AGP takes the native build type from this argument, so the
                    // debug and the release variant share one native build and one set of
                    // libraries (a debug APK carries them with their debug information, a
                    // release APK stripped).
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DPROSEPLOW_LLAMA_SRC=${llamaSource.get().asFile.absolutePath}",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // These two shape this module's own test APK; the app must say the same for its APK, and
    // the checks of apk-checks.gradle.kts, which the app applies, fail its build if it does not.
    packaging {
        jniLibs {
            // Native libraries stored in the APK, page-aligned, and mapped from there instead of
            // being extracted at install time. The models depend on it: only in this mode does
            // AGP also put every other stored entry whose name ends in ".so" on a 16 KiB
            // boundary; with the legacy packaging such an entry is aligned to 4 bytes, and a
            // model could not be mapped. (It is the default from minSdk 23 on, and is stated
            // here because so much hangs on it.)
            useLegacyPackaging = false
        }
    }
    androidResources {
        // A model is stored, not deflated: it is opened by file descriptor and mapped.
        noCompress += "gguf.so"
    }

    sourceSets {
        // The committed reference data of the host tests is what the phone is checked against.
        getByName("androidTest").assets.srcDir(repository.dir("native/tests/data"))
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // As in :app: these checks look versions up online on every run.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// --- 1. The patched llama.cpp source ---

/**
 * Runs native/tools/prepare-llama-src.sh, the only way a llama.cpp source tree reaches a build.
 * The inputs are exactly what the copy is a function of: the pinned commit, the patches and the
 * files added to llama.cpp. The submodule itself is not an input: the script refuses to run
 * unless it is at the pinned commit, and exports that commit rather than the working tree.
 */
abstract class PrepareLlamaSource @Inject constructor(private val exec: ExecOperations) : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val script: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pinnedCommit: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val patches: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val addedFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun prepare() {
        exec.exec {
            commandLine("bash", script.get().asFile.absolutePath, output.get().asFile.absolutePath)
        }
    }
}

val prepareLlamaSource = tasks.register<PrepareLlamaSource>("prepareLlamaSource") {
    group = "build"
    description = "Writes the patched copy of llama.cpp that the native build compiles."
    script.set(repository.file("native/tools/prepare-llama-src.sh"))
    pinnedCommit.set(repository.file("native/patches/UPSTREAM_COMMIT"))
    patches.from(fileTree(repository.dir("native/patches")) { include("*.patch") })
    addedFiles.from(fileTree(repository.dir("native/llama-ext")) { exclude("**/*.md") })
    output.set(llamaSource)
}

// CMake must not configure (or build) before the source is there. AGP names these tasks after
// the build type and ABI, for example configureCMakeDebug[arm64-v8a].
tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) {
        dependsOn(prepareLlamaSource)
    }
}

// --- 3. The models ---

/**
 * Copies the selected models into a directory of assets, each verified against the SHA-256 that
 * models/manifest.json pins for it: the build fails rather than package a file that is not byte
 * for byte the pinned conversion.
 *
 * An asset is named `models/<id>.gguf.so`. The suffix is not decoration: every tool that
 * touches an APK (AGP, zipalign, apksigner) aligns uncompressed entries whose name ends in
 * `.so` to a page, 16 KiB, and other uncompressed entries only to 4 bytes. A model that starts
 * on a page boundary can be mapped straight out of the APK.
 */
abstract class PackageModels : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    /** "none", "all", or manifest ids separated by commas. */
    @get:Input
    abstract val selection: Property<String>

    /** The directory of converted models; only needed when something is selected. */
    @get:Input
    @get:Optional
    abstract val modelDirectory: Property<String>

    /** The same files as inputs, so that a re-converted model is packaged again. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val modelFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun pack() {
        val target = output.get().asFile.resolve(ASSET_DIRECTORY)
        target.deleteRecursively()
        target.mkdirs()
        val entries = selected(manifest.get().asFile, selection.get())
        if (entries.isEmpty()) {
            logger.lifecycle("$path: no model is bundled (proseplow.models=${selection.get()}). The app will list none of its own.")
            return
        }
        val directory = modelDirectory.orNull?.takeIf { it.isNotBlank() }
            ?: throw GradleException("Models are selected (${selection.get()}) but no directory was given: pass -Pproseplow.modelDir=<dir> or set PROSEPLOW_MODEL_OUT (see native/README.md).")
        for (entry in entries) {
            val source = sourceFile(directory, entry)
            if (!source.isFile) {
                throw GradleException("${entry.id}: $source does not exist. Convert the model first (models/README.md).")
            }
            val copy = target.resolve(assetName(entry.id))
            val digest = MessageDigest.getInstance("SHA-256")
            source.inputStream().use { input ->
                copy.outputStream().use { out ->
                    val buffer = ByteArray(1 shl 20)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        out.write(buffer, 0, read)
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != entry.sha256) {
                copy.delete()
                throw GradleException("${entry.id}: $source has SHA-256 $actual, models/manifest.json pins ${entry.sha256}. It is not the pinned conversion.")
            }
            logger.lifecycle("$path: ${entry.id} (${copy.length()} bytes, SHA-256 verified) -> assets/$ASSET_DIRECTORY/${copy.name}")
        }
    }

    /** One model of the manifest that can be packaged. */
    class Entry(val id: String, val output: String, val sha256: String)

    companion object {
        const val ASSET_DIRECTORY = "models"

        fun assetName(id: String): String = "$id.gguf.so"

        fun sourceFile(directory: String, entry: Entry): File = File(directory).resolve(entry.id).resolve(entry.output)

        /** The entries a selection names, in manifest order; fails on anything it cannot honour. */
        fun selected(manifest: File, selection: String): List<Entry> {
            val wanted = selection.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (wanted.isEmpty() || wanted == listOf("none")) return emptyList()
            @Suppress("UNCHECKED_CAST") // The manifest's shape is fixed by models/README.md.
            val models = (JsonSlurper().parse(manifest) as Map<String, Any?>)["models"] as List<Map<String, Any?>>
            val bundled = models.filter { it["status"] == "ready" && it["purpose"] == "bundled" }
            val chosen = if (wanted == listOf("all")) {
                bundled
            } else {
                if ("all" in wanted || "none" in wanted) {
                    throw GradleException("proseplow.models is \"none\", \"all\" or a list of ids, not a mixture: $selection")
                }
                val unknown = wanted - bundled.map { it["id"] as String }.toSet()
                if (unknown.isNotEmpty()) {
                    throw GradleException(
                        "proseplow.models names ${unknown.joinToString()}, which models/manifest.json does not list as ready and bundled. " +
                            "It lists: ${bundled.joinToString { it["id"] as String }}.",
                    )
                }
                bundled.filter { it["id"] in wanted }
            }
            return chosen.map { model ->
                val id = model["id"] as String
                val sha256 = model["output_sha256"] as String?
                    ?: throw GradleException("$id has no output_sha256 in models/manifest.json; a model is only packaged against a pinned hash.")
                Entry(id, model["output"] as String, sha256)
            }
        }
    }
}

androidComponents {
    onVariants { variant ->
        val packageModels = tasks.register<PackageModels>("package${variant.name.replaceFirstChar(Char::uppercaseChar)}Models") {
            group = "build"
            description = "Puts the selected model files, verified against models/manifest.json, among the ${variant.name} assets."
            manifest.set(repository.file("models/manifest.json"))
            selection.set(bundledModels)
            modelDirectory.set(convertedModels)
            modelFiles.from(
                bundledModels.zip(convertedModels.orElse("")) { selection, directory ->
                    if (directory.isBlank()) {
                        emptyList()
                    } else {
                        PackageModels.selected(repository.file("models/manifest.json").asFile, selection).map { PackageModels.sourceFile(directory, it) }
                    }
                },
            )
        }
        // AGP decides where the directory is and merges it into the variant's assets, from
        // where it reaches the app's APK like any other asset of a library.
        variant.sources.assets?.addGeneratedSourceDirectory(packageModels, PackageModels::output)
    }
}

// The unit tests compare the asset names of the build with the model catalogue.
tasks.withType<Test>().configureEach {
    val manifest = repository.file("models/manifest.json").asFile
    inputs.file(manifest).withPathSensitivity(PathSensitivity.NONE)
    systemProperty("proseplow.manifest", manifest.absolutePath)
}

dependencies {
    api(project(":core"))

    testImplementation(libs.junit)

    // The instrumented tests: the runner, its JUnit 4 integration, and the application context.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
}
