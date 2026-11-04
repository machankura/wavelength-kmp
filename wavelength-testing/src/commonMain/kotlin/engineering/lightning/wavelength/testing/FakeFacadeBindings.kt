package engineering.lightning.wavelength.testing

import engineering.lightning.wavelength.Destination
import engineering.lightning.wavelength.FacadeBindings
import engineering.lightning.wavelength.InvoiceAmount
import engineering.lightning.wavelength.FacadeSubscription
import engineering.lightning.wavelength.FacadeVerb
import engineering.lightning.wavelength.model.ActivityList
import engineering.lightning.wavelength.model.Balance
import engineering.lightning.wavelength.model.CreateWalletResult
import engineering.lightning.wavelength.model.DepositResult
import engineering.lightning.wavelength.model.Entry
import engineering.lightning.wavelength.model.EntryKind
import engineering.lightning.wavelength.model.EntryProgress
import engineering.lightning.wavelength.model.EntryStatus
import engineering.lightning.wavelength.model.ExitResult
import engineering.lightning.wavelength.model.ExitSummaryResult
import engineering.lightning.wavelength.model.GetExitPlanResult
import engineering.lightning.wavelength.model.ExternalSeedWalletOpenResult
import engineering.lightning.wavelength.model.Info
import engineering.lightning.wavelength.model.ListResult
import engineering.lightning.wavelength.model.ListView
import engineering.lightning.wavelength.model.ListWalletDescriptorsResult
import engineering.lightning.wavelength.model.OpenWalletResult
import engineering.lightning.wavelength.model.PrepareSendResult
import engineering.lightning.wavelength.model.ReceiveResult
import engineering.lightning.wavelength.model.SendRail
import engineering.lightning.wavelength.model.SendResult
import engineering.lightning.wavelength.model.Status
import engineering.lightning.wavelength.model.UnlockWalletResult
import engineering.lightning.wavelength.model.VTXOInventory
import engineering.lightning.wavelength.model.WalletState
import kotlin.time.Clock
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * A simulated wavelength wallet behind [FacadeBindings], speaking the same
 * JSON as the real gomobile facade. Use it with
 * `FacadeWavelengthClient(FakeFacadeBindings())` to test app code (or the
 * engine) without the native runtime.
 *
 * It models a single wallet: none -> created/unlocked (ready) -> locked again
 * after a restart; Lightning receives that the test settles; sends that
 * debit the balance; and an activity stream. Tests can inject failures with
 * [failNext] and inspect what was sent with [requests]. Thread-safe.
 */
public class FakeFacadeBindings(
    /** The wallet's state before the first start (NONE: no wallet yet). */
    initialState: WalletState = WalletState.NONE,
    initialConfirmedSat: Long = 0,
    public val network: String = "signet",
) : FacadeBindings {
    private val lock = SynchronizedObject()
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private var running = false
    private var walletState = initialState
    private var password: String? = null
    private var confirmedSat = initialConfirmedSat
    private var pendingInSat = 0L
    private var blockHeight = 100_000L
    private var nextId = 1
    private var nextCursor = 1L
    private val entries = mutableListOf<Entry>()
    private val intents = mutableMapOf<String, PrepareSendResult>()
    private val subscriptions = mutableListOf<FakeSubscription>()
    private val failures = mutableMapOf<String, ArrayDeque<String>>()
    private val _requests = mutableListOf<Pair<String, String>>()

    /** The identity reported once a wallet exists. */
    public val identityPubKey: String = "02" + "ab".repeat(32)

    /** The master key fingerprint of the wallet's descriptors. */
    public val rootFingerprint: String = "5bf9f809"

    /** The public descriptors the wallet watches. */
    public val publicDescriptors: List<String> = listOf(
        "tr([5bf9f809/86h/0h/0h]tpubFake86/<0;1>/*)#fake86",
        "wpkh([5bf9f809/84h/0h/0h]tpubFake84/<0;1>/*)#fake84",
    )

    /** Scripts GetExitPlan: receives the requested outpoints. */
    public var exitPlanHandler: ((List<String>) -> GetExitPlanResult)? = null

    /** Scripts Exit: receives the outpoint and whether it was a forced unroll. */
    public var exitHandler: ((outpoint: String, forceUnroll: Boolean) -> ExitResult)? = null

    /** Every call made, as (verb wire name, request JSON), oldest first. */
    public val requests: List<Pair<String, String>> get() = synchronized(lock) { _requests.toList() }

    /** The last config JSON passed to [start]. */
    public var lastConfigJson: String? = null
        private set

    /** Makes the next call of [verb] (a [FacadeVerb.wireName], "start", "stop", "subscribe", or "next") throw [message]. */
    public fun failNext(verb: String, message: String): Unit = synchronized(lock) {
        failures.getOrPut(verb) { ArrayDeque() }.addLast(message)
    }

    /** Current wallet state, for assertions. */
    public val state: WalletState get() = synchronized(lock) { walletState }

    /** The confirmed balance, for assertions. */
    public val balanceSat: Long get() = synchronized(lock) { confirmedSat }

    /** Moves the simulated chain tip. */
    public fun mineBlocks(count: Int = 1): Unit = synchronized(lock) { blockHeight += count }

    /** Puts the wallet into [state], e.g. SYNCING, to exercise UI states. */
    public fun setWalletState(state: WalletState): Unit = synchronized(lock) { walletState = state }

    /** Completes the pending receive (or deposit) [entryId], crediting the balance. */
    public fun settle(entryId: String) {
        val updated = synchronized(lock) {
            val i = entries.indexOfFirst { it.id == entryId }
            require(i >= 0) { "no entry $entryId" }
            val e = entries[i]
            require(e.status == EntryStatus.PENDING) { "entry $entryId is not pending" }
            pendingInSat -= e.amountSat
            confirmedSat += e.amountSat
            touch(e.copy(status = EntryStatus.COMPLETE), i)
        }
        publish(updated)
    }

    /** Fails the pending entry [entryId]. */
    public fun fail(entryId: String, reason: String = "failed") {
        val updated = synchronized(lock) {
            val i = entries.indexOfFirst { it.id == entryId }
            require(i >= 0) { "no entry $entryId" }
            val e = entries[i]
            if (e.kind == EntryKind.RECEIVE || e.kind == EntryKind.DEPOSIT) pendingInSat -= e.amountSat
            touch(e.copy(status = EntryStatus.FAILED, failureReason = reason), i)
        }
        publish(updated)
    }

    /** Ends every open activity stream with an error, as a broken daemon stream would. */
    public fun breakStreams(message: String = "stream reset") {
        val subs = synchronized(lock) { subscriptions.toList().also { subscriptions.clear() } }
        subs.forEach { it.fail(message) }
    }

    // --- FacadeBindings ------------------------------------------------------

    override fun start(configJson: String): Unit = synchronized(lock) {
        record("start", configJson)
        check(!running) { "wavewalletdk mobile already started" }
        lastConfigJson = configJson
        running = true
        // A restarted daemon has its wallet locked again.
        if (walletState == WalletState.READY || walletState == WalletState.SYNCING) walletState = WalletState.LOCKED
    }

    override fun startExternalSeedWallet(request: ByteArray): ByteArray = synchronized(lock) {
        val body = request.decodeToString()
        record("startExternalSeedWallet", body)
        check(!running) { "wavewalletdk mobile already started" }
        val obj = json.parseToJsonElement(body).jsonObject
        lastConfigJson = obj["config"].toString()
        val imported = walletState == WalletState.NONE
        running = true
        walletState = WalletState.READY
        encode(ExternalSeedWalletOpenResult.serializer(), ExternalSeedWalletOpenResult(imported = imported, identityPubKey = identityPubKey))
    }

    override fun stop() {
        val subs = synchronized(lock) {
            record("stop", "")
            running = false
            subscriptions.toList().also { subscriptions.clear() }
        }
        subs.forEach { it.end() }
    }

    override fun isRunning(): Boolean = synchronized(lock) { running }

    override fun confirmedBalanceSat(): Long = synchronized(lock) {
        requireWallet("confirmedBalanceSat")
        confirmedSat
    }

    override fun pendingInboundSat(): Long = synchronized(lock) {
        requireWallet("pendingInboundSat")
        pendingInSat
    }

    override fun walletReady(): Boolean = synchronized(lock) {
        requireRunning("walletReady")
        walletState == WalletState.READY
    }

    override fun call(verb: FacadeVerb, request: ByteArray): ByteArray {
        val body = request.decodeToString()
        val (result, published) = synchronized(lock) {
            record(verb.wireName, body)
            requireRunning(verb.wireName)
            val req = if (body.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(body).jsonObject
            handle(verb, req)
        }
        published?.let(::publish)
        return result
    }

    override fun subscribe(request: ByteArray): FacadeSubscription {
        val body = request.decodeToString()
        val req = if (body.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(body).jsonObject
        val kinds = req["Kinds"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonArray
            ?.map { EntryKind.fromWire(it.jsonPrimitive.content) }?.toSet().orEmpty()
        return open("subscribe", body, req.bool("IncludeExisting"), kinds, req["Cursor"]?.jsonPrimitive?.longOrNull ?: 0)
    }

    /**
     * Opens an activity subscription whose [SuspendingSubscription.receive]
     * suspends instead of blocking a thread, for tests on virtual time.
     */
    public fun openSubscription(
        includeExisting: Boolean = false,
        kinds: Set<EntryKind> = emptySet(),
        cursor: Long = 0,
    ): SuspendingSubscription = open("subscribe", "", includeExisting, kinds, cursor)

    private fun open(verb: String, body: String, includeExisting: Boolean, kinds: Set<EntryKind>, cursor: Long): FakeSubscription =
        synchronized(lock) {
            record(verb, body)
            requireWallet(verb)
            val sub = FakeSubscription(kinds)
            if (includeExisting || cursor > 0) {
                entries.filter { it.cursor > cursor }.sortedBy { it.cursor }.forEach(sub::offer)
            }
            subscriptions += sub
            sub
        }

    // --- verbs -----------------------------------------------------------------

    /** Returns the response bytes and an entry to publish after unlocking. */
    private fun handle(verb: FacadeVerb, req: JsonObject): Pair<ByteArray, Entry?> = when (verb) {
        FacadeVerb.GET_INFO -> encode(Info.serializer(), info()) to null
        FacadeVerb.STATUS -> {
            requireWallet(verb.wireName)
            encode(Status.serializer(), Status(walletState == WalletState.READY, walletState == WalletState.READY, network, balance(), pendingCount())) to null
        }
        FacadeVerb.BALANCE -> {
            requireWallet(verb.wireName)
            encode(Balance.serializer(), balance()) to null
        }
        FacadeVerb.CREATE_WALLET -> {
            if (walletState != WalletState.NONE) precondition("wallet already exists")
            val mnemonic = req.strings("Mnemonic")
            val freshSeed = mnemonic.isEmpty() && req.strings("Descriptors").isEmpty()
            password = req.string("WalletPassword")
            walletState = WalletState.READY
            encode(
                CreateWalletResult.serializer(),
                CreateWalletResult(
                    mnemonic = if (freshSeed) List(24) { "word${it + 1}" } else emptyList(),
                    identityPubKey = identityPubKey,
                    rootFingerprint = rootFingerprint,
                    descriptors = publicDescriptors,
                    recoveryRan = req.bool("RecoverState"),
                ),
            ) to null
        }
        FacadeVerb.LIST_WALLET_DESCRIPTORS -> {
            requireWallet(verb.wireName)
            val includePrivate = req.bool("IncludePrivate")
            if (includePrivate && req.strings("Mnemonic").isEmpty() && req.strings("Descriptors").isEmpty()) {
                error("include_private requires the wallet's mnemonic or root descriptors")
            }
            val descriptors = if (includePrivate) publicDescriptors.map { it.replace("tpub", "tprv") } else publicDescriptors
            encode(
                ListWalletDescriptorsResult.serializer(),
                ListWalletDescriptorsResult(rootFingerprint, descriptors, includePrivate),
            ) to null
        }
        FacadeVerb.UNLOCK_WALLET -> {
            if (walletState != WalletState.LOCKED) precondition("wallet is not locked")
            if (req.string("WalletPassword") != password) error("invalid passphrase for master public key")
            walletState = WalletState.READY
            encode(UnlockWalletResult.serializer(), UnlockWalletResult(identityPubKey)) to null
        }
        FacadeVerb.OPEN_WALLET_FROM_PASSKEY -> {
            val imported = walletState == WalletState.NONE
            walletState = WalletState.READY
            encode(OpenWalletResult.serializer(), OpenWalletResult(imported, if (imported) List(24) { "pk${it + 1}" } else emptyList(), identityPubKey)) to null
        }
        FacadeVerb.RECEIVE -> {
            requireReady(verb.wireName)
            val amount = req.long("AmountSat")
            if (amount <= 0) error("amt_sat is out of range")
            val entry = newEntry(EntryKind.RECEIVE, amount, note = req.string("Memo").orEmpty())
            pendingInSat += amount
            val invoice = "lntbs${amount * 10}n1fake${entry.id}"
            encode(ReceiveResult.serializer(), ReceiveResult(invoice, entry)) to entry
        }
        FacadeVerb.DEPOSIT -> {
            requireReady(verb.wireName)
            val entry = newEntry(EntryKind.DEPOSIT, req.long("AmountSatHint"))
            pendingInSat += entry.amountSat
            encode(DepositResult.serializer(), DepositResult("tb1qfake${entry.id}", entry)) to entry
        }
        FacadeVerb.PREPARE_SEND -> {
            requireReady(verb.wireName)
            val invoice = req.string("Invoice").orEmpty()
            val address = req.string("OnchainAddress").orEmpty()
            if (invoice.isEmpty() == address.isEmpty()) error("send destination must set invoice or onchain_address")
            val invoiceAmount = (Destination.classify(invoice) as? Destination.Invoice)
                ?.let { (it.amount as? InvoiceAmount.Known)?.sat }
            val amount = req.long("AmountSat").takeIf { it > 0 }
                ?: invoiceAmount
                ?: if (req.bool("SweepAll")) confirmedSat else error("amt_sat is required for the requested send")
            val fee = if (invoice.isNotEmpty()) 2L else 150L
            val quote = PrepareSendResult(
                sendIntentId = "intent-${nextId++}",
                amountSat = amount,
                expectedFeeSat = fee,
                feeKnown = true,
                expectedTotalOutflowSat = amount + fee,
                totalOutflowKnown = true,
                rail = if (invoice.isNotEmpty()) SendRail.LIGHTNING else SendRail.ONCHAIN,
                destinationSummary = invoice.ifEmpty { address },
                paymentHash = if (invoice.isNotEmpty()) "hash-${invoice.takeLast(8)}" else "",
            )
            intents[quote.sendIntentId] = quote
            encode(PrepareSendResult.serializer(), quote) to null
        }
        FacadeVerb.SEND_PREPARED -> {
            requireReady(verb.wireName)
            val quote = intents.remove(req.string("SendIntentID").orEmpty())
                ?: error("send intent is missing, expired, or already consumed")
            if (quote.expectedTotalOutflowSat > confirmedSat) precondition("insufficient balance")
            confirmedSat -= quote.expectedTotalOutflowSat
            val entry = newEntry(
                EntryKind.SEND,
                quote.amountSat,
                status = EntryStatus.COMPLETE,
                fee = quote.expectedFeeSat,
                progress = quote.paymentHash.takeIf { it.isNotEmpty() }?.let { EntryProgress(paymentHash = it, preimage = "preimage-$it") },
            )
            encode(SendResult.serializer(), SendResult(entry, quote.amountSat)) to entry
        }
        FacadeVerb.LIST -> {
            requireWallet(verb.wireName)
            val view = ListView.fromWire(req.string("View") ?: "activity")
            val result = when (view) {
                ListView.VTXOS -> ListResult(view, vtxos = VTXOInventory())
                ListView.ONCHAIN -> ListResult(view, onchain = engineering.lightning.wavelength.model.OnchainHistory())
                else -> {
                    val pendingOnly = req.bool("PendingOnly")
                    val list = entries.filter { !pendingOnly || it.status == EntryStatus.PENDING }
                        .sortedByDescending { it.cursor }
                        // The daemon reports a send's preimage only on the stream, never in list.
                        .map { e -> e.progress?.let { e.copy(progress = it.copy(preimage = "")) } ?: e }
                    ListResult(ListView.ACTIVITY, activity = ActivityList(list, list.size.toLong()))
                }
            }
            encode(ListResult.serializer(), result) to null
        }
        FacadeVerb.EXIT_SUMMARY -> encode(ExitSummaryResult.serializer(), ExitSummaryResult()) to null
        FacadeVerb.GET_EXIT_PLAN -> {
            requireReady(verb.wireName)
            val handler = exitPlanHandler ?: error("FakeFacadeBindings.exitPlanHandler is not set")
            val outpoints = req["Outpoints"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonArray
                ?.map { it.jsonPrimitive.content }.orEmpty()
            encode(GetExitPlanResult.serializer(), handler(outpoints)) to null
        }
        FacadeVerb.EXIT -> {
            requireReady(verb.wireName)
            val handler = exitHandler ?: error("FakeFacadeBindings.exitHandler is not set")
            val result = handler(req.string("Outpoint").orEmpty(), !req.string("ForceUnrollAck").isNullOrEmpty())
            encode(ExitResult.serializer(), result) to null
        }
        else -> error("FakeFacadeBindings does not simulate ${verb.wireName}")
    }

    private fun info() = Info(
        version = "fake",
        network = network,
        blockHeight = blockHeight,
        serverConnected = true,
        walletType = "lwwallet",
        walletState = walletState,
        identityPubKey = if (walletState == WalletState.NONE) "" else identityPubKey,
    )

    private fun balance() = Balance(confirmedSat = confirmedSat, pendingInSat = pendingInSat)

    private fun pendingCount() = entries.count { it.status == EntryStatus.PENDING }.toLong()

    private fun newEntry(
        kind: EntryKind,
        amount: Long,
        status: EntryStatus = EntryStatus.PENDING,
        fee: Long = 0,
        note: String = "",
        progress: EntryProgress? = null,
    ): Entry {
        val now = Clock.System.now()
        val e = Entry(
            id = "${kind.wire}-${nextId++}",
            kind = kind,
            status = status,
            amountSat = amount,
            feeSat = fee,
            createdAt = now,
            updatedAt = now,
            note = note,
            cursor = nextCursor++,
            progress = progress,
        )
        entries += e
        return e
    }

    private fun touch(e: Entry, index: Int): Entry {
        val updated = e.copy(updatedAt = Clock.System.now(), cursor = nextCursor++)
        entries[index] = updated
        return updated
    }

    private fun publish(entry: Entry) {
        val subs = synchronized(lock) { subscriptions.toList() }
        subs.forEach { it.offer(entry) }
    }

    private fun record(verb: String, body: String) {
        _requests += verb to body
        failures[verb]?.removeFirstOrNull()?.let { throw FakeBindingException(it) }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun requireRunning(verb: String) {
        if (!running) throw FakeBindingException("wavewalletdk mobile not started")
    }

    private fun requireWallet(verb: String) {
        requireRunning(verb)
        if (walletState == WalletState.NONE || walletState == WalletState.LOCKED) {
            precondition(if (walletState == WalletState.NONE) "wallet not created" else "wallet is locked")
        }
    }

    private fun requireReady(verb: String) {
        requireWallet(verb)
        if (walletState != WalletState.READY) precondition("wallet is not ready")
    }

    private fun precondition(desc: String): Nothing =
        throw FakeBindingException("rpc error: code = FailedPrecondition desc = $desc")

    private fun error(message: String): Nothing = throw FakeBindingException(message)

    private fun <T> encode(serializer: KSerializer<T>, value: T): ByteArray =
        json.encodeToString(serializer, value).encodeToByteArray()

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.strings(key: String): List<String> =
        this[key]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
    private fun JsonObject.long(key: String): Long = this[key]?.jsonPrimitive?.longOrNull ?: 0
    private fun JsonObject.bool(key: String): Boolean = this[key]?.jsonPrimitive?.booleanOrNull ?: false

    private inner class FakeSubscription(private val kinds: Set<EntryKind>) : FacadeSubscription, SuspendingSubscription {
        private val channel = Channel<Result<Entry>>(Channel.UNLIMITED)

        fun offer(entry: Entry) {
            if (kinds.isEmpty() || entry.kind in kinds) channel.trySend(Result.success(entry))
        }

        fun fail(message: String) {
            channel.trySend(Result.failure(FakeBindingException(message)))
            channel.close()
        }

        fun end() {
            channel.close()
        }

        override fun next(): ByteArray? {
            val entry = runBlocking { receive() } ?: return null
            return encode(Entry.serializer(), entry)
        }

        override suspend fun receive(): Entry? {
            synchronized(lock) { failures["next"]?.removeFirstOrNull() }?.let { throw FakeBindingException(it) }
            val item = channel.receiveCatching().getOrNull() ?: return null
            return item.getOrThrow()
        }

        override fun close() {
            synchronized(lock) { subscriptions.remove(this) }
            channel.close()
        }
    }
}

/** A non-blocking view of a fake activity subscription. */
public interface SuspendingSubscription {
    /** The next entry, or null at a clean end; throws on a stream error. */
    public suspend fun receive(): Entry?

    public fun close()
}

/** An error thrown by [FakeFacadeBindings], carrying Go-style message text. */
public class FakeBindingException(message: String) : Exception(message)
