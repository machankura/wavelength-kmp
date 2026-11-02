package engineering.lightning.wavelength.embedded

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import engineering.lightning.wavelength.CreateWalletRequest
import engineering.lightning.wavelength.Network
import engineering.lightning.wavelength.RuntimeConfig
import engineering.lightning.wavelength.RuntimeLifecycle
import engineering.lightning.wavelength.WavelengthException
import engineering.lightning.wavelength.model.WalletState
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real gomobile runtime in the test app's process. Needs network
 * access to Lightning Labs' signet endpoints.
 */
@RunWith(AndroidJUnit4::class)
class EmbeddedRuntimeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val client = Wavelength.client
    private val dataDir = File(context.cacheDir, "wavelength-test-${System.nanoTime()}").absolutePath
    private val config = RuntimeConfig.preset(Network.SIGNET, dataDir = dataDir).copy(debugLevel = "info")

    @After
    fun tearDown(): Unit = runBlocking {
        client.stop()
        File(dataDir).deleteRecursively()
    }

    @Test
    fun bootCreateRestartUnlock(): Unit = runBlocking {
        assertFailsWith<WavelengthException.RuntimeNotReady> { client.getInfo() }

        val info = withTimeout(120_000) { client.start(config) }
        assertEquals("signet", info.network)
        assertEquals(WalletState.NONE, info.walletState)
        assertEquals(RuntimeLifecycle.RUNNING, client.lifecycle.value)

        val created = client.createWallet(CreateWalletRequest("correct horse"))
        assertEquals(24, created.mnemonic.size)
        assertTrue(created.identityPubKey.isNotEmpty())

        // Persistence: a restarted daemon finds the wallet, locked.
        client.stop()
        assertEquals(RuntimeLifecycle.STOPPED, client.lifecycle.value)
        val reopened = withTimeout(120_000) { client.start(config) }
        assertEquals(WalletState.LOCKED, reopened.walletState)
        assertEquals(created.identityPubKey, client.unlockWallet("correct horse".encodeToByteArray()).identityPubKey)
    }

    @Test
    fun walletCallBeforeCreateIsTyped(): Unit = runBlocking {
        withTimeout(120_000) { client.start(config) }
        val e = assertFailsWith<WavelengthException> { client.balance() }
        assertTrue(e is WavelengthException.FailedPrecondition || e is WavelengthException.Daemon, "got $e")
    }

    @Test
    fun activityStreamEndsOnStop(): Unit = runBlocking {
        withTimeout(120_000) { client.start(config) }
        client.createWallet(CreateWalletRequest("password1"))
        val stream = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { client.activity().toList() }
        delay(2_000) // let the subscription open on a binding thread
        client.stop()
        // A stop ends the stream cleanly (no error) and the fresh wallet has no activity.
        assertEquals(emptyList(), withTimeout(30_000) { stream.await() })
    }
}
