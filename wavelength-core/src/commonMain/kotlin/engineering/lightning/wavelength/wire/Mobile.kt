package engineering.lightning.wavelength.wire

import engineering.lightning.wavelength.ByteString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Hand-written envelopes for the parts of the gomobile facade that do not
// decode into sdk/wavewalletdk/types.go DTOs (see docs/wavewalletdk_mobile.md
// in wavelength). Unlike the PascalCase DTOs, these use explicit json tags.

/**
 * The flat Start config (`mobileConfig` in sdk/wavewalletdk/mobile/config.go),
 * snake_case. Every field is nullable and omitted when null, so the daemon's
 * build defaults fill the rest.
 */
@Serializable
internal data class MobileConfig(
    @SerialName("data_dir") val dataDir: String? = null,
    @SerialName("network") val network: String? = null,
    @SerialName("debug_level") val debugLevel: String? = null,
    @SerialName("allow_mainnet") val allowMainnet: Boolean? = null,
    @SerialName("server_address") val serverAddress: String? = null,
    @SerialName("server_transport") val serverTransport: String? = null,
    @SerialName("server_tls_cert_path") val serverTlsCertPath: String? = null,
    @SerialName("server_insecure") val serverInsecure: Boolean? = null,
    @SerialName("wallet_type") val walletType: String? = null,
    @SerialName("wallet_esplora_url") val walletEsploraUrl: String? = null,
    @SerialName("wallet_password_file") val walletPasswordFile: String? = null,
    @SerialName("wallet_poll_interval_seconds") val walletPollIntervalSeconds: Long? = null,
    @SerialName("wallet_recovery_window") val walletRecoveryWindow: Long? = null,
    @SerialName("wallet_fee_url") val walletFeeUrl: String? = null,
    @SerialName("wallet_block_headers_source") val walletBlockHeadersSource: String? = null,
    @SerialName("wallet_filter_headers_source") val walletFilterHeadersSource: String? = null,
    @SerialName("swap_server_address") val swapServerAddress: String? = null,
    @SerialName("swap_server_transport") val swapServerTransport: String? = null,
    @SerialName("swap_server_tls_cert_path") val swapServerTlsCertPath: String? = null,
    @SerialName("swap_server_insecure") val swapServerInsecure: Boolean? = null,
    @SerialName("swap_database_file_name") val swapDatabaseFileName: String? = null,
    @SerialName("max_operator_fee_sat") val maxOperatorFeeSat: Long? = null,
    @SerialName("max_payment_cltv") val maxPaymentCltv: Long? = null,
    @SerialName("auto_refresh_fee_floor_sat") val autoRefreshFeeFloorSat: Long? = null,
    @SerialName("auto_refresh_fee_rate_ppm") val autoRefreshFeeRatePpm: Long? = null,
    @SerialName("signing_workers") val signingWorkers: Long? = null,
    @SerialName("eager_round_join") val eagerRoundJoin: Boolean? = null,
    @SerialName("buffer_size") val bufferSize: Long? = null,
    @SerialName("remote_signer_rpc_host") val remoteSignerRpcHost: String? = null,
    @SerialName("remote_signer_macaroon_path") val remoteSignerMacaroonPath: String? = null,
    @SerialName("remote_signer_tls_cert_path") val remoteSignerTlsCertPath: String? = null,
    @SerialName("remote_signer_connect_timeout_seconds") val remoteSignerConnectTimeoutSeconds: Long? = null,
    @SerialName("remote_signer_request_timeout_seconds") val remoteSignerRequestTimeoutSeconds: Long? = null,
)

/**
 * The StartExternalSeedWallet envelope (`externalSeedWalletStartRequest` in
 * sdk/wavewalletdk/mobile/external_seed_wallet.go). `config` is the nested
 * final [MobileConfig]; the entropy is standard base64.
 */
@Serializable
internal data class ExternalSeedWalletStartRequest(
    @SerialName("config") val config: JsonElement,
    @SerialName("seed_entropy") val seedEntropy: ByteString,
    @SerialName("expected_identity_pubkey") val expectedIdentityPubKey: String? = null,
    @SerialName("recover_state") val recoverState: Boolean? = null,
    @SerialName("recovery_window") val recoveryWindow: Long? = null,
)

/** Receive with the mobile-only binding deadline (`mobileReceiveRequest`). */
@Serializable
internal data class MobileReceiveRequest(
    @SerialName("AmountSat") val amountSat: Long,
    @SerialName("Memo") val memo: String? = null,
    @SerialName("TimeoutSeconds") val timeoutSeconds: Long? = null,
)

/** OpenWalletFromPasskey's camelCase request (hex PRF output). */
@Serializable
internal data class OpenWalletFromPasskeyRequest(
    @SerialName("prfOutput") val prfOutput: String,
)
