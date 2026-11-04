package engineering.lightning.wavelength.embedded

import engineering.lightning.wavelength.CreateWalletRequest
import engineering.lightning.wavelength.DescriptorWalletRequest
import engineering.lightning.wavelength.FacadeWavelengthClient
import engineering.lightning.wavelength.Network
import engineering.lightning.wavelength.RemoteSignerConfig
import engineering.lightning.wavelength.RuntimeConfig
import engineering.lightning.wavelength.WatchOnlyWalletRequest
import engineering.lightning.wavelength.WavelengthException
import engineering.lightning.wavelength.model.WalletState
import engineering.lightning.wavelength.testing.FakeFacadeBindings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
    fun reopensADescriptorWalletThroughItsVerb(): Unit = runBlocking {
        val bindings = FakeFacadeBindings()
        val client = FacadeWavelengthClient(bindings, Dispatchers.Default)
        val request = DescriptorWalletRequest(listOf("tr(tprv1)"))
        client.startDescriptorWallet(config, request)
        val policy = BackgroundRestartPolicy.reopening(client, this) {
            client.startDescriptorWallet(config, request)
            client.getInfo()
        }

        val restart = async(start = CoroutineStart.UNDISPATCHED) { policy.restarts.first() }
        policy.onBackground()
        policy.onForeground()
        val info = withTimeout(5_000) { restart.await() }.getOrThrow()

        assertEquals(WalletState.READY, info.walletState)
        assertEquals(
            listOf("startDescriptorWallet", "stop", "startDescriptorWallet"),
            bindings.requests.map { it.first }.filter { it != "getInfo" },
        )
        client.stop()
    }

    @Test
    fun aDeadSignerOnReturnIsATypedFailureAndRetries(): Unit = runBlocking {
        val bindings = FakeFacadeBindings()
        val client = FacadeWavelengthClient(bindings, Dispatchers.Default)
        val signed = config.copy(remoteSigner = RemoteSignerConfig("host:1", "/m", "/c"))
        val request = WatchOnlyWalletRequest("password1".encodeToByteArray(), bundle = "b")
        client.startWatchOnlyWallet(signed, request)
        val policy = BackgroundRestartPolicy.reopening(client, this) {
            client.startWatchOnlyWallet(signed, request)
            client.getInfo()
        }

        val failure = async(start = CoroutineStart.UNDISPATCHED) { policy.restarts.first() }
        policy.onBackground()
        withTimeout(5_000) { while (client.isRunning()) kotlinx.coroutines.delay(1) }
        bindings.failNext("startWatchOnlyWallet", "start watch-only wallet: connect to remote signer: refused")
        policy.onForeground()
        val error = withTimeout(5_000) { failure.await() }.exceptionOrNull()
        assertIs<WavelengthException.RemoteSignerFailed>(error)
        assertFalse(client.isRunning())

        // The signer is back: the host's retry opens the wallet.
        assertFalse(client.startWatchOnlyWallet(signed, request).imported)
        assertTrue(client.walletReady())
        client.stop()
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
