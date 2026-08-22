plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.kgs.notes.editor"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    buildFeatures.compose = true
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.webkit)

    testImplementation(libs.junit4)
    debugImplementation(libs.compose.ui.tooling)
}
