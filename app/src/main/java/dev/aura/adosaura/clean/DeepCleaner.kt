package dev.aura.adosaura.clean

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.provider.Settings
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The deep clean — Xiaomi's "Cleaner", within what a launcher may do.
 *
 * Almost all of the gigabytes a phone cleaner frees are app caches. A normal
 * app cannot delete another app's cache, but any app may ask the system for
 * free space with StorageManager.allocateBytes, and the system makes the room
 * by deleting other apps' cached files, oldest first. Asking for all the
 * space it could give clears every cache down to the reserve Android always
 * keeps ([Junk.cacheReserve]); only system apps may go below it.
 *
 * Not ACTION_CLEAR_APP_CACHE: that system dialog only empties the caches in
 * Android/data, a few hundred MB, and leaves the internal ones where the
 * gigabytes are.
 *
 * Everything else is plain files in shared storage, found here and deleted
 * with all files access, which the launcher already has for its config
 * (scripts/adb-grants.sh): installers of apps already installed, thumbnail
 * caches, debug logs, the media of uninstalled apps and the 30-day trash.
 * Android/data and Android/obb are closed to every app since Android 11 and
 * are never touched.
 */
class DeepCleaner(private val context: Context) {

    private val pm = context.packageManager
    private val stats = context.getSystemService(StorageStatsManager::class.java)
    private val storage = context.getSystemService(StorageManager::class.java)
    private val root: File = Environment.getExternalStorageDirectory()

    /** All files access: without it only the app caches can be cleared. */
    fun filesAccess(): Boolean = Environment.isExternalStorageManager()

    /**
     * Have the system delete other apps' caches, down to its reserve, by
     * asking it for every byte it could free. Blocks for seconds. False when
     * the system refused; asked twice, since another app writing between the
     * two calls makes the first ask too big.
     */
    fun clearCaches(): Boolean = (1..2).any {
        runCatching {
            val uuid = StorageManager.UUID_DEFAULT
            storage.allocateBytes(uuid, storage.getAllocatableBytes(uuid))
        }.isSuccess
    }

    /**
     * Bytes really free. Not StorageStatsManager.getFreeBytes, which counts
     * clearable cache as free already, so clearing it would show nothing.
     */
    fun free(): Long = runCatching { StatFs(Environment.getDataDirectory().path).availableBytes }.getOrDefault(0)

    private fun total(): Long =
        runCatching { stats.getTotalBytes(StorageManager.UUID_DEFAULT) }.getOrDefault(0)

    /** [Junk.cacheReserve] for /data, with the device's own settings if it has any. */
    private fun cacheReserve(): Long {
        val r = context.contentResolver
        return Junk.cacheReserve(
            volume = Environment.getDataDirectory().totalSpace,
            percent = Settings.Global.getInt(r, "sys_storage_cache_percentage", Junk.CACHE_PERCENT),
            max = Settings.Global.getLong(r, "sys_storage_cache_max_bytes", Junk.CACHE_MAX),
        )
    }

    /**
     * Find everything but the per-app cache list, which [appCaches] adds:
     * the caches' total comes from one call, so this takes a second or two.
     */
    fun scan(onStep: (String) -> Unit = {}): Scan {
        val installed = installed()
        onStep("app caches")
        val cache = runCatching {
            stats.queryStatsForUser(StorageManager.UUID_DEFAULT, Process.myUserHandle()).cacheBytes
        }.getOrNull()
        val items = buildList {
            if (filesAccess()) {
                onStep("installers")
                addAll(installers())
                onStep("thumbnails")
                addAll(folders(Kind.THUMBNAILS, Junk.existing(root, Junk.THUMBNAIL_DIRS)))
                onStep("logs")
                addAll(folders(Kind.LOGS, Junk.existing(root, Junk.LOG_DIRS)))
                onStep("leftovers")
                val names = installed.mapTo(HashSet()) { it.packageName }
                addAll(folders(Kind.LEFTOVERS, Junk.leftovers(File(root, "Android/media"), names)))
                onStep("trash")
                addAll(trash())
            }
        }
        return Scan(
            items = items.filter { it.bytes > 0 }.sortedByDescending { it.bytes },
            free = free(),
            total = total(),
            cache = cache,
            cacheReserve = cacheReserve(),
            perApp = false,
        )
    }

    /**
     * Each app's cache, internal and external, biggest first. One system
     * call per app, so it runs on a few threads and reports [onProgress].
     * Only the biggest get their label looked up: that loads the app's
     * resources and costs more than the size itself.
     *
     * Gives up on apps still being sized after [SIZING_SECONDS]: one app
     * whose files Android has to walk can take a minute alone. They are left
     * out of the list; the total from [scan] still counts them.
     */
    fun appCaches(labels: Map<String, String>, onProgress: (done: Int, of: Int) -> Unit = { _, _ -> }): List<Item> {
        val apps = installed()
        val user = Process.myUserHandle()
        val lock = Any()
        var done = 0
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            val sizes = pool.invokeAll(
                apps.map { info ->
                    Callable {
                        // An app uninstalled mid-scan, or one on a missing SD card, counts as empty.
                        val bytes = runCatching {
                            stats.queryStatsForPackage(info.storageUuid, info.packageName, user).cacheBytes
                        }.getOrDefault(0L)
                        // In order, so the count never steps back.
                        synchronized(lock) { onProgress(++done, apps.size) }
                        info to bytes
                    }
                },
                SIZING_SECONDS,
                TimeUnit.SECONDS,
            ).mapNotNull { f -> if (f.isCancelled) null else runCatching { f.get() }.getOrNull() }
            return sizes.filter { it.second > 0 }
                .sortedByDescending { it.second }
                .mapIndexed { i, (info, bytes) ->
                    val name = if (i < NAMED) labels[info.packageName] ?: label(info) else info.packageName
                    Item(Kind.CACHE, name, bytes)
                }
        } finally {
            pool.shutdown()
        }
    }

    private fun installed(): List<ApplicationInfo> =
        runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())

    private fun label(info: ApplicationInfo): String =
        runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(info.packageName)

    /**
     * Apks in shared storage whose app is already installed at that version
     * or newer. An apk of an app that is not installed is someone's spare
     * copy and stays.
     */
    private fun installers(): List<Item> =
        (mediaFiles("_data LIKE ?", arrayOf("%.apk")) ?: Junk.find(root) { it.name.endsWith(".apk", ignoreCase = true) })
            .mapNotNull { apk ->
                val archive = runCatching { pm.getPackageArchiveInfo(apk.path, 0) }.getOrNull() ?: return@mapNotNull null
                val have = runCatching { pm.getPackageInfo(archive.packageName, 0).longVersionCode }.getOrNull()
                if (!Junk.isSpentInstaller(have, archive.longVersionCode)) return@mapNotNull null
                Item(Kind.INSTALLERS, apk.name, apk.length(), listOf(apk.path))
            }

    /** MediaStore's trash, which a normal query hides. */
    private fun trash(): List<Item> {
        val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY) }
        return query(args).orEmpty().filter { Junk.isTrashed(it.name) }
            .map { Item(Kind.TRASH, Junk.untrashedName(it.name), it.length(), listOf(it.path)) }
    }

    private fun mediaFiles(selection: String, selectionArgs: Array<String>): List<File>? {
        val args = Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
        }
        return query(args)
    }

    /** Files from MediaStore that still exist on disk; null when MediaStore would not answer. */
    private fun query(args: Bundle): List<File>? = runCatching {
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), args, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    c.getString(0)?.let { File(it) }?.takeIf { it.isFile }?.let(::add)
                }
            }
        }
    }.getOrNull()

    private fun folders(kind: Kind, dirs: List<File>): List<Item> =
        dirs.map {
            // A leftover is known by its app; a cache folder by where it is.
            val name = if (kind == Kind.LEFTOVERS) it.name else it.relativeTo(root).path
            Item(kind, name, Junk.du(it), listOf(it.path))
        }

    /**
     * Delete the files of [items] and tell MediaStore, so galleries and file
     * pickers stop listing them. Returns the bytes really removed.
     */
    fun delete(items: List<Item>): Long {
        val paths = items.flatMap { it.paths }
        if (paths.isEmpty()) return 0
        val freed = Junk.delete(paths)
        runCatching { MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null) }
        return freed
    }

    private companion object {
        /** installd answers size queries in parallel; more threads stop helping. */
        const val THREADS = 4

        /** Apps whose label is looked up; the rest only ever show as `+N more`. */
        const val NAMED = 10

        /** How long the per-app list waits for the slowest app. */
        const val SIZING_SECONDS = 10L
    }
}
