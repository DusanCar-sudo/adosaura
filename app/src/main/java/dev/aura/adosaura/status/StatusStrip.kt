package dev.aura.adosaura.status

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.aura.adosaura.theme.AdosauraMono
import dev.aura.adosaura.theme.Metrics
import dev.aura.adosaura.theme.Palette

/**
 * The one-line strip at the top of every page: page dots, the time, settings cog,
 * the battery, and text keys. Drawn by the launcher, because the system status
 * bar is hidden on the home screen.
 */
@Composable
fun StatusStrip(
    page: Int,
    pageCount: Int,
    battery: Int,
    time: String,
    onAddTile: () -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onCleanAll: () -> Unit,
    onOptimize: () -> Unit = {},
    cleaning: Boolean = false,
    openAppCount: Int? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Palette.current.bg.copy(alpha = 0.55f))
            .padding(horizontal = Metrics.padH, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Page dots, the same ● ○ language herdr uses for agent state.
        Text(
            text = pageDots(page, pageCount),
            color = Palette.current.accent,
            fontSize = Metrics.smallSize,
            fontFamily = AdosauraMono,
        )

        // Time and Settings cog right next to each other
        Row(
            modifier = Modifier.weight(1f).padding(start = Metrics.padH),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = time,
                color = Palette.current.fg,
                fontSize = Metrics.smallSize,
                fontWeight = FontWeight.Bold,
                fontFamily = AdosauraMono,
            )

            Spacer(Modifier.width(6.dp))

            SettingsCogIcon(
                onClick = onOpenSettings,
                color = Palette.current.accent,
                modifier = Modifier.size(15.dp),
            )
        }

        Text(
            text = if (battery >= 0) "$battery%" else "--",
            color = Palette.current.muted,
            fontSize = Metrics.smallSize,
            fontFamily = AdosauraMono,
        )

        if (openAppCount != null) {
            Text(
                text = "[$openAppCount on]",
                color = Palette.current.accent,
                fontSize = Metrics.smallSize,
                fontFamily = AdosauraMono,
                modifier = Modifier.padding(start = Metrics.padH),
            )
        }

        Text(
            text = when {
                cleaning -> "cleaning"
                Palette.eco -> "clean·eco"
                else -> "clean"
            },
            color = if (cleaning) Palette.current.dim else Palette.current.yellow,
            fontSize = Metrics.smallSize,
            fontWeight = FontWeight.Bold,
            fontFamily = AdosauraMono,
            modifier = Modifier
                .pointerInput(onCleanAll, onOptimize) {
                    detectTapGestures(onTap = { onCleanAll() }, onLongPress = { onOptimize() })
                }
                .padding(start = Metrics.padH),
        )

        Text(
            text = "add",
            color = Palette.current.dim,
            fontSize = Metrics.smallSize,
            fontWeight = FontWeight.Bold,
            fontFamily = AdosauraMono,
            modifier = Modifier
                .clickable { onAddTile() }
                .padding(start = Metrics.padH),
        )

        Text(
            text = "apps",
            color = Palette.current.dim,
            fontSize = Metrics.smallSize,
            fontWeight = FontWeight.Bold,
            fontFamily = AdosauraMono,
            modifier = Modifier
                .clickable { onOpenDrawer() }
                .padding(start = Metrics.padH),
        )
    }
}

/**
 * An orange/accent 8-tooth settings cog icon matching Aura OS system icons.
 */
@Composable
fun SettingsCogIcon(
    onClick: () -> Unit,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(1.dp)
    ) {
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        val innerRadius = radius * 0.62f
        val holeRadius = radius * 0.28f

        // Draw 8 gear teeth
        for (i in 0 until 8) {
            val angle = (i * 45.0 * Math.PI / 180.0).toFloat()
            val cos = kotlin.math.cos(angle)
            val sin = kotlin.math.sin(angle)
            drawLine(
                color = color,
                start = Offset(center.x + cos * innerRadius, center.y + sin * innerRadius),
                end = Offset(center.x + cos * radius, center.y + sin * radius),
                strokeWidth = radius * 0.42f,
                cap = StrokeCap.Square,
            )
        }

        // Draw gear ring body
        drawCircle(
            color = color,
            radius = innerRadius,
            center = center,
        )

        // Center hole
        drawCircle(
            color = Palette.current.bg,
            radius = holeRadius,
            center = center,
        )
    }
}

/**
 * A warning banner for whatever the launcher could not do — a missing
 * permission, a line it had to skip. Tapping it reloads the configs, which is
 * also the escape hatch when FileObserver has been dropped after doze.
 */
@Composable
fun NoticeBar(notices: List<String>, onReload: () -> Unit, modifier: Modifier = Modifier) {
    if (notices.isEmpty()) return

    Text(
        text = notices.joinToString("  ·  "),
        color = Palette.current.yellow,
        fontSize = Metrics.titleSize,
        fontFamily = AdosauraMono,
        maxLines = 2,
        modifier = modifier
            .fillMaxWidth()
            .background(Palette.current.bg.copy(alpha = 0.85f))
            .clickable { onReload() }
            .padding(horizontal = Metrics.padH, vertical = 4.dp),
    )
}

private fun pageDots(page: Int, pageCount: Int): String = buildString {
    for (index in 0 until pageCount.coerceAtLeast(1)) {
        append(if (index == page) "●" else "○")
    }
}
