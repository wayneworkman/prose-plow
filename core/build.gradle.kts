import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Plain Kotlin for the JVM, deliberately without the Android plugin: nothing in this module may
// depend on Android, so all of it is unit-tested on the build machine without a phone.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    // Publishes src/testFixtures (FakeNativeLlama, FakeInferenceEngine, FakeClock) to the tests of
    // other modules: testImplementation(testFixtures(project(":core"))).
    `java-test-fixtures`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // api, not implementation: Flow and StateFlow are part of InferenceEngine's signature.
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testFixturesApi(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
