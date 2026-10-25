plugins {
    alias(libs.plugins.android.application)
    // AGP 9 compiles Kotlin itself (built-in Kotlin); only the Compose compiler plugin is applied.
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.phopsd"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.phopsd"
        // minSdk 29 (Android 10): ImageDecoder (API 28+) with automatic EXIF orientation,
        // scoped storage / SAF semantics, and modern stylus MotionEvent data. Covers the
        // tablets this professional editor targets.
        minSdk = 29
        targetSdk = 37
        versionCode = 4
        versionName = "0.4.0-dev"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:editor"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
