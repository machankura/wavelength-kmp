// Republishes the upstream gomobile Wavewalletdk.aar, unchanged, under Maven
// coordinates (ADR 0005). Stage it first with scripts/fetch-bindings.sh, which
// checks it against bindings.digests.

plugins {
    id("wavelength.publishing-binary")
}

description = "The wavelength gomobile bindings (Wavewalletdk.aar) for Android, as published by lightninglabs/wavelength."

val bindingsVersion = rootProject.file("wavelength.version").readText().trim().removePrefix("v")
val aar = rootProject.file("bindings/android/Wavewalletdk.aar")

group = "engineering.lightning.wavelength"
version = bindingsVersion

publishing {
    publications {
        create<MavenPublication>("bindings") {
            artifactId = "wavelength-bindings-android"
            artifact(aar) { extension = "aar" }
            pom { packaging = "aar" }
        }
    }
}

tasks.withType<AbstractPublishToMaven>().configureEach {
    val staged = aar // a local, so the action does not capture the build script
    doFirst {
        check(staged.isFile) { "missing ${staged.path}; run scripts/fetch-bindings.sh android" }
    }
}
