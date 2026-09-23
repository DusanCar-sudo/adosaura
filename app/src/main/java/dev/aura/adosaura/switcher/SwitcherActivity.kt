package dev.aura.adosaura.switcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.aura.adosaura.config.ConfigFiles
import dev.aura.adosaura.config.KeyValueParser
import dev.aura.adosaura.drawer.AppRepository
import dev.aura.adosaura.theme.Palette
import dev.aura.adosaura.theme.Themes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The switcher on its own, so the edge swipe-down can open it over any app.
 *
 * Its own task affinity keeps it out of the launcher's task: closing it drops
 * you back into the app you were in, not onto the home screen.
 */
class SwitcherActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Opened by the edge service with the launcher never shown in this
        // process: load the theme here, or the switcher comes up in fallback colours.
        if (Palette.revision == 0) {
            val look = KeyValueParser.parse(ConfigFiles.read(ConfigFiles.look).orEmpty()).entries
            val panelAlpha = look["panel_alpha"]?.toIntOrNull()?.let { it / 100f } ?: 0.85f
            val theme = ConfigFiles.read(ConfigFiles.theme)?.trim().orEmpty()
            Palette.apply(Themes.load(this, theme, panelAlpha))
        }

        setContent {
            var labels by remember { mutableStateOf<Map<String, String>?>(null) }
            LaunchedEffect(Unit) {
                labels = withContext(Dispatchers.IO) { AppRepository(this@SwitcherActivity).labels() }
            }
            labels?.let { SwitcherScreen(labels = it, onClose = { finish() }) }
        }
    }

    override fun onPause() {
        super.onPause()
        // Picked an app, pressed Home, or the screen went off: the switcher
        // is never something to come back to.
        finish()
    }
}
