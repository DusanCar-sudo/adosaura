package dev.aura.adosaura.tiles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.aura.adosaura.config.ConfigFiles
import dev.aura.adosaura.config.DirWatch
import dev.aura.adosaura.theme.AdosauraMono
import dev.aura.adosaura.theme.Metrics
import dev.aura.adosaura.theme.Palette
import dev.aura.adosaura.theme.ThemePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ---------------------------------------------------------------------- file

/**
 * `file:<path>` — any text file, redrawn whenever it changes. A relative path
 * is under /sdcard/adosaura/. Whatever writes the file is the data source:
 * a Termux cron job, a Tasker task, `adb push` from the laptop. Colour codes
 * in the text come out in the theme's colours.
 */
@Composable
fun FileTile(arg: String?) {
    val file = remember(arg) { TextFiles.resolve(ConfigFiles.dir, arg) }
    if (file == null) {
        Hint("file:<path>\ne.g. file:status.txt")
        return
    }
    val snapshot = rememberWatchedText(file, TextFiles.FILE_BYTES).value ?: return
    val text = snapshot.text ?: run {
        Hint("nothing yet — write\n${file.path}")
        return
    }

    val p = Palette.tile
    val runs = remember(text) { Ansi.parse(text.trimEnd('\n')) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (snapshot.truncated) Hint("… the last ${TextFiles.FILE_BYTES / 1024}k")
        Text(
            text = buildAnnotatedString {
                runs.forEach { run ->
                    withStyle(
                        SpanStyle(
                            color = run.rgb?.let { Color(0xFF000000 or it.toLong()) } ?: toneColour(p, run.tone),
                            fontWeight = if (run.bold) FontWeight.Bold else null,
                        ),
                    ) { append(run.text) }
                }
            },
            color = p.fg,
            fontSize = Metrics.smallSize,
            fontFamily = AdosauraMono,
        )
    }
}

private fun toneColour(p: ThemePalette, tone: Tone?): Color = when (tone) {
    null -> p.fg
    Tone.DIM -> p.muted
    Tone.RED -> p.red
    Tone.GREEN -> p.green
    Tone.YELLOW -> p.yellow
    Tone.BLUE -> p.blue
    Tone.ACCENT -> p.accentBright
    Tone.CYAN -> p.cyan
    Tone.BRIGHT -> p.fgBright
}

// ---------------------------------------------------------------------- note

/**
 * `note:<name>` — a Markdown checklist, `/sdcard/adosaura/<name>.md`. Tap an
 * item to tick it, `+ add` to append one, `clear done` to drop the ticked
 * ones. The same file opens in any editor, and an edit made there shows up
 * here live.
 */
@Composable
fun NoteTile(arg: String?) {
    val file = remember(arg) { TextFiles.resolve(ConfigFiles.dir, arg?.takeIf { it.isNotBlank() } ?: DEFAULT_NOTE, extension = "md")!! }
    val state = rememberWatchedText(file, TextFiles.NOTE_BYTES)
    val snapshot = state.value ?: return
    val scope = rememberCoroutineScope()
    val p = Palette.tile

    if (snapshot.truncated) {
        Hint("too big to edit here\n${file.path}")
        return
    }
    val lines = remember(snapshot.text) { Notes.parse(snapshot.text.orEmpty()) }

    fun edit(change: (String) -> String?) {
        scope.launch { writeNote(file, state, change) }
    }

    // The list scrolls; the keys under it stay put, so `+ add` is always one tap away.
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            lines.forEach { line ->
                when (line) {
                    is NoteLine.Task -> Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { edit { Notes.toggle(it, line.index, line.raw) } }
                            .padding(vertical = 3.dp),
                    ) {
                        val colour = if (line.done) p.dim else p.fg
                        // The mark and the text side by side, so a long item wraps
                        // under its own text rather than under the mark.
                        Text(if (line.done) "[x] " else "[ ] ", color = colour, fontSize = Metrics.bodySize, fontFamily = AdosauraMono)
                        Text(
                            text = line.text,
                            color = colour,
                            fontSize = Metrics.bodySize,
                            fontFamily = AdosauraMono,
                            textDecoration = if (line.done) TextDecoration.LineThrough else null,
                        )
                    }
                    is NoteLine.Heading -> Text(
                        text = line.text,
                        color = p.accent,
                        fontSize = Metrics.smallSize,
                        fontWeight = FontWeight.Bold,
                        fontFamily = AdosauraMono,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                    )
                    is NoteLine.Plain ->
                        if (line.text.isBlank()) Spacer(Modifier.height(6.dp))
                        else Text(line.text, color = p.muted, fontSize = Metrics.smallSize, fontFamily = AdosauraMono)
                }
            }
        }

        NoteKeys(
            anyDone = lines.any { it is NoteLine.Task && it.done },
            onAdd = { item -> edit { Notes.append(it, item) } },
            onClearDone = { edit { Notes.clearDone(it) } },
        )
    }
}

/** `+ add` and `clear done` as text keys; `+ add` turns into the input line. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoteKeys(anyDone: Boolean, onAdd: (String) -> Unit, onClearDone: () -> Unit) {
    val p = Palette.tile
    var adding by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }

    if (!adding) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Key("+ add") { adding = true }
            if (anyDone) Key("clear done", onClearDone)
        }
        return
    }

    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // Back hides the keyboard but leaves the field focused; an empty line
    // with no keyboard is just clutter, so close it.
    val keyboard = WindowInsets.isImeVisible
    var keyboardSeen by remember { mutableStateOf(false) }
    LaunchedEffect(keyboard) {
        if (keyboard) keyboardSeen = true
        else if (keyboardSeen && draft.isBlank()) adding = false
    }
    BasicTextField(
        value = draft,
        onValueChange = { draft = it },
        singleLine = true,
        textStyle = TextStyle(color = p.fgBright, fontSize = Metrics.bodySize, fontFamily = AdosauraMono),
        cursorBrush = SolidColor(p.accent),
        // Enter adds the item and keeps the line open for the next one;
        // Enter on an empty line closes it.
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            if (draft.isBlank()) adding = false else onAdd(draft)
            draft = ""
        }),
        decorationBox = { inner ->
            Row {
                Text("> ", color = p.accent, fontSize = Metrics.bodySize, fontFamily = AdosauraMono)
                inner()
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .focusRequester(focus)
            .onFocusChanged {
                if (focused && !it.isFocused && draft.isBlank()) adding = false
                focused = it.isFocused
            },
    )
}

@Composable
private fun Key(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = Palette.tile.dim,
        fontSize = Metrics.smallSize,
        fontFamily = AdosauraMono,
        modifier = Modifier.clickable(onClick = onClick).padding(end = 16.dp, top = 4.dp, bottom = 4.dp),
    )
}

/**
 * Read the file fresh, apply [change], write it back. Reading again rather
 * than trusting what is on screen means an edit made in another editor a
 * moment ago is kept, not overwritten.
 */
private suspend fun writeNote(
    file: File,
    state: MutableState<TextFiles.Snapshot?>,
    change: (String) -> String?,
) {
    val written = withContext(Dispatchers.IO) {
        val now = TextFiles.read(file, TextFiles.NOTE_BYTES)
        if (now.truncated) return@withContext null
        val next = change(now.text.orEmpty()) ?: return@withContext null
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(next)
            next
        }.getOrNull()
    }
    // Draw the edit now rather than waiting for the watcher to see it.
    if (written != null) state.value = TextFiles.Snapshot(written)
}

private const val DEFAULT_NOTE = "todo"

// -------------------------------------------------------------------- shared

@Composable
private fun Hint(text: String) {
    Text(text, color = Palette.tile.dim, fontSize = Metrics.smallSize, fontFamily = AdosauraMono)
}

/**
 * The file's text, read again whenever it changes and whenever the launcher
 * comes back on screen. The watch can be dropped silently after doze (see
 * ConfigWatcher); the re-read on return covers that.
 */
@Composable
private fun rememberWatchedText(file: File, maxBytes: Int): MutableState<TextFiles.Snapshot?> {
    val state = remember(file) { mutableStateOf<TextFiles.Snapshot?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(file) {
        val changed = Channel<Unit>(Channel.CONFLATED)
        val watch = file.parentFile?.let { dir ->
            DirWatch.add(dir) { path -> if (path == null || path == file.name) changed.trySend(Unit) }
        }
        try {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                changed.trySend(Unit)
                for (unit in changed) {
                    // A write is often several events; let it finish, then read once.
                    delay(WATCH_DEBOUNCE_MS)
                    state.value = withContext(Dispatchers.IO) { TextFiles.read(file, maxBytes) }
                }
            }
        } finally {
            watch?.close()
        }
    }
    return state
}

private const val WATCH_DEBOUNCE_MS = 150L
