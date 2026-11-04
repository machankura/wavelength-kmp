package engineering.lightning.wavelength

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class ErrorClassifierTest {
    private inline fun <reified T : WavelengthException> assertClass(message: String): T {
        val e = ErrorClassifier.classify(message)
        assertIs<T>(e, "for: $message")
        assertEquals(message, e.message)
        return e
    }

    @Test
    fun lifecycleErrors() {
        assertClass<WavelengthException.RuntimeNotReady>("wavewalletdk mobile not started")
        assertClass<WavelengthException.RuntimeActive>("wavewalletdk mobile already started")
        assertClass<WavelengthException.RuntimePanic>("wavewalletdk mobile panic: boom\ngoroutine 1")
    }

    @Test
    fun receiveUncertain() {
        assertClass<WavelengthException.ReceiveOutcomeUncertain>(
            "receive outcome uncertain; reconcile Activity before retrying: context deadline exceeded",
        )
    }

    @Test
    fun reconstructedSentinelsPrefixTheGrpcError() {
        // wavewalletdk reconstructs sentinels as fmt.Errorf("%w: %w", sentinel, grpcErr).
        assertClass<WavelengthException.InvalidSendIntent>(
            "send intent is missing, expired, or already consumed: rpc error: code = InvalidArgument desc = intent gone",
        )
        assertClass<WavelengthException.AmountExceedsVtxoLimit>(
            "amount exceeds the operator's maximum VTXO size: rpc error: code = InvalidArgument desc = x",
        )
        assertClass<WavelengthException.BalanceLimitExceeded>("amount would exceed the maximum allowed wallet balance")
        assertClass<WavelengthException.InvalidDestination>("send destination must set invoice or onchain_address")
        assertClass<WavelengthException.AmountRequired>("amt_sat is required for the requested send")
        assertClass<WavelengthException.AmountInvalid>("amt_sat is out of range")
        assertClass<WavelengthException.SwapBackendUnavailable>("swap backend handle is unavailable")
        assertClass<WavelengthException.CreditReceiveUnavailable>("sub-dust receive via the operator credit subsystem is unavailable")
        assertClass<WavelengthException.UnsupportedKind>("unsupported entry kind in list filter")
    }

    @Test
    fun descriptorErrors() {
        for (message in listOf(
            "start descriptor wallet: invalid descriptors: unsupported key",
            "resolve descriptor wallet keys: invalid account keys: key families 0 to 255 are required",
            "descriptor wallet requires at least one descriptor",
            "descriptor wallet identity mismatch: expected 02aa, wallet has 02bb",
            "include_private requires the wallet's mnemonic or root descriptors",
            "the supplied seed material does not belong to this wallet",
        )) {
            assertEquals("invalid_descriptor", assertClass<WavelengthException.InvalidDescriptor>(message).code)
        }
    }

    @Test
    fun watchOnlyErrors() {
        for (message in listOf(
            "invalid watch-only bundle: bad checksum",
            "invalid manifest: unexpected end of JSON input",
            "invalid authentication keys: bad length",
            "watch-only wallet requires a wallet password",
            "watch-only wallet requires exactly one of a bundle and a manifest",
            "a watch-only wallet needs Config.RemoteSigner",
            "a manifest requires its authentication keys",
            "authentication keys and OverrideIdentity are only used with a manifest",
            "watch-only wallet identity mismatch: request gives 02aa, wallet has 02bb",
            "wallet accounts do not match its stored manifest: slot family/0",
            "the wallet database holds public keys only; configure the remote signer that holds its private keys",
            "the wallet's bundle does not cover every key family this version uses; export a new minimal bundle",
            "key family not supplied by the wallet's bundle",
        )) {
            assertEquals("invalid_watch_only", assertClass<WavelengthException.InvalidWatchOnly>(message).code)
        }
    }

    @Test
    fun remoteSignerErrors() {
        for (message in listOf(
            "connect to remote signer: context deadline exceeded",
            "remote signer not ready: context deadline exceeded",
            "remote signer challenge: rpc error: code = Unavailable desc = connection refused",
            "verify remote signer: remote signer does not hold this wallet's keys",
            "signer does not hold the key for a wallet slot: [family/0]",
            // A first open that failed after the database was created.
            "rpc error: code = FailedPrecondition desc = connect to remote signer: refused " +
                "(the wallet database was created; retry via UnlockWallet)",
        )) {
            assertEquals("remote_signer_failed", assertClass<WavelengthException.RemoteSignerFailed>(message).code)
        }
    }

    @Test
    fun grpcCodes() {
        val pre = assertClass<WavelengthException.FailedPrecondition>("rpc error: code = FailedPrecondition desc = wallet locked")
        assertEquals("FailedPrecondition", pre.grpcCode)
        val other = assertClass<WavelengthException.Daemon>("rpc error: code = Unavailable desc = connection refused")
        assertEquals("Unavailable", other.grpcCode)
        assertEquals("wavelength_error", other.code)
    }

    @Test
    fun streamGapCarriesCursor() {
        val e = assertClass<WavelengthException.StreamGap>(
            "wallet subscription gap at cursor 42: buffer overflow: resume a new subscription from the cursor",
        )
        assertEquals(42, e.cursor)
    }

    @Test
    fun walletLocked() {
        assertClass<WavelengthException.WalletLocked>("open db: database is locked")
    }

    @Test
    fun fallbackAndPassthrough() {
        assertClass<WavelengthException.Daemon>("something new")
        val typed = WavelengthException.InvalidConfig("x")
        assertSame(typed, ErrorClassifier.classify(typed))
        assertEquals("unknown wavelength error", ErrorClassifier.classify(null).message)
    }
}
