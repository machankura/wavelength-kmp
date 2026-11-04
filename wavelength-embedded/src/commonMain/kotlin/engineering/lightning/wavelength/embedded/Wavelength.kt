package engineering.lightning.wavelength.embedded

import engineering.lightning.wavelength.EmbeddedWavelengthClient
import engineering.lightning.wavelength.FacadeBindings
import engineering.lightning.wavelength.FacadeWavelengthClient

/**
 * Entry point to the embedded wavelength runtime.
 *
 * ```kotlin
 * val client = Wavelength.client
 * client.start(RuntimeConfig.preset(Network.SIGNET, dataDir = Wavelength.defaultDataDir(context)))
 * ```
 */
public object Wavelength {
    /** The raw gomobile facade for this platform. */
    public val bindings: FacadeBindings get() = platformBindings

    /**
     * The process-wide client. The daemon is a per-process singleton, so
     * every caller must share this instance (its lifecycle lock is what keeps
     * start and stop calls from racing).
     */
    public val client: EmbeddedWavelengthClient by lazy { FacadeWavelengthClient(platformBindings) }
}

internal expect val platformBindings: FacadeBindings
