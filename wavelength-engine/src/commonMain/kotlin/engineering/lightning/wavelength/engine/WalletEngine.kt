package engineering.lightning.wavelength.engine

import engineering.lightning.wavelength.CreateWalletRequest
import engineering.lightning.wavelength.DescriptorWalletClient
import engineering.lightning.wavelength.ExitBatchEvent
import engineering.lightning.wavelength.ExitBatchOptions
import engineering.lightning.wavelength.ExitBatchResult
import engineering.lightning.wavelength.ExitRequest
import engineering.lightning.wavelength.ListRequest
import engineering.lightning.wavelength.ListWalletDescriptorsRequest
import engineering.lightning.wavelength.LogLevel
import engineering.lightning.wavelength.ReceiveRequest
import engineering.lightning.wavelength.RuntimeConfig
import engineering.lightning.wavelength.SendOutcome
import engineering.lightning.wavelength.SendRequest
import engineering.lightning.wavelength.SweepWalletRequest
import engineering.lightning.wavelength.WavelengthClient
import engineering.lightning.wavelength.WavelengthEvent
import engineering.lightning.wavelength.WavelengthException
import engineering.lightning.wavelength.exitBatch
import engineering.lightning.wavelength.model.Balance
import engineering.lightning.wavelength.model.CreateWalletResult
import engineering.lightning.wavelength.model.DepositResult
import engineering.lightning.wavelength.model.ExitResult
import engineering.lightning.wavelength.model.ExitStatusResult
import engineering.lightning.wavelength.model.ExitSummaryResult
import engineering.lightning.wavelength.model.GetExitPlanResult
import engineering.lightning.wavelength.model.Info
import engineering.lightning.wavelength.model.ListResult
import engineering.lightning.wavelength.model.ListView
import engineering.lightning.wavelength.model.ListWalletDescriptorsResult
import engineering.lightning.wavelength.model.OpenWalletResult
import engineering.lightning.wavelength.model.PrepareSendResult
import engineering.lightning.wavelength.model.ReceiveResult
import engineering.lightning.wavelength.model.SweepWalletResult
import engineering.lightning.wavelength.model.UnlockWalletResult
import engineering.lightning.wavelength.model.WalletState
import engineering.lightning.wavelength.walletReady
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An observable wallet: [snapshot] is everything a wallet UI renders (phase,
 * info, balance, activity, recovery, logs), kept current by background work
 * the engine owns.
 *
 * - While READY it holds an activity stream open (reconnecting with backoff
 *   and resuming from its cursor) and refreshes after activity, probing again
 *   until the balance settles.
 * - While SYNCING it polls until the wallet is ready; while RESTORING it polls
 *   until the restored wallet is usable.
 * - It retains send preimages, which the daemon reports only once.
 *
 * A port of the TypeScript SDK's `WalletEngine` with the same phases and
 * timings, so web, React Native, and Kotlin apps behave alike. Call [close]
 * (or cancel [scope]) when done.
 *
 * @param scope Parent scope for background work, e.g. a `viewModelScope`.
 * @param config Default config for [start].
 * @param autoStart Starts with [config] immediately.
 */
public class WalletEngine(
    public val client: WavelengthClient,
    scope: CoroutineScope,
    private val config: RuntimeConfig? = null,
    autoStart: Boolean = false,
) {
    private val scope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val lock = SynchronizedObject()
    private val _snapshot = MutableStateFlow(WalletSnapshot())

    /** The current state; a new value per change. */
    public val snapshot: StateFlow<WalletSnapshot> = _snapshot.asStateFlow()

    private var closed = false
    private var deliberateStops = 0
    private var refreshFailures = 0
    private val refreshMutex = Mutex()
    private var restore: CompletableDeferred<Info>? = null

    private val stream = ActivityStream(
        scope = this.scope,
        client = client,
        onActivity = { reconciler.trigger() },
        onReconcile = { reconciler.trigger() },
        onDead = { error -> if (phase == RuntimePhase.READY) dispatch(EngineEvent.StreamLost) { it.copy(error = error) } },
    )

    private val reconciler: SettleReconciler = SettleReconciler(
        scope = this.scope,
        refresh = { backgroundRefresh() },
        baseline = { _snapshot.value.balance },
    )

    private val syncPoller = Poller(
        scope = this.scope,
        interval = EngineConstants.SYNC_POLL,
        failureLimit = EngineConstants.SYNC_POLL_FAILURE_LIMIT,
        tick = { refresh() },
        onExhausted = { error ->
            if (phase == RuntimePhase.SYNCING) dispatch(EngineEvent.SyncPollExhausted) { it.copy(error = error) }
        },
    )

    private val restorePoller = Poller(
        scope = this.scope,
        interval = EngineConstants.RESTORE_POLL,
        immediate = true,
        tick = { restoreTick() },
    )

    private val phase: RuntimePhase get() = _snapshot.value.phase

    init {
        // The embedded runtime needs no asset loading, so it is ready at once.
        dispatch(EngineEvent.RuntimeReady)
        // Subscribe before returning: client events are not replayed.
        this.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.events.collect(::onClientEvent)
        }
        if (autoStart && config != null) {
            this.scope.launch {
                try {
                    start()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The failure is on the snapshot.
                }
            }
        }
    }

    // --- lifecycle ---------------------------------------------------------------

    /** Starts the runtime with [config] (or the engine's default) and loads wallet state. */
    public suspend fun start(config: RuntimeConfig? = null): Info {
        checkOpen()
        val cfg = config ?: this.config ?: throw IllegalStateException("start() needs a RuntimeConfig")
        check(phase != RuntimePhase.STOPPING) { "cannot start while the runtime is stopping" }
        refreshFailures = 0
        val stopsBefore = deliberateStops
        dispatch(EngineEvent.StartRequested) { it.copy(error = null) }
        try {
            val info = client.start(cfg)
            if (deliberateStops != stopsBefore) return info
            dispatch(EngineEvent.InfoReceived(info)) { it.copy(info = info) }
            try {
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A locked or new wallet cannot report a balance yet.
            }
            return info
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (deliberateStops != stopsBefore) {
                appendLog(WavelengthEvent.Log(LogLevel.DEBUG, "start failed but a deliberate stop took precedence: ${e.message}"))
                throw e
            }
            dispatch(EngineEvent.StartFailed) { it.copy(error = e) }
            throw e
        }
    }

    /** Stops the runtime and clears wallet state from the snapshot. */
    public suspend fun stop() {
        checkOpen()
        deliberateStops++
        dispatch(EngineEvent.StopRequested)
        try {
            client.stop()
            dispatch(EngineEvent.StopCompleted) { it.copy(info = null, balance = null, activity = emptyList(), error = null) }
            rejectRestore(IllegalStateException("the runtime stopped during the restore"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dispatch(EngineEvent.StopFailed) { it.copy(error = e) }
            throw e
        }
    }

    /** Re-reads info, balance, and activity. */
    public suspend fun refresh() {
        checkOpen()
        fetchAll()
    }

    // --- wallet setup --------------------------------------------------------------

    /**
     * Creates a wallet. If it fails having left a database behind, as a
     * watch-only wallet does when its signer holds other keys, the phase moves to
     * LOCKED: retry with [unlockWallet]. A signer that could not be reached leaves
     * no database, so the phase stays and creating again works.
     */
    public suspend fun createWallet(request: CreateWalletRequest): CreateWalletResult {
        checkOpen()
        val result = try {
            client.createWallet(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            followLockedWallet(readInfoOrNull())
            throw e
        }
        adoptInfo()
        kickRefresh()
        return result
    }

    /**
     * Restores from [request]'s mnemonic, descriptors, or watch-only bundle or
     * manifest, and returns once the wallet is usable. With `recoverState`, the
     * recovery scan continues in the background; follow it in
     * [WalletSnapshot.recovery]. A failure that leaves a database behind moves the
     * phase to LOCKED, as in [createWallet].
     *
     * @throws WavelengthException.InvalidArgument unless exactly one source is set.
     */
    public suspend fun restoreWallet(request: CreateWalletRequest): Info {
        checkOpen()
        val sources = listOf(
            request.mnemonic.isNotEmpty(),
            request.descriptors.isNotEmpty(),
            request.watchOnlyBundle != null,
            request.watchOnlyManifest != null,
        ).count { it }
        if (sources != 1) {
            throw WavelengthException.InvalidArgument(
                "a restore needs exactly one of a mnemonic, descriptors, or a watch-only bundle or manifest",
            )
        }
        val deferred = synchronized(lock) {
            check(restore?.isCompleted != false) { "a restore is already in flight" }
            CompletableDeferred<Info>().also { restore = it }
        }
        val tracking = request.recoverState
        dispatch(EngineEvent.RestoreRequested) {
            it.copy(error = null, recovery = if (tracking) RecoveryState.Restoring else RecoveryState.Idle)
        }
        scope.launch {
            try {
                val result = client.createWallet(request)
                if (closed) return@launch
                if (tracking) update { it.copy(recovery = RecoveryState.Done(result)) }
                settleRestore(adoptWalletUp())
                kickRefresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (closed) return@launch
                val probe = readInfoOrNull()
                if (probe != null && probe.walletReady) {
                    dispatch(EngineEvent.RestoreFailedWalletUp) {
                        it.copy(
                            info = probe,
                            recovery = if (tracking) RecoveryState.Failed(e, walletUsable = true) else RecoveryState.Idle,
                        )
                    }
                    settleRestore(probe)
                    kickRefresh()
                } else {
                    dispatch(EngineEvent.RestoreFailedWalletDown) {
                        it.copy(recovery = RecoveryState.Failed(e, walletUsable = false))
                    }
                    followLockedWallet(probe)
                    rejectRestore(e)
                }
            }
        }
        return deferred.await()
    }

    /** Clears a finished or failed [WalletSnapshot.recovery] (a running one stays). */
    public fun acknowledgeRecovery() {
        update { if (it.recovery == RecoveryState.Restoring) it else it.copy(recovery = RecoveryState.Idle) }
    }

    /**
     * Returns the open wallet's `tr` and `wpkh` descriptors: public by default,
     * or private when [request] resupplies the wallet's mnemonic or root
     * descriptors. The engine keeps no copy.
     *
     * @throws WavelengthException.UnsupportedFacadeMethod if [client] is not a
     *   [DescriptorWalletClient].
     */
    public suspend fun listWalletDescriptors(
        request: ListWalletDescriptorsRequest = ListWalletDescriptorsRequest(),
    ): ListWalletDescriptorsResult {
        checkOpen()
        val descriptorClient = client as? DescriptorWalletClient
            ?: throw WavelengthException.UnsupportedFacadeMethod("this client cannot list wallet descriptors")
        return descriptorClient.listWalletDescriptors(request)
    }

    public suspend fun unlockWallet(password: ByteArray): UnlockWalletResult {
        checkOpen()
        val result = client.unlockWallet(password)
        adoptInfo()
        kickRefresh()
        return result
    }

    public suspend fun openWalletFromPasskey(prfOutput: ByteArray): OpenWalletResult {
        checkOpen()
        val result = client.openWalletFromPasskey(prfOutput)
        adoptInfo()
        kickRefresh()
        return result
    }

    // --- payments ------------------------------------------------------------------

    public suspend fun deposit(amountSatHint: Long = 0): DepositResult =
        client.deposit(amountSatHint).also { kickRefresh() }

    public suspend fun receive(request: ReceiveRequest): ReceiveResult =
        client.receive(request).also { kickRefresh() }

    public suspend fun prepareSend(request: SendRequest): PrepareSendResult = client.prepareSend(request)

    public suspend fun sendPrepared(prepared: PrepareSendResult): SendOutcome =
        client.sendPrepared(prepared).also { kickRefresh() }

    public suspend fun send(request: SendRequest): SendOutcome = client.send(request).also { kickRefresh() }

    // --- exits ---------------------------------------------------------------------

    public suspend fun exit(request: ExitRequest): ExitResult = client.exit(request).also { kickRefresh() }

    public suspend fun exitStatus(outpoint: String, detailed: Boolean = false): ExitStatusResult =
        client.exitStatus(outpoint, detailed)

    public suspend fun exitSummary(): ExitSummaryResult = client.exitSummary()

    public suspend fun getExitPlan(outpoints: List<String>, confTarget: Long? = null): GetExitPlanResult =
        client.getExitPlan(outpoints, confTarget)

    public suspend fun sweepWallet(request: SweepWalletRequest): SweepWalletResult =
        client.sweepWallet(request).also { if (request.broadcast) kickRefresh() }

    /** [exitBatch] that also refreshes the snapshot after each started exit. */
    public suspend fun exitBatch(options: ExitBatchOptions, onEvent: (ExitBatchEvent) -> Unit = {}): ExitBatchResult =
        client.exitBatch(options) { event ->
            if (event is ExitBatchEvent.Started) kickRefresh()
            onEvent(event)
        }

    /** [WavelengthClient.list], with retained preimages restored to activity entries. */
    public suspend fun list(request: ListRequest = ListRequest()): ListResult {
        val result = client.list(request)
        val activity = result.activity ?: return result
        return result.copy(activity = activity.copy(entries = restorePreimages(activity.entries, stream.preimages())))
    }

    public fun clearLogs() {
        update { it.copy(logs = emptyList()) }
    }

    /** Stops background work. The runtime itself keeps running; call [stop] first to end it. */
    public fun close() {
        closed = true
        stream.stop()
        reconciler.cancel()
        syncPoller.stop()
        restorePoller.stop()
        rejectRestore(IllegalStateException("the engine was closed during the restore"))
        scope.cancel()
    }

    // --- internals ---------------------------------------------------------------------

    private fun checkOpen() {
        check(!closed) { "the engine has been closed" }
    }

    private fun onClientEvent(event: WavelengthEvent) {
        when (event) {
            WavelengthEvent.RuntimeReady -> dispatch(EngineEvent.RuntimeReady)
            WavelengthEvent.RuntimeStopped -> {
                // Events arrive asynchronously: a stop that a restart has
                // already superseded must not knock the new runtime's phase.
                if (client.isRunning()) return
                dispatch(EngineEvent.RuntimeStopped) { it.copy(info = null, balance = null, activity = emptyList()) }
                rejectRestore(IllegalStateException("the runtime stopped during the restore"))
            }
            is WavelengthEvent.Log -> appendLog(event)
            // The engine runs its own stream and reconnects it itself.
            is WavelengthEvent.ActivityStreamEnded -> Unit
        }
    }

    private fun appendLog(log: WavelengthEvent.Log) {
        update { it.copy(logs = (it.logs + log).takeLast(EngineConstants.MAX_LOGS)) }
    }

    private fun update(patch: (WalletSnapshot) -> WalletSnapshot): Unit = synchronized(lock) {
        _snapshot.value = patch(_snapshot.value)
    }

    /** Applies [event] to the phase machine and [patch] to the snapshot, then aligns background work. */
    private fun dispatch(event: EngineEvent, patch: (WalletSnapshot) -> WalletSnapshot = { it }): Unit = synchronized(lock) {
        val prev = _snapshot.value.phase
        val next = PhaseMachine.transition(prev, event)
        _snapshot.value = patch(_snapshot.value).copy(phase = next)
        if (next != prev && !closed) reconcileProcesses(next)
    }

    private fun reconcileProcesses(phase: RuntimePhase) {
        if (phase == RuntimePhase.READY) {
            stream.start()
        } else {
            stream.stop()
            reconciler.cancel()
        }
        if (phase == RuntimePhase.SYNCING) syncPoller.start() else syncPoller.stop()
        if (phase == RuntimePhase.RESTORING) restorePoller.start() else restorePoller.stop()
    }

    private suspend fun fetchAll(): Balance? {
        val (info, balance, rows) = coroutineScope {
            val info = async { client.getInfo() }
            val balance = async { client.balance() }
            val rows = async { client.list(ListRequest(ListView.ACTIVITY)) }
            Triple(info.await(), balance.await(), rows.await())
        }
        val snap = _snapshot.value
        if (snap.phase == RuntimePhase.STOPPING || snap.phase == RuntimePhase.STOPPED) return snap.balance
        val entries = restorePreimages(rows.activity?.entries.orEmpty(), stream.preimages())
        var stable: Balance? = null
        dispatch(EngineEvent.InfoReceived(info)) {
            stable = stabilize(it.balance, balance)
            it.copy(
                info = stabilize(it.info, info),
                balance = stable,
                activity = stabilize(it.activity, entries),
            )
        }
        return stable
    }

    private suspend fun readInfoOrNull(): Info? = try {
        client.getInfo()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** Moves to LOCKED when a failed open left a database that only unlock can open. */
    private fun followLockedWallet(info: Info?) {
        if (info?.walletState == WalletState.LOCKED && !closed) {
            dispatch(EngineEvent.InfoReceived(info)) { it.copy(info = info) }
        }
    }

    private suspend fun adoptInfo(): Info? {
        repeat(EngineConstants.ADOPT_INFO_RETRIES) { attempt ->
            if (closed) return null
            try {
                val info = client.getInfo()
                dispatch(EngineEvent.InfoReceived(info)) { it.copy(info = info) }
                return info
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (attempt < EngineConstants.ADOPT_INFO_RETRIES - 1) delay(EngineConstants.ADOPT_INFO_RETRY)
            }
        }
        if (!closed) {
            dispatch(EngineEvent.WalletAdoptionFailed) {
                it.copy(error = IllegalStateException("the wallet was opened but the daemon stopped responding"))
            }
        }
        return null
    }

    /** Serialised refresh for background work; counts failures toward ERROR while READY. */
    private suspend fun backgroundRefresh(): RefreshResult = refreshMutex.withLock {
        try {
            val balance = fetchAll()
            refreshFailures = 0
            RefreshResult(ok = true, balance = balance)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            refreshFailures++
            if (refreshFailures >= EngineConstants.BACKGROUND_REFRESH_FAILURE_LIMIT && phase == RuntimePhase.READY) {
                dispatch(EngineEvent.BackgroundRefreshExhausted) {
                    it.copy(error = IllegalStateException("the wallet stopped responding to background refreshes"))
                }
            }
            RefreshResult(ok = false, balance = null)
        }
    }

    private fun kickRefresh() {
        if (!closed) scope.launch { backgroundRefresh() }
    }

    private suspend fun restoreTick() {
        val info = try {
            client.getInfo()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        if (info.walletReady) {
            dispatch(EngineEvent.WalletBecameReady) { it.copy(info = info) }
            settleRestore(info)
            kickRefresh()
        }
    }

    private suspend fun adoptWalletUp(): Info? {
        val info = try {
            client.getInfo()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        dispatch(EngineEvent.WalletBecameReady) { if (info != null) it.copy(info = info) else it }
        return info
    }

    private fun settleRestore(info: Info?) {
        val pending = synchronized(lock) { restore?.takeIf { !it.isCompleted } } ?: return
        val resolved = info ?: _snapshot.value.info
        if (resolved == null) {
            pending.completeExceptionally(IllegalStateException("the restored wallet came up but its info could not be read"))
        } else {
            pending.complete(resolved)
        }
    }

    private fun rejectRestore(error: Throwable) {
        synchronized(lock) { restore?.takeIf { !it.isCompleted } }?.completeExceptionally(error)
    }

    private fun <T : Any> stabilize(prev: T?, next: T): T = if (prev != null && prev == next) prev else next
}
