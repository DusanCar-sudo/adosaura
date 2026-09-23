package dev.aura.adosaura.tiles

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import dev.aura.adosaura.theme.AdosauraMono
import dev.aura.adosaura.theme.Metrics
import dev.aura.adosaura.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import androidx.compose.ui.platform.LocalContext
import dev.aura.adosaura.config.ConfigFiles
import dev.aura.adosaura.config.KeyValueParser
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Current weather from Open-Meteo, for wherever the phone actually is.
 *
 * Where "here" is, most precise first:
 *  1. the phone's last known coarse location (ACCESS_COARSE_LOCATION)
 *  2. `lat=` / `lon=` in /sdcard/adosaura/look
 *  3. IP geolocation over HTTPS
 *
 * The place is printed under the temperature, so a wrong guess is visible
 * instead of quietly showing someone else's weather.
 */
@Composable
fun WeatherTile(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    var weather by remember { mutableStateOf("loading...") }
    var tempC by remember { mutableStateOf<Int?>(null) }
    var place by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            val result = withContext(Dispatchers.IO) { runCatching { fetchWeather(context) } }
            result.onSuccess {
                tempC = it.tempC
                weather = it.desc
                place = it.place
                isError = false
            }.onFailure {
                // Keep the last good reading on a flaky network; only say
                // "error" when there has never been one.
                if (tempC == null) {
                    weather = "offline"
                    isError = true
                }
            }
            delay(if (result.isSuccess) 15 * 60 * 1000L else 60 * 1000L)
        }
    }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val infiniteTransition = rememberInfiniteTransition(label = "weather")
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.9f,
                targetValue = 1.1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2000, easing = EaseInOut),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "weather_anim"
            )
            
            // Icon beside the temperature: stacked, they overflow a half-height tile.
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Simple animated sun or cloud based on weather string
                if (!isError && weather != "loading...") {
                    Canvas(modifier = Modifier.size(32.dp)) {
                        if (weather.contains("clear")) {
                            drawCircle(
                                color = Palette.current.yellow,
                                radius = size.width / 2 * scale,
                                center = center
                            )
                        } else if (weather.contains("cloud") || weather.contains("overcast") || weather.contains("fog")) {
                            val cloudColor = Palette.current.fgBright
                            drawCircle(
                                color = cloudColor,
                                radius = size.width / 3,
                                center = Offset(center.x - 10f * scale, center.y)
                            )
                            drawCircle(
                                color = cloudColor,
                                radius = size.width / 2.5f,
                                center = Offset(center.x + 5f * scale, center.y - 5f)
                            )
                            drawCircle(
                                color = cloudColor,
                                radius = size.width / 3.5f,
                                center = Offset(center.x + 15f * scale, center.y + 2f)
                            )
                        } else if (weather.contains("rain") || weather.contains("drizzle") || weather.contains("shower")) {
                            drawCircle(
                                color = Palette.current.dim,
                                radius = size.width / 2.5f,
                                center = Offset(center.x, center.y - 5f)
                            )
                            drawCircle(
                                color = Palette.current.blue,
                                radius = 4f,
                                center = Offset(center.x - 5f, center.y + 10f + (scale * 5f))
                            )
                            drawCircle(
                                color = Palette.current.blue,
                                radius = 4f,
                                center = Offset(center.x + 5f, center.y + 15f + (scale * 5f))
                            )
                        } else if (weather.contains("snow")) {
                            drawCircle(
                                color = Palette.current.fgBright,
                                radius = size.width / 2.5f,
                                center = Offset(center.x, center.y - 5f)
                            )
                            drawCircle(
                                color = Palette.current.fgBright,
                                radius = 3f,
                                center = Offset(center.x - 5f, center.y + 10f + (scale * 5f))
                            )
                            drawCircle(
                                color = Palette.current.fgBright,
                                radius = 3f,
                                center = Offset(center.x + 5f, center.y + 15f + (scale * 5f))
                            )
                        }
                    }
                }

                if (tempC != null) {
                    Text(
                        text = "${tempC}°",
                        color = Palette.current.fgBright,
                        fontSize = Metrics.clockSize * 0.75f,
                        softWrap = false,
                        fontFamily = AdosauraMono,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
            Text(
                text = if (place.isEmpty() || isError) weather else "$weather\n$place",
                textAlign = TextAlign.Center,
                lineHeight = Metrics.bodySize,
                color = if (isError) Palette.current.red else Palette.current.accent,
                fontSize = Metrics.bodySize,
                fontFamily = AdosauraMono,
            )
        }
    }
}

private class Reading(val tempC: Int, val desc: String, val place: String)

private class Spot(val lat: Double, val lon: Double, val place: String)

private fun fetchWeather(context: Context): Reading {
    val spot = deviceSpot(context) ?: lookSpot() ?: ipSpot()
        ?: error("no location")
    val json = JSONObject(
        httpGet(
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=${spot.lat}&longitude=${spot.lon}" +
                "&current=temperature_2m,weather_code&timezone=auto",
        ),
    )
    val current = json.getJSONObject("current")
    return Reading(
        tempC = current.getDouble("temperature_2m").roundToInt(),
        desc = describe(current.getInt("weather_code")),
        place = spot.place,
    )
}

/** WMO weather codes, as Open-Meteo documents them. */
private fun describe(code: Int): String = when (code) {
    0 -> "clear"
    1 -> "mostly clear"
    2 -> "partly cloudy"
    3 -> "overcast"
    45, 48 -> "fog"
    51, 53, 55, 56, 57 -> "drizzle"
    61, 63, 65, 66, 67 -> "rain"
    71, 73, 75, 77 -> "snow"
    80, 81, 82 -> "showers"
    85, 86 -> "snow showers"
    95, 96, 99 -> "thunderstorm"
    else -> "unknown"
}

/** The freshest fix any provider already has — no GPS spin-up, no battery cost. */
private fun deviceSpot(context: Context): Spot? {
    val granted = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    if (!granted) return null
    val lm = context.getSystemService(LocationManager::class.java) ?: return null
    val fix = runCatching {
        lm.getProviders(true)
            .mapNotNull { lm.getLastKnownLocation(it) }
            .maxByOrNull { it.time }
    }.getOrNull() ?: return null
    val city = runCatching {
        @Suppress("DEPRECATION")
        Geocoder(context, Locale.getDefault())
            .getFromLocation(fix.latitude, fix.longitude, 1)
            ?.firstOrNull()?.locality
    }.getOrNull()
    return Spot(fix.latitude, fix.longitude, city?.lowercase() ?: "here")
}

/** `lat=44.8` / `lon=20.4` (and optional `place=`) in the look file. */
private fun lookSpot(): Spot? {
    val kv = KeyValueParser.parse(ConfigFiles.read(ConfigFiles.look).orEmpty()).entries
    val lat = kv["lat"]?.toDoubleOrNull() ?: return null
    val lon = kv["lon"]?.toDoubleOrNull() ?: return null
    return Spot(lat, lon, kv["place"] ?: "look")
}

/** HTTPS only: Android blocks cleartext HTTP, which is why the old lookup always fell back to London. */
private fun ipSpot(): Spot? = runCatching {
    val j = JSONObject(httpGet("https://ipwho.is/"))
    if (!j.optBoolean("success")) null
    else Spot(j.getDouble("latitude"), j.getDouble("longitude"), "~" + j.optString("city").lowercase())
}.getOrNull()

private fun httpGet(url: String): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 5000
    conn.readTimeout = 5000
    return try {
        conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
        conn.disconnect()
    }
}
