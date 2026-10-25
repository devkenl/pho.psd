import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin editor state machine: commands, tools, availability, undo-aware editing,
// viewport control. No Android dependency so it is unit-testable on the JVM; the Android
// app wraps it in a ViewModel and adds decoding/rendering/bitmaps.
plugins {
    alias(libs.plugins.kotlin.jvm)
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
    api(project(":core:engine"))
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
