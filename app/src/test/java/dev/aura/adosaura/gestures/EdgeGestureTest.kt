package dev.aura.adosaura.gestures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The edge strips are invisible, so the only feedback is whether the right
 * thing happened. These pin down what each stroke means.
 */
class EdgeGestureTest {

    private val side = 30f
    private val down = 60f

    private fun right(dx: Float, dy: Float) = EdgeGesture.classify(Edge.RIGHT, dx, dy, side, down)
    private fun left(dx: Float, dy: Float) = EdgeGesture.classify(Edge.LEFT, dx, dy, side, down)

    @Test
    fun `in from the right edge is back`() {
        assertEquals(EdgeAction.BACK, right(-40f, 5f))
    }

    @Test
    fun `in from the left edge is forward`() {
        assertEquals(EdgeAction.FORWARD, left(40f, -5f))
    }

    @Test
    fun `down along either edge is the apps list`() {
        assertEquals(EdgeAction.APPS, right(-3f, 70f))
        assertEquals(EdgeAction.APPS, left(3f, 70f))
    }

    @Test
    fun `a stroke too short to mean anything is not judged yet`() {
        assertNull(right(-20f, 4f))
        assertNull(left(10f, 10f))
    }

    @Test
    fun `a downward stroke needs to go further than a sideways one`() {
        assertNull(right(0f, 45f))
        assertEquals(EdgeAction.APPS, right(0f, 60f))
    }

    @Test
    fun `the dominant axis wins on a diagonal`() {
        assertEquals(EdgeAction.BACK, right(-50f, 40f))
        assertNull(right(-40f, 50f))
        assertEquals(EdgeAction.APPS, right(-40f, 65f))
    }

    @Test
    fun `swiping up along an edge is nothing`() {
        assertEquals(EdgeAction.NONE, right(0f, -70f))
    }

    @Test
    fun `moving outward is nothing`() {
        assertEquals(EdgeAction.NONE, right(40f, 0f))
        assertEquals(EdgeAction.NONE, left(-40f, 0f))
    }
}
