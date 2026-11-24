import groovy.json.JsonSlurper
import java.security.MessageDigest
import javax.inject.Inject
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The Android library around the native code. It implements the native seam that :core defines
// and is the only module that knows about JNI, llama.cpp and the bundled model files.
//
// Four things happen here that an ordinary library module does not have (native/README.md,
// "The Android build", describes them for a reader; the comments below are for a maintainer):
// 1. prepareLlamaSource writes the patched copy of llama.cpp into this module's build directory;
// 2. CMake (src/main/cpp/CMakeLists.txt) compiles it, our own libraries and the JNI binding;
// 3. convertModels converts the checkpoints of the selected models that are not converted yet;
// 4. package<Variant>Models puts the selected, hash-verified model files into a directory of
//    assets, which only the app's APK and this module's test APK take in (see section 4).
plugins {
    // AGP 9 has built-in Kotlin support; org.jetbrains.kotlin.android must not be applied.
    alias(libs.plugins.android.library)
}

// --- What a build can be told ---

/** A Gradle property, or the environment variable that stands in for it when it is not given. */
fun setting(property: String, environment: String): Provider<String> =
    providers.gradleProperty(property).orElse(providers.environmentVariable(environment))

// The three directories outside the repository have defaults under ~/proseplow, the places the
// README's build instructions use, so that a build that follows them needs no setting at all.
val proseplowHome: String = File(System.getProperty("user.home"), "proseplow").path

// Which models go into the APK: "all" (the default: an app is built to be used), "none", or
// manifest ids separated by commas. apk-checks.gradle.kts reads the same setting with the same
// default and must stay in step with this line.
val bundledModels: Provider<String> = setting("proseplow.models", "PROSEPLOW_MODELS").orElse("all")
// Where the converted models are: <directory>/<id>/<output>, as models/convert_models.py writes
// them. Without a setting: $PROSEPLOW_BUILD/models, the place native/tools/env.sh gives the host
// build, or ~/proseplow/build/models.
val convertedModels: Provider<String> = setting("proseplow.modelDir", "PROSEPLOW_MODEL_OUT")
    .orElse(providers.environmentVariable("PROSEPLOW_BUILD").map { File(it, "models").path })
    .orElse(File(proseplowHome, "build/models").path)
// Where the downloaded checkpoints are, as <directory>/<id>/ or as a Hugging Face hub cache.
val checkpoints: Provider<String> = setting("proseplow.modelSrc", "PROSEPLOW_MODEL_SRC")
    .orElse(File(proseplowHome, "checkpoints").path)
// The Python virtualenv with the packages of native/requirements.txt, which the converters need.
val pythonVenv: Provider<String> = setting("proseplow.venv", "PROSEPLOW_VENV")
    .orElse(File(proseplowHome, "venv").path)

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
                    // libraries, which the app module strips when it packages either variant.
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

    // These two shape this module's own test APK; the app must say the same for its APK. The
    // checks of apk-checks.gradle.kts, which the app applies and this module applies at the end
    // of this file for its test APK, fail the build of either if it does not.
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

// --- 3. Converting the models ---

/**
 * Makes sure that the converted file of every selected model is there and is the pinned one,
 * converting what is missing with models/convert_models.py, one model at a time.
 *
 * For each model, in manifest order:
 * - a converted file whose SHA-256 is the manifest's `output_sha256` is left as it is;
 * - otherwise its checkpoint must be under [checkpointDirectory], and the converter of the
 *   Python environment turns it into the file (the script verifies every source file and the
 *   result against the manifest, and fails on any difference);
 * - a model that has neither stops the build before anything is converted, with the download
 *   command for each such model, so that one message lists everything that is missing.
 *
 * The task's outputs are the converted files of the selection, and its inputs the selection and
 * the manifest, which pins what those files must be. Gradle therefore runs it again only when a
 * file was removed or changed, or the selection or the manifest changed; a build in which none of
 * that happened skips it. The checkpoints are deliberately not inputs: hashing gigabytes on every
 * build would cost more than it could find, and the converter hashes them when it reads them.
 * The Python environment and the checkpoints are only needed when something must be converted,
 * so a builder who already has the converted files needs neither.
 */
abstract class ConvertModels @Inject constructor(private val exec: ExecOperations) : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    /** "none", "all", or manifest ids separated by commas. */
    @get:Input
    abstract val selection: Property<String>

    @get:Internal
    abstract val checkpointDirectory: Property<String>

    @get:Internal
    abstract val modelDirectory: Property<String>

    @get:Internal
    abstract val pythonEnvironment: Property<String>

    /** models/convert_models.py. */
    @get:Internal
    abstract val converter: RegularFileProperty

    /** The patched llama.cpp copy, which holds the converters; written by prepareLlamaSource. */
    @get:Internal
    abstract val llamaSource: DirectoryProperty

    /** The converted file of each selected model, at the place packaging reads it from. */
    @get:OutputFiles
    abstract val converted: ConfigurableFileCollection

    @TaskAction
    fun convert() {
        val entries = PackageModels.selected(manifest.get().asFile, selection.get())
        val directory = modelDirectory.get()
        val pending = entries.filter { entry ->
            val file = PackageModels.sourceFile(directory, entry)
            val current = file.isFile && sha256(file) == entry.sha256
            if (current) logger.info("$path: ${entry.id}: $file is the pinned conversion")
            !current
        }
        if (pending.isEmpty()) {
            if (entries.isNotEmpty()) logger.lifecycle("$path: ${entries.joinToString { it.id }}: already converted, as pinned")
            return
        }

        val sources = File(checkpointDirectory.get())
        val python = File(pythonEnvironment.get(), "bin/python")
        val missing = pending.filter { checkpointOf(sources, it) == null }
        // Both are checked before either is reported, so that one message says everything that is missing.
        val noPython = !python.canExecute()
        if (missing.isNotEmpty() || noPython) {
            val hf = File(pythonEnvironment.get(), "bin/hf")
            throw GradleException(
                buildString {
                    if (noPython) {
                        append("Converting ${pending.joinToString { it.id }} needs the Python environment, and there is none at ${pythonEnvironment.get()} ")
                        append("(no bin/python). Create it as README.md, \"The Python environment\", says, or name the one you have with ")
                        append("-Pproseplow.venv=<dir> or PROSEPLOW_VENV.\n")
                        if (missing.isNotEmpty()) append("\n")
                    }
                    if (missing.isNotEmpty()) {
                        append("Not converted yet, and no checkpoint to convert from: ${missing.joinToString { it.id }}.\n")
                        append("The checkpoints are looked for in $sources (-Pproseplow.modelSrc=<dir> or PROSEPLOW_MODEL_SRC to look elsewhere).\n")
                        append("Download ${if (missing.size == 1) "it" else "them"} at the pinned revision (README.md, \"Get the model checkpoints\")")
                        append(if (noPython) ", once the Python environment is there:\n\n" else ":\n\n")
                        for (entry in missing) {
                            append("    \"$hf\" download ${entry.repository} --revision ${entry.revision} \\\n")
                            append("        --local-dir \"${File(sources, entry.id)}\"\n")
                        }
                        append("\nOr leave ${if (missing.size == 1) "it" else "them"} out of the APK: -Pproseplow.models=<ids> names the models to bundle, -Pproseplow.models=none bundles none.")
                    }
                }.trimEnd(),
            )
        }

        for (entry in pending) {
            logger.lifecycle("$path: converting ${entry.id} from ${checkpointOf(sources, entry)} (one model at a time)")
            val started = System.nanoTime()
            val result = exec.exec {
                commandLine(
                    python.path, converter.get().asFile.path,
                    "--src", sources.path,
                    "--out", directory,
                    "--llama-src", llamaSource.get().asFile.path,
                    // The file is missing or is not the pinned one, whatever the record beside it says.
                    "--force",
                    entry.id,
                )
                // CPU only, and no bytecode caches in the repository or the patched copy.
                environment("CUDA_VISIBLE_DEVICES", "")
                environment("PYTHONDONTWRITEBYTECODE", "1")
                isIgnoreExitValue = true
            }
            if (result.exitValue == MISSING_PACKAGES) {
                throw GradleException(
                    "The Python environment at ${pythonEnvironment.get()} lacks packages the converters need (named above). " +
                        "Run `pip install -r native/requirements.txt` in it (README.md, \"The Python environment\").",
                )
            }
            if (result.exitValue != 0) {
                throw GradleException(
                    "${entry.id}: models/convert_models.py failed (exit ${result.exitValue}); its message is above. A checkpoint file " +
                        "that is not the pinned one is replaced by downloading it again (README.md, \"Get the model checkpoints\").",
                )
            }
            logger.lifecycle("$path: ${entry.id} converted in ${(System.nanoTime() - started) / 1_000_000_000} s")
        }
    }

    private companion object {
        /** convert_models.py's exit status when the Python environment lacks packages (EXIT_MISSING_PACKAGES there). */
        const val MISSING_PACKAGES = 3
    }

    /** The directory of [entry]'s checkpoint under [sources], in either layout convert_models.py accepts, or null. */
    private fun checkpointOf(sources: File, entry: PackageModels.Entry): File? =
        listOf(
            File(sources, entry.id),
            File(sources, "models--${entry.repository.replace("/", "--")}/snapshots/${entry.revision}"),
        ).firstOrNull { it.isDirectory }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

val convertModels = tasks.register<ConvertModels>("convertModels") {
    group = "build"
    description = "Converts the checkpoints of the selected models whose pinned 16-bit file is not there yet."
    manifest.set(repository.file("models/manifest.json"))
    selection.set(bundledModels)
    checkpointDirectory.set(checkpoints)
    modelDirectory.set(convertedModels)
    pythonEnvironment.set(pythonVenv)
    converter.set(repository.file("models/convert_models.py"))
    llamaSource.set(prepareLlamaSource.flatMap { it.output })
    converted.from(
        bundledModels.zip(convertedModels) { selection, directory ->
            PackageModels.selected(repository.file("models/manifest.json").asFile, selection).map { PackageModels.sourceFile(directory, it) }
        },
    )
    // The copy is only read when something is converted, but it must be there before that.
    dependsOn(prepareLlamaSource)
}

// --- 4. The models in the APK ---

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

    /** The directory of converted models. */
    @get:Input
    abstract val modelDirectory: Property<String>

    /** The same files, as convertModels left them, as inputs: a re-converted model is packaged again. */
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
        val directory = modelDirectory.get()
        for (entry in entries) {
            val source = sourceFile(directory, entry)
            if (!source.isFile) {
                // convertModels runs first and fails rather than leave a file out; this is a file removed since.
                throw GradleException("${entry.id}: $source does not exist. Build again, and convertModels converts it.")
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

    /** One model of the manifest that can be packaged, with where its checkpoint is published. */
    class Entry(val id: String, val output: String, val sha256: String, val repository: String, val revision: String)

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
                Entry(id, model["output"] as String, sha256, model["repository"] as String, model["revision"] as String)
            }
        }
    }
}

// Where the models go, and why not into this library's own assets. Android lint of the app, and
// so `check`, reads the merged assets of this library: models among them would make every lint
// run convert and copy gigabytes, and fail on a machine without the checkpoints. So the
// library's own assets carry no model, and the models reach only what installs them:
// - the app's APK: the app module adds them to its merged assets (a transform of AGP's ASSETS
//   artifact there), taking this directory through the configuration <variant>ModelAssets below;
//   lint of the app does not read its own merged assets;
// - this module's test APK: as generated assets of its androidTest component, which nothing but
//   the test APK reads.
// Converting and packaging therefore run for the tasks that build those APKs and for no other.
// (Without models in it, the library's AAR stays small too.)

/** The Usage of the configurations that hand the model directory to the app module. */
val modelAssetsUsage: Usage = objects.named(Usage::class.java, "proseplow-model-assets")
/** Which build type's model directory a configuration carries; the app module asks by the same name. */
val modelAssetsBuildType: Attribute<String> = Attribute.of("us.theworkmans.proseplow.modelAssets.buildType", String::class.java)

androidComponents {
    onVariants { variant ->
        val capitalised = variant.name.replaceFirstChar(Char::uppercaseChar)
        val packageModels = tasks.register<PackageModels>("package${capitalised}Models") {
            group = "build"
            description = "Puts the selected model files, verified against models/manifest.json, into a directory of ${variant.name} assets."
            manifest.set(repository.file("models/manifest.json"))
            selection.set(bundledModels)
            modelDirectory.set(convertedModels)
            // The outputs of convertModels, which makes this task depend on it: every packaging
            // of models, for either variant and for the test APK, converts first.
            modelFiles.from(convertModels)
            output.convention(layout.buildDirectory.dir("generated/proseplow-models/${variant.name}"))
        }
        // For the app module: this directory, under the attributes it asks for.
        configurations.consumable("${variant.name}ModelAssets") {
            attributes {
                attribute(Usage.USAGE_ATTRIBUTE, modelAssetsUsage)
                attribute(modelAssetsBuildType, variant.buildType ?: variant.name)
            }
            outgoing.artifact(packageModels.flatMap { it.output })
        }
        // For this module's test APK (AGP decides where the directory is when it is added here).
        variant.androidTest?.sources?.assets?.addGeneratedSourceDirectory(packageModels, PackageModels::output)
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

// The test APK of this module carries the models too (the instrumented tests run them from its
// assets), so it is held to the same checks as the app's APK: every model in it the pinned one,
// stored on a 16 KiB boundary, and every native library built for 16 KB pages.
//
// The script is named through file() and not by a bare string, on purpose. Lint analyses this
// build script too, and when apply(from = ...) is given a string that names a script beside
// this one, it follows the string, reads that script and fails on it: the analysis ends with
// "findFirCompiledSymbol only works on compiled declarations", lintAnalyze<Variant> fails and
// `check` with it. A File is not followed.
apply(from = file("apk-checks.gradle.kts"))
