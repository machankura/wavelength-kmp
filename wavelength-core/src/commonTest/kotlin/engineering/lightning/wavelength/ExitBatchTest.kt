package engineering.lightning.wavelength

import engineering.lightning.wavelength.model.ExitInfeasibilityReason
import engineering.lightning.wavelength.model.ExitPath
import engineering.lightning.wavelength.model.ExitPlanEntry
import engineering.lightning.wavelength.model.ExitResult
import engineering.lightning.wavelength.model.GetExitPlanResult
import engineering.lightning.wavelength.testing.FakeWavelengthClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ExitBatchTest {
    private val client = FakeWavelengthClient()
    private val bindings = client.bindings
    private val exited = mutableListOf<Pair<String, Boolean>>()

    private suspend fun ready() {
        client.start(RuntimeConfig(network = Network.REGTEST))
        client.createWallet(CreateWalletRequest("password1"))
        bindings.exitHandler = { outpoint, force ->
            exited += outpoint to force
            ExitResult(path = if (force) ExitPath.UNILATERAL else ExitPath.COOPERATIVE, queuedOutpoints = listOf(outpoint))
        }
    }

    @Test
    fun cooperativeStartsEveryOutpoint() = runTest {
        ready()
        val events = mutableListOf<ExitBatchEvent>()
        val result = client.exitBatch(ExitBatchOptions.Cooperative(listOf("a:0", "b:0"), destination = "tb1q"), events::add)
        assertEquals(listOf("a:0", "b:0"), result.started.map { it.first })
        assertEquals(listOf("a:0" to false, "b:0" to false), exited)
        assertEquals(4, events.size)
    }

    @Test
    fun unilateralSkipsRunningAndReplans() = runTest {
        ready()
        var plans = 0
        bindings.exitPlanHandler = { outpoints ->
            plans++
            GetExitPlanResult(
                plans = outpoints.map { ExitPlanEntry(outpoint = it, exitJobFound = it == "running:0") },
                canStart = true,
            )
        }
        val result = client.exitBatch(ExitBatchOptions.Unilateral(listOf("running:0", "a:0", "b:0")))
        assertEquals(listOf("running:0"), result.skipped)
        assertEquals(listOf("a:0" to true, "b:0" to true), exited)
        assertTrue(plans >= 3, "re-plans before every start")
    }

    @Test
    fun unilateralStopsWhenInfeasible() = runTest {
        ready()
        bindings.exitPlanHandler = { outpoints ->
            GetExitPlanResult(plans = outpoints.map { ExitPlanEntry(outpoint = it) }, canStart = exited.isEmpty())
        }
        val result = client.exitBatch(ExitBatchOptions.Unilateral(listOf("a:0", "b:0")))
        assertEquals(listOf("a:0"), result.started.map { it.first })
        assertEquals(listOf("b:0"), result.remaining)
        assertIs<ExitBatchStop.Infeasible>(result.stoppedBy)
    }

    @Test
    fun rejectionIsACleanStop() = runTest {
        ready()
        bindings.exitHandler = { outpoint, _ -> if (outpoint == "b:0") error("unexpected") else ExitResult() }
        bindings.failNext("exit", "rpc error: code = FailedPrecondition desc = vtxo spent")
        val result = client.exitBatch(ExitBatchOptions.Cooperative(listOf("a:0", "b:0")))
        val stop = assertIs<ExitBatchStop.Rejected>(result.stoppedBy)
        assertEquals("a:0", stop.outpoint)
        assertEquals(listOf("a:0", "b:0"), result.remaining)
    }

    @Test
    fun fundableReasons() {
        assertTrue(ExitInfeasibilityReason.WALLET_UNDERFUNDED.isFundable)
        assertTrue(ExitInfeasibilityReason.WALLET_TOO_FEW_INPUTS.isFundable)
        assertFalse(ExitInfeasibilityReason.ROUND_COMMITTED.isFundable)
        assertFalse(ExitInfeasibilityReason.UNECONOMICAL.isFundable)
    }
}
