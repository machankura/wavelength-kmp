package engineering.lightning.wavelength.embedded

import engineering.lightning.wavelength.CreateWalletRequest
import engineering.lightning.wavelength.DescriptorWalletRequest
import engineering.lightning.wavelength.ListWalletDescriptorsRequest
import engineering.lightning.wavelength.Network
import engineering.lightning.wavelength.RuntimeConfig
import engineering.lightning.wavelength.WavelengthException
import engineering.lightning.wavelength.walletBirthday
import engineering.lightning.wavelength.model.WalletState
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/**
 * The iOS twin of the Android device test: runs the real gomobile runtime in
 * the simulator test process. Needs network access to the signet endpoints.
 */
class IosEmbeddedRuntimeTest {
    private val client = Wavelength.client
    private val dataDir = NSTemporaryDirectory() + "wavelength-test-" + NSUUID().UUIDString
    private val config = RuntimeConfig.preset(Network.SIGNET, dataDir = dataDir)

    @AfterTest
    fun tearDown() = runBlocking {
        client.stop()
        NSFileManager.defaultManager.removeItemAtPath(dataDir, null)
        Unit
    }

    @Test
    fun bootCreateRestartUnlock() = runBlocking(Dispatchers.IO) {
        assertFailsWith<WavelengthException.RuntimeNotReady> { client.getInfo() }
        assertEquals(WalletState.NONE, withTimeout(2.minutes) { client.start(config) }.walletState)

        val created = client.createWallet(CreateWalletRequest("correct horse"))
        assertEquals(24, created.mnemonic.size)

        client.stop()
        assertEquals(WalletState.LOCKED, withTimeout(2.minutes) { client.start(config) }.walletState)
        assertEquals(created.identityPubKey, client.unlockWallet("correct horse".encodeToByteArray()).identityPubKey)
    }

    @Test
    fun bootCreateDescriptorRestartUnlock() = runBlocking(Dispatchers.IO) {
        assertEquals(WalletState.NONE, withTimeout(2.minutes) { client.start(config) }.walletState)

        val created = client.createWallet(CreateWalletRequest("correct horse", descriptors = TEST_DESCRIPTORS, birthday = BIRTHDAY))
        assertEquals(TEST_FINGERPRINT, created.rootFingerprint)
        assertEquals(0, created.mnemonic.size)
        assertPublic(created.descriptors)
        assertPublic(client.listWalletDescriptors(ListWalletDescriptorsRequest()).descriptors)

        client.stop()
        assertEquals(WalletState.LOCKED, withTimeout(2.minutes) { client.start(config) }.walletState)
        assertEquals(created.identityPubKey, client.unlockWallet("correct horse".encodeToByteArray()).identityPubKey)
    }

    @Test
    fun atomicDescriptorStartReopens() = runBlocking(Dispatchers.IO) {
        val request = DescriptorWalletRequest(TEST_DESCRIPTORS, birthday = BIRTHDAY)
        val first = withTimeout(2.minutes) { client.startDescriptorWallet(config, request) }
        assertTrue(first.imported)
        assertEquals(TEST_FINGERPRINT, first.rootFingerprint)

        client.stop()
        val second = withTimeout(2.minutes) { client.startDescriptorWallet(config, request) }
        assertFalse(second.imported)
        assertEquals(first.identityPubKey, second.identityPubKey)
    }

    private fun assertPublic(descriptors: List<String>) {
        assertEquals(2, descriptors.size)
        assertTrue(descriptors.none { "prv" in it })
    }

    private companion object {
        // The signet form of BIP32 test vector 1's master key: public, and for tests only.
        const val TEST_KEY = "tprv8ZgxMBicQKsPeDgjzdC36fs6bMjGApWDNLR9erAXMs5skhMv36j9MV5ecvfavji5khqjWaWSFhN3YcCUUdiKH6isR4Pwy3U5y5egddBr16m"
        const val TEST_FINGERPRINT = "3442193e"
        val TEST_DESCRIPTORS = listOf("tr($TEST_KEY/86h/0h/0h/<0;1>/*)#xmftezr0", "wpkh($TEST_KEY/84h/0h/0h/<0;1>/*)#kvpx80va")
        val BIRTHDAY = walletBirthday(1_767_225_600)
    }
}
