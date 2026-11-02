package engineering.lightning.wavelength

import engineering.lightning.wavelength.internal.Redaction
import kotlin.test.Test
import kotlin.test.assertEquals

class RedactionTest {
    @Test
    fun masksSecretsOnly() {
        val json = """{"WalletPassword":"cHc=","Mnemonic":["a","b"],"seed_entropy":"AAA=","prfOutput":"ab","AmountSat":5,"Memo":"hi"}"""
        assertEquals(
            """{"WalletPassword":"<redacted>","Mnemonic":"<redacted>","seed_entropy":"<redacted>","prfOutput":"<redacted>","AmountSat":5,"Memo":"hi"}""",
            Redaction.redact(json),
        )
    }
}
