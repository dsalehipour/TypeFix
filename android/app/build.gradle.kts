import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Bundled KLIPY GIF key. Read from local.properties (kept out of git) on a dev
// machine, or from the KLIPY_API_KEY env var in CI (set from a repo secret). The
// built APK embeds it so GIF search works without the user pasting a key.
val klipyApiKey: String = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}.getProperty("KLIPY_API_KEY") ?: System.getenv("KLIPY_API_KEY") ?: ""

// Release signing, read from keystore.properties (kept out of git). Present on
// the machine that publishes releases; absent elsewhere (debug builds still
// work). Every release MUST be signed with this same key or the in-app updater
// can't install over an existing install.
val keystoreProps: Properties? = rootProject.file("keystore.properties").takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

android {
    namespace = "com.typefix.keyboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.typefix.keyboard"
        minSdk = 28
        targetSdk = 35
        versionCode = 32
        versionName = "0.1.31"
        buildConfigField("String", "KLIPY_API_KEY", "\"$klipyApiKey\"")
        // Where the in-app updater looks for new releases.
        buildConfigField("String", "GITHUB_OWNER", "\"dsalehipour\"")
        buildConfigField("String", "GITHUB_REPO", "\"typefix\"")
        // LiteRT-LM ships large native libs; arm64 covers all modern phones (and
        // the Apple-silicon arm64 emulator), keeping the APK from ballooning.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Sign with the stable release key when keystore.properties is present
            // (the release-publishing machine); otherwise fall back so debug-style
            // builds still succeed elsewhere.
            signingConfig = signingConfigs.findByName("release")
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

// Keep the correction prompt (CorrectionText.kt) in sync with the shared
// prompt/system-prompt.txt before every build, so the Android and macOS prompts
// can never drift. No-op when already up to date; skipped if python3 or the
// script is unavailable (e.g. CI images without Python).
val syncPrompt by tasks.registering(Exec::class) {
    val script = rootProject.file("../scripts/sync_prompt.py")
    val python = listOf("python3", "python").firstOrNull { exe ->
        runCatching {
            ProcessBuilder(exe, "--version").redirectErrorStream(true).start().waitFor() == 0
        }.getOrDefault(false)
    }
    onlyIf { script.exists() && python != null }
    workingDir = rootProject.file("..")
    isIgnoreExitValue = true
    commandLine(python ?: "python3", script.absolutePath)
}

tasks.named("preBuild") {
    dependsOn(syncPrompt)
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
    implementation(libs.androidx.work.runtime)

    // On-device LLM via Google's LiteRT-LM. Unlike the MediaPipe `.task` API,
    // this runs the official `.litertlm` builds (with their HuggingFace
    // tokenizers), so the same Qwen3 family as the macOS app works on Android.
    implementation(libs.litertlm.android)

    debugImplementation(libs.androidx.ui.tooling)
}
