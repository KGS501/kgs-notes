plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        // Keep the JVM module's platform calls compatible with Android's minimum SDK.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
        freeCompilerArgs.add("-Xjdk-release=8")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
}

dependencies {
    implementation(project(":notes-engine"))
    implementation(libs.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
}

tasks.test {
    useJUnitPlatform()
}
