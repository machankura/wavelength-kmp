package engineering.lightning.wavelength

/**
 * The gomobile facade (`sdk/wavewalletdk/mobile` in wavelength) as seen from
 * Kotlin: the only part of the SDK that differs per platform.
 *
 * `wavelength-embedded` implements it over the Android `.aar` and the iOS
 * `.xcframework`; tests implement it with a fake. Every method may block (some
 * for seconds), so callers run them off the main thread. Failures are thrown as
 * any [Throwable] whose message is the Go error text; [FacadeWavelengthClient]
 * classifies them.
 *
 * One daemon runs per process, so there is one logical instance of this
 * interface per process.
 */
public interface FacadeBindings {
    /** Boots the daemon from a snake_case JSON config; blocks until serving. */
    public fun start(configJson: String)

    /** Atomically boots the daemon and imports or unlocks an external-seed wallet. */
    public fun startExternalSeedWallet(request: ByteArray): ByteArray

    /** Atomically boots the daemon and imports or unlocks a descriptor wallet. */
    public fun startDescriptorWallet(request: ByteArray): ByteArray

    /** Atomically boots the daemon and imports or unlocks a watch-only wallet. */
    public fun startWatchOnlyWallet(request: ByteArray): ByteArray

    /** Tears the daemon down. Idempotent. */
    public fun stop()

    /** True from the start of a boot until stop completes. Never blocks. */
    public fun isRunning(): Boolean

    /** Calls a JSON verb: [request] and the result are UTF-8 JSON. */
    public fun call(verb: FacadeVerb, request: ByteArray): ByteArray

    /** Opens an activity subscription. */
    public fun subscribe(request: ByteArray): FacadeSubscription

    public fun confirmedBalanceSat(): Long

    public fun pendingInboundSat(): Long

    public fun walletReady(): Boolean
}

/** A pull handle over the activity stream. */
public interface FacadeSubscription {
    /**
     * Blocks for the next entry's JSON. Returns null at a clean end of stream
     * (Go's `io.EOF`, also reported after [close] or a daemon stop) and throws
     * on a real stream error.
     */
    public fun next(): ByteArray?

    /** Cancels the subscription and unblocks a pending [next]. Idempotent. */
    public fun close()
}

/**
 * The facade's JSON verbs. [wireName] matches the TypeScript SDK's
 * `FacadeMethod` and the wasm `wavewalletdkCall` method names. Lifecycle verbs
 * (start, stop, and the atomic wallet starts) are deliberately absent: they go
 * through the typed, serialised methods on [WavelengthClient].
 */
public enum class FacadeVerb(public val wireName: String) {
    GET_INFO("getInfo"),
    STATUS("status"),
    BALANCE("balance"),
    CREATE_WALLET("createWallet"),
    UNLOCK_WALLET("unlockWallet"),
    OPEN_WALLET_FROM_PASSKEY("openWalletFromPasskey"),
    DEPOSIT("deposit"),
    RECEIVE("receive"),
    PREPARE_SEND("prepareSend"),
    SEND_PREPARED("sendPrepared"),
    LIST("list"),
    EXIT("exit"),
    EXIT_STATUS("exitStatus"),
    EXIT_SUMMARY("exitSummary"),
    GET_EXIT_PLAN("getExitPlan"),
    SWEEP_WALLET("sweepWallet"),
    LIST_WALLET_DESCRIPTORS("listWalletDescriptors"),
}
