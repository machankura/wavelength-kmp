// A wavelength-kmp module with no native dependency: Android (from
// wavelength.kmp-base), iOS, and the plain JVM, where its tests run fastest.

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("wavelength.kmp-base")
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }
}
