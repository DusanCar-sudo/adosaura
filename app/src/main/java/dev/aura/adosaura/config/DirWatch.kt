package dev.aura.adosaura.config

import android.os.FileObserver
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One FileObserver per directory, shared by everything that watches it.
 *
 * Android cannot have two: every FileObserver in a process shares one inotify
 * descriptor, the same directory always gets the same watch id, and the
 * framework keeps one observer per id — so a second observer on
 * /sdcard/adosaura silently takes the events from the first, and stopping
 * either one ends the watch for both. The config hot reload and a `file:` or
 * `note:` tile all watch that directory; this is how they coexist.
 */
object DirWatch {

    /** Called on the FileObserver thread with the name of the file inside the directory. */
    fun interface Listener {
        fun onEvent(name: String?)
    }

    private const val MASK = FileObserver.CREATE or FileObserver.MODIFY or FileObserver.DELETE or
        FileObserver.MOVED_TO or FileObserver.MOVED_FROM or FileObserver.CLOSE_WRITE

    private class Shared(dir: File) {
        val listeners = CopyOnWriteArrayList<Listener>()
        val observer = object : FileObserver(dir, MASK) {
            override fun onEvent(event: Int, path: String?) {
                listeners.forEach { it.onEvent(path) }
            }
        }
    }

    private val watched = HashMap<String, Shared>()

    /** Start hearing about changes in [dir]. Close the result to stop. */
    @Synchronized
    fun add(dir: File, listener: Listener): AutoCloseable {
        // /sdcard/x and /storage/emulated/0/x are one directory and one watch id.
        val key = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
        val shared = watched.getOrPut(key) {
            Shared(dir).also { it.observer.startWatching() }
        }
        shared.listeners += listener
        return AutoCloseable { remove(key, listener) }
    }

    @Synchronized
    private fun remove(key: String, listener: Listener) {
        val shared = watched[key] ?: return
        shared.listeners -= listener
        if (shared.listeners.isEmpty()) {
            shared.observer.stopWatching()
            watched -= key
        }
    }
}
