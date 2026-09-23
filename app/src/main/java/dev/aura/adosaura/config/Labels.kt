package dev.aura.adosaura.config

/**
 * What each row actually prints. Two apps can share a name (Google's and
 * Microsoft's "Authenticator"); those get the vendor from their package name
 * appended, so the drawer never shows the same word twice.
 */
object Labels {

    private val GENERIC = setOf("com", "org", "net", "io", "dev", "app", "apps", "android", "mobile")

    fun display(entries: List<AppEntry>): Map<String, String> {
        val out = HashMap<String, String>(entries.size)
        entries.groupBy { it.label.lowercase() }.values.forEach { same ->
            if (same.size == 1) {
                out[same[0].pkg] = same[0].label
                return@forEach
            }
            val tags = same.associate { it.pkg to vendorOf(it.pkg, it.label) }
            val clash = tags.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            same.forEach { entry ->
                val tag = tags.getValue(entry.pkg).takeUnless { it in clash } ?: entry.pkg
                out[entry.pkg] = "${entry.label} · $tag"
            }
        }
        return out
    }

    /** The first package segment that says who made it: `com.azure.authenticator` → `azure`. */
    fun vendorOf(pkg: String, label: String): String {
        val labelWord = label.lowercase().filter { it.isLetterOrDigit() }
        return pkg.split('.')
            .firstOrNull { it.lowercase() !in GENERIC && it.lowercase() != labelWord }
            ?: pkg.substringAfterLast('.')
    }

    /**
     * A name too wide for its tile is cut in two halves on two lines
     * (`Camera` → `Cam` / `era`) instead of being clipped to `Camer`. Returns
     * the lines to print; [cols] is how many monospace cells fit.
     */
    fun split(label: String, cols: Int): List<String> {
        if (cols <= 0 || label.length <= cols) return listOf(label)
        val words = label.split(' ')
        // Break at a space when that already fits: "Aura Pulse" → "Aura" / "Pulse".
        if (words.size > 1 && words.all { it.length <= cols }) {
            val lines = mutableListOf<String>()
            var line = ""
            for (w in words) {
                line = when {
                    line.isEmpty() -> w
                    line.length + 1 + w.length <= cols -> "$line $w"
                    else -> { lines += line; w }
                }
            }
            lines += line
            return lines
        }
        val half = (label.length + 1) / 2
        return if (half <= cols) {
            listOf(label.take(half).trimEnd(), label.drop(half).trimStart())
        } else {
            label.chunked(cols)
        }
    }
}
