package dev.aura.adosaura.tiles

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aura.adosaura.theme.AdosauraMono
import dev.aura.adosaura.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.ui.platform.LocalContext

/**
 * A block-matrix system monitor graph showing CPU usage, live temperature,
 * and RAM metrics in Aura OS style.
 */
@Composable
fun PerfTile(modifier: Modifier = Modifier) {
    var historyCpu by remember { mutableStateOf(List(50) { 0f }) }
    var historyRam by remember { mutableStateOf(List(50) { 0.40f }) }
    val context = LocalContext.current.applicationContext
    var temp by remember { mutableStateOf("") }
    var cpuKnown by remember { mutableStateOf(true) }
    var ramUsedStr by remember { mutableStateOf("0.0 GB") }
    var ramTotalStr by remember { mutableStateOf("0.0 GB") }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            var lastTotal = 0L
            var lastIdle = 0L

            while (isActive) {
                // 1. CPU. Android 8+ forbids apps from reading /proc/stat, so on
                //    this phone it always failed and the tile sat at a made-up 8%.
                //    Try it anyway (some ROMs allow it), else use how hard the
                //    cores are clocked: each core's current / max frequency.
                val stat = File("/proc/stat").runCatching { readLines().firstOrNull() }.getOrNull()
                var currentTotal = 0L
                var currentIdle = 0L
                if (stat != null && stat.startsWith("cpu ")) {
                    val parts = stat.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
                    if (parts.size >= 4) {
                        currentIdle = parts[3]
                        currentTotal = parts.sum()
                    }
                }

                val deltaTotal = currentTotal - lastTotal
                val deltaIdle = currentIdle - lastIdle
                val usage: Float? = if (deltaTotal > 0 && lastTotal > 0) {
                    (1f - (deltaIdle.toFloat() / deltaTotal.toFloat())).coerceIn(0f, 1f)
                } else {
                    cpuFreqLoad()
                }

                if (currentTotal > 0) {
                    lastTotal = currentTotal
                    lastIdle = currentIdle
                }

                // 2. Read RAM from /proc/meminfo
                val meminfo = File("/proc/meminfo").runCatching { readLines() }.getOrNull()
                var memTotalKb = 0L
                var memAvailableKb = 0L
                meminfo?.forEach { line ->
                    if (line.startsWith("MemTotal:")) {
                        memTotalKb = line.split(Regex("\\s+")).getOrNull(1)?.toLongOrNull() ?: 0L
                    }
                    if (line.startsWith("MemAvailable:")) {
                        memAvailableKb = line.split(Regex("\\s+")).getOrNull(1)?.toLongOrNull() ?: 0L
                    }
                }
                val ramUsage = if (memTotalKb > 0) {
                    (1f - (memAvailableKb.toFloat() / memTotalKb.toFloat())).coerceIn(0f, 1f)
                } else 0.50f
                val usedGb = String.format("%.1f", (memTotalKb - memAvailableKb).coerceAtLeast(0) / 1048576f)
                val totalGb = String.format("%.1f", memTotalKb / 1048576f)

                // 3. Temperature: the median CPU zone, so one core spiking does not
                //    read as the whole chip being hot. Falls back to the battery.
                val cpuTemps = mutableListOf<Int>()
                File("/sys/class/thermal").runCatching { listFiles() }.getOrNull()?.forEach { dir ->
                    if (!dir.name.startsWith("thermal_zone")) return@forEach
                    val type = File(dir, "type").runCatching { readText().trim().lowercase() }.getOrNull() ?: return@forEach
                    if (!type.startsWith("cpu")) return@forEach
                    val raw = File(dir, "temp").runCatching { readText().trim().toIntOrNull() }.getOrNull() ?: return@forEach
                    val t = if (raw > 1000) raw / 1000 else raw
                    if (t in 15..110) cpuTemps += t
                }
                val tempLabel = if (cpuTemps.isNotEmpty()) {
                    "${cpuTemps.sorted()[cpuTemps.size / 2]}°C"
                } else {
                    batteryTempC(context)?.let { "batt ${it}°C" } ?: "?°C"
                }

                withContext(Dispatchers.Main) {
                    cpuKnown = usage != null
                    historyCpu = (historyCpu + (usage ?: 0f)).takeLast(50)
                    historyRam = (historyRam + ramUsage).takeLast(50)
                    temp = tempLabel
                    ramUsedStr = "$usedGb GB"
                    ramTotalStr = "$totalGb GB"
                }

                delay(if (dev.aura.adosaura.theme.Palette.eco) 5000 else 1000)
            }
        }
    }

    val currentCpuPct = ((historyCpu.lastOrNull() ?: 0f) * 100).toInt()
    val currentRamPct = ((historyRam.lastOrNull() ?: 0f) * 100).toInt()

    Column(modifier.padding(10.dp)) {
        // CPU status line
        Text(
            text = (if (cpuKnown) "cpu $currentCpuPct%" else "cpu ?") + "  ·  $temp",
            color = Palette.tile.accent,
            fontSize = 12.sp,
            fontFamily = AdosauraMono,
        )
        Spacer(Modifier.height(4.dp))
        Box(Modifier.weight(1f).fillMaxSize()) {
            MatrixGraph(history = historyCpu)
        }
        
        Spacer(Modifier.height(8.dp))

        // RAM status line
        Text(
            text = "ram $currentRamPct%  ·  $ramUsedStr / $ramTotalStr",
            color = Palette.tile.cyan,
            fontSize = 12.sp,
            fontFamily = AdosauraMono,
        )
        Spacer(Modifier.height(4.dp))
        Box(Modifier.weight(1f).fillMaxSize()) {
            MatrixGraph(history = historyRam)
        }
    }
}

/**
 * Draws stacked block-matrix sparkline graphs matching the Aura OS System Monitor.
 */
@Composable
private fun MatrixGraph(
    history: List<Float>,
    modifier: Modifier = Modifier,
) {
    val p = Palette.tile
    Canvas(modifier = modifier.fillMaxSize()) {
        if (history.isEmpty()) return@Canvas
        val numCols = history.size
        val colWidth = size.width / numCols
        val barWidth = (colWidth - 1.2.dp.toPx()).coerceAtLeast(1.5f)

        val segHeight = 2.2.dp.toPx()
        val segGap = 1.0.dp.toPx()
        val segTotal = segHeight + segGap
        val maxSegments = (size.height / segTotal).toInt().coerceAtLeast(1)

        history.forEachIndexed { colIdx, value ->
            val activeSegs = (value.coerceIn(0f, 1f) * maxSegments).toInt().coerceAtLeast(1)
            val x = colIdx * colWidth
            for (seg in 0 until activeSegs) {
                val y = size.height - (seg + 1) * segTotal
                val fraction = seg.toFloat() / maxSegments
                val segColor = when {
                    fraction < 0.28f -> p.accent
                    fraction < 0.52f -> p.cyan
                    fraction < 0.72f -> p.green
                    fraction < 0.88f -> p.yellow
                    else -> p.red
                }
                drawRect(
                    color = segColor,
                    topLeft = Offset(x, y),
                    size = Size(barWidth, segHeight),
                )
            }
        }
    }
}

/** Mean of cur/max frequency over every core that exposes cpufreq, or null. */
private fun cpuFreqLoad(): Float? {
    val cores = File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu\\d+")) }
        ?: return null
    val loads = cores.mapNotNull { core ->
        val cur = File(core, "cpufreq/scaling_cur_freq").runCatching { readText().trim().toLong() }.getOrNull()
        val max = File(core, "cpufreq/cpuinfo_max_freq").runCatching { readText().trim().toLong() }.getOrNull()
        if (cur != null && max != null && max > 0) (cur.toFloat() / max).coerceIn(0f, 1f) else null
    }
    return if (loads.isEmpty()) null else loads.average().toFloat()
}

private fun batteryTempC(context: Context): Int? {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
    val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
    return if (tenths == Int.MIN_VALUE) null else tenths / 10
}
