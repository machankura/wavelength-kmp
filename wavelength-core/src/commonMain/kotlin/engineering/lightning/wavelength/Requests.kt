package engineering.lightning.wavelength

import engineering.lightning.wavelength.model.EntryKind
import engineering.lightning.wavelength.model.ListView
import kotlin.time.Instant

// Public request types. They mirror the TypeScript SDK's requests.ts: unions
// there are sealed classes here, and secrets are ByteArrays so callers can
// clear their own copies (the SDK never retains them).

/**
 * Creates a wallet, restores one when [mnemonic] is set, or imports one when
 * [descriptors] or a watch-only source are set.
 *
 * @property password Encrypts the seed on disk; needed again to unlock. At
 *   least 8 bytes (the daemon's minimum).
 * @property mnemonic Empty for a fresh wallet (the result carries the words to
 *   back up), or the aezeed words to restore.
 * @property seedPassphrase Optional aezeed passphrase for a restored seed.
 * @property recoverState Rescans for Ark state (VTXOs, boarding outputs) on
 *   restore.
 * @property recoveryWindow Address look-ahead for the recovery scan (uint32).
 * @property descriptors Private output descriptors to build the wallet from
 *   instead of a mnemonic, as sensitive as a seed. They either share one BIP32
 *   master key, or are account-level private keys covering the on-chain account
 *   and every key family.
 * @property birthday The earliest time the [descriptors] may have received
 *   funds, bounding the first rescan. Null scans from genesis. Ignored for a
 *   mnemonic, which carries its own.
 * @property watchOnlyBundle A watch-only wallet from a remote signer's export,
 *   instead of a mnemonic or descriptors. Needs [RuntimeConfig.remoteSigner]. A
 *   minimal bundle holds private authentication keys.
 * @property watchOnlyManifest The JSON manifest of a signer whose fund slots
 *   come from several keys, used instead of [watchOnlyBundle]. Needs [authKeys].
 * @property authKeys The authentication private keys that go with
 *   [watchOnlyManifest].
 * @property overrideIdentity Allows a [watchOnlyManifest] that replaces the
 *   identity family's key.
 */
public class CreateWalletRequest(
    public val password: ByteArray,
    public val mnemonic: List<String> = emptyList(),
    public val seedPassphrase: ByteArray? = null,
    public val recoverState: Boolean = false,
    public val recoveryWindow: Long? = null,
    public val descriptors: List<String> = emptyList(),
    public val birthday: Instant? = null,
    public val watchOnlyBundle: String? = null,
    public val watchOnlyManifest: String? = null,
    public val authKeys: String? = null,
    public val overrideIdentity: Boolean = false,
) {
    public constructor(
        password: String,
        mnemonic: List<String> = emptyList(),
        seedPassphrase: String? = null,
        recoverState: Boolean = false,
        recoveryWindow: Long? = null,
        descriptors: List<String> = emptyList(),
        birthday: Instant? = null,
        watchOnlyBundle: String? = null,
        watchOnlyManifest: String? = null,
        authKeys: String? = null,
        overrideIdentity: Boolean = false,
    ) : this(
        password.encodeToByteArray(),
        mnemonic,
        seedPassphrase?.encodeToByteArray(),
        recoverState,
        recoveryWindow,
        descriptors,
        birthday,
        watchOnlyBundle,
        watchOnlyManifest,
        authKeys,
        overrideIdentity,
    )

    /** Whether this request asks for a watch-only wallet. */
    public val isWatchOnly: Boolean
        get() = watchOnlyBundle != null || watchOnlyManifest != null || authKeys != null || overrideIdentity

    /**
     * @throws WavelengthException.InvalidDescriptor
     * @throws WavelengthException.InvalidWatchOnly
     */
    public fun validate() {
        if (isWatchOnly) {
            if (mnemonic.isNotEmpty() || seedPassphrase != null || descriptors.isNotEmpty()) {
                throw WavelengthException.InvalidWatchOnly(
                    "a watch-only wallet cannot also have a mnemonic, seedPassphrase or descriptors",
                )
            }
            requireWatchOnlyKeys(
                password, watchOnlyBundle, watchOnlyManifest, authKeys, overrideIdentity,
                passwordName = "password", bundleName = "watchOnlyBundle", manifestName = "watchOnlyManifest",
            )
            requireImportOptions(birthday, null, recoveryWindow, WavelengthException::InvalidWatchOnly)
            return
        }
        if (descriptors.isEmpty()) return
        if (mnemonic.isNotEmpty() || seedPassphrase != null) {
            throw WavelengthException.InvalidDescriptor("descriptors cannot be combined with a mnemonic or seedPassphrase")
        }
        requireDescriptors(descriptors)
        requireImportOptions(birthday, null, recoveryWindow, WavelengthException::InvalidDescriptor)
    }

    override fun toString(): String =
        "CreateWalletRequest(password=<redacted>, mnemonic=<${mnemonic.size} words>, " +
            "descriptors=<${descriptors.size}>, watchOnly=$isWatchOnly, birthday=$birthday, recoverState=$recoverState)"
}

/**
 * Selects what [DescriptorWalletClient.listWalletDescriptors] returns: public
 * descriptors by default.
 *
 * @property includePrivate Requests private descriptors. Needs the wallet's
 *   [mnemonic] or its private root [descriptors], which the daemon checks
 *   against the open wallet before rendering anything private.
 * @property mnemonic The wallet's mnemonic, resupplied to authorize the export.
 * @property seedPassphrase The passphrase for [mnemonic], if it has one.
 * @property descriptors The wallet's private root descriptors, resupplied to
 *   authorize the export.
 */
public class ListWalletDescriptorsRequest(
    public val includePrivate: Boolean = false,
    public val mnemonic: List<String> = emptyList(),
    public val seedPassphrase: ByteArray? = null,
    public val descriptors: List<String> = emptyList(),
) {
    /** @throws WavelengthException.InvalidDescriptor */
    public fun validate() {
        val hasMnemonic = mnemonic.isNotEmpty()
        val hasDescriptors = descriptors.isNotEmpty()
        val invalid = when {
            hasMnemonic && hasDescriptors -> "mnemonic and descriptors are mutually exclusive"
            seedPassphrase != null && !hasMnemonic -> "seedPassphrase requires a mnemonic"
            includePrivate && !hasMnemonic && !hasDescriptors -> "includePrivate requires the wallet's mnemonic or root descriptors"
            !includePrivate && (hasMnemonic || hasDescriptors) -> "mnemonic and descriptors are only used with includePrivate"
            else -> return
        }
        throw WavelengthException.InvalidDescriptor(invalid)
    }

    override fun toString(): String =
        "ListWalletDescriptorsRequest(includePrivate=$includePrivate, mnemonic=<${mnemonic.size} words>, descriptors=<${descriptors.size}>)"
}

private fun requireDescriptors(descriptors: List<String>) {
    if (descriptors.isEmpty() || descriptors.any { it.isBlank() }) {
        throw WavelengthException.InvalidDescriptor("descriptors must be a nonempty list of nonblank strings")
    }
}

// The rules for a watch-only wallet's key material, whichever request carries it.
// The names are how that request spells its fields, for its error messages.
private fun requireWatchOnlyKeys(
    password: ByteArray,
    bundle: String?,
    manifest: String?,
    authKeys: String?,
    overrideIdentity: Boolean,
    passwordName: String,
    bundleName: String,
    manifestName: String,
) {
    val invalid = when {
        password.size < MIN_WALLET_PASSWORD_BYTES -> "$passwordName must be at least $MIN_WALLET_PASSWORD_BYTES bytes"
        bundle != null && bundle.isBlank() -> "$bundleName must not be blank"
        manifest != null && manifest.isBlank() -> "$manifestName must not be blank"
        authKeys != null && authKeys.isBlank() -> "authKeys must not be blank"
        (bundle == null) == (manifest == null) -> "exactly one of $bundleName and $manifestName is required"
        manifest != null && authKeys == null -> "a manifest requires its authKeys"
        bundle != null && (authKeys != null || overrideIdentity) -> "authKeys and overrideIdentity apply only to a manifest"
        else -> return
    }
    throw WavelengthException.InvalidWatchOnly(invalid)
}

// The options a wallet opened from imported keys shares across its lifecycles.
private fun requireImportOptions(
    birthday: Instant?,
    expectedIdentityPubKey: String?,
    recoveryWindow: Long?,
    invalid: (String, Throwable?) -> WavelengthException,
) {
    if (birthday != null && birthday.epochSeconds < 0) {
        throw invalid("birthday must be at or after the Unix epoch", null)
    }
    if (expectedIdentityPubKey != null && expectedIdentityPubKey.isEmpty()) {
        throw invalid("expectedIdentityPubKey must be nonempty when set", null)
    }
    if (recoveryWindow != null && recoveryWindow !in 0..0xFFFF_FFFFL) {
        throw invalid("recoveryWindow must fit in uint32", null)
    }
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
 * Material for [DescriptorWalletClient.startDescriptorWallet]. The host keeps it
 * and supplies it on every open, because the wallet's database password is
 * derived from these keys and no password can reopen the wallet alone.
 */
public class DescriptorWalletRequest(
    /** Private output descriptors, as sensitive as a seed. See [CreateWalletRequest.descriptors]. */
    public val descriptors: List<String>,
    /** The earliest time the keys may have received funds. Null scans from genesis. */
    public val birthday: Instant? = null,
    /**
     * Pins the wallet identity persisted after first use; a mismatch fails. The
     * daemon also always checks the wallet against the identity the descriptors
     * derive.
     */
    public val expectedIdentityPubKey: String? = null,
    /** Runs the (idempotent) recovery scan on this start. */
    public val recoverState: Boolean = false,
    public val recoveryWindow: Long? = null,
) {
    /** @throws WavelengthException.InvalidDescriptor */
    public fun validate() {
        requireDescriptors(descriptors)
        requireImportOptions(birthday, expectedIdentityPubKey, recoveryWindow, WavelengthException::InvalidDescriptor)
    }

    override fun toString(): String =
        "DescriptorWalletRequest(descriptors=<${descriptors.size}>, birthday=$birthday, " +
            "expectedIdentityPubKey=$expectedIdentityPubKey, recoverState=$recoverState)"
}

/**
 * Material for [WatchOnlyWalletClient.startWatchOnlyWallet]: exactly one of
 * [bundle] and [manifest], on every open. The source is imported into an empty
 * data directory and, on later opens, pins the identity the wallet must have.
 */
public class WatchOnlyWalletRequest(
    /**
     * Protects the wallet database and any authentication keys it keeps. A
     * watch-only wallet has no seed to derive it from, so the host supplies and
     * keeps it. At least 8 bytes (the daemon's minimum).
     */
    public val walletPassword: ByteArray,
    /** The signer's export, from `wavesigner export-watchonly`. A minimal bundle holds authentication private keys. */
    public val bundle: String? = null,
    /** The JSON manifest of a signer whose fund slots come from several keys. Needs [authKeys]. */
    public val manifest: String? = null,
    /** The authentication private keys that go with [manifest]. */
    public val authKeys: String? = null,
    /** Allows a [manifest] that replaces the identity family's key. */
    public val overrideIdentity: Boolean = false,
    /** The earliest time the keys may have received funds. Null scans from genesis. */
    public val birthday: Instant? = null,
    /**
     * Pins the wallet identity persisted after first use; a mismatch fails. The
     * daemon also always checks the wallet against the identity [bundle] or
     * [manifest] implies.
     */
    public val expectedIdentityPubKey: String? = null,
    /** Runs the (idempotent) recovery scan on this start. */
    public val recoverState: Boolean = false,
    public val recoveryWindow: Long? = null,
) {
    /** @throws WavelengthException.InvalidWatchOnly */
    public fun validate() {
        requireWatchOnlyKeys(
            walletPassword, bundle, manifest, authKeys, overrideIdentity,
            passwordName = "walletPassword", bundleName = "bundle", manifestName = "manifest",
        )
        requireImportOptions(birthday, expectedIdentityPubKey, recoveryWindow, WavelengthException::InvalidWatchOnly)
    }

    override fun toString(): String =
        "WatchOnlyWalletRequest(walletPassword=<redacted>, source=${if (bundle != null) "bundle" else "manifest"}, " +
            "overrideIdentity=$overrideIdentity, birthday=$birthday, expectedIdentityPubKey=$expectedIdentityPubKey, " +
            "recoverState=$recoverState)"
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

// The daemon's minimum wallet password length (ValidateWalletPassword in waved).
internal const val MIN_WALLET_PASSWORD_BYTES: Int = 8
