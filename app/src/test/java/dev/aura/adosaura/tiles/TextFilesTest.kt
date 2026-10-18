package dev.aura.adosaura.tiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TextFilesTest {

    private val base = File("/sdcard/adosaura")

    // ------------------------------------------------------------- resolve

    @Test fun relativePathsLiveUnderTheConfigDir() {
        assertEquals(File(base, "status.txt"), TextFiles.resolve(base, "status.txt"))
        assertEquals(File("/sdcard/Documents/log.txt"), TextFiles.resolve(base, "/sdcard/Documents/log.txt"))
        assertNull(TextFiles.resolve(base, null))
        assertNull(TextFiles.resolve(base, " "))
    }

    @Test fun aNoteNameGetsMdOnlyWhenItHasNoExtension() {
        assertEquals(File(base, "todo.md"), TextFiles.resolve(base, "todo", extension = "md"))
        assertEquals(File(base, "shop.txt"), TextFiles.resolve(base, "shop.txt", extension = "md"))
        assertEquals(File(base, "my.notes/todo.md"), TextFiles.resolve(base, "my.notes/todo", extension = "md"))
    }

    @Test fun titleIsTheBareFileName() {
        assertEquals("status", TextFiles.title("status.txt", "file"))
        assertEquals("log", TextFiles.title("/sdcard/Documents/log.txt", "file"))
        assertEquals("todo", TextFiles.title("todo", "x"))
        assertEquals("file", TextFiles.title(null, "file"))
    }

    // ---------------------------------------------------------------- read

    @Test fun readWholeSmallFileAndReportMissingOne() {
        val dir = Files.createTempDirectory("tf").toFile()
        val f = File(dir, "a.txt").apply { writeText("one\ntwo\n") }
        assertEquals(TextFiles.Snapshot("one\ntwo\n"), TextFiles.read(f, 1024))
        assertNull(TextFiles.read(File(dir, "missing"), 1024).text)
    }

    @Test fun bigFileKeepsItsTailFromAWholeLine() {
        val f = Files.createTempFile("tf", ".txt").toFile()
        f.writeText((1..100).joinToString("\n") { "line $it" } + "\n")
        val s = TextFiles.read(f, 40)
        assertTrue(s.truncated)
        assertTrue(s.text!!.startsWith("line "))
        assertTrue(s.text!!.endsWith("line 100\n"))
    }

    // ---------------------------------------------------------------- ansi

    @Test fun plainTextIsOneRun() {
        assertEquals(listOf(Run("hello\nworld")), Ansi.parse("hello\nworld"))
    }

    @Test fun coloursBecomeTonesAndResetGoesBack() {
        val runs = Ansi.parse("ci \u001B[32mok\u001B[0m \u001B[1;31mfail\u001B[m.")
        assertEquals(
            listOf(
                Run("ci "),
                Run("ok", tone = Tone.GREEN),
                Run(" "),
                Run("fail", tone = Tone.RED, bold = true),
                Run("."),
            ),
            runs,
        )
    }

    @Test fun trueColourAnd256AndBackgroundsAreRead() {
        assertEquals(listOf(Run("x", rgb = 0xFFD466)), Ansi.parse("\u001B[38;2;255;212;102mx"))
        assertEquals(listOf(Run("y", tone = Tone.CYAN)), Ansi.parse("\u001B[38;5;14my"))
        // the background's own arguments must not be read as a colour
        assertEquals(listOf(Run("z", tone = Tone.RED)), Ansi.parse("\u001B[48;5;2;31mz"))
    }

    @Test fun cursorCodesAndControlCharactersAreDropped() {
        assertEquals(listOf(Run("ab")), Ansi.parse("\u001B[?25l\u001B[2Ka\rb\u0007"))
    }

    @Test fun tabsLineUpToEightColumns() {
        assertEquals(listOf(Run("ab      c\n        d")), Ansi.parse("ab\tc\n\td"))
    }

    // --------------------------------------------------------------- notes

    private val note = "# shop\n- [ ] milk\n- [x] bread\n\nremember the list\n"

    @Test fun parseTasksHeadingsAndText() {
        val lines = Notes.parse(note)
        assertEquals(5, lines.size)
        assertEquals(NoteLine.Heading(0, "shop"), lines[0])
        assertEquals(NoteLine.Task(1, "milk", false, "- [ ] milk"), lines[1])
        assertEquals(NoteLine.Task(2, "bread", true, "- [x] bread"), lines[2])
        assertEquals(NoteLine.Plain(3, ""), lines[3])
        assertEquals(NoteLine.Plain(4, "remember the list"), lines[4])
        assertTrue(Notes.parse("").isEmpty())
    }

    @Test fun toggleRewritesOnlyTheMark() {
        val ticked = Notes.toggle(note, 1, "- [ ] milk")
        assertEquals(note.replace("- [ ] milk", "- [x] milk"), ticked)
        assertEquals(note, Notes.toggle(ticked!!, 1, "- [x] milk"))
    }

    @Test fun toggleKeepsCrlfAndIndent() {
        val text = "  * [X] done\r\nnext\r\n"
        assertEquals("  * [ ] done\r\nnext\r\n", Notes.toggle(text, 0, "  * [X] done\r"))
    }

    @Test fun toggleRefusesWhenTheLineChangedUnderneath() {
        assertNull(Notes.toggle(note, 1, "- [ ] eggs"))
        assertNull(Notes.toggle(note, 99, "- [ ] milk"))
        assertNull(Notes.toggle(note, 4, "remember the list"))
    }

    @Test fun appendAddsOneLineAndFixesAMissingNewline() {
        assertEquals("- [ ] eggs\n", Notes.append("", "eggs"))
        assertEquals("a\n- [ ] eggs\n", Notes.append("a", " eggs "))
        assertEquals("a\n- [ ] two lines\n", Notes.append("a\n", "two\nlines"))
        assertNull(Notes.append("a\n", "  "))
    }

    @Test fun clearDoneDropsOnlyTickedTasks() {
        val cleared = Notes.clearDone(note)
        assertEquals("# shop\n- [ ] milk\n\nremember the list\n", cleared)
        assertFalse(Notes.parse(cleared).any { it is NoteLine.Task && it.done })
    }
}
