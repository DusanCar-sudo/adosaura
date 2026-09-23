package dev.aura.adosaura.tiles

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.sp
import dev.aura.adosaura.theme.AdosauraMono
import dev.aura.adosaura.theme.Metrics
import dev.aura.adosaura.theme.Palette
import kotlinx.coroutines.delay
import java.time.LocalTime
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Seconds since the tile appeared, advanced every frame. In eco mode the
 * clock only ticks once a second, so nothing animates smoothly and the GPU
 * stays asleep. Compose pauses frames while the launcher is off screen.
 */
@Composable
private fun rememberFrameSeconds(): Float {
    var t by remember { mutableFloatStateOf(0f) }
    val eco = Palette.eco
    LaunchedEffect(eco) {
        val start = withFrameMillis { it } - (t * 1000).toLong()
        while (true) {
            if (eco) {
                t = (System.currentTimeMillis() / 1000L % 100_000L).toFloat()
                delay(1000)
            } else {
                withFrameMillis { t = (it - start) / 1000f }
            }
        }
    }
    return t
}

// ------------------------------------------------------------------ analog

/** A round clock face with a sweeping second hand and a breathing centre. */
@Composable
fun AnalogClockTile() {
    val t = rememberFrameSeconds()
    val p = Palette.tile
    val now = LocalTime.now()
    val sec = now.second + now.nano / 1e9f
    val min = now.minute + sec / 60f
    val hour = now.hour % 12 + min / 60f

    Canvas(Modifier.fillMaxSize()) {
        val r = min(size.width, size.height) / 2f * 0.92f
        val c = center
        fun at(angleTurns: Float, len: Float): Offset {
            val a = angleTurns * 2 * PI - PI / 2
            return Offset(c.x + cos(a).toFloat() * len, c.y + sin(a).toFloat() * len)
        }
        drawCircle(p.dim, r, c, style = Stroke(r * 0.03f))
        for (i in 0 until 60) {
            val major = i % 5 == 0
            drawLine(
                color = if (major) p.fg else p.dim,
                start = at(i / 60f, r * if (major) 0.82f else 0.9f),
                end = at(i / 60f, r * 0.96f),
                strokeWidth = r * if (major) 0.035f else 0.015f,
            )
        }
        drawLine(p.fgBright, c, at(hour / 12f, r * 0.5f), r * 0.07f, StrokeCap.Round)
        drawLine(p.fg, c, at(min / 60f, r * 0.75f), r * 0.045f, StrokeCap.Round)
        // The second hand leaves a short fading arc behind it.
        for (k in 1..8) {
            drawLine(
                p.accent.copy(alpha = 0.35f * (1 - k / 9f)),
                c,
                at((sec - k * 0.12f) / 60f, r * 0.88f),
                r * 0.02f,
            )
        }
        drawLine(p.accent, at(sec / 60f + 0.5f, r * 0.15f), at(sec / 60f, r * 0.88f), r * 0.02f, StrokeCap.Round)
        val breathe = 0.5f + 0.5f * sin(t * 2f)
        drawCircle(p.accent.copy(alpha = 0.25f * breathe), r * (0.08f + 0.06f * breathe), c)
        drawCircle(p.accentBright, r * 0.05f, c)
    }
}

// ----------------------------------------------------------------- battery

private class Charge(val percent: Int, val charging: Boolean)

private fun readCharge(context: Context): Charge {
    val i: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val pct = if (level >= 0 && scale > 0) level * 100 / scale else 0
    return Charge(
        pct,
        status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
    )
}

/** A battery filled with liquid that sloshes; bubbles rise while charging. */
@Composable
fun BatteryTile() {
    val context = LocalContext.current.applicationContext
    var charge by remember { mutableStateOf(readCharge(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            charge = readCharge(context)
        }
    }
    val t = rememberFrameSeconds()
    val p = Palette.tile
    val level = charge.percent / 100f
    val liquid = when {
        charge.percent <= 20 -> p.red
        charge.percent <= 45 -> p.yellow
        else -> p.green
    }

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val h = size.height * 0.9f
                val w = min(size.width * 0.8f, h * 1.9f)
                val left = (size.width - w) / 2f
                val top = (size.height - h) / 2f
                val cap = w * 0.05f
                val bodyW = w - cap
                val radius = CornerRadius(h * 0.12f)
                val body = Path().apply {
                    addRoundRect(
                        androidx.compose.ui.geometry.RoundRect(left, top, left + bodyW, top + h, radius),
                    )
                }
                clipPath(body) {
                    val surface = top + h * (1 - level)
                    val wave = Path().apply {
                        moveTo(left, top + h)
                        var x = 0f
                        while (x <= bodyW) {
                            val y = surface + sin(x / bodyW * 4 * PI.toFloat() + t * 3f) * h * 0.04f
                            lineTo(left + x, y)
                            x += 4f
                        }
                        lineTo(left + bodyW, top + h)
                        close()
                    }
                    drawPath(wave, liquid.copy(alpha = 0.75f))
                    if (charge.charging) {
                        for (b in 0 until 7) {
                            val phase = (t * 0.4f + b * 0.37f) % 1f
                            val bx = left + bodyW * ((b * 0.29f + 0.1f) % 1f)
                            val by = top + h - phase * h * level
                            drawCircle(p.fgBright.copy(alpha = 0.5f * (1 - phase)), h * 0.035f, Offset(bx, by))
                        }
                    }
                }
                drawRoundRect(p.fg, Offset(left, top), Size(bodyW, h), radius, style = Stroke(h * 0.04f))
                drawRoundRect(p.fg, Offset(left + bodyW, top + h * 0.3f), Size(cap, h * 0.4f), CornerRadius(cap / 2))
            }
            Text(
                text = "${charge.percent}%",
                color = p.fgBright,
                fontSize = Metrics.clockSize * 0.6f,
                fontWeight = FontWeight.Bold,
                fontFamily = AdosauraMono,
            )
        }
        Text(
            text = if (charge.charging) "charging" else "on battery",
            color = if (charge.charging) p.green else p.muted,
            fontSize = Metrics.smallSize,
            fontFamily = AdosauraMono,
        )
    }
}

// ------------------------------------------------------------------ matrix

private const val GLYPHS = "ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄ0123456789ZXΛΞ#$%&*+<>"

/** Falling columns of glyphs, in the tile's own colours. */
@Composable
fun MatrixTile() {
    val t = rememberFrameSeconds()
    val p = Palette.tile
    val measurer = rememberTextMeasurer()
    val glyphs = remember(measurer) {
        GLYPHS.map { measurer.measure(it.toString(), TextStyle(fontFamily = AdosauraMono, fontSize = 13.sp)) }
    }

    Canvas(Modifier.fillMaxSize()) {
        val cellW = glyphs[0].size.width.toFloat().coerceAtLeast(8f) * 1.15f
        val cellH = glyphs[0].size.height.toFloat().coerceAtLeast(10f)
        val cols = (size.width / cellW).toInt().coerceAtLeast(1)
        val rows = (size.height / cellH).toInt().coerceAtLeast(1)
        for (col in 0 until cols) {
            // Each column gets its own speed, trail and start from a cheap hash.
            val seed = (col * 7919) % 1000 / 1000f
            val speed = 4f + seed * 8f
            val trail = 5 + (seed * 11).toInt()
            val span = rows + trail
            val head = (t * speed + seed * span * 3) % span
            for (k in 0 until trail) {
                val row = floor(head).toInt() - k
                if (row < 0 || row >= rows) continue
                val flicker = floor(t * 3f + row * 0.7f + col).toInt()
                val g = glyphs[((row * 31 + col * 17 + flicker) and 0x7fffffff) % glyphs.size]
                val colour = if (k == 0) p.fgBright else p.accent
                drawText(
                    g,
                    color = colour,
                    topLeft = Offset(col * cellW, row * cellH),
                    alpha = if (k == 0) 1f else (1f - k / trail.toFloat()) * 0.9f,
                )
            }
        }
    }
}
