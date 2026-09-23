package dev.aura.adosaura.gestures

/**
 * Back and forward between apps, the way a browser does it between pages.
 *
 * Android has a system-wide Back and no Forward, so Forward is ours: every
 * time the edge Back takes you out of an app and another app comes to the
 * front, the app you left goes on the forward stack. Forward reopens the top
 * of it. Opening any other app yourself clears the stack, exactly like
 * following a new link does.
 *
 * Only Back sent by the edge gesture counts — the 3-button bar's Back never
 * reaches us, so leaving an app that way reads as a fresh navigation.
 *
 * Pure: the service feeds it package names and a clock, so it is tested.
 */
class AppHistory(
    private val limit: Int = 10,
    private val backWindowMs: Long = 1_500,
) {

    /** The app on screen, as far as the last foreground change told us. */
    var current: String? = null
        private set

    private val ahead = ArrayDeque<String>()
    private var leaving: String? = null
    private var backAt = 0L
    private var arriving: String? = null

    /** How many apps Forward can still reopen. */
    val forwardCount: Int get() = ahead.size

    /**
     * The edge just sent Back. If a different app comes to the front within
     * [backWindowMs], the one on screen now is what Forward brings back. Back
     * that stays inside an app (a page, a dialog) simply expires.
     */
    fun back(now: Long) {
        leaving = current
        backAt = now
    }

    /** The app Forward should reopen, taken off the stack. Null when there is none. */
    fun forward(): String? {
        val next = ahead.removeLastOrNull() ?: return null
        arriving = next
        return next
    }

    /** [pkg] is now the app on screen. */
    fun foreground(pkg: String, now: Long) {
        if (pkg == current) return

        val left = leaving
        when {
            left != null && left != pkg && now - backAt <= backWindowMs -> {
                ahead.remove(left)
                ahead.addLast(left)
                while (ahead.size > limit) ahead.removeFirst()
            }
            pkg == arriving -> Unit
            else -> ahead.clear()
        }

        leaving = null
        arriving = null
        current = pkg
    }
}
