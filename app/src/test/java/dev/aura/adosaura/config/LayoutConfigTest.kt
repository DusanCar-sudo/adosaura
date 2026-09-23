package dev.aura.adosaura.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `layout.conf` is the other half of the launcher's public API: the shape of
 * the home screen, described in text and editable over adb.
 * Tests the backward-compatible `top`/`main` grammar as well as the new row format.
 */
class LayoutConfigTest {

    private fun parse(text: String) = LayoutConfigParser.parse(text)

    // --------------------------------------------------------------- happy path

    @Test
    fun `a full line is read as written`() {
        val config = parse("0 main clockcal 2\n")

        val tile = config.tiles.single()
        assertEquals(0, tile.page)
        assertEquals(1, tile.row)  // main is assigned row 1
        assertEquals("clockcal", tile.type)
        assertNull(tile.arg)
        assertEquals(1, tile.w)
        assertEquals(2, tile.h)
        assertEquals(1, tile.line)
    }

    @Test
    fun `a type may carry an argument`() {
        val config = parse("0 top apps:social 1\n")

        val tile = config.tiles.single()
        assertEquals("apps", tile.type)
        assertEquals("social", tile.arg)
        assertEquals(0, tile.row) // top is row 0
    }

    @Test
    fun `an argument containing dots survives`() {
        val config = parse("0 main apps:com.example.group 1\n")

        assertEquals("apps", config.tiles.single().type)
        assertEquals("com.example.group", config.tiles.single().arg)
    }

    @Test
    fun `weight and span may be omitted and default to one`() {
        val config = parse("0 main clock\n")

        val tile = config.tiles.single()
        assertEquals(1, tile.w)
        assertEquals(1, tile.h)
    }

    @Test
    fun `span multiplies weight, so the grammar spells the same size two ways`() {
        val bySpan = parse("0 main clock 1 2\n").tiles.single()
        val byWeight = parse("0 main clock 2\n").tiles.single()

        assertEquals(2, bySpan.h)
        assertEquals(2, byWeight.h)
    }

    // ------------------------------------------------------------------ noise

    @Test
    fun `comments and blank lines are ignored`() {
        val config = parse(
            """
            # header

            0 top clock 1     # inline comment
            # trailing comment
            0 main stats 1
            """.trimMargin(),
        )

        assertEquals(2, config.tiles.size)
        assertTrue(config.problems.isEmpty())
    }

    @Test
    fun `CRLF does not break the last field`() {
        val config = parse("0 main clock 2\r\n")

        assertEquals(2, config.tiles.single().h)
        assertTrue(config.problems.isEmpty())
    }

    @Test
    fun `tabs work as well as spaces`() {
        val config = parse("0\tmain\tclock\t2\n")

        assertEquals(2, config.tiles.single().h)
    }

    // ------------------------------------------------------------- bad input

    @Test
    fun `a non-numeric page is skipped and reported`() {
        val config = parse("x main clock 1\n")

        assertTrue(config.tiles.isEmpty())
        assertEquals(1, config.problems.size)
    }

    @Test
    fun `an unknown row keyword is skipped and reported`() {
        val config = parse("0 sideways clock 1\n")

        assertTrue(config.tiles.isEmpty())
        assertEquals(1, config.problems.size)
    }

    @Test
    fun `a non-numeric weight is skipped rather than silently ignored`() {
        val config = parse("0 main clock wide\n")

        assertTrue(config.tiles.isEmpty())
        assertEquals(1, config.problems.size)
    }

    @Test
    fun `a line too short to name a tile is reported`() {
        val config = parse("0 main\n")

        assertTrue(config.tiles.isEmpty())
        assertEquals(1, config.problems.size)
    }

    @Test
    fun `one bad line never takes the rest with it`() {
        val config = parse("0 top clock 1\nbogus\n0 main stats 1\n")

        assertEquals(2, config.tiles.size)
        assertEquals(1, config.problems.size)
    }

    // -------------------------------------------------------------- structure

    @Test
    fun `pages are implicit and run to the highest one named`() {
        val config = parse("0 main clock 1\n2 main stats 1\n")

        assertEquals(3, config.pageCount)
        assertEquals(1, config.onPage(0).size)
        assertEquals(0, config.onPage(1).size)
    }

    @Test
    fun `an empty layout is a blank screen and no error`() {
        val config = parse("")

        assertEquals(1, config.pageCount)
        assertTrue(config.tiles.isEmpty())
        assertTrue(config.problems.isEmpty())
    }

    @Test
    fun `row filters within a page`() {
        val config = parse("0 top clock 1\n0 main stats 1\n")

        // top -> row 0, main -> row 1
        assertEquals(listOf("clock"), config.tiles.filter { it.row == 0 }.map { it.type })
        assertEquals(listOf("stats"), config.tiles.filter { it.row == 1 }.map { it.type })
    }

    // ------------------------------------------------------------- round trip

    @Test
    fun `render reads back exactly`() {
        val spec = TileSpec(3, 0, "apps", "work", 2, 3, 7) // row 0, w=2, h=3

        val again = parse(LayoutConfigParser.render(spec) + "\n").tiles.single()

        assertEquals(spec.copy(line = 1), again)
    }

    @Test
    fun `render omits h when it is one, keeping the file tidy`() {
        assertEquals("0 1 clock 2 1", LayoutConfigParser.render(TileSpec(0, 1, "clock", null, 2, 1)))
    }
}

/**
 * Editing the layout must never rewrite what the user wrote: comments stay,
 * line numbers stay, and only the one line that changed is touched.
 */
class LayoutDocumentTest {

    private val source = """
        |# my layout
        |0 0 clock 1 1
        |
        |0 1 stats  1 1
        |""".trimMargin() + "\n"

    private fun parse(text: String) = LayoutConfigParser.parse(text)
    private fun parseAll() = parse(source).tiles

    @Test
    fun `resizing a tile rewrites only its own line`() {
        val stats = parseAll().first { it.type == "stats" }

        val out = LayoutDocument(source).withTile(stats.copy(h = 3))
        val lines = out.split('\n')

        assertEquals("# my layout", lines[0])
        assertEquals("0 0 clock 1 1", lines[1])
        assertEquals("", lines[2])
        assertEquals("0 1 stats 1 3", lines[3])
    }

    @Test
    fun `a comment and a blank line survive an edit`() {
        val clock = parseAll().first { it.type == "clock" }

        val out = LayoutDocument(source).withTile(clock.copy(h = 2))

        assertTrue(out.contains("# my layout"))
        assertTrue(out.contains("\n\n"))
    }

    @Test
    fun `removing a tile blanks its line so the others keep their line numbers`() {
        val clock = parseAll().first { it.type == "clock" }

        val out = LayoutDocument(source).withoutTile(clock)
        val remaining = parse(out)

        assertEquals(1, remaining.tiles.size)
        assertEquals(4, remaining.tiles.single().line)
    }

    @Test
    fun `a tile created in memory is appended, not spliced into a stranger's line`() {
        val out = LayoutDocument(source).withTile(TileSpec(0, 1, "calendar", null, 1, 1))

        assertTrue(out.contains("0 1 calendar 1 1"))
        assertEquals(3, parse(out).tiles.size)
    }

    @Test
    fun `an empty file can be seeded with its first tile`() {
        val out = LayoutDocument("").withTile(TileSpec(0, 0, "clock", null, 1, 1))

        assertEquals(1, parse(out).tiles.size)
    }
}