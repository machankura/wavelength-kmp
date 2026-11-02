package engineering.lightning.wavelength.embedded

import engineering.lightning.wavelength.profiles.WalletProfileStore
import platform.Foundation.NSUserDefaults

/** Stores wallet profiles in NSUserDefaults. */
public class UserDefaultsProfileStore(
    private val key: String = "wavelength-wallet-profiles",
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : WalletProfileStore {
    override suspend fun read(): String? = defaults.stringForKey(key)

    override suspend fun write(value: String) {
        defaults.setObject(value, forKey = key)
    }
}
