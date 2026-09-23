package dev.aura.adosaura

import android.content.Context
import android.content.pm.LauncherApps
import android.os.UserHandle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.aura.adosaura.config.AppEntry
import dev.aura.adosaura.config.AppsConfig
import dev.aura.adosaura.config.AppsConfigParser
import dev.aura.adosaura.config.AppsDocument
import dev.aura.adosaura.config.ConfigFiles
import dev.aura.adosaura.config.ConfigWatcher
import dev.aura.adosaura.config.LayoutConfig
import dev.aura.adosaura.config.LayoutConfigParser
import dev.aura.adosaura.config.LayoutDocument
import dev.aura.adosaura.config.TileSpec
import dev.aura.adosaura.drawer.AppRepository
import dev.aura.adosaura.switcher.Recents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything the launcher knows, in one place.
 *
 * The two config files on /sdcard are the source of truth; this class reads
 * them, keeps a parsed copy, and writes back only the one line that changed.
 * Nothing here ever throws at the UI — a broken file becomes a notice in the
 * status strip, never a crash on the home screen.
 */
class LauncherModel(private val context: Context) {

    val repo = AppRepository(context)

    var labels by mutableStateOf(emptyMap<String, String>())
        private set
    var apps by mutableStateOf(AppsConfig())
        private set
    var layout by mutableStateOf(LayoutConfig())
        private set

    /** One line per thing that went wrong, shown in the status strip. */
    var notices by mutableStateOf(emptyList<String>())
        private set

    var drawerOpen by mutableStateOf(false)
    var switcherOpen by mutableStateOf(false)
    var query by mutableStateOf("")
    var currentPage by mutableIntStateOf(0)

    /** The active/selected tile with accent outline and glow. */
    var activeTile by mutableStateOf<TileSpec?>(null)

    /** The two modal text menus. Only one can be open at a time. */
    var editingTile by mutableStateOf<TileSpec?>(null)
        private set
    var addMenuOpen by mutableStateOf(false)
        private set
    var themeMenuOpen by mutableStateOf(false)
        private set
    var savesMenuOpen by mutableStateOf(false)
        private set
    /** The save whose load/overwrite/delete menu is open. */
    var selectedSave by mutableStateOf<String?>(null)
        private set
    /** Save names, refreshed whenever the saves menu opens. */
    var saves by mutableStateOf(emptyList<String>())
        private set

    /** Bumped on every Home press, so the UI can collapse to page 0. */
    var homeSignal by mutableIntStateOf(0)
        private set

    /**
     * True until the first read of the config files lands. The UI draws its
     * frame without waiting for it — resolving every installed app is the
     * expensive part of starting a launcher, and it must never sit in onCreate.
     */
    var loading by mutableStateOf(true)
        private set

    var openAppCount by mutableStateOf<Int?>(null)
        private set

    private var started = false
    private var watcher: ConfigWatcher? = null

    /** Everything heavy runs here, never in onCreate. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Weights changed by a drag but not yet written to layout.conf. */
    private val pending = mutableMapOf<Int, TileSpec>()

    private val packageCallback = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String, user: UserHandle) {
            appendInstalled(packageName)
            reload()
        }

        override fun onPackageRemoved(packageName: String, user: UserHandle) = reload()

        override fun onPackageChanged(packageName: String, user: UserHandle) = reload()

        override fun onPackagesAvailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) = reload()

        override fun onPackagesUnavailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) = reload()
    }

    fun start() {
        if (started) return
        started = true
        reload()
        watcher = ConfigWatcher(ConfigFiles.dir) { reload() }.also { it.start() }
        runCatching { repo.register(packageCallback) }
    }

    fun stop() {
        watcher?.stop()
        watcher = null
        runCatching { repo.unregister(packageCallback) }
        scope.cancel()
        started = false
    }

    // ------------------------------------------------------------- reading

    /**
     * Reads both files and resolves every installed app, off the main thread,
     * then publishes the lot in one go. Never blocks the first frame.
     */
    fun reload() {
        scope.launch {
            val snapshot = readSnapshot()
            withContext(Dispatchers.Main) { publish(snapshot) }
        }
    }

    private fun publish(snapshot: Snapshot) {
        labels = snapshot.labels
        apps = snapshot.apps
        layout = snapshot.layout
        notices = snapshot.notices
        loading = false
        dev.aura.adosaura.theme.Palette.apply(dev.aura.adosaura.theme.Themes.load(context, snapshot.themeName, snapshot.panelAlpha))
        dev.aura.adosaura.theme.Palette.applyWallpaperOverride(snapshot.wallpaperSetting)
        dev.aura.adosaura.theme.Palette.crt = snapshot.crt
    }

    private fun readSnapshot(): Snapshot {
        val notes = mutableListOf<String>()

        if (!ConfigFiles.accessible()) {
            notes += ConfigFiles.accessHint()
        }

        val newLabels = repo.labels()

        // apps.conf: generate on first run so the drawer is never empty.
        if (ConfigFiles.read(ConfigFiles.apps) == null && newLabels.isNotEmpty()) {
            ConfigFiles.write(ConfigFiles.apps, generateApps(newLabels))
            notes += "generated apps.conf"
        }

        val parsedApps = AppsConfigParser.parse(ConfigFiles.read(ConfigFiles.apps).orEmpty())
        if (parsedApps.problems.isNotEmpty()) {
            notes += "${parsedApps.problems.size} apps.conf line(s) skipped"
        }

        // An uninstalled app takes its line with it. Deliberately the one
        // place that does it: apps.conf is the source of truth for the drawer
        // *and* the apps tiles, so fixing the file here is what stops the two
        // going stale behind our back. Runs on every reload, so it covers an
        // uninstall that happened while we were alive (the package callback
        // fires) and one that happened while we were dead.
        //
        // Two safeguards, because this code deletes user-authored text:
        //
        // 1. Calibrate before trusting the installed-check. Package managers
        //    can filter our view; if PackageManager will not even confirm a
        //    package LauncherApps just handed us, then "not found" means "not
        //    visible", not "uninstalled", and we fall back to the looser rule.
        // 2. Verify the splice before writing it. The result must contain
        //    exactly the entries we chose to keep, and must never be empty
        //    when the input was not. A purge that does not add up is a bug in
        //    the detection, and keeping a stale line beats losing the file.
        var liveApps = parsedApps
        if (parsedApps.entries.isNotEmpty() && newLabels.isNotEmpty()) {
            val probe = newLabels.keys.firstOrNull()
            val pmWorks = probe != null && isInstalled(probe)

            val gone = parsedApps.entries.filter { entry ->
                when {
                    entry.pkg in newLabels -> false   // launchable and present
                    !pmWorks -> true                  // cannot tell: assume gone
                    else -> !isInstalled(entry.pkg)   // precise: really uninstalled
                }
            }

            if (gone.isNotEmpty() && gone.size < parsedApps.entries.size) {
                val source = ConfigFiles.read(ConfigFiles.apps).orEmpty()
                if (source.isNotEmpty()) {
                    val trimmed = AppsDocument(source).withoutAll(gone)
                    val kept = AppsConfigParser.parse(trimmed).entries
                    val expected = parsedApps.entries.size - gone.size
                    if (kept.size == expected && kept.size > 0) {
                        ConfigFiles.write(ConfigFiles.apps, trimmed)
                        liveApps = AppsConfigParser.parse(trimmed)
                        notes += "dropped ${gone.size} uninstalled app(s)"
                    }
                }
            }
        }

        // We'll pass theme settings through Snapshot to publish() to ensure it's applied on Main thread.
        val themeName = ConfigFiles.read(ConfigFiles.theme)?.trim()?.ifEmpty { dev.aura.adosaura.theme.Themes.DEFAULT } ?: dev.aura.adosaura.theme.Themes.DEFAULT
        val lookText = ConfigFiles.read(ConfigFiles.look).orEmpty()
        val lookKv = dev.aura.adosaura.config.KeyValueParser.parse(lookText).entries
        val panelAlpha = lookKv["panel_alpha"]?.toIntOrNull()?.let { it / 100f } ?: 0.85f
        val wallpaperSetting = lookKv["wallpaper"]
        val crt = lookKv["crt"] == "on"

        // layout.conf: ship the documented default until one exists.
        if (ConfigFiles.read(ConfigFiles.layout) == null) {
            ConfigFiles.seed(context, "default-layout.conf", ConfigFiles.layout)
            notes += "generated layout.conf"
        }

        val parsedLayout = LayoutConfigParser.parse(ConfigFiles.read(ConfigFiles.layout).orEmpty())
        if (parsedLayout.problems.isNotEmpty()) {
            notes += "${parsedLayout.problems.size} layout.conf line(s) skipped"
        }

        return Snapshot(newLabels, liveApps, parsedLayout, notes, themeName, panelAlpha, wallpaperSetting, crt)
    }

    private data class Snapshot(
        val labels: Map<String, String>,
        val apps: AppsConfig,
        val layout: LayoutConfig,
        val notices: List<String>,
        val themeName: String,
        val panelAlpha: Float,
        val wallpaperSetting: String?,
        val crt: Boolean,
    )

    /**
     * Installed, launcher activity or not.
     *
     * Self-checking on purpose: this is called against our own package before
     * it is trusted to delete anything, so if PackageManager ever stops
     * answering, the purge stands down instead of wiping a healthy file.
     */
    private fun isInstalled(pkg: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    }.getOrDefault(false)

    /**
     * Every launchable app listed as `all`, and the first six by name also
     * tagged `pinned` so the home screen is not empty on first run. The header
     * of the generated file says exactly that, so it is never a mystery.
     */
    private fun generateApps(available: Map<String, String>): String {
        val all = available.entries
            .sortedBy { it.value.lowercase() }
            .map { (pkg, label) -> AppEntry(pkg, label, listOf(AppsConfigParser.DEFAULT_GROUP)) }

        val pinned = all.take(PINNED_COUNT)
            .map { it.copy(groups = listOf(AppsConfigParser.DEFAULT_GROUP, GROUP_PINNED)) }

        val byPackage = linkedMapOf<String, AppEntry>()
        all.forEach { byPackage[it.pkg] = it }
        pinned.forEach { byPackage[it.pkg] = it }

        val header = buildString {
            appendLine("# pinned = the first $PINNED_COUNT apps by name, just so the screen is")
            appendLine("# not empty on first run. Delete the lines you do not want.")
        }
        return header + AppsConfigParser.format(byPackage.values.toList())
    }

    // ------------------------------------------------------------- writing

    /**
     * Resize while dragging: update in memory only, write once at the end.
     *
     * `dw` widens the tile inside its row, `dh` makes its whole row taller.
     * Shares are relative, so taking more here is exactly what narrows the
     * neighbours — no second edit needed.
     *
     * Reads the current value rather than the one the caller captured, because
     * a drag emits steps faster than recomposition can keep up with.
     */
    fun resize(spec: TileSpec, dw: Int, dh: Int) {
        val current = pending[spec.line]
            ?: layout.tiles.firstOrNull { it.line == spec.line }
            ?: spec
        val next = current.copy(
            w = (current.w + dw).coerceIn(1, MAX_WEIGHT),
            h = (current.h + dh).coerceIn(1, MAX_WEIGHT),
        )
        if (next.w == current.w && next.h == current.h) return
        pending[spec.line] = next
        layout = layout.copy(tiles = layout.tiles.map { if (it.line == spec.line) next else it })
    }

    /**
     * Put the tile on another row. Tiles sharing a row sit side by side, so
     * this is what "stack it to the left/right of that one" means in practice.
     */
    fun moveTileToRow(spec: TileSpec, row: Int) {
        val text = ConfigFiles.read(ConfigFiles.layout) ?: return
        ConfigFiles.write(
            ConfigFiles.layout,
            LayoutDocument(text).withTile(spec.copy(row = row.coerceAtLeast(0))),
        )
        reload()
    }

    /** The drag ended: splice every changed line into layout.conf. */
    fun resizeEnd() {
        if (pending.isEmpty()) return
        var text = ConfigFiles.read(ConfigFiles.layout) ?: run { pending.clear(); return }
        pending.values.forEach { text = LayoutDocument(text).withTile(it) }
        pending.clear()
        ConfigFiles.write(ConfigFiles.layout, text)
        reload()
    }

    fun addTile(spec: TileSpec) {
        val text = ConfigFiles.read(ConfigFiles.layout) ?: return
        ConfigFiles.write(ConfigFiles.layout, LayoutDocument(text).withTile(spec.copy(line = 0)))
        reload()
    }

    fun removeTile(spec: TileSpec) {
        val text = ConfigFiles.read(ConfigFiles.layout) ?: return
        ConfigFiles.write(ConfigFiles.layout, LayoutDocument(text).withoutTile(spec))
        reload()
    }

    fun moveTileToPage(spec: TileSpec, page: Int) {
        val text = ConfigFiles.read(ConfigFiles.layout) ?: return
        ConfigFiles.write(
            ConfigFiles.layout,
            LayoutDocument(text).withTile(spec.copy(page = page)),
        )
        reload()
    }

    fun hideApp(entry: AppEntry) {
        val text = ConfigFiles.read(ConfigFiles.apps) ?: return
        ConfigFiles.write(ConfigFiles.apps, AppsDocument(text).without(entry))
        reload()
    }

    /** A freshly installed app becomes a line in apps.conf, marked as ours. */
    private fun appendInstalled(packageName: String) {
        val text = ConfigFiles.read(ConfigFiles.apps) ?: return
        val label = labels[packageName] ?: AppsConfigParser.labelFrom(packageName)
        val entry = AppEntry(packageName, label, listOf(AppsConfigParser.DEFAULT_GROUP))
        ConfigFiles.write(ConfigFiles.apps, AppsDocument(text).appendIfMissing(entry))
    }

    // -------------------------------------------------------------- intent

    fun launch(packageName: String): Boolean = repo.launch(packageName)

    fun appInfo(packageName: String): Boolean = repo.appInfo(packageName)

    fun openSwitcher() {
        switcherOpen = true
    }

    fun closeSwitcher() {
        switcherOpen = false
    }

    fun factoryReset() {
        ConfigFiles.apps.delete()
        ConfigFiles.layout.delete()
        ConfigFiles.look.delete()
        ConfigFiles.theme.delete()
        reload()
    }

    fun openDrawer() {
        drawerOpen = true
        query = ""
    }

    fun selectTile(spec: TileSpec?) {
        activeTile = spec
    }

    fun editTile(spec: TileSpec) {
        dismissMenus()
        activeTile = spec
        editingTile = spec
    }

    fun openAddMenu() {
        dismissMenus()
        addMenuOpen = true
    }

    fun openThemeMenu() {
        dismissMenus()
        themeMenuOpen = true
    }

    fun openSavesMenu() {
        dismissMenus()
        saves = dev.aura.adosaura.config.Saves.list()
        savesMenuOpen = true
    }

    fun selectSave(name: String) {
        dismissMenus()
        selectedSave = name
    }

    /** Saves the current home screen under [name], or the next free `home-N`. */
    fun saveHome(name: String? = null) {
        val target = name ?: dev.aura.adosaura.config.Saves.nextName(dev.aura.adosaura.config.Saves.list())
        val ok = dev.aura.adosaura.config.Saves.save(target)
        flash(if (ok) "saved $target" else "could not save $target")
    }

    /** Keeps the current screen as `previous` first, so a load is never destructive. */
    fun loadHome(name: String) {
        if (name != dev.aura.adosaura.config.Saves.PREVIOUS) {
            dev.aura.adosaura.config.Saves.save(dev.aura.adosaura.config.Saves.PREVIOUS)
        }
        val ok = dev.aura.adosaura.config.Saves.load(name)
        reload()
        flash(if (ok) "loaded $name" else "could not load $name")
    }

    fun applyPreset(name: String) {
        val ok = dev.aura.adosaura.config.Saves.applyPreset(context, name)
        reload()
        flash(if (ok) "loaded $name" else "could not load $name")
    }

    fun deleteHome(name: String) {
        val ok = dev.aura.adosaura.config.Saves.delete(name)
        flash(if (ok) "deleted $name" else "could not delete $name")
    }

    /** A one-off line in the status strip, cleared by the next reload. */
    private var flashed: String? = null

    /** A one-off line in the status strip that replaces the last one and fades after [ms] (0 = stays). */
    private fun flash(text: String, ms: Long = 4000) {
        scope.launch {
            kotlinx.coroutines.delay(400) // after a reload publishes
            withContext(Dispatchers.Main) {
                notices = notices.filterNot { it == flashed } + text
                flashed = text
            }
            if (ms <= 0) return@launch
            kotlinx.coroutines.delay(ms)
            withContext(Dispatchers.Main) { if (flashed == text) { notices = notices - text; flashed = null } }
        }
    }

    fun setTheme(name: String) {
        ConfigFiles.write(ConfigFiles.theme, name)
        // Also apply immediately just in case reload is racing or dropping it
        val lookText = ConfigFiles.read(ConfigFiles.look).orEmpty()
        val lookKv = dev.aura.adosaura.config.KeyValueParser.parse(lookText).entries
        val panelAlpha = lookKv["panel_alpha"]?.toIntOrNull()?.let { it / 100f } ?: 0.85f
        dev.aura.adosaura.theme.Palette.apply(dev.aura.adosaura.theme.Themes.load(context, name, panelAlpha))
        reload()
    }

    fun setPanelAlpha(alpha: Float) {
        val text = ConfigFiles.read(ConfigFiles.look).orEmpty()
        val newLook = dev.aura.adosaura.config.KeyValueParser.parse(text).entries.toMutableMap()
        newLook["panel_alpha"] = (alpha * 100).toInt().toString()
        ConfigFiles.write(ConfigFiles.look, newLook.map { "${it.key}=${it.value}" }.joinToString("\n"))
        reload()
    }

    fun setCrt(on: Boolean) {
        val text = ConfigFiles.read(ConfigFiles.look).orEmpty()
        val newLook = dev.aura.adosaura.config.KeyValueParser.parse(text).entries.toMutableMap()
        newLook["crt"] = if (on) "on" else "off"
        ConfigFiles.write(ConfigFiles.look, newLook.map { "${it.key}=${it.value}" }.joinToString("\n"))
        reload()
    }

    fun setWallpaper(type: String) {
        val text = ConfigFiles.read(ConfigFiles.look).orEmpty()
        val newLook = dev.aura.adosaura.config.KeyValueParser.parse(text).entries.toMutableMap()
        newLook["wallpaper"] = type
        // Nearly solid panes hide the wallpaper completely, so showing it means glass.
        val alpha = newLook["panel_alpha"]?.toIntOrNull() ?: 85
        if (type == "transparent" && alpha > 70) newLook["panel_alpha"] = "70"
        ConfigFiles.write(ConfigFiles.look, newLook.map { "${it.key}=${it.value}" }.joinToString("\n"))
        reload()
    }

    fun dismissMenus() {
        editingTile = null
        addMenuOpen = false
        themeMenuOpen = false
        savesMenuOpen = false
        selectedSave = null
        optimizeMenuOpen = false
    }

    fun closeDrawer() {
        drawerOpen = false
        query = ""
    }

    /** What pressing Home does: collapse to the first page and close the drawer. */
    fun goHome() {
        closeDrawer()
        closeSwitcher()
        homeSignal++
    }

    /** True while a clean is running, so a second tap does nothing. */
    var cleaning by mutableStateOf(false)
        private set
    var optimizeMenuOpen by mutableStateOf(false)
        private set

    fun openOptimizeMenu() {
        dismissMenus()
        optimizeMenuOpen = true
    }

    fun cleanAllRecents(context: Context) {
        if (cleaning) return
        cleaning = true
        flash("cleaning…", 0)
        scope.launch(Dispatchers.IO) {
            val r = dev.aura.adosaura.switcher.Optimizer(context).run()
            val battery = if (dev.aura.adosaura.switcher.Optimizer.powerSaveOn(context)) "battery saver on" else "hold clean for battery"
            withContext(Dispatchers.Main) {
                cleaning = false
                flash("freed ${r.freedMb} MB · ram ${r.ramBefore}% → ${r.ramAfter}% · $battery", 6000)
            }
            updateOpenAppCount(context)
        }
    }

    private fun updateOpenAppCount(context: Context) {
        val recents = Recents(context)
        if (recents.granted()) {
            openAppCount = recents.recent(labels).size
        }
    }

    companion object {
        const val GROUP_PINNED = "pinned"
        const val PINNED_COUNT = 6
        const val MAX_WEIGHT = 8
    }
}
