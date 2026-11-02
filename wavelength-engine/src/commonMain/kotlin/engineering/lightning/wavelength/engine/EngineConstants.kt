package engineering.lightning.wavelength.engine

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Timings and budgets of the engine's background work (TypeScript engine/constants.ts). */
public object EngineConstants {
    /** Log lines kept in [WalletSnapshot.logs]. */
    public const val MAX_LOGS: Int = 200

    /** Consecutive failed activity-stream opens before the stream is dead. */
    public const val STREAM_FAILURE_LIMIT: Int = 5
    public val STREAM_BACKOFF: Duration = 1.seconds
    public val STREAM_BACKOFF_CAP: Duration = 30.seconds

    /** Debounce between activity and the refresh it triggers. */
    public val ACTIVITY_DEBOUNCE: Duration = 250.milliseconds

    public val SYNC_POLL: Duration = 2.seconds
    public const val SYNC_POLL_FAILURE_LIMIT: Int = 5

    public val RESTORE_POLL: Duration = 1500.milliseconds

    /**
     * Follow-up refreshes after activity: the daemon can report an entry
     * settled a beat before balance() reflects it.
     */
    public val SETTLE_RECONCILE_DELAYS: List<Duration> = listOf(750.milliseconds, 1500.milliseconds, 3000.milliseconds)

    public const val BACKGROUND_REFRESH_FAILURE_LIMIT: Int = 5

    /** Info refetches after create/unlock before escalating. */
    public const val ADOPT_INFO_RETRIES: Int = 3
    public val ADOPT_INFO_RETRY: Duration = 1.seconds
}
