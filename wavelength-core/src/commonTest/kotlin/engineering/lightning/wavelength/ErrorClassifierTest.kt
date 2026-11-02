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
