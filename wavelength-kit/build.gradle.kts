import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

// WavelengthKit: the static iOS framework apps consume (through the Swift
// package). It exports the whole public API, with SKIE generating Swift
// async/AsyncSequence/enum bridges. Apps also link the gomobile
// Wavewalletdk.xcframework (ADR 0004).

plugins {
    id("wavelength.ios-framework")
    alias(libs.plugins.skie)
}

val wavelengthKit = XCFramework("WavelengthKit")

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "WavelengthKit"
            isStatic = true
            export(projects.wavelengthCore)
            export(projects.wavelengthEngine)
            export(projects.wavelengthEmbedded)
            export(projects.wavelengthPasskey)
            wavelengthKit.add(this)
        }
    }

    sourceSets {
        iosMain.dependencies {
            api(projects.wavelengthCore)
            api(projects.wavelengthEngine)
            api(projects.wavelengthEmbedded)
            api(projects.wavelengthPasskey)
        }
    }
}
