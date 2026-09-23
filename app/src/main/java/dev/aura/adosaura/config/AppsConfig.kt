package dev.aura.adosaura.config

/**
 * Parsing for `/sdcard/adosaura/apps.conf` — the app drawer's contents.
 *
 * Pure Kotlin on purpose: no Android imports anywhere in this file, so the
 * format the launcher depends on is unit-tested on the JVM without a device.
 * See app/src/test/java/dev/aura/adosaura/config/AppsConfigTest.kt.
 *
 * Format, one app per line:
 *
 *     # comment
 *     <package> | <display name> | <group>[,<group>...]
 *
 * - the display name and the groups are optional; the name defaults to the
 *   package's last segment, the groups default to `all`
 * - `#` starts a comment anywhere on the line
 * - blank lines are ignored
 * - a duplicate package keeps the first entry and reports the later one
 * - a bad line is skipped and reported; it never aborts the parse
 */

data class AppEntry(
    val pkg: String,
    val label: String,
    val groups: List<String>,
    /** 1-based source line in apps.conf, or 0 for an entry created in memory. */
    val line: Int = 0,
)

/** A line the parser refused, kept so the UI can say `2 lines skipped`. */
data class ConfigProblem(
    val line: Int,
    val text: String,
    val reason: String,
)

data class AppsConfig(
    val entries: List<AppEntry> = emptyList(),
    val problems: List<ConfigProblem> = emptyList(),
) {
    fun inGroup(group: String): List<AppEntry> = entries.filter { group in it.groups }

    /** Every group name mentioned in the file, for the tile picker. */
    val groupNames: List<String>
        get() = entries.flatMap { it.groups }.distinct().sorted()
}

object AppsConfigParser {

    const val DEFAULT_GROUP = "all"

    fun parse(text: String): AppsConfig {
        val entries = mutableListOf<AppEntry>()
        val problems = mutableListOf<ConfigProblem>()
        val seen = mutableSetOf<String>()

        text.split('\n').forEachIndexed { index, raw ->
            val number = index + 1
            val line = raw.removeSuffix("\r").substringBefore('#').trim()
            if (line.isEmpty()) return@forEachIndexed

            val fields = line.split('|').map { it.trim() }
            val pkg = fields.getOrNull(0).orEmpty()

            when {
                pkg.isEmpty() -> {
                    problems += ConfigProblem(number, line, "no package name")
                    return@forEachIndexed
                }

                !isPackageName(pkg) -> {
                    problems += ConfigProblem(number, line, "not a package name: $pkg")
                    return@forEachIndexed
                }

                !seen.add(pkg) -> {
                    problems += ConfigProblem(number, line, "duplicate $pkg — first wins")
                    return@forEachIndexed
                }
            }

            val label = fields.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: labelFrom(pkg)
            val groups = fields.getOrNull(2).orEmpty()
                .split(',', ' ')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .ifEmpty { listOf(DEFAULT_GROUP) }

            entries += AppEntry(pkg, label, groups, number)
        }

        return AppsConfig(entries, problems)
    }

    /** `com.example.my_thing` -> `My thing`. */
    fun labelFrom(pkg: String): String =
        pkg.substringAfterLast('.')
            .replace('_', ' ')
            .replaceFirstChar { it.uppercaseChar() }

    /** Deliberately loose: at least one dot, no whitespace, no path separators. */
    fun isPackageName(value: String): Boolean =
        value.contains('.') &&
            value.none { it.isWhitespace() || it == '/' || it == '\\' || it == ':' }

    /** Used to write a generated file on first run, and by the in-app editor. */
    fun format(entries: List<AppEntry>): String = buildString {
        appendLine("# adosaura — app drawer. Hot-reloaded; edit freely.")
        appendLine("# <package> | <display name> | <group>[,<group>]")
        appendLine("# This file was generated because none existed: it lists every")
        appendLine("# launchable app. Delete lines to hide apps, reorder to reorder.")
        appendLine()
        entries.forEach { entry ->
            append(entry.pkg.padEnd(40))
            append("| ")
            append(entry.label.padEnd(24))
            append("| ")
            append(entry.groups.joinToString(","))
            appendLine()
        }
    }
}

/** Small subsequence matcher for the drawer's search box. Pure, so it is tested. */
object Fuzzy {

    /**
     * Returns a score where higher is better, or null when [query] does not
     * appear in [text] as a subsequence. Case-insensitive. Consecutive hits and
     * a match at the start of a word both score higher, so typing `chr` puts
     * Chrome above something that merely contains c-h-r far apart.
     */
    fun score(query: String, text: String): Int? {
        if (query.isEmpty()) return 0
        val q = query.lowercase()
        val t = text.lowercase()

        var qi = 0
        var score = 0
        var lastHit = -2

        for (ti in t.indices) {
            if (qi >= q.length) break
            if (t[ti] != q[qi]) continue

            score += 1
            if (ti == lastHit + 1) score += 3                    // consecutive
            if (ti == 0 || t[ti - 1] == ' ') score += 5           // start of a word

            lastHit = ti
            qi++
        }

        return if (qi == q.length) score else null
    }

    /**
     * Filter and rank in one pass. Label matches beat package matches; ties
     * break alphabetically so the list never jitters between frames.
     */
    fun rank(query: String, entries: List<AppEntry>): List<AppEntry> {
        if (query.isBlank()) return entries
        return entries
            .mapNotNull { entry ->
                // A hit in the display name is what the user meant; a hit in the
                // package name still counts, just behind it.
                val byLabel = score(query, entry.label)?.plus(10)
                val byPackage = score(query, entry.pkg)
                val best = listOfNotNull(byLabel, byPackage).maxOrNull()
                best?.let { entry to it }
            }
            .sortedWith(compareByDescending<Pair<AppEntry, Int>> { it.second }.thenBy { it.first.label })
            .map { it.first }
    }
}
