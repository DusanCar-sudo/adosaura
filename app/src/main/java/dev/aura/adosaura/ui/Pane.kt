package dev.aura.adosaura.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.aura.adosaura.theme.AdosauraMono
import dev.aura.adosaura.theme.Metrics
import dev.aura.adosaura.theme.Palette

/** Which way an edge runs: a side edge resizes width, a top/bottom edge height. */
enum class Axis { HORIZONTAL, VERTICAL }

/**
 * The chrome every tile wears: a hairline border, a glass background over the
 * wallpaper, and a `─ title ─` strip at the top.
 *
 * ## Active Tile & Resizing
 *
 * Active tiles display a glowing theme accent border (1px line + accent glow).
 * Resizing via dragging requires a long-press on the edge handle to avoid
 * accidental resizes while scrolling. Height resizing is controlled by the bottom
 * edge, eliminating conflicting edge handles between adjacent rows.
 */
@Composable
fun Pane(
    title: String,
    modifier: Modifier = Modifier,
    onResizeWidth: ((Int) -> Unit)? = null,
    onResizeHeight: ((Int) -> Unit)? = null,
    onResizeEnd: (() -> Unit)? = null,
    isActive: Boolean = false,
    onSelect: (() -> Unit)? = null,
    onTitleLongPress: (() -> Unit)? = null,
    /** Outline and glow colour; null = the tile's accent. */
    edge: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = Palette.tile
    val frame = Palette.frame
    val line = edge ?: p.accent
    val glowing = frame != "line"
    // Glow needs room: each pane steps in, so two neighbours' glows meet in the gap.
    val inset = if (glowing) GLOW_INSET else 0.dp
    val strength = glowStrength(frame, isActive)

    Box(
        modifier = modifier.drawBehind {
            if (!glowing) return@drawBehind
            val i = inset.toPx()
            val rings = 7
            for (k in 1..rings) {
                val grow = i * k / rings
                val fade = 1f - k / (rings + 1f)
                drawRect(
                    color = line.copy(alpha = strength * 0.22f * fade * fade),
                    topLeft = Offset(i - grow, i - grow),
                    size = Size(size.width - 2 * (i - grow), size.height - 2 * (i - grow)),
                    style = Stroke(width = i / rings + 0.5f),
                )
            }
        },
    ) {
        val borderMod = when {
            glowing -> Modifier
                .drawBehind {
                    // A faint inner glow, then the crisp outer line on top.
                    for (k in 1..3) {
                        val d = k * 2.dp.toPx()
                        drawRect(
                            color = line.copy(alpha = strength * 0.10f / k),
                            topLeft = Offset(d / 2, d / 2),
                            size = Size(size.width - d, size.height - d),
                            style = Stroke(width = d),
                        )
                    }
                }
                .border(if (isActive) 1.5.dp else 1.dp, line.copy(alpha = 0.55f + 0.45f * strength))
            isActive -> Modifier
                .drawBehind {
                    drawRect(color = line.copy(alpha = 0.35f), style = Stroke(width = 3.dp.toPx()))
                }
                .border(1.dp, line)
            else -> Modifier.border(Metrics.border, p.border)
        }
        val pane = p.bgAlt.copy(alpha = p.panelAlpha)
        val fill = if (glowing) {
            // A slight sheen from the top edge, so the pane reads as lit glass.
            Modifier.background(Brush.verticalGradient(listOf(lerp(pane, line.copy(alpha = pane.alpha), 0.10f), pane)))
        } else Modifier.background(pane)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inset)
                .then(borderMod)
                .then(fill)
                .pointerInput(onTitleLongPress, onSelect) {
                    detectTapGestures(
                        onTap = { onSelect?.invoke() },
                        onLongPress = {
                            onSelect?.invoke()
                            onTitleLongPress?.invoke()
                        },
                    )
                },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp)
                    .padding(horizontal = Metrics.padH),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "─ $title ─",
                    color = when {
                        isActive -> line
                        glowing -> line.copy(alpha = 0.8f)
                        else -> p.dim
                    },
                    fontSize = Metrics.titleSize,
                    fontFamily = AdosauraMono,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                )
            }

            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(Metrics.padH), content = content)
            }
        }

        // Horizontal edges: drag after long press to resize width
        if (onResizeWidth != null) {
            // Right edge (pulling right expands width)
            EdgeHandle(
                axis = Axis.HORIZONTAL,
                sign = 1f,
                onStep = onResizeWidth,
                onEnd = onResizeEnd,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(EDGE),
            )
            // Left edge (pulling left expands width)
            EdgeHandle(
                axis = Axis.HORIZONTAL,
                sign = -1f,
                onStep = onResizeWidth,
                onEnd = onResizeEnd,
                modifier = Modifier.align(Alignment.CenterStart).fillMaxHeight().width(EDGE),
            )
        }

        // Vertical edge: bottom edge only, so dragging up/down controls this row's
        // height unambiguously without conflicting with the row below it.
        if (onResizeHeight != null) {
            EdgeHandle(
                axis = Axis.VERTICAL,
                sign = 1f,
                onStep = onResizeHeight,
                onEnd = onResizeEnd,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(EDGE),
            )
        }
    }
}

private val EDGE = 16.dp

/** How far a glowing pane steps in from its cell; the glow fills that margin. */
private val GLOW_INSET = 5.dp

/** 0..1: how bright the glow is. `neon` breathes, unless the phone is in eco mode. */
@Composable
private fun glowStrength(frame: String, isActive: Boolean): Float {
    val base = if (isActive) 1f else 0.55f
    if (frame != "neon" || Palette.eco) return base
    val breath by rememberInfiniteTransition(label = "neon").animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "neon",
    )
    return if (isActive) 1f else base * breath + 0.25f
}

/**
 * One draggable edge handle. Requires a long-press before drag begins in order
 * to prevent accidental resizes while scrolling. Shows a glowing accent line
 * while actively being dragged.
 */
@Composable
private fun EdgeHandle(
    axis: Axis,
    sign: Float,
    onStep: (Int) -> Unit,
    onEnd: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val stepPx = with(LocalDensity.current) { 50.dp.toPx() }
    var accumulated by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    val handleHighlight = if (isDragging) {
        Modifier.background(Palette.tile.accent.copy(alpha = 0.40f))
    } else Modifier

    Box(
        modifier = modifier
            .then(handleHighlight)
            .pointerInput(axis, sign, stepPx) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        isDragging = true
                        accumulated = 0f
                    },
                    onDragEnd = {
                        isDragging = false
                        accumulated = 0f
                        onEnd?.invoke()
                    },
                    onDragCancel = {
                        isDragging = false
                        accumulated = 0f
                        onEnd?.invoke()
                    },
                ) { change, drag ->
                    change.consume()
                    val along = if (axis == Axis.VERTICAL) drag.y else drag.x
                    accumulated += along * sign
                    while (accumulated >= stepPx) {
                        accumulated -= stepPx
                        onStep(1)
                    }
                    while (accumulated <= -stepPx) {
                        accumulated += stepPx
                        onStep(-1)
                    }
                }
            },
    )
}
