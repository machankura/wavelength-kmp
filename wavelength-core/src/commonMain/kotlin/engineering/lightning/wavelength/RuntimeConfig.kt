package engineering.lightning.wavelength

import engineering.lightning.wavelength.wire.MobileConfig

/** The Bitcoin network the embedded daemon runs against. */
public enum class Network(public val wire: String) {
    MAINNET("mainnet"),

    /** Bitcoin testnet3. */
    TESTNET("testnet"),
    TESTNET4("testnet4"),
    SIGNET("signet"),
    REGTEST("regtest"),
}

/** The embedded on-chain wallet backend. */
public enum class WalletType(public val wire: String) {
    /** The lightweight, Esplora-backed wallet (the default). */
    LWWALLET("lwwallet"),

    /** The full btcwallet/neutrino backend. */
    BTCWALLET("btcwallet"),
}

/** How the daemon dials the operator and swap servers. */
public enum class ServerTransport(public val wire: String) {
    /** Native gRPC with `host:port` addresses (the mobile default). */
    GRPC("grpc"),

    /** grpc-gateway HTTP/JSON with URL addresses. */
    REST("rest"),
}

/**
 * Configuration for [WavelengthClient.start]. Every field except [network] is
 * optional; an unset field is omitted from the wire config so the daemon's own
 * per-network default applies.
 *
 * For the public test networks start from [RuntimeConfig.preset], which fills
 * in the hosted operator, swap, and Esplora endpoints, and override only what
 * you need. Mainnet and regtest have no preset.
 *
 * Mirrors `RuntimeConfig` in the TypeScript SDK (`@lightninglabs/wavelength-core`).
 */
public data class RuntimeConfig(
    val network: Network,
    /** Must be true to run on [Network.MAINNET]. */
    val allowMainnet: Boolean = false,
    /** Absolute storage root for daemon and wallet state. */
    val dataDir: String? = null,
    /** A level from [DEBUG_LEVELS], or a per-subsystem list such as `ROND=debug,info`. */
    val debugLevel: String? = null,
    /** Ark operator and mailbox endpoint (`host:port` for gRPC). */
    val arkServerAddress: String? = null,
    val arkServerTlsCertPath: String? = null,
    /** Disables TLS for the operator. Local development only. */
    val arkServerInsecure: Boolean? = null,
    val serverTransport: ServerTransport = ServerTransport.GRPC,
    val walletType: WalletType = WalletType.LWWALLET,
    /** Esplora HTTP API for [WalletType.LWWALLET]. */
    val walletEsploraUrl: String? = null,
    /** Password file [WalletType.LWWALLET] uses to unlock automatically. */
    val walletPasswordFile: String? = null,
    /** Chain polling interval for [WalletType.LWWALLET], in seconds. */
    val walletPollIntervalSeconds: Long? = null,
    /** Address look-ahead window for either backend (fits in uint32). */
    val walletRecoveryWindow: Long? = null,
    /** Fee estimator endpoint for [WalletType.BTCWALLET]. */
    val walletFeeUrl: String? = null,
    /** Block headers import source (file path or URL) for [WalletType.BTCWALLET]. */
    val walletBlockHeadersSource: String? = null,
    /** Compact filter headers import source for [WalletType.BTCWALLET]. */
    val walletFilterHeadersSource: String? = null,
    /** Lightning swap server endpoint. */
    val swapServerAddress: String? = null,
    val swapServerTlsCertPath: String? = null,
    /** Disables TLS for the swap server. Local development only. */
    val swapServerInsecure: Boolean? = null,
    val swapDatabaseFileName: String? = null,
    /** Runs without Lightning swaps: every swap field is omitted. */
    val disableSwaps: Boolean = false,
    /** Maximum per-round operator fee the daemon accepts, in satoshis. */
    val maxOperatorFeeSat: Long? = null,
    /**
     * Largest Lightning payment CLTV (blocks) reserved by automatic VTXO
     * maintenance. Null keeps the daemon default; an explicit 0 disables it.
     */
    val maxPaymentCltv: Long? = null,
    /** Allowance floor for unattended expiry refreshes, in satoshis. */
    val autoRefreshFeeFloorSat: Long? = null,
    /** Proportional allowance for unattended expiry refreshes, in ppm. */
    val autoRefreshFeeRatePpm: Long? = null,
    /** Concurrent VTXO signing sessions; 0 selects the backend default. */
    val signingWorkers: Long? = null,
    /** Joins rounds eagerly instead of waiting for the scheduled batch. */
    val eagerRoundJoin: Boolean? = null,
    /** In-memory gRPC listener buffer size override. */
    val bufferSize: Long? = null,
) {
    /**
     * Checks host-owned settings before they reach the daemon.
     *
     * @throws WavelengthException.InvalidConfig when a setting is invalid.
     */
    public fun validate() {
        if (network == Network.MAINNET && !allowMainnet) {
            invalid("mainnet requires allowMainnet = true")
        }
        when (walletType) {
            WalletType.LWWALLET -> {
                requireUnset("walletFeeUrl", walletFeeUrl, "btcwallet")
                requireUnset("walletBlockHeadersSource", walletBlockHeadersSource, "btcwallet")
                requireUnset("walletFilterHeadersSource", walletFilterHeadersSource, "btcwallet")
            }
            WalletType.BTCWALLET -> {
                requireUnset("walletEsploraUrl", walletEsploraUrl, "lwwallet")
                requireUnset("walletPasswordFile", walletPasswordFile, "lwwallet")
                requireUnset("walletPollIntervalSeconds", walletPollIntervalSeconds, "lwwallet")
            }
        }
        listOf(
            "walletPollIntervalSeconds" to walletPollIntervalSeconds,
            "walletRecoveryWindow" to walletRecoveryWindow,
            "maxOperatorFeeSat" to maxOperatorFeeSat,
            "maxPaymentCltv" to maxPaymentCltv,
            "autoRefreshFeeFloorSat" to autoRefreshFeeFloorSat,
            "autoRefreshFeeRatePpm" to autoRefreshFeeRatePpm,
            "signingWorkers" to signingWorkers,
            "bufferSize" to bufferSize,
        ).forEach { (name, value) ->
            if (value != null && value < 0) invalid("$name must not be negative")
        }
        if ((walletRecoveryWindow ?: 0) > UINT32_MAX) invalid("walletRecoveryWindow must fit in uint32")
        if ((maxPaymentCltv ?: 0) > Int.MAX_VALUE) invalid("maxPaymentCltv must fit in int32")
        if ((bufferSize ?: 0) > Int.MAX_VALUE) invalid("bufferSize must fit in int32")
        if (dataDir != null && dataDir.isBlank()) invalid("dataDir must not be blank")
    }

    internal fun toWire(): MobileConfig = MobileConfig(
        dataDir = dataDir,
        network = network.wire,
        debugLevel = debugLevel,
        allowMainnet = allowMainnet.takeIf { it },
        serverAddress = arkServerAddress,
        serverTransport = serverTransport.wire,
        serverTlsCertPath = arkServerTlsCertPath,
        serverInsecure = arkServerInsecure,
        walletType = walletType.wire,
        walletEsploraUrl = walletEsploraUrl,
        walletPasswordFile = walletPasswordFile,
        walletPollIntervalSeconds = walletPollIntervalSeconds,
        walletRecoveryWindow = walletRecoveryWindow,
        walletFeeUrl = walletFeeUrl,
        walletBlockHeadersSource = walletBlockHeadersSource,
        walletFilterHeadersSource = walletFilterHeadersSource,
        // An unset swap address selects the network default rather than
        // disabling swaps, so disableSwaps omits every swap field instead.
        swapServerAddress = swapServerAddress.unlessSwapsDisabled(),
        swapServerTransport = serverTransport.wire.unlessSwapsDisabled(),
        swapServerTlsCertPath = swapServerTlsCertPath.unlessSwapsDisabled(),
        swapServerInsecure = swapServerInsecure.unlessSwapsDisabled(),
        swapDatabaseFileName = swapDatabaseFileName.unlessSwapsDisabled(),
        maxOperatorFeeSat = maxOperatorFeeSat,
        maxPaymentCltv = maxPaymentCltv,
        autoRefreshFeeFloorSat = autoRefreshFeeFloorSat,
        autoRefreshFeeRatePpm = autoRefreshFeeRatePpm,
        signingWorkers = signingWorkers,
        eagerRoundJoin = eagerRoundJoin,
        bufferSize = bufferSize,
    )

    private fun <T> T.unlessSwapsDisabled(): T? = if (disableSwaps) null else this

    public companion object {
        /** Daemon log levels, most to least verbose. */
        public val DEBUG_LEVELS: List<String> = listOf("trace", "debug", "info", "warn", "error", "critical", "off")

        private const val UINT32_MAX = 0xFFFF_FFFFL

        /**
         * A config for one of the public test networks with Lightning Labs'
         * hosted operator, swap, and Esplora endpoints filled in.
         *
         * @param network [Network.SIGNET], [Network.TESTNET], or [Network.TESTNET4].
         * @param dataDir Absolute storage root, for example
         *   `"${context.filesDir}/wavelength"` on Android.
         */
        public fun preset(
            network: Network,
            dataDir: String? = null,
            transport: ServerTransport = ServerTransport.GRPC,
        ): RuntimeConfig {
            val endpoints = NetworkPresets.endpoints(network)
                ?: throw WavelengthException.InvalidConfig("no endpoint preset for $network; build its RuntimeConfig by hand")
            return RuntimeConfig(
                network = network,
                dataDir = dataDir,
                serverTransport = transport,
                arkServerAddress = endpoints.ark.address(transport),
                swapServerAddress = endpoints.swap.address(transport),
                walletEsploraUrl = endpoints.esploraUrl,
            )
        }
    }
}

private fun invalid(message: String): Nothing = throw WavelengthException.InvalidConfig(message)

private fun requireUnset(name: String, value: Any?, onlyFor: String) {
    if (value != null) invalid("$name applies only to walletType $onlyFor")
}

/** One hosted service in both transport flavours. */
public data class ServiceEndpoints(val grpc: String, val rest: String) {
    public fun address(transport: ServerTransport): String = when (transport) {
        ServerTransport.GRPC -> grpc
        ServerTransport.REST -> rest
    }
}

/** The public deployment of one network. */
public data class NetworkEndpoints(
    val ark: ServiceEndpoints,
    val swap: ServiceEndpoints,
    val esploraUrl: String,
)

/**
 * Lightning Labs' hosted endpoints per public test network, matching the
 * TypeScript SDK's `networkDefaults`.
 */
public object NetworkPresets {
    public val SIGNET: NetworkEndpoints = NetworkEndpoints(
        ark = ServiceEndpoints(
            grpc = "signet.wavelength.lightning.finance:443",
            rest = "https://signet.wavelength-rest.lightning.finance",
        ),
        swap = ServiceEndpoints(
            grpc = "swap.signet.wavelength.lightning.finance:443",
            rest = "https://signet.swapd-rest.lightning.finance",
        ),
        esploraUrl = "https://mempool-signet.testnet.lightningcluster.com/api",
    )

    public val TESTNET: NetworkEndpoints = NetworkEndpoints(
        ark = ServiceEndpoints(
            grpc = "test.wavelength.lightning.finance:443",
            rest = "https://test.wavelength-rest.lightning.finance",
        ),
        swap = ServiceEndpoints(
            grpc = "swap.test.wavelength.lightning.finance:443",
            rest = "https://test.swapd-rest.lightning.finance",
        ),
        esploraUrl = "https://mempool-testnet3.testnet.lightningcluster.com/api",
    )

    public val TESTNET4: NetworkEndpoints = NetworkEndpoints(
        ark = ServiceEndpoints(
            // testnet4's public gRPC load balancer is not enabled yet, so the
            // daemon dials the raw cluster hostname.
            grpc = "lumosd-testnet4.testnet.lightningcluster.com:443",
            rest = "https://test4.wavelength-rest.lightning.finance",
        ),
        swap = ServiceEndpoints(
            grpc = "swapd-testnet4.testnet.lightningcluster.com:443",
            rest = "https://test4.swapd-rest.lightning.finance",
        ),
        esploraUrl = "https://mempool-testnet4.testnet.lightningcluster.com/api",
    )

    /** The preset for [network], or null for mainnet and regtest. */
    public fun endpoints(network: Network): NetworkEndpoints? = when (network) {
        Network.SIGNET -> SIGNET
        Network.TESTNET -> TESTNET
        Network.TESTNET4 -> TESTNET4
        Network.MAINNET, Network.REGTEST -> null
    }
}
