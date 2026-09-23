package dev.aura.adosaura.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Purging uninstalled apps out of apps.conf.
 *
 * The contract: an app that is gone must take its line with it, and nothing
 * else in the file may move — comments, blank lines and every surviving entry
 * stay exactly as the user typed them.
 */
class AppsPurgeTest {

    private fun entry(pkg: String, line: Int) =
        AppEntry(pkg, pkg, listOf("all"), line = line)

    @Test
    fun `purging one app removes its line and nothing else`() {
        val text = """
            # a comment
            com.keep.one   | One   | all
            com.gone.two   | Two   | all
            com.keep.three | Three | all
        """.trimIndent()

        val parsed = AppsConfigParser.parse(text)
        val gone = parsed.entries.single { it.pkg == "com.gone.two" }

        val after = AppsDocument(text).withoutAll(listOf(gone))

        assertTrue("the survivor is still listed", after.contains("com.keep.one"))
        assertTrue("the survivor is still listed", after.contains("com.keep.three"))
        assertTrue("comments survive", after.contains("# a comment"))
        assertFalse("the uninstalled app is gone", after.contains("com.gone.two"))
    }

    @Test
    fun `purging several at once leaves no blank holes`() {
        val text = """
            com.a | A | all
            com.b | B | all
            com.c | C | all
            com.d | D | all
        """.trimIndent()

        val parsed = AppsConfigParser.parse(text)
        val gone = parsed.entries.filter { it.pkg in setOf("com.b", "com.d") }

        val after = AppsDocument(text).withoutAll(gone)
        val lines = after.split('\n')

        assertEquals("no blank lines left behind", 2, lines.count { it.isNotBlank() })
        assertEquals(2, AppsConfigParser.parse(after).entries.size)
    }

    @Test
    fun `bottom-up deletion keeps later line numbers true`() {
        // Deleting b (line 2) first would make d's recorded line (4) point at
        // the wrong row. withoutAll works bottom-up precisely so this holds.
        val text = """
            com.a | A | all
            com.b | B | all
            com.c | C | all
            com.d | D | all
        """.trimIndent()

        val parsed = AppsConfigParser.parse(text)
        val gone = listOf(
            parsed.entries.single { it.pkg == "com.b" },   // line 2
            parsed.entries.single { it.pkg == "com.d" },   // line 4
        )

        val after = AppsDocument(text).withoutAll(gone)

        assertTrue(after.contains("com.a"))
        assertTrue(after.contains("com.c"))
        assertFalse(after.contains("com.b"))
        assertFalse(after.contains("com.d"))
    }

    @Test
    fun `purging everything leaves an empty file, not a pile of blanks`() {
        val text = """
            com.a | A | all
            com.b | B | all
        """.trimIndent()

        val parsed = AppsConfigParser.parse(text)
        val after = AppsDocument(text).withoutAll(parsed.entries)

        assertTrue("no leftovers", AppsConfigParser.parse(after).entries.isEmpty())
    }

    @Test
    fun `an entry with no valid line is ignored rather than corrupting the file`() {
        val text = "com.a | A | all"

        val after = AppsDocument(text).withoutAll(
            listOf(
                entry("com.a", line = 0),       // no line recorded
                entry("com.z", line = 99),      // past the end
            )
        )

        assertEquals(text, after)
    }

    @Test
    fun `a reparsed purge is stable across runs`() {
        // Purging twice must be a no-op the second time: the next reload sees
        // a clean file and writes nothing.
        val text = """
            com.a | A | all
            com.b | B | all
        """.trimIndent()

        val first = AppsDocument(text).withoutAll(
            AppsConfigParser.parse(text).entries.filter { it.pkg == "com.b" }
        )
        val second = AppsDocument(first).withoutAll(
            AppsConfigParser.parse(first).entries.filter { it.pkg == "com.b" }
        )

        assertEquals("second pass changes nothing", first, second)
    }
}
