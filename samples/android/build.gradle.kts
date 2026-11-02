plugins {
    id("wavelength.android-app")
}

android {
    namespace = "engineering.lightning.wavelength.sample"
    defaultConfig {
        applicationId = "engineering.lightning.wavelength.sample"
        versionCode = 1
        versionName = "1.0"
    }
}

dependencies {
    implementation(projects.wavelengthEmbedded)
    implementation(projects.wavelengthEngine)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
