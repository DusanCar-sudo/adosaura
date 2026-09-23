package dev.aura.adosaura.config

/**
 * Parsing for `/sdcard/adosaura/layout.conf` — the tiles on each page.
 *
 * Pure Kotlin, no Android imports: tested on the JVM in LayoutConfigTest.
 *
 * A page is a stack of ROWS. Tiles sharing a row sit side by side, left to
 * right in file order. This is the sway model, and it is what makes "put a
 * tile to the left of that one" possible at all — the older top-row-plus-one-
 * column shape could only ever stack downwards.
 *
 * Format, one tile per line:
 *
 *     # <page> <row> <type>[:<arg>] <w> [h]
 *     0  0  stats        1  1
 *     0  0  calendar     1  1
 *     0  1  apps:pinned  1  2
 *     0  1  clockcal     1  2
 *
 * - `page`  0-based page; pages exist implicitly up to the highest one
 * - `row`   0-based band from the top. Same row = side by side.
 *           Also accepts the older `top` / `main` words (see below).
 * - `type`  one of the tile ids, optionally `:arg` — e.g. `apps:social`
 * - `w`     the tile's share of its row's width
 * - `h`     the row's share of the page height. All tiles in a row share it;
 *           the first one written wins and a mismatch is reported.
 *
 * Weights are relative and self-normalising, so resizing one tile shrinks its
 * neighbours for free — which is exactly what dragging an edge should do.
 *
 * ## The older syntax still parses
 *
 *     0 top  clock        1
 *     0 main clockcal     2
 *
 * `top` means row 0 (width share = the number). `main` means the rows below,
 * numbered in file order, where the number is the HEIGHT share — which is what
 * "weight 2 makes the middle tile tall" always meant. Files written in the old
 * grammar keep working untouched; only lines the app rewrites come out in the
 * new one.
 *
 * A bad line is skipped and reported, never fatal — a broken layout file must
 * not be able to brick the home screen.
 */

data class TileSpec(
    val page: Int,
    /** 0-based band from the top. Tiles on the same row sit side by side. */
    val row: Int,
    val type: String,
    val arg: String? = null,
    /** Share of the row's width. */
    val w: Int = 1,
    /** Share of the page's height, for the whole row. */
    val h: Int = 1,
    /** 1-based source line in layout.conf, or 0 for a tile created in memory. */
    val line: Int = 0,
    /** Pane colour: a palette key (`red`, `accent`) or six hex digits. Null = theme. */
    val bg: String? = null,
    /** Text colour, same forms as [bg]. */
    val fg: String? = null,
    /** Text size in percent of normal, 100 = unchanged. */
    val size: Int = 100,
)

data class LayoutConfig(
    val tiles: List<TileSpec> = emptyList(),
    val problems: List<ConfigProblem> = emptyList(),
) {
    /** Pages are implicit: one beyond the highest page index in the file. */
    val pageCount: Int get() = (tiles.maxOfOrNull { it.page } ?: 0) + 1

    fun onPage(page: Int): List<TileSpec> = tiles.filter { it.page == page }

    /** Rows of one page, keyed by row index, tiles in file order. */
    fun rows(page: Int): Map<Int, List<TileSpec>> =
        onPage(page).groupBy { it.row }.toSortedMap()
}

object LayoutConfigParser {

    private val WHITESPACE = Regex("\\s+")
    private const val MIN_SHARE = 1
    private const val MAX_SHARE = 12

    fun parse(text: String): LayoutConfig {
        val tiles = mutableListOf<TileSpec>()
        val problems = mutableListOf<ConfigProblem>()

        // `main` rows are numbered in file order, per page.
        val nextMainRow = mutableMapOf<Int, Int>()

        text.split('\n').forEachIndexed { index, raw ->
            val number = index + 1
            val line = raw.removeSuffix("\r").substringBefore('#').trim()
            if (line.isEmpty()) return@forEachIndexed

            fun reject(reason: String) {
                problems += ConfigProblem(number, line, reason)
            }

            // `key=value` style tokens may follow the numbers, in any order.
            val allParts = line.split(WHITESPACE)
            val parts = allParts.filter { '=' !in it }
            val style = allParts.filter { '=' in it }.associate { it.substringBefore('=').lowercase() to it.substringAfter('=') }
            var bg: String? = null
            var fg: String? = null
            var size = 100
            style.forEach { (key, value) ->
                when (key) {
                    "bg" -> bg = TileStyle.colour(value) ?: run { reject("bg: unknown colour '$value'"); null }
                    "fg" -> fg = TileStyle.colour(value) ?: run { reject("fg: unknown colour '$value'"); null }
                    "size" -> size = value.toIntOrNull()?.coerceIn(TileStyle.MIN_SIZE, TileStyle.MAX_SIZE)
                        ?: run { reject("size must be a percent like 120"); 100 }
                    else -> reject("unknown setting '$key'")
                }
            }
            if (parts.size < 3) {
                reject("needs at least: <page> <row> <type>")
                return@forEachIndexed
            }

            val page = parts[0].toIntOrNull()
            if (page == null || page < 0) {
                reject("page must be a number >= 0")
                return@forEachIndexed
            }

            val (type, arg) = splitType(parts[2])
            if (type.isEmpty()) {
                reject("empty tile type")
                return@forEachIndexed
            }

            val first = parts.getOrNull(3)?.toIntOrNull()
            if (parts.size >= 4 && (first == null || first < MIN_SHARE)) {
                reject("width share must be a number >= $MIN_SHARE")
                return@forEachIndexed
            }
            val share1 = first ?: 1

            val second = parts.getOrNull(4)?.toIntOrNull()
            if (parts.size >= 5 && (second == null || second < MIN_SHARE)) {
                reject("height share must be a number >= $MIN_SHARE")
                return@forEachIndexed
            }
            val share2 = second ?: 1

            val row: Int
            val w: Int
            val h: Int
            when (val key = parts[1].lowercase()) {
                // old grammar: `top` is row 0, and its number is a width share
                "top" -> {
                    row = 0
                    w = share1 * share2
                    h = 1
                }
                // old grammar: `main` stacks one tile per row downwards, and
                // its number is the height share ("2 makes the tile tall")
                "main" -> {
                    val at = nextMainRow.getOrDefault(page, 1)
                    nextMainRow[page] = at + 1
                    row = at
                    w = 1
                    h = share1 * share2
                }
                else -> {
                    val parsedRow = key.toIntOrNull()
                    if (parsedRow == null || parsedRow < 0) {
                        reject("row must be a number >= 0, or 'top' / 'main'")
                        return@forEachIndexed
                    }
                    row = parsedRow
                    w = share1
                    h = share2
                }
            }

            tiles += TileSpec(page, row, type, arg, w, h, number, bg, fg, size)
        }

        // A row has one height. Say so rather than silently picking one.
        tiles.groupBy { it.page to it.row }.forEach { (_, inRow) ->
            val distinct = inRow.map { it.h }.distinct()
            if (distinct.size > 1) {
                val first = inRow.minBy { it.line }
                problems += ConfigProblem(
                    first.line,
                    "row ${first.row} of page ${first.page}",
                    "tiles in one row share a height; using ${first.h}",
                )
            }
        }

        return LayoutConfig(tiles, problems)
    }

    /** `apps:social` -> ("apps", "social"); `clock` -> ("clock", null). */
    fun splitType(token: String): Pair<String, String?> {
        val at = token.indexOf(':')
        return if (at < 0) {
            token to null
        } else {
            token.substring(0, at) to token.substring(at + 1)
        }
    }

    fun render(spec: TileSpec): String = buildString {
        append(spec.page).append(' ')
        append(spec.row).append(' ')
        append(spec.type)
        spec.arg?.takeIf { it.isNotEmpty() }?.let { append(':').append(it) }
        append(' ').append(spec.w.coerceIn(MIN_SHARE, MAX_SHARE))
        append(' ').append(spec.h.coerceIn(MIN_SHARE, MAX_SHARE))
        spec.bg?.let { append(" bg=").append(it) }
        spec.fg?.let { append(" fg=").append(it) }
        if (spec.size != 100) append(" size=").append(spec.size)
    }

    fun header(): String = buildString {
        appendLine("# adosaura — tile layout. Hot-reloaded; edit freely.")
        appendLine("# <page> <row> <type>[:<arg>] <w> [h]")
        appendLine("#   row  0-based band from the top. Tiles on the SAME row sit")
        appendLine("#        side by side, left to right in file order.")
        appendLine("#   w    the tile's share of its row's width")
        appendLine("#   h    the row's share of the page height (shared by the row)")
        appendLine("# Shares are relative, so making one tile wider shrinks its")
        appendLine("# neighbours. Drag a tile's edge to resize; the app rewrites")
        appendLine("# just that line.")
        appendLine("# types: clock, calendar, clockcal, apps:<group>, stats, perf, weather, analog, battery, matrix")
        appendLine("# optional style: bg=<colour> fg=<colour> size=<percent>")
        appendLine("#   colour: a theme name (red green yellow blue cyan orange accent fg dim)")
        appendLine("#   or six hex digits without # (# starts a comment): fg=ffd466")
    }
}

/**
 * Edits a layout file while preserving everything the user wrote: comments,
 * blank lines and unrelated tiles keep their exact text and line numbers.
 *
 * Blanking a line instead of deleting it is what makes that possible — every
 * other tile's recorded line number stays valid.
 */
class LayoutDocument(private val text: String) {

    private val lines = text.split('\n').toMutableList()

    /**
     * Update the tile's own line if it has one, otherwise append a new line at
     * the end of the file. Returns the new file contents.
     */
    fun withTile(spec: TileSpec): String {
        val rendered = LayoutConfigParser.render(spec)
        val index = spec.line - 1
        if (spec.line > 0 && index < lines.size) {
            lines[index] = rendered
        } else {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
            lines.add(rendered)
        }
        return lines.joinToString("\n")
    }

    /** Remove a tile by blanking its line, so line numbers survive. */
    fun withoutTile(spec: TileSpec): String {
        val index = spec.line - 1
        if (spec.line > 0 && index < lines.size) lines[index] = ""
        return lines.joinToString("\n")
    }
}
