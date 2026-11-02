package engineering.lightning.wavelength

import engineering.lightning.wavelength.internal.Redaction
import engineering.lightning.wavelength.internal.WireJson
import engineering.lightning.wavelength.model.Balance
import engineering.lightning.wavelength.model.CreateWalletResult
import engineering.lightning.wavelength.model.DepositResult
import engineering.lightning.wavelength.model.Entry
import engineering.lightning.wavelength.model.ExitResult
import engineering.lightning.wavelength.model.ExitStatusResult
import engineering.lightning.wavelength.model.ExitSummaryResult
import engineering.lightning.wavelength.model.ExternalSeedWalletOpenResult
import engineering.lightning.wavelength.model.GetExitPlanResult
import engineering.lightning.wavelength.model.Info
import engineering.lightning.wavelength.model.ListResult
import engineering.lightning.wavelength.model.OpenWalletResult
import engineering.lightning.wavelength.model.PrepareSendResult
import engineering.lightning.wavelength.model.ReceiveResult
import engineering.lightning.wavelength.model.SendResult
import engineering.lightning.wavelength.model.Status
import engineering.lightning.wavelength.model.SweepWalletResult
import engineering.lightning.wavelength.model.UnlockWalletResult
import engineering.lightning.wavelength.wire.DepositRequest
import engineering.lightning.wavelength.wire.ExitStatusRequest
import engineering.lightning.wavelength.wire.ExitSummaryRequest
import engineering.lightning.wavelength.wire.ExternalSeedWalletStartRequest
import engineering.lightning.wavelength.wire.GetExitPlanRequest
import engineering.lightning.wavelength.wire.MobileConfig
import engineering.lightning.wavelength.wire.MobileReceiveRequest
import engineering.lightning.wavelength.wire.OpenWalletFromPasskeyRequest
import engineering.lightning.wavelength.wire.PrepareSendRequest
import engineering.lightning.wavelength.wire.SendPreparedRequest
import engineering.lightning.wavelength.wire.SubscribeRequest
import engineering.lightning.wavelength.wire.UnlockWalletRequest
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import engineering.lightning.wavelength.wire.CreateWalletRequest as WireCreateWalletRequest
import engineering.lightning.wavelength.wire.ExitRequest as WireExitRequest
import engineering.lightning.wavelength.wire.ListRequest as WireListRequest
import engineering.lightning.wavelength.wire.SweepWalletRequest as WireSweepWalletRequest

/**
 * [ExternalSeedWalletClient] over a [FacadeBindings]: all of the SDK's
 * behaviour, independent of platform.
 *
 * - Start, stop, and external-seed starts run one at a time under a lifecycle
 *   mutex (the facade requires typed SDKs to serialise them).
 * - Blocking binding calls run on [dispatcher]; each open activity stream
 *   holds one of its threads while it waits for the next entry.
 * - Binding errors become typed [WavelengthException]s via [ErrorClassifier].
 *
 * Create one per process: `wavelength-embedded`'s `Wavelength.client()`
 * returns the process-wide instance.
 */
public class FacadeWavelengthClient(
    private val bindings: FacadeBindings,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(DEFAULT_PARALLELISM),
    /** Logs request payloads (secrets redacted) at debug level. Off by default. */
    private val logPayloads: Boolean = false,
) : ExternalSeedWalletClient {

    private val lifecycleMutex = Mutex()

    private val _lifecycle = MutableStateFlow(
        if (bindings.isRunning()) RuntimeLifecycle.RUNNING else RuntimeLifecycle.STOPPED,
    )
    override val lifecycle: StateFlow<RuntimeLifecycle> = _lifecycle.asStateFlow()

    private val _events = MutableSharedFlow<WavelengthEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<WavelengthEvent> = _events.asSharedFlow()

    // --- lifecycle -----------------------------------------------------------

    override suspend fun start(config: RuntimeConfig): Info {
        config.validate()
        lifecycleMutex.withLock {
            if (!bindings.isRunning()) {
                val json = WireJson.encodeToString(MobileConfig.serializer(), config.toWire())
                log(LogLevel.INFO, "starting wavelength runtime (${config.network.wire})")
                transition(RuntimeLifecycle.STARTING) {
                    blocking { bindings.start(json) }
                }
                _events.tryEmit(WavelengthEvent.RuntimeReady)
            } else {
                _lifecycle.value = RuntimeLifecycle.RUNNING
            }
        }
        return getInfo()
    }

    override suspend fun startExternalSeedWallet(
        config: RuntimeConfig,
        request: ExternalSeedWalletRequest,
    ): ExternalSeedWalletOpenResult {
        config.validate()
        request.validate()
        if (config.dataDir.isNullOrBlank()) {
            throw WavelengthException.InvalidConfig("startExternalSeedWallet requires a final nonempty dataDir")
        }
        val envelope = ExternalSeedWalletStartRequest(
            config = WireJson.encodeToJsonElement(MobileConfig.serializer(), config.toWire()),
            seedEntropy = ByteString.of(request.seedEntropy),
            expectedIdentityPubKey = request.expectedIdentityPubKey,
            recoverState = request.recoverState.takeIf { it },
            recoveryWindow = request.recoveryWindow,
        )
        val bytes = WireJson.encodeToString(ExternalSeedWalletStartRequest.serializer(), envelope).encodeToByteArray()
        return lifecycleMutex.withLock {
            if (bindings.isRunning()) {
                throw WavelengthException.RuntimeActive("a wavelength runtime is already active; stop it first")
            }
            val result = try {
                transition(RuntimeLifecycle.STARTING) {
                    blocking { bindings.startExternalSeedWallet(bytes) }
                }
            } finally {
                bytes.fill(0)
            }
            _events.tryEmit(WavelengthEvent.RuntimeReady)
            decode(ExternalSeedWalletOpenResult.serializer(), result)
        }
    }

    override suspend fun stop() {
        lifecycleMutex.withLock {
            if (!bindings.isRunning() && _lifecycle.value == RuntimeLifecycle.STOPPED) return
            _lifecycle.value = RuntimeLifecycle.STOPPING
            try {
                blocking { bindings.stop() }
            } finally {
                _lifecycle.value = if (bindings.isRunning()) RuntimeLifecycle.RUNNING else RuntimeLifecycle.STOPPED
            }
            _events.tryEmit(WavelengthEvent.RuntimeStopped)
        }
    }

    override fun isRunning(): Boolean = bindings.isRunning()

    /** Runs a boot in [state], landing on RUNNING or (on failure) STOPPED. */
    private suspend fun <T> transition(state: RuntimeLifecycle, block: suspend () -> T): T {
        _lifecycle.value = state
        try {
            return block().also { _lifecycle.value = RuntimeLifecycle.RUNNING }
        } catch (e: Throwable) {
            _lifecycle.value = if (bindings.isRunning()) RuntimeLifecycle.RUNNING else RuntimeLifecycle.STOPPED
            throw e
        }
    }

    // --- reads ---------------------------------------------------------------

    override suspend fun getInfo(): Info = call(FacadeVerb.GET_INFO, Info.serializer())

    override suspend fun status(): Status = call(FacadeVerb.STATUS, Status.serializer())

    override suspend fun balance(): Balance = call(FacadeVerb.BALANCE, Balance.serializer())

    override suspend fun confirmedBalanceSat(): Long = blocking { bindings.confirmedBalanceSat() }

    override suspend fun pendingInboundSat(): Long = blocking { bindings.pendingInboundSat() }

    override suspend fun walletReady(): Boolean = blocking { bindings.walletReady() }

    // --- wallet setup --------------------------------------------------------

    override suspend fun createWallet(request: CreateWalletRequest): CreateWalletResult {
        if (request.password.size < MIN_PASSWORD_BYTES) {
            throw WavelengthException.InvalidArgument("password must be at least $MIN_PASSWORD_BYTES bytes")
        }
        request.recoveryWindow?.let {
            if (it !in 0..0xFFFF_FFFFL) throw WavelengthException.InvalidArgument("recoveryWindow must fit in uint32")
        }
        val wire = WireCreateWalletRequest(
            mnemonic = request.mnemonic,
            seedPassphrase = request.seedPassphrase?.let(ByteString::of) ?: ByteString.EMPTY,
            walletPassword = ByteString.of(request.password),
            recoverState = request.recoverState,
            recoveryWindow = request.recoveryWindow ?: 0,
        )
        return call(FacadeVerb.CREATE_WALLET, WireCreateWalletRequest.serializer(), wire, CreateWalletResult.serializer())
    }

    override suspend fun unlockWallet(password: ByteArray): UnlockWalletResult {
        if (password.isEmpty()) throw WavelengthException.InvalidArgument("password must not be empty")
        return call(
            FacadeVerb.UNLOCK_WALLET,
            UnlockWalletRequest.serializer(),
            UnlockWalletRequest(walletPassword = ByteString.of(password)),
            UnlockWalletResult.serializer(),
        )
    }

    override suspend fun openWalletFromPasskey(prfOutput: ByteArray): OpenWalletResult {
        if (prfOutput.size != PRF_OUTPUT_SIZE) {
            throw WavelengthException.InvalidArgument("passkey PRF output must be $PRF_OUTPUT_SIZE bytes")
        }
        return call(
            FacadeVerb.OPEN_WALLET_FROM_PASSKEY,
            OpenWalletFromPasskeyRequest.serializer(),
            OpenWalletFromPasskeyRequest(ByteString.of(prfOutput).hex()),
            OpenWalletResult.serializer(),
        )
    }

    // --- receive and send ----------------------------------------------------

    override suspend fun deposit(amountSatHint: Long): DepositResult {
        requireNonNegative("amountSatHint", amountSatHint)
        return call(FacadeVerb.DEPOSIT, DepositRequest.serializer(), DepositRequest(amountSatHint), DepositResult.serializer())
    }

    override suspend fun receive(request: ReceiveRequest): ReceiveResult {
        if (request.amountSat <= 0) throw WavelengthException.InvalidArgument("amountSat must be positive")
        request.timeoutSeconds?.let {
            if (it !in 1..MAX_RECEIVE_TIMEOUT_SECONDS) {
                throw WavelengthException.InvalidArgument("timeoutSeconds must be in 1..$MAX_RECEIVE_TIMEOUT_SECONDS")
            }
        }
        return call(
            FacadeVerb.RECEIVE,
            MobileReceiveRequest.serializer(),
            MobileReceiveRequest(request.amountSat, request.memo.ifEmpty { null }, request.timeoutSeconds),
            ReceiveResult.serializer(),
        )
    }

    override suspend fun prepareSend(request: SendRequest): PrepareSendResult {
        val wire = when (request) {
            is SendRequest.Invoice -> {
                if (request.invoice.isBlank()) throw WavelengthException.InvalidArgument("invoice must not be blank")
                PrepareSendRequest(
                    invoice = request.invoice.trim(),
                    amountSat = request.amountSat ?: 0,
                    note = request.note,
                    maxFeeSat = request.maxFeeSat ?: 0,
                    maxCreditSat = request.maxCreditSat ?: 0,
                    maxCreditTopupSat = request.maxCreditTopupSat ?: 0,
                )
            }
            is SendRequest.Onchain -> {
                if (request.address.isBlank()) throw WavelengthException.InvalidArgument("address must not be blank")
                PrepareSendRequest(
                    onchainAddress = request.address.trim(),
                    amountSat = request.amountSat ?: 0,
                    note = request.note,
                    maxFeeSat = request.maxFeeSat ?: 0,
                    sweepAll = request.sweepAll,
                )
            }
        }
        listOf(wire.amountSat, wire.maxFeeSat, wire.maxCreditSat, wire.maxCreditTopupSat).forEach {
            requireNonNegative("amount", it)
        }
        return call(FacadeVerb.PREPARE_SEND, PrepareSendRequest.serializer(), wire, PrepareSendResult.serializer())
    }

    override suspend fun sendPrepared(prepared: PrepareSendResult): SendOutcome {
        val result = call(
            FacadeVerb.SEND_PREPARED,
            SendPreparedRequest.serializer(),
            SendPreparedRequest(prepared.sendIntentId),
            SendResult.serializer(),
        )
        val hash = result.entry.progress?.paymentHash?.ifEmpty { null }
            ?: prepared.paymentHash.ifEmpty { null }
        return SendOutcome(result.entry, result.actualAmountSat, hash)
    }

    override suspend fun send(request: SendRequest): SendOutcome = sendPrepared(prepareSend(request))

    // --- history ---------------------------------------------------------------

    override suspend fun list(request: ListRequest): ListResult {
        requireNonNegative("limit", request.limit)
        requireNonNegative("offset", request.offset)
        val wire = WireListRequest(
            view = request.view,
            pendingOnly = request.pendingOnly,
            kinds = request.kinds.toList(),
            limit = request.limit,
            offset = request.offset,
            cursor = request.cursor.orEmpty(),
        )
        return call(FacadeVerb.LIST, WireListRequest.serializer(), wire, ListResult.serializer())
    }

    override fun activityEvents(options: ActivityOptions): Flow<ActivityEvent> = channelFlow {
        options.cursor?.let {
            if (it < 0) throw WavelengthException.InvalidCursor("activity cursor must not be negative")
        }
        val request = encode(
            SubscribeRequest.serializer(),
            SubscribeRequest(options.includeExisting, options.kinds.toList(), options.cursor ?: 0),
        )
        val subscription = blocking { bindings.subscribe(request) }
        var closedByCollector = false
        send(ActivityEvent.Opened)

        val pump = launch(dispatcher) {
            try {
                while (true) {
                    val next = try {
                        subscription.next()
                    } catch (e: Throwable) {
                        throw ErrorClassifier.classify(e)
                    } ?: break
                    send(ActivityEvent.EntryReceived(decode(Entry.serializer(), next)))
                }
                // Report only ends the app did not cause: not a cancelled
                // collector and not a stop() it requested.
                if (!closedByCollector && _lifecycle.value == RuntimeLifecycle.RUNNING) {
                    _events.tryEmit(WavelengthEvent.ActivityStreamEnded(null))
                }
            } catch (e: WavelengthException) {
                _events.tryEmit(WavelengthEvent.ActivityStreamEnded(e))
                throw e
            }
        }

        // The pump may block inside next(); closing the subscription is what
        // unblocks it, so it must happen before (not instead of) cancellation.
        pump.invokeOnCompletion { cause -> if (cause == null) channel.close() else channel.close(cause) }
        awaitClose {
            closedByCollector = true
            subscription.close()
            pump.cancel()
        }
    }

    // --- exits -----------------------------------------------------------------

    override suspend fun exit(request: ExitRequest): ExitResult {
        if (request.outpoint.isBlank()) throw WavelengthException.InvalidArgument("outpoint must not be blank")
        val wire = when (request) {
            is ExitRequest.Cooperative -> WireExitRequest(outpoint = request.outpoint, destination = request.destination.orEmpty())
            is ExitRequest.ForceUnroll -> WireExitRequest(outpoint = request.outpoint, forceUnrollAck = ExitRequest.FORCE_UNROLL_ACK)
        }
        return call(FacadeVerb.EXIT, WireExitRequest.serializer(), wire, ExitResult.serializer())
    }

    override suspend fun exitStatus(outpoint: String, detailed: Boolean): ExitStatusResult = call(
        FacadeVerb.EXIT_STATUS,
        ExitStatusRequest.serializer(),
        ExitStatusRequest(outpoint, detailed),
        ExitStatusResult.serializer(),
    )

    override suspend fun exitSummary(): ExitSummaryResult =
        call(FacadeVerb.EXIT_SUMMARY, ExitSummaryRequest.serializer(), ExitSummaryRequest(), ExitSummaryResult.serializer())

    override suspend fun getExitPlan(outpoints: List<String>, confTarget: Long?): GetExitPlanResult {
        confTarget?.let { requireNonNegative("confTarget", it) }
        return call(
            FacadeVerb.GET_EXIT_PLAN,
            GetExitPlanRequest.serializer(),
            GetExitPlanRequest(outpoints, confTarget ?: 0),
            GetExitPlanResult.serializer(),
        )
    }

    override suspend fun sweepWallet(request: SweepWalletRequest): SweepWalletResult {
        if (request.destinationAddress.isBlank()) {
            throw WavelengthException.InvalidArgument("destinationAddress must not be blank")
        }
        val wire = WireSweepWalletRequest(
            destinationAddress = request.destinationAddress,
            broadcast = request.broadcast,
            feeRateSatPerVByte = request.feeRateSatPerVByte ?: 0,
            confTarget = request.confTarget ?: 0,
        )
        return call(FacadeVerb.SWEEP_WALLET, WireSweepWalletRequest.serializer(), wire, SweepWalletResult.serializer())
    }

    override suspend fun callFacade(verb: FacadeVerb, requestJson: String): String =
        invoke(verb, requestJson.encodeToByteArray()).decodeToString()

    // --- plumbing ----------------------------------------------------------------

    private suspend fun <Res> call(verb: FacadeVerb, response: DeserializationStrategy<Res>): Res =
        decode(response, invoke(verb, ByteArray(0)))

    private suspend fun <Req, Res> call(
        verb: FacadeVerb,
        requestSerializer: SerializationStrategy<Req>,
        request: Req,
        response: DeserializationStrategy<Res>,
    ): Res {
        val bytes = encode(requestSerializer, request)
        try {
            return decode(response, invoke(verb, bytes))
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun invoke(verb: FacadeVerb, request: ByteArray): ByteArray {
        if (logPayloads) log(LogLevel.DEBUG, "${verb.wireName} ${Redaction.redact(request.decodeToString())}")
        return blocking { bindings.call(verb, request) }
    }

    /** Runs a blocking binding call on [dispatcher], classifying failures. */
    private suspend fun <T> blocking(block: () -> T): T = withContext(dispatcher) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw ErrorClassifier.classify(e)
        }
    }

    private fun <T> encode(serializer: SerializationStrategy<T>, value: T): ByteArray =
        WireJson.encodeToString(serializer, value).encodeToByteArray()

    private fun <T> decode(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T = try {
        WireJson.decodeFromString(deserializer, bytes.decodeToString())
    } catch (e: SerializationException) {
        throw WavelengthException.Daemon("unexpected response from the wavelength runtime: ${e.message}", cause = e)
    } catch (e: IllegalArgumentException) {
        throw WavelengthException.Daemon("unexpected response from the wavelength runtime: ${e.message}", cause = e)
    }

    private fun log(level: LogLevel, message: String) {
        _events.tryEmit(WavelengthEvent.Log(level, message))
    }

    private fun requireNonNegative(name: String, value: Long) {
        if (value < 0) throw WavelengthException.InvalidArgument("$name must not be negative")
    }

    public companion object {
        /** Threads for blocking calls: boot, a few RPCs, and open streams. */
        public const val DEFAULT_PARALLELISM: Int = 16

        /** The facade's maximum receive deadline. */
        public const val MAX_RECEIVE_TIMEOUT_SECONDS: Long = 300

        /** The daemon's minimum wallet password length. */
        public const val MIN_PASSWORD_BYTES: Int = 8

        /** WebAuthn PRF output size. */
        public const val PRF_OUTPUT_SIZE: Int = 32
    }
}
