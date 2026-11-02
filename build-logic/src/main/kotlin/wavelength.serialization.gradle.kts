// kotlinx.serialization for modules that define wire models.

import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    id("org.jetbrains.kotlin.plugin.serialization")
}

extensions.configure<KotlinMultiplatformExtension> {
    compilerOptions.optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
}
