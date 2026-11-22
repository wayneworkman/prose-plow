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
include(":app")
