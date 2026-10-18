package dev.aura.adosaura.clean

import java.io.File
import java.util.Locale

/**
 * What the deep clean can remove, and the file rules behind each kind.
 *
 * Pure Kotlin on java.io.File, no Android imports: tested on the JVM in
 * JunkTest. The scanning that needs the package manager lives in DeepCleaner.
 *
 * Every kind is something that is rebuilt on its own or that the user already
 * threw away. Nothing here touches a document, a photo or a download that is
 * still in use. The two kinds that hold real user data, leftovers and trash,
 * start unticked.
 */
enum class Kind(val label: String, val ticked: Boolean, val note: String) {
    CACHE("app caches", true, "rebuilt by each app as it is used"),
    INSTALLERS("installers", true, "apks of apps already installed"),
    THUMBNAILS("thumbnails", true, "previews, redrawn when needed"),
    LOGS("logs", true, "system debug logs"),
    LEFTOVERS("leftovers", false, "media of apps no longer installed"),
    TRASH("trash", false, "deleted files kept for 30 days"),
}

/** One thing to remove: an app's cache, an apk, a folder. */
data class Item(
    val kind: Kind,
    /** What the row shows: an app label, a file or folder name. */
    val name: String,
    val bytes: Long,
    /** Files or folders to delete. Empty for [Kind.CACHE], which the system clears. */
    val paths: List<String> = emptyList(),
)

data class Scan(
    val items: List<Item> = emptyList(),
    /** Bytes really free on /data right now. */
    val free: Long = 0,
    /** The volume's size, rounded the way the box says (128 GB). */
    val total: Long = 0,
    /**
     * Every app's cache together, from one system call, so the total is
     * there before the per-app list. Null when usage access is missing:
     * unknown, not zero.
     */
    val cache: Long? = 0,
    /** Cache Android keeps whatever is asked of it: see [Junk.cacheReserve]. */
    val cacheReserve: Long = 0,
    /** True once [items] holds each app's own cache. */
    val perApp: Boolean = true,
) {
    val cacheSizes: Boolean get() = cache != null

    /** The part of the caches a clean can free: all of it above the reserve. */
    val cacheClearable: Long get() = ((cache ?: 0) - cacheReserve).coerceAtLeast(0)

    fun of(kind: Kind): List<Item> = items.filter { it.kind == kind }

    fun bytes(kind: Kind): Long =
        if (kind == Kind.CACHE) cacheClearable else of(kind).sumOf { it.bytes }

    fun bytes(kinds: Set<Kind>): Long = kinds.sumOf { bytes(it) }

    /** This scan with the per-app list filled in. */
    fun withAppCaches(apps: List<Item>): Scan =
        copy(items = items.filter { it.kind != Kind.CACHE } + apps, perApp = true)
}

object Junk {

    /**
     * Thumbnail caches of old galleries and of the media scanner. Every
     * gallery redraws them on demand; MIUI's lives in Android/data, out of reach.
     */
    val THUMBNAIL_DIRS = listOf("DCIM/.thumbnails", "Pictures/.thumbnails", "Movies/.thumbnails", "Music/.thumbnails")

    /** Debug logs HyperOS and MediaTek builds leave in shared storage. */
    val LOG_DIRS = listOf("MIUI/debug_log", "mtklog", "debuglogger")

    /** AOSP's defaults for the cache reserve, used when Settings.Global has none. */
    const val CACHE_PERCENT = 10
    const val CACHE_MAX = 5L * 1024 * 1024 * 1024

    /**
     * The cache Android keeps when an app asks it for space: [percent] of the
     * [volume], at most [max] bytes (StorageManager.getStorageCacheBytes).
     * Only system apps may clear below it.
     */
    fun cacheReserve(volume: Long, percent: Int = CACHE_PERCENT, max: Long = CACHE_MAX): Long =
        minOf(volume / 100 * percent.coerceAtLeast(0), max).coerceAtLeast(0)

    /** Shared-storage folders a walk never enters: unreadable, or not ours to judge. */
    private val SKIP_DIRS = setOf("Android")

    // ------------------------------------------------------------- formatting

    /**
     * Sizes the way Android's own storage screen counts them: 1 GB is 10^9
     * bytes, so the numbers here and in Settings agree.
     */
    fun size(bytes: Long): String {
        val b = bytes.coerceAtLeast(0)
        // Each bound is where rounding would reach the next unit or precision,
        // so 999.6 MB reads "1.00 GB", never "1000 MB".
        return when {
            b < 1_000 -> "$b B"
            b < 999_500 -> "${(b + 500) / 1_000} KB"
            b < 9_950_000 -> String.format(Locale.ROOT, "%.1f MB", b / 1e6)
            b < 999_500_000 -> "${(b + 500_000) / 1_000_000} MB"
            b < 9_995_000_000 -> String.format(Locale.ROOT, "%.2f GB", b / 1e9)
            b < 99_950_000_000 -> String.format(Locale.ROOT, "%.1f GB", b / 1e9)
            else -> "${(b + 500_000_000) / 1_000_000_000} GB"
        }
    }

    /**
     * The [n] biggest items, then everything else folded into one `+N more`
     * row, so a long list of tiny caches does not push the clean key off screen.
     */
    fun top(items: List<Item>, n: Int): List<Item> {
        val sorted = items.sortedByDescending { it.bytes }
        if (sorted.size <= n + 1) return sorted
        val rest = sorted.drop(n)
        return sorted.take(n) + Item(rest.first().kind, "+${rest.size} more", rest.sumOf { it.bytes })
    }

    // ----------------------------------------------------------------- rules

    /** An apk is spent once the same app, at that version or newer, is installed. */
    fun isSpentInstaller(installedVersion: Long?, apkVersion: Long): Boolean =
        installedVersion != null && installedVersion >= apkVersion

    /** MediaStore's trash: `.trashed-<expiry>-<name>`, purged by the system after 30 days. */
    fun isTrashed(name: String): Boolean = name.startsWith(".trashed-")

    /** `.trashed-1700000000-IMG_1.jpg` -> `IMG_1.jpg`, the name the user knew. */
    fun untrashedName(name: String): String =
        if (isTrashed(name)) name.removePrefix(".trashed-").substringAfter('-') else name

    /**
     * Folders in Android/media named after a package that is not installed:
     * what an uninstalled app left behind. Folders that are not package names
     * are someone else's business and are left alone.
     */
    fun leftovers(mediaDir: File, installed: Set<String>): List<File> =
        mediaDir.listFiles().orEmpty()
            .filter { it.isDirectory && looksLikePackage(it.name) && it.name !in installed }
            .sortedBy { it.name }

    fun looksLikePackage(name: String): Boolean =
        PACKAGE.matches(name)

    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    /** Those of [relative] that exist under [root] as directories. */
    fun existing(root: File, relative: List<String>): List<File> =
        relative.map { File(root, it) }.filter { it.isDirectory }

    // ----------------------------------------------------------------- files

    /** Bytes under [file], without following symlinks out of the tree. */
    fun du(file: File): Long {
        if (isSymlink(file)) return 0
        if (file.isFile) return file.length()
        return file.listFiles().orEmpty().sumOf { du(it) }
    }

    /**
     * Every file under [root] whose name passes [match], skipping Android/ and
     * symlinks. Stops after [limit] hits, so a pathological tree cannot stall
     * the scan.
     */
    fun find(root: File, limit: Int = 5_000, match: (File) -> Boolean): List<File> {
        val hits = ArrayList<File>()
        val stack = ArrayDeque<File>().apply { add(root) }
        while (stack.isNotEmpty() && hits.size < limit) {
            val dir = stack.removeLast()
            for (f in dir.listFiles().orEmpty()) {
                if (isSymlink(f)) continue
                if (f.isDirectory) {
                    if (!(dir == root && f.name in SKIP_DIRS)) stack.add(f)
                } else if (match(f)) {
                    hits += f
                    if (hits.size >= limit) break
                }
            }
        }
        return hits
    }

    /**
     * Delete each path and report the bytes that are really gone: a file that
     * refused to go is not counted.
     */
    fun delete(paths: List<String>): Long = paths.sumOf { path ->
        val f = File(path)
        if (!f.exists()) return@sumOf 0L
        val before = du(f)
        if (f.isDirectory) f.deleteRecursively() else f.delete()
        if (f.exists()) before - du(f) else before
    }

    private fun isSymlink(f: File): Boolean =
        runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)
}
