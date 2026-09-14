plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.aimediaeditor.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.aimediaeditor.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-mvp"
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.coil.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui.compose)
    implementation(libs.media3.ui.compose.material3)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.work.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)
    // EncryptedSharedPreferences -- the README's own stated plan for the API key is
    // "a securely-stored key (never bundled into the APK)"; this is the standard,
    // Google-recommended way to do that on Android rather than hand-rolling AES/
    // Keystore wiring, which is a much worse risk to get subtly wrong than adding
    // one well-known first-party library.
    implementation(libs.androidx.security.crypto)

    debugImplementation(libs.androidx.ui.tooling)

    // Local JVM unit tests (app/src/test) -- editor/model/ is deliberately Android-free
    // (see ProjectState.kt's own doc comment) specifically so it's unit-testable without
    // an emulator; this dependency is what actually lets that promise be exercised via
    // `./gradlew test`, which nothing in the build did before this round.
    testImplementation(libs.junit)
}
