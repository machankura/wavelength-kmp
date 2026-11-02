package engineering.lightning.wavelength

import engineering.lightning.wavelength.model.EntryKind
import engineering.lightning.wavelength.model.ListView

// Public request types. They mirror the TypeScript SDK's requests.ts: unions
// there are sealed classes here, and secrets are ByteArrays so callers can
// clear their own copies (the SDK never retains them).

/**
 * Creates a wallet, or restores one when [mnemonic] is set.
 *
 * @property password Encrypts the seed on disk; needed again to unlock. At
 *   least 8 bytes (the daemon's minimum).
 * @property mnemonic Empty for a fresh wallet (the result carries the words to
 *   back up), or the aezeed words to restore.
 * @property seedPassphrase Optional aezeed passphrase for a restored seed.
 * @property recoverState Rescans for Ark state (VTXOs, boarding outputs) on
 *   restore.
 * @property recoveryWindow Address look-ahead for the recovery scan (uint32).
 */
public class CreateWalletRequest(
    public val password: ByteArray,
    public val mnemonic: List<String> = emptyList(),
    public val seedPassphrase: ByteArray? = null,
    public val recoverState: Boolean = false,
    public val recoveryWindow: Long? = null,
) {
    public constructor(
        password: String,
        mnemonic: List<String> = emptyList(),
        seedPassphrase: String? = null,
        recoverState: Boolean = false,
        recoveryWindow: Long? = null,
    ) : this(password.encodeToByteArray(), mnemonic, seedPassphrase?.encodeToByteArray(), recoverState, recoveryWindow)

    override fun toString(): String =
        "CreateWalletRequest(password=<redacted>, mnemonic=<${mnemonic.size} words>, recoverState=$recoverState)"
}

/** Material for [ExternalSeedWalletClient.startExternalSeedWallet]. */
public class ExternalSeedWalletRequest(
    /** Exactly 16 bytes of host-derived entropy. */
    public val seedEntropy: ByteArray,
    /** Pins the wallet identity persisted after first use; a mismatch fails. */
    public val expectedIdentityPubKey: String? = null,
    /** Runs the (idempotent) recovery scan on this start. */
    public val recoverState: Boolean = false,
    public val recoveryWindow: Long? = null,
) {
    /** @throws WavelengthException.InvalidExternalSeed */
    public fun validate() {
        if (seedEntropy.size != ENTROPY_SIZE) {
            throw WavelengthException.InvalidExternalSeed("seedEntropy must be exactly $ENTROPY_SIZE bytes")
        }
        if (expectedIdentityPubKey != null && expectedIdentityPubKey.isEmpty()) {
            throw WavelengthException.InvalidExternalSeed("expectedIdentityPubKey must be nonempty when set")
        }
        if (recoveryWindow != null && recoveryWindow !in 0..0xFFFF_FFFFL) {
            throw WavelengthException.InvalidExternalSeed("recoveryWindow must fit in uint32")
        }
    }

    override fun toString(): String =
        "ExternalSeedWalletRequest(seedEntropy=<redacted>, expectedIdentityPubKey=$expectedIdentityPubKey, recoverState=$recoverState)"

    public companion object {
        /** aezeed entropy size. */
        public const val ENTROPY_SIZE: Int = 16
    }
}

/**
 * Opens a Lightning invoice for [amountSat].
 *
 * @property timeoutSeconds The binding-owned deadline (1..300). Null uses the
 *   facade default of 20 seconds. A timeout throws
 *   [WavelengthException.ReceiveOutcomeUncertain].
 */
public data class ReceiveRequest(
    val amountSat: Long,
    val memo: String = "",
    val timeoutSeconds: Long? = null,
)

/** An outbound payment. Exactly one destination kind. */
public sealed class SendRequest {
    public abstract val note: String
    public abstract val maxFeeSat: Long?

    /**
     * Pays a BOLT-11 invoice.
     *
     * @property amountSat Required only for an amountless invoice.
     * @property maxCreditSat Caps the operator credit applied to this send.
     * @property maxCreditTopupSat Caps a credit top-up made for this send.
     */
    public data class Invoice(
        val invoice: String,
        val amountSat: Long? = null,
        override val note: String = "",
        override val maxFeeSat: Long? = null,
        val maxCreditSat: Long? = null,
        val maxCreditTopupSat: Long? = null,
    ) : SendRequest()

    /**
     * Sends on-chain to [address]. Set [amountSat], or [sweepAll] to drain
     * every VTXO.
     */
    public data class Onchain(
        val address: String,
        val amountSat: Long? = null,
        val sweepAll: Boolean = false,
        override val note: String = "",
        override val maxFeeSat: Long? = null,
    ) : SendRequest()
}

/** Which page of which wallet view to list. */
public data class ListRequest(
    val view: ListView = ListView.ACTIVITY,
    /** Activity view: only entries still in flight. */
    val pendingOnly: Boolean = false,
    /** Activity view: only these kinds (all when empty). */
    val kinds: Set<EntryKind> = emptySet(),
    val limit: Long = 0,
    val offset: Long = 0,
    /** Opaque paging cursor from a previous result's `nextCursor`. */
    val cursor: String? = null,
)

/** Options for [WavelengthClient.activity]. */
public data class ActivityOptions(
    /** Replays current activity before streaming new events. */
    val includeExisting: Boolean = false,
    /** Only these kinds (all when empty). */
    val kinds: Set<EntryKind> = emptySet(),
    /** Resumes after this entry cursor (from a previous stream). */
    val cursor: Long? = null,
)

/** Moves a VTXO out of Ark back on-chain. */
public sealed class ExitRequest {
    public abstract val outpoint: String

    /**
     * The default: the operator folds the exit into a round and pays
     * [destination] (or a fresh backing-wallet address when null).
     */
    public data class Cooperative(
        override val outpoint: String,
        val destination: String? = null,
    ) : ExitRequest()

    /**
     * Unilateral unroll that bypasses the operator: a long-running on-chain
     * job. Reserve it for when the operator is unavailable.
     */
    public data class ForceUnroll(override val outpoint: String) : ExitRequest()

    public companion object {
        /** The acknowledgement the daemon requires for a forced unroll. */
        public const val FORCE_UNROLL_ACK: String = "I_KNOW_WHAT_I_AM_DOING"
    }
}

/** Previews (or, with [broadcast], performs) a backing-wallet sweep. */
public data class SweepWalletRequest(
    val destinationAddress: String,
    val broadcast: Boolean = false,
    val feeRateSatPerVByte: Long? = null,
    val confTarget: Long? = null,
)
