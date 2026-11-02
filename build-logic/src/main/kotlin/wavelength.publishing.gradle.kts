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
        name = project.name
        description = providers.provider { project.description ?: "wavelength-kmp ${project.name}" }
        inceptionYear = "2026"
        url = "https://github.com/lightninglabs/wavelength-kmp"
        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "lightninglabs"
                name = "Lightning Labs"
                url = "https://lightning.engineering"
            }
        }
        scm {
            url = "https://github.com/lightninglabs/wavelength-kmp"
            connection = "scm:git:https://github.com/lightninglabs/wavelength-kmp.git"
            developerConnection = "scm:git:ssh://git@github.com/lightninglabs/wavelength-kmp.git"
        }
    }
}
