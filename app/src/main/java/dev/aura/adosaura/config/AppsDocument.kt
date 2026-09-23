package dev.aura.adosaura.config

/**
 * Edits `apps.conf` the same way [LayoutDocument] edits the layout: splice the
 * one line that changed, leave every comment, blank line and untouched entry
 * exactly as the user wrote it.
 */
class AppsDocument(private val text: String) {

    private val lines = text.split('\n').toMutableList()

    /** Blank the entry's line — never delete it, so line numbers stay valid. */
    fun without(entry: AppEntry): String {
        val index = entry.line - 1
        if (entry.line > 0 && index < lines.size) lines[index] = ""
        return lines.joinToString("\n")
    }

    /**
     * Drop several entries at once and leave no holes behind.
     *
     * Unlike [without], which blanks one line so a caller part-way through a
     * splice keeps its other line numbers, this deletes the lines outright.
     * That is only safe because it works bottom-up: removing line N cannot
     * move any line before N, so the `line` numbers of everything still to be
     * removed stay true.
     *
     * Used when uninstalled apps take their lines with them.
     */
    fun withoutAll(entries: List<AppEntry>): String {
        entries
            .map { it.line - 1 }
            .filter { it in lines.indices }
            .distinct()
            .sortedDescending()
            .forEach { lines.removeAt(it) }
        return lines.joinToString("\n")
    }

    /** Rewrite one entry's line (rename, regroup). */
    fun withEntry(entry: AppEntry): String {
        val rendered = render(entry)
        val index = entry.line - 1
        if (entry.line > 0 && index < lines.size) {
            lines[index] = rendered
        } else {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
            lines.add(rendered)
        }
        return lines.joinToString("\n")
    }

    /**
     * Add a package at the end of the file, marked as auto-added so it is
     * obvious what the app did on its own. Does nothing when it is already
     * listed — a line the user deleted is a line they want gone.
     */
    fun appendIfMissing(entry: AppEntry): String {
        val already = lines.any { line ->
            val body = line.substringBefore('#').trim()
            body.isNotEmpty() && body.split('|').first().trim() == entry.pkg
        }
        if (already) return text

        if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
        lines.add("# added by adosaura on install")
        lines.add(render(entry))
        return lines.joinToString("\n")
    }

    private fun render(entry: AppEntry): String = buildString {
        append(entry.pkg.padEnd(40))
        append("| ")
        append(entry.label.padEnd(24))
        append("| ")
        append(entry.groups.joinToString(","))
    }
}
