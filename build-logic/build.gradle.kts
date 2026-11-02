plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.gradlePlugin.kotlin)
    implementation(libs.gradlePlugin.kotlin.serialization)
    implementation(libs.gradlePlugin.android)
    implementation(libs.gradlePlugin.mavenPublish)
}
