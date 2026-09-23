package dev.aura.adosaura.config

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * All file IO for the two config files lives here and nowhere else.
 *
 * Both live in `/sdcard/adosaura/` so they can be edited from a file manager
 * and over `adb push` without touching the app. That needs All Files Access
 * (MANAGE_EXTERNAL_STORAGE), granted once by scripts/adb-grants.sh. When the
 * permission is missing we say so in the status strip and fall back to the
 * bundled defaults — never a crash.
 */
object ConfigFiles {

    const val DIR_NAME = "adosaura"
    const val APPS_NAME = "apps.conf"
    const val LAYOUT_NAME = "layout.conf"
    const val THEME_NAME = "theme"
    const val LOOK_NAME = "look"

    val dir: File get() = File(Environment.getExternalStorageDirectory(), DIR_NAME)
    val apps: File get() = File(dir, APPS_NAME)
    val layout: File get() = File(dir, LAYOUT_NAME)
    val theme: File get() = File(dir, THEME_NAME)
    val look: File get() = File(dir, LOOK_NAME)

    /** True when we can actually reach the shared directory. */
    fun accessible(): Boolean = runCatching {
        dir.mkdirs()
        dir.isDirectory && dir.canRead()
    }.getOrDefault(false)

    fun read(file: File): String? = runCatching {
        if (file.isFile) file.readText() else null
    }.getOrNull()

    fun write(file: File, text: String): Boolean = runCatching {
        dir.mkdirs()
        file.writeText(text)
        true
    }.getOrDefault(false)

    /**
     * First run, or whenever a file went missing: drop the bundled default so
     * there is always something to edit. Never overwrites what exists.
     */
    fun seed(context: Context, asset: String, target: File): Boolean {
        if (target.isFile) return false
        return runCatching {
            dir.mkdirs()
            context.assets.open(asset).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            true
        }.getOrDefault(false)
    }

    /** Convenience for the status strip: what to tell the user when we can't see /sdcard. */
    fun accessHint(): String = "no /sdcard access — run scripts/adb-grants.sh"
}
