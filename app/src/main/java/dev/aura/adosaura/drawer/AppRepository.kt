package dev.aura.adosaura.drawer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Process
import android.provider.Settings

/**
 * The only class that talks to Android's package manager.
 *
 * LauncherApps rather than queryIntentActivities, so install / uninstall /
 * rename arrive as callbacks and a work profile would come along for free.
 *
 * Everything here degrades to an empty result rather than throwing: this class
 * runs on the home screen, where a crash is worse than a missing row.
 */
class AppRepository(private val context: Context) {

    private val launcherApps: LauncherApps? =
        context.getSystemService(LauncherApps::class.java)

    /** package -> real display label for every launchable activity. */
    fun labels(): Map<String, String> {
        val apps = launcherApps ?: return emptyMap()
        return runCatching {
            apps.getActivityList(null, Process.myUserHandle())
                .associate { it.applicationInfo.packageName to it.label.toString() }
        }.getOrDefault(emptyMap())
    }

    /** Launch the app's default activity. False when it is gone or disabled. */
    fun launch(packageName: String): Boolean {
        val apps = launcherApps ?: return false
        val activity = runCatching {
            apps.getActivityList(packageName, Process.myUserHandle()).firstOrNull()
        }.getOrNull() ?: return false

        return runCatching {
            apps.startMainActivity(activity.componentName, activity.user, null, null)
            true
        }.getOrDefault(false)
    }

    fun appInfo(packageName: String): Boolean = runCatching {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    fun hasActivity(packageName: String): Boolean {
        val apps = launcherApps ?: return false
        return runCatching {
            apps.getActivityList(packageName, Process.myUserHandle()).isNotEmpty()
        }.getOrDefault(false)
    }

    /**
     * Live app list: the launcher re-reads configs whenever Android reports a
     * change, so installing an app shows up without a reload.
     */
    fun register(callback: LauncherApps.Callback) {
        launcherApps?.registerCallback(callback)
    }

    fun unregister(callback: LauncherApps.Callback) {
        launcherApps?.unregisterCallback(callback)
    }

    /** For tests and diagnostics. */
    fun componentNameOf(packageName: String): ComponentName? {
        val apps = launcherApps ?: return null
        return runCatching {
            apps.getActivityList(packageName, Process.myUserHandle()).firstOrNull()?.componentName
        }.getOrNull()
    }
}
