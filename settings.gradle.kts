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
include(":wavelength-embedded")
include(":wavelength-bindings-android")
