package dev.aura.adosaura.switcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.aura.adosaura.theme.AdosauraMono
import dev.aura.adosaura.theme.Metrics
import dev.aura.adosaura.theme.Palette
import dev.aura.adosaura.ui.MenuItem
import dev.aura.adosaura.ui.TextMenu

/**
 * The app switcher UI. Shows apps in MRU order with clean actions.
 */
@Composable
fun SwitcherScreen(
    labels: Map<String, String>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val recents = remember { Recents(context) }
    var apps by remember { mutableStateOf(emptyList<RecentApp>()) }
    var menuEntry by remember { mutableStateOf<RecentApp?>(null) }
    var freedKb by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        apps = recents.recent(labels)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Palette.current.bg.copy(alpha = 0.97f))
            .border(Metrics.border, Palette.current.border),
    ) {
        Column(Modifier.fillMaxSize().padding(vertical = Metrics.padV)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Metrics.padH),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "─ apps on ─",
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

            if (!recents.granted()) {
                Text(
                    text = "Usage access required.\nRun scripts/adb-grants.sh.",
                    color = Palette.current.red,
                    fontSize = Metrics.drawerRowSize,
                    fontFamily = AdosauraMono,
                    modifier = Modifier.padding(Metrics.padH),
                )
            }

            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(apps, key = { it.pkg }) { app ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .pointerInput(app.pkg) {
                                detectTapGestures(
                                    onTap = { 
                                        if (Recents.open(context, app.pkg)) onClose()
                                    },
                                    onLongPress = { menuEntry = app },
                                )
                            }
                            .padding(horizontal = Metrics.padH, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = app.label,
                            color = Palette.current.fg,
                            fontSize = Metrics.drawerRowSize,
                            fontFamily = AdosauraMono,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = app.age,
                            color = Palette.current.dim,
                            fontSize = Metrics.drawerRowSize,
                            fontFamily = AdosauraMono,
                            modifier = Modifier.padding(end = 16.dp),
                        )
                        Text(
                            text = "[clean]",
                            color = Palette.current.yellow,
                            fontSize = Metrics.drawerRowSize,
                            fontFamily = AdosauraMono,
                            modifier = Modifier.clickable {
                                recents.clean(app.pkg)
                                apps = apps.filter { it.pkg != app.pkg }
                            },
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = Metrics.padH, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "clean all",
                    color = Palette.current.yellow,
                    fontSize = Metrics.titleSize,
                    fontFamily = AdosauraMono,
                    modifier = Modifier
                        .clickable {
                            freedKb = recents.cleanAll(apps.map { it.pkg })
                            apps = emptyList()
                        },
                )
                if (freedKb != null) {
                    val mb = freedKb!! / 1024
                    Text(
                        text = " · freed ${mb}MB",
                        color = Palette.current.dim,
                        fontSize = Metrics.titleSize,
                        fontFamily = AdosauraMono,
                    )
                }
            }
        }

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
                        MenuItem("clean") {
                            recents.clean(entry.pkg)
                            apps = apps.filter { it.pkg != entry.pkg }
                            menuEntry = null
                        },
                        MenuItem("cancel") { menuEntry = null },
                    ),
                    onDismiss = { menuEntry = null },
                )
            }
        }
    }
}
