package dev.aura.adosaura.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drawer's search is the one place a bad matcher is obvious: it either
 * finds Chrome when you type `chr`, or it does not.
 */
class FuzzyTest {

    @Test
    fun `a subsequence matches`() {
        val score = Fuzzy.score("chr", "Chrome")

        assertTrue("expected a score, got $score", score != null)
        assertTrue("expected a positive score, got $score", score!! > 0)
    }

    @Test
    fun `something that is not a subsequence scores nothing`() {
        assertNull(Fuzzy.score("zzz", "Chrome"))
    }

    @Test
    fun `an empty query matches everything with no preference`() {
        assertEquals(0, Fuzzy.score("", "Chrome"))
    }

    @Test
    fun `matching is case insensitive`() {
        assertTrue(Fuzzy.score("CHR", "chrome") != null)
        assertTrue(Fuzzy.score("chr", "CHROME") != null)
    }

    @Test
    fun `a match at the start of a word beats one in the middle`() {
        val wordStart = Fuzzy.score("chr", "Chrome")!!
        val midWord = Fuzzy.score("chr", "xchromex")!!

        assertTrue("word start $wordStart should beat mid-word $midWord", wordStart > midWord)
    }

    @Test
    fun `consecutive letters beat scattered ones`() {
        val consecutive = Fuzzy.score("chr", "chrome")!!
        val scattered = Fuzzy.score("chr", "c_h_r_o_m_e")!!

        assertTrue(
            "consecutive $consecutive should beat scattered $scattered",
            consecutive > scattered,
        )
    }

    @Test
    fun `a hit in the label outranks a hit in the package name`() {
        val entries = listOf(
            AppEntry("com.chrono.app", "Time", listOf("all")),
            AppEntry("com.other.app", "Chrome", listOf("all")),
        )

        val ranked = Fuzzy.rank("chr", entries)

        assertEquals(listOf("Chrome", "Time"), ranked.map { it.label })
    }

    @Test
    fun `a blank query keeps the file's own order`() {
        val entries = listOf(
            AppEntry("com.b", "B", listOf("all")),
            AppEntry("com.a", "A", listOf("all")),
        )

        assertEquals(listOf("B", "A"), Fuzzy.rank("  ", entries).map { it.label })
    }

    @Test
    fun `ties break alphabetically so the list never jitters`() {
        val entries = listOf(
            AppEntry("com.b", "Beta", listOf("all")),
            AppEntry("com.a", "Alpha", listOf("all")),
        )

        val first = Fuzzy.rank("a", entries).map { it.label }
        val second = Fuzzy.rank("a", entries.reversed()).map { it.label }

        assertEquals(first, second)
    }

    @Test
    fun `an unmatched query returns an empty list rather than everything`() {
        val entries = listOf(AppEntry("com.a", "Alpha", listOf("all")))

        assertTrue(Fuzzy.rank("qqqq", entries).isEmpty())
    }
}
