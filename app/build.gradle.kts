plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.cue.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cue.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-hackathon"
        ndk { abiFilters += setOf("arm64-v8a") }
    }

    buildTypes {
        debug { isDebuggable = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

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
