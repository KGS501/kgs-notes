plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        // Android's minimum SDK provides Java 8 APIs, not the build JDK's APIs.
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
    implementation(libs.coroutines.core)
    testImplementation(kotlin("test-junit5"))
}

tasks.test {
    useJUnitPlatform()
}
