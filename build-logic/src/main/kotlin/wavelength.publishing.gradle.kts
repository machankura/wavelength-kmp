// Maven Central publishing for wavelength-kmp artifacts. Signing is enabled
// only when a key is configured, so publishToMavenLocal works on dev machines.

plugins {
    id("com.vanniktech.maven.publish")
}

mavenPublishing {
    publishToMavenCentral()

    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    coordinates(project.group.toString(), project.name, project.version.toString())

    pom {
        wavelengthPom(project.name, project.description ?: "wavelength-kmp ${project.name}")
    }
}
