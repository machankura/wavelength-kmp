// Shared Kotlin Multiplatform setup for every wavelength-kmp module: the Android
// target, explicit API mode, and a namespace derived from the module name.
// Pure-Kotlin modules apply wavelength.kmp-library (adds iOS and the JVM);
// wavelength-embedded adds its iOS targets itself, with cinterop.

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

group = "engineering.lightning.wavelength"
version = wavelengthVersion()

kotlin {
    explicitApi()

    androidLibrary {
        namespace = "engineering.lightning.wavelength." +
            project.name.removePrefix("wavelength-").replace('-', '.')
        compileSdk = libs.findVersion("android-compileSdk").get().requiredVersion.toInt()
        minSdk = libs.findVersion("android-minSdk").get().requiredVersion.toInt()

        withHostTestBuilder {}

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
        optIn.add("kotlin.time.ExperimentalTime")
    }

    sourceSets {
        commonTest.dependencies {
            implementation(libs.findLibrary("kotlin-test").get())
        }
    }
}

// The library version tracks the pinned wavelength daemon tag (see
// wavelength.version and docs/adr/0003-versioning-and-pinning.md). Builds are
// snapshots unless -Prelease=true is passed by the release workflow.
fun wavelengthVersion(): String {
    val tag = rootProject.file("wavelength.version").readText().trim().removePrefix("v")
    val release = providers.gradleProperty("release").orNull == "true"
    return if (release) tag else "$tag-SNAPSHOT"
}
