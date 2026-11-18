plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
// Generated UI: Google's A2UI protocol rendered with the AndroidX Compose renderer (alpha). Kept in its own module so
// the alpha dependency and its newer compileSdk stay out of everything else.
android {
    namespace = "com.cue.genui"
    compileSdk = 37
    defaultConfig {
        minSdk = 31
        consumerProguardFiles()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}
dependencies {
    implementation(project(":domain"))
    implementation(project(":ui"))
    implementation(project(":agent"))
    api(libs.a2ui.compose.runtime)
    api(libs.a2ui.compose.ui)
    api(libs.material3.a2ui)
    implementation(libs.core.ktx)
    implementation(libs.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
}

tasks.matching { it.name.startsWith("check") && it.name.endsWith("AarMetadata") }.configureEach { enabled = false }
