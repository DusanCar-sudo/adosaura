package dev.aura.adosaura.switcher

import android.app.ActivityManager
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import kotlinx.coroutines.delay

/**
 * What the `clean` button really does, within what Android allows a
 * non-root app: stop the background processes of every user app, drop the
 * launcher's own caches, then measure what came back.
 *
 * killBackgroundProcesses never touches a foreground app, a playing music
 * service or anything the user can see — only cached processes that sit in
 * RAM and wake up for work. System apps are left alone on purpose: killing
 * Play Services only makes it restart, which costs battery instead of saving it.
 */
class Optimizer(private val context: Context) {

    data class Result(val freedMb: Long, val ramBefore: Int, val ramAfter: Int, val apps: Int)

    private val am = context.getSystemService(ActivityManager::class.java)

    suspend fun run(): Result {
        val before = memory()
        val targets = targets()
        targets.forEach { runCatching { am?.killBackgroundProcesses(it) } }
        runCatching { context.cacheDir.deleteRecursively() }
        System.gc()
        // Kills are asynchronous: the memory comes back over the next second or so.
        delay(1800)
        val after = memory()
        return Result(
            freedMb = ((after.availMem - before.availMem) / (1024 * 1024)).coerceAtLeast(0),
            ramBefore = usedPct(before),
            ramAfter = usedPct(after),
            apps = targets.size,
        )
    }

    /** Every installed user app except us, the keyboard and a live wallpaper. */
    private fun targets(): List<String> {
        val keep = buildSet {
            add(context.packageName)
            runCatching {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                    ?.substringBefore('/')?.let { add(it) }
            }
            runCatching { WallpaperManager.getInstance(context).wallpaperInfo?.packageName?.let { add(it) } }
        }
        return runCatching {
            context.packageManager.getInstalledApplications(0)
                .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 || it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0 }
                .map { it.packageName }
                .filter { it !in keep }
        }.getOrDefault(emptyList())
    }

    private fun memory() = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }

    private fun usedPct(m: ActivityManager.MemoryInfo): Int =
        if (m.totalMem == 0L) 0 else (100 - m.availMem * 100 / m.totalMem).toInt()

    companion object {
        /** Battery saver is on, or the battery is at 20% or less and not charging. */
        fun eco(context: Context): Boolean {
            val power = context.getSystemService(PowerManager::class.java)
            if (power?.isPowerSaveMode == true) return true
            val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
            val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            return !plugged && level >= 0 && level * 100 / scale <= 20
        }

        fun powerSaveOn(context: Context): Boolean =
            context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true

        /** HyperOS will not let an app flip battery saver, so open its switch. */
        fun openBatterySaver(context: Context) = startFirst(
            context,
            Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
            Intent(Intent.ACTION_POWER_USAGE_SUMMARY),
        )

        fun openBatteryUsage(context: Context) = startFirst(
            context,
            Intent(Intent.ACTION_POWER_USAGE_SUMMARY),
            Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
        )

        fun pickWallpaper(context: Context) = startFirst(
            context,
            Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "wallpaper"),
        )

        private fun startFirst(context: Context, vararg intents: Intent): Boolean =
            intents.any { intent ->
                runCatching {
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                }.getOrDefault(false)
            }
    }
}
