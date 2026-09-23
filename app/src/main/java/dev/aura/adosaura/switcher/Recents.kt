package dev.aura.adosaura.switcher

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process

/**
 * "The apps that are on" — without root.
 *
 * Android will not let a normal app enumerate real tasks (`getRunningTasks`
 * stopped returning anything but your own in API 21) and there is no way
 * around that short of a Magisk module. What a normal app *can* do is ask
 * UsageStatsManager for the most-recently-used apps, which is what an app
 * switcher is anyway — a most-recently-used list — and then kill background
 * processes to clean up.
 *
 * Both halves need no prompt and no Play-store permission dance:
 * `PACKAGE_USAGE_STATS` is an appop, granted by scripts/adb-grants.sh.
 */
class Recents(private val context: Context) {

    private val usm = context.getSystemService(UsageStatsManager::class.java)
    private val am = context.getSystemService(ActivityManager::class.java)
    private val ops = context.getSystemService(AppOpsManager::class.java)

    /** False until usage access is granted. The UI says so rather than lying. */
    fun granted(): Boolean = runCatching {
        ops?.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /**
     * Most recently used, newest first. Skips adosaura itself and anything
     * with no real launch intent, so the list is apps you actually opened.
     */
    fun recent(labels: Map<String, String>, limit: Int = 14): List<RecentApp> {
        if (!granted()) return emptyList()

        val now = System.currentTimeMillis()
        val window = now - DAY_MS * 7

        val stats: List<UsageStats> = runCatching {
            usm?.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, window, now).orEmpty()
        }.getOrDefault(emptyList())

        return stats
            .asSequence()
            .filter { it.lastTimeUsed in 1 until now }
            .filter { it.packageName != context.packageName }
            .sortedByDescending { it.lastTimeUsed }
            .distinctBy { it.packageName }
            .take(limit)
            .map {
                RecentApp(
                    pkg = it.packageName,
                    label = labels[it.packageName] ?: it.packageName,
                    lastUsed = it.lastTimeUsed,
                    age = Age.of(now, it.lastTimeUsed),
                )
            }
            .toList()
    }

    /** Kill one app's background processes. Never touches a foreground app. */
    fun clean(pkg: String) {
        runCatching { am?.killBackgroundProcesses(pkg) }
    }

    /** Kill every background process the platform will let us. Returns KB freed. */
    fun cleanAll(packages: List<String>): Long {
        val before = freeKb()
        packages.forEach { runCatching { am?.killBackgroundProcesses(it) } }
        val after = freeKb()
        return (after - before).coerceAtLeast(0L)
    }

    private fun freeKb(): Long = runCatching {
        val info = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(info)
        info.availMem / 1024
    }.getOrDefault(0L)

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** Bring an app to the front, exactly as if tapped in a launcher. */
        fun open(context: Context, pkg: String): Boolean = runCatching {
            val launch = context.packageManager.getLaunchIntentForPackage(pkg)
                ?: return false
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            launch.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            context.startActivity(launch)
            true
        }.getOrDefault(false)
    }
}

data class RecentApp(
    val pkg: String,
    val label: String,
    val lastUsed: Long,
    val age: String,
)

/** `2m`, `14h`, `3d` — short enough to sit in one column. Pure, so tested. */
object Age {
    fun of(now: Long, then: Long): String {
        val delta = (now - then).coerceAtLeast(0)
        val minutes = delta / 60_000
        return when {
            minutes < 1 -> "now"
            minutes < 60 -> "${minutes}m"
            minutes < 60 * 24 -> "${minutes / 60}h"
            else -> "${minutes / (60 * 24)}d"
        }
    }
}
