package engineering.lightning.wavelength

import engineering.lightning.wavelength.internal.WireJson
import engineering.lightning.wavelength.wire.MobileConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class RuntimeConfigTest {
    private fun wireOf(config: RuntimeConfig) =
        WireJson.encodeToJsonElement(MobileConfig.serializer(), config.toWire()).jsonObject

    @Test
    fun wireConfigKeysMatchTheGoFacade() {
        val go = GoFixtures.MOBILE_CONFIG_KEYS.split(",").toSet()
        val descriptor = MobileConfig.serializer().descriptor
        val ours = (0 until descriptor.elementsCount).map(descriptor::getElementName).toSet()
        assertEquals(go, ours, "MobileConfig must mirror mobileConfig's json tags exactly")
    }

    @Test
    fun signetPreset() {
        val c = RuntimeConfig.preset(Network.SIGNET, dataDir = "/data/w")
        val wire = wireOf(c)
        assertEquals(JsonPrimitive("signet"), wire["network"])
        assertEquals(JsonPrimitive("signet.wavelength.lightning.finance:443"), wire["server_address"])
        assertEquals(JsonPrimitive("swap.signet.wavelength.lightning.finance:443"), wire["swap_server_address"])
        assertEquals(JsonPrimitive("grpc"), wire["server_transport"])
        assertEquals(JsonPrimitive("lwwallet"), wire["wallet_type"])
        assertEquals(JsonPrimitive("https://mempool-signet.testnet.lightningcluster.com/api"), wire["wallet_esplora_url"])
    }

    @Test
    fun restPresetUsesUrls() {
        val c = RuntimeConfig.preset(Network.TESTNET4, transport = ServerTransport.REST)
        assertEquals("https://test4.wavelength-rest.lightning.finance", c.arkServerAddress)
    }

    @Test
    fun noPresetForMainnetOrRegtest() {
        assertFailsWith<WavelengthException.InvalidConfig> { RuntimeConfig.preset(Network.MAINNET) }
        assertFailsWith<WavelengthException.InvalidConfig> { RuntimeConfig.preset(Network.REGTEST) }
    }

    @Test
    fun unsetFieldsAreOmitted() {
        val wire = wireOf(RuntimeConfig(network = Network.REGTEST))
        assertEquals(setOf("network", "server_transport", "wallet_type", "swap_server_transport"), wire.keys)
    }

    @Test
    fun disableSwapsOmitsEverySwapField() {
        val c = RuntimeConfig.preset(Network.SIGNET).copy(disableSwaps = true, swapServerInsecure = true)
        assertFalse(wireOf(c).keys.any { it.startsWith("swap_") })
    }

    @Test
    fun explicitZeroCltvIsKept() {
        val wire = wireOf(RuntimeConfig(network = Network.REGTEST, maxPaymentCltv = 0))
        assertEquals(JsonPrimitive(0), wire["max_payment_cltv"])
    }

    @Test
    fun mainnetNeedsAllowMainnet() {
        assertFailsWith<WavelengthException.InvalidConfig> { RuntimeConfig(network = Network.MAINNET).validate() }
        RuntimeConfig(network = Network.MAINNET, allowMainnet = true).validate()
        assertEquals(JsonPrimitive(true), wireOf(RuntimeConfig(network = Network.MAINNET, allowMainnet = true))["allow_mainnet"])
    }

    @Test
    fun walletTypeSpecificFields() {
        assertFailsWith<WavelengthException.InvalidConfig> {
            RuntimeConfig(network = Network.REGTEST, walletFeeUrl = "http://fees").validate()
        }
        assertFailsWith<WavelengthException.InvalidConfig> {
            RuntimeConfig(network = Network.REGTEST, walletType = WalletType.BTCWALLET, walletEsploraUrl = "http://e").validate()
        }
        RuntimeConfig(network = Network.REGTEST, walletType = WalletType.BTCWALLET, walletFeeUrl = "http://fees").validate()
    }

    @Test
    fun numericBounds() {
        val base = RuntimeConfig(network = Network.REGTEST)
        assertFailsWith<WavelengthException.InvalidConfig> { base.copy(walletPollIntervalSeconds = -1).validate() }
        assertFailsWith<WavelengthException.InvalidConfig> { base.copy(walletRecoveryWindow = 0x1_0000_0000).validate() }
        assertFailsWith<WavelengthException.InvalidConfig> { base.copy(maxPaymentCltv = Int.MAX_VALUE + 1L).validate() }
        assertFailsWith<WavelengthException.InvalidConfig> { base.copy(dataDir = " ").validate() }
        base.copy(walletRecoveryWindow = 0xFFFF_FFFF).validate()
    }

    @Test
    fun debugLevels() {
        assertTrue("info" in RuntimeConfig.DEBUG_LEVELS)
    }
}
