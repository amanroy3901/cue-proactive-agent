plugins {
    alias(libs.plugins.kotlin.jvm)
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    api(project(":domain"))
    api(project(":brain:api"))
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
