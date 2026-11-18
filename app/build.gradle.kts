import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.cue.app"
    // 37: the A2UI renderer (alpha) and the Compose alphas it pulls in are built against Android 17.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.cue.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-hackathon"
        ndk { abiFilters += setOf("arm64-v8a") }
        // The Gemini key comes from local.properties (not committed): GEMINI_API_KEY=...
        val local = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
        buildConfigField("String", "GEMINI_API_KEY", "\"${local.getProperty("GEMINI_API_KEY", "")}\"")
    }

    buildTypes {
        debug { isDebuggable = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true; buildConfig = true }

    packaging {
        jniLibs { useLegacyPackaging = true }
        resources { excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/*.kotlin_module") }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":brain:api"))
    implementation(project(":brain:rules"))
    implementation(project(":brain:ondevice"))
    implementation(project(":brain:cloud"))
    implementation(project(":agent"))
    implementation(project(":memory"))
    implementation(project(":triggers"))
    implementation(project(":ui"))
    implementation(project(":call"))
    implementation(project(":ingest"))
    implementation(project(":surfaces"))
    implementation(project(":genui"))

    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.work.runtime)
    implementation(libs.play.location)
    implementation(libs.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
}

// The A2UI alphas ask for compileSdk 37.1; 37.0 is what is installed and compiles them fine. Skip the metadata gate.
tasks.matching { it.name.startsWith("check") && it.name.endsWith("AarMetadata") }.configureEach { enabled = false }
