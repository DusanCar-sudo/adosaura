package dev.aura.adosaura.config

/**
 * The per-tile style words in layout.conf. Pure, so the parser can check a
 * colour without Android; the UI turns the names into real colours.
 */
object TileStyle {

    const val MIN_SIZE = 60
    const val MAX_SIZE = 200

    /** Theme colours by name: these follow a theme change. */
    val THEME_KEYS = listOf("accent", "fg", "dim", "red", "green", "yellow", "blue", "cyan", "orange")

    /** Fixed colours offered in the menu, named for the menu only. */
    val FIXED = linkedMapOf(
        "white" to "ffffff",
        "black" to "000000",
        "pink" to "ff5cd0",
        "purple" to "8a5cff",
        "deep red" to "3a0f14",
        "deep blue" to "0f1f3a",
        "deep green" to "0f2a18",
        "deep purple" to "25123a",
    )

    /** A valid colour word, lower-cased, or null. */
    fun colour(value: String): String? {
        val v = value.trim().lowercase().removePrefix("#")
        return when {
            v in THEME_KEYS -> v
            v.length == 6 && v.all { it in '0'..'9' || it in 'a'..'f' } -> v
            else -> null
        }
    }

    /** One step of the text-size buttons, kept in range. */
    fun step(size: Int, by: Int): Int = (size + by * 15).coerceIn(MIN_SIZE, MAX_SIZE)
}
