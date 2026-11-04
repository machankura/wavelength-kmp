package engineering.lightning.wavelength.embedded

import engineering.lightning.wavelength.FacadeBindings
import engineering.lightning.wavelength.FacadeSubscription
import engineering.lightning.wavelength.FacadeVerb
import engineering.lightning.wavewalletdk.cinterop.MobileBalance
import engineering.lightning.wavewalletdk.cinterop.MobileConfirmedBalanceSat
import engineering.lightning.wavewalletdk.cinterop.MobileCreateWallet
import engineering.lightning.wavewalletdk.cinterop.MobileDeposit
import engineering.lightning.wavewalletdk.cinterop.MobileExit
import engineering.lightning.wavewalletdk.cinterop.MobileExitStatus
import engineering.lightning.wavewalletdk.cinterop.MobileExitSummary
import engineering.lightning.wavewalletdk.cinterop.MobileGetExitPlan
import engineering.lightning.wavewalletdk.cinterop.MobileGetInfo
import engineering.lightning.wavewalletdk.cinterop.MobileIsRunning
import engineering.lightning.wavewalletdk.cinterop.MobileList
import engineering.lightning.wavewalletdk.cinterop.MobileListWalletDescriptors
import engineering.lightning.wavewalletdk.cinterop.MobileOpenWalletFromPasskey
import engineering.lightning.wavewalletdk.cinterop.MobilePendingInboundSat
import engineering.lightning.wavewalletdk.cinterop.MobilePrepareSend
import engineering.lightning.wavewalletdk.cinterop.MobileReceive
import engineering.lightning.wavewalletdk.cinterop.MobileSendPrepared
import engineering.lightning.wavewalletdk.cinterop.MobileStart
import engineering.lightning.wavewalletdk.cinterop.MobileStartDescriptorWallet
import engineering.lightning.wavewalletdk.cinterop.MobileStartExternalSeedWallet
import engineering.lightning.wavewalletdk.cinterop.MobileStatus
import engineering.lightning.wavewalletdk.cinterop.MobileStop
import engineering.lightning.wavewalletdk.cinterop.MobileSubscribe
import engineering.lightning.wavewalletdk.cinterop.MobileSubscription
import engineering.lightning.wavewalletdk.cinterop.MobileSweepWallet
import engineering.lightning.wavewalletdk.cinterop.MobileUnlockWallet
import engineering.lightning.wavewalletdk.cinterop.MobileWalletReady
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.create
import platform.posix.memcpy

/**
 * [FacadeBindings] over the gomobile-generated C functions (`MobileStart`,
 * `MobileGetInfo`, ...). gomobile reports Go errors through an `NSError**`
 * out-parameter whose `localizedDescription` is the Go error text.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosFacadeBindings : FacadeBindings {
    override fun start(configJson: String) {
        goCall { err -> MobileStart(configJson, err) }.requireTrue("MobileStart")
    }

    override fun startExternalSeedWallet(request: ByteArray): ByteArray =
        goCall { err -> MobileStartExternalSeedWallet(request.toNSData(), err) }.toByteArray()

    override fun startDescriptorWallet(request: ByteArray): ByteArray =
        goCall { err -> MobileStartDescriptorWallet(request.toNSData(), err) }.toByteArray()

    override fun stop() {
        goCall { err -> MobileStop(err) }.requireTrue("MobileStop")
    }

    override fun isRunning(): Boolean = MobileIsRunning()

    override fun call(verb: FacadeVerb, request: ByteArray): ByteArray {
        val req = request.toNSData()
        return goCall { err ->
            when (verb) {
                FacadeVerb.GET_INFO -> MobileGetInfo(err)
                FacadeVerb.STATUS -> MobileStatus(err)
                FacadeVerb.BALANCE -> MobileBalance(err)
                FacadeVerb.CREATE_WALLET -> MobileCreateWallet(req, err)
                FacadeVerb.UNLOCK_WALLET -> MobileUnlockWallet(req, err)
                FacadeVerb.OPEN_WALLET_FROM_PASSKEY -> MobileOpenWalletFromPasskey(req, err)
                FacadeVerb.DEPOSIT -> MobileDeposit(req, err)
                FacadeVerb.RECEIVE -> MobileReceive(req, err)
                FacadeVerb.PREPARE_SEND -> MobilePrepareSend(req, err)
                FacadeVerb.SEND_PREPARED -> MobileSendPrepared(req, err)
                FacadeVerb.LIST -> MobileList(req, err)
                FacadeVerb.EXIT -> MobileExit(req, err)
                FacadeVerb.EXIT_STATUS -> MobileExitStatus(req, err)
                FacadeVerb.EXIT_SUMMARY -> MobileExitSummary(req, err)
                FacadeVerb.GET_EXIT_PLAN -> MobileGetExitPlan(req, err)
                FacadeVerb.SWEEP_WALLET -> MobileSweepWallet(req, err)
                FacadeVerb.LIST_WALLET_DESCRIPTORS -> MobileListWalletDescriptors(req, err)
            }
        }.toByteArray()
    }

    override fun subscribe(request: ByteArray): FacadeSubscription {
        val subscription = goCall { err -> MobileSubscribe(request.toNSData(), err) }
            ?: throw IosBindingException("MobileSubscribe returned no subscription")
        return IosSubscription(subscription)
    }

    override fun confirmedBalanceSat(): Long = memScoped {
        val out = alloc<LongVar>()
        goCall { err -> MobileConfirmedBalanceSat(out.ptr, err) }.requireTrue("MobileConfirmedBalanceSat")
        out.value
    }

    override fun pendingInboundSat(): Long = memScoped {
        val out = alloc<LongVar>()
        goCall { err -> MobilePendingInboundSat(out.ptr, err) }.requireTrue("MobilePendingInboundSat")
        out.value
    }

    override fun walletReady(): Boolean = memScoped {
        val out = alloc<BooleanVar>()
        goCall { err -> MobileWalletReady(out.ptr, err) }.requireTrue("MobileWalletReady")
        out.value
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosSubscription(private val subscription: MobileSubscription) : FacadeSubscription {
    override fun next(): ByteArray? = try {
        goCall { err -> subscription.next(err) }.toByteArray()
    } catch (e: IosBindingException) {
        // Go's io.EOF arrives as an NSError whose description is "EOF".
        if (e.message == GO_EOF) null else throw e
    }

    override fun close() {
        goCall { err -> subscription.close(err) }
    }

    private companion object {
        const val GO_EOF = "EOF"
    }
}

/** A Go error reported by the gomobile framework. */
internal class IosBindingException(message: String) : Exception(message)

/** Runs a gomobile call with an `NSError**` out-parameter, throwing on error. */
@OptIn(ExperimentalForeignApi::class)
private inline fun <T> goCall(block: (CPointer<ObjCObjectVar<NSError?>>) -> T): T = memScoped {
    val error = alloc<ObjCObjectVar<NSError?>>()
    val result = block(error.ptr)
    error.value?.let { throw IosBindingException(it.localizedDescription) }
    result
}

private fun Boolean.requireTrue(function: String) {
    if (!this) throw IosBindingException("$function failed without an error")
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) {
        NSData()
    } else {
        usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }
    }

@OptIn(ExperimentalForeignApi::class)
private fun NSData?.toByteArray(): ByteArray {
    if (this == null) return ByteArray(0)
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    return ByteArray(size).apply { usePinned { memcpy(it.addressOf(0), bytes, length) } }
}

internal actual val platformBindings: FacadeBindings get() = IosFacadeBindings
