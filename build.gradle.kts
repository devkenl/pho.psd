// Top-level build file. Module configuration lives in each module's build.gradle.kts.
// AGP 9 ships built-in Kotlin support, so the app module does not apply
// org.jetbrains.kotlin.android. Declaring the Kotlin JVM plugin here pins the
// Kotlin Gradle Plugin version used by both the JVM engine module and AGP's
// built-in Kotlin compilation.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
