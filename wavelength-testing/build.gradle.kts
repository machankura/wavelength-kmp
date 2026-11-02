plugins {
    id("wavelength.kmp-library")
    id("wavelength.serialization")
    id("wavelength.publishing")
}

description = "Test doubles for wavelength-kmp: a simulated wallet behind FacadeBindings."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.wavelengthCore)
            implementation(libs.atomicfu)
        }
    }
}
