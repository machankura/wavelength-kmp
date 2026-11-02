package engineering.lightning.wavelength.engine

import engineering.lightning.wavelength.ActivityEvent
import engineering.lightning.wavelength.ActivityOptions
import engineering.lightning.wavelength.WavelengthClient
import engineering.lightning.wavelength.model.Balance
import engineering.lightning.wavelength.model.Entry
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Runs [tick] every [interval] while started; stops after [failureLimit] consecutive failures. */
internal class Poller(
    private val scope: CoroutineScope,
    private val interval: Duration,
    private val immediate: Boolean = false,
    private val failureLimit: Int = 0,
    private val tick: suspend () -> Unit,
    private val onExhausted: (Throwable) -> Unit = {},
) {
    private val lock = SynchronizedObject()
    private var job: Job? = null

    fun start(): Unit = synchronized(lock) {
        if (job?.isActive == true) return
        job = scope.launch {
            var failures = 0
            if (!immediate) delay(interval)
            while (isActive) {
                try {
                    tick()
                    failures = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures++
                    if (failureLimit > 0 && failures >= failureLimit) {
                        onExhausted(e)
                        return@launch
                    }
                }
                delay(interval)
            }
        }
    }

    fun stop(): Unit = synchronized(lock) {
        job?.cancel()
        job = null
    }
}

internal data class RefreshResult(val ok: Boolean, val balance: Balance?)

/**
 * After activity, refreshes once and then probes at the settle delays until
 * the balance has moved off its baseline and held steady. A new trigger
 * supersedes an in-flight sequence.
 */
internal class SettleReconciler(
    private val scope: CoroutineScope,
    private val refresh: suspend () -> RefreshResult,
    private val baseline: () -> Balance?,
) {
    private val lock = SynchronizedObject()
    private var job: Job? = null

    fun trigger(): Unit = synchronized(lock) {
        job?.cancel()
        val base = baseline()
        job = scope.launch {
            val first = refresh()
            if (!first.ok) return@launch
            var prev = first.balance
            for (wait in EngineConstants.SETTLE_RECONCILE_DELAYS) {
                delay(wait)
                val res = refresh()
                if (!res.ok) return@launch
                val moved = base != res.balance
                val steady = prev == res.balance
                if (moved && steady) return@launch
                prev = res.balance
            }
        }
    }

    fun cancel(): Unit = synchronized(lock) {
        job?.cancel()
        job = null
    }
}

/**
 * The engine's activity subscription while READY: reopens with exponential
 * backoff after a loss, resumes from the last cursor, and retains the send
 * preimages the daemon reports only once (on the stream, never in list).
 */
internal class ActivityStream(
    private val scope: CoroutineScope,
    private val client: WavelengthClient,
    private val onActivity: () -> Unit,
    private val onReconcile: () -> Unit,
    private val onDead: (Throwable) -> Unit,
) {
    private val lock = SynchronizedObject()
    private var running = false
    private var generation = 0
    private var cursor = 0L
    private var backoff = EngineConstants.STREAM_BACKOFF
    private var failures = 0
    private var job: Job? = null
    private var debounce: Job? = null
    private val preimages = mutableMapOf<String, String>()

    fun preimages(): Map<String, String> = synchronized(lock) { preimages.toMap() }

    fun start(): Unit = synchronized(lock) {
        if (running) return
        running = true
        generation++
        cursor = 0
        backoff = EngineConstants.STREAM_BACKOFF
        failures = 0
        val gen = generation
        job = scope.launch { run(gen) }
    }

    fun stop(): Unit = synchronized(lock) {
        cursor = 0
        if (!running) return
        running = false
        generation++
        preimages.clear()
        job?.cancel()
        debounce?.cancel()
    }

    private fun current(gen: Int) = synchronized(lock) { running && gen == generation }

    private suspend fun run(gen: Int) {
        while (current(gen)) {
            var opened = false
            var error: Throwable? = null
            val options = synchronized(lock) { ActivityOptions(includeExisting = cursor == 0L, cursor = cursor.takeIf { it > 0 }) }
            try {
                client.activityEvents(options).collect { event ->
                    when (event) {
                        ActivityEvent.Opened -> {
                            opened = true
                            synchronized(lock) {
                                backoff = EngineConstants.STREAM_BACKOFF
                                failures = 0
                            }
                        }
                        is ActivityEvent.EntryReceived -> noteActivity(gen, event.entry)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e
            }
            if (!current(gen)) return

            if (opened) {
                // Lost after a successful open: reconcile what may have been missed.
                onReconcile()
            } else {
                val dead = synchronized(lock) {
                    failures++
                    if (failures >= EngineConstants.STREAM_FAILURE_LIMIT) {
                        running = false
                        debounce?.cancel()
                        true
                    } else {
                        false
                    }
                }
                if (dead) {
                    onDead(IllegalStateException("lost the activity stream and could not reconnect", error))
                    return
                }
            }
            val wait = synchronized(lock) { backoff }
            delay(wait)
            synchronized(lock) { backoff = minOf(backoff * 2, EngineConstants.STREAM_BACKOFF_CAP) }
        }
    }

    private fun noteActivity(gen: Int, entry: Entry): Unit = synchronized(lock) {
        if (!running || gen != generation) return
        if (entry.cursor > cursor) cursor = entry.cursor
        val progress = entry.progress
        if (progress != null && progress.paymentHash.isNotEmpty() && progress.preimage.isNotEmpty()) {
            preimages[progress.paymentHash] = progress.preimage
        }
        debounce?.cancel()
        debounce = scope.launch {
            delay(EngineConstants.ACTIVITY_DEBOUNCE)
            onActivity()
        }
    }
}

/** Puts retained preimages back into entries that lack them. */
public fun restorePreimages(entries: List<Entry>, preimages: Map<String, String>): List<Entry> {
    if (preimages.isEmpty()) return entries
    return entries.map { entry ->
        val progress = entry.progress
        if (progress == null || progress.paymentHash.isEmpty() || progress.preimage.isNotEmpty()) return@map entry
        val preimage = preimages[progress.paymentHash] ?: return@map entry
        entry.copy(progress = progress.copy(preimage = preimage))
    }
}
