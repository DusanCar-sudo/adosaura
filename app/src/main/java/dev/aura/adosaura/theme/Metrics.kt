package dev.aura.adosaura.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One place for the measurements, the same way sway keeps them in one theme
 * file: hairline borders, square corners, tight gaps.
 */
object Metrics {
    /** sway: `default_border pixel 4` — here a hairline, since a phone is dense. */
    val border = 1.dp

    /** sway: `gaps inner 0` — panes touch, separated by the border only. */
    val gap = 0.dp
    val outerGap = 0.dp

    val padH = 12.dp
    val padV = 8.dp

    /** Square corners everywhere, exactly like the desktop. */
    val corner = 0.dp

    /**
     * `/home/dusan/.config/aura-os/look` has `panel_alpha=85`: panes are glass
     * over the wallpaper, not opaque boxes.
     */
    const val PANEL_ALPHA = 0.85f

    /** A scrim so text stays readable whatever the wallpaper is doing. */
    const val SCRIM_ALPHA = 0.30f

    val titleSize = 14.sp
    val bodySize = 18.sp
    val smallSize = 14.sp
    val clockSize = 56.sp
    val drawerRowSize = 20.sp
}
