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

/**
 * A block-matrix system monitor graph showing CPU usage, live temperature,
 * and RAM metrics in Aura OS style.
 */
@Composable
fun PerfTile(modifier: Modifier = Modifier) {
    var historyCpu by remember { mutableStateOf(List(50) { 0.05f }) }
    var historyRam by remember { mutableStateOf(List(50) { 0.40f }) }
    var temp by remember { mutableStateOf("40°C") }
    var ramUsedStr by remember { mutableStateOf("0.0 GB") }
    var ramTotalStr by remember { mutableStateOf("0.0 GB") }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            var lastTotal = 0L
            var lastIdle = 0L

            while (isActive) {
                // 1. Read CPU from /proc/stat
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
                val usage = if (deltaTotal > 0 && lastTotal > 0) {
                    (1f - (deltaIdle.toFloat() / deltaTotal.toFloat())).coerceIn(0f, 1f)
                } else 0.08f
                
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

                // 3. Read CPU Temp from /sys/class/thermal
                val thermals = File("/sys/class/thermal").runCatching { listFiles() }.getOrNull()
                var maxCpuTemp = 0
                var maxAnyTemp = 0
                thermals?.forEach { dir ->
                    if (dir.name.startsWith("thermal_zone")) {
                        val type = File(dir, "type").runCatching { readText().trim().lowercase() }.getOrNull() ?: ""
                        // Skip 'soc' or 'step' thermal zones that report bogus numbers (e.g. 274000)
                        if (type == "soc" || type.contains("step")) return@forEach
                        val raw = File(dir, "temp").runCatching { readText().trim().toIntOrNull() }.getOrNull() ?: return@forEach
                        val t = if (raw > 1000) raw / 1000 else raw
                        if (t in 15..110) {
                            if (type.contains("cpu") || type.contains("cpuss")) {
                                if (t > maxCpuTemp) maxCpuTemp = t
                            } else {
                                if (t > maxAnyTemp) maxAnyTemp = t
                            }
                        }
                    }
                }
                val tempC = when {
                    maxCpuTemp > 0 -> maxCpuTemp
                    maxAnyTemp > 0 -> maxAnyTemp
                    else -> 41
                }

                withContext(Dispatchers.Main) {
                    historyCpu = (historyCpu + usage).takeLast(50)
                    historyRam = (historyRam + ramUsage).takeLast(50)
                    temp = "${tempC}°C"
                    ramUsedStr = "$usedGb GB"
                    ramTotalStr = "$totalGb GB"
                }

                delay(1000)
            }
        }
    }

    val currentCpuPct = ((historyCpu.lastOrNull() ?: 0f) * 100).toInt()
    val currentRamPct = ((historyRam.lastOrNull() ?: 0f) * 100).toInt()

    Column(modifier.padding(10.dp)) {
        // CPU status line
        Text(
            text = "cpu $currentCpuPct%  ·  $temp",
            color = Palette.current.accent,
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
            color = Palette.current.cyan,
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
                    fraction < 0.28f -> Palette.current.accent
                    fraction < 0.52f -> Palette.current.cyan
                    fraction < 0.72f -> Palette.current.green
                    fraction < 0.88f -> Palette.current.yellow
                    else -> Palette.current.red
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
