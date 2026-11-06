package engineering.lightning.wavelength.embedded

import engineering.lightning.wavelength.FacadeBindings
import engineering.lightning.wavelength.FacadeSubscription
import engineering.lightning.wavelength.FacadeVerb
import engineering.lightning.wavelength.SignerHost
import engineering.lightning.wavewalletdk.mobile.Mobile
import engineering.lightning.wavewalletdk.mobile.Subscription

/** [FacadeBindings] over the gomobile-generated `Mobile` class (JNI). */
internal object AndroidFacadeBindings : FacadeBindings {
    override fun start(configJson: String): Unit = Mobile.start(configJson)

    override fun startExternalSeedWallet(request: ByteArray): ByteArray =
        Mobile.startExternalSeedWallet(request).orEmpty()

    override fun startDescriptorWallet(request: ByteArray): ByteArray =
        Mobile.startDescriptorWallet(request).orEmpty()

    override fun startWatchOnlyWallet(request: ByteArray): ByteArray =
        Mobile.startWatchOnlyWallet(request).orEmpty()

    override fun startHostedWatchOnlyWallet(request: ByteArray, host: SignerHost): ByteArray =
        Mobile.startWatchOnlyWalletWithHost(
            request,
            object : engineering.lightning.wavewalletdk.mobile.SignerHost {
                override fun handle(request: ByteArray?): ByteArray = host.handle(request ?: ByteArray(0))
            },
        ).orEmpty()

    override fun stop(): Unit = Mobile.stop()

    override fun isRunning(): Boolean = Mobile.isRunning()

    override fun call(verb: FacadeVerb, request: ByteArray): ByteArray = when (verb) {
        FacadeVerb.GET_INFO -> Mobile.getInfo()
        FacadeVerb.STATUS -> Mobile.status()
        FacadeVerb.BALANCE -> Mobile.balance()
        FacadeVerb.CREATE_WALLET -> Mobile.createWallet(request)
        FacadeVerb.UNLOCK_WALLET -> Mobile.unlockWallet(request)
        FacadeVerb.OPEN_WALLET_FROM_PASSKEY -> Mobile.openWalletFromPasskey(request)
        FacadeVerb.DEPOSIT -> Mobile.deposit(request)
        FacadeVerb.RECEIVE -> Mobile.receive(request)
        FacadeVerb.PREPARE_SEND -> Mobile.prepareSend(request)
        FacadeVerb.SEND_PREPARED -> Mobile.sendPrepared(request)
        FacadeVerb.LIST -> Mobile.list(request)
        FacadeVerb.EXIT -> Mobile.exit(request)
        FacadeVerb.EXIT_STATUS -> Mobile.exitStatus(request)
        FacadeVerb.EXIT_SUMMARY -> Mobile.exitSummary(request)
        FacadeVerb.GET_EXIT_PLAN -> Mobile.getExitPlan(request)
        FacadeVerb.SWEEP_WALLET -> Mobile.sweepWallet(request)
        FacadeVerb.LIST_WALLET_DESCRIPTORS -> Mobile.listWalletDescriptors(request)
    }.orEmpty()

    override fun subscribe(request: ByteArray): FacadeSubscription = AndroidSubscription(Mobile.subscribe(request))

    override fun confirmedBalanceSat(): Long = Mobile.confirmedBalanceSat()

    override fun pendingInboundSat(): Long = Mobile.pendingInboundSat()

    override fun walletReady(): Boolean = Mobile.walletReady()

    private fun ByteArray?.orEmpty(): ByteArray = this ?: ByteArray(0)
}

private class AndroidSubscription(private val subscription: Subscription) : FacadeSubscription {
    override fun next(): ByteArray? = try {
        subscription.next()
    } catch (e: Exception) {
        // Go's io.EOF crosses gomobile as an exception whose message is "EOF".
        if (e.message == GO_EOF) null else throw e
    }

    override fun close(): Unit = subscription.close()

    private companion object {
        const val GO_EOF = "EOF"
    }
}

internal actual val platformBindings: FacadeBindings get() = AndroidFacadeBindings
