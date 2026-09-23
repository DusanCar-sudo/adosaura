package dev.aura.adosaura

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.aura.adosaura.config.AppsConfigParser
import dev.aura.adosaura.config.TileSpec
import dev.aura.adosaura.drawer.DrawerScreen
import dev.aura.adosaura.switcher.SwitcherScreen
import dev.aura.adosaura.status.NoticeBar
import dev.aura.adosaura.status.StatusStrip
import dev.aura.adosaura.theme.AdosauraMono
import dev.aura.adosaura.theme.Metrics
import dev.aura.adosaura.theme.Palette
import dev.aura.adosaura.tiles.TileView
import dev.aura.adosaura.ui.MenuItem
import dev.aura.adosaura.ui.TextMenu
import dev.aura.adosaura.ui.SwipeUpBar
import dev.aura.adosaura.ui.rememberBatteryPercent
import dev.aura.adosaura.ui.rememberTick
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The launcher — one Activity, and the only one that answers HOME.
 */
class LauncherActivity : ComponentActivity() {

    private lateinit var model: LauncherModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = LauncherModel(applicationContext)
        model.start()

        // A launcher is on screen every time the phone is looked at; letting
        // the panel sleep mid-gesture makes it feel dead.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent { LauncherRoot(model) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Pressing Home while already home collapses to page 0 and closes the
        // drawer — the contract every launcher has to honour.
        model.goHome()
    }

    override fun onResume() {
        super.onResume()
        applyImmersive()
        model.reload()
    }

    override fun onDestroy() {
        model.stop()
        super.onDestroy()
    }

    /**
     * The system bars are hidden on the home screen and the launcher draws its
     * own status strip. Swiping in from an edge brings them back transiently,
     * which is the behaviour people expect.
     */
    private fun applyImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

private val STRIP_TIME = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun LauncherRoot(model: LauncherModel) {
    BackHandler(enabled = model.drawerOpen) { model.closeDrawer() }

    val bgModifier = when (Palette.wallpaperOverride) {
        "solid" -> Modifier.fillMaxSize().background(Palette.current.bg)
        "transparent" -> Modifier.fillMaxSize() // Default system wallpaper shows through
        else -> Modifier.fillMaxSize()
    }
    Box(bgModifier) {
        Column(Modifier.fillMaxSize()) {
            val tick = rememberTick()
            val battery = rememberBatteryPercent()
            val now = Instant.ofEpochMilli(tick).atZone(ZoneId.systemDefault())

            val context = androidx.compose.ui.platform.LocalContext.current
            StatusStrip(
                page = model.currentPage,
                pageCount = model.layout.pageCount.coerceAtLeast(1),
                battery = battery,
                time = now.toLocalTime().format(STRIP_TIME),
                onAddTile = { model.openAddMenu() },
                onOpenDrawer = { model.openDrawer() },
                onOpenSettings = { model.openThemeMenu() },
                onCleanAll = { model.cleanAllRecents(context) },
                openAppCount = model.openAppCount,
            )

            NoticeBar(
                notices = model.notices,
                onReload = { model.reload() },
            )

            TilePager(model, Modifier.weight(1f).fillMaxWidth())
            
            SwipeUpBar(
                onDrawer = { model.openDrawer() },
                onSwitcher = { model.openSwitcher() },
            )
        }

        if (model.drawerOpen) {
            DrawerScreen(
                entries = model.apps.entries,
                known = model.labels.keys,
                query = model.query,
                onQueryChange = { model.query = it },
                onLaunch = { if (model.launch(it)) model.closeDrawer() },
                onHide = { model.hideApp(it) },
                onInfo = { model.appInfo(it.pkg) },
                onAddTile = { 
                    model.addTile(TileSpec(model.currentPage, model.layout.rows(model.currentPage).size, "apps", it.pkg))
                    model.closeDrawer()
                },
                onClose = { model.closeDrawer() },
            )
        }

        if (model.switcherOpen) {
            SwitcherScreen(
                labels = model.labels,
                onClose = { model.closeSwitcher() },
            )
        }

        // Two modal menus, both dismissed by a tap anywhere.
        model.editingTile?.let { spec ->
            TileMenu(model, spec)
        }
        if (model.addMenuOpen) {
            AddMenu(model)
        }
        if (model.themeMenuOpen) {
            ThemeMenu(model)
        }
        if (model.savesMenuOpen) {
            SavesMenu(model)
        }
        model.selectedSave?.let { name ->
            SaveMenu(model, name)
        }

        // Drawn last, over menus too, and never takes a touch.
        if (Palette.crt) CrtOverlay()
    }
}

/** Scanlines, a faint rolling band and a dark vignette: an old monitor, over everything. */
@Composable
private fun CrtOverlay() {
    val roll by rememberInfiniteTransition(label = "crt").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(7000, easing = LinearEasing),
        ),
        label = "roll",
    )
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val line = Color.Black.copy(alpha = 0.45f)
        var y = 0f
        while (y < size.height) {
            drawRect(line, topLeft = Offset(0f, y), size = Size(size.width, 2f))
            y += 4f
        }
        val bandH = size.height / 6f
        val bandY = roll * (size.height + bandH) - bandH
        drawRect(
            Brush.verticalGradient(
                listOf(Color.Transparent, Palette.current.fg.copy(alpha = 0.08f), Color.Transparent),
                startY = bandY, endY = bandY + bandH,
            ),
            topLeft = Offset(0f, bandY),
            size = Size(size.width, bandH),
        )
        drawRect(
            Brush.radialGradient(
                0.35f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.78f),
                center = center,
                radius = size.maxDimension * 0.58f,
            ),
        )
    }
}

@Composable
private fun TilePager(model: LauncherModel, modifier: Modifier = Modifier) {
    val pageCount = model.layout.pageCount.coerceAtLeast(1)
    val pagerState = rememberPagerState(initialPage = model.currentPage) { pageCount }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { model.currentPage = it }
    }

    // A Home press collapses to the first page.
    LaunchedEffect(model.homeSignal) {
        if (model.homeSignal > 0) pagerState.scrollToPage(0)
    }

    HorizontalPager(state = pagerState, modifier = modifier) { page ->
        TilePage(model, page)
    }
}

/**
 * One page: the top row of tiles and the column below. Both lay out by weight,
 * so `weight 2` in the column is exactly the tall middle tile.
 */
@Composable
private fun TilePage(model: LauncherModel, page: Int) {
    val tiles = model.layout.onPage(page)
    val rows = model.layout.rows(page)

    Column(
        Modifier
            .fillMaxWidth()
            .padding(Metrics.outerGap)
            .verticalScroll(rememberScrollState())
    ) {
        when {
            tiles.isEmpty() -> Box(Modifier.fillMaxWidth().height(400.dp)) { EmptyPageHint() }

            else -> {
                // Rows stack downwards; tiles inside one row sit side by side.
                // We use fixed height per share now to allow scrolling.
                val baseHeight = 160.dp
                val lastRow = rows.keys.last()
                rows.forEach { (row, inRow) ->
                    val maxH = inRow.maxOf { it.h }
                    Row(Modifier.fillMaxWidth().height(baseHeight * maxH)) {
                        inRow.forEach { spec ->
                            TileCell(model, spec, Modifier.weight(spec.w.toFloat()))
                        }
                    }
                    if (row != lastRow) Spacer(Modifier.height(Metrics.gap))
                }

                // While the first read is in flight there is nothing to say,
                // and an "empty page" hint would flash and then vanish.
                if (model.loading && tiles.isEmpty()) {
                    Spacer(Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun TileCell(model: LauncherModel, spec: TileSpec, modifier: Modifier = Modifier) {
    TileView(
        spec = spec,
        apps = model.apps,
        isActive = model.activeTile == spec || model.editingTile == spec,
        onSelect = { model.selectTile(spec) },
        onLaunch = { model.launch(it) },
        onResizeWidth = { steps -> model.resize(spec, steps, 0) },
        onResizeHeight = { steps -> model.resize(spec, 0, steps) },
        onResizeEnd = { model.resizeEnd() },
        onEdit = { model.editTile(spec) },
        modifier = modifier,
    )
}

@Composable
private fun EmptyPageHint() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "empty page\npress \"add\" to fill it",
            color = Palette.current.dim,
            fontSize = Metrics.bodySize,
            fontFamily = AdosauraMono,
            lineHeight = Metrics.bodySize * 1.6f,
        )
    }
}

@Composable
private fun TileMenu(model: LauncherModel, spec: TileSpec) {
    MenuOverlay(onDismiss = { model.dismissMenus() }) {
        TextMenu(
            title = "tile",
            items = listOf(
                MenuItem("bigger wide") {
                    model.resize(spec, 1, 0)
                    model.resizeEnd()
                    model.dismissMenus()
                },
                MenuItem("smaller wide") {
                    model.resize(spec, -1, 0)
                    model.resizeEnd()
                    model.dismissMenus()
                },
                MenuItem("bigger tall") {
                    model.resize(spec, 0, 1)
                    model.resizeEnd()
                    model.dismissMenus()
                },
                MenuItem("smaller tall") {
                    model.resize(spec, 0, -1)
                    model.resizeEnd()
                    model.dismissMenus()
                },
                MenuItem("bigger (both)") {
                    model.resize(spec, 1, 1)
                    model.resizeEnd()
                    model.dismissMenus()
                },
                MenuItem("smaller (both)") {
                    model.resize(spec, -1, -1)
                    model.resizeEnd()
                    model.dismissMenus()
                },
                MenuItem("move to page ${spec.page + 2}") {
                    model.moveTileToPage(spec, spec.page + 1)
                    model.dismissMenus()
                },
                MenuItem("add tile") {
                    model.dismissMenus()
                    model.openAddMenu()
                },
                MenuItem("beside the row above") {
                    model.moveTileToRow(spec, spec.row - 1)
                    model.dismissMenus()
                },
                MenuItem("beside the row below") {
                    model.moveTileToRow(spec, spec.row + 1)
                    model.dismissMenus()
                },
                MenuItem("remove tile") {
                    model.removeTile(spec)
                    model.dismissMenus()
                },
                MenuItem("cancel") { model.dismissMenus() },
            ),
            onDismiss = { model.dismissMenus() },
        )
    }
}

@Composable
private fun ThemeMenu(model: LauncherModel) {
    MenuOverlay(onDismiss = { model.dismissMenus() }) {
        TextMenu(
            title = "theme",
            items = dev.aura.adosaura.theme.Themes.BUNDLED.map { name ->
                MenuItem(if (name == dev.aura.adosaura.theme.Palette.name) "[ $name ]" else name) {
                    model.setTheme(name)
                    model.dismissMenus()
                }
            } + listOf(
                MenuItem("glass panes") {
                    model.setPanelAlpha(0.6f)
                    model.dismissMenus()
                },
                MenuItem("solid panes") {
                    model.setPanelAlpha(0.95f)
                    model.dismissMenus()
                },
                MenuItem("bg: wallpaper") {
                    model.setWallpaper("transparent")
                    model.dismissMenus()
                },
                MenuItem("bg: solid color") {
                    model.setWallpaper("solid")
                    model.dismissMenus()
                },
                MenuItem(if (Palette.crt) "crt effect: on" else "crt effect: off") {
                    model.setCrt(!Palette.crt)
                    model.dismissMenus()
                },
                MenuItem("cancel") { model.dismissMenus() }
            ),
            onDismiss = { model.dismissMenus() },
        )
    }
}

@Composable
private fun SavesMenu(model: LauncherModel) {
    MenuOverlay(onDismiss = { model.dismissMenus() }) {
        TextMenu(
            title = "saved screens",
            items = listOf(
                MenuItem("+ save this screen") {
                    model.saveHome()
                    model.dismissMenus()
                },
            ) + model.saves.map { name ->
                MenuItem(name) { model.selectSave(name) }
            } + dev.aura.adosaura.config.Saves.PRESETS.map { name ->
                MenuItem("preset: $name") {
                    model.applyPreset(name)
                    model.dismissMenus()
                }
            } + MenuItem("cancel") { model.dismissMenus() },
            onDismiss = { model.dismissMenus() },
        )
    }
}

@Composable
private fun SaveMenu(model: LauncherModel, name: String) {
    MenuOverlay(onDismiss = { model.dismissMenus() }) {
        TextMenu(
            title = name,
            items = listOf(
                MenuItem("load") {
                    model.loadHome(name)
                    model.dismissMenus()
                },
                MenuItem("overwrite with this screen") {
                    model.saveHome(name)
                    model.dismissMenus()
                },
                MenuItem("delete") {
                    model.deleteHome(name)
                    model.dismissMenus()
                },
                MenuItem("back") { model.openSavesMenu() },
            ),
            onDismiss = { model.dismissMenus() },
        )
    }
}

@Composable
private fun AddMenu(model: LauncherModel) {
    val page = model.currentPage
    MenuOverlay(onDismiss = { model.dismissMenus() }) {
        TextMenu(
            title = "add tile",
            items = listOf(
                MenuItem("clock + calendar") {
                    model.addTile(TileSpec(page, model.layout.rows(page).size, "clockcal"))
                    model.dismissMenus()
                },
                MenuItem("clock") {
                    model.addTile(TileSpec(page, model.layout.rows(page).size, "clock"))
                    model.dismissMenus()
                },
                MenuItem("calendar") {
                    model.addTile(TileSpec(page, model.layout.rows(page).size, "calendar"))
                    model.dismissMenus()
                },
                MenuItem("status") {
                    model.addTile(TileSpec(page, model.layout.rows(page).size, "stats"))
                    model.dismissMenus()
                },
                MenuItem("performance") {
                    model.addTile(TileSpec(page, model.layout.rows(page).size, "perf"))
                    model.dismissMenus()
                },
                MenuItem("weather") {
                    model.addTile(TileSpec(page, model.layout.rows(page).size, "weather"))
                    model.dismissMenus()
                },
                MenuItem("apps (pinned)") {
                    model.addTile(TileSpec(page, model.layout.rows(page).size, "apps", LauncherModel.GROUP_PINNED))
                    model.dismissMenus()
                },
                MenuItem("apps (all)") {
                    model.addTile(
                        TileSpec(page, model.layout.rows(page).size, "apps", AppsConfigParser.DEFAULT_GROUP),
                    )
                    model.dismissMenus()
                },
                MenuItem("new page") {
                    model.addTile(TileSpec(model.layout.pageCount, 0, "clockcal"))
                    model.dismissMenus()
                },
                MenuItem("theme...") {
                    model.openThemeMenu()
                },
                MenuItem("saves...") {
                    model.openSavesMenu()
                },
                MenuItem("factory reset") {
                    model.factoryReset()
                    model.dismissMenus()
                },
                MenuItem("cancel") { model.dismissMenus() },
            ),
            onDismiss = { model.dismissMenus() },
        )
    }
}

/** A centred menu over a dimmed launcher. A tap anywhere dismisses it. */
@Composable
private fun MenuOverlay(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.current.bg.copy(alpha = 0.6f))
            .pointerInput(onDismiss) { detectTapGestures(onTap = { onDismiss() }) },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
