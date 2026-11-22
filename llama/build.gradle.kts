import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The Android library around the native code. It implements the native seam that :core defines
// and is the only module that knows about JNI, llama.cpp and the bundled model files.
plugins {
    // AGP 9 has built-in Kotlin support; org.jetbrains.kotlin.android must not be applied.
    alias(libs.plugins.android.library)
}

android {
    namespace = "us.theworkmans.proseplow.llama"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        minSdk = 28
        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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

dependencies {
    api(project(":core"))
}
