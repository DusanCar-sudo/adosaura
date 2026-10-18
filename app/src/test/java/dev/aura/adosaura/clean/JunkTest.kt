package dev.aura.adosaura.clean

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class JunkTest {

    private fun tmp(): File = Files.createTempDirectory("junk").toFile()

    private fun File.file(path: String, bytes: Int): File =
        File(this, path).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(bytes)) }

    // ---------------------------------------------------------------- size

    @Test fun sizesCountLikeAndroidSettings() {
        assertEquals("0 B", Junk.size(0))
        assertEquals("999 B", Junk.size(999))
        assertEquals("1 KB", Junk.size(1_000))
        assertEquals("48 KB", Junk.size(48_200))
        assertEquals("4.2 MB", Junk.size(4_200_000))
        assertEquals("612 MB", Junk.size(612_400_000))
        assertEquals("2.14 GB", Junk.size(2_140_000_000))
        assertEquals("41.2 GB", Junk.size(41_230_000_000))
        assertEquals("128 GB", Junk.size(128_000_000_000))
        assertEquals("0 B", Junk.size(-5))
    }

    @Test fun roundingStepsUpToTheNextUnitNeverToAThousandOfOne() {
        assertEquals("999 KB", Junk.size(999_499))
        assertEquals("1.0 MB", Junk.size(999_500))
        assertEquals("9.9 MB", Junk.size(9_949_999))
        assertEquals("10 MB", Junk.size(9_960_000))
        assertEquals("1.00 GB", Junk.size(999_600_000))
        assertEquals("10.0 GB", Junk.size(9_996_000_000))
        assertEquals("100 GB", Junk.size(99_960_000_000))
    }

    // ----------------------------------------------------------------- top

    @Test fun topFoldsTheRestIntoOneRow() {
        val items = listOf(5L, 50L, 1L, 30L, 2L).mapIndexed { i, b -> Item(Kind.CACHE, "app$i", b) }
        val shown = Junk.top(items, 2)
        assertEquals(listOf("app1", "app3", "+3 more"), shown.map { it.name })
        assertEquals(8L, shown.last().bytes)
    }

    @Test fun topDoesNotFoldASingleExtraRow() {
        val items = listOf(3L, 2L, 1L).mapIndexed { i, b -> Item(Kind.CACHE, "app$i", b) }
        assertEquals(listOf("app0", "app1", "app2"), Junk.top(items, 2).map { it.name })
        assertTrue(Junk.top(emptyList(), 3).isEmpty())
    }

    // --------------------------------------------------------------- rules

    @Test fun anInstallerIsSpentOnlyWhenThatVersionOrNewerIsInstalled() {
        assertTrue(Junk.isSpentInstaller(installedVersion = 120, apkVersion = 120))
        assertTrue(Junk.isSpentInstaller(installedVersion = 130, apkVersion = 120))
        assertFalse(Junk.isSpentInstaller(installedVersion = 110, apkVersion = 120))
        assertFalse(Junk.isSpentInstaller(installedVersion = null, apkVersion = 120))
    }

    @Test fun trashedFilesKeepTheirOriginalName() {
        assertTrue(Junk.isTrashed(".trashed-1700000000-IMG_1.jpg"))
        assertFalse(Junk.isTrashed("IMG_1.jpg"))
        assertEquals("IMG_1.jpg", Junk.untrashedName(".trashed-1700000000-IMG_1.jpg"))
        assertEquals("a-b-c.mp4", Junk.untrashedName(".trashed-1700000000-a-b-c.mp4"))
        assertEquals("plain.txt", Junk.untrashedName("plain.txt"))
    }

    @Test fun leftoversAreMediaFoldersOfUninstalledPackagesOnly() {
        val media = tmp()
        listOf("com.whatsapp", "org.telegram.messenger", "com.gone.app", "Notes", ".hidden").forEach {
            File(media, it).mkdirs()
        }
        media.file("com.stray.file", 1) // a file, not a folder
        val left = Junk.leftovers(media, installed = setOf("com.whatsapp", "org.telegram.messenger"))
        assertEquals(listOf("com.gone.app"), left.map { it.name })
        assertTrue(Junk.leftovers(File(media, "missing"), emptySet()).isEmpty())
    }

    @Test fun packageNamesNeedADot() {
        assertTrue(Junk.looksLikePackage("com.example.app_2"))
        assertFalse(Junk.looksLikePackage("WhatsApp"))
        assertFalse(Junk.looksLikePackage(".thumbnails"))
        assertFalse(Junk.looksLikePackage("1.2"))
    }

    // --------------------------------------------------------------- files

    @Test fun duSumsATreeAndExistingFindsOnlyFolders() {
        val root = tmp()
        root.file("DCIM/.thumbnails/a", 100)
        root.file("DCIM/.thumbnails/sub/b", 50)
        root.file("Pictures/.thumbnails", 7) // a file squatting on the name
        assertEquals(150L, Junk.du(File(root, "DCIM/.thumbnails")))
        assertEquals(listOf(File(root, "DCIM/.thumbnails")), Junk.existing(root, Junk.THUMBNAIL_DIRS))
    }

    @Test fun findSkipsAndroidAndSymlinks() {
        val root = tmp()
        root.file("Download/old.apk", 10)
        root.file("Download/deep/er/new.APK", 10)
        root.file("Android/media/x/hidden.apk", 10)
        root.file("Download/readme.txt", 10)
        val outside = tmp().apply { file("loop.apk", 10) }
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        val hits = Junk.find(root) { it.name.endsWith(".apk", ignoreCase = true) }
        assertEquals(setOf("old.apk", "new.APK"), hits.map { it.name }.toSet())
    }

    @Test fun findStopsAtItsLimit() {
        val root = tmp()
        repeat(10) { root.file("d/$it.apk", 1) }
        assertEquals(3, Junk.find(root, limit = 3) { true }.size)
    }

    @Test fun deleteRemovesFilesAndFoldersAndCountsWhatWentAway() {
        val root = tmp()
        val apk = root.file("Download/old.apk", 300)
        root.file("DCIM/.thumbnails/a", 100)
        root.file("DCIM/.thumbnails/b/c", 20)
        val keep = root.file("DCIM/Camera/IMG.jpg", 999)
        val freed = Junk.delete(listOf(apk.path, File(root, "DCIM/.thumbnails").path, File(root, "nope").path))
        assertEquals(420L, freed)
        assertFalse(apk.exists())
        assertFalse(File(root, "DCIM/.thumbnails").exists())
        assertTrue(keep.exists())
    }

    // ---------------------------------------------------------------- scan

    @Test fun scanTotalsByKind() {
        val scan = Scan(
            listOf(
                Item(Kind.INSTALLERS, "x.apk", 7),
                Item(Kind.TRASH, "t", 1000),
            ),
            cache = 150,
        )
        assertEquals(150L, scan.bytes(Kind.CACHE))
        assertEquals(157L, scan.bytes(setOf(Kind.CACHE, Kind.INSTALLERS)))
        assertEquals(0L, scan.bytes(Kind.LOGS))
    }

    @Test fun theCacheTotalStandsBeforeAndAfterThePerAppList() {
        val fast = Scan(listOf(Item(Kind.LOGS, "MIUI/debug_log", 5)), cache = 900, perApp = false)
        assertTrue(fast.of(Kind.CACHE).isEmpty())
        assertEquals(900L, fast.bytes(Kind.CACHE))

        val full = fast.withAppCaches(listOf(Item(Kind.CACHE, "Chrome", 600), Item(Kind.CACHE, "Maps", 200)))
        assertTrue(full.perApp)
        assertEquals(listOf("Chrome", "Maps"), full.of(Kind.CACHE).map { it.name })
        // The system's total counts what no single app owns, so it stays the figure.
        assertEquals(900L, full.bytes(Kind.CACHE))
        assertEquals(5L, full.bytes(Kind.LOGS))
    }

    @Test fun aCleanFreesTheCacheAboveWhatAndroidKeeps() {
        val scan = Scan(cache = 8_670_000_000, cacheReserve = 5_368_709_120)
        assertEquals(3_301_290_880L, scan.bytes(Kind.CACHE))
        assertEquals(0L, Scan(cache = 4_000_000_000, cacheReserve = 5_368_709_120).bytes(Kind.CACHE))
    }

    @Test fun theReserveIsTenPercentUpToFiveGib() {
        val fiveGib = 5L * 1024 * 1024 * 1024
        assertEquals(fiveGib, Junk.cacheReserve(241_595_035_648))
        assertEquals(3_200_000_000L, Junk.cacheReserve(32_000_000_000))
        assertEquals(0L, Junk.cacheReserve(241_595_035_648, max = 0))
        assertEquals(0L, Junk.cacheReserve(241_595_035_648, percent = 0))
        assertEquals(1_000_000_000L, Junk.cacheReserve(64_000_000_000, percent = 5, max = 1_000_000_000))
    }

    @Test fun withoutUsageAccessTheCacheIsUnknownNotZero() {
        val scan = Scan(cache = null)
        assertFalse(scan.cacheSizes)
        assertEquals(0L, scan.bytes(Kind.CACHE))
        assertTrue(Scan().cacheSizes)
    }

    @Test fun onlyTheKindsThatHoldRealUserDataStartUnticked() {
        assertEquals(setOf(Kind.LEFTOVERS, Kind.TRASH), Kind.entries.filter { !it.ticked }.toSet())
    }
}
