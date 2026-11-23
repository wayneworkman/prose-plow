import com.android.build.api.artifact.SingleArtifact
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
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
 * The app's promise is that it never uses the network. Its own manifest asks for no permission at
 * all, but every dependency's manifest is merged into it and can add one unnoticed, so it is the
 * merged result, the manifest that is packaged, that is checked.
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

androidComponents {
    // Every permission a merged manifest may contain. The app itself declares the four that let an
    // answer be finished while it is not on screen: the foreground service and its type, the
    // notifications, and the wake lock. USE_BIOMETRIC and USE_FINGERPRINT are merged in by
    // androidx.biometric. androidx.core declares and requests the app's own signature-level
    // DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION, added per variant below because it carries the
    // application id. Anything else, android.permission.INTERNET above all, fails the build.
    val allowedPermissions = setOf(
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.WAKE_LOCK",
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

// The checks on every APK and bundle this module writes that the native code depends on: each
// model in it is, byte for byte, one that models/manifest.json pins, and is stored on a 16 KiB
// boundary; the native libraries are built for 16 KB pages. They live with the module they protect.
// Named through file() for the reason given where llama/build.gradle.kts applies the same script.
apply(from = file("../llama/apk-checks.gradle.kts"))
