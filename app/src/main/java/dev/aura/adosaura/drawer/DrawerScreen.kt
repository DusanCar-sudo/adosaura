package dev.aura.adosaura.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.aura.adosaura.config.AppEntry
import dev.aura.adosaura.config.Fuzzy
import dev.aura.adosaura.theme.AdosauraMono
import dev.aura.adosaura.theme.Metrics
import dev.aura.adosaura.theme.Palette
import dev.aura.adosaura.ui.MenuItem
import dev.aura.adosaura.ui.TextMenu

/**
 * The app drawer: a searchable, icon-free list, modelled on the omarchy menu
 * but with every row a plain monospace name.
 *
 *   ─ apps ─                        esc
 *   ┌ Search… ────────────────────┐
 *   Chrome
 *   Termux
 *   …
 *   enter launch · esc close
 *
 * Long-pressing a row opens a text menu (open / app info / hide), never an
 * Android context menu.
 */
@Composable
fun DrawerScreen(
    entries: List<AppEntry>,
    known: Set<String>,
    query: String,
    onQueryChange: (String) -> Unit,
    onLaunch: (String) -> Unit,
    onHide: (AppEntry) -> Unit,
    onInfo: (AppEntry) -> Unit,
    onAddTile: (AppEntry) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ranked = remember(query, entries) { Fuzzy.rank(query, entries) }
    val top = ranked.firstOrNull()
    val focusRequester = remember { FocusRequester() }
    var menuEntry by remember { mutableStateOf<AppEntry?>(null) }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Palette.current.bg.copy(alpha = 0.5f))
            .pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.8f)
                .background(Palette.current.bgAlt.copy(alpha = Metrics.PANEL_ALPHA))
                .border(Metrics.border, Palette.current.border)
                .pointerInput(Unit) { detectTapGestures { /* consume taps so they don't close drawer */ } }
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyUp && event.key == Key.Escape) {
                        onClose()
                        true
                    } else {
                        false
                    }
                }
        ) {
        Column(Modifier.fillMaxSize().padding(vertical = Metrics.padV)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Metrics.padH),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "─ apps ─",
                    color = Palette.current.dim,
                    fontSize = Metrics.titleSize,
                    fontWeight = FontWeight.Bold,
                    fontFamily = AdosauraMono,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "esc",
                    color = Palette.current.dim,
                    fontSize = Metrics.titleSize,
                    fontFamily = AdosauraMono,
                    modifier = Modifier.clickable { onClose() },
                )
            }

            SearchField(
                query = query,
                onQueryChange = onQueryChange,
                focusRequester = focusRequester,
                onSearch = { top?.let { onLaunch(it.pkg) } },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Metrics.padH, vertical = 6.dp),
            )

            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(ranked, key = { it.pkg }) { entry ->
                    AppRow(
                        entry = entry,
                        installed = entry.pkg in known,
                        onLaunch = onLaunch,
                        onMenu = { menuEntry = entry },
                    )
                }

                if (ranked.isEmpty()) {
                    item {
                        Text(
                            text = "nothing matches \"${query.trim()}\"",
                            color = Palette.current.dim,
                            fontSize = Metrics.drawerRowSize,
                            fontFamily = AdosauraMono,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Metrics.padH, vertical = 6.dp),
                        )
                    }
                }
            }

            Text(
                text = "enter launch · esc close",
                color = Palette.current.muted,
                fontSize = Metrics.titleSize,
                fontFamily = AdosauraMono,
                modifier = Modifier.padding(horizontal = Metrics.padH, vertical = 4.dp),
            )
        }

        // Long-press menu, drawn over the list and dismissed by a tap anywhere.
        menuEntry?.let { entry ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Palette.current.bg.copy(alpha = 0.6f))
                    .pointerInput(entry) { detectTapGestures(onTap = { menuEntry = null }) },
                contentAlignment = Alignment.Center,
            ) {
                TextMenu(
                    title = entry.label,
                    items = listOf(
                        MenuItem("open") { menuEntry = null; onLaunch(entry.pkg) },
                        MenuItem("app info") { menuEntry = null; onInfo(entry) },
                        MenuItem("add as tile") { menuEntry = null; onAddTile(entry) },
                        MenuItem("hide") { menuEntry = null; onHide(entry) },
                        MenuItem("cancel") { menuEntry = null },
                    ),
                    onDismiss = { menuEntry = null },
                )
            }
        }
    }
}

}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    focusRequester: FocusRequester,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = TextStyle(
            color = Palette.current.fgBright,
            fontSize = Metrics.drawerRowSize,
            fontFamily = AdosauraMono,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        decorationBox = { inner ->
            if (query.isEmpty()) {
                Text(
                    text = "Search…",
                    color = Palette.current.dim,
                    fontSize = Metrics.drawerRowSize,
                    fontFamily = AdosauraMono,
                )
            }
            inner()
        },
        modifier = modifier
            .border(Metrics.border, Palette.current.border)
            .background(Palette.current.bgAlt.copy(alpha = dev.aura.adosaura.theme.Palette.current.panelAlpha))
            .focusRequester(focusRequester)
            .padding(horizontal = Metrics.padH, vertical = 6.dp),
    )
}

@Composable
private fun AppRow(
    entry: AppEntry,
    installed: Boolean,
    onLaunch: (String) -> Unit,
    onMenu: () -> Unit,
) {
    Text(
        text = if (installed) entry.label else "${entry.label} ?",
        color = if (installed) Palette.current.fg else Palette.current.dim,
        fontSize = Metrics.drawerRowSize,
        fontFamily = AdosauraMono,
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(entry.pkg) {
                detectTapGestures(
                    onTap = { onLaunch(entry.pkg) },
                    onLongPress = { onMenu() },
                )
            }
            .padding(horizontal = Metrics.padH, vertical = 6.dp),
    )
}

