package dev.aura.adosaura.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

/**
 * The numbers the tiles show. Everything here is a composable clock or
 * broadcast-backed read, so the tiles never hold stale data and never poll.
 */

/** A one-second tick aligned to the second, so digits never stutter or double. */
@Composable
fun rememberTick(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000L - (now % 1000L))
        }
    }
    return now
}

/** Battery level in percent, or -1 when the system will not tell us. */
@Composable
fun rememberBatteryPercent(): Int {
    val context = LocalContext.current
    var percent by remember { mutableIntStateOf(readBattery(context)) }

    DisposableEffect(context) {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                percent = percentOf(intent)
            }
        }
        // RECEIVER_NOT_EXPORTED: this is a system broadcast and we are not a
        // broadcast target for anyone else.
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    return percent
}

private fun readBattery(context: Context): Int =
    runCatching {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        percentOf(sticky)
    }.getOrDefault(-1)

private fun percentOf(intent: Intent?): Int {
    val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
    if (level < 0 || scale <= 0) return -1
    return ((level * 100f) / scale).toInt().coerceIn(0, 100)
}

/** Free space on the data partition, in whole gibibytes. */
fun freeStorageGb(): Int = runCatching {
    val stat = StatFs(Environment.getDataDirectory().path)
    (stat.availableBytes / (1024L * 1024L * 1024L)).toInt()
}.getOrDefault(-1)

/** Time since boot as `3h 12m`, matching how Aura reports uptime. */
fun uptimeShort(): String {
    val minutes = SystemClock.elapsedRealtime() / 60_000L
    val hours = minutes / 60
    val rest = minutes % 60
    return if (hours > 0) "${hours}h ${rest}m" else "${rest}m"
}
