package engineering.lightning.wavelength.passkey

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

/**
 * [PasskeyAuthenticator] over Android Credential Manager, which takes and
 * returns standard WebAuthn JSON. The system sheet needs a foreground
 * [Activity], supplied on demand so the authenticator can outlive one.
 *
 * Your relying party domain must publish Digital Asset Links for this app.
 */
public class CredentialManagerAuthenticator(
    context: Context,
    private val activity: () -> Activity?,
) : PasskeyAuthenticator {
    private val appContext = context.applicationContext
    private val credentials = CredentialManager.create(appContext)

    /** Platform passkeys need API 28 and Google Play services. */
    override suspend fun isSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext) == ConnectionResult.SUCCESS

    override suspend fun create(requestJson: String): String {
        val response = try {
            credentials.createCredential(foreground(), CreatePublicKeyCredentialRequest(requestJson))
        } catch (e: CreateCredentialException) {
            throw map(e)
        }
        return (response as? CreatePublicKeyCredentialResponse)?.registrationResponseJson
            ?: throw PasskeyException.Failed("the credential provider returned an unexpected type")
    }

    override suspend fun get(requestJson: String): String {
        val response = try {
            credentials.getCredential(foreground(), GetCredentialRequest(listOf(GetPublicKeyCredentialOption(requestJson))))
        } catch (e: GetCredentialException) {
            throw map(e)
        }
        return (response.credential as? PublicKeyCredential)?.authenticationResponseJson
            ?: throw PasskeyException.Failed("the credential provider returned an unexpected type")
    }

    private fun foreground(): Activity =
        activity() ?: throw PasskeyException.Unavailable("a passkey ceremony needs a foreground activity")

    private fun map(e: Exception): PasskeyException = when (e) {
        is CreateCredentialCancellationException -> PasskeyException.Cancelled("passkey registration was cancelled")
        is GetCredentialCancellationException -> PasskeyException.Cancelled("passkey authentication was cancelled")
        is NoCredentialException -> PasskeyException.NoCredential("no passkey is available on this device for this app")
        is CreateCredentialProviderConfigurationException,
        is GetCredentialProviderConfigurationException,
        -> PasskeyException.Unavailable("no passkey provider is configured on this device")
        else -> PasskeyException.Failed(e.message ?: "passkey ceremony failed", e)
    }
}
