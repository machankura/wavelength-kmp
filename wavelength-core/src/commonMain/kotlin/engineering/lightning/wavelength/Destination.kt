package engineering.lightning.wavelength

/**
 * What a pasted send destination is, so a send UI can render only the fields
 * that apply. It names no settlement rail: an invoice may still quote as
 * Lightning, in-Ark, credit, or mixed (read `rail` from [WavelengthClient.prepareSend]).
 *
 * Mirrors `classifyDestination` in the TypeScript SDK.
 */
public sealed class Destination {
    /** The input is blank. */
    public data object Empty : Destination()

    /** A BOLT-11 invoice, with the amount read from its human-readable part. */
    public data class Invoice(val amount: InvoiceAmount) : Destination()

    /** Not an invoice: treat it as a payable address. */
    public data object Address : Destination()

    public companion object {
        // The digit 1 is outside bech32's data charset, so the last 1 before
        // the data part is always the separator and amounts never contain one.
        private val BOLT11_HRP = Regex("^ln(?:bcrt|tbs|bc|tb)(?:(\\d+)([munp])?)?1", RegexOption.IGNORE_CASE)

        /** 2^53 - 1, the TypeScript SDK's bound, so both SDKs agree. */
        private const val MAX_SAT = 9_007_199_254_740_991L

        /** Classifies [raw], trimming surrounding whitespace. */
        public fun classify(raw: String): Destination {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return Empty
            val match = BOLT11_HRP.find(trimmed) ?: return Address
            val digits = match.groups[1]?.value ?: return Invoice(InvoiceAmount.Amountless)
            val sat = satsFromHrp(digits, match.groups[2]?.value?.lowercase())
            return Invoice(if (sat == null) InvoiceAmount.Unrepresentable else InvoiceAmount.Known(sat))
        }

        /**
         * Converts an HRP amount to whole sats with exact decimal shifting, or
         * null for a sub-satoshi or too-large amount.
         */
        private fun satsFromHrp(digits: String, multiplier: String?): Long? {
            val tenExponent = when (multiplier) {
                null -> 0
                "m" -> 3
                "u" -> 6
                "n" -> 9
                "p" -> 12
                else -> return null
            }
            val exponent = 8 - tenExponent
            var value = digits.trimStart('0')
            if (exponent >= 0) {
                value += "0".repeat(exponent)
            } else {
                val drop = -exponent
                val padded = value.padStart(drop, '0')
                if (padded.takeLast(drop).any { it != '0' }) return null
                value = padded.dropLast(drop).trimStart('0')
            }
            if (value.isEmpty() || value.length > 19) return null
            val sat = value.toLongOrNull() ?: return null
            return sat.takeIf { it in 1..MAX_SAT }
        }
    }
}

/** The amount an invoice carries. */
public sealed class InvoiceAmount {
    /** A whole number of satoshis. */
    public data class Known(val sat: Long) : InvoiceAmount()

    /** No amount: the payer must supply one. */
    public data object Amountless : InvoiceAmount()

    /**
     * An amount that is not a whole number of satoshis, or too large. The
     * invoice still carries it (the daemon pays sub-satoshi amounts rounded
     * up), so do not ask for an amount; it just cannot be displayed.
     */
    public data object Unrepresentable : InvoiceAmount()
}
