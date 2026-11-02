package engineering.lightning.wavelength

import engineering.lightning.wavelength.profiles.InMemoryWalletProfileStore
import engineering.lightning.wavelength.profiles.WalletKind
import engineering.lightning.wavelength.profiles.WalletProfiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class WalletProfilesTest {
    @Test
    fun createListUpdateRemoveAndPersist() = runTest {
        val store = InMemoryWalletProfileStore()
        val profiles = WalletProfiles(store, "/data/wavelength/")
        val a = profiles.create("Spending", Network.SIGNET, WalletKind.PASSKEY, credentialId = "cred")
        val b = profiles.create("Savings", Network.SIGNET, WalletKind.PASSWORD)
        assertNotEquals(a.id, b.id)
        assertEquals("/data/wavelength/wallets/${a.id}", a.dataDir)

        profiles.update(a.copy(identityPubKey = "02ab"))
        // A second registry over the same store sees the persisted state.
        val reloaded = WalletProfiles(store, "/data/wavelength")
        assertEquals("02ab", reloaded.get(a.id)?.identityPubKey)
        assertEquals(listOf("Spending", "Savings"), reloaded.list().map { it.name })

        reloaded.remove(b.id)
        assertEquals(listOf(a.id), profiles.list().map { it.id })

        val config = a.config(RuntimeConfig.preset(Network.SIGNET))
        assertEquals(a.dataDir, config.dataDir)
        assertTrue(config.arkServerAddress!!.isNotEmpty())
    }

    @Test
    fun relativeRootIsRejected() {
        assertFailsWith<WavelengthException.InvalidArgument> { WalletProfiles(InMemoryWalletProfileStore(), "wavelength") }
    }
}
