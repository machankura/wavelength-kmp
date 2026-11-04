package engineering.lightning.wavelength.engine

import engineering.lightning.wavelength.CreateWalletRequest
import engineering.lightning.wavelength.DescriptorWalletRequest
import engineering.lightning.wavelength.ListWalletDescriptorsRequest
import engineering.lightning.wavelength.Network
import engineering.lightning.wavelength.ReceiveRequest
import engineering.lightning.wavelength.RuntimeConfig
import engineering.lightning.wavelength.RemoteSignerConfig
import engineering.lightning.wavelength.SendRequest
import engineering.lightning.wavelength.WavelengthClient
import engineering.lightning.wavelength.WavelengthException
import engineering.lightning.wavelength.model.EntryStatus
import engineering.lightning.wavelength.model.WalletState
import engineering.lightning.wavelength.testing.FakeFacadeBindings
import engineering.lightning.wavelength.testing.FakeWavelengthClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class WalletEngineTest {
    private val config = RuntimeConfig(network = Network.REGTEST, dataDir = "/tmp/w")
    private val password = "password1"

    private fun TestScope.engine(bindings: FakeFacadeBindings = FakeFacadeBindings()): Pair<WalletEngine, FakeFacadeBindings> {
        val engine = WalletEngine(FakeWavelengthClient(bindings), backgroundScope, config)
        return engine to bindings
    }

    private fun WalletEngine.phase() = snapshot.value.phase

    @Test
    fun startsReadyForAStart() = runTest {
        val (engine, _) = engine()
        assertEquals(RuntimePhase.RUNTIME_READY, engine.phase())
    }

    @Test
    fun startWithoutWalletNeedsWallet() = runTest {
        val (engine, _) = engine()
        engine.start()
        assertEquals(RuntimePhase.NEEDS_WALLET, engine.phase())
        assertEquals(WalletState.NONE, engine.snapshot.value.info?.walletState)
    }

    @Test
    fun createWalletBecomesReadyAndLoadsState() = runTest {
        val (engine, bindings) = engine()
        engine.start()
        engine.createWallet(CreateWalletRequest(password))
        runCurrent()
        assertEquals(RuntimePhase.READY, engine.phase())
        assertEquals(0L, engine.snapshot.value.balance?.confirmedSat)
        // The activity stream opened (includeExisting for a fresh cursor).
        assertTrue(bindings.requests.any { it.first == "subscribe" })
    }

    @Test
    fun activityTriggersDebouncedRefreshAndSettles() = runTest {
        val (engine, bindings) = engine()
        engine.start()
        engine.createWallet(CreateWalletRequest(password))
        runCurrent()

        val receive = engine.receive(ReceiveRequest(21_000))
        runCurrent()
        bindings.settle(receive.entry.id)
        runCurrent()
        // Nothing until the debounce elapses.
        assertEquals(0L, engine.snapshot.value.balance?.confirmedSat)
        advanceTimeBy(EngineConstants.ACTIVITY_DEBOUNCE + 1.milliseconds)
        runCurrent()
        assertEquals(21_000L, engine.snapshot.value.balance?.confirmedSat)
        assertEquals(EntryStatus.COMPLETE, engine.snapshot.value.activity.first().status)

        // The settle probes keep going until the balance holds, then stop.
        val balanceCallsBefore = bindings.requests.count { it.first == "balance" }
        advanceTimeBy(10.seconds)
        val probes = bindings.requests.count { it.first == "balance" } - balanceCallsBefore
        assertTrue(probes in 1..3, "settle probes: $probes")
    }

    @Test
    fun lockedOnRestartThenUnlock() = runTest {
        val (engine, _) = engine()
        engine.start()
        engine.createWallet(CreateWalletRequest(password))
        engine.stop()
        assertEquals(RuntimePhase.STOPPED, engine.phase())
        assertNull(engine.snapshot.value.info)
        engine.start()
        assertEquals(RuntimePhase.LOCKED, engine.phase())
        engine.unlockWallet(password.encodeToByteArray())
        assertEquals(RuntimePhase.READY, engine.phase())
    }

    @Test
    fun syncingPollsUntilReady() = runTest {
        val bindings = FakeFacadeBindings()
        val (engine, _) = engine(bindings)
        engine.start()
        engine.createWallet(CreateWalletRequest(password))
        bindings.setWalletState(WalletState.SYNCING)
        engine.refresh()
        assertEquals(RuntimePhase.SYNCING, engine.phase())

        bindings.setWalletState(WalletState.READY)
        advanceTimeBy(EngineConstants.SYNC_POLL + 1.milliseconds)
        runCurrent()
        assertEquals(RuntimePhase.READY, engine.phase())
    }

    @Test
    fun syncPollExhaustionIsAnError() = runTest {
        val bindings = FakeFacadeBindings()
        val (engine, _) = engine(bindings)
        engine.start()
        engine.createWallet(CreateWalletRequest(password))
        bindings.setWalletState(WalletState.SYNCING)
        engine.refresh()
        repeat(EngineConstants.SYNC_POLL_FAILURE_LIMIT) { bindings.failNext("getInfo", "rpc error: code = Unavailable desc = down") }
        advanceTimeBy(EngineConstants.SYNC_POLL * (EngineConstants.SYNC_POLL_FAILURE_LIMIT + 1))
        runCurrent()
        assertEquals(RuntimePhase.ERROR, engine.phase())
        assertNotNull(engine.snapshot.value.error)
    }

    @Test
    fun streamReconnectsFromItsCursorAfterALoss() = runTest {
        val (engine, bindings) = engine()
        engine.start()
        engine.createWallet(CreateWalletRequest(password))
        runCurrent()
        engine.receive(ReceiveRequest(1_000))
        runCurrent()

        bindings.breakStreams("stream reset")
        runCurrent()
        advanceTimeBy(EngineConstants.STREAM_BACKOFF + 1.milliseconds)
        runCurrent()
        assertEquals(RuntimePhase.READY, engine.phase())
        assertEquals(2, bindings.requests.count { it.first == "subscribe" })
    }

    @Test
    fun repeatedOpenFailuresKillTheStream() = runTest {
        val (engine, bindings) = engine()
        engine.start()
        engine.createWallet(CreateWalletRequest(password))
        runCurrent()
        repeat(10) { bindings.failNext("subscribe", "rpc error: code = Unavailable desc = down") }
        bindings.breakStreams()
        advanceTimeBy(5.seconds * 60)
        runCurrent()
        assertEquals(RuntimePhase.ERROR, engine.phase())
        assertEquals(1 + EngineConstants.STREAM_FAILURE_LIMIT, bindings.requests.count { it.first == "subscribe" })
    }

    @Test
    fun sendPreimageIsRetainedInActivity() = runTest {
        val (engine, bindings) = engine(FakeFacadeBindings(initialConfirmedSat = 0))
        engine.start()
        engine.createWallet(CreateWalletRequest(password))
        runCurrent()
        val r = engine.receive(ReceiveRequest(50_000))
        bindings.settle(r.entry.id)
        runCurrent()

        val sent = engine.send(SendRequest.Invoice("lntbs1000n1pfakeinvoice"))
        runCurrent()
        advanceTimeBy(1.seconds)
        runCurrent()
        val hash = assertNotNull(sent.paymentHash)
        val entry = engine.snapshot.value.activity.first { it.progress?.paymentHash == hash }
        // The fake, like the daemon, omits preimages from list(); the engine restores it.
        assertEquals("preimage-$hash", entry.progress?.preimage)
        val listed = engine.list().activity!!.entries.first { it.progress?.paymentHash == hash }
        assertEquals("preimage-$hash", listed.progress?.preimage)
    }

    @Test
    fun restoreResolvesOnceUsableAndTracksRecovery() = runTest {
        val (engine, _) = engine()
        engine.start()
        val restored = async { engine.restoreWallet(CreateWalletRequest(password, mnemonic = List(24) { "w$it" }, recoverState = true)) }
        runCurrent()
        val info = restored.await()
        assertEquals(WalletState.READY, info.walletState)
        assertEquals(RuntimePhase.READY, engine.phase())
        assertIs<RecoveryState.Done>(engine.snapshot.value.recovery)
        engine.acknowledgeRecovery()
        assertEquals(RecoveryState.Idle, engine.snapshot.value.recovery)
    }

    @Test
    fun restoresFromDescriptors() = runTest {
        val (engine, bindings) = engine()
        engine.start()
        val restored = async {
            engine.restoreWallet(CreateWalletRequest(password, descriptors = listOf("tr(tprv1)"), recoverState = true))
        }
        runCurrent()
        assertEquals(WalletState.READY, restored.await().walletState)
        assertEquals(RuntimePhase.READY, engine.phase())
        assertIs<RecoveryState.Done>(engine.snapshot.value.recovery)
        assertEquals(1, bindings.requests.count { it.first == "createWallet" && "Descriptors" in it.second })
    }

    @Test
    fun restoresWatchOnlyAndCarriesTheManifestHash() = runTest {
        val bindings = FakeFacadeBindings()
        val signed = config.copy(remoteSigner = RemoteSignerConfig("host:1", "/m", "/c"))
        val engine = WalletEngine(FakeWavelengthClient(bindings), backgroundScope, signed)
        engine.start()
        val restored = async {
            engine.restoreWallet(CreateWalletRequest(password, watchOnlyManifest = "{}", authKeys = "keys"))
        }
        runCurrent()
        assertEquals(WalletState.READY, restored.await().walletState)
        assertEquals(bindings.fakeManifestHash, engine.snapshot.value.info?.manifestHash)
    }

    @Test
    fun aRestoreNeedsExactlyOneSource() = runTest {
        val (engine, bindings) = engine()
        engine.start()
        listOf(
            CreateWalletRequest(password),
            CreateWalletRequest(password, recoverState = true),
            CreateWalletRequest(password, mnemonic = listOf("w"), descriptors = listOf("tr(tprv1)")),
            CreateWalletRequest(password, descriptors = listOf("tr(tprv1)"), watchOnlyBundle = "b"),
            CreateWalletRequest(password, watchOnlyBundle = "b", watchOnlyManifest = "m", authKeys = "k"),
        ).forEach {
            assertFailsWith<WavelengthException.InvalidArgument>("$it") { engine.restoreWallet(it) }
        }
        assertEquals(RuntimePhase.NEEDS_WALLET, engine.phase())
        assertEquals(0, bindings.requests.count { it.first == "createWallet" })
    }

    @Test
    fun aRestoreLeavingALockedWalletMovesToLocked() = runTest {
        val bindings = FakeFacadeBindings()
        val signed = config.copy(remoteSigner = RemoteSignerConfig("host:1", "/m", "/c"))
        val engine = WalletEngine(FakeWavelengthClient(bindings), backgroundScope, signed)
        engine.start()
        bindings.setWalletState(WalletState.LOCKED)
        bindings.failNext("createWallet", "verify remote signer: remote signer does not hold this wallet's keys")
        assertFailsWith<WavelengthException.RemoteSignerFailed> {
            engine.restoreWallet(CreateWalletRequest(password, watchOnlyBundle = "b"))
        }
        assertEquals(RuntimePhase.LOCKED, engine.phase())
        assertEquals(false, assertIs<RecoveryState.Failed>(engine.snapshot.value.recovery).walletUsable)
    }

    @Test
    fun aCreateLeavingALockedWalletMovesToLocked() = runTest {
        val (engine, bindings) = engine()
        engine.start()
        bindings.setWalletState(WalletState.LOCKED)
        bindings.failNext("createWallet", "signer does not hold the key for a wallet slot: [0]")
        assertFailsWith<WavelengthException.RemoteSignerFailed> {
            engine.createWallet(CreateWalletRequest(password, watchOnlyBundle = "b"))
        }
        assertEquals(RuntimePhase.LOCKED, engine.phase())
    }

    @Test
    fun aCreateLeavingNoWalletStaysOnNeedsWallet() = runTest {
        val (engine, bindings) = engine()
        engine.start()
        bindings.failNext("createWallet", "connect to remote signer: refused")
        assertFailsWith<WavelengthException.RemoteSignerFailed> {
            engine.createWallet(CreateWalletRequest(password, watchOnlyBundle = "b"))
        }
        assertEquals(RuntimePhase.NEEDS_WALLET, engine.phase())
    }

    @Test
    fun aWalletOpenedAtomicallyIsAdoptedByStart() = runTest {
        val client = FakeWavelengthClient()
        client.startDescriptorWallet(config, DescriptorWalletRequest(listOf("tr(tprv1)")))
        val engine = WalletEngine(client, backgroundScope, config)
        engine.start()
        runCurrent()
        assertEquals(RuntimePhase.READY, engine.phase())
    }

    @Test
    fun exportsDescriptorsAndKeepsNoCopy() = runTest {
        val (engine, bindings) = engine()
        engine.start()
        engine.createWallet(CreateWalletRequest(password))
        val before = engine.snapshot.value.toString()
        assertEquals(bindings.publicDescriptors, engine.listWalletDescriptors().descriptors)
        val secret = engine.listWalletDescriptors(ListWalletDescriptorsRequest(includePrivate = true, descriptors = listOf("tr(tprv1)")))
        assertTrue(secret.includesPrivate)
        assertEquals(before, engine.snapshot.value.toString())
    }

    @Test
    fun exportNeedsADescriptorClient() = runTest {
        val plain = object : WavelengthClient by FakeWavelengthClient() {}
        val engine = WalletEngine(plain, backgroundScope, config)
        assertFailsWith<WavelengthException.UnsupportedFacadeMethod> { engine.listWalletDescriptors() }
    }

    @Test
    fun failedRestoreFallsBackToNeedsWallet() = runTest {
        val (engine, bindings) = engine()
        engine.start()
        bindings.failNext("createWallet", "invalid mnemonic")
        assertFailsWith<Exception> { engine.restoreWallet(CreateWalletRequest(password, mnemonic = listOf("bad"), recoverState = true)) }
        assertEquals(RuntimePhase.NEEDS_WALLET, engine.phase())
        val recovery = assertIs<RecoveryState.Failed>(engine.snapshot.value.recovery)
        assertEquals(false, recovery.walletUsable)
    }

    @Test
    fun startFailureIsAnErrorAndRetryable() = runTest {
        val (engine, bindings) = engine()
        bindings.failNext("start", "start embedded wallet: boom")
        assertFailsWith<Exception> { engine.start() }
        assertEquals(RuntimePhase.ERROR, engine.phase())
        engine.start()
        assertEquals(RuntimePhase.NEEDS_WALLET, engine.phase())
        assertNull(engine.snapshot.value.error)
    }

    @Test
    fun backgroundRefreshExhaustionIsAnError() = runTest {
        val (engine, bindings) = engine()
        engine.start()
        engine.createWallet(CreateWalletRequest(password))
        runCurrent()
        repeat(40) { bindings.failNext("balance", "rpc error: code = Unavailable desc = down") }
        repeat(EngineConstants.BACKGROUND_REFRESH_FAILURE_LIMIT) {
            engine.deposit()
            runCurrent()
        }
        assertEquals(RuntimePhase.ERROR, engine.phase())
    }

    @Test
    fun runtimeLogsAreKept() = runTest {
        val (engine, _) = engine()
        engine.start()
        runCurrent()
        assertTrue(engine.snapshot.value.logs.any { it.message.contains("starting wavelength runtime") })
        engine.clearLogs()
        assertTrue(engine.snapshot.value.logs.isEmpty())
    }

    @Test
    fun closedEngineRejectsCalls() = runTest {
        val (engine, _) = engine()
        engine.close()
        assertFailsWith<IllegalStateException> { engine.start() }
    }
}
