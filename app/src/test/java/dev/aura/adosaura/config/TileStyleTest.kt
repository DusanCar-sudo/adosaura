package dev.aura.adosaura.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TileStyleTest {

    @Test fun styleTokensParseInAnyOrder() {
        val t = LayoutConfigParser.parse("0 1 clock 2 2 size=130 fg=FFD466 bg=red").tiles.single()
        assertEquals("red", t.bg)
        assertEquals("ffd466", t.fg)
        assertEquals(130, t.size)
        assertEquals(2, t.w)
    }

    @Test fun renderRoundTripsStyleAndOmitsDefaults() {
        val plain = TileSpec(0, 1, "clock", w = 2, h = 2)
        assertEquals("0 1 clock 2 2", LayoutConfigParser.render(plain))
        val styled = plain.copy(bg = "0f1f3a", fg = "accent", size = 115)
        val back = LayoutConfigParser.parse(LayoutConfigParser.render(styled)).tiles.single()
        assertEquals(styled.copy(line = 1), back)
    }

    @Test fun badStyleKeepsTheTileAndReportsIt() {
        val c = LayoutConfigParser.parse("0 0 stats 1 1 bg=mauve size=huge glow=on")
        assertEquals(1, c.tiles.size)
        assertNull(c.tiles.single().bg)
        assertEquals(100, c.tiles.single().size)
        assertEquals(3, c.problems.size)
    }

    @Test fun sizeIsClampedAndStepped() {
        assertEquals(200, LayoutConfigParser.parse("0 0 clock 1 1 size=999").tiles.single().size)
        assertEquals(115, TileStyle.step(100, 1))
        assertEquals(TileStyle.MIN_SIZE, TileStyle.step(65, -1))
    }

    @Test fun hashHexIsACommentSoItIsNotWrittenThatWay() {
        assertTrue(TileStyle.colour("#ff0000") == "ff0000")
        assertEquals("0 0 clock 1 1 fg=ff0000", LayoutConfigParser.render(TileSpec(0, 0, "clock", fg = TileStyle.colour("#ff0000"))))
    }
}
