// Maven Central publishing for a prebuilt binary (no Kotlin/Java sources): the
// module declares its MavenPublication; this adds the POM, signing, and the
// empty sources/javadoc jars Central requires.

plugins {
    id("com.vanniktech.maven.publish.base")
}

val emptySourcesJar by tasks.registering(Jar::class) {
    archiveClassifier.set("sources")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    from(rootProject.file("README.md"))
}

val emptyJavadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    from(rootProject.file("README.md"))
}

mavenPublishing {
    publishToMavenCentral()

    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    pom {
        wavelengthPom(project.name, project.description ?: "wavelength-kmp ${project.name}")
    }
}

afterEvaluate {
    extensions.configure<PublishingExtension> {
        publications.withType<MavenPublication>().configureEach {
            artifact(emptySourcesJar)
            artifact(emptyJavadocJar)
        }
    }
}
