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
 * paths, and the Go side cannot see the app lifecycle. After the restart the
 * wallet is locked again, so collect [restarts] and unlock it.
 *
 * The trade-off: nothing is processed while the app is in the background.
 * Platform hooks: `bindToProcessLifecycle()` on Android; on iOS, call
 * [onBackground] and [onForeground] from your scene phase handler.
 */
public class BackgroundRestartPolicy(
    private val client: WavelengthClient,
    private val scope: CoroutineScope,
    /** The config to restart with (normally the one the app started with). */
    private val config: () -> RuntimeConfig,
) {
    private var restartPending = false
    private var pending: Job? = null

    private val _restarts = MutableSharedFlow<Result<Info>>(extraBufferCapacity = 4)

    /** Each restart's info (wallet typically LOCKED) or failure. */
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
                Result.success(client.start(config()))
            } catch (e: WavelengthException) {
                Result.failure(e)
            }
            _restarts.emit(result)
        }
    }
}
