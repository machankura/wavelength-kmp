package engineering.lightning.wavelength.engine

import engineering.lightning.wavelength.model.Info

/** Events the engine feeds its phase machine. They carry no behaviour. */
public sealed class EngineEvent {
    public data object RuntimeReady : EngineEvent()
    public data object RuntimeFailed : EngineEvent()
    public data object RuntimeStopped : EngineEvent()
    public data object StartRequested : EngineEvent()
    public data object StartFailed : EngineEvent()
    public data class InfoReceived(val info: Info) : EngineEvent()
    public data object RestoreRequested : EngineEvent()
    public data object WalletBecameReady : EngineEvent()
    public data object RestoreFailedWalletUp : EngineEvent()
    public data object RestoreFailedWalletDown : EngineEvent()
    public data object WalletAdoptionFailed : EngineEvent()
    public data object StreamLost : EngineEvent()
    public data object SyncPollExhausted : EngineEvent()
    public data object BackgroundRefreshExhausted : EngineEvent()
    public data object StopRequested : EngineEvent()
    public data object StopCompleted : EngineEvent()
    public data object StopFailed : EngineEvent()
}

/**
 * The pure phase transition table, ported from the TypeScript SDK's
 * engine/machine.ts. A pair without an entry leaves the phase unchanged.
 */
public object PhaseMachine {
    public fun transition(phase: RuntimePhase, event: EngineEvent): RuntimePhase = when (event) {
        EngineEvent.RuntimeReady -> if (phase == RuntimePhase.LOADING) RuntimePhase.RUNTIME_READY else phase

        // A runtime that dies before it is ready announces its stop before the
        // failure, so STOPPED is accepted too: the failure must not be lost.
        EngineEvent.RuntimeFailed ->
            if (phase == RuntimePhase.LOADING || phase == RuntimePhase.STOPPED) RuntimePhase.ERROR else phase

        // An error outranks the stop that it caused, whatever order the two
        // arrive in; START_REQUESTED still leaves ERROR, so retries work.
        EngineEvent.RuntimeStopped -> if (phase == RuntimePhase.ERROR) phase else RuntimePhase.STOPPED

        EngineEvent.StartRequested -> if (phase == RuntimePhase.STOPPING) phase else RuntimePhase.STARTING

        EngineEvent.StartFailed ->
            if (phase == RuntimePhase.STARTING || phase == RuntimePhase.STOPPED) RuntimePhase.ERROR else phase

        // No entry for RESTORING: the transient locked-looking states a
        // restore passes through must not leak into the UI.
        is EngineEvent.InfoReceived -> when (phase) {
            RuntimePhase.STARTING, RuntimePhase.NEEDS_WALLET, RuntimePhase.LOCKED,
            RuntimePhase.SYNCING, RuntimePhase.READY,
            -> phaseFromInfo(event.info)
            else -> phase
        }

        EngineEvent.RestoreRequested ->
            if (phase == RuntimePhase.NEEDS_WALLET || phase == RuntimePhase.LOCKED) RuntimePhase.RESTORING else phase

        EngineEvent.WalletBecameReady ->
            if (phase == RuntimePhase.RESTORING || phase == RuntimePhase.SYNCING) RuntimePhase.READY else phase

        EngineEvent.RestoreFailedWalletUp -> if (phase == RuntimePhase.RESTORING) RuntimePhase.READY else phase

        EngineEvent.RestoreFailedWalletDown -> if (phase == RuntimePhase.RESTORING) RuntimePhase.NEEDS_WALLET else phase

        EngineEvent.WalletAdoptionFailed ->
            if (phase == RuntimePhase.NEEDS_WALLET || phase == RuntimePhase.LOCKED) RuntimePhase.ERROR else phase

        EngineEvent.StreamLost, EngineEvent.BackgroundRefreshExhausted ->
            if (phase == RuntimePhase.READY) RuntimePhase.ERROR else phase

        EngineEvent.SyncPollExhausted -> if (phase == RuntimePhase.SYNCING) RuntimePhase.ERROR else phase

        EngineEvent.StopRequested ->
            if (phase == RuntimePhase.STOPPING || phase == RuntimePhase.STOPPED) phase else RuntimePhase.STOPPING

        EngineEvent.StopCompleted -> if (phase == RuntimePhase.STOPPING) RuntimePhase.STOPPED else phase

        EngineEvent.StopFailed -> if (phase == RuntimePhase.STOPPING) RuntimePhase.ERROR else phase
    }
}
