package dev.aura.adosaura

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import dev.aura.adosaura.config.TileStyle
import dev.aura.adosaura.theme.resolve
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
import dev.aura.adosaura.ui.WhileStarted
import dev.aura.adosaura.ui.rememberBatteryPercent
import dev.aura.adosaura.ui.rememberTick
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The launcher — one Activity, and the only one that answers HOME.
 */
class LauncherActivity : ComponentActivity() {

    private lateinit var model: LauncherModel

    /** The system photo picker: no storage permission, only the one picture chosen. */
    private val pickPhoto = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val target = minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        Thread {
            val ok = dev.aura.adosaura.ui.Photo.import(this, uri, target)
            runOnUiThread {
                if (ok) model.setWallpaper("photo") else model.flash("could not read that photo")
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = LauncherModel(applicationContext)
        model.start()
        model.pickPhoto = {
            pickPhoto.launch(
                androidx.activity.result.PickVisualMediaRequest(
                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                ),
            )
        }

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

    override fun onUserInteraction() {
        super.onUserInteraction()
        model.lastInteraction.longValue = System.currentTimeMillis()
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
        "photo" -> Modifier.fillMaxSize().background(Palette.current.bg)
        "transparent" -> Modifier.fillMaxSize() // Default system wallpaper shows through
        else -> Modifier.fillMaxSize()
    }
    Box(bgModifier) {
        if (Palette.wallpaperOverride == "photo") {
            dev.aura.adosaura.ui.PhotoBackground(Palette.photoStamp)
        }
        // imePadding: typing into a note tile shrinks the tiles above the
        // keyboard instead of hiding the line being typed behind it.
        Column(Modifier.fillMaxSize().imePadding()) {
            val tick = rememberTick()
            val battery = rememberBatteryPercent()
            val now = Instant.ofEpochMilli(tick).atZone(ZoneId.systemDefault())

            val context = androidx.compose.ui.platform.LocalContext.current
            // Eco: battery saver on, or low and unplugged. Checked twice a minute.
            WhileStarted {
                while (true) {
                    Palette.eco = dev.aura.adosaura.switcher.Optimizer.eco(context)
                    kotlinx.coroutines.delay(30_000)
                }
            }
            StatusStrip(
                page = model.currentPage,
                pageCount = model.layout.pageCount.coerceAtLeast(1),
                battery = battery,
                time = now.toLocalTime().format(STRIP_TIME),
                onAddTile = { model.openAddMenu() },
                onOpenDrawer = { model.openDrawer() },
                onOpenSettings = { model.openThemeMenu() },
                onCleanAll = { model.cleanAllRecents(context) },
                onOptimize = { model.openOptimizeMenu() },
                cleaning = model.cleaning,
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

        if (model.deepCleanOpen) {
            dev.aura.adosaura.clean.DeepCleanScreen(
                labels = model.labels,
                onClose = { model.closeDeepClean() },
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
        if (model.optimizeMenuOpen) {
            OptimizeMenu(model)
        }
        if (model.savesMenuOpen) {
            SavesMenu(model)
        }
        model.selectedSave?.let { name ->
            SaveMenu(model, name)
        }

        // Drawn last, over menus too, and never takes a touch.
        if (Palette.crt) CrtOverlay()
        // The charging screensaver: on top of everything, shown only when idle.
        ChargingOverlay(model.lastInteraction)
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
        if (Palette.eco) return@Canvas
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

/** Seconds with no touch before the charging animation takes over the screen. */
private const val CHARGE_IDLE_MS = 4000L

/** One full loop of the spark around the rail, in milliseconds. */
private const val CHARGE_LOOP_MS = 4000L

/**
 * A charging screensaver: electricity races around the screen edges, bolts
 * strike a chip in the middle, and current flows through the chip. Shown only
 * while plugged in and idle; any touch dismisses it (onUserInteraction), and it
 * returns a few seconds after the last touch.
 */
@Composable
private fun ChargingOverlay(lastInteraction: androidx.compose.runtime.MutableLongState) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var charging by remember { mutableStateOf(false) }
    WhileStarted {
        while (true) {
            val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            kotlinx.coroutines.delay(1500)
        }
    }
    if (!charging) return

    // A slow ticker notices idle->show; the frame loop below runs only while shown.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    WhileStarted {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(250)
        }
    }
    val idle = now - lastInteraction.longValue >= CHARGE_IDLE_MS
    // `visible` only flips off when the screensaver's own gesture finishes, so a
    // dismissing tap is fully swallowed and never leaks to the launcher below.
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(idle) { if (idle) visible = true }
    // Stop the tiles animating behind the scrim: their 120fps redraws would
    // otherwise redraw this overlay every frame and get the launcher killed.
    DisposableEffect(visible) {
        val was = Palette.eco
        Palette.eco = was || visible
        onDispose { Palette.eco = was }
    }
    var t by remember { mutableFloatStateOf(0f) }
    WhileStarted(visible) {
        if (!visible) return@WhileStarted
        val start = System.currentTimeMillis() - (t * 1000f).toLong()
        while (true) {
            t = (System.currentTimeMillis() - start) / 1000f
            kotlinx.coroutines.delay(100) // ~10fps: electricity crackles, no need to race the display or burn CPU
        }
    }
    if (!visible) return

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitFirstDown().consume()
                        var up = false
                        while (!up) {
                            val e = awaitPointerEvent()
                            e.changes.forEach { it.consume() }
                            up = e.changes.none { it.pressed }
                        }
                        visible = false
                    }
                }
            },
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val phase = (t * 1000f / CHARGE_LOOP_MS) % 1f
            drawRect(Color.Black.copy(alpha = 0.32f))

            val cyan = Color(0xFF00E5FF)
            val whiteHot = Color(0xFFE6F9FF)

            // One river of electricity: a jagged channel from the bottom edge
            // into the chip at the centre.
            val chipW = size.minDimension * 0.26f
            val chipH = chipW * 0.62f
            val chipR = chipW * 0.16f
            val chipL = center.x - chipW / 2f
            val chipT = center.y - chipH / 2f
            val pulse = 0.5f + 0.5f * sin(phase * PI.toFloat() * 2f)

            val from = Offset(center.x, size.height)
            val to = Offset(center.x, chipT)
            val dx = to.x - from.x
            val dy = to.y - from.y
            val len = hypot(dx, dy)
            val nx = -dy / len
            val ny = dx / len
            val flick = (t * 1000f / 90f).toInt() // crackles ~11/s

            val riverPath = Path()
            riverPath.moveTo(from.x, from.y)
            for (k in 1..10) {
                val f = k / 10f
                val j = (h(flick + k) - 0.5f) * size.width * 0.15f * sin(f * PI).toFloat()
                riverPath.lineTo(from.x + dx * f + nx * j, from.y + dy * f + ny * j)
            }
            riverPath.lineTo(to.x, to.y)

            // Two thin strokes only: cyan body + hot core (wide glow strokes are CPU-expensive).
            drawPath(riverPath, cyan.copy(alpha = 0.6f), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
            drawPath(riverPath, whiteHot.copy(alpha = 0.95f), style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round))

            // A few sparks flowing up into the chip.
            for (p in 0 until 4) {
                val f = (phase + p / 4f) % 1f
                val px = center.x + sin((f + phase) * PI * 2f).toFloat() * size.width * 0.05f
                val py = from.y + (to.y - from.y) * f
                drawCircle(whiteHot.copy(alpha = 0.9f * (1f - f)), 1.5.dp.toPx(), Offset(px, py))
            }

            // The chip, fed from below.
            drawRoundRect(color = Color(0xFF061830), topLeft = Offset(chipL, chipT), size = Size(chipW, chipH), cornerRadius = CornerRadius(chipR, chipR))
            drawRoundRect(color = cyan.copy(alpha = (0.5f + 0.5f * pulse)), topLeft = Offset(chipL, chipT), size = Size(chipW, chipH), cornerRadius = CornerRadius(chipR, chipR), style = Stroke(1.5.dp.toPx()))
            for (f in 0 until 2) {
                val y = chipT + chipH * (0.3f + 0.4f * f)
                val sweep = (phase * 1.4f + f * 0.5f) % 1f
                val x = chipL + chipW * 0.12f + chipW * 0.76f * sweep
                drawCircle(cyan.copy(alpha = 0.7f), 1.6.dp.toPx(), Offset(x - 4.dp.toPx(), y))
                drawCircle(whiteHot.copy(alpha = 0.9f), 1.4.dp.toPx(), Offset(x, y))
            }
            // Impact glow where the river strikes the chip.
            drawCircle(whiteHot.copy(alpha = 0.3f + 0.4f * pulse), chipW * 0.12f, Offset(center.x, chipT))
            drawCircle(cyan.copy(alpha = (0.12f + 0.2f * pulse)), chipW * 0.30f, center)
        }
    }
}

/** Deterministic 0..1 from an int, so each bolt keeps its own zigzag shape. */
private fun h(x: Int): Float {
    var n = x * 0x45d9f3b
    n = (n xor (n ushr 16)) * 0x45d9f3b
    n = (n xor (n ushr 16)) and 0x7fffffff
    return n / 0x7fffffff.toFloat()
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
    when (model.tileMenuPage) {
        "style" -> return TileStyleMenu(model, spec)
        "bg", "fg", "edge" -> return TileColourMenu(model, spec, model.tileMenuPage!!)
    }
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
                MenuItem("style… (colours, text size)") { model.tileMenuPage = "style" },
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
private fun TileStyleMenu(model: LauncherModel, spec: TileSpec) {
    MenuOverlay(onDismiss = { model.dismissMenus() }) {
        TextMenu(
            title = "tile style · text ${spec.size}%",
            items = listOf(
                MenuItem("tile colour…  (${spec.bg ?: "theme"})") { model.tileMenuPage = "bg" },
                MenuItem("text colour…  (${spec.fg ?: "theme"})") { model.tileMenuPage = "fg" },
                MenuItem("outline colour…  (${spec.edge ?: "theme"})") { model.tileMenuPage = "edge" },
                MenuItem("text bigger") {
                    model.setTileStyle(spec, spec.bg, spec.fg, TileStyle.step(spec.size, +1), spec.edge)
                },
                MenuItem("text smaller") {
                    model.setTileStyle(spec, spec.bg, spec.fg, TileStyle.step(spec.size, -1), spec.edge)
                },
                MenuItem("reset style") { model.setTileStyle(spec, null, null, 100) },
                MenuItem("back") { model.tileMenuPage = null },
                MenuItem("done") { model.dismissMenus() },
            ),
            onDismiss = { model.dismissMenus() },
        )
    }
}

/** Every colour a tile can take, each row drawn in its own colour. */
@Composable
private fun TileColourMenu(model: LauncherModel, spec: TileSpec, which: String) {
    val palette = Palette.current
    fun pick(word: String?) {
        when (which) {
            "bg" -> model.setTileStyle(spec, word, spec.fg, spec.size, spec.edge)
            "fg" -> model.setTileStyle(spec, spec.bg, word, spec.size, spec.edge)
            else -> model.setTileStyle(spec, spec.bg, spec.fg, spec.size, word)
        }
        model.tileMenuPage = "style"
    }
    val words = TileStyle.THEME_KEYS + TileStyle.FIXED.keys
    MenuOverlay(onDismiss = { model.dismissMenus() }) {
        TextMenu(
            title = when (which) { "bg" -> "tile colour"; "fg" -> "text colour"; else -> "outline colour" },
            items = listOf(MenuItem("theme default") { pick(null) }) +
                words.map { name ->
                    val word = TileStyle.FIXED[name] ?: name
                    MenuItem(name, swatch = palette.resolve(word)) { pick(word) }
                } +
                MenuItem("back") { model.tileMenuPage = "style" },
            onDismiss = { model.dismissMenus() },
        )
    }
}

@Composable
private fun ThemeMenu(model: LauncherModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
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
                MenuItem("pick wallpaper…") {
                    model.setWallpaper("transparent")
                    dev.aura.adosaura.switcher.Optimizer.pickWallpaper(context)
                    model.dismissMenus()
                },
                MenuItem("bg: photo from gallery…") {
                    model.dismissMenus()
                    model.pickPhoto()
                },
                MenuItem("bg: solid color") {
                    model.setWallpaper("solid")
                    model.dismissMenus()
                },
                MenuItem("tile frame: ${Palette.frame}  (tap to change)") {
                    val all = LauncherModel.FRAMES
                    model.setFrame(all[(all.indexOf(Palette.frame) + 1) % all.size])
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
private fun OptimizeMenu(model: LauncherModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val saver = dev.aura.adosaura.switcher.Optimizer.powerSaveOn(context)
    MenuOverlay(onDismiss = { model.dismissMenus() }) {
        TextMenu(
            title = "optimize",
            items = listOf(
                MenuItem("clean memory now") {
                    model.dismissMenus()
                    model.cleanAllRecents(context)
                },
                MenuItem("deep clean storage…") { model.openDeepClean() },
                MenuItem(if (saver) "battery saver: on…" else "battery saver: off…") {
                    dev.aura.adosaura.switcher.Optimizer.openBatterySaver(context)
                    model.dismissMenus()
                },
                MenuItem("what drains the battery…") {
                    dev.aura.adosaura.switcher.Optimizer.openBatteryUsage(context)
                    model.dismissMenus()
                },
                MenuItem("cancel") { model.dismissMenus() },
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
                MenuItem("analog clock (animated)") {
                    model.addTile(TileSpec(page, model.layout.rows(page).size, "analog"))
                    model.dismissMenus()
                },
                MenuItem("battery (animated)") {
                    model.addTile(TileSpec(page, model.layout.rows(page).size, "battery"))
                    model.dismissMenus()
                },
                MenuItem("matrix rain (animated)") {
                    model.addTile(TileSpec(page, model.layout.rows(page).size, "matrix"))
                    model.dismissMenus()
                },
                MenuItem("note (todo.md)") {
                    model.addTile(TileSpec(page, model.layout.rows(page).size, "note", "todo"))
                    model.dismissMenus()
                },
                MenuItem("text file (status.txt)") {
                    model.addTile(TileSpec(page, model.layout.rows(page).size, "file", "status.txt"))
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
