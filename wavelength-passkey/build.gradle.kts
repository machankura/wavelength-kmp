plugins {
    id("wavelength.kmp-library")
    id("wavelength.serialization")
    id("wavelength.publishing")
}

description = "Passkey (WebAuthn PRF) wallets for wavelength-kmp: Credential Manager on Android, AuthenticationServices on iOS."

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.wavelengthCore)
        }
        androidMain.dependencies {
            implementation(libs.androidx.credentials)
            implementation(libs.androidx.credentials.play.services)
            implementation(libs.play.services.base)
        }
        commonTest.dependencies {
            implementation(projects.wavelengthTesting)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
