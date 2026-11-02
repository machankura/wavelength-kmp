package engineering.lightning.wavelength

import engineering.lightning.wavelength.internal.WireJson
import engineering.lightning.wavelength.model.Balance
import engineering.lightning.wavelength.model.CreateWalletResult
import engineering.lightning.wavelength.model.Entry
import engineering.lightning.wavelength.model.EntryFailureCode
import engineering.lightning.wavelength.model.EntryKind
import engineering.lightning.wavelength.model.EntryPhase
import engineering.lightning.wavelength.model.EntryRequestType
import engineering.lightning.wavelength.model.EntryStatus
import engineering.lightning.wavelength.model.ExitJobStatus
import engineering.lightning.wavelength.model.ExitPath
import engineering.lightning.wavelength.model.ExitResult
import engineering.lightning.wavelength.model.ExitStatusResult
import engineering.lightning.wavelength.model.Info
import engineering.lightning.wavelength.model.ListResult
import engineering.lightning.wavelength.model.ListView
import engineering.lightning.wavelength.model.OnchainTx
import engineering.lightning.wavelength.model.PrepareSendResult
import engineering.lightning.wavelength.model.SendQuoteStatus
import engineering.lightning.wavelength.model.SendRail
import engineering.lightning.wavelength.model.Status
import engineering.lightning.wavelength.model.WalletState
import engineering.lightning.wavelength.wire.CreateWalletRequest
import engineering.lightning.wavelength.wire.ExitSummaryRequest
import engineering.lightning.wavelength.wire.ListRequest
import engineering.lightning.wavelength.wire.SendPreparedRequest
import engineering.lightning.wavelength.wire.SubscribeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class WireCodecTest {
    private inline fun <reified T> decode(json: String): T = WireJson.decodeFromString(json)

    @Test
    fun infoDecodesPascalCaseAndIntEnum() {
        val info = decode<Info>(GoFixtures.INFO)
        assertEquals("signet", info.network)
        assertEquals(4294967295L, info.blockHeight) // uint32 max widens to Long
        assertEquals(WalletState.READY, info.walletState)
        assertEquals(144L, info.serverInfo?.freeRefreshWindowBlocks)
    }

    @Test
    fun zeroInfoDecodesNullsToDefaults() {
        val info = decode<Info>(GoFixtures.INFO_ZERO)
        assertEquals(WalletState.UNSPECIFIED, info.walletState)
        assertNull(info.serverInfo)
    }

    @Test
    fun balance() {
        val b = decode<Balance>(GoFixtures.BALANCE)
        assertEquals(125_000L, b.confirmedSat)
        assertEquals(500L, b.creditAvailableSat)
    }

    @Test
    fun byteSliceIsBase64() {
        val r = decode<CreateWalletResult>(GoFixtures.CREATE_WALLET_RESULT)
        assertEquals(ByteString.of(byteArrayOf(0, 1, 2, 250.toByte())), r.encipheredSeed)
        assertEquals(listOf("abandon", "ability"), r.mnemonic)
        assertEquals(3L, r.recoveredVtxos)
    }

    @Test
    fun entryWithNestedObjectsAndTimestamps() {
        val e = decode<Entry>(GoFixtures.ENTRY_SEND)
        assertEquals("send-1", e.id)
        assertEquals(EntryKind.SEND, e.kind)
        assertEquals(EntryStatus.COMPLETE, e.status)
        assertEquals(Instant.parse("2026-11-02T08:30:00.123456789Z"), e.createdAt)
        assertEquals(42L, e.cursor)
        assertEquals(EntryPhase.CONFIRMED, e.progress?.phase)
        assertEquals("pre", e.progress?.preimage)
        assertEquals(EntryRequestType.LIGHTNING, e.request?.type)
        assertNull(e.failureCode, "an empty Go string enum is unset")
    }

    @Test
    fun goZeroTimeDecodesToNull() {
        val e = decode<Entry>(GoFixtures.ENTRY_MINIMAL)
        assertNull(e.createdAt)
        assertNull(e.progress)
        assertNull(decode<OnchainTx>(GoFixtures.ONCHAIN_TX_ZERO_TIME).createdAt)
    }

    @Test
    fun unknownEnumValueFromNewerDaemon() {
        val e = decode<Entry>(GoFixtures.ENTRY_UNKNOWN_KIND)
        assertEquals(EntryKind.UNKNOWN, e.kind)
        assertEquals(EntryFailureCode.NEEDS_INTERVENTION, e.failureCode)
    }

    @Test
    fun prepareSendResultNullSliceBecomesEmpty() {
        val r = decode<PrepareSendResult>(GoFixtures.PREPARE_SEND_RESULT)
        assertEquals("intent-1", r.sendIntentId)
        assertEquals(SendRail.LIGHTNING, r.rail)
        assertEquals(SendQuoteStatus.COMPLETE, r.quoteStatus)
        assertEquals(emptyList(), r.selectedOutpoints)
        assertEquals(1L, r.creditPreview?.creditAppliedSat)
    }

    @Test
    fun listResultTaggedUnion() {
        val v = decode<ListResult>(GoFixtures.LIST_VTXOS)
        assertEquals(ListView.VTXOS, v.view)
        assertEquals("txid:0", v.vtxos?.vtxos?.single()?.outpoint)
        assertNull(v.activity)

        val a = decode<ListResult>(GoFixtures.LIST_ACTIVITY_EMPTY)
        assertEquals(emptyList(), a.activity?.entries)
    }

    @Test
    fun exitTypesWithAcronymFields() {
        val s = decode<ExitStatusResult>(GoFixtures.EXIT_STATUS)
        assertEquals(ExitJobStatus.CSV_PENDING, s.status)
        assertEquals(12, s.csv?.blocksRemaining)
        assertEquals(300L, s.fees?.cpfpFeeSat)
        assertEquals(10_000L, s.fees?.vtxoAmountSat)

        val r = decode<ExitResult>(GoFixtures.EXIT_RESULT)
        assertEquals(ExitPath.COOPERATIVE, r.path)
        assertEquals("actor", r.actorId)
    }

    @Test
    fun status() {
        val s = decode<Status>(GoFixtures.STATUS)
        assertEquals(2L, s.pendingCount)
        assertEquals(1L, s.balance.confirmedSat)
    }

    /**
     * Every key a Kotlin request emits must be a key Go's own encoding emits
     * for that DTO, with the same value. (Kotlin omits zero values, which Go
     * decodes identically from a missing key.)
     */
    private fun <T> assertWireSubset(serializer: KSerializer<T>, value: T, goJson: String) {
        val ours = WireJson.encodeToJsonElement(serializer, value).jsonObject
        val go = WireJson.parseToJsonElement(goJson).jsonObject
        for ((key, v) in ours) {
            assertTrue(key in go, "Kotlin emitted $key, which Go does not use: $go")
            assertEquals(go[key], v, "value for $key")
        }
        for ((key, v) in go) {
            if (key !in ours) assertTrue(v.isGoZero(), "Kotlin omitted non-zero $key=$v")
        }
    }

    private fun kotlinx.serialization.json.JsonElement.isGoZero(): Boolean = when (this) {
        JsonNull -> true
        is JsonPrimitive -> content in setOf("", "0", "false")
        is JsonObject -> isEmpty()
        else -> false
    }

    @Test
    fun requestsUseGoWireNames() {
        assertWireSubset(
            CreateWalletRequest.serializer(),
            CreateWalletRequest(walletPassword = ByteString.of("pw".encodeToByteArray()), recoveryWindow = 2500),
            GoFixtures.REQ_CREATE_WALLET,
        )
        assertWireSubset(
            ListRequest.serializer(),
            ListRequest(view = ListView.ACTIVITY, kinds = listOf(EntryKind.SEND), limit = 50, cursor = "c1"),
            GoFixtures.REQ_LIST,
        )
        assertWireSubset(
            SubscribeRequest.serializer(),
            SubscribeRequest(includeExisting = true, cursor = 7),
            GoFixtures.REQ_SUBSCRIBE,
        )
        assertWireSubset(
            SendPreparedRequest.serializer(),
            SendPreparedRequest(sendIntentId = "intent-1"),
            GoFixtures.REQ_SEND_PREPARED,
        )
        assertEquals("{}", WireJson.encodeToString(ExitSummaryRequest.serializer(), ExitSummaryRequest()))
    }
}
