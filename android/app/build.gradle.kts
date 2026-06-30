import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Bundled KLIPY GIF key, read from local.properties (kept out of git). The
// built APK embeds it so GIF search works without the user pasting a key.
val klipyApiKey: String = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}.getProperty("KLIPY_API_KEY", "")

android {
    namespace = "com.typefix.keyboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.typefix.keyboard"
        minSdk = 28
        targetSdk = 35
        versionCode = 13
        versionName = "0.1.12"
        buildConfigField("String", "KLIPY_API_KEY", "\"$klipyApiKey\"")
        // LiteRT-LM ships large native libs; arm64 covers all modern phones (and
        // the Apple-silicon arm64 emulator), keeping the APK from ballooning.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.google.material)
    implementation(libs.okhttp)
    implementation(libs.coil)
    implementation(libs.coil.gif)

    // On-device LLM via Google's LiteRT-LM. Unlike the MediaPipe `.task` API,
    // this runs the official `.litertlm` builds (with their HuggingFace
    // tokenizers), so the same Qwen3 family as the macOS app works on Android.
    implementation(libs.litertlm.android)

    debugImplementation(libs.androidx.ui.tooling)
}
