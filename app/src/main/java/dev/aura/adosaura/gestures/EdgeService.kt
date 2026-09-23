package dev.aura.adosaura.gestures

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import dev.aura.adosaura.switcher.Recents
import dev.aura.adosaura.switcher.SwitcherActivity

/**
 * Full-swipe control over every app, not only the home screen.
 *
 * Two thin invisible strips sit on the left and right edges, above whatever
 * is on screen. What a stroke on them means is [EdgeGesture]'s call; Forward's
 * memory is [AppHistory]'s, fed from the window changes this service is told
 * about.
 *
 * An accessibility service because it is the one thing a normal app can use
 * to press the system Back and to draw over other apps without the
 * draw-over-apps permission, and it is exempt from the background
 * activity-start block, so the switcher can open over anything. Enabled by
 * scripts/adb-grants.sh; turned off again under Settings > Accessibility.
 */
class EdgeService : AccessibilityService() {

    private val history = AppHistory()
    private val launchable = HashMap<String, Boolean>()
    private val strips = mutableListOf<Strip>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (strips.isEmpty()) {
            strips += Strip(Edge.LEFT)
            strips += Strip(Edge.RIGHT)
            strips.forEach { runCatching { windows().addView(it, it.params()) } }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // The switcher is a detour, not a destination.
        if (event.className?.toString() == SwitcherActivity::class.java.name) return
        // Keyboards, the notification shade and permission dialogs are windows
        // too; only something with a launcher icon is an app you were "in".
        if (!launchable.getOrPut(pkg) { packageManager.getLaunchIntentForPackage(pkg) != null }) return
        history.foreground(pkg, SystemClock.uptimeMillis())
    }

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation: the strips keep their share of the new height.
        strips.forEach { runCatching { windows().updateViewLayout(it, it.params()) } }
    }

    override fun onDestroy() {
        strips.forEach { runCatching { windows().removeView(it) } }
        strips.clear()
        super.onDestroy()
    }

    private fun windows() = getSystemService(WindowManager::class.java)

    private fun perform(action: EdgeAction, strip: View) {
        val done = when (action) {
            EdgeAction.BACK -> {
                history.back(SystemClock.uptimeMillis())
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            EdgeAction.FORWARD -> history.forward()?.let { Recents.open(this, it) } ?: false
            EdgeAction.APPS -> runCatching {
                startActivity(
                    Intent(this, SwitcherActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.isSuccess
            EdgeAction.NONE -> return
        }
        // The strips are invisible: a buzz is the only sign anything happened,
        // and a different one says "nothing to go forward to".
        strip.performHapticFeedback(
            if (done) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.REJECT,
        )
    }

    /** One edge's strip: owns its touch stream from finger-down to finger-up. */
    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private inner class Strip(private val edge: Edge) : View(this@EdgeService) {

        private val density = resources.displayMetrics.density
        private var downX = 0f
        private var downY = 0f
        private var judged = false

        init {
            isHapticFeedbackEnabled = true
        }

        fun params(): WindowManager.LayoutParams {
            val height = runCatching { windows().currentWindowMetrics.bounds.height() }
                .getOrElse { resources.displayMetrics.heightPixels }
            return WindowManager.LayoutParams(
                (EdgeStrip.WIDTH_DP * density).toInt(),
                (height * (EdgeStrip.BOTTOM - EdgeStrip.TOP)).toInt(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or if (edge == Edge.LEFT) Gravity.LEFT else Gravity.RIGHT
                y = (height * EdgeStrip.TOP).toInt()
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    judged = false
                }
                MotionEvent.ACTION_MOVE -> if (!judged) {
                    val action = EdgeGesture.classify(
                        edge,
                        e.rawX - downX,
                        e.rawY - downY,
                        side = EdgeStrip.SIDE_DP * density,
                        down = EdgeStrip.DOWN_DP * density,
                    )
                    if (action != null) {
                        judged = true
                        perform(action, this)
                    }
                }
            }
            return true
        }
    }
}

/**
 * Where the strips are and how far a stroke has to go. 16dp is narrower than
 * the system's own back-gesture zone, so it takes very little from the apps
 * underneath.
 *
 * The strips run from 12% to 60% of the screen height: clear of the status
 * bar pull-down at the top, and clear of the keyboard at the bottom, whose
 * outer keys (q, a, shift, backspace) sit right on the edges.
 */
object EdgeStrip {
    const val WIDTH_DP = 16f
    const val TOP = 0.12f
    const val BOTTOM = 0.60f
    const val SIDE_DP = 28f
    const val DOWN_DP = 64f
}
