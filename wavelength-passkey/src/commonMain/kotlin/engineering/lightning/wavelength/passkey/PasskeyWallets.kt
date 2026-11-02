package engineering.lightning.wavelength.passkey

import engineering.lightning.wavelength.WavelengthClient
import engineering.lightning.wavelength.model.OpenWalletResult

/** The outcome of opening a passkey wallet. */
public data class PasskeyWallet(
    val result: OpenWalletResult,
    /** Store this to scope future unlocks to the same passkey. */
    val credentialId: String,
)

/**
 * Registers a new passkey and creates the wallet it derives. The runtime must
 * be started at this wallet's data directory first. The result's mnemonic is
 * the wallet's backup, shown once.
 */
public suspend fun WavelengthClient.createPasskeyWallet(ceremony: PasskeyCeremony, appName: String): PasskeyWallet {
    val assertion = ceremony.register(appName)
    return open(assertion)
}

/**
 * Asserts an existing passkey (optionally a specific one) and imports or
 * unlocks the wallet it derives.
 */
public suspend fun WavelengthClient.openPasskeyWallet(ceremony: PasskeyCeremony, credentialId: String? = null): PasskeyWallet {
    val assertion = ceremony.assert(credentialId)
    return open(assertion)
}

private suspend fun WavelengthClient.open(assertion: PasskeyAssertion): PasskeyWallet {
    try {
        return PasskeyWallet(openWalletFromPasskey(assertion.prfOutput), assertion.credentialId)
    } finally {
        assertion.prfOutput.fill(0)
    }
}
