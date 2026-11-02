// Plugins live on the build-logic classpath; modules apply the wavelength.*
// convention plugins. Only plugins not provided by build-logic are declared here.
plugins {
    alias(libs.plugins.skie) apply false
}
