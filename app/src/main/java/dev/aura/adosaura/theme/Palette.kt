package dev.aura.adosaura.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * One theme: the 15 keys every Aura OS `palette` file has, plus how solid its
 * panes are. Built from a `palette` file so a theme written for the desktop
 * can be dropped onto the phone untouched.
 */
data class ThemePalette(
    val name: String,
    val bg: Color,
    val bgAlt: Color,
    val border: Color,
    val fg: Color,
    val fgBright: Color,
    val dim: Color,
    val muted: Color,
    val accent: Color,
    val accentBright: Color,
    val red: Color,
    val green: Color,
    val yellow: Color,
    val blue: Color,
    val cyan: Color,
    val orange: Color,
    val panelAlpha: Float,
) {
    companion object {

        /** gruvbox default, so the launcher matches the requested theme immediately. */
        fun fallback(name: String = "gruvbox"): ThemePalette =
            fromHex(
                name,
                mapOf(
                    "bg" to "#1d2021",
                    "bg_alt" to "#282828",
                    "border" to "#3c3836",
                    "fg" to "#ebdbb2",
                    "fg_bright" to "#fbf1c7",
                    "dim" to "#665c54",
                    "muted" to "#928374",
                    "accent" to "#fe8019",
                    "accent_bright" to "#fabd2f",
                    "red" to "#fb4934",
                    "green" to "#b8bb26",
                    "yellow" to "#fabd2f",
                    "blue" to "#83a598",
                    "cyan" to "#8ec07c",
                    "orange" to "#fe8019",
                ),
                0.85f
            )

        /**
         * @param hex the `key=value` entries of a palette file (`bg=#0e0e14`)
         * @param panelAlpha from `look`'s `panel_alpha`, 0..1
         */
        fun fromHex(name: String, hex: Map<String, String>, panelAlpha: Float = 0.85f): ThemePalette {
            fun color(key: String, default: Long) = Color(parseHex(hex[key]) ?: default)

            return ThemePalette(
                name = name,
                bg = color("bg", 0xFF1D2021),
                bgAlt = color("bg_alt", 0xFF282828),
                border = color("border", 0xFF3C3836),
                fg = color("fg", 0xFFEBDBB2),
                fgBright = color("fg_bright", 0xFFFBF1C7),
                dim = color("dim", 0xFF665C54),
                muted = color("muted", 0xFF928374),
                accent = color("accent", 0xFFFE8019),
                accentBright = color("accent_bright", 0xFFFABD2F),
                red = color("red", 0xFFFB4934),
                green = color("green", 0xFFB8BB26),
                yellow = color("yellow", 0xFFFABD2F),
                blue = color("blue", 0xFF83A598),
                cyan = color("cyan", 0xFF8EC07C),
                orange = color("orange", 0xFFFE8019),
                panelAlpha = panelAlpha.coerceIn(0f, 1f),
            )
        }

        /** `#0e0e14` or `0e0e14` -> opaque ARGB, or null when it is not a colour. */
        private fun parseHex(value: String?): Long? {
            val digits = value?.trim()?.removePrefix("#")?.trim() ?: return null
            if (digits.length != 6) return null
            val parsed = digits.toLongOrNull(16) ?: return null
            return parsed or 0xFF000000L
        }
    }
}

/**
 * The palette the whole launcher draws with.
 *
 * Backed by [mutableStateOf], so swapping a theme is one assignment and every
 * `Text`, `border` and `background` that read [Palette] recomposes. Call sites
 * never change when a theme does.
 *
 * Mirrors `/mnt/data/aura-os/themes/<name>/palette` key for key — the same
 * names `aura-os-theme` reads on the laptop. If a colour changes there, it
 * changes here too.
 */
object Palette {

    var current by mutableStateOf(ThemePalette.fallback())
        private set
    var revision by mutableStateOf(0)
        private set

    fun apply(theme: ThemePalette) {
        current = theme
        revision++
    }

    val bg get() = current.bg
    val bgAlt get() = current.bgAlt
    val border get() = current.border
    val fg get() = current.fg
    val fgBright get() = current.fgBright
    val dim get() = current.dim
    val muted get() = current.muted
    val accent get() = current.accent
    val accentBright get() = current.accentBright
    val red get() = current.red
    val green get() = current.green
    val yellow get() = current.yellow
    val blue get() = current.blue
    val cyan get() = current.cyan
    val orange get() = current.orange


                                                            
    /** `look`'s `panel_alpha`: 1.0 is a solid pane, 0.5 is glass. */
    val panelAlpha get() = current.panelAlpha

    /** The theme's name, shown in the settings menu. */
    val name get() = current.name
    
    var wallpaperOverride by mutableStateOf<String?>(null)
        
    fun applyWallpaperOverride(type: String?) {
        wallpaperOverride = type
    }

    /** `look`'s `crt=on`: scanlines and a vignette over the whole launcher. */
    var crt by mutableStateOf(false)
}
