import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM image engine. Deliberately has NO Android or Compose dependency so
// that document model, coordinate maths, compositing and history are unit-testable on
// any JVM and reusable by a future native/GPU backend.
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
    testImplementation(libs.junit)
}
