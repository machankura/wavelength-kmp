package engineering.lightning.wavelength.embedded

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import engineering.lightning.wavelength.CreateWalletRequest
import engineering.lightning.wavelength.DescriptorWalletRequest
import engineering.lightning.wavelength.ListWalletDescriptorsRequest
import engineering.lightning.wavelength.Network
import engineering.lightning.wavelength.RemoteSignerConfig
import engineering.lightning.wavelength.RuntimeConfig
import engineering.lightning.wavelength.RuntimeLifecycle
import engineering.lightning.wavelength.WatchOnlyWalletRequest
import engineering.lightning.wavelength.WavelengthException
import engineering.lightning.wavelength.model.WalletState
import engineering.lightning.wavelength.walletBirthday
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assume.assumeTrue
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
    private val signerDirs = mutableListOf<File>()

    @After
    fun tearDown(): Unit = runBlocking {
        client.stop()
        File(dataDir).deleteRecursively()
        signerDirs.forEach { it.deleteRecursively() }
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
    fun bootCreateDescriptorRestartUnlock(): Unit = runBlocking {
        withTimeout(120_000) { client.start(config) }
        val created = client.createWallet(CreateWalletRequest("correct horse", descriptors = TEST_DESCRIPTORS, birthday = BIRTHDAY))
        assertEquals(TEST_FINGERPRINT, created.rootFingerprint)
        assertTrue(created.mnemonic.isEmpty())
        assertPublic(created.descriptors)
        assertPublic(client.listWalletDescriptors().descriptors)

        client.stop()
        assertEquals(WalletState.LOCKED, withTimeout(120_000) { client.start(config) }.walletState)
        assertEquals(created.identityPubKey, client.unlockWallet("correct horse".encodeToByteArray()).identityPubKey)
        assertEquals(created.identityPubKey, client.getInfo().identityPubKey)
    }

    @Test
    fun privateExportNeedsTheWalletsOwnKeys(): Unit = runBlocking {
        val request = DescriptorWalletRequest(TEST_DESCRIPTORS, birthday = BIRTHDAY)
        withTimeout(120_000) { client.startDescriptorWallet(config, request) }
        val exported = client.listWalletDescriptors(ListWalletDescriptorsRequest(includePrivate = true, descriptors = TEST_DESCRIPTORS))
        assertTrue(exported.includesPrivate)
        assertTrue(exported.descriptors.all { "tprv" in it })
    }

    @Test
    fun atomicDescriptorStartReopens(): Unit = runBlocking {
        val request = DescriptorWalletRequest(TEST_DESCRIPTORS, birthday = BIRTHDAY)
        val first = withTimeout(120_000) { client.startDescriptorWallet(config, request) }
        assertTrue(first.imported)
        assertEquals(TEST_FINGERPRINT, first.rootFingerprint)
        assertPublic(first.descriptors)
        assertTrue(client.walletReady())

        client.stop()
        val second = withTimeout(120_000) { client.startDescriptorWallet(config, request) }
        assertFalse(second.imported)
        assertEquals(first.identityPubKey, second.identityPubKey)
        assertEquals(TEST_FINGERPRINT, second.rootFingerprint)
        assertPublic(second.descriptors)
    }

    @Test
    fun aBadDescriptorIsTypedAndDoesNotEchoTheKey(): Unit = runBlocking {
        val e = assertFailsWith<WavelengthException.InvalidDescriptor> {
            client.startDescriptorWallet(config, DescriptorWalletRequest(listOf("tr(${TEST_KEY}x/86h/0h/0h/<0;1>/*)")))
        }
        assertFalse(TEST_KEY.take(20) in e.message.orEmpty(), e.message)
        assertEquals(RuntimeLifecycle.STOPPED, client.lifecycle.value)
    }

    /** Needs a wavesigner on the host: see signerArguments. */
    @Test
    fun watchOnlyOpensAgainstSigner(): Unit = runBlocking {
        val signer = signerArguments("signer")
        val signed = config.copy(remoteSigner = signer.config)
        val request = WatchOnlyWalletRequest("correct horse".encodeToByteArray(), bundle = signer.bundle, birthday = BIRTHDAY)

        val first = withTimeout(180_000) { client.startWatchOnlyWallet(signed, request) }
        assertTrue(first.imported)
        assertEquals(signer.fingerprint, first.rootFingerprint)
        assertPublic(first.descriptors)
        assertPublic(client.listWalletDescriptors().descriptors)
        assertTrue(client.walletReady())

        client.stop()
        val second = withTimeout(180_000) { client.startWatchOnlyWallet(signed, request) }
        assertFalse(second.imported)
        assertEquals(first.identityPubKey, second.identityPubKey)
        assertEquals(signer.fingerprint, second.rootFingerprint)
        assertPublic(second.descriptors)
    }

    @Test
    fun watchOnlyCreateRestartUnlock(): Unit = runBlocking {
        val signer = signerArguments("signer")
        val signed = config.copy(remoteSigner = signer.config)
        withTimeout(120_000) { client.start(signed) }
        val created = withTimeout(180_000) {
            client.createWallet(CreateWalletRequest("correct horse", watchOnlyBundle = signer.bundle, birthday = BIRTHDAY))
        }
        assertEquals(signer.fingerprint, created.rootFingerprint)
        assertTrue(created.mnemonic.isEmpty())

        client.stop()
        assertEquals(WalletState.LOCKED, withTimeout(120_000) { client.start(signed) }.walletState)
        assertEquals(created.identityPubKey, client.unlockWallet("correct horse".encodeToByteArray()).identityPubKey)
    }

    @Test
    fun aDeadSignerIsATypedFailureAndLeavesNoWallet(): Unit = runBlocking {
        val signer = signerArguments("signer")
        val dead = signer.config.copy(rpcHost = "10.0.2.2:1", connectTimeoutSeconds = 3)
        val request = WatchOnlyWalletRequest("correct horse".encodeToByteArray(), bundle = signer.bundle, birthday = BIRTHDAY)
        assertFailsWith<WavelengthException.RemoteSignerFailed> {
            withTimeout(180_000) { client.startWatchOnlyWallet(config.copy(remoteSigner = dead), request) }
        }
        assertEquals(RuntimeLifecycle.STOPPED, client.lifecycle.value)

        // The signer's back: the same request opens the wallet.
        assertTrue(withTimeout(180_000) { client.startWatchOnlyWallet(config.copy(remoteSigner = signer.config), request) }.imported)
    }

    @Test
    fun aSignerWithOtherKeysIsRefused(): Unit = runBlocking {
        val signer = signerArguments("signer")
        val other = signerArguments("otherSigner")
        val request = WatchOnlyWalletRequest("correct horse".encodeToByteArray(), bundle = signer.bundle, birthday = BIRTHDAY)
        assertFailsWith<WavelengthException.RemoteSignerFailed> {
            withTimeout(180_000) { client.startWatchOnlyWallet(config.copy(remoteSigner = other.config), request) }
        }
        assertEquals(RuntimeLifecycle.STOPPED, client.lifecycle.value)

        // The right signer then opens what the failed attempt left behind.
        assertEquals(signer.fingerprint, withTimeout(180_000) { client.startWatchOnlyWallet(config.copy(remoteSigner = signer.config), request) }.rootFingerprint)
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

    private fun assertPublic(descriptors: List<String>) {
        assertEquals(2, descriptors.size)
        assertTrue(descriptors.none { "prv" in it }, "a public export named a private key")
    }

    private class SignerArguments(val config: RemoteSignerConfig, val bundle: String, val fingerprint: String)

    /**
     * Reads a signer from instrumentation arguments, writing its credentials
     * where the daemon can open them, and skips the test when none is named:
     *
     *     ./gradlew :wavelength-embedded:connectedAndroidDeviceTest \
     *       -Pandroid.testInstrumentationRunnerArguments.signerHost=10.0.2.2:10019 \
     *       -Pandroid.testInstrumentationRunnerArguments.signerMacaroonHex=... \
     *       -Pandroid.testInstrumentationRunnerArguments.signerCertBase64=... \
     *       -Pandroid.testInstrumentationRunnerArguments.signerBundleBase64=... \
     *       -Pandroid.testInstrumentationRunnerArguments.signerFingerprint=...
     *
     * [prefix] is `signer`, or `otherSigner` for one holding different keys.
     */
    private fun signerArguments(prefix: String): SignerArguments {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("${prefix}Host")
        assumeTrue("no $prefix named in the instrumentation arguments", host != null)
        val dir = File(context.cacheDir, "wavelength-$prefix-${System.nanoTime()}").apply { mkdirs() }
        signerDirs += dir
        val macaroon = File(dir, "signer.macaroon").apply { writeBytes(hexToBytes(args.getString("${prefix}MacaroonHex")!!)) }
        val cert = File(dir, "tls.cert").apply { writeBytes(Base64.decode(args.getString("${prefix}CertBase64")!!, Base64.DEFAULT)) }
        return SignerArguments(
            RemoteSignerConfig(host!!, macaroon.absolutePath, cert.absolutePath),
            args.getString("${prefix}BundleBase64")?.let { Base64.decode(it, Base64.DEFAULT).decodeToString() }.orEmpty(),
            args.getString("${prefix}Fingerprint").orEmpty(),
        )
    }

    private fun hexToBytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private companion object {
        // The signet form of BIP32 test vector 1's master key: public, and for tests only.
        const val TEST_KEY = "tprv8ZgxMBicQKsPeDgjzdC36fs6bMjGApWDNLR9erAXMs5skhMv36j9MV5ecvfavji5khqjWaWSFhN3YcCUUdiKH6isR4Pwy3U5y5egddBr16m"
        const val TEST_FINGERPRINT = "3442193e"
        val TEST_DESCRIPTORS = listOf("tr($TEST_KEY/86h/0h/0h/<0;1>/*)#xmftezr0", "wpkh($TEST_KEY/84h/0h/0h/<0;1>/*)#kvpx80va")
        val BIRTHDAY = walletBirthday(1_767_225_600)
    }
}
