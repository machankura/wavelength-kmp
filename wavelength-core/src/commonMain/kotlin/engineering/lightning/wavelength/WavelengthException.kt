package engineering.lightning.wavelength

/**
 * A failed wavelength call. [code] matches the TypeScript SDK's
 * `WavelengthError.code` where one exists, so apps can share handling logic.
 *
 * The gomobile boundary carries only an error's message text, so daemon errors
 * are classified by matching the stable sentinel strings of the Go SDK (see
 * [ErrorClassifier]). Anything unrecognised is a [Daemon] error carrying the
 * raw message.
 */
public sealed class WavelengthException(
    public val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** A wallet call was made before [WavelengthClient.start] (or after stop). */
    public class RuntimeNotReady(message: String = "wavelength runtime is not started", cause: Throwable? = null) :
        WavelengthException("runtime_not_ready", message, cause)

    /** A runtime is already active in this process; call stop first. */
    public class RuntimeActive(message: String, cause: Throwable? = null) :
        WavelengthException("runtime_active", message, cause)

    /** The runtime config is invalid; nothing was started. */
    public class InvalidConfig(message: String, cause: Throwable? = null) :
        WavelengthException("invalid_config", message, cause)

    /** An external-seed request is invalid, or its identity pin did not match. */
    public class InvalidExternalSeed(message: String, cause: Throwable? = null) :
        WavelengthException("invalid_external_seed", message, cause)

    /**
     * A descriptor wallet request is invalid, the daemon refused its
     * descriptors, or they derive a different identity from the wallet's.
     */
    public class InvalidDescriptor(message: String, cause: Throwable? = null) :
        WavelengthException("invalid_descriptor", message, cause)

    /**
     * A watch-only wallet request is invalid, the daemon refused its bundle or
     * manifest, or the wallet no longer matches them. Retrying will not help.
     */
    public class InvalidWatchOnly(message: String, cause: Throwable? = null) :
        WavelengthException("invalid_watch_only", message, cause)

    /**
     * The remote signer could not be reached, or it holds different keys. This
     * is not a wrong password: offer a retry once the signer is back, and check
     * its settings if the message says it does not hold the wallet's keys.
     */
    public class RemoteSignerFailed(message: String, cause: Throwable? = null) :
        WavelengthException("remote_signer_failed", message, cause)

    /** An argument was rejected before reaching the daemon. */
    public class InvalidArgument(message: String, cause: Throwable? = null) :
        WavelengthException("invalid_argument", message, cause)

    /** The activity cursor is invalid. */
    public class InvalidCursor(message: String, cause: Throwable? = null) :
        WavelengthException("invalid_cursor", message, cause)

    /** The wallet database is held by another process or instance. */
    public class WalletLocked(message: String, cause: Throwable? = null) :
        WavelengthException("wallet_locked", message, cause)

    /**
     * A receive timed out or was cancelled with an unknown outcome: the
     * invoice may exist. Reconcile the activity list before creating another.
     */
    public class ReceiveOutcomeUncertain(message: String, cause: Throwable? = null) :
        WavelengthException("receive_outcome_uncertain", message, cause)

    /** The prepared send intent is missing, expired, or already used. Prepare again. */
    public class InvalidSendIntent(message: String, cause: Throwable? = null) :
        WavelengthException("invalid_send_intent", message, cause)

    /** The send destination is missing or not payable. */
    public class InvalidDestination(message: String, cause: Throwable? = null) :
        WavelengthException("invalid_destination", message, cause)

    /** The send needs an amount (an amountless invoice or on-chain send). */
    public class AmountRequired(message: String, cause: Throwable? = null) :
        WavelengthException("amount_required", message, cause)

    /** The amount is out of range. */
    public class AmountInvalid(message: String, cause: Throwable? = null) :
        WavelengthException("amount_invalid", message, cause)

    /** The amount exceeds the operator's maximum VTXO size. */
    public class AmountExceedsVtxoLimit(message: String, cause: Throwable? = null) :
        WavelengthException("amount_exceeds_vtxo_limit", message, cause)

    /** The amount would exceed the maximum allowed wallet balance. */
    public class BalanceLimitExceeded(message: String, cause: Throwable? = null) :
        WavelengthException("balance_limit_exceeded", message, cause)

    /** A sub-dust receive needs the operator credit subsystem, which is unavailable. */
    public class CreditReceiveUnavailable(message: String, cause: Throwable? = null) :
        WavelengthException("credit_receive_unavailable", message, cause)

    /** Lightning swaps are unavailable (disabled or the swap server is unreachable). */
    public class SwapBackendUnavailable(message: String, cause: Throwable? = null) :
        WavelengthException("swap_backend_unavailable", message, cause)

    /** A list filter named an unsupported entry kind. */
    public class UnsupportedKind(message: String, cause: Throwable? = null) :
        WavelengthException("unsupported_kind", message, cause)

    /**
     * The daemon rejected the call in its current state, for example a wallet
     * call before a wallet exists or while it is locked. [grpcCode] is the
     * gRPC status code name when the daemon reported one.
     */
    public class FailedPrecondition(message: String, public val grpcCode: String, cause: Throwable? = null) :
        WavelengthException("failed_precondition", message, cause)

    /** The activity stream skipped entries; resume a new stream from [cursor]. */
    public class StreamGap(message: String, public val cursor: Long, cause: Throwable? = null) :
        WavelengthException("stream_gap", message, cause)

    /** The native runtime panicked; the panic was recovered and the call failed. */
    public class RuntimePanic(message: String, cause: Throwable? = null) :
        WavelengthException("runtime_panic", message, cause)

    /** Any other daemon error. [grpcCode] is set when the message carried one. */
    public class Daemon(message: String, public val grpcCode: String? = null, cause: Throwable? = null) :
        WavelengthException("wavelength_error", message, cause)
}

/**
 * Maps the message text of an error from the gomobile binding to a typed
 * [WavelengthException].
 *
 * The patterns are the Go SDK's stable strings at the pinned wavelength
 * release: the `wavewalletdk` sentinel errors (sdk/wavewalletdk/errors.go,
 * reconstructed from gRPC ErrorInfo reasons, so the message starts with the
 * sentinel text), the mobile facade's lifecycle errors, and gRPC status text.
 */
public object ErrorClassifier {
    private val sentinels: List<Pair<String, (String, Throwable?) -> WavelengthException>> = listOf(
        "send destination must set invoice or onchain_address" to WavelengthException::InvalidDestination,
        "send intent is missing, expired, or already consumed" to WavelengthException::InvalidSendIntent,
        "amt_sat is required for the requested send" to WavelengthException::AmountRequired,
        "amt_sat is out of range" to WavelengthException::AmountInvalid,
        "unsupported entry kind in list filter" to WavelengthException::UnsupportedKind,
        "swap backend handle is unavailable" to WavelengthException::SwapBackendUnavailable,
        "amount exceeds the operator's maximum VTXO size" to WavelengthException::AmountExceedsVtxoLimit,
        "amount would exceed the maximum allowed wallet balance" to WavelengthException::BalanceLimitExceeded,
        "sub-dust receive via the operator credit subsystem is unavailable" to WavelengthException::CreditReceiveUnavailable,
        "invalid descriptors" to WavelengthException::InvalidDescriptor,
        "invalid account keys" to WavelengthException::InvalidDescriptor,
        "descriptor wallet requires at least" to WavelengthException::InvalidDescriptor,
        "descriptor wallet identity mismatch" to WavelengthException::InvalidDescriptor,
        "invalid watch-only bundle" to WavelengthException::InvalidWatchOnly,
        "invalid manifest" to WavelengthException::InvalidWatchOnly,
        "invalid authentication keys" to WavelengthException::InvalidWatchOnly,
        "watch-only wallet requires" to WavelengthException::InvalidWatchOnly,
        "a watch-only wallet needs" to WavelengthException::InvalidWatchOnly,
        "a manifest requires its authentication keys" to WavelengthException::InvalidWatchOnly,
        "authentication keys and OverrideIdentity" to WavelengthException::InvalidWatchOnly,
        "watch-only wallet identity mismatch" to WavelengthException::InvalidWatchOnly,
        "wallet accounts do not match its stored manifest" to WavelengthException::InvalidWatchOnly,
        "the wallet database holds public keys only" to WavelengthException::InvalidWatchOnly,
        "the wallet's bundle does not cover every key family" to WavelengthException::InvalidWatchOnly,
        "key family not supplied by the wallet's bundle" to WavelengthException::InvalidWatchOnly,
        "connect to remote signer" to WavelengthException::RemoteSignerFailed,
        "remote signer not ready" to WavelengthException::RemoteSignerFailed,
        "remote signer challenge" to WavelengthException::RemoteSignerFailed,
        "verify remote signer" to WavelengthException::RemoteSignerFailed,
        "remote signer does not hold this wallet's keys" to WavelengthException::RemoteSignerFailed,
        "signer does not hold the key for a wallet slot" to WavelengthException::RemoteSignerFailed,
    )

    private val grpcStatus = Regex("""rpc error: code = (\w+) desc = """)
    private val streamGap = Regex("""wallet subscription gap at cursor (-?\d+)""")
    private val walletLocked = Regex("""database is locked|SQLITE_BUSY""", RegexOption.IGNORE_CASE)

    /** The stable prefix of a receive whose outcome is unknown (mobile/wallet.go). */
    public const val RECEIVE_UNCERTAIN_PREFIX: String = "receive outcome uncertain; reconcile Activity before retrying"

    /** Classifies [message]; [cause] is attached to the result. */
    public fun classify(message: String?, cause: Throwable? = null): WavelengthException {
        val msg = message.orEmpty().ifEmpty { "unknown wavelength error" }

        if (msg.startsWith(RECEIVE_UNCERTAIN_PREFIX)) return WavelengthException.ReceiveOutcomeUncertain(msg, cause)
        if (msg.contains("wavewalletdk mobile not started")) return WavelengthException.RuntimeNotReady(msg, cause)
        if (msg.contains("wavewalletdk mobile already started")) return WavelengthException.RuntimeActive(msg, cause)
        if (msg.startsWith("wavewalletdk mobile panic")) return WavelengthException.RuntimePanic(msg, cause)

        sentinels.firstOrNull { (text, _) -> msg.contains(text) }?.let { (_, make) -> return make(msg, cause) }

        streamGap.find(msg)?.let {
            return WavelengthException.StreamGap(msg, it.groupValues[1].toLong(), cause)
        }
        if (walletLocked.containsMatchIn(msg)) return WavelengthException.WalletLocked(msg, cause)

        val code = grpcStatus.find(msg)?.groupValues?.get(1)
        return when (code) {
            "FailedPrecondition" -> WavelengthException.FailedPrecondition(msg, code, cause)
            else -> WavelengthException.Daemon(msg, code, cause)
        }
    }

    /** Classifies [error] unless it already is a [WavelengthException]. */
    public fun classify(error: Throwable): WavelengthException =
        error as? WavelengthException ?: classify(error.message, error)
}
