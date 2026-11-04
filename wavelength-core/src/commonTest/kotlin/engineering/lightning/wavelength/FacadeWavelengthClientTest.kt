package engineering.lightning.wavelength

import engineering.lightning.wavelength.model.EntryKind
import engineering.lightning.wavelength.model.EntryStatus
import engineering.lightning.wavelength.model.WalletState
import engineering.lightning.wavelength.testing.FakeFacadeBindings
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Runs on real threads: the activity pump blocks a thread in next(). */
internal fun realTimeTest(block: suspend CoroutineScope.() -> Unit): TestResult =
    runTest { withContext(Dispatchers.Default) { block() } }

class FacadeWavelengthClientTest {
    private val config = RuntimeConfig(network = Network.REGTEST, dataDir = "/tmp/w")
    private val bindings = FakeFacadeBindings()
    private val client = FacadeWavelengthClient(bindings, Dispatchers.Default)

    private fun lastRequest(verb: String) =
        Json.parseToJsonElement(bindings.requests.last { it.first == verb }.second).jsonObject

    private suspend fun readyWallet(balance: Long = 0) {
        client.start(config)
        client.createWallet(CreateWalletRequest("password1"))
        if (balance > 0) {
            val r = client.receive(ReceiveRequest(balance))
            bindings.settle(r.entry.id)
        }
    }

    @Test
    fun startSendsSnakeCaseConfigAndReturnsInfo() = realTimeTest {
        val info = client.start(config)
        assertEquals(WalletState.NONE, info.walletState)
        assertFalse(info.walletReady)
        assertEquals(RuntimeLifecycle.RUNNING, client.lifecycle.value)
        val sent = Json.parseToJsonElement(bindings.lastConfigJson!!).jsonObject
        assertEquals("regtest", sent["network"]?.jsonPrimitive?.content)
        assertEquals("/tmp/w", sent["data_dir"]?.jsonPrimitive?.content)
    }

    @Test
    fun secondStartReturnsCurrentInfoWithoutRebooting() = realTimeTest {
        client.start(config)
        client.start(config.copy(network = Network.SIGNET))
        assertEquals(1, bindings.requests.count { it.first == "start" })
    }

    @Test
    fun invalidConfigNeverReachesTheRuntime() = realTimeTest {
        assertFailsWith<WavelengthException.InvalidConfig> { client.start(RuntimeConfig(network = Network.MAINNET)) }
        assertTrue(bindings.requests.isEmpty())
    }

    @Test
    fun failedStartReturnsToStopped() = realTimeTest {
        bindings.failNext("start", "start embedded wallet: boom")
        assertFailsWith<WavelengthException.Daemon> { client.start(config) }
        assertEquals(RuntimeLifecycle.STOPPED, client.lifecycle.value)
        client.start(config)
        assertEquals(RuntimeLifecycle.RUNNING, client.lifecycle.value)
    }

    @Test
    fun concurrentLifecycleCallsAreSerialised() = realTimeTest {
        (1..8).map { i ->
            async { if (i % 2 == 0) client.stop() else client.start(config) }
        }.awaitAll()
        // Never "already started": every start saw the result of the previous op.
        assertTrue(bindings.requests.none { it.first == "start" && it.second.isEmpty() })
        client.stop()
        assertEquals(RuntimeLifecycle.STOPPED, client.lifecycle.value)
        assertFalse(client.isRunning())
    }

    @Test
    fun stopIsIdempotentAndEmitsStopped() = realTimeTest {
        client.start(config)
        val stopped = async(start = CoroutineStart.UNDISPATCHED) { client.events.first { it == WavelengthEvent.RuntimeStopped } }
        yield()
        client.stop()
        client.stop()
        withTimeout(5.seconds) { stopped.await() }
        assertEquals(1, bindings.requests.count { it.first == "stop" })
    }

    @Test
    fun callsBeforeStartAreRuntimeNotReady() = realTimeTest {
        assertFailsWith<WavelengthException.RuntimeNotReady> { client.getInfo() }
    }

    @Test
    fun createWalletSendsPascalCaseBase64Password() = realTimeTest {
        client.start(config)
        val r = client.createWallet(CreateWalletRequest("password1"))
        assertEquals(24, r.mnemonic.size)
        val req = lastRequest("createWallet")
        assertEquals(Base64.encode("password1".encodeToByteArray()), req["WalletPassword"]?.jsonPrimitive?.content)
        assertTrue(client.getInfo().walletReady)
    }

    @Test
    fun shortPasswordIsRejectedLocally() = realTimeTest {
        client.start(config)
        assertFailsWith<WavelengthException.InvalidArgument> { client.createWallet(CreateWalletRequest("short")) }
        assertTrue(bindings.requests.none { it.first == "createWallet" })
    }

    @Test
    fun walletCallBeforeCreateIsFailedPrecondition() = realTimeTest {
        client.start(config)
        assertFailsWith<WavelengthException.FailedPrecondition> { client.balance() }
    }

    @Test
    fun restartLocksAndUnlockReopens() = realTimeTest {
        readyWallet()
        client.stop()
        assertEquals(WalletState.LOCKED, client.start(config).walletState)
        client.unlockWallet("password1".encodeToByteArray())
        assertTrue(client.walletReady())
    }

    @Test
    fun receiveAndSettle() = realTimeTest {
        readyWallet()
        val r = client.receive(ReceiveRequest(10_000, memo = "coffee", timeoutSeconds = 30))
        assertEquals(EntryStatus.PENDING, r.entry.status)
        val req = lastRequest("receive")
        assertEquals(30, req["TimeoutSeconds"]?.jsonPrimitive?.content?.toInt())
        assertEquals("coffee", req["Memo"]?.jsonPrimitive?.content)
        assertEquals(10_000, client.pendingInboundSat())
        bindings.settle(r.entry.id)
        assertEquals(10_000, client.balance().confirmedSat)
        assertEquals(10_000, client.confirmedBalanceSat())
    }

    @Test
    fun receiveValidatesArguments() = realTimeTest {
        readyWallet()
        assertFailsWith<WavelengthException.InvalidArgument> { client.receive(ReceiveRequest(0)) }
        assertFailsWith<WavelengthException.InvalidArgument> { client.receive(ReceiveRequest(1, timeoutSeconds = 301)) }
    }

    @Test
    fun prepareThenSendCarriesPaymentHash() = realTimeTest {
        readyWallet(balance = 50_000)
        val quote = client.prepareSend(SendRequest.Invoice("lntbs1000n1pinvoice", maxFeeSat = 50))
        assertTrue(quote.feeKnown)
        assertEquals("hash-" + "lntbs1000n1pinvoice".takeLast(8), quote.paymentHash)
        val sent = client.sendPrepared(quote)
        assertEquals(quote.paymentHash, sent.paymentHash)
        assertEquals(50_000 - quote.expectedTotalOutflowSat, client.balance().confirmedSat)

        // The intent is single-use.
        assertFailsWith<WavelengthException.InvalidSendIntent> { client.sendPrepared(quote) }
        val req = lastRequest("sendPrepared")
        assertEquals(quote.sendIntentId, req["SendIntentID"]?.jsonPrimitive?.content)
    }

    @Test
    fun onchainSendUsesOnchainAddressKey() = realTimeTest {
        readyWallet(balance = 50_000)
        client.send(SendRequest.Onchain("tb1qdest", amountSat = 1_000))
        val req = lastRequest("prepareSend")
        assertEquals("tb1qdest", req["OnchainAddress"]?.jsonPrimitive?.content)
        assertNull(req["Invoice"])
    }

    @Test
    fun listActivityTyped() = realTimeTest {
        readyWallet(balance = 5_000)
        val activity = client.listActivity()
        assertEquals(1, activity.entries.size)
        assertEquals(EntryKind.RECEIVE, activity.entries.single().kind)
    }

    @Test
    fun activityStreamsEntriesAndClosesOnCancel() = realTimeTest {
        readyWallet()
        val received = async { client.activity().take(2).toList() }
        // Wait for the subscription to be registered before producing entries.
        withTimeout(5.seconds) { while (bindings.requests.none { it.first == "subscribe" }) yield() }
        val r = client.receive(ReceiveRequest(1_000))
        bindings.settle(r.entry.id)
        val entries = withTimeout(5.seconds) { received.await() }
        assertEquals(listOf(EntryStatus.PENDING, EntryStatus.COMPLETE), entries.map { it.status })
        assertTrue(entries[1].cursor > entries[0].cursor)
    }

    @Test
    fun activityReplaysExistingAndFiltersKinds() = realTimeTest {
        readyWallet(balance = 2_000)
        val first = withTimeout(5.seconds) {
            client.activity(ActivityOptions(includeExisting = true, kinds = setOf(EntryKind.RECEIVE))).first()
        }
        assertEquals(EntryKind.RECEIVE, first.kind)
        val req = lastRequest("subscribe")
        assertEquals("true", req["IncludeExisting"]?.jsonPrimitive?.content)
    }

    @Test
    fun activityEndsCleanlyOnStop() = realTimeTest {
        readyWallet()
        val collected = async { client.activity().toList() }
        withTimeout(5.seconds) { while (bindings.requests.none { it.first == "subscribe" }) yield() }
        client.stop()
        assertEquals(emptyList(), withTimeout(5.seconds) { collected.await() })
    }

    @Test
    fun brokenStreamFailsWithTypedErrorAndEvent() = realTimeTest {
        readyWallet()
        val event = async(start = CoroutineStart.UNDISPATCHED) { client.events.first { it is WavelengthEvent.ActivityStreamEnded } }
        val collected = async { runCatching { client.activity().toList() } }
        withTimeout(5.seconds) { while (bindings.requests.none { it.first == "subscribe" }) yield() }
        bindings.breakStreams("wallet subscription gap at cursor 7: overflow")
        val error = withTimeout(5.seconds) { collected.await() }.exceptionOrNull()
        assertIs<WavelengthException.StreamGap>(error)
        assertEquals(7, error.cursor)
        val ended = withTimeout(5.seconds) { event.await() }
        assertIs<WavelengthException.StreamGap>((ended as WavelengthEvent.ActivityStreamEnded).error)
    }

    @Test
    fun negativeCursorIsRejected() = realTimeTest {
        readyWallet()
        assertFailsWith<WavelengthException.InvalidCursor> { client.activity(ActivityOptions(cursor = -1)).first() }
    }

    @Test
    fun externalSeedStart() = realTimeTest {
        val entropy = ByteArray(16) { it.toByte() }
        val result = client.startExternalSeedWallet(config, ExternalSeedWalletRequest(entropy, recoverState = true))
        assertTrue(result.imported)
        val body = Json.parseToJsonElement(bindings.requests.last().second).jsonObject
        assertEquals(Base64.encode(entropy), body["seed_entropy"]?.jsonPrimitive?.content)
        assertEquals("true", body["recover_state"]?.jsonPrimitive?.content)
        assertEquals("/tmp/w", body["config"]?.jsonObject?.get("data_dir")?.jsonPrimitive?.content)
        assertTrue(client.walletReady())

        // A second external-seed start while running is refused, not ignored.
        assertFailsWith<WavelengthException.RuntimeActive> {
            client.startExternalSeedWallet(config, ExternalSeedWalletRequest(entropy))
        }
    }

    @Test
    fun externalSeedValidation() = realTimeTest {
        assertFailsWith<WavelengthException.InvalidExternalSeed> {
            client.startExternalSeedWallet(config, ExternalSeedWalletRequest(ByteArray(15)))
        }
        assertFailsWith<WavelengthException.InvalidConfig> {
            client.startExternalSeedWallet(config.copy(dataDir = null), ExternalSeedWalletRequest(ByteArray(16)))
        }
    }

    @Test
    fun descriptorRestoreSendsTheDescriptorsAndBirthday() = realTimeTest {
        client.start(config)
        val descriptors = listOf("tr(tprv1/86h/0h/0h/<0;1>/*)#aaa", "wpkh(tprv1/84h/0h/0h/<0;1>/*)#bbb")
        val result = client.restoreWalletFromDescriptors(
            "password1".encodeToByteArray(),
            descriptors,
            Instant.fromEpochSeconds(1_767_225_600),
            recoveryWindow = 2500,
        )
        assertEquals(bindings.rootFingerprint, result.rootFingerprint)
        assertEquals(bindings.publicDescriptors, result.descriptors)
        assertEquals(emptyList(), result.mnemonic)
        assertEquals(
            """{"WalletPassword":"${Base64.encode("password1".encodeToByteArray())}","RecoverState":true,"RecoveryWindow":2500,""" +
                """"Descriptors":["tr(tprv1/86h/0h/0h/<0;1>/*)#aaa","wpkh(tprv1/84h/0h/0h/<0;1>/*)#bbb"],""" +
                """"Birthday":"2026-01-01T00:00:00Z"}""",
            bindings.requests.last { it.first == "createWallet" }.second,
        )
    }

    @Test
    fun walletBirthdayIsInUnixSeconds() {
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), walletBirthday(1_767_225_600))
    }

    @Test
    fun descriptorCreateValidation() = realTimeTest {
        val descriptors = listOf("tr(tprv1/86h/0h/0h/<0;1>/*)")
        client.start(config)
        listOf(
            CreateWalletRequest("password1", mnemonic = listOf("word"), descriptors = descriptors),
            CreateWalletRequest("password1", seedPassphrase = "pass", descriptors = descriptors),
            CreateWalletRequest("password1", descriptors = listOf(" ")),
            CreateWalletRequest("password1", descriptors = descriptors, birthday = Instant.fromEpochSeconds(-1)),
        ).forEach {
            assertFailsWith<WavelengthException.InvalidDescriptor>("$it") { client.createWallet(it) }
        }
        assertFailsWith<WavelengthException.InvalidDescriptor> {
            client.restoreWalletFromDescriptors("password1".encodeToByteArray(), emptyList())
        }
        assertEquals(0, bindings.requests.count { it.first == "createWallet" })
    }

    @Test
    fun descriptorStartIsAtomicAndPinsTheEnvelope() = realTimeTest {
        val request = DescriptorWalletRequest(
            descriptors = listOf("tr([d34db33f/86h/0h/0h]tprv1/<0;1>/*)#aaa"),
            birthday = Instant.fromEpochSeconds(1_767_225_600, 999_000_000),
            expectedIdentityPubKey = bindings.identityPubKey,
            recoverState = true,
            recoveryWindow = 2500,
        )
        val result = client.startDescriptorWallet(config, request)
        assertTrue(result.imported)
        assertEquals(bindings.rootFingerprint, result.rootFingerprint)
        assertEquals(bindings.publicDescriptors, result.descriptors)
        assertTrue(client.walletReady())
        assertEquals(listOf("startDescriptorWallet"), bindings.requests.map { it.first }.filter { it != "walletReady" })
        assertEquals(
            """{"config":{"data_dir":"/tmp/w","network":"regtest","server_transport":"grpc","wallet_type":"lwwallet",""" +
                """"swap_server_transport":"grpc"},"descriptors":["tr([d34db33f/86h/0h/0h]tprv1/<0;1>/*)#aaa"],""" +
                """"birthday_unix":1767225600,"expected_identity_pubkey":"${bindings.identityPubKey}",""" +
                """"recover_state":true,"recovery_window":2500}""",
            bindings.requests.first().second,
        )
    }

    @Test
    fun descriptorStartIsRefusedWhileRunning() = realTimeTest {
        client.start(config)
        assertFailsWith<WavelengthException.RuntimeActive> {
            client.startDescriptorWallet(config, DescriptorWalletRequest(listOf("tr(tprv1)")))
        }
        assertEquals(0, bindings.requests.count { it.first == "startDescriptorWallet" })
    }

    @Test
    fun descriptorStartValidation() = realTimeTest {
        val descriptors = listOf("tr(tprv1)")
        listOf(
            DescriptorWalletRequest(emptyList()),
            DescriptorWalletRequest(listOf(" ")),
            DescriptorWalletRequest(descriptors, birthday = Instant.fromEpochSeconds(-1)),
            DescriptorWalletRequest(descriptors, expectedIdentityPubKey = ""),
            DescriptorWalletRequest(descriptors, recoveryWindow = -1),
            DescriptorWalletRequest(descriptors, recoveryWindow = 0x1_0000_0000),
        ).forEach {
            assertFailsWith<WavelengthException.InvalidDescriptor>("$it") { client.startDescriptorWallet(config, it) }
        }
        assertFailsWith<WavelengthException.InvalidConfig> {
            client.startDescriptorWallet(config.copy(dataDir = null), DescriptorWalletRequest(descriptors))
        }
        assertTrue(bindings.requests.isEmpty())
        assertEquals(RuntimeLifecycle.STOPPED, client.lifecycle.value)
    }

    @Test
    fun descriptorStartZeroesItsRequestEvenOnFailure() = realTimeTest {
        val seen = mutableListOf<ByteArray>()
        val spying = object : FacadeBindings by bindings {
            override fun startDescriptorWallet(request: ByteArray): ByteArray {
                seen += request
                return bindings.startDescriptorWallet(request)
            }
        }
        val spied = FacadeWavelengthClient(spying, Dispatchers.Default)
        val request = DescriptorWalletRequest(listOf("tr(tprv1)"))

        spied.startDescriptorWallet(config, request)
        spied.stop()
        bindings.failNext("startDescriptorWallet", "invalid descriptors: bad key")
        assertFailsWith<WavelengthException.InvalidDescriptor> { spied.startDescriptorWallet(config, request) }

        assertEquals(2, seen.size)
        seen.forEach { bytes -> assertTrue(bytes.all { it == 0.toByte() }) }
        assertEquals(RuntimeLifecycle.STOPPED, spied.lifecycle.value)
    }

    @Test
    fun descriptorStartEmitsRuntimeReadyAndCanReopen() = realTimeTest {
        val request = DescriptorWalletRequest(listOf("tr(tprv1)"))
        val ready = async(start = CoroutineStart.UNDISPATCHED) { client.events.first { it == WavelengthEvent.RuntimeReady } }
        yield()
        assertTrue(client.startDescriptorWallet(config, request).imported)
        withTimeout(5.seconds) { ready.await() }
        client.stop()
        assertFalse(client.startDescriptorWallet(config, request).imported)
        assertEquals(RuntimeLifecycle.RUNNING, client.lifecycle.value)
    }

    @Test
    fun descriptorRequestHidesItsKeys() {
        val text = DescriptorWalletRequest(listOf("tr(tprvSECRET)")).toString()
        assertFalse("tprvSECRET" in text, text)
        assertTrue("descriptors=<1>" in text, text)
    }

    private val signed = config.copy(remoteSigner = RemoteSignerConfig("10.0.2.2:10019", "/s/signer.macaroon", "/s/tls.cert"))

    @Test
    fun watchOnlyBundleStartPinsTheEnvelope() = realTimeTest {
        val request = WatchOnlyWalletRequest(
            walletPassword = "password1".encodeToByteArray(),
            bundle = "bundle text",
            birthday = Instant.fromEpochSeconds(1_767_225_600),
            expectedIdentityPubKey = bindings.identityPubKey,
            recoverState = true,
            recoveryWindow = 2500,
        )
        val result = client.startWatchOnlyWallet(signed, request)
        assertTrue(result.imported)
        assertEquals("", result.manifestHash)
        assertEquals(bindings.publicDescriptors, result.descriptors)
        assertTrue(client.walletReady())
        assertEquals(
            """{"config":{"data_dir":"/tmp/w","network":"regtest","server_transport":"grpc","wallet_type":"lwwallet",""" +
                """"swap_server_transport":"grpc","remote_signer_rpc_host":"10.0.2.2:10019",""" +
                """"remote_signer_macaroon_path":"/s/signer.macaroon","remote_signer_tls_cert_path":"/s/tls.cert"},""" +
                """"bundle":"bundle text","wallet_password":"${Base64.encode("password1".encodeToByteArray())}",""" +
                """"birthday_unix":1767225600,"expected_identity_pubkey":"${bindings.identityPubKey}",""" +
                """"recover_state":true,"recovery_window":2500}""",
            bindings.requests.first().second,
        )
    }

    @Test
    fun watchOnlyManifestStartPinsTheEnvelope() = realTimeTest {
        val request = WatchOnlyWalletRequest(
            walletPassword = "password1".encodeToByteArray(),
            manifest = "{}",
            authKeys = "keys",
            overrideIdentity = true,
        )
        val result = client.startWatchOnlyWallet(signed, request)
        assertEquals(bindings.fakeManifestHash, result.manifestHash)
        assertEquals(bindings.fakeManifestHash, client.getInfo().manifestHash)
        val body = Json.parseToJsonElement(bindings.requests.first().second).jsonObject
        assertEquals("{}", body["manifest"]?.jsonPrimitive?.content)
        assertEquals("keys", body["auth_keys"]?.jsonPrimitive?.content)
        assertEquals("true", body["override_identity"]?.jsonPrimitive?.content)
        assertNull(body["bundle"])
    }

    @Test
    fun watchOnlyStartValidation() = realTimeTest {
        val password = "password1".encodeToByteArray()
        listOf(
            WatchOnlyWalletRequest(ByteArray(0), bundle = "b"),
            WatchOnlyWalletRequest("short".encodeToByteArray(), bundle = "b"),
            WatchOnlyWalletRequest(password),
            WatchOnlyWalletRequest(password, bundle = "b", manifest = "m", authKeys = "k"),
            WatchOnlyWalletRequest(password, bundle = " "),
            WatchOnlyWalletRequest(password, manifest = "m"),
            WatchOnlyWalletRequest(password, manifest = "m", authKeys = " "),
            WatchOnlyWalletRequest(password, bundle = "b", authKeys = "k"),
            WatchOnlyWalletRequest(password, bundle = "b", overrideIdentity = true),
            WatchOnlyWalletRequest(password, bundle = "b", birthday = Instant.fromEpochSeconds(-1)),
            WatchOnlyWalletRequest(password, bundle = "b", expectedIdentityPubKey = ""),
            WatchOnlyWalletRequest(password, bundle = "b", recoveryWindow = -1),
        ).forEach {
            assertFailsWith<WavelengthException.InvalidWatchOnly>("$it") { client.startWatchOnlyWallet(signed, it) }
        }
        val valid = WatchOnlyWalletRequest(password, bundle = "b")
        assertFailsWith<WavelengthException.InvalidConfig> { client.startWatchOnlyWallet(config, valid) }
        assertFailsWith<WavelengthException.InvalidConfig> {
            client.startWatchOnlyWallet(signed.copy(dataDir = null), valid)
        }
        assertFailsWith<WavelengthException.InvalidConfig> {
            client.startWatchOnlyWallet(signed.copy(remoteSigner = RemoteSignerConfig(" ", "/m", "/c")), valid)
        }
        assertTrue(bindings.requests.isEmpty())
    }

    @Test
    fun watchOnlyStartReopensAndRefusesWhileRunning() = realTimeTest {
        val request = WatchOnlyWalletRequest("password1".encodeToByteArray(), bundle = "b")
        assertTrue(client.startWatchOnlyWallet(signed, request).imported)
        assertFailsWith<WavelengthException.RuntimeActive> { client.startWatchOnlyWallet(signed, request) }
        client.stop()
        assertFalse(client.startWatchOnlyWallet(signed, request).imported)
    }

    @Test
    fun aDeadSignerIsNotAWrongPassword() = realTimeTest {
        bindings.failNext("startWatchOnlyWallet", "start watch-only wallet: connect to remote signer: refused")
        val e = assertFailsWith<WavelengthException.RemoteSignerFailed> {
            client.startWatchOnlyWallet(signed, WatchOnlyWalletRequest("password1".encodeToByteArray(), bundle = "b"))
        }
        assertEquals("remote_signer_failed", e.code)
        assertEquals(RuntimeLifecycle.STOPPED, client.lifecycle.value)
    }

    @Test
    fun watchOnlyCreateThenUnlock() = realTimeTest {
        client.start(signed)
        val created = client.createWallet(CreateWalletRequest("password1", watchOnlyBundle = "bundle text"))
        assertEquals(emptyList(), created.mnemonic)
        assertEquals(bindings.publicDescriptors, created.descriptors)
        val body = Json.parseToJsonElement(bindings.requests.last { it.first == "createWallet" }.second).jsonObject
        assertEquals(Base64.encode("bundle text".encodeToByteArray()), body["WatchOnlyBundle"]?.jsonPrimitive?.content)
        assertNull(body["Mnemonic"])

        client.stop()
        client.start(signed)
        client.unlockWallet("password1".encodeToByteArray())
        assertTrue(client.walletReady())
    }

    @Test
    fun watchOnlyManifestCreateSendsItsFields() = realTimeTest {
        client.start(signed)
        val created = client.createWallet(
            CreateWalletRequest("password1", watchOnlyManifest = "{}", authKeys = "keys", overrideIdentity = true),
        )
        assertEquals(bindings.fakeManifestHash, created.manifestHash)
        val body = Json.parseToJsonElement(bindings.requests.last { it.first == "createWallet" }.second).jsonObject
        assertEquals(Base64.encode("{}".encodeToByteArray()), body["WatchOnlyManifest"]?.jsonPrimitive?.content)
        assertEquals(Base64.encode("keys".encodeToByteArray()), body["AuthKeys"]?.jsonPrimitive?.content)
        assertEquals("true", body["OverrideIdentity"]?.jsonPrimitive?.content)
    }

    @Test
    fun watchOnlyCreateValidation() = realTimeTest {
        client.start(signed)
        listOf(
            CreateWalletRequest("password1", watchOnlyBundle = "b", mnemonic = listOf("word")),
            CreateWalletRequest("password1", watchOnlyBundle = "b", seedPassphrase = "pass"),
            CreateWalletRequest("password1", watchOnlyBundle = "b", descriptors = listOf("tr(tprv1)")),
            CreateWalletRequest("password1", watchOnlyBundle = "b", watchOnlyManifest = "m", authKeys = "k"),
            CreateWalletRequest("password1", watchOnlyManifest = "m"),
            CreateWalletRequest("password1", watchOnlyBundle = "b", authKeys = "k"),
            CreateWalletRequest("password1", watchOnlyBundle = "b", overrideIdentity = true),
            CreateWalletRequest("password1", authKeys = "k"),
            CreateWalletRequest("password1", overrideIdentity = true),
            CreateWalletRequest("password1", watchOnlyBundle = " "),
            CreateWalletRequest("password1", watchOnlyBundle = "b", birthday = Instant.fromEpochSeconds(-1)),
        ).forEach {
            assertFailsWith<WavelengthException.InvalidWatchOnly>("$it") { client.createWallet(it) }
        }
        assertEquals(0, bindings.requests.count { it.first == "createWallet" })
    }

    @Test
    fun watchOnlyCreateWithoutASignerIsTyped() = realTimeTest {
        client.start(config)
        assertFailsWith<WavelengthException.InvalidWatchOnly> {
            client.createWallet(CreateWalletRequest("password1", watchOnlyBundle = "b"))
        }
    }

    @Test
    fun watchOnlyRequestsHideTheirKeys() {
        val start = WatchOnlyWalletRequest("hunter2hunter2".encodeToByteArray(), manifest = "MANIFEST", authKeys = "AUTHKEY").toString()
        val create = CreateWalletRequest("hunter2hunter2", watchOnlyBundle = "BUNDLE").toString()
        listOf("hunter2", "MANIFEST", "AUTHKEY", "BUNDLE").forEach { secret ->
            assertFalse(secret in start, start)
            assertFalse(secret in create, create)
        }
    }

    @Test
    fun createWalletRequestHidesItsKeys() {
        val text = CreateWalletRequest("hunter2hunter2", descriptors = listOf("tr(tprvSECRET)")).toString()
        assertFalse("tprvSECRET" in text || "hunter2" in text, text)
        assertTrue("descriptors=<1>" in text, text)
    }

    @Test
    fun exportsPublicDescriptorsByDefault() = realTimeTest {
        readyWallet()
        val result = client.listWalletDescriptors()
        assertEquals(bindings.publicDescriptors, result.descriptors)
        assertEquals(bindings.rootFingerprint, result.rootFingerprint)
        assertFalse(result.includesPrivate)
        assertEquals("{}", bindings.requests.last { it.first == "listWalletDescriptors" }.second)
    }

    @Test
    fun privateExportSendsItsCredentials() = realTimeTest {
        readyWallet()
        val result = client.listWalletDescriptors(
            ListWalletDescriptorsRequest(includePrivate = true, descriptors = listOf("tr(tprv1)")),
        )
        assertTrue(result.includesPrivate)
        assertEquals(
            """{"IncludePrivate":true,"Descriptors":["tr(tprv1)"]}""",
            bindings.requests.last { it.first == "listWalletDescriptors" }.second,
        )
    }

    @Test
    fun exportValidation() = realTimeTest {
        readyWallet()
        val mnemonic = listOf("word")
        val descriptors = listOf("tr(tprv1)")
        listOf(
            ListWalletDescriptorsRequest(includePrivate = true),
            ListWalletDescriptorsRequest(mnemonic = mnemonic),
            ListWalletDescriptorsRequest(descriptors = descriptors),
            ListWalletDescriptorsRequest(includePrivate = true, mnemonic = mnemonic, descriptors = descriptors),
            ListWalletDescriptorsRequest(includePrivate = true, seedPassphrase = ByteArray(1), descriptors = descriptors),
        ).forEach {
            assertFailsWith<WavelengthException.InvalidDescriptor>("$it") { client.listWalletDescriptors(it) }
        }
        assertEquals(0, bindings.requests.count { it.first == "listWalletDescriptors" })
    }

    @Test
    fun passkeyPrfIsHex() = realTimeTest {
        client.start(config)
        client.openWalletFromPasskey(ByteArray(32) { 0xAB.toByte() })
        assertEquals("ab".repeat(32), lastRequest("openWalletFromPasskey")["prfOutput"]?.jsonPrimitive?.content)
        assertFailsWith<WavelengthException.InvalidArgument> { client.openWalletFromPasskey(ByteArray(31)) }
    }

    @Test
    fun forceUnrollSendsAck() = realTimeTest {
        readyWallet()
        runCatching { client.exit(ExitRequest.ForceUnroll("txid:0")) }
        assertEquals(ExitRequest.FORCE_UNROLL_ACK, lastRequest("exit")["ForceUnrollAck"]?.jsonPrimitive?.content)
    }

    @Test
    fun callFacadeEscapeHatch() = realTimeTest {
        client.start(config)
        val raw = client.callFacade(FacadeVerb.GET_INFO)
        assertTrue(raw.contains("\"WalletState\""))
    }

    @Test
    fun payloadLoggingRedactsSecrets() = realTimeTest {
        val logging = FacadeWavelengthClient(bindings, Dispatchers.Default, logPayloads = true)
        logging.start(config)
        val log = async(start = CoroutineStart.UNDISPATCHED) { logging.events.first { it is WavelengthEvent.Log && it.message.startsWith("createWallet") } }
        yield()
        launch { logging.createWallet(CreateWalletRequest("hunter2hunter2")) }
        val line = (withTimeout(5.seconds) { log.await() } as WavelengthEvent.Log).message
        assertFalse(line.contains(Base64.encode("hunter2hunter2".encodeToByteArray())))
        assertTrue(line.contains("<redacted>"))
    }
}
