plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
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

ksp {
    // Room's own recommended setup -- exports the DB schema per version to a
    // checked-in-able directory, which is what a future migration would diff
    // against. No migrations exist yet (schema version 1), but this costs nothing
    // to set up now versus retrofitting it once a real migration is needed.
    arg("room.schemaLocation", "$projectDir/schemas")
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
    // EncryptedSharedPreferences -- used by ModelAccessTokenStore for the Hugging Face
    // token that authenticates the ONE-TIME on-device model download (see
    // LocalLlmModelManager). The standard, Google-recommended way to store a secret on
    // Android rather than hand-rolling AES/Keystore wiring, which is a much worse risk
    // to get subtly wrong than adding one well-known first-party library.
    implementation(libs.androidx.security.crypto)

    // Media index (spec sections 5/6/20): a local, on-device database of what's IN the
    // user's media, built once and queried for natural-language search -- explicitly
    // named in spec section 16's suggested architecture, not a new choice.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // EXIF GPS extraction from photos (ACCESS_MEDIA_LOCATION-gated, see AndroidManifest).
    implementation(libs.androidx.exifinterface)
    // On-device (Level 1, spec section 20) object/scene labeling and face detection --
    // no network call, no cloud upload, matching spec section 15's privacy principle.
    implementation(libs.mlkit.image.labeling)
    implementation(libs.mlkit.face.detection)
    // The on-device LLM runtime (Gemma, via LocalLlmEngine) that both AI features --
    // edit-command prompts and AI library search -- run on. Matches spec section 15's
    // privacy principle even more directly than ML Kit above: the whole AI layer was
    // originally built against Anthropic's cloud API (still in this project's git
    // history), then moved fully on-device this round specifically so no project data,
    // prompt, or search query is ever sent off the device -- see the README.
    implementation(libs.mediapipe.tasks.genai)

    debugImplementation(libs.androidx.ui.tooling)

    // Local JVM unit tests (app/src/test) -- editor/model/ is deliberately Android-free
    // (see ProjectState.kt's own doc comment) specifically so it's unit-testable without
    // an emulator; this dependency is what actually lets that promise be exercised via
    // `./gradlew test`, which nothing in the build did before this round.
    testImplementation(libs.junit)
}
