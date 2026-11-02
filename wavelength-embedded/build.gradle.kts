plugins {
    id("wavelength.kmp-base")
    id("wavelength.publishing")
}

description = "Runs the wavelength daemon in-process through its gomobile bindings (Android .aar, iOS .xcframework)."

// The bindings are versioned by the daemon tag alone (ADR 0005).
val bindingsVersion = rootProject.file("wavelength.version").readText().trim().removePrefix("v")

kotlin {
    androidLibrary {
        withDeviceTestBuilder {}.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.wavelengthCore)
        }
        androidMain.dependencies {
            implementation("engineering.lightning.wavelength:wavelength-bindings-android:$bindingsVersion")
            implementation(libs.androidx.lifecycle.process)
        }
        getByName("androidHostTest").dependencies {
            implementation(projects.wavelengthTesting)
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.ext.junit)
        }
    }
}

// iOS: cinterop over the staged Wavewalletdk.xcframework. Apple cinterop needs
// Xcode, so the iOS targets exist only on macOS hosts (ADR 0002, 0004).
apply(from = "ios.gradle.kts")
