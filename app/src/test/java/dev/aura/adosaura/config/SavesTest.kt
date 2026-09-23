package dev.aura.adosaura.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SavesTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun nextNameFillsTheFirstGap() {
        assertEquals("home-1", Saves.nextName(emptyList()))
        assertEquals("home-2", Saves.nextName(listOf("home-1", "home-3")))
    }

    @Test fun sortIsNumericAndPreviousGoesLast() {
        assertEquals(
            listOf("home-2", "home-10", "previous"),
            Saves.sortNames(listOf("previous", "home-10", "home-2")),
        )
    }

    @Test fun saveThenLoadRoundTrips() {
        val live = tmp.newFolder("live")
        val root = tmp.newFolder("saves")
        File(live, ConfigFiles.LAYOUT_NAME).writeText("0 top clock 1")
        File(live, ConfigFiles.THEME_NAME).writeText("nord")

        assertTrue(Saves.save("home-1", live, root))
        File(live, ConfigFiles.LAYOUT_NAME).writeText("0 top stats 1")
        File(live, ConfigFiles.THEME_NAME).delete()
        File(live, ConfigFiles.LOOK_NAME).writeText("panel_alpha=60")

        assertTrue(Saves.load("home-1", live, root))
        assertEquals("0 top clock 1", File(live, ConfigFiles.LAYOUT_NAME).readText())
        assertEquals("nord", File(live, ConfigFiles.THEME_NAME).readText())
        // Not in the save, so it goes back to defaults.
        assertFalse(File(live, ConfigFiles.LOOK_NAME).exists())
        assertEquals(listOf("home-1"), Saves.list(root))
    }

    @Test fun loadingAMissingSaveChangesNothing() {
        val live = tmp.newFolder("live")
        File(live, ConfigFiles.LAYOUT_NAME).writeText("keep")
        assertFalse(Saves.load("nope", live, tmp.newFolder("saves")))
        assertEquals("keep", File(live, ConfigFiles.LAYOUT_NAME).readText())
    }
}
