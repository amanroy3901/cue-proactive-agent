plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)

}
android {
    namespace = "com.cue.triggers"
    compileSdk = 36
    defaultConfig {
        minSdk = 31
        consumerProguardFiles()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)

    }
}
dependencies {
    api(project(":agent"))
    implementation(libs.core.ktx)
    implementation(libs.play.location)
    implementation(libs.work.runtime)
    implementation(libs.coroutines.android)
}
