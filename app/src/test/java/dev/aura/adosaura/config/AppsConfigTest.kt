package dev.aura.adosaura.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `apps.conf` is the launcher's public API: an agent can edit it over adb and
 * expect a specific result. These tests are what make that promise true.
 */
class AppsConfigTest {

    private fun parse(text: String) = AppsConfigParser.parse(text)

    // --------------------------------------------------------------- happy path

    @Test
    fun `a fully specified line is read as written`() {
        val config = parse("com.android.chrome | Chrome | pinned,top\n")

        assertEquals(1, config.entries.size)
        val entry = config.entries.single()
        assertEquals("com.android.chrome", entry.pkg)
        assertEquals("Chrome", entry.label)
        assertEquals(listOf("pinned", "top"), entry.groups)
        assertEquals(1, entry.line)
        assertTrue(config.problems.isEmpty())
    }

    @Test
    fun `a bare package line gets a derived label and the default group`() {
        val config = parse("com.example.my_app\n")

        val entry = config.entries.single()
        assertEquals("My app", entry.label)
        assertEquals(listOf("all"), entry.groups)
    }

    @Test
    fun `a missing label falls back to the derived one`() {
        val config = parse("com.example.thing |  | tools\n")

        assertEquals("Thing", config.entries.single().label)
        assertEquals(listOf("tools"), config.entries.single().groups)
    }

    @Test
    fun `several groups may be separated by commas or spaces`() {
        val config = parse("com.a.b | B | work dev, tools\n")

        assertEquals(listOf("work", "dev", "tools"), config.entries.single().groups)
    }

    // ------------------------------------------------------------------ noise

    @Test
    fun `comments and blank lines are ignored and keep line numbers honest`() {
        val config = parse(
            """
            |# the header
            |
            |com.a.b | B | all
            |   # an indented comment
            |com.c.d | D | all
            |""".trimMargin(),
        )

        assertEquals(2, config.entries.size)
        assertEquals(3, config.entries[0].line)
        assertEquals(5, config.entries[1].line)
        assertTrue(config.problems.isEmpty())
    }

    @Test
    fun `an inline comment is stripped from a real line`() {
        val config = parse("com.a.b | B | all   # my browser\n")

        assertEquals("com.a.b", config.entries.single().pkg)
        assertTrue(config.problems.isEmpty())
    }

    @Test
    fun `CRLF from an adb push on another machine does not corrupt the label`() {
        val config = parse("com.a.b | B | all\r\ncom.c.d | D | all\r\n")

        assertEquals(listOf("B", "D"), config.entries.map { it.label })
        assertTrue(config.problems.isEmpty())
    }

    @Test
    fun `an empty file is simply empty, never an error`() {
        val config = parse("")

        assertTrue(config.entries.isEmpty())
        assertTrue(config.problems.isEmpty())
    }

    // ------------------------------------------------------------- bad input

    @Test
    fun `a duplicate package keeps the first entry and reports the later one`() {
        val config = parse("com.a.b | first | all\ncom.a.b | second | all\n")

        assertEquals(1, config.entries.size)
        assertEquals("first", config.entries.single().label)
        assertEquals(1, config.problems.size)
        assertTrue(config.problems.single().reason.contains("duplicate"))
    }

    @Test
    fun `a line that is not a package name is skipped and reported`() {
        val config = parse("justsomeword | nope | all\n")

        assertTrue(config.entries.isEmpty())
        assertEquals(1, config.problems.size)
    }

    @Test
    fun `one bad line does not take the rest of the file with it`() {
        val config = parse("com.a.b | B | all\nnonsense\ncom.c.d | D | all\n")

        assertEquals(2, config.entries.size)
        assertEquals(1, config.problems.size)
    }

    @Test
    fun `a line with only separators is reported rather than trusted`() {
        val config = parse("   |   |   \n")

        assertTrue(config.entries.isEmpty())
        assertEquals(1, config.problems.size)
    }

    // -------------------------------------------------------------- helpers

    @Test
    fun `package names accept dots and reject paths and whitespace`() {
        assertTrue(AppsConfigParser.isPackageName("com.example.app2"))
        assertFalse(AppsConfigParser.isPackageName("nodots"))
        assertFalse(AppsConfigParser.isPackageName("com.example.app "))
        assertFalse(AppsConfigParser.isPackageName("com/example/app"))
    }

    @Test
    fun `derived labels come from the last segment, tidied`() {
        assertEquals("Chrome", AppsConfigParser.labelFrom("com.android.chrome"))
        assertEquals("My app", AppsConfigParser.labelFrom("com.example.my_app"))
    }

    // ------------------------------------------------------------- round trip

    @Test
    fun `format round-trips through parse unchanged`() {
        val original = listOf(
            AppEntry("com.a.b", "B", listOf("all", "pinned")),
            AppEntry("com.c.d", "D", listOf("tools")),
        )

        val again = parse(AppsConfigParser.format(original)).entries

        assertEquals(
            original.map { Triple(it.pkg, it.label, it.groups) },
            again.map { Triple(it.pkg, it.label, it.groups) },
        )
    }

    @Test
    fun `inGroup filters by any group`() {
        val config = parse("com.a.b | B | all,work\ncom.c.d | D | all\n")

        assertEquals(listOf("com.a.b"), config.inGroup("work").map { it.pkg })
        assertEquals(2, config.inGroup("all").size)
        assertEquals(listOf("all", "work"), config.groupNames)
    }
}

/**
 * `apps.conf` is also an editable file: hiding an app or adding one must never
 * touch the comments the user wrote.
 */
class AppsDocumentTest {

    @Test
    fun `hiding an app blanks its line and keeps every line number`() {
        val text = "# mine\ncom.a.b | B | all\ncom.c.d | D | all\n"
        val doc = AppsConfigParser.parse(text)
        val target = doc.entries.first { it.pkg == "com.c.d" }

        val out = AppsDocument(text).without(target)
        val lines = out.split('\n')

        assertEquals("", lines[2])
        assertEquals("# mine", lines[0])
        assertEquals("com.a.b | B | all", lines[1])
        // B still points at the line it always did.
        assertEquals(2, AppsConfigParser.parse(out).entries.single().line)
    }

    @Test
    fun `a new install is appended and marked as ours`() {
        val text = "com.a.b | B | all\n"
        val out = AppsDocument(text)
            .appendIfMissing(AppEntry("com.new.app", "New app", listOf("all")))

        assertTrue(out.contains("com.new.app"))
        assertTrue(out.contains("# added by adosaura"))
        assertTrue(out.startsWith("com.a.b | B | all"))
    }

    @Test
    fun `an appended entry ends on a newline so the next one gets its own line`() {
        val once = AppsDocument("com.a.b | B | all\n")
            .appendIfMissing(AppEntry("com.new.app", "New app", listOf("all")))
        val twice = AppsDocument(once)
            .appendIfMissing(AppEntry("com.other.app", "Other", listOf("all")))
        val byHand = twice + "com.shell.app | Shell | all\n"

        assertTrue(once.endsWith("\n"))
        assertEquals(
            listOf("com.a.b", "com.new.app", "com.other.app", "com.shell.app"),
            AppsConfigParser.parse(byHand).entries.map { it.pkg },
        )
    }

    @Test
    fun `a package the user deliberately deleted is not sneaked back in`() {
        val text = "com.a.b | B | all\n"
        val out = AppsDocument(text).appendIfMissing(AppEntry("com.a.b", "B", listOf("all")))

        assertEquals(text, out)
    }
}
