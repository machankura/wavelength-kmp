package engineering.lightning.wavelength.embedded

import engineering.lightning.wavelength.CreateWalletRequest
import engineering.lightning.wavelength.Network
import engineering.lightning.wavelength.RuntimeConfig
import engineering.lightning.wavelength.WavelengthException
import engineering.lightning.wavelength.model.WalletState
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
}
