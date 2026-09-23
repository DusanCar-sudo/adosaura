package dev.aura.adosaura.gestures

import kotlin.math.abs

/** The two screen edges that carry a gesture strip. */
enum class Edge { LEFT, RIGHT }

/** What an edge stroke asks for. [NONE] is a stroke that decided to be nothing. */
enum class EdgeAction { BACK, FORWARD, APPS, NONE }

/**
 * Full-swipe control, as a pure function so it is tested rather than tried:
 *
 *   in from the right edge   -> Back
 *   in from the left edge    -> Forward (the app you just backed out of)
 *   down along either edge   -> the apps that are on
 *
 * A stroke is judged once, the moment it has gone far enough to mean
 * something, so the action fires under the finger instead of on release.
 * The dominant axis wins. A downward stroke has to travel further than a
 * sideways one: scrolling a list near the edge is also a downward drag, and
 * opening the switcher by accident is worse than having to swipe a bit more.
 */
object EdgeGesture {

    /**
     * [dx]/[dy] is how far the finger has moved since it touched [edge], in
     * pixels. [side] and [down] are the distances that commit a sideways and a
     * downward stroke. Null while the stroke has not gone far enough to judge.
     */
    fun classify(edge: Edge, dx: Float, dy: Float, side: Float, down: Float): EdgeAction? {
        val ax = abs(dx)
        val ay = abs(dy)

        if (ax >= ay) {
            if (ax < side) return null
            val inward = if (edge == Edge.LEFT) dx > 0 else dx < 0
            return when {
                !inward -> EdgeAction.NONE
                edge == Edge.RIGHT -> EdgeAction.BACK
                else -> EdgeAction.FORWARD
            }
        }

        if (ay < down) return null
        return if (dy > 0) EdgeAction.APPS else EdgeAction.NONE
    }
}
