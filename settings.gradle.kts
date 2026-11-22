pluginManagement {
    repositories {
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "prose-plow"
// :core is plain Kotlin on the JVM with no Android dependency: the chat model, the prompt
// formats and the inference engine's interface. :app is the Android application around it.
include(":core")
// :llama is the Android library around the native code: the JNI binding of the engine's native
// seam, llama.cpp itself, and the bundled model files.
include(":llama")
include(":app")
