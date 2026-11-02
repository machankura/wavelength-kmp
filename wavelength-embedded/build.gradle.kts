import org.jetbrains.kotlin.konan.target.HostManager

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
            implementation(libs.androidx.core.ktx)
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
if (HostManager.hostIsMac) {
    val xcframework = rootProject.file("bindings/ios/Wavewalletdk.xcframework")

    kotlin {
        listOf(
            iosArm64() to "ios-arm64",
            iosSimulatorArm64() to "ios-arm64_x86_64-simulator",
        ).forEach { (target, slice) ->
            val sliceDir = xcframework.resolve(slice).absolutePath
            target.compilations.getByName("main").cinterops.create("wavewalletdk") {
                definitionFile.set(file("src/nativeInterop/cinterop/wavewalletdk.def"))
                compilerOpts("-F$sliceDir")
            }
            // Test executables link the static Go framework directly; app
            // builds get it from the app's own Wavewalletdk.xcframework
            // dependency (ADR 0004).
            target.binaries.all {
                linkerOpts("-F$sliceDir", "-framework", "Wavewalletdk", "-lresolv")
            }
        }

        sourceSets {
            iosTest.dependencies {
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.CInteropProcess>().configureEach {
        val framework = xcframework
        doFirst {
            check(framework.isDirectory) { "missing ${framework.path}; run scripts/fetch-bindings.sh ios" }
        }
    }
}
