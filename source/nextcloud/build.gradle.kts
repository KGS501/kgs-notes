plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":notes-engine"))
    implementation(libs.coroutines.core)
    testImplementation(kotlin("test-junit5"))
}

tasks.test {
    useJUnitPlatform()
}
