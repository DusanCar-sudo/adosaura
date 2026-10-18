package dev.aura.adosaura.config

import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Hot reload: watch `/sdcard/adosaura` so an `adb push` or a file-manager edit
 * shows up without reinstalling.
 *
 * FileObserver on shared storage can be silently dropped after doze, so this is
 * best-effort by design — the status strip also carries a manual reload. The
 * directory is watched through [DirWatch], because a `file:` or `note:` tile
 * watches it too.
 *
 * @param dir      directory to watch
 * @param debounce collapse bursts (an editor that writes twice in 200 ms should
 *                 trigger one reload, not two)
 * @param onChange called on the main thread when a relevant file changed
 */
class ConfigWatcher(
    private val dir: File,
    private val debounceMs: Long = 200L,
    private val onChange: () -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var watch: AutoCloseable? = null

    private val watchedNames = setOf(
        ConfigFiles.APPS_NAME,
        ConfigFiles.LAYOUT_NAME,
        ConfigFiles.THEME_NAME,
        ConfigFiles.LOOK_NAME,
    )

    private val fire = Runnable { onChange() }

    fun start() {
        if (watch != null) return
        if (!dir.isDirectory) {
            runCatching { dir.mkdirs() }
        }

        watch = runCatching {
            DirWatch.add(dir) { path ->
                // `path == null` means we lost track of which file moved; reload.
                if (path == null || path in watchedNames) {
                    mainHandler.removeCallbacks(fire)
                    mainHandler.postDelayed(fire, debounceMs)
                }
            }
        }.getOrNull()
    }

    fun stop() {
        watch?.close()
        watch = null
        mainHandler.removeCallbacks(fire)
    }
}
