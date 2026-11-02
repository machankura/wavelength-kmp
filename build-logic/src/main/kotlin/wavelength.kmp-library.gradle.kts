// A wavelength-kmp module with no native dependency: the mobile targets from
// wavelength.kmp-base plus the plain JVM, where its tests run fastest.

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("wavelength.kmp-base")
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }
}
