plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.coroutines.core)
    testImplementation(kotlin("test-junit5"))
}

tasks.test {
    useJUnitPlatform()
}
