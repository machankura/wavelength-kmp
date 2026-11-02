package engineering.lightning.wavelength.embedded

import engineering.lightning.wavelength.CreateWalletRequest
import engineering.lightning.wavelength.FacadeWavelengthClient
import engineering.lightning.wavelength.Network
import engineering.lightning.wavelength.RuntimeConfig
import engineering.lightning.wavelength.model.WalletState
import engineering.lightning.wavelength.testing.FakeFacadeBindings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class BackgroundRestartPolicyTest {
    private val config = RuntimeConfig(network = Network.REGTEST, dataDir = "/tmp/w")

    @Test
    fun stopsInBackgroundAndRestartsLockedInForeground(): Unit = runBlocking {
        val bindings = FakeFacadeBindings()
        val client = FacadeWavelengthClient(bindings, Dispatchers.Default)
        client.start(config)
        client.createWallet(CreateWalletRequest("password1"))
        val policy = BackgroundRestartPolicy(client, this) { config }

        val restart = async(start = CoroutineStart.UNDISPATCHED) { policy.restarts.first() }
        policy.onBackground()
        policy.onForeground()
        val info = withTimeout(5_000) { restart.await() }.getOrThrow()

        assertEquals(WalletState.LOCKED, info.walletState)
        assertEquals(listOf("start", "createWallet", "stop", "start"), bindings.requests.map { it.first }.filter { it != "getInfo" })
        assertTrue(client.isRunning())
    }

    @Test
    fun noRestartIfItWasNotRunning(): Unit = runBlocking {
        val bindings = FakeFacadeBindings()
        val client = FacadeWavelengthClient(bindings, Dispatchers.Default)
        val policy = BackgroundRestartPolicy(client, this) { config }
        policy.onBackground()
        policy.onForeground()
        assertFalse(client.isRunning())
        assertTrue(bindings.requests.isEmpty())
    }
}
