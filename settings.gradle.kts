pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        // The pinned gomobile .aar, installed by scripts/fetch-bindings.sh.
        exclusiveContent {
            forRepository {
                maven(rootDir.resolve("bindings/maven")) { name = "wavelengthBindings" }
            }
            filter { includeModule("engineering.lightning.wavelength", "wavelength-bindings-android") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "wavelength-kmp"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":wavelength-core")
include(":wavelength-testing")
include(":wavelength-engine")
include(":wavelength-passkey")
include(":wavelength-embedded")
include(":wavelength-bindings-android")
include(":samples:android")

// The iOS umbrella framework (WavelengthKit) needs Xcode, so it is part of the
// build only on macOS (ADR 0004).
if (System.getProperty("os.name").startsWith("Mac")) {
    include(":wavelength-kit")
}
