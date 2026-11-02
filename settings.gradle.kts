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
        google()
        mavenCentral()
    }
}

rootProject.name = "wavelength-kmp"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":wavelength-core")
include(":wavelength-testing")
