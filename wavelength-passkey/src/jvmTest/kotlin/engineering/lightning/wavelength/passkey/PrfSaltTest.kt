package engineering.lightning.wavelength.passkey

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

class PrfSaltTest {
    @Test
    fun saltIsSha256OfTheNamespace() {
        val digest = MessageDigest.getInstance("SHA-256").digest(PasskeyCeremony.PRF_NAMESPACE.encodeToByteArray())
        assertEquals(PasskeyCeremony.PRF_SALT_HEX, digest.joinToString("") { "%02x".format(it) })
    }
}
