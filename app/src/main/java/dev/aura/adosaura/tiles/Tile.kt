package dev.aura.adosaura.tiles

import dev.aura.adosaura.config.TileSpec

/**
 * Every tile the launcher knows how to draw. A type that is not in here still
 * renders — as `? type` — so a typo in layout.conf is visible instead of
 * silently eating a slot.
 */
enum class TileType(val id: String) {
    /** Big time, with the date under it. */
    CLOCK("clock"),

    /** The month grid, today in the accent colour. */
    CALENDAR("calendar"),

    /** Clock over a compact month grid — the tall middle tile. */
    CLOCKCAL("clockcal"),

    /** A titled list of text rows from one group in apps.conf (`apps:social`). */
    APPS("apps"),

    /** Battery, free storage and uptime, as Aura reports them. */
    STATS("stats"),

    /** A sparkline graph showing CPU usage and temperature. */
    PERF("perf"),

    /** Current weather and temperature. */
    WEATHER("weather"),

    /** A round clock with a sweeping second hand. Animated. */
    ANALOG("analog"),

    /** Battery as sloshing liquid, bubbles while charging. Animated. */
    BATTERY("battery"),

    /** Falling columns of glyphs. Animated, decorative. */
    MATRIX("matrix"),
    ;

    companion object {
        fun of(id: String): TileType? = entries.firstOrNull { it.id == id }
    }
}

/** The title a tile wears on its `─ title ─` strip. */
fun titleOf(spec: TileSpec): String {
    val type = TileType.of(spec.type) ?: return "?? ${spec.type}"
    return when (type) {
        TileType.CLOCK -> "clock"
        TileType.CALENDAR -> "calendar"
        TileType.CLOCKCAL -> "time"
        TileType.APPS -> {
            val arg = spec.arg ?: "apps"
            if (arg.contains(".")) {
                // Heuristic: if it looks like a package name, try to extract something readable.
                arg.substringAfterLast(".")
            } else {
                arg
            }
        }
        TileType.STATS -> "status"
        TileType.PERF -> "performance"
        TileType.WEATHER -> "weather"
        TileType.ANALOG -> "clock"
        TileType.BATTERY -> "battery"
        TileType.MATRIX -> "matrix"
    }
}
