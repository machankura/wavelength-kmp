plugins {
    id("wavelength.kmp-library")
    id("wavelength.publishing")
}

description = "WalletEngine: an observable wavelength wallet state machine for UIs."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.wavelengthCore)
            implementation(libs.atomicfu)
        }
        commonTest.dependencies {
            implementation(projects.wavelengthTesting)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
