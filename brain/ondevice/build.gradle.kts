plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)

}
android {
    namespace = "com.cue.brain.ondevice"
    compileSdk = 36
    defaultConfig {
        minSdk = 31
        consumerProguardFiles()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    defaultConfig { ndk { abiFilters += setOf("arm64-v8a") } }
    androidResources { noCompress += listOf("json", "bin") }
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}
dependencies {
    api(project(":brain:api"))
    implementation(libs.core.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.litert)
    implementation(libs.litert.gpu)
}
