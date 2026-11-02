package engineering.lightning.wavelength.passkey

import engineering.lightning.wavelength.CreateWalletRequest
import engineering.lightning.wavelength.Network
import engineering.lightning.wavelength.RuntimeConfig
import engineering.lightning.wavelength.testing.FakeWavelengthClient
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class PasskeyCeremonyTest {
    private val prf = ByteArray(32) { (it + 1).toByte() }
    private val prfB64 = PasskeyCeremony.base64Url(prf)
    private val salt = PasskeyCeremony.base64Url(PasskeyCeremony.PRF_SALT)

    private class FakeAuthenticator(
        var onCreate: suspend (JsonObject) -> String = { error("unexpected create") },
        var onGet: suspend (JsonObject) -> String = { error("unexpected get") },
    ) : PasskeyAuthenticator {
        val creates = mutableListOf<JsonObject>()
        val gets = mutableListOf<JsonObject>()

        override suspend fun isSupported() = true

        override suspend fun create(requestJson: String): String {
            val req = Json.parseToJsonElement(requestJson).jsonObject
            creates += req
            return onCreate(req)
        }

        override suspend fun get(requestJson: String): String {
            val req = Json.parseToJsonElement(requestJson).jsonObject
            gets += req
            return onGet(req)
        }
    }

    private fun response(id: String, prfFirst: String?) =
        """{"id":"$id","rawId":"$id","type":"public-key","clientExtensionResults":""" +
            (if (prfFirst == null) "{}" else """{"prf":{"results":{"first":"$prfFirst"}}}""") + "}"

    private fun JsonObject.path(vararg keys: String): String =
        keys.dropLast(1).fold(this) { o, k -> o[k]!!.jsonObject }[keys.last()]!!.jsonPrimitive.content

    @Test
    fun saltIsThePinnedNamespaceDigest() {
        assertEquals(32, PasskeyCeremony.PRF_SALT.size)
        assertEquals("9a7a2e0f", PasskeyCeremony.PRF_SALT_HEX.take(8))
    }

    @Test
    fun registrationRequestShape() = runTest {
        val auth = FakeAuthenticator(onCreate = { response("cred1", prfB64) })
        val result = PasskeyCeremony(auth, "example.com").register("Wave")
        assertEquals("cred1", result.credentialId)
        assertContentEquals(prf, result.prfOutput)

        val req = auth.creates.single()
        assertEquals(salt, req.path("challenge"))
        assertEquals(salt, req.path("extensions", "prf", "eval", "first"))
        assertEquals("example.com", req.path("rp", "id"))
        assertEquals("platform", req.path("authenticatorSelection", "authenticatorAttachment"))
        assertEquals("required", req.path("authenticatorSelection", "residentKey"))
        assertEquals(16, PasskeyCeremony.fromBase64Url(req.path("user", "id")).size)
        assertTrue(auth.gets.isEmpty())
    }

    @Test
    fun registrationWithoutPrfFallsBackToAScopedAssertion() = runTest {
        val auth = FakeAuthenticator(
            onCreate = { response("cred2", null) },
            onGet = { response("cred2", prfB64) },
        )
        val result = PasskeyCeremony(auth, "example.com").register("Wave")
        assertEquals("cred2", result.credentialId)
        val get = auth.gets.single()
        assertEquals("cred2", get["allowCredentials"]!!.jsonArray.single().jsonObject.path("id"))
        assertEquals("example.com", get.path("rpId"))
        assertEquals("required", get.path("userVerification"))
    }

    @Test
    fun assertionWithoutPrfIsUnsupported() = runTest {
        val auth = FakeAuthenticator(onGet = { response("c", null) })
        assertFailsWith<PasskeyException.PrfUnsupported> { PasskeyCeremony(auth, "example.com").assert() }
        assertTrue(auth.gets.single()["allowCredentials"]!!.jsonArray.isEmpty())
    }

    @Test
    fun wrongSizedPrfIsRejected() = runTest {
        val auth = FakeAuthenticator(onGet = { response("c", PasskeyCeremony.base64Url(ByteArray(16))) })
        assertFailsWith<PasskeyException.Failed> { PasskeyCeremony(auth, "example.com").assert() }
    }

    @Test
    fun platformErrorsAreClassified() {
        assertTrue(PasskeyException.fromPlatformMessage("androidx.credentials.exceptions.GetCredentialCancellationException") is PasskeyException.Cancelled)
        assertTrue(PasskeyException.fromPlatformMessage("The operation couldn't be completed. (com.apple.AuthenticationServices.AuthorizationError error 1001.)") is PasskeyException.Cancelled)
        assertTrue(PasskeyException.fromPlatformMessage("passkey registration was cancelled") is PasskeyException.Cancelled)
        assertTrue(PasskeyException.fromPlatformMessage("passkeys require iOS 18 or newer") is PasskeyException.Unavailable)
        assertTrue(PasskeyException.fromPlatformMessage("something odd") is PasskeyException.Failed)
    }

    @Test
    fun callbackAdapterMapsErrors() = runTest {
        val callbacks = object : CallbackPasskeyAuthenticator {
            override fun isSupported() = true
            override fun create(requestJson: String, completion: (String?, String?) -> Unit) =
                completion(null, "passkey registration was cancelled")
            override fun get(requestJson: String, completion: (String?, String?) -> Unit) =
                completion(response("c", prfB64), null)
        }
        val ceremony = PasskeyCeremony(PasskeyAuthenticator.fromCallbacks(callbacks), "example.com")
        assertFailsWith<PasskeyException.Cancelled> { ceremony.register("Wave") }
        assertEquals("c", ceremony.assert().credentialId)
    }

    @Test
    fun ceremonyTimesOut() = runTest {
        val auth = FakeAuthenticator(onGet = { awaitCancellation() })
        assertFailsWith<PasskeyException.TimedOut> { PasskeyCeremony(auth, "example.com", timeout = 5.seconds).assert() }
    }

    @Test
    fun createPasskeyWalletOpensTheDerivedWallet() = runTest {
        val client = FakeWavelengthClient()
        client.start(RuntimeConfig(network = Network.REGTEST))
        val auth = FakeAuthenticator(onCreate = { response("cred9", prfB64) })
        val wallet = client.createPasskeyWallet(PasskeyCeremony(auth, "example.com"), "Wave")
        assertTrue(wallet.result.imported)
        assertEquals("cred9", wallet.credentialId)
        val sent = Json.parseToJsonElement(client.bindings.requests.last { it.first == "openWalletFromPasskey" }.second).jsonObject
        assertEquals(prf.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }, sent.path("prfOutput"))
    }

    @Test
    fun openPasskeyWalletScopesToTheCredential() = runTest {
        val client = FakeWavelengthClient()
        client.start(RuntimeConfig(network = Network.REGTEST))
        client.createWallet(CreateWalletRequest("password1"))
        val auth = FakeAuthenticator(onGet = { response("cred9", prfB64) })
        val wallet = client.openPasskeyWallet(PasskeyCeremony(auth, "example.com"), credentialId = "cred9")
        assertEquals(false, wallet.result.imported)
        assertEquals("cred9", auth.gets.single()["allowCredentials"]!!.jsonArray.single().jsonObject.path("id"))
    }
}
