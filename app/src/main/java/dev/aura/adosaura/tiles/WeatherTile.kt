package dev.aura.adosaura.tiles

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import dev.aura.adosaura.theme.AdosauraMono
import dev.aura.adosaura.theme.Metrics
import dev.aura.adosaura.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A simple weather tile fetching data from Open-Meteo. */
@Composable
fun WeatherTile(modifier: Modifier = Modifier) {
    var weather by remember { mutableStateOf("loading...") }
    var tempC by remember { mutableStateOf<Int?>(null) }
    var isError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) {
                try {
                    // Open-Meteo requires latitude and longitude. Since we don't have location permissions,
                    // we'll default to a generic lat/lon or try to detect it if possible. 
                    // Using IP-based detection via a free service or a fallback (e.g. London).
                    
                    var lat = 51.5074
                    var lon = -0.1278
                    
                    try {
                        val ipApi = URL("http://ip-api.com/json").readText()
                        val ipJson = JSONObject(ipApi)
                        if (ipJson.getString("status") == "success") {
                            lat = ipJson.getDouble("lat")
                            lon = ipJson.getDouble("lon")
                        }
                    } catch (e: Exception) {
                        // ignore IP-API failure
                    }
                    
                    val url = URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current_weather=true")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    val current = json.getJSONObject("current_weather")
                    val t = current.getDouble("temperature").toInt()
                    val code = current.getInt("weathercode")
                    
                    val desc = when (code) {
                        0 -> "clear"
                        1, 2, 3 -> "cloudy"
                        45, 48 -> "fog"
                        51, 53, 55, 56, 57 -> "drizzle"
                        61, 63, 65, 66, 67 -> "rain"
                        71, 73, 75, 77 -> "snow"
                        80, 81, 82 -> "showers"
                        85, 86 -> "snow showers"
                        95, 96, 99 -> "thunderstorm"
                        else -> "unknown"
                    }
                    
                    withContext(Dispatchers.Main) {
                        tempC = t
                        weather = desc
                        isError = false
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        weather = "error"
                        isError = true
                    }
                }
            }
            delay(15 * 60 * 1000) // 15 mins
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
            
            // Simple animated sun or cloud based on weather string
            if (!isError && weather != "loading...") {
                Canvas(modifier = Modifier.size(32.dp).padding(bottom = 8.dp)) {
                    if (weather == "clear") {
                        drawCircle(
                            color = Palette.current.yellow,
                            radius = size.width / 2 * scale,
                            center = center
                        )
                    } else if (weather.contains("cloud") || weather.contains("fog")) {
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

            Text(
                text = weather,
                color = if (isError) Palette.current.red else Palette.current.accent,
                fontSize = Metrics.bodySize,
                fontFamily = AdosauraMono,
            )
            if (tempC != null) {
                Text(
                    text = "${tempC}°C",
                    color = Palette.current.fgBright,
                    fontSize = Metrics.clockSize,
                    fontFamily = AdosauraMono,
                )
            }
        }
    }
}
