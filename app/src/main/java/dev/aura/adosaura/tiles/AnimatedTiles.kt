package dev.aura.adosaura.tiles

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.aura.adosaura.ui.WhileStarted
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
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Seconds since the tile appeared, advanced every frame. In eco mode the
 * clock only ticks once a second, so nothing animates smoothly and the GPU
 * stays asleep. Compose pauses frames while the launcher is off screen.
 */
@Composable
private fun rememberFrameSeconds(): Float {
    var t by remember { mutableFloatStateOf(0f) }
    val eco = Palette.eco
    WhileStarted(eco) {
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

/**
 * Gravity as the tile feels it, in screen coordinates (x right, y down), as a
 * spring so the liquid overshoots and settles like a real water level.
 */
private class Slosh {
    var gx = 0f; var gy = 1f
    var vx = 0f; var vy = 0f
    var sx = 0f; var sy = 1f   // raw sensor target
    var last = -1f
    var shake = 0f             // 0..1, how much the colours are cycling
    var hue = 0f               // degrees

    fun step(t: Float) {
        val dt = if (last < 0f) 0f else (t - last).coerceIn(0f, 0.05f)
        last = t
        shake = (shake - dt / 4f).coerceAtLeast(0f)          // fades ~4 s after the shaking stops
        hue = (hue + dt * (60f + 300f * shake)) % 360f
        val k = 40f; val c = 5f
        vx += (k * (sx - gx) - c * vx) * dt
        vy += (k * (sy - gy) - c * vy) * dt
        gx += vx * dt
        gy += vy * dt
        val n = hypot(gx, gy)
        if (n > 1e-3f) { gx /= n; gy /= n }
    }
}

/** Phone gravity into [Slosh]; flat on a table the liquid settles level. */
@Composable
private fun RegisterGravity(slosh: Slosh, active: Boolean) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(active, lifecycle) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val tx = -e.values[0] / SensorManager.GRAVITY_EARTH
                val ty = e.values[1] / SensorManager.GRAVITY_EARTH
                val len = hypot(tx, ty)
                // Screen-plane pull fades out as the phone lies flat.
                val w = ((len - 0.15f) / 0.2f).coerceIn(0f, 1f)
                val dx = if (len > 1e-3f) tx / len else 0f
                val dy = if (len > 1e-3f) ty / len else 1f
                val x = w * dx
                val y = w * dy + (1 - w)
                val n = hypot(x, y).coerceAtLeast(1e-3f)
                slosh.sx = x / n
                slosh.sy = y / n
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        // A shake is the raw pull straying well off 1 g; gravity alone cannot see it.
        val shaker = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val m = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                if (abs(m - SensorManager.GRAVITY_EARTH) > 6f) slosh.shake = min(1f, slosh.shake + 0.15f)
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        val accel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        // Gravity stays live even in eco (the tile must still tilt); eco
        // only slows the rate, since the costly part is per-frame drawing.
        val rate = if (active) SensorManager.SENSOR_DELAY_GAME else SensorManager.SENSOR_DELAY_UI
        fun start() {
            if (sm == null || sensor == null) return
            sm.registerListener(listener, sensor, rate)
            if (accel != null) sm.registerListener(shaker, accel, rate)
        }
        fun stop() {
            sm?.unregisterListener(listener)
            sm?.unregisterListener(shaker)
        }
        // Game-rate sensors keep waking the CPU after Home is left, and the
        // system kills a background process that burns CPU.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) start() else if (event == Lifecycle.Event.ON_STOP) stop()
        }
        lifecycle.addObserver(observer) // replays up to the current state, so an already-started launcher registers now
        onDispose { lifecycle.removeObserver(observer); stop() }
    }
}

/** Area of the w x h box (centred on the origin) where x*gx + y*gy >= d. */
private fun filledArea(w: Float, h: Float, gx: Float, gy: Float, d: Float): Float {
    var poly = listOf(
        Offset(-w / 2, -h / 2), Offset(w / 2, -h / 2), Offset(w / 2, h / 2), Offset(-w / 2, h / 2),
    )
    val out = ArrayList<Offset>(6)
    for (i in poly.indices) {
        val a = poly[i]; val b = poly[(i + 1) % poly.size]
        val da = a.x * gx + a.y * gy - d
        val db = b.x * gx + b.y * gy - d
        if (da >= 0) out.add(a)
        if ((da >= 0) != (db >= 0)) {
            val f = da / (da - db)
            out.add(Offset(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f))
        }
    }
    poly = out
    var area = 0f
    for (i in poly.indices) {
        val a = poly[i]; val b = poly[(i + 1) % poly.size]
        area += a.x * b.y - b.x * a.y
    }
    return abs(area) / 2f
}

/** The surface offset along gravity that holds exactly [level] of the box. */
private fun surfaceOffset(w: Float, h: Float, gx: Float, gy: Float, level: Float): Float {
    val half = (abs(gx) * w + abs(gy) * h) / 2f
    val target = w * h * level
    var lo = -half; var hi = half
    repeat(18) {
        val mid = (lo + hi) / 2f
        if (filledArea(w, h, gx, gy, mid) > target) lo = mid else hi = mid
    }
    return (lo + hi) / 2f
}

/**
 * A battery that is a water level (waservaga): the liquid stays flat against
 * gravity as the phone tilts, sloshes when it is moved, and bubbles rise
 * toward "up" while charging.
 */
@Composable
fun BatteryTile() {
    val context = LocalContext.current.applicationContext
    var charge by remember { mutableStateOf(readCharge(context)) }
    WhileStarted {
        while (true) {
            delay(5000)
            charge = readCharge(context)
        }
    }
    val t = rememberFrameSeconds()
    val p = Palette.tile
    val slosh = remember { Slosh() }
    RegisterGravity(slosh, active = true)
    slosh.step(t)
    val level = charge.percent / 100f
    val base = when {
        charge.percent <= 20 -> p.red
        charge.percent <= 45 -> p.yellow
        else -> p.green
    }
    // Shaking spins the liquid through the rainbow; it drifts back to its level colour.
    val liquid = androidx.compose.ui.graphics.lerp(
        base,
        androidx.compose.ui.graphics.Color(android.graphics.Color.HSVToColor(floatArrayOf(slosh.hue, 0.8f, 1f))),
        slosh.shake.coerceAtMost(1f),
    )

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
                val gx = slosh.gx; val gy = slosh.gy
                val ux = -gy; val uy = gx                 // along the surface
                val centre = Offset(left + bodyW / 2f, top + h / 2f)
                val d = surfaceOffset(bodyW, h, gx, gy, level)
                val agitation = (hypot(slosh.vx, slosh.vy) * 0.8f).coerceIn(0f, 1f)
                val amp = h * (0.025f + 0.07f * agitation)
                val reach = hypot(bodyW, h)
                clipPath(body) {
                    val wave = Path()
                    var s = -reach
                    var first = true
                    while (s <= reach) {
                        val rip = sin(s / bodyW * 4 * PI.toFloat() + t * 3f) * amp
                        val x = centre.x + gx * (d + rip) + ux * s
                        val y = centre.y + gy * (d + rip) + uy * s
                        if (first) { wave.moveTo(x, y); first = false } else wave.lineTo(x, y)
                        s += 4f
                    }
                    wave.lineTo(centre.x + gx * (d + reach) + ux * reach, centre.y + gy * (d + reach) + uy * reach)
                    wave.lineTo(centre.x + gx * (d + reach) - ux * reach, centre.y + gy * (d + reach) - uy * reach)
                    wave.close()
                    drawPath(wave, liquid.copy(alpha = 0.75f))
                    if (charge.charging && level > 0f) {
                        val half = (abs(gx) * bodyW + abs(gy) * h) / 2f
                        val halfU = (abs(gy) * bodyW + abs(gx) * h) / 2f
                        for (b in 0 until 7) {
                            val phase = (t * 0.4f + b * 0.37f) % 1f
                            val lateral = ((b * 0.29f + 0.1f) % 1f - 0.5f) * 1.8f * halfU
                            val along = half - phase * (half - d)   // far wall up to the surface
                            val bx = centre.x + gx * along + ux * lateral
                            val by = centre.y + gy * along + uy * lateral
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
