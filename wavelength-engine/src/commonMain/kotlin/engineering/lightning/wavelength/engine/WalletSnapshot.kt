package engineering.lightning.wavelength.engine

import engineering.lightning.wavelength.WavelengthEvent
import engineering.lightning.wavelength.model.Balance
import engineering.lightning.wavelength.model.CreateWalletResult
import engineering.lightning.wavelength.model.Entry
import engineering.lightning.wavelength.model.Info
import engineering.lightning.wavelength.model.WalletState

/** The runtime and wallet lifecycle phase a UI renders. */
public enum class RuntimePhase {
    /** The engine is initialising. */
    LOADING,

    /** The runtime can be started. */
    RUNTIME_READY,
    STARTING,

    /** Running, with no wallet yet: offer create or restore. */
    NEEDS_WALLET,

    /** A wallet exists but is locked: ask for the password. */
    LOCKED,

    /** Unlocked and catching up with the chain. */
    SYNCING,

    /** A restore is running; the wallet is not usable yet. */
    RESTORING,

    /** Fully usable. */
    READY,
    STOPPING,
    STOPPED,

    /** A fatal error; see [WalletSnapshot.error]. [WalletEngine.start] recovers. */
    ERROR,
}

/** The wallet phase implied by a daemon info snapshot. */
public fun phaseFromInfo(info: Info): RuntimePhase = when (info.walletState) {
    WalletState.READY -> RuntimePhase.READY
    WalletState.LOCKED -> RuntimePhase.LOCKED
    WalletState.SYNCING -> RuntimePhase.SYNCING
    else -> RuntimePhase.NEEDS_WALLET
}

/** The state of a background recovery started by [WalletEngine.restoreWallet]. */
public sealed class RecoveryState {
    /** No tracked restore (or it was acknowledged). */
    public data object Idle : RecoveryState()

    /** The daemon's recovery scan is running. */
    public data object Restoring : RecoveryState()

    /** The scan finished; [result] carries its counters. */
    public data class Done(val result: CreateWalletResult) : RecoveryState()

    /**
     * The restore failed. With [walletUsable] the wallet works but its state
     * may be incomplete; without it, the phase fell back to NEEDS_WALLET.
     */
    public data class Failed(val error: Throwable, val walletUsable: Boolean) : RecoveryState()
}

/**
 * The engine's immutable state. A fetch that changed nothing keeps the
 * previous field instances, so consumers can compare slices cheaply.
 */
public data class WalletSnapshot(
    val phase: RuntimePhase = RuntimePhase.LOADING,
    /** The last fatal runtime-level error. */
    val error: Throwable? = null,
    val info: Info? = null,
    val balance: Balance? = null,
    /** Activity entries, newest first, with retained send preimages. */
    val activity: List<Entry> = emptyList(),
    val recovery: RecoveryState = RecoveryState.Idle,
    /** A bounded tail of SDK log lines, newest last. */
    val logs: List<WavelengthEvent.Log> = emptyList(),
)
