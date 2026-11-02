package engineering.lightning.wavelength.embedded

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification

/**
 * The conventional data directory, `Application Support/wavelength`, created
 * if missing. With [excludeFromBackup] the directory is kept out of iCloud and
 * device backups (the encrypted seed then lives only on this device, so the
 * user's mnemonic backup becomes the only recovery path).
 */
@OptIn(ExperimentalForeignApi::class)
public fun Wavelength.defaultDataDir(excludeFromBackup: Boolean = false): String {
    val fm = NSFileManager.defaultManager
    val base = fm.URLForDirectory(NSApplicationSupportDirectory, NSUserDomainMask, null, true, null)
        ?: error("Application Support directory unavailable")
    val dir: NSURL = base.URLByAppendingPathComponent("wavelength", isDirectory = true)
        ?: error("cannot build the wavelength data directory URL")
    fm.createDirectoryAtURL(dir, withIntermediateDirectories = true, attributes = null, error = null)
    if (excludeFromBackup) {
        dir.setResourceValue(NSNumber(bool = true), forKey = NSURLIsExcludedFromBackupKey, error = null)
    }
    return dir.path ?: error("wavelength data directory has no path")
}

/**
 * Drives this policy from UIApplication's background and foreground
 * notifications. Returns a function that unbinds it.
 */
public fun BackgroundRestartPolicy.bindToApplicationLifecycle(): () -> Unit {
    val center = NSNotificationCenter.defaultCenter
    val queue = NSOperationQueue.mainQueue
    val background = center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, queue) { _ ->
        onBackground()
    }
    val foreground = center.addObserverForName(UIApplicationWillEnterForegroundNotification, null, queue) { _ ->
        onForeground()
    }
    return {
        center.removeObserver(background)
        center.removeObserver(foreground)
    }
}
