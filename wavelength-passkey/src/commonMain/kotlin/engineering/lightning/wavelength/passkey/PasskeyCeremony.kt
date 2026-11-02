package engineering.lightning.wavelength.passkey

import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** A passkey's PRF output and the credential that produced it. */
public class PasskeyAssertion(
    /** The 32-byte WebAuthn PRF output, the input to wavelength's wallet derivation. */
    public val prfOutput: ByteArray,
    /** base64url credential id; store it to scope later assertions to this passkey. */
    public val credentialId: String,
) {
    override fun toString(): String = "PasskeyAssertion(credentialId=$credentialId, prfOutput=<redacted>)"
}

/** Passkey ceremony failures. */
public sealed class PasskeyException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The user dismissed the system sheet. */
    public class Cancelled(message: String = "passkey ceremony was cancelled") : PasskeyException(message)

    /** No passkey for this relying party exists on the device. */
    public class NoCredential(message: String) : PasskeyException(message)

    /** The device cannot run passkey ceremonies (no provider, old OS). */
    public class Unavailable(message: String) : PasskeyException(message)

    /** The authenticator returned no PRF result: it does not support the PRF extension. */
    public class PrfUnsupported(message: String = "the authenticator did not return a PRF result") : PasskeyException(message)

    /** The ceremony did not finish within [PasskeyCeremony.timeout]. */
    public class TimedOut(message: String) : PasskeyException(message)

    /** Anything else; [message] is the platform's. */
    public class Failed(message: String, cause: Throwable? = null) : PasskeyException(message, cause)

    public companion object {
        // Mirrors the TypeScript SDK's isNativeCancel matching.
        private val cancel = Regex(
            """GetCredentialCancellationException|CreateCredentialCancellationException|""" +
                """ASAuthorizationError.*\b1001\b|\berror\s*1001\b|\buser.{0,10}cancel|was cancelled""",
            RegexOption.IGNORE_CASE,
        )

        /** Classifies a platform error message. */
        public fun fromPlatformMessage(message: String?): PasskeyException {
            val text = message ?: "passkey ceremony failed"
            return when {
                cancel.containsMatchIn(text) -> Cancelled(text)
                text.contains("no passkey is available", ignoreCase = true) -> NoCredential(text)
                text.contains("iOS 18", ignoreCase = true) ||
                    text.contains("no passkey provider", ignoreCase = true) -> Unavailable(text)
                else -> Failed(text)
            }
        }
    }
}

/**
 * Wavelength's passkey ceremony: a WebAuthn registration or assertion that
 * evaluates the PRF extension with the fixed wavelength salt, yielding the
 * 32 bytes [engineering.lightning.wavelength.WavelengthClient.openWalletFromPasskey]
 * turns into a wallet. The same passkey gives the same wallet on every device.
 *
 * Mirrors the TypeScript SDK's `nativePasskeyCeremony`.
 *
 * @param rpId The relying party (your domain). Android needs Digital Asset
 *   Links and iOS an associated-domains entitlement for it.
 */
public class PasskeyCeremony(
    private val authenticator: PasskeyAuthenticator,
    private val rpId: String,
    public val timeout: Duration = 2.minutes,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val saltB64Url = base64Url(PRF_SALT)

    public suspend fun isSupported(): Boolean = try {
        authenticator.isSupported()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    /**
     * Registers a new passkey named [appName] and returns its PRF output. If
     * the authenticator does not evaluate PRF during registration, it
     * immediately asserts the new credential to get it.
     */
    public suspend fun register(appName: String, userName: String = appName): PasskeyAssertion {
        val request = buildJsonObject {
            put("challenge", saltB64Url)
            putJsonObject("rp") {
                put("id", rpId)
                put("name", appName)
            }
            putJsonObject("user") {
                put("id", base64Url(Random.nextBytes(USER_ID_BYTES)))
                put("name", userName)
                put("displayName", userName)
            }
            putJsonArray("pubKeyCredParams") {
                for (alg in listOf(-7, -257)) {
                    addJsonObject {
                        put("alg", alg)
                        put("type", "public-key")
                    }
                }
            }
            putJsonObject("authenticatorSelection") {
                put("authenticatorAttachment", "platform")
                put("userVerification", "required")
                put("residentKey", "required")
            }
            prfExtension()
        }
        val response = parse(run("registration") { authenticator.create(request.toString()) })
        val first = prfFirst(response) ?: return assert(credentialId(response))
        return PasskeyAssertion(prfBytes(first), credentialId(response))
    }

    /**
     * Asserts an existing passkey and returns its PRF output. Pass
     * [allowCredentialId] to require one specific passkey.
     */
    public suspend fun assert(allowCredentialId: String? = null): PasskeyAssertion {
        val request = buildJsonObject {
            put("challenge", saltB64Url)
            put("rpId", rpId)
            putJsonArray("allowCredentials") {
                if (allowCredentialId != null) {
                    addJsonObject {
                        put("type", "public-key")
                        put("id", allowCredentialId)
                    }
                }
            }
            put("userVerification", "required")
            prfExtension()
        }
        val response = parse(run("authentication") { authenticator.get(request.toString()) })
        val first = prfFirst(response) ?: throw PasskeyException.PrfUnsupported()
        return PasskeyAssertion(prfBytes(first), credentialId(response))
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.prfExtension() {
        putJsonObject("extensions") {
            putJsonObject("prf") {
                putJsonObject("eval") { put("first", saltB64Url) }
            }
        }
    }

    private suspend fun run(label: String, block: suspend () -> String): String = try {
        withTimeout(timeout) { block() }
    } catch (e: TimeoutCancellationException) {
        throw PasskeyException.TimedOut("passkey $label timed out")
    } catch (e: CancellationException) {
        throw e
    } catch (e: PasskeyException) {
        throw e
    } catch (e: Exception) {
        throw PasskeyException.fromPlatformMessage(e.message)
    }

    private fun parse(response: String): JsonObject = try {
        json.parseToJsonElement(response).jsonObject
    } catch (e: Exception) {
        throw PasskeyException.Failed("malformed passkey response", e)
    }

    private fun credentialId(response: JsonObject): String =
        response["id"]?.jsonPrimitive?.contentOrNull ?: throw PasskeyException.Failed("passkey response has no credential id")

    private fun prfFirst(response: JsonObject): String? =
        (response["clientExtensionResults"] as? JsonObject)
            ?.let { it["prf"] as? JsonObject }
            ?.let { it["results"] as? JsonObject }
            ?.get("first")?.jsonPrimitive?.contentOrNull

    private fun prfBytes(firstB64Url: String): ByteArray {
        val bytes = try {
            fromBase64Url(firstB64Url)
        } catch (e: IllegalArgumentException) {
            throw PasskeyException.Failed("malformed PRF output", e)
        }
        if (bytes.size != PRF_OUTPUT_BYTES) throw PasskeyException.Failed("passkey PRF output is not $PRF_OUTPUT_BYTES bytes")
        return bytes
    }

    public companion object {
        /**
         * The PRF namespace. Its SHA-256 is the fixed salt every ceremony
         * evaluates; changing it would orphan every existing passkey wallet.
         */
        public const val PRF_NAMESPACE: String = "wavewalletdk-passkey:v1"

        /** SHA-256([PRF_NAMESPACE]), as in the TypeScript SDK's PASSKEY_PRF_SALT_HEX. */
        public const val PRF_SALT_HEX: String = "9a7a2e0ff3c5d1f2f172cd5edd67527bf392ae399048eb6e36b9cb6c3ab89d03"

        public val PRF_SALT: ByteArray get() = PRF_SALT_HEX.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        public const val PRF_OUTPUT_BYTES: Int = 32

        private const val USER_ID_BYTES = 16

        private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

        internal fun base64Url(bytes: ByteArray): String = base64Url.encode(bytes).trimEnd('=')

        internal fun fromBase64Url(value: String): ByteArray =
            base64Url.decode(value.replace('+', '-').replace('/', '_').trimEnd('='))
    }
}
