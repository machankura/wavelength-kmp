package engineering.lightning.wavelength.embedded

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.io.File

/**
 * The conventional data directory: `<filesDir>/wavelength`. It must be an
 * absolute path; a relative one stalls daemon startup on Android.
 */
public fun Wavelength.defaultDataDir(context: Context): String =
    File(context.filesDir, "wavelength").absolutePath

/**
 * Drives this policy from the whole app's foreground state
 * (`ProcessLifecycleOwner`). Call once, on the main thread, e.g. from
 * `Application.onCreate`. Returns a function that unbinds it.
 */
public fun BackgroundRestartPolicy.bindToProcessLifecycle(): () -> Unit {
    val observer = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) = onForeground()

        override fun onStop(owner: LifecycleOwner) = onBackground()
    }
    val lifecycle = ProcessLifecycleOwner.get().lifecycle
    lifecycle.addObserver(observer)
    return { lifecycle.removeObserver(observer) }
}
