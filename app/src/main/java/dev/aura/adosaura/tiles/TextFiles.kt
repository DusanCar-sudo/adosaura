package dev.aura.adosaura.tiles

import java.io.File
import java.io.RandomAccessFile

/**
 * The pure half of the `file:` and `note:` tiles: where a tile's file lives,
 * how much of it is read, what script output looks like once its colour codes
 * are read, and how a checklist line is ticked.
 *
 * No Android imports — tested on the JVM in TextFilesTest.
 */
object TextFiles {

    /** A `file:` tile shows at most this much: the END of a bigger file, like `tail`. */
    const val FILE_BYTES = 64 * 1024

    /** A note bigger than this is shown read-only rather than risk a partial rewrite. */
    const val NOTE_BYTES = 256 * 1024

    /**
     * `status.txt` -> `<base>/status.txt`; an absolute path is kept as it is.
     * [extension] is added when the name has none, so `note:todo` is `todo.md`.
     * Null when there is no name at all.
     */
    fun resolve(base: File, arg: String?, extension: String? = null): File? {
        val name = arg?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val withExt = if (extension != null && '.' !in name.substringAfterLast('/')) "$name.$extension" else name
        return if (withExt.startsWith("/")) File(withExt) else File(base, withExt)
    }

    /** `/sdcard/adosaura/status.txt` -> `status`: what the tile's title strip says. */
    fun title(arg: String?, fallback: String): String =
        arg?.trim()?.substringAfterLast('/')?.substringBeforeLast('.')?.takeIf { it.isNotEmpty() } ?: fallback

    /**
     * Read [file], keeping only its last [maxBytes]. A cut file starts at its
     * first whole line, so a half character or half a line is never shown.
     */
    fun read(file: File, maxBytes: Int): Snapshot = runCatching {
        if (!file.isFile) return Snapshot(null)
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            if (length <= maxBytes) {
                val bytes = ByteArray(length.toInt())
                raf.readFully(bytes)
                Snapshot(String(bytes, Charsets.UTF_8))
            } else {
                raf.seek(length - maxBytes)
                val bytes = ByteArray(maxBytes)
                raf.readFully(bytes)
                val text = String(bytes, Charsets.UTF_8)
                Snapshot(text.substringAfter('\n', text), truncated = true)
            }
        }
    }.getOrDefault(Snapshot(null))

    /** What a tile last read. [text] is null when the file does not exist or cannot be read. */
    data class Snapshot(val text: String?, val truncated: Boolean = false)
}

// ---------------------------------------------------------------------- ansi

/** The theme colours a colour code can turn into; the tile resolves them. */
enum class Tone { DIM, RED, GREEN, YELLOW, BLUE, ACCENT, CYAN, BRIGHT }

/** One stretch of text in one style. Null tone and rgb = the tile's text colour. */
data class Run(val text: String, val tone: Tone? = null, val rgb: Int? = null, val bold: Boolean = false)

/**
 * Script output with its colour codes read rather than printed: `\e[32mok`
 * comes out as `ok` in the theme's green, so a script can colour its own tile
 * and the colours still follow a theme change.
 *
 * Only colour and bold are kept. Cursor moves, backgrounds and other control
 * characters are dropped; tabs become spaces to the next multiple of 8 so
 * columns still line up in the monospace font.
 */
object Ansi {

    private val ESCAPE = Regex("\u001B\\[([0-9;?]*)([A-Za-z])|\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)?|\u001B")
    private const val TAB = 8

    private val BASIC = listOf(Tone.DIM, Tone.RED, Tone.GREEN, Tone.YELLOW, Tone.BLUE, Tone.ACCENT, Tone.CYAN, null)
    private val BRIGHT = listOf(Tone.DIM, Tone.RED, Tone.GREEN, Tone.YELLOW, Tone.BLUE, Tone.ACCENT, Tone.CYAN, Tone.BRIGHT)

    fun parse(text: String): List<Run> {
        val runs = mutableListOf<Run>()
        var style = Run("")
        var column = 0
        val pending = StringBuilder()

        fun flush() {
            if (pending.isEmpty()) return
            val last = runs.lastOrNull()
            if (last != null && last.copy(text = "") == style) {
                runs[runs.lastIndex] = last.copy(text = last.text + pending)
            } else {
                runs += style.copy(text = pending.toString())
            }
            pending.clear()
        }

        fun plain(chunk: String) {
            for (c in chunk) when {
                c == '\n' -> { pending.append(c); column = 0 }
                c == '\t' -> repeat(TAB - column % TAB) { pending.append(' '); column++ }
                c < ' ' || c == '\u007F' -> Unit
                else -> { pending.append(c); column++ }
            }
        }

        var at = 0
        for (match in ESCAPE.findAll(text)) {
            plain(text.substring(at, match.range.first))
            at = match.range.last + 1
            if (match.groupValues[2] != "m") continue
            flush()
            style = sgr(style, match.groupValues[1])
        }
        plain(text.substring(at))
        flush()
        return runs
    }

    /** Apply one `\e[...m` sequence to [style]. */
    private fun sgr(style: Run, params: String): Run {
        var s = style
        val codes = params.split(';').map { it.toIntOrNull() ?: 0 }
        var i = 0
        while (i < codes.size) {
            when (val code = codes[i]) {
                0 -> s = Run("")
                1 -> s = s.copy(bold = true)
                2 -> s = s.copy(tone = Tone.DIM, rgb = null)
                22 -> s = s.copy(bold = false)
                in 30..37 -> s = s.copy(tone = BASIC[code - 30], rgb = null)
                in 90..97 -> s = s.copy(tone = BRIGHT[code - 90], rgb = null)
                39 -> s = s.copy(tone = null, rgb = null)
                38 -> {
                    // 38;5;n picks from 256 colours, 38;2;r;g;b is true colour.
                    when (codes.getOrNull(i + 1)) {
                        5 -> {
                            val n = codes.getOrNull(i + 2) ?: 0
                            if (n < 8) s = s.copy(tone = BASIC[n], rgb = null)
                            else if (n < 16) s = s.copy(tone = BRIGHT[n - 8], rgb = null)
                            i += 2
                        }
                        2 -> {
                            val (r, g, b) = List(3) { (codes.getOrNull(i + 2 + it) ?: 0).coerceIn(0, 255) }
                            s = s.copy(tone = null, rgb = (r shl 16) or (g shl 8) or b)
                            i += 4
                        }
                    }
                }
                // Backgrounds are not drawn, but their arguments must be skipped.
                48 -> i += when (codes.getOrNull(i + 1)) { 5 -> 2; 2 -> 4; else -> 0 }
            }
            i++
        }
        return s
    }
}

// --------------------------------------------------------------------- notes

/** One line of a note, by its 0-based line number in the file. */
sealed interface NoteLine {
    val index: Int

    /** `- [ ] buy milk` / `- [x] done`. [raw] is the line exactly as read. */
    data class Task(override val index: Int, val text: String, val done: Boolean, val raw: String) : NoteLine

    /** `# groceries` */
    data class Heading(override val index: Int, val text: String) : NoteLine

    /** Anything else, shown as written. Empty for a blank line. */
    data class Plain(override val index: Int, val text: String) : NoteLine
}

/**
 * A checklist in Markdown task syntax, the same file Obsidian or any editor
 * opens. Every edit touches only the lines it must: ticking rewrites one
 * character, adding appends one line, and everything else in the file keeps
 * its exact bytes.
 */
object Notes {

    private val TASK = Regex("^(\\s*[-*+]\\s+\\[)([ xX])(\\].*)$")
    private val TASK_TEXT = Regex("^\\s*[-*+]\\s+\\[[ xX]\\]\\s?")
    private val HEADING = Regex("^#{1,6}\\s+(.*)$")

    fun parse(text: String): List<NoteLine> {
        if (text.isEmpty()) return emptyList()
        val lines = text.split('\n')
        // A file that ends in a newline does not have an extra blank line.
        val count = if (lines.size > 1 && lines.last().isEmpty()) lines.size - 1 else lines.size
        return (0 until count).map { index ->
            val raw = lines[index]
            val line = raw.removeSuffix("\r")
            val task = TASK.matchEntire(line)
            val heading = HEADING.matchEntire(line)
            when {
                task != null -> NoteLine.Task(
                    index,
                    line.replaceFirst(TASK_TEXT, "").trim(),
                    task.groupValues[2] != " ",
                    raw,
                )
                heading != null -> NoteLine.Heading(index, heading.groupValues[1].trim())
                else -> NoteLine.Plain(index, line.trimEnd())
            }
        }
    }

    /**
     * Tick or untick the task on line [index]. Null when that line is no
     * longer [raw] — the file changed since it was drawn, so the tap is
     * dropped rather than ticking the wrong item.
     */
    fun toggle(text: String, index: Int, raw: String): String? {
        val lines = text.split('\n').toMutableList()
        if (lines.getOrNull(index) != raw) return null
        val match = TASK.matchEntire(raw.removeSuffix("\r")) ?: return null
        val mark = if (match.groupValues[2] == " ") "x" else " "
        lines[index] = match.groupValues[1] + mark + match.groupValues[3] + (if (raw.endsWith("\r")) "\r" else "")
        return lines.joinToString("\n")
    }

    /** Append `- [ ] item` as the last line. Null for an empty item. */
    fun append(text: String, item: String): String? {
        val clean = item.replace(Regex("[\\r\\n]+"), " ").trim()
        if (clean.isEmpty()) return null
        val prefix = if (text.isEmpty() || text.endsWith("\n")) text else "$text\n"
        return "$prefix- [ ] $clean\n"
    }

    /** Drop every ticked task; everything else stays byte for byte. */
    fun clearDone(text: String): String =
        text.split('\n')
            .filterNot { line -> TASK.matchEntire(line.removeSuffix("\r"))?.groupValues?.get(2)?.let { it != " " } == true }
            .joinToString("\n")
}
