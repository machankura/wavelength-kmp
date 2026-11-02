package engineering.lightning.wavelength

import engineering.lightning.wavelength.model.ExitInfeasibilityReason
import engineering.lightning.wavelength.model.ExitResult
import engineering.lightning.wavelength.model.GetExitPlanResult
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** How [exitBatch] moves a set of VTXOs on-chain. */
public sealed class ExitBatchOptions {
    public abstract val outpoints: List<String>

    /** Queues each outpoint into the next round, paying [destination] (or a fresh address). */
    public data class Cooperative(override val outpoints: List<String>, val destination: String? = null) :
        ExitBatchOptions()

    /**
     * Forces each outpoint on-chain, funded from the backing wallet. Funding is
     * previewed with getExitPlan before every start.
     */
    public data class Unilateral(override val outpoints: List<String>, val confTarget: Long? = null) :
        ExitBatchOptions()
}

/** Progress reported by [exitBatch]. */
public sealed class ExitBatchEvent {
    /** The latest unilateral funding plan. */
    public data class Planned(val plan: GetExitPlanResult) : ExitBatchEvent()

    public data class Starting(val outpoint: String) : ExitBatchEvent()

    public data class Started(val outpoint: String, val result: ExitResult) : ExitBatchEvent()

    /** The batch halted early. */
    public data class Stopped(val stoppedBy: ExitBatchStop, val remaining: List<String>) : ExitBatchEvent()
}

/** Why an [exitBatch] stopped before finishing. */
public sealed class ExitBatchStop {
    /** A re-plan found the backing wallet can no longer fund the remaining exits. */
    public data class Infeasible(val plan: GetExitPlanResult) : ExitBatchStop()

    /** The daemon rejected the exit for [outpoint]. */
    public data class Rejected(val outpoint: String, val error: WavelengthException) : ExitBatchStop()
}

/**
 * The outcome of [exitBatch]: exits [started] (a unilateral exit keeps
 * running for hours or days afterwards), outpoints [skipped] because an exit
 * was already running, and outpoints never started ([remaining]).
 */
public data class ExitBatchResult(
    val started: List<Pair<String, ExitResult>>,
    val skipped: List<String>,
    val remaining: List<String>,
    val stoppedBy: ExitBatchStop? = null,
)

/**
 * Whether more confirmed backing-wallet funds or inputs can resolve [this]
 * infeasibility (as opposed to a structural or temporary block).
 */
public val ExitInfeasibilityReason.isFundable: Boolean
    get() = this == ExitInfeasibilityReason.WALLET_UNDERFUNDED || this == ExitInfeasibilityReason.WALLET_TOO_FEW_INPUTS

/**
 * Starts a batch of exits, one outpoint per call, and returns once each has
 * STARTED (not completed). The unilateral path re-plans funding before every
 * start, skips outpoints already exiting, and stops cleanly when the wallet can
 * no longer fund the rest or the daemon rejects an exit. Cancel the calling
 * coroutine to abort between starts.
 *
 * Mirrors `exitBatch` in the TypeScript SDK.
 */
public suspend fun WavelengthClient.exitBatch(
    options: ExitBatchOptions,
    onEvent: (ExitBatchEvent) -> Unit = {},
): ExitBatchResult {
    val started = mutableListOf<Pair<String, ExitResult>>()
    val skipped = mutableListOf<String>()
    var remaining = options.outpoints.toList()

    while (remaining.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        if (options is ExitBatchOptions.Unilateral) {
            val plan = getExitPlan(remaining, options.confTarget)
            onEvent(ExitBatchEvent.Planned(plan))
            // The daemon rejects a second exit for one outpoint; the plan flags them.
            val running = plan.plans.filter { it.exitJobFound }.map { it.outpoint }.toSet()
            if (running.isNotEmpty()) {
                val newlySkipped = remaining.filter { it in running && it !in skipped }
                skipped += newlySkipped
                remaining = remaining.filter { it !in running }
                if (remaining.isEmpty()) break
                // Re-plan without the running outpoints before gating on canStart:
                // their leased fee inputs can make the aggregate plan infeasible.
                if (newlySkipped.isNotEmpty()) continue
            }
            if (!plan.canStart) {
                val stop = ExitBatchStop.Infeasible(plan)
                onEvent(ExitBatchEvent.Stopped(stop, remaining))
                return ExitBatchResult(started, skipped, remaining, stop)
            }
        }

        val outpoint = remaining.first()
        onEvent(ExitBatchEvent.Starting(outpoint))
        try {
            val request = when (options) {
                is ExitBatchOptions.Unilateral -> ExitRequest.ForceUnroll(outpoint)
                is ExitBatchOptions.Cooperative -> ExitRequest.Cooperative(outpoint, options.destination)
            }
            val result = exit(request)
            started += outpoint to result
            remaining = remaining.drop(1)
            onEvent(ExitBatchEvent.Started(outpoint, result))
        } catch (e: CancellationException) {
            throw e
        } catch (e: WavelengthException) {
            val stop = ExitBatchStop.Rejected(outpoint, e)
            onEvent(ExitBatchEvent.Stopped(stop, remaining))
            return ExitBatchResult(started, skipped, remaining, stop)
        }
    }
    return ExitBatchResult(started, skipped, emptyList())
}
