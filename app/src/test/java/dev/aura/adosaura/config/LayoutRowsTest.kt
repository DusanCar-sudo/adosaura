package dev.aura.adosaura.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rows: tiles sharing a row sit side by side. This is what makes left/right
 * arrangement possible at all, so it gets its own tests — and the older
 * `top` / `main` grammar has to keep parsing exactly as it always did.
 */
class LayoutRowsTest {

    @Test
    fun `tiles on one row sit side by side`() {
        val layout = LayoutConfigParser.parse(
            """
            0 0 apps:pinned 1 2
            0 0 clockcal    1 2
            """.trimIndent()
        )

        assertEquals("one row", 1, layout.rows(0).size)
        assertEquals("two tiles in it", 2, layout.rows(0)[0]?.size)
    }

    @Test
    fun `tiles on different rows stack downwards`() {
        val layout = LayoutConfigParser.parse(
            """
            0 0 stats       1 1
            0 1 apps:all    1 1
            """.trimIndent()
        )

        assertEquals("two rows", 2, layout.rows(0).size)
        assertTrue("each row has one tile", layout.rows(0).values.all { it.size == 1 })
    }

    @Test
    fun `width share is per tile, height share is per row`() {
        val layout = LayoutConfigParser.parse("0 0 apps:all 3 2")

        val tile = layout.tiles.single()
        assertEquals("wide", 3, tile.w)
        assertEquals("tall", 2, tile.h)
    }

    @Test
    fun `height defaults to one when left out`() {
        val tile = LayoutConfigParser.parse("0 0 clock 2").tiles.single()
        assertEquals(2, tile.w)
        assertEquals(1, tile.h)
    }

    @Test
    fun `a mismatched height in one row is reported, not silently obeyed`() {
        val layout = LayoutConfigParser.parse(
            """
            0 0 apps:all 1 1
            0 0 apps:pinned 1 3
            """.trimIndent()
        )

        assertEquals("tiles still parse", 2, layout.tiles.size)
        assertEquals("but the row says so", 1, layout.problems.size)
    }

    // -------------------------------------------------- the older grammar

    @Test
    fun `old top and main still parse`() {
        val layout = LayoutConfigParser.parse(
            """
            0 top  stats        1
            0 top  calendar     1
            0 top  clock        1
            0 main apps:pinned  1
            0 main clockcal     2
            0 main apps:all     1
            """.trimIndent()
        )

        assertEquals("no complaints about a valid old file", 0, layout.problems.size)
        assertEquals("six tiles", 6, layout.tiles.size)

        val rows = layout.rows(0)
        assertEquals("top is row 0 with three side by side", 3, rows[0]?.size)
        assertEquals("main stacks one per row", 4, rows.size)
    }

    @Test
    fun `old main keeps its height semantics - weight 2 is the tall one`() {
        val layout = LayoutConfigParser.parse(
            """
            0 main apps:pinned 1
            0 main clockcal    2
            0 main apps:all     1
            """.trimIndent()
        )

        val tall = layout.tiles.single { it.type == "clockcal" }
        assertEquals("still the tall middle tile", 2, tall.h)
        assertEquals("and it is alone in its row", 1, tall.w)
    }

    @Test
    fun `old top keeps its width semantics`() {
        val wide = LayoutConfigParser.parse("0 top stats 3").tiles.single()
        assertEquals(3, wide.w)
        assertEquals("top rows are one band", 0, wide.row)
    }

    @Test
    fun `rows are per page, so two pages can both have a row 0`() {
        val layout = LayoutConfigParser.parse(
            """
            0 0 clock 1 1
            1 0 clock 1 1
            """.trimIndent()
        )

        assertEquals(1, layout.rows(0).size)
        assertEquals(1, layout.rows(1).size)
        assertEquals(2, layout.pageCount)
    }

    // ------------------------------------------------------------- writing

    @Test
    fun `rendering comes out in the new grammar`() {
        val spec = TileSpec(page = 0, row = 1, type = "apps", arg = "pinned", w = 2, h = 3)
        assertEquals("0 1 apps:pinned 2 3", LayoutConfigParser.render(spec))
    }

    @Test
    fun `a round trip through render and parse keeps the shape`() {
        val spec = TileSpec(page = 2, row = 5, type = "clockcal", w = 4, h = 2)
        val back = LayoutConfigParser.parse(LayoutConfigParser.render(spec)).tiles.single()

        assertEquals(spec.page, back.page)
        assertEquals(spec.row, back.row)
        assertEquals(spec.type, back.type)
        assertEquals(spec.w, back.w)
        assertEquals(spec.h, back.h)
    }

    @Test
    fun `an unknown row is skipped and reported`() {
        val layout = LayoutConfigParser.parse("0 sideways clock 1 1")
        assertEquals(0, layout.tiles.size)
        assertEquals(1, layout.problems.size)
    }

    @Test
    fun `a negative width is skipped and reported`() {
        val layout = LayoutConfigParser.parse("0 0 clock -2 1")
        assertEquals(0, layout.tiles.size)
        assertEquals(1, layout.problems.size)
    }
}
