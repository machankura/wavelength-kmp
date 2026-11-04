package engineering.lightning.wavelength.embedded

import engineering.lightning.wavelength.RuntimeConfig
import engineering.lightning.wavelength.WavelengthClient
import engineering.lightning.wavelength.WavelengthException
import engineering.lightning.wavelength.model.Info
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Stops the daemon when the app goes to the background and starts it again
 * when the app returns.
 *
 * wavelength's gomobile docs require this: a daemon left running while the OS
 * suspends the process keeps external gRPC connections on stale network
 * paths, and the Go side cannot see the app lifecycle. After the restart a
 * password wallet is locked again, so collect [restarts] and unlock it.
 *
 * A wallet opened through an atomic verb cannot be unlocked that way: a
 * descriptor wallet's password is derived from its keys, which the host holds.
 * Build the policy with [reopening] for those, passing that verb.
 *
 * A watch-only wallet opens only while its signer answers, so a restart or the
 * unlock after it can fail with [WavelengthException.RemoteSignerFailed]. That
 * is not a wrong password: show it and offer a retry.
 *
 * The trade-off: nothing is processed while the app is in the background.
 * Platform hooks: `bindToProcessLifecycle()` on Android; on iOS, call
 * [onBackground] and [onForeground] from your scene phase handler.
 */
public class BackgroundRestartPolicy private constructor(
    private val client: WavelengthClient,
    private val scope: CoroutineScope,
    private val reopen: suspend () -> Info,
) {
    /** Restarts the daemon with [config] (normally the one the app started with). */
    public constructor(
        client: WavelengthClient,
        scope: CoroutineScope,
        config: () -> RuntimeConfig,
    ) : this(client, scope, reopen = { client.start(config()) })

    private var restartPending = false
    private var pending: Job? = null

    private val _restarts = MutableSharedFlow<Result<Info>>(extraBufferCapacity = 4)

    /** Each restart's info (a password wallet is typically LOCKED) or failure. */
    public val restarts: SharedFlow<Result<Info>> = _restarts.asSharedFlow()

    /** The app moved to the background. */
    public fun onBackground() {
        if (!client.isRunning()) return
        restartPending = true
        val previous = pending
        pending = scope.launch {
            previous?.join()
            client.stop()
        }
    }

    /** The app returned to the foreground. */
    public fun onForeground() {
        if (!restartPending) return
        restartPending = false
        val previous = pending
        pending = scope.launch {
            previous?.join()
            val result = try {
                Result.success(reopen())
            } catch (e: WavelengthException) {
                Result.failure(e)
            }
            _restarts.emit(result)
        }
    }

    public companion object {
        /**
         * A policy that returns to the foreground by calling [reopen] instead of
         * starting the daemon, for a wallet opened through an atomic verb.
         */
        public fun reopening(
            client: WavelengthClient,
            scope: CoroutineScope,
            reopen: suspend () -> Info,
        ): BackgroundRestartPolicy = BackgroundRestartPolicy(client, scope, reopen = reopen)
    }
}
