package dev.aura.adosaura.config

import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Hot reload: watch `/sdcard/adosaura` so an `adb push` or a file-manager edit
 * shows up without reinstalling.
 *
 * FileObserver on shared storage can be silently dropped after doze, so this is
 * best-effort by design — the status strip also carries a manual reload.
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
    private var observer: FileObserver? = null

    private val watchedNames = setOf(
        ConfigFiles.APPS_NAME,
        ConfigFiles.LAYOUT_NAME,
        ConfigFiles.THEME_NAME,
        ConfigFiles.LOOK_NAME,
    )

    private val fire = Runnable { onChange() }

    @Suppress("DEPRECATION")  // the File-based constructor is the only portable one here
    fun start() {
        if (observer != null) return
        if (!dir.isDirectory) {
            runCatching { dir.mkdirs() }
        }

        val callback = { _: Int, path: String? ->
            // `path == null` means we lost track of which file moved; reload.
            if (path == null || path in watchedNames) {
                mainHandler.removeCallbacks(fire)
                mainHandler.postDelayed(fire, debounceMs)
            }
        }

        observer = runCatching {
            object : FileObserver(
                dir.absolutePath,
                CREATE or MODIFY or DELETE or MOVED_TO or MOVED_FROM or CLOSE_WRITE,
            ) {
                @Suppress("DEPRECATION")
                override fun onEvent(event: Int, path: String?) {
                    callback(event, path)
                }
            }
        }.getOrNull().also { it?.startWatching() }
    }

    fun stop() {
        observer?.stopWatching()
        observer = null
        mainHandler.removeCallbacks(fire)
    }
}
