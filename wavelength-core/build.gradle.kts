plugins {
    id("wavelength.kmp-library")
    id("wavelength.publishing")
}

description = "Wavelength Kotlin Multiplatform SDK: client contract, models, config, and errors."

// Generates RuntimePin.kt so the code knows which daemon tag it was built against.
val generateRuntimePin by tasks.registering {
    val pinFile = rootProject.file("wavelength.version")
    val outDir = layout.buildDirectory.dir("generated/runtimePin/commonMain/kotlin")
    inputs.file(pinFile)
    outputs.dir(outDir)
    doLast {
        val tag = pinFile.readText().trim()
        val file = outDir.get().file("engineering/lightning/wavelength/RuntimePin.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |// Code generated from wavelength.version. DO NOT EDIT.
            |package engineering.lightning.wavelength
            |
            |/** The wavelength daemon release this SDK build is paired with. */
            |public const val WAVELENGTH_RUNTIME_VERSION: String = "$tag"
            |""".trimMargin(),
        )
    }
}

kotlin {
    sourceSets {
        commonMain {
            kotlin.srcDir(generateRuntimePin)
        }
    }
}
