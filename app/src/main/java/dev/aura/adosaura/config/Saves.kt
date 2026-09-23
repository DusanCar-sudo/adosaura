package dev.aura.adosaura.config

import android.content.Context
import java.io.File

/**
 * Saved home screens: `/sdcard/adosaura/saves/<name>/` holding copies of the
 * four config files. Loading one copies them back and reloads, so a save is
 * just a folder you can also copy, rename or share from a file manager.
 */
object Saves {

    /** The files that make up a home screen. A missing one is skipped. */
    val FILES = listOf(
        ConfigFiles.LAYOUT_NAME,
        ConfigFiles.THEME_NAME,
        ConfigFiles.LOOK_NAME,
        ConfigFiles.APPS_NAME,
    )

    /** Written automatically before every load, so a load can be undone. */
    const val PREVIOUS = "previous"

    val dir: File get() = File(ConfigFiles.dir, "saves")

    /** Save names, oldest first by name order, `previous` last. */
    fun list(root: File = dir): List<String> =
        sortNames(root.listFiles()?.filter { it.isDirectory }?.map { it.name }.orEmpty())

    fun save(name: String, from: File = ConfigFiles.dir, root: File = dir): Boolean = runCatching {
        val target = File(root, name)
        target.mkdirs()
        FILES.forEach { f ->
            val src = File(from, f)
            val dst = File(target, f)
            if (src.isFile) src.copyTo(dst, overwrite = true) else dst.delete()
        }
        true
    }.getOrDefault(false)

    /** Copies the save back over the live files. A file the save lacks is deleted, so defaults come back. */
    fun load(name: String, to: File = ConfigFiles.dir, root: File = dir): Boolean = runCatching {
        val source = File(root, name)
        if (!source.isDirectory) return false
        FILES.forEach { f ->
            val src = File(source, f)
            val dst = File(to, f)
            if (src.isFile) src.copyTo(dst, overwrite = true) else dst.delete()
        }
        true
    }.getOrDefault(false)

    fun delete(name: String, root: File = dir): Boolean =
        runCatching { File(root, name).deleteRecursively() }.getOrDefault(false)

    /** `home-N` with the lowest N not already taken. */
    fun nextName(existing: Collection<String>): String {
        var n = 1
        while ("home-$n" in existing) n++
        return "home-$n"
    }

    /** `home-2` before `home-10`, `previous` at the end. */
    fun sortNames(names: Collection<String>): List<String> {
        val (prev, rest) = names.partition { it == PREVIOUS }
        return rest.sortedWith(
            compareBy<String>({ it.substringBeforeLast('-') }, { it.substringAfterLast('-').toIntOrNull() ?: Int.MAX_VALUE }, { it }),
        ) + prev
    }

    /** Bundled starting points under `assets/presets/<name>/`, in menu order. */
    val PRESETS = listOf("minimal", "dashboard", "focus", "two-pages", "retro")

    /**
     * Applies a bundled preset: its layout and theme replace the live ones, its
     * look keys are merged in (so a weather location survives), and apps.conf
     * is never touched. The current screen is kept as `previous` first.
     */
    fun applyPreset(context: Context, name: String): Boolean = runCatching {
        fun asset(f: String) = runCatching {
            context.assets.open("presets/$name/$f").bufferedReader().use { it.readText() }
        }.getOrNull()
        val layout = asset(ConfigFiles.LAYOUT_NAME) ?: return false
        save(PREVIOUS)
        ConfigFiles.write(ConfigFiles.layout, layout)
        asset(ConfigFiles.THEME_NAME)?.let { ConfigFiles.write(ConfigFiles.theme, it.trim()) }
        asset(ConfigFiles.LOOK_NAME)?.let { preset ->
            ConfigFiles.write(ConfigFiles.look, mergeLook(ConfigFiles.read(ConfigFiles.look).orEmpty(), preset))
        }
        true
    }.getOrDefault(false)

    /** [current] look with every key from [preset] set over it. */
    fun mergeLook(current: String, preset: String): String {
        val merged = KeyValueParser.parse(current).entries.toMutableMap()
        merged.putAll(KeyValueParser.parse(preset).entries)
        return merged.map { "${it.key}=${it.value}" }.joinToString("\n")
    }
}
