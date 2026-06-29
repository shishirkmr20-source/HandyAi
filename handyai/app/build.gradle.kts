plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.handyai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.handyai"
        minSdk = 26
        targetSdk = 35
        versionCode = 25
        versionName = "1.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        ndk {
            // MediaPipe 0.10.35 ships ~30MB of native libs per ABI.
            // Including all 4 ABIs (arm64-v8a, armeabi-v7a, x86_64, x86)
            // bloats the APK to 130+ MB. We restrict to the two ARM ABIs
            // that cover ~99.9% of real Android devices. x86 / x86_64
            // are emulator-only and not worth the size cost.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Sign with debug keystore for sideload distribution
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/INDEX.LIST"
        }
    }
}

dependencies {
    // AndroidX core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Room database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // MediaPipe LLM Inference API — 0.10.22+ required for litert-community
    // multi-prefill-seq (ekv1280) model format. 0.10.21 throws:
    //   "RET_CHECK failure (prefill_input_names.size() % 2)==(0)"
    // when loading _multi-prefill-seq_ models (Qwen2.5, SmolLM, Phi-4).
    // 0.10.35 is the latest stable as of 2026-06.
    //
    // NOTE: `tasks-vision` was previously added here for the Image Generator
    // API (Stable Diffusion 1.5). That class is NOT shipped in the public
    // Maven AAR — it's experimental and excluded from the released artifact.
    // Importing it broke the release build with "Unresolved reference
    // 'ImageGenerator'". The dependency has been removed; ImageGenEngine is
    // now a stub. See ImageGenEngine.kt for the restoration plan.
    implementation("com.google.mediapipe:tasks-genai:0.10.35")

    // File parsing
    implementation("com.tom-roush:pdfbox-android:2.0.27.0") // PDF (correct groupId has hyphen, not underscore)
    implementation("org.apache.poi:poi-ooxml:5.3.0")         // DOCX, XLSX, PPTX
    implementation("org.apache.poi:poi-scratchpad:5.3.0")    // DOC, XLS, PPT (legacy)

    // Networking (web search)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")

    // ML Kit — on-device OCR (text recognition) + image labeling
    // Bundled model variants (~10MB added to APK) work fully offline.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:image-labeling:17.0.7")

    // Icons
    implementation("androidx.compose.material:material-icons-extended")

    // Debugging
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
