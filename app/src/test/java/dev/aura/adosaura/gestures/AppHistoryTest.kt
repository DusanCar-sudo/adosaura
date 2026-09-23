package dev.aura.adosaura.gestures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Forward is only worth having if it reopens exactly the app Back took you
 * out of, and nothing else.
 */
class AppHistoryTest {

    private val home = "dev.aura.adosaura"
    private val chrome = "com.android.chrome"
    private val viber = "com.viber.voip"
    private val maps = "com.google.android.apps.maps"

    @Test
    fun `back out of an app and forward brings it back`() {
        val h = AppHistory()
        h.foreground(home, 0)
        h.foreground(chrome, 100)

        h.back(1_000)
        h.foreground(home, 1_200)

        assertEquals(chrome, h.forward())
    }

    @Test
    fun `nothing to go forward to until something was left with back`() {
        val h = AppHistory()
        h.foreground(home, 0)
        h.foreground(chrome, 100)

        assertNull(h.forward())
    }

    @Test
    fun `back that stays inside the app does not count`() {
        val h = AppHistory()
        h.foreground(chrome, 0)

        h.back(1_000)
        // Chrome went back a page; five seconds later you press Home.
        h.foreground(home, 6_000)

        assertNull(h.forward())
    }

    @Test
    fun `backing out twice goes forward in the opposite order`() {
        val h = AppHistory()
        h.foreground(viber, 0)
        h.foreground(chrome, 100)

        h.back(1_000)
        h.foreground(viber, 1_100)
        h.back(2_000)
        h.foreground(home, 2_100)

        assertEquals(viber, h.forward())
        h.foreground(viber, 2_500)
        assertEquals(chrome, h.forward())
        h.foreground(chrome, 2_900)
        assertNull(h.forward())
    }

    @Test
    fun `opening another app yourself clears forward`() {
        val h = AppHistory()
        h.foreground(chrome, 0)
        h.back(1_000)
        h.foreground(home, 1_100)

        h.foreground(maps, 5_000)

        assertEquals(0, h.forwardCount)
        assertNull(h.forward())
    }

    @Test
    fun `arriving through forward does not clear what is still ahead`() {
        val h = AppHistory()
        h.foreground(viber, 0)
        h.foreground(chrome, 100)
        h.back(1_000)
        h.foreground(viber, 1_100)
        h.back(2_000)
        h.foreground(home, 2_100)

        h.forward()
        h.foreground(viber, 2_500)

        assertEquals(1, h.forwardCount)
    }

    @Test
    fun `an app appears once on the stack, at the top`() {
        val h = AppHistory()
        h.foreground(chrome, 0)
        h.back(1_000)
        h.foreground(home, 1_100)
        assertEquals(chrome, h.forward())
        h.foreground(chrome, 1_500)
        h.back(2_000)
        h.foreground(home, 2_100)

        assertEquals(1, h.forwardCount)
        assertEquals(chrome, h.forward())
    }

    @Test
    fun `the stack is capped`() {
        val h = AppHistory(limit = 2)
        val apps = listOf("a", "b", "c", "d")
        h.foreground(home, 0)
        var t = 100L
        // Open a, b, c, d in turn, then back out of each: d, c, b, a pile up.
        apps.forEach { h.foreground(it, t); t += 100 }
        apps.reversed().drop(1).forEach { next ->
            h.back(t); h.foreground(next, t + 50); t += 100
        }

        assertEquals(2, h.forwardCount)
        assertEquals("b", h.forward())
    }
}
