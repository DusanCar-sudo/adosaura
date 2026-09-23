package dev.aura.adosaura.theme

import android.content.Context
import dev.aura.adosaura.config.KeyValueParser

/**
 * The themes bundled with the launcher: the same nine Aura OS ships, copied
 * verbatim from `/mnt/data/aura-os/themes/<name>/palette`.
 *
 * Adding a tenth is a file drop into `assets/themes/<name>/palette` — no code,
 * no rebuild of the parser.
 */
object Themes {

    /** The directory names under `assets/themes/`, in the order they show up. */
    val BUNDLED = listOf(
        "purplerain",
        "tokyo-night",
        "aura",
        "nord",
        "gruvbox",
        "sunset",
        "neon",
        "mono",
        "paper",
    )

    const val DEFAULT = "gruvbox"

    /** Load one bundled theme by name; unknown names fall back, never crash. */
    fun load(context: Context, name: String, panelAlpha: Float): ThemePalette {
        val wanted = name.ifBlank { DEFAULT }
        val file = if (wanted in BUNDLED) wanted else DEFAULT

        return runCatching {
            val text = context.assets.open("themes/$file/palette")
                .bufferedReader()
                .use { it.readText() }
            ThemePalette.fromHex(file, KeyValueParser.parse(text).entries, panelAlpha)
        }.getOrElse {
            ThemePalette.fallback(file).copy(panelAlpha = panelAlpha)
        }
    }
}
