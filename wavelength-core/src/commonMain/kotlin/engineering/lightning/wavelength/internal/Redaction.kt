package engineering.lightning.wavelength.internal

/** Masks secret values in a JSON payload before it is logged. */
internal object Redaction {
    // Compared lower-cased with underscores removed, matching the TypeScript
    // SDK's debug redaction list.
    private val secretKeys = setOf(
        "walletpassword", "seedpassphrase", "mnemonic", "encipheredseed", "seedentropy", "prfoutput",
    )

    private val keyedValue = Regex(""""([A-Za-z_]+)"\s*:\s*("(?:[^"\\]|\\.)*"|\[[^\]]*]|null)""")

    fun redact(json: String): String = keyedValue.replace(json) { m ->
        val key = m.groupValues[1]
        if (key.lowercase().replace("_", "") in secretKeys) "\"$key\":\"<redacted>\"" else m.value
    }
}
