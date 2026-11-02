package engineering.lightning.wavelength.engine

import engineering.lightning.wavelength.engine.RuntimePhase.ERROR
import engineering.lightning.wavelength.engine.RuntimePhase.LOADING
import engineering.lightning.wavelength.engine.RuntimePhase.LOCKED
import engineering.lightning.wavelength.engine.RuntimePhase.NEEDS_WALLET
import engineering.lightning.wavelength.engine.RuntimePhase.READY
import engineering.lightning.wavelength.engine.RuntimePhase.RESTORING
import engineering.lightning.wavelength.engine.RuntimePhase.RUNTIME_READY
import engineering.lightning.wavelength.engine.RuntimePhase.STARTING
import engineering.lightning.wavelength.engine.RuntimePhase.STOPPED
import engineering.lightning.wavelength.engine.RuntimePhase.STOPPING
import engineering.lightning.wavelength.engine.RuntimePhase.SYNCING
import engineering.lightning.wavelength.model.Info
import engineering.lightning.wavelength.model.WalletState
import kotlin.test.Test
import kotlin.test.assertEquals

// Ported case for case from the TypeScript SDK's engine/machine.test.ts.
class PhaseMachineTest {
    private val readyInfo = EngineEvent.InfoReceived(Info(walletState = WalletState.READY))
    private val lockedInfo = EngineEvent.InfoReceived(Info(walletState = WalletState.LOCKED))

    private val table: List<Triple<EngineEvent, RuntimePhase, RuntimePhase>> = listOf(
        Triple(EngineEvent.RuntimeReady, LOADING, RUNTIME_READY),
        Triple(EngineEvent.RuntimeFailed, LOADING, ERROR),
        Triple(EngineEvent.StartFailed, STARTING, ERROR),
        Triple(EngineEvent.StartFailed, STOPPED, ERROR),
        Triple(EngineEvent.RuntimeFailed, STOPPED, ERROR),
        Triple(readyInfo, STARTING, READY),
        Triple(lockedInfo, STARTING, LOCKED),
        Triple(readyInfo, SYNCING, READY),
        Triple(readyInfo, NEEDS_WALLET, READY),
        Triple(readyInfo, LOCKED, READY),
        Triple(lockedInfo, READY, LOCKED),
        Triple(EngineEvent.RestoreRequested, NEEDS_WALLET, RESTORING),
        Triple(EngineEvent.RestoreRequested, LOCKED, RESTORING),
        Triple(EngineEvent.WalletBecameReady, RESTORING, READY),
        Triple(EngineEvent.WalletBecameReady, SYNCING, READY),
        Triple(EngineEvent.RestoreFailedWalletUp, RESTORING, READY),
        Triple(EngineEvent.RestoreFailedWalletDown, RESTORING, NEEDS_WALLET),
        Triple(EngineEvent.WalletAdoptionFailed, NEEDS_WALLET, ERROR),
        Triple(EngineEvent.WalletAdoptionFailed, LOCKED, ERROR),
        Triple(EngineEvent.StreamLost, READY, ERROR),
        Triple(EngineEvent.SyncPollExhausted, SYNCING, ERROR),
        Triple(EngineEvent.BackgroundRefreshExhausted, READY, ERROR),
        Triple(EngineEvent.StopCompleted, STOPPING, STOPPED),
        Triple(EngineEvent.StopFailed, STOPPING, ERROR),
    )

    @Test
    fun table() {
        for ((event, from, to) in table) {
            assertEquals(to, PhaseMachine.transition(from, event), "$event: $from")
        }
    }

    @Test
    fun runtimeStoppedWinsExceptFromError() {
        for (phase in RuntimePhase.entries) {
            assertEquals(if (phase == ERROR) ERROR else STOPPED, PhaseMachine.transition(phase, EngineEvent.RuntimeStopped))
        }
    }

    @Test
    fun startRequestedLeavesEveryPhaseButStopping() {
        for (phase in RuntimePhase.entries) {
            assertEquals(if (phase == STOPPING) STOPPING else STARTING, PhaseMachine.transition(phase, EngineEvent.StartRequested))
        }
    }

    @Test
    fun stopRequested() {
        for (phase in RuntimePhase.entries) {
            val expected = if (phase == STOPPING || phase == STOPPED) phase else STOPPING
            assertEquals(expected, PhaseMachine.transition(phase, EngineEvent.StopRequested))
        }
    }

    @Test
    fun infoIsIgnoredWhileRestoring() {
        assertEquals(RESTORING, PhaseMachine.transition(RESTORING, lockedInfo))
        assertEquals(RESTORING, PhaseMachine.transition(RESTORING, readyInfo))
    }

    @Test
    fun everyOtherPairIsIdentity() {
        val special = setOf(EngineEvent.RuntimeStopped, EngineEvent.StartRequested, EngineEvent.StopRequested)
        val events = listOf(
            EngineEvent.RuntimeReady, EngineEvent.RuntimeFailed, EngineEvent.StartFailed, readyInfo,
            EngineEvent.RestoreRequested, EngineEvent.WalletBecameReady, EngineEvent.RestoreFailedWalletUp,
            EngineEvent.RestoreFailedWalletDown, EngineEvent.WalletAdoptionFailed, EngineEvent.StreamLost,
            EngineEvent.SyncPollExhausted, EngineEvent.BackgroundRefreshExhausted, EngineEvent.StopCompleted,
            EngineEvent.StopFailed,
        )
        for (event in events) {
            if (event in special) continue
            for (phase in RuntimePhase.entries) {
                if (table.any { (e, from) -> e == event && from == phase }) continue
                assertEquals(phase, PhaseMachine.transition(phase, event), "$event from $phase")
            }
        }
    }
}
