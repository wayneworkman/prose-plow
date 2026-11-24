import com.android.build.api.artifact.ArtifactTransformationRequest
import com.android.build.api.artifact.SingleArtifact
import groovy.json.JsonSlurper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.w3c.dom.Element

plugins {
    // AGP 9 has built-in Kotlin support; org.jetbrains.kotlin.android must not be applied.
    // Applied by id, not through the catalog alias (the version still comes from the catalog, in
    // the root build file): F-Droid's update checker only takes versionCode and versionName from a
    // Gradle file in which it finds this line.
    id("com.android.application")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "us.theworkmans.proseplow"
    compileSdk = 37
    // Pin to the installed build-tools; AGP's default (36.0.0) would otherwise be needed.
    buildToolsVersion = "37.0.0"
    // The same NDK as :llama, which compiles the native code. This module compiles none, but it is
    // the one that strips the native libraries when it packages them, and without this line AGP
    // looks for its own default NDK, does not find it, and packages every library unstripped
    // (about 80 MB more, and with symbols) while still reporting success.
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "us.theworkmans.proseplow"
        minSdk = 28
        targetSdk = 37

        // Plain literals on purpose: F-Droid's update checker reads these two lines as text, without
        // running Gradle, so they must not be computed. The rule is
        //   versionCode = major * 10000 + minor * 100 + patch   (0.1.0 is 100, 1.2.3 is 10203)
        // with minor and patch at most 99. Change both together: the check below the android block
        // fails the build when they disagree.
        versionCode = 100
        versionName = "0.1.0"

        // The app is arm64 only. This also drops the other ABIs of the native libraries that
        // AndroidX bundles (libandroidx.graphics.path.so), which would otherwise be packaged.
        // Lint's ChromeOsAbiSupport asks for an x86_64 build as well; on-device inference is built
        // and tested for arm64 phones only, so there is deliberately none.
        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += "arm64-v8a"
        }

        // For the instrumented tests (src/androidTest), which need a phone: they exercise the real
        // Android Keystore. They run with connectedDebugAndroidTest only and are not part of check.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // A debug build is another app as far as the phone is concerned: its own package, its own
            // data directory and its own Keystore keys. Development (installing, clearing data,
            // uninstalling, the instrumented tests, which remove the app when they are done) can
            // therefore never touch a real installation and the chats in it. Its launcher label
            // differs too (src/debug/res).
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            // No signingConfig, on purpose: AGP packages the release APK unsigned, and signReleaseApk
            // (below) signs it with the builder's own key. See SignApk for why.
        }
    }

    // F-Droid requirement: without this AGP adds a list of the dependencies to the APK's signing
    // block, encrypted with a Google key so that nobody else can read what the APK carries.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // What the native code of :llama needs from the APK (native/README.md, "Models in the APK").
    // A model is an asset named *.gguf.so that the app opens by file descriptor and maps in place,
    // so it must be stored, not deflated, and must start on a page boundary. AGP puts stored
    // entries named *.so on 16 KiB boundaries only while the native libraries themselves are
    // stored and page-aligned, which is what useLegacyPackaging = false says (the default at this
    // minSdk, stated because the models depend on it). The checks applied at the end of this file
    // make the task that writes an APK fail if it comes out otherwise.
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
    androidResources {
        noCompress += "gguf.so"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // These three checks ask Google's and Maven Central's servers for newer versions on every
        // run. The versions are pinned on purpose, and a build of this app should contact nothing
        // beyond what resolving its dependencies needs.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
}

// The version rule, enforced. Configuration fails, and with it every task, when the two literals
// in defaultConfig disagree or the name is not "major.minor.patch" in plain numbers.
val declaredVersion: String = android.defaultConfig.versionName.orEmpty()
val declaredCode: Int? = android.defaultConfig.versionCode
val expectedCode: Int? = Regex("""(0|[1-9]\d{0,4})\.(0|[1-9]\d?)\.(0|[1-9]\d?)""").matchEntire(declaredVersion)?.destructured
    ?.let { (major, minor, patch) -> major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt() }
if (expectedCode == null || expectedCode != declaredCode) {
    throw GradleException(
        "app/build.gradle.kts: version \"$declaredVersion\" and code $declaredCode disagree. The name must be " +
            "major.minor.patch (minor and patch at most 99) and the code major * 10000 + minor * 100 + patch" +
            (expectedCode?.let { ", here $it." } ?: "."),
    )
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

/**
 * Fails when a variant's merged manifest requests or declares a permission that is not in
 * [allowed]; the permissions it found are written to [report].
 *
 * The app's promise is that it never uses the network. Its own manifest asks for five
 * permissions: four for finishing an answer out of sight (a foreground service of the special-use
 * type, notifications and a wake lock) and one that keeps the background maintenance job across
 * restarts of the phone; the allow-list is those five, the two the fingerprint library adds and
 * the one the support library declares for the app's own receivers: eight entries. The attribute
 * `android:permission` of a component is not checked here: it is what the app requires of others
 * (the job service requires the system's `BIND_JOB_SERVICE`), not what it asks for. Every dependency's manifest is merged into the app's and can add one unnoticed, so it
 * is the merged result, the manifest that is packaged, that is checked.
 *
 * The task is not a check beside the build but a step of it: it takes the merged manifest in and
 * hands the same manifest on as [checkedManifest], and everything that packages or installs the
 * app (an APK, a bundle, an install, the instrumented tests) is given that copy and no other.
 * There is therefore no task that can produce an artifact from a manifest this has not passed.
 */
abstract class VerifyManifestPermissions : DefaultTask() {

    @get:Input
    abstract val variantName: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val allowed: SetProperty<String>

    /** The manifest as it came in, written only when it passed. What gets packaged is this file. */
    @get:OutputFile
    abstract val checkedManifest: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val manifest = mergedManifest.get().asFile
        // What an earlier run let through is no answer for this one.
        checkedManifest.get().asFile.delete()
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        val document = factory.newDocumentBuilder().parse(manifest)

        val found = sortedSetOf<String>()
        for (tag in PERMISSION_TAGS) {
            val elements = document.getElementsByTagName(tag)
            for (index in 0 until elements.length) {
                val name = (elements.item(index) as Element).getAttributeNS(ANDROID_NAMESPACE, "name")
                found += name.ifEmpty { "<$tag> without android:name" }
            }
        }

        val unexpected = found - allowed.get()
        if (unexpected.isNotEmpty()) {
            throw GradleException(
                "The merged ${variantName.get()} manifest has permissions outside the allow-list: " +
                    unexpected.joinToString() + ".\n" +
                    "Allowed: " + allowed.get().sorted().joinToString() + ".\n" +
                    "Remove the <uses-permission>, or the dependency that merges it in: the manifest merger " +
                    "report under build/outputs/logs names the source of every element. Checked: $manifest",
            )
        }
        report.get().asFile.writeText(found.joinToString(separator = "\n", postfix = "\n"))
        manifest.copyTo(checkedManifest.get().asFile, overwrite = true)
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"

        /** Both ways of requesting a permission, and the declaration of a new one. */
        val PERMISSION_TAGS = listOf("uses-permission", "uses-permission-sdk-23", "permission")
    }
}

/**
 * Writes the third-party notices into a variant as the raw resource `third_party_notices`, after
 * checking that they are about that variant: the libraries that [components] lists must be
 * exactly the libraries on the variant's runtime classpath, each in the same version.
 *
 * The licences of what an APK contains ask for their notices to go with every copy of it. Which
 * libraries an APK contains is decided when Gradle resolves the dependencies, and it changes
 * with one edited version number, with a new dependency, and with a library that starts to
 * depend on another. A list that is kept by hand beside that would be right until it silently
 * was not, so the list is compared with what was resolved, and a difference in either direction
 * fails the build with what to do about it.
 *
 * Like [VerifyManifestPermissions] this is a step of the build and not a check beside it. The
 * resource is the output of this task and of nothing else (no source directory holds a file of
 * that name; a test that reads the sources holds that), and the screen that shows the notices
 * does not compile without it. There is therefore no task that can package an app whose notices
 * were not compared with what it contains.
 *
 * What a classpath cannot show is checked by ThirdPartyNoticesTest: the models, llama.cpp and
 * the NDK against the versions the build pins, and [text] against [components] and the licence
 * files.
 */
abstract class GenerateThirdPartyNotices : DefaultTask() {

    @get:Input
    abstract val variantName: Property<String>

    /** `group:artifact:version` of every artifact of an external module on the variant's runtime classpath. */
    @get:Input
    abstract val resolved: SetProperty<String>

    /** `notices/notices.json`. Its entries of the kind `jvm-library` are what is compared. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val components: RegularFileProperty

    /** `notices/NOTICES.txt`, the text the app shows. It is copied as it is, and only when the list passed. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val text: RegularFileProperty

    /** A resource directory that belongs to this task alone; the Android Gradle plugin decides where it is. */
    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun generate() {
        val raw = output.get().asFile.resolve("raw")
        // What an earlier run let through is no answer for this one.
        raw.deleteRecursively()

        @Suppress("UNCHECKED_CAST") // The shape of the file is the one ThirdPartyNoticesTest reads.
        val entries = (JsonSlurper().parse(components.get().asFile) as Map<String, Any?>)["components"] as List<Map<String, Any?>>
        val libraries = entries.filter { it["kind"] == "jvm-library" }.map { "${it["group"]}:${it["artifact"]}:${it["version"]}" }
        val listed = libraries.toSortedSet()
        val packaged = resolved.get().toSortedSet()

        val unlisted = packaged - listed
        val stale = listed - packaged
        val repeated = libraries.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()
        if (unlisted.isNotEmpty() || stale.isNotEmpty() || repeated.isNotEmpty()) {
            val lines = { artifacts: Collection<String> -> if (artifacts.isEmpty()) "  (none)" else artifacts.joinToString("\n") { "  - $it" } }
            throw GradleException(
                "The third-party notices do not describe what the ${variantName.get()} build packages.\n\n" +
                    "On the runtime classpath, but not in app/notices/notices.json:\n" + lines(unlisted) + "\n" +
                    "In app/notices/notices.json, but not on the runtime classpath:\n" + lines(stale) + "\n" +
                    (if (repeated.isEmpty()) "" else "Listed more than once:\n" + lines(repeated) + "\n") +
                    "\nWhat to do, in app/notices/notices.json and in app/notices/NOTICES.txt alike:\n" +
                    "1. An artifact that is in both lists has another version. Read the <licenses> of its new POM and any\n" +
                    "   LICENSE or NOTICE file inside the artifact and in its repository at that version; if nothing changed,\n" +
                    "   correct \"version\" in its entry and the version in the line of part 1 that names it.\n" +
                    "2. An artifact that is only in the first list is new. Read the same files, add an entry of the kind\n" +
                    "   \"jvm-library\" with its licence, copyright lines and NOTICE text, and name it in part 1 under its\n" +
                    "   licence. A licence the app does not have yet also needs its text in app/notices/licences/ and in part 2.\n" +
                    "3. An artifact that is only in the second list is gone. Remove its entry and its mention in part 1.\n" +
                    "Then run ./gradlew :app:testDebugUnitTest --tests \"*ThirdPartyNoticesTest\", which holds the text to the list.\n" +
                    "README.md, \"Licence and third-party notices\", says more.",
            )
        }
        raw.mkdirs()
        text.get().asFile.copyTo(raw.resolve(RESOURCE_FILE))
    }

    private companion object {
        /** The file name is the resource's name: `R.raw.third_party_notices`. */
        const val RESOURCE_FILE = "third_party_notices.txt"
    }
}

androidComponents {
    // Every permission a merged manifest may contain. The app itself declares the four that let an
    // answer be finished while it is not on screen: the foreground service and its type, the
    // notifications, and the wake lock; and RECEIVE_BOOT_COMPLETED, which keeps the background
    // maintenance job scheduled across restarts. USE_BIOMETRIC and USE_FINGERPRINT are merged in
    // by androidx.biometric. androidx.core declares and requests the app's own signature-level
    // DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION, added per variant below because it carries the
    // application id. Anything else, android.permission.INTERNET above all, fails the build.
    val allowedPermissions = setOf(
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.WAKE_LOCK",
        "android.permission.RECEIVE_BOOT_COMPLETED",
        "android.permission.USE_BIOMETRIC",
        "android.permission.USE_FINGERPRINT",
    )

    // verifyDebugManifest and verifyReleaseManifest. Each is put into the build as a transform of
    // its variant's merged manifest: whatever consumes that manifest (processing the resources,
    // and through it every package, bundle, install and connected-test task) consumes the output
    // of the check, so none of them can run, here or on F-Droid's build server, before the check
    // has passed, and when it fails nothing is packaged. `check` runs both as well.
    onVariants { variant ->
        val verify = tasks.register<VerifyManifestPermissions>("verify${variant.name.replaceFirstChar(Char::uppercaseChar)}Manifest") {
            group = "verification"
            description = "Fails if the merged ${variant.name} manifest has a permission outside the allow-list."
            variantName.set(variant.name)
            allowed.set(variant.applicationId.map { id -> allowedPermissions + "$id.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" })
            report.set(layout.buildDirectory.file("reports/manifest-permissions/${variant.name}.txt"))
        }
        variant.artifacts.use(verify)
            .wiredWithFiles(VerifyManifestPermissions::mergedManifest, VerifyManifestPermissions::checkedManifest)
            .toTransform(SingleArtifact.MERGED_MANIFEST)
        tasks.named("check") { dependsOn(verify) }

        // generateDebugThirdPartyNotices and generateReleaseThirdPartyNotices. Each compares
        // notices/notices.json with the libraries its variant really packages and, when they agree,
        // writes notices/NOTICES.txt into a resource directory of its own, which becomes one of the
        // variant's. Merging the resources, and through it compiling and packaging, therefore waits
        // for the comparison, and nothing else can supply the resource. `check` runs both as well.
        val notices = tasks.register<GenerateThirdPartyNotices>("generate${variant.name.replaceFirstChar(Char::uppercaseChar)}ThirdPartyNotices") {
            group = "build"
            description = "Checks the third-party notices against the ${variant.name} runtime classpath and writes them as a raw resource."
            variantName.set(variant.name)
            resolved.set(
                variant.runtimeConfiguration.incoming.artifactView {
                    // The project's own modules are not third-party (and their artifacts could not be
                    // asked for here without the attributes the Android Gradle plugin selects them by).
                    componentFilter { it is ModuleComponentIdentifier }
                }.artifacts.resolvedArtifacts.map { artifacts ->
                    artifacts.map { artifact ->
                        val id = artifact.id.componentIdentifier as ModuleComponentIdentifier
                        "${id.group}:${id.module}:${id.version}"
                    }.toSet()
                },
            )
            components.set(layout.projectDirectory.file("notices/notices.json"))
            text.set(layout.projectDirectory.file("notices/NOTICES.txt"))
        }
        variant.sources.res?.addGeneratedSourceDirectory(notices, GenerateThirdPartyNotices::output)
        tasks.named("check") { dependsOn(notices) }
    }
}

/**
 * Signs the release APK with the key of whoever builds it, and verifies the result.
 *
 * There is no official key and no published APK: everyone who installs the app built it and
 * signs it with a key of their own. The key is a PKCS12 keystore, by default
 * `~/.config/proseplow/proseplow-release.p12` (made once by `tools/signing-key.sh`), with the
 * alias `proseplow`. Its password is taken, in this order, from the environment variable
 * `PROSEPLOW_KEYSTORE_PASSWORD`, or from the login keyring through `tools/keyring.py`, where the
 * key script put it. It reaches `apksigner` on its standard input, never on a command line or in
 * an environment, and is never printed or written anywhere.
 *
 * Why a task and not AGP's `signingConfigs`: a signing configuration takes its password as a plain
 * string while the build is configured, so it would have to be looked up for every build, debug
 * builds and `check` included, and AGP writes the configuration, password and all, into a file
 * of the build directory. Here it is looked up only when a release APK is signed and lives only in
 * memory while it is.
 *
 * The signature is what `apksigner` gives an APK whose minimum SDK is 28 with the v1 (JAR) scheme
 * switched off: APK Signature Scheme v3, which Android 9, the oldest version the app runs on,
 * checks. The v1 signature is off because it is what stops tools that copy signatures from
 * handling an APK of more than 2 GiB, and v4 is off because it is a separate file that installing
 * does not need. Alignment is kept as AGP made it (`--alignment-preserved`): the models inside are
 * mapped in place and must stay on their 16 KiB boundaries, which the checks of
 * `apk-checks.gradle.kts` confirm on the signed file too.
 *
 * The task is a transform of the variant's APK, so the signed file is THE release APK: it is what
 * `assembleRelease` leaves in `build/outputs/apk/release/` and what `installRelease` installs.
 */
abstract class SignApk @Inject constructor(private val exec: ExecOperations) : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val unsigned: DirectoryProperty

    @get:OutputDirectory
    abstract val signed: DirectoryProperty

    @get:Internal
    abstract val transformationRequest: Property<ArtifactTransformationRequest<SignApk>>

    @get:Input
    abstract val keystorePath: Property<String>

    /** The keystore again, as a file whose content is an input: a new key signs again. Empty while it is missing. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val keystoreFile: ConfigurableFileCollection

    @get:Input
    abstract val keyAlias: Property<String>

    @get:Internal
    abstract val buildTools: DirectoryProperty

    /** tools/keyring.py of the repository. */
    @get:Internal
    abstract val keyringTool: RegularFileProperty

    @TaskAction
    fun sign() {
        val keystore = File(keystorePath.get())
        if (!keystore.isFile) {
            throw GradleException(
                "No signing key at $keystore. A release APK is signed with your own key; create it once with\n\n" +
                    "    tools/signing-key.sh\n\n" +
                    "and back it up together with its password (README.md, \"Create your signing key\"). A key kept elsewhere is " +
                    "named with -Pproseplow.keystore=<file> or PROSEPLOW_KEYSTORE.",
            )
        }
        val password = password(keystore)
        val apksigner = buildTools.file("apksigner").get().asFile.path
        val zipalign = buildTools.file("zipalign").get().asFile.path
        // apksigner and zipalign are started by scripts that need a Java; the one running Gradle will do.
        val javaHome = System.getProperty("java.home")
        // The directory belongs to this task. An APK left there by an earlier build (the unsigned
        // one, from before the build signed by itself) would be mistaken for the result.
        signed.get().asFile.listFiles { file -> file.name.endsWith(".apk") || file.name.endsWith(".idsig") }?.forEach { it.delete() }

        transformationRequest.get().submit(this) { artifact ->
            val input = File(artifact.outputFile)
            val output = signed.file(input.name.replace("-unsigned", "")).get().asFile
            output.delete()
            // apksigner prompts for the password even when it reads it from its input; what it
            // says is only shown when it fails. It never repeats the password.
            val said = ByteArrayOutputStream()
            val signing = exec.exec {
                commandLine(
                    apksigner, "sign",
                    "--ks", keystore.path, "--ks-type", "PKCS12", "--ks-key-alias", keyAlias.get(),
                    "--ks-pass", "stdin",
                    "--v1-signing-enabled", "false",
                    "--v4-signing-enabled", "false",
                    "--alignment-preserved", "true",
                    "--out", output.path, input.path,
                )
                environment("JAVA_HOME", javaHome)
                standardInput = ByteArrayInputStream((password + "\n").toByteArray())
                standardOutput = said
                errorOutput = said
                isIgnoreExitValue = true
            }
            if (signing.exitValue != 0) {
                output.delete()
                throw GradleException(
                    "apksigner could not sign with $keystore (alias ${keyAlias.get()}):\n" +
                        said.toString().replace("Keystore password for signer #1: ", "").lines()
                            .filterNot { it.trimStart().startsWith("at ") || it.trimStart().startsWith("... ") }
                            .joinToString("\n").trim() + "\nA wrong password, " +
                        "or a keystore whose key has another alias (-Pproseplow.keyAlias=<alias> or PROSEPLOW_KEY_ALIAS), are the usual causes.",
                )
            }
            val report = ByteArrayOutputStream()
            exec.exec {
                commandLine(apksigner, "verify", "--verbose", "--print-certs", output.path)
                environment("JAVA_HOME", javaHome)
                standardOutput = report
            }
            exec.exec {
                commandLine(zipalign, "-c", "-P", "16", "4", output.path)
            }
            val summary = report.toString().lines().filter {
                it.startsWith("Verifies") || it.startsWith("Verified using v") || it.contains("certificate DN") || it.contains("certificate SHA-256")
            }
            logger.lifecycle("$path: signed $output\n" + summary.joinToString("\n") { "    $it" })
            output
        }
    }

    /** The keystore's password from the environment or the login keyring; it never leaves this process except to apksigner. */
    private fun password(keystore: File): String {
        System.getenv(PASSWORD_VARIABLE)?.takeIf { it.isNotEmpty() }?.let { return it }
        val tool = keyringTool.get().asFile
        if (tool.canExecute()) {
            val process = ProcessBuilder(tool.path, "get")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .start()
            val text = process.inputStream.bufferedReader().readText()
            // A keyring that is locked and asks to be unlocked gets a minute; then the build goes on without it.
            if (process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) {
                text.trimEnd('\n').takeIf { it.isNotEmpty() }?.let { return it }
            } else {
                process.destroyForcibly()
            }
        }
        throw GradleException(
            "No password for the signing key $keystore: $PASSWORD_VARIABLE is not set, and the login keyring holds none " +
                "(or there is no keyring here). Give it for this build without leaving it in your shell's history:\n\n" +
                "    read -rs $PASSWORD_VARIABLE && export $PASSWORD_VARIABLE\n\n" +
                "and run the build again (README.md, \"Build a release APK\").",
        )
    }

    private companion object {
        const val PASSWORD_VARIABLE = "PROSEPLOW_KEYSTORE_PASSWORD"
    }
}

/**
 * Adds the models to a variant's merged assets: the directory that `:llama:package<Variant>Models`
 * fills, verified against the manifest, joined to what AGP merged.
 *
 * This is where the models enter the app, rather than as assets of the library, so that only
 * the tasks that build an APK (and through them `assemble`, `install` and `bundle`) need them.
 * Android lint, which `check` runs, reads the library's merged assets but not this module's, and
 * never asks for a model; `llama/build.gradle.kts`, "4. The models in the APK", says more.
 *
 * The models are hard links to the packaged files where the file system allows it, so that this
 * step costs neither the time nor the disk of another copy; otherwise they are copied. A model
 * whose name an asset of the app already has fails the task rather than replace it.
 */
abstract class AddModelAssets : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val merged: DirectoryProperty

    /** The model directories of `:llama`, through the configuration `<variant>ModelAssets`. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val models: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val combined: DirectoryProperty

    @TaskAction
    fun combine() {
        val target = combined.get().asFile
        target.deleteRecursively()
        merged.get().asFile.copyRecursively(target)
        for (directory in models.files.filter { it.isDirectory }) {
            for (file in directory.walkTopDown().filter { it.isFile }) {
                val placed = target.resolve(file.relativeTo(directory).path)
                if (placed.exists()) {
                    throw GradleException("The asset ${file.relativeTo(directory)} exists twice: among the app's assets and among the models.")
                }
                placed.parentFile.mkdirs()
                try {
                    Files.createLink(placed.toPath(), file.toPath())
                } catch (_: IOException) {
                    file.copyTo(placed)
                } catch (_: UnsupportedOperationException) {
                    file.copyTo(placed)
                }
            }
        }
    }
}

// add<Variant>ModelAssets: the models of :llama, joined to the merged assets of each variant.
val modelAssetsUsage: Usage = objects.named(Usage::class.java, "proseplow-model-assets")
val modelAssetsBuildType: Attribute<String> = Attribute.of("us.theworkmans.proseplow.modelAssets.buildType", String::class.java)
val modelAssetsSource = configurations.dependencyScope("modelAssets")
dependencies {
    add(modelAssetsSource.name, project(":llama"))
}
androidComponents {
    onVariants { variant ->
        val capitalised = variant.name.replaceFirstChar(Char::uppercaseChar)
        val wanted = configurations.resolvable("${variant.name}ModelAssets") {
            extendsFrom(modelAssetsSource.get())
            attributes {
                attribute(Usage.USAGE_ATTRIBUTE, modelAssetsUsage)
                attribute(modelAssetsBuildType, variant.buildType ?: variant.name)
            }
        }
        val add = tasks.register<AddModelAssets>("add${capitalised}ModelAssets") {
            group = "build"
            description = "Adds the bundled models to the merged ${variant.name} assets."
            models.from(wanted)
        }
        variant.artifacts.use(add)
            .wiredWithDirectories(AddModelAssets::merged, AddModelAssets::combined)
            .toTransform(SingleArtifact.ASSETS)
    }
}

// signReleaseApk: put between AGP's packaging and everything that takes the release APK.
//
// -Pproseplow.sign=false (or PROSEPLOW_SIGN=false) leaves it out, for a builder whose signing
// happens elsewhere (a build server that signs with its own key, or tools/sign-release.sh later):
// assembleRelease then writes app-release-unsigned.apk, as AGP does without a signing
// configuration, with the models in it and every check of the APK made.
val signRelease: Boolean = when (val value = providers.gradleProperty("proseplow.sign").orElse(providers.environmentVariable("PROSEPLOW_SIGN")).getOrElse("true")) {
    "true" -> true
    "false" -> false
    else -> throw GradleException("proseplow.sign is \"true\" (the default) or \"false\", not \"$value\".")
}
androidComponents {
    val home = System.getProperty("user.home")
    onVariants(selector().withBuildType("release")) { variant ->
        if (!signRelease) return@onVariants
        val sign = tasks.register<SignApk>("sign${variant.name.replaceFirstChar(Char::uppercaseChar)}Apk") {
            group = "build"
            description = "Signs the ${variant.name} APK with the builder's own key (tools/signing-key.sh makes it)."
            val keystore = providers.gradleProperty("proseplow.keystore")
                .orElse(providers.environmentVariable("PROSEPLOW_KEYSTORE"))
                .orElse(File(home, ".config/proseplow/proseplow-release.p12").path)
            keystorePath.set(keystore)
            keystoreFile.from(keystore.map { path -> listOf(File(path)).filter { it.isFile } })
            keyAlias.set(providers.gradleProperty("proseplow.keyAlias").orElse(providers.environmentVariable("PROSEPLOW_KEY_ALIAS")).orElse("proseplow"))
            buildTools.set(sdkComponents.sdkDirectory.map { it.dir("build-tools/${android.buildToolsVersion}") })
            keyringTool.set(rootProject.layout.projectDirectory.file("tools/keyring.py"))
        }
        val request = variant.artifacts.use(sign)
            .wiredWithDirectories(SignApk::unsigned, SignApk::signed)
            .toTransformMany(SingleArtifact.APK)
        sign.configure { transformationRequest.set(request) }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":llama"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    // No Compose tooling, in any build type: nothing here is ever previewed in an IDE, and the
    // tooling library adds an exported activity (PreviewActivity) to the build it is in.

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.markdown.renderer.m3)

    // Fingerprint (strong biometric) unlock through BiometricPrompt with a CryptoObject.
    implementation(libs.androidx.biometric)
    constraints {
        // biometric 1.1.0 (the latest stable release) depends on fragment 1.2.5, whose
        // FragmentActivity predates the Activity Result API of the androidx.activity that Compose
        // brings in (lint: InvalidFragmentVersionForActivityResult once registerForActivityResult
        // is used). This only raises that version; it adds no library.
        implementation(libs.androidx.fragment) {
            because("FragmentActivity must support registerForActivityResult")
        }
    }

    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // The instrumented tests: the runner, its JUnit 4 integration, and the application context.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
}

// Two of the unit tests read files of the repository that are not on their classpath: the list of
// features, which is held to the app's wording, and the models' manifest, which the catalogue is
// held to. They are declared here, so that a change to either of them runs the tests again
// instead of leaving the result of an earlier run in place.
tasks.withType<Test>().configureEach {
    inputs.files(rootProject.file("FEATURES.md"), rootProject.file("models/manifest.json"))
        .withPropertyName("repositoryFilesTheUnitTestsRead")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// The checks on every APK and bundle this module writes that the native code depends on: each
// model in it is, byte for byte, one that models/manifest.json pins, and is stored on a 16 KiB
// boundary; the native libraries are built for 16 KB pages. They live with the module they protect.
// Named through file() for the reason given where llama/build.gradle.kts applies the same script.
apply(from = file("../llama/apk-checks.gradle.kts"))
