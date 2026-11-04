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

    @Test
    fun masksWalletKeyMaterialInEitherCase() {
        for (key in listOf(
            "descriptors", "Descriptors", "bundle", "Bundle", "manifest", "Manifest", "auth_keys", "AuthKeys",
            "watch_only_bundle", "WatchOnlyBundle", "watch_only_manifest", "WatchOnlyManifest",
        )) {
            assertEquals(
                """{"$key":"<redacted>","Memo":"hi"}""",
                Redaction.redact("""{"$key":"secret","Memo":"hi"}"""),
                "for $key",
            )
        }
    }

    @Test
    fun aKeyOriginDoesNotEndTheArrayEarly() {
        val json = """{"Descriptors":["tr([d34db33f/86h/0h/0h]xprv1/<0;1>/*)#abc","[d34db33f/1017h/0h]xprv2"],"AmountSat":5}"""
        assertEquals("""{"Descriptors":"<redacted>","AmountSat":5}""", Redaction.redact(json))
    }
}
