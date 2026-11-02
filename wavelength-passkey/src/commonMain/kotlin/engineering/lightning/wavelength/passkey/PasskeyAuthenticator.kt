package engineering.lightning.wavelength.passkey

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The platform's WebAuthn transport. It takes and returns standard WebAuthn
 * JSON (base64url fields), as Android Credential Manager does natively.
 *
 * Android: [CredentialManagerAuthenticator]. iOS: the `WavelengthPasskey`
 * Swift class in the `WavelengthPasskey` Swift package target, wrapped with
 * [fromCallbacks].
 */
public interface PasskeyAuthenticator {
    /** A cheap prerequisite probe; true does not guarantee a PRF result. */
    public suspend fun isSupported(): Boolean

    /** Runs a registration; returns the registration response JSON. */
    public suspend fun create(requestJson: String): String

    /** Runs an assertion; returns the authentication response JSON. */
    public suspend fun get(requestJson: String): String

    public companion object {
        /** Adapts a callback-style authenticator (one Swift can implement). */
        public fun fromCallbacks(callbacks: CallbackPasskeyAuthenticator): PasskeyAuthenticator =
            CallbackAdapter(callbacks)
    }
}

/**
 * A callback-style [PasskeyAuthenticator]. Swift cannot implement Kotlin
 * `suspend` functions, so the iOS authenticator implements this instead.
 * Each completion receives either the response JSON or an error message.
 */
public interface CallbackPasskeyAuthenticator {
    public fun isSupported(): Boolean

    public fun create(requestJson: String, completion: (response: String?, error: String?) -> Unit)

    public fun get(requestJson: String, completion: (response: String?, error: String?) -> Unit)
}

private class CallbackAdapter(private val callbacks: CallbackPasskeyAuthenticator) : PasskeyAuthenticator {
    override suspend fun isSupported(): Boolean = callbacks.isSupported()

    override suspend fun create(requestJson: String): String = await { callbacks.create(requestJson, it) }

    override suspend fun get(requestJson: String): String = await { callbacks.get(requestJson, it) }

    private suspend fun await(start: ((String?, String?) -> Unit) -> Unit): String =
        suspendCancellableCoroutine { cont ->
            start { response, error ->
                when {
                    response != null -> cont.resume(response)
                    else -> cont.resumeWithException(PasskeyException.fromPlatformMessage(error))
                }
            }
        }
}
