import org.gradle.api.publish.maven.MavenPom

/** The POM metadata shared by every wavelength-kmp artifact. */
fun MavenPom.wavelengthPom(artifactName: String, artifactDescription: String) {
    name.set(artifactName)
    description.set(artifactDescription)
    inceptionYear.set("2026")
    url.set("https://github.com/lightninglabs/wavelength-kmp")
    licenses {
        license {
            name.set("The Apache License, Version 2.0")
            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            distribution.set("repo")
        }
    }
    developers {
        developer {
            id.set("lightninglabs")
            name.set("Lightning Labs")
            url.set("https://lightning.engineering")
        }
    }
    scm {
        url.set("https://github.com/lightninglabs/wavelength-kmp")
        connection.set("scm:git:https://github.com/lightninglabs/wavelength-kmp.git")
        developerConnection.set("scm:git:ssh://git@github.com/lightninglabs/wavelength-kmp.git")
    }
}
