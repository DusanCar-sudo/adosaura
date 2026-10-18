package dev.aura.adosaura.clean

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import dev.aura.adosaura.theme.AdosauraMono
import dev.aura.adosaura.theme.Metrics
import dev.aura.adosaura.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Rows shown under each kind before the rest fold into `+N more`. */
private const val SHOWN = 3

/**
 * Scan, tick, clean, then measure: the deep clean as one text pane.
 *
 * The files go first, then the app caches, then a fresh scan. What it
 * reports as freed is the real change in free space, not the sum of what it
 * meant to delete.
 */
@Composable
fun DeepCleanScreen(
    labels: Map<String, String>,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val cleaner = remember { DeepCleaner(context) }
    val scope = rememberCoroutineScope()

    var scan by remember { mutableStateOf<Scan?>(null) }
    var ticked by remember { mutableStateOf(Kind.entries.filter { it.ticked }.toSet()) }
    /** Non-null while working: what it is doing right now. */
    var busy by remember { mutableStateOf<String?>("scanning") }
    var result by remember { mutableStateOf<String?>(null) }
    var freeBefore by remember { mutableLongStateOf(0L) }
    /** Apps sized so far while the per-app cache list fills in, out of [apps]. */
    var sized by remember { mutableIntStateOf(0) }
    var apps by remember { mutableIntStateOf(0) }

    BackHandler { onClose() }

    fun rescan(after: (Scan) -> Unit = {}) {
        scope.launch {
            val fresh = withContext(Dispatchers.IO) { cleaner.scan { busy = "scanning $it" } }
            scan = fresh
            busy = null
            after(fresh)
            if (!fresh.cacheSizes) return@launch
            // The per-app list comes after the total, without holding up the clean key.
            sized = 0
            apps = 0
            val list = withContext(Dispatchers.IO) {
                cleaner.appCaches(labels) { done, of -> sized = done; apps = of }
            }
            // A newer scan, after a clean or a rescan, has its own list coming.
            if (scan === fresh) scan = fresh.withAppCaches(list)
        }
    }

    /** Last step of a clean: measure, and say what changed and why. */
    fun measure(cacheNote: String?) {
        busy = "measuring"
        rescan { fresh ->
            val freed = (fresh.free - freeBefore).coerceAtLeast(0)
            result = buildString {
                append("freed ${Junk.size(freed)} · free ${Junk.size(freeBefore)} → ${Junk.size(fresh.free)}")
                cacheNote?.let { append(" · ").append(it) }
            }
        }
    }

    fun clean() {
        val s = scan ?: return
        if (busy != null || ticked.isEmpty()) return
        result = null
        freeBefore = cleaner.free()
        busy = "deleting files"
        scope.launch {
            withContext(Dispatchers.IO) { cleaner.delete(s.items.filter { it.kind in ticked }) }
            var note: String? = null
            if (Kind.CACHE in ticked) {
                busy = "clearing app caches"
                if (!withContext(Dispatchers.IO) { cleaner.clearCaches() }) note = "the system kept the caches"
            }
            measure(note)
        }
    }

    LaunchedEffect(Unit) { rescan() }

    val p = Palette.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(p.bg.copy(alpha = 0.97f))
            .border(Metrics.border, p.border)
            // Swallow taps so nothing reaches the home screen underneath.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Column(Modifier.fillMaxSize().padding(vertical = Metrics.padV)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Metrics.padH),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Line("─ deep clean ─", p.dim, Metrics.titleSize, bold = true, modifier = Modifier.weight(1f))
                Line("esc", p.dim, Metrics.titleSize, modifier = Modifier.clickable { onClose() })
            }

            val s = scan
            Line(
                text = if (s == null) " " else "free ${Junk.size(s.free)} of ${Junk.size(s.total)}",
                color = p.muted,
                size = Metrics.smallSize,
                modifier = Modifier.padding(horizontal = Metrics.padH, vertical = 4.dp),
            )
            if (!cleaner.filesAccess()) {
                Warning("all files access is off: run scripts/adb-grants.sh")
            } else if (s != null && !s.cacheSizes) {
                Warning("cache sizes need usage access; clearing still works")
            }

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 6.dp),
            ) {
                if (s != null) {
                    Kind.entries.forEach { kind ->
                        KindRows(
                            kind = kind,
                            scan = s,
                            sizing = if (kind == Kind.CACHE && !s.perApp && s.cacheSizes) "sizing each app $sized/$apps…" else null,
                            ticked = kind in ticked,
                            enabled = busy == null,
                            onToggle = { ticked = if (kind in ticked) ticked - kind else ticked + kind },
                        )
                    }
                }
            }

            result?.let {
                Line(it, p.green, Metrics.smallSize, modifier = Modifier.padding(horizontal = Metrics.padH, vertical = 4.dp))
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = Metrics.padH, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val text = busy?.let { "$it…" }
                if (text != null) {
                    Line(text, p.dim, Metrics.bodySize, modifier = Modifier.weight(1f))
                } else {
                    val sizes = s?.cacheSizes != false || Kind.CACHE !in ticked
                    val bytes = s?.bytes(ticked) ?: 0
                    // Unknown cache sizes may still be worth a try; a known nothing is not.
                    val ready = s != null && ticked.isNotEmpty() && (bytes > 0 || !sizes)
                    Line(
                        text = if (sizes) "[ clean ${Junk.size(bytes)} ]" else "[ clean ]",
                        color = if (ready) p.yellow else p.dim,
                        size = Metrics.bodySize,
                        bold = true,
                        modifier = Modifier.clickable(enabled = ready) { clean() },
                    )
                    Spacer(Modifier.weight(1f))
                    Line(
                        "rescan",
                        p.dim,
                        Metrics.bodySize,
                        modifier = Modifier.clickable {
                            busy = "scanning"
                            rescan()
                        },
                    )
                }
            }
        }
    }
}

/** One kind: a tickable header, what it is, and its biggest items. */
@Composable
private fun KindRows(kind: Kind, scan: Scan, sizing: String?, ticked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val p = Palette.current
    val items = scan.of(kind)
    val unknown = kind == Kind.CACHE && !scan.cacheSizes
    val empty = !unknown && scan.bytes(kind) == 0L
    val mark = when {
        empty -> "[-]"
        ticked -> "[x]"
        else -> "[ ]"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled && !empty) { onToggle() }
            .padding(horizontal = Metrics.padH, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Line("$mark ${kind.label}", if (empty) p.dim else if (ticked) p.fgBright else p.fg, Metrics.bodySize, bold = ticked && !empty, modifier = Modifier.weight(1f))
        Line(
            text = when {
                unknown -> "?"
                empty -> "none"
                else -> Junk.size(scan.bytes(kind))
            },
            color = if (ticked && !empty) p.yellow else p.dim,
            size = Metrics.bodySize,
        )
    }
    Line(kind.note, p.dim, Metrics.smallSize, modifier = Modifier.padding(start = Metrics.padH + 36.dp, end = Metrics.padH))
    val cache = scan.cache
    if (kind == Kind.CACHE && cache != null && scan.cacheReserve > 0) {
        Line(
            "${Junk.size(cache)} in all · android keeps ${Junk.size(minOf(cache, scan.cacheReserve))}",
            p.dim,
            Metrics.smallSize,
            modifier = Modifier.padding(start = Metrics.padH + 36.dp, end = Metrics.padH),
        )
    }
    sizing?.let { Line(it, p.dim, Metrics.smallSize, modifier = Modifier.padding(start = Metrics.padH + 36.dp, end = Metrics.padH)) }
    Junk.top(items, SHOWN).forEach { item ->
        Row(
            Modifier.fillMaxWidth().padding(start = Metrics.padH + 36.dp, end = Metrics.padH),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Line(item.name, p.muted, Metrics.smallSize, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Line(Junk.size(item.bytes), p.muted, Metrics.smallSize)
        }
    }
    Spacer(Modifier.padding(bottom = 6.dp))
}

@Composable
private fun Warning(text: String) {
    Line(text, Palette.current.red, Metrics.smallSize, modifier = Modifier.padding(horizontal = Metrics.padH, vertical = 2.dp))
}

@Composable
private fun Line(text: String, color: Color, size: TextUnit, modifier: Modifier = Modifier, bold: Boolean = false) {
    Text(
        text = text,
        color = color,
        fontSize = size,
        fontFamily = AdosauraMono,
        fontWeight = if (bold) FontWeight.Bold else null,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
