package dev.aura.adosaura.config

/**
 * `key=value` parsing, shared by the theme `palette` files and the `look`
 * file — the same two formats `aura-os-theme` and `~/.config/aura-os/look` use
 * on the laptop, so a theme written for the desktop works on the phone
 * unchanged.
 *
 * Pure Kotlin, no Android imports: tested on the JVM in KeyValueTest.
 *
 *     panel_alpha=85      # trailing comments and full-line comments are fine
 *     bg=#0e0e14
 */

data class KeyValue(val entries: Map<String, String> = emptyMap()) {

    fun int(key: String, default: Int): Int =
        entries[key]?.trim()?.toIntOrNull() ?: default

    fun double(key: String, default: Double): Double =
        entries[key]?.trim()?.toDoubleOrNull() ?: default

    fun string(key: String, default: String): String =
        entries[key]?.trim()?.takeIf { it.isNotEmpty() } ?: default

    /** `85` -> `0.85f`, clamped to the only range that makes sense. */
    fun percentFloat(key: String, default: Float): Float {
        val raw = entries[key]?.trim()?.toFloatOrNull() ?: return default
        return (raw / 100f).coerceIn(0f, 1f)
    }
}

object KeyValueParser {

    fun parse(text: String): KeyValue {
        val map = linkedMapOf<String, String>()

        text.split('\n').forEach { raw ->
            val trimmed = raw.removeSuffix("\r").trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#')) return@forEach

            val at = trimmed.indexOf('=')
            if (at <= 0) return@forEach

            val key = trimmed.substring(0, at).trim()
            if (key.isEmpty()) return@forEach

            var value = trimmed.substring(at + 1).trim()
            // Trailing comments are preceded by whitespace (e.g. "panel_alpha=85  # comment")
            // Hex color values like "#1d2021" must NOT be stripped!
            val commentIdx = value.indexOf(" #")
            if (commentIdx >= 0) {
                value = value.substring(0, commentIdx).trim()
            }
            map[key] = value
        }

        return KeyValue(map)
    }

    /** Used when writing `look` back after a change from the settings menu. */
    fun format(entries: Map<String, String>): String = buildString {
        appendLine("# adosaura — look. Hot-reloaded; edit freely.")
        appendLine("# The same key=value shape as ~/.config/aura-os/look on the laptop.")
        appendLine("#   panel_alpha=85      panes: 100 solid .. 50 glass")
        appendLine("#   weather_lat=44.81   where the weather tile looks")
        appendLine("#   weather_lon=20.46")
        appendLine()
        entries.forEach { (key, value) -> appendLine("$key=$value") }
    }
}
