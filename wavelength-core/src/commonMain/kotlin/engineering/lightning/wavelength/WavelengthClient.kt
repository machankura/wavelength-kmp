package engineering.lightning.wavelength

import engineering.lightning.wavelength.model.ActivityList
import engineering.lightning.wavelength.model.Balance
import engineering.lightning.wavelength.model.CreateWalletResult
import engineering.lightning.wavelength.model.DepositResult
import engineering.lightning.wavelength.model.DescriptorWalletOpenResult
import engineering.lightning.wavelength.model.Entry
import engineering.lightning.wavelength.model.ExitResult
import engineering.lightning.wavelength.model.ExitStatusResult
import engineering.lightning.wavelength.model.ExitSummaryResult
import engineering.lightning.wavelength.model.ExternalSeedWalletOpenResult
import engineering.lightning.wavelength.model.GetExitPlanResult
import engineering.lightning.wavelength.model.Info
import engineering.lightning.wavelength.model.ListResult
import engineering.lightning.wavelength.model.ListWalletDescriptorsResult
import engineering.lightning.wavelength.model.ListView
import engineering.lightning.wavelength.model.OnchainHistory
import engineering.lightning.wavelength.model.OpenWalletResult
import engineering.lightning.wavelength.model.PrepareSendResult
import engineering.lightning.wavelength.model.ReceiveResult
import engineering.lightning.wavelength.model.Status
import engineering.lightning.wavelength.model.SweepWalletResult
import engineering.lightning.wavelength.model.UnlockWalletResult
import engineering.lightning.wavelength.model.VTXOInventory
import engineering.lightning.wavelength.model.WalletState
import engineering.lightning.wavelength.model.WatchOnlyWalletOpenResult
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map

/**
 * The wavelength wallet: an embedded `waved` daemon driven through typed
 * `suspend` calls. Mirrors the TypeScript SDK's `WavelengthClient`.
 *
 * One daemon runs per process, so an app holds a single client. Every call is
 * main-safe (blocking work runs on a background dispatcher) and throws
 * [WavelengthException] on failure.
 */
public interface WavelengthClient {
    /** The client-side view of the daemon lifecycle. */
    public val lifecycle: StateFlow<RuntimeLifecycle>

    /** Runtime events: ready/stopped, unexpected stream ends, and SDK logs. */
    public val events: SharedFlow<WavelengthEvent>

    /**
     * Boots the daemon and returns its first info snapshot. If a daemon is
     * already running, returns its info and ignores [config] (as the
     * TypeScript SDK does). Start, stop, and external-seed starts are
     * serialised.
     */
    public suspend fun start(config: RuntimeConfig): Info

    /** Stops the daemon and ends every activity stream. Idempotent. */
    public suspend fun stop()

    /** True from the start of a boot until stop completes. */
    public fun isRunning(): Boolean

    public suspend fun getInfo(): Info

    /** Readiness, balance, and pending count in one call. */
    public suspend fun status(): Status

    public suspend fun balance(): Balance

    /** The confirmed balance without decoding a JSON payload. */
    public suspend fun confirmedBalanceSat(): Long

    public suspend fun pendingInboundSat(): Long

    /** Whether the wallet is unlocked and ready to sign. */
    public suspend fun walletReady(): Boolean

    /**
     * Creates a wallet; with a mnemonic or descriptors, restores one. A fresh
     * wallet's result carries the words to back up, once.
     */
    public suspend fun createWallet(request: CreateWalletRequest): CreateWalletResult

    public suspend fun unlockWallet(password: ByteArray): UnlockWalletResult

    /**
     * Imports or unlocks the wallet derived from a WebAuthn passkey's 32-byte
     * PRF output (see `wavelength-passkey`).
     */
    public suspend fun openWalletFromPasskey(prfOutput: ByteArray): OpenWalletResult

    /** A fresh on-chain boarding address. [amountSatHint] may size the boarding round. */
    public suspend fun deposit(amountSatHint: Long = 0): DepositResult

    /** A Lightning invoice to receive into the wallet. */
    public suspend fun receive(request: ReceiveRequest): ReceiveResult

    /** Quotes a payment without moving funds; the quote's intent is single-use. */
    public suspend fun prepareSend(request: SendRequest): PrepareSendResult

    /** Dispatches a prepared quote. On failure, prepare a fresh quote to retry. */
    public suspend fun sendPrepared(prepared: PrepareSendResult): SendOutcome

    /** [prepareSend] then [sendPrepared], for when the fee need not be shown first. */
    public suspend fun send(request: SendRequest): SendOutcome

    /** One wallet view. Prefer the typed [listActivity], [listVtxos], [listOnchain]. */
    public suspend fun list(request: ListRequest = ListRequest()): ListResult

    /**
     * Streams activity entries as they are created and progress. Completes at
     * a clean end (including [stop]); fails with [WavelengthException] on a
     * stream error. Cancelling collection closes the subscription.
     */
    public fun activity(options: ActivityOptions = ActivityOptions()): Flow<Entry> =
        activityEvents(options).filterIsInstance<ActivityEvent.EntryReceived>().map { it.entry }

    /**
     * [activity] with an explicit [ActivityEvent.Opened] once the daemon has
     * accepted the subscription, so a reconnecting consumer can tell an open
     * failure from a stream that opened and later dropped.
     */
    public fun activityEvents(options: ActivityOptions = ActivityOptions()): Flow<ActivityEvent>

    public suspend fun exit(request: ExitRequest): ExitResult

    public suspend fun exitStatus(outpoint: String, detailed: Boolean = false): ExitStatusResult

    public suspend fun exitSummary(): ExitSummaryResult

    /** Previews unilateral-exit readiness and funding for [outpoints]. */
    public suspend fun getExitPlan(outpoints: List<String>, confTarget: Long? = null): GetExitPlanResult

    public suspend fun sweepWallet(request: SweepWalletRequest): SweepWalletResult

    /**
     * Escape hatch: calls a facade verb with raw JSON in the Go wire format
     * (PascalCase keys) and returns the raw JSON result.
     */
    public suspend fun callFacade(verb: FacadeVerb, requestJson: String = "{}"): String
}

/** A client that can also start an external-seed wallet. */
public interface ExternalSeedWalletClient : WavelengthClient {
    /**
     * Boots the daemon at [RuntimeConfig.dataDir] (required: the final
     * per-wallet directory) and imports or unlocks the wallet for
     * [ExternalSeedWalletRequest.seedEntropy] in one step. On success the
     * wallet is ready; no readiness poll is needed.
     *
     * @throws WavelengthException.RuntimeActive if a daemon is already running.
     */
    public suspend fun startExternalSeedWallet(
        config: RuntimeConfig,
        request: ExternalSeedWalletRequest,
    ): ExternalSeedWalletOpenResult
}

/** A client that can also work with a wallet's output descriptors. */
public interface DescriptorWalletClient : WavelengthClient {
    /**
     * Boots the daemon at [RuntimeConfig.dataDir] (required: the final
     * per-wallet directory) and imports or unlocks the wallet for
     * [DescriptorWalletRequest.descriptors] in one step. On success the wallet
     * is ready; no readiness poll is needed. The database password is derived
     * from the keys, so reopen the wallet with this call, not [unlockWallet].
     *
     * @throws WavelengthException.RuntimeActive if a daemon is already running.
     */
    public suspend fun startDescriptorWallet(
        config: RuntimeConfig,
        request: DescriptorWalletRequest,
    ): DescriptorWalletOpenResult

    /**
     * Returns the open wallet's `tr` and `wpkh` descriptors: public by default,
     * or private when [request] resupplies the wallet's mnemonic or root
     * descriptors. Private descriptors are as sensitive as a seed.
     */
    public suspend fun listWalletDescriptors(
        request: ListWalletDescriptorsRequest = ListWalletDescriptorsRequest(),
    ): ListWalletDescriptorsResult
}

/** A client that can also open a wallet whose fund keys live on a remote signer. */
public interface WatchOnlyWalletClient : WavelengthClient {
    /**
     * Boots the daemon at [RuntimeConfig.dataDir] (required: the final
     * per-wallet directory) with [RuntimeConfig.remoteSigner] (required), and
     * imports or unlocks the wallet in one step. On success the wallet is ready;
     * no readiness poll is needed.
     *
     * Every open has the signer sign a challenge with each key, so this fails
     * with [WavelengthException.RemoteSignerFailed] when the signer is
     * unreachable or holds other keys. That is not a wrong password: show it and
     * offer a retry.
     *
     * @throws WavelengthException.RuntimeActive if a daemon is already running.
     */
    public suspend fun startWatchOnlyWallet(
        config: RuntimeConfig,
        request: WatchOnlyWalletRequest,
    ): WatchOnlyWalletOpenResult
}

/** A client with every way this SDK has of opening a wallet. */
public interface EmbeddedWavelengthClient : ExternalSeedWalletClient, DescriptorWalletClient, WatchOnlyWalletClient

/** The outcome of a dispatched send. */
public data class SendOutcome(
    /** The activity entry tracking the payment. */
    val entry: Entry,
    val actualAmountSat: Long,
    /** The Lightning payment hash from the quote, when there is one. */
    val paymentHash: String?,
)

/** Events on [WavelengthClient.activityEvents]. */
public sealed class ActivityEvent {
    /** The subscription is open; entries follow. */
    public data object Opened : ActivityEvent()

    /** An activity entry was created or progressed. */
    public data class EntryReceived(val entry: Entry) : ActivityEvent()
}

/** The client-side lifecycle of the embedded daemon. */
public enum class RuntimeLifecycle { STOPPED, STARTING, RUNNING, STOPPING }

/** Events emitted on [WavelengthClient.events]. */
public sealed class WavelengthEvent {
    /** The daemon finished booting. */
    public data object RuntimeReady : WavelengthEvent()

    /** The daemon stopped. */
    public data object RuntimeStopped : WavelengthEvent()

    /** An activity stream ended without the collector asking it to. */
    public data class ActivityStreamEnded(val error: WavelengthException?) : WavelengthEvent()

    /** An SDK log line (secrets redacted). */
    public data class Log(val level: LogLevel, val message: String) : WavelengthEvent()
}

public enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/** Whether the wallet is fully unlocked and ready to sign. */
public val Info.walletReady: Boolean get() = walletState == WalletState.READY

/** Lists the merged payment history. */
public suspend fun WavelengthClient.listActivity(
    pendingOnly: Boolean = false,
    kinds: Set<engineering.lightning.wavelength.model.EntryKind> = emptySet(),
    limit: Long = 0,
    cursor: String? = null,
): ActivityList = list(ListRequest(ListView.ACTIVITY, pendingOnly, kinds, limit, cursor = cursor)).activity ?: ActivityList()

/** Lists the live VTXO set. */
public suspend fun WavelengthClient.listVtxos(): VTXOInventory =
    list(ListRequest(ListView.VTXOS)).vtxos ?: VTXOInventory()

/** Lists boarding, sweep, and leave transactions. */
public suspend fun WavelengthClient.listOnchain(limit: Long = 0, offset: Long = 0): OnchainHistory =
    list(ListRequest(ListView.ONCHAIN, limit = limit, offset = offset)).onchain ?: OnchainHistory()

/** Restores a wallet from [mnemonic]: [createWallet] with a recovery scan. */
public suspend fun WavelengthClient.restoreWallet(
    password: ByteArray,
    mnemonic: List<String>,
    seedPassphrase: ByteArray? = null,
    recoveryWindow: Long? = null,
): CreateWalletResult {
    if (mnemonic.isEmpty()) throw WavelengthException.InvalidArgument("restoreWallet needs a mnemonic")
    return createWallet(CreateWalletRequest(password, mnemonic, seedPassphrase, recoverState = true, recoveryWindow))
}

/**
 * Restores a wallet from private [descriptors]: [createWallet] with a recovery
 * scan. [birthday] bounds the first rescan; null scans from genesis.
 */
public suspend fun WavelengthClient.restoreWalletFromDescriptors(
    password: ByteArray,
    descriptors: List<String>,
    birthday: Instant? = null,
    recoveryWindow: Long? = null,
): CreateWalletResult {
    if (descriptors.isEmpty()) throw WavelengthException.InvalidDescriptor("restoreWalletFromDescriptors needs descriptors")
    return createWallet(
        CreateWalletRequest(password, recoverState = true, recoveryWindow = recoveryWindow, descriptors = descriptors, birthday = birthday),
    )
}
