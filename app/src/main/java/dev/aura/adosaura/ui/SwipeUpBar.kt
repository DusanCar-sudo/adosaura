package dev.aura.adosaura.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aura.adosaura.theme.AdosauraMono
import dev.aura.adosaura.theme.Palette
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The bottom gesture bar, positioned right down at the bottom edge.
 *
 * Supports:
 *   flick up or down     -> the app drawer
 *   drag and hold        -> the switcher
 *   tap                  -> the app drawer
 *   long-press           -> the switcher
 */
@Composable
fun SwipeUpBar(
    onDrawer: () -> Unit,
    onSwitcher: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var holdJob by remember { mutableStateOf<Job?>(null) }
    var switcherFired by remember { mutableStateOf(false) }
    var travelled by remember { mutableStateOf(0f) }

    Box(
        modifier
            .fillMaxWidth()
            .height(SwipeBar.HEIGHT)
            // 1. Drag: flick in either direction (up or down) for drawer, hold for switcher
            .pointerInput(Unit) {
                val trigger = SwipeBar.TRIGGER.toPx()
                detectVerticalDragGestures(
                    onDragStart = {
                        switcherFired = false
                        travelled = 0f
                        holdJob = scope.launch {
                            delay(SwipeBar.HOLD_MS)
                            if (travelled > trigger) {
                                switcherFired = true
                                onSwitcher()
                            }
                        }
                    },
                    onVerticalDrag = { change, dragAmount ->
                        travelled += kotlin.math.abs(dragAmount)
                        if (travelled > trigger) change.consume()
                    },
                    onDragEnd = {
                        holdJob?.cancel()
                        holdJob = null
                        if (!switcherFired && travelled > trigger) onDrawer()
                        switcherFired = false
                        travelled = 0f
                    },
                    onDragCancel = {
                        holdJob?.cancel()
                        holdJob = null
                        switcherFired = false
                        travelled = 0f
                    },
                )
            }
            // 2. Tap: tap for drawer, long-press for switcher
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onDrawer() },
                    onLongPress = { onSwitcher() },
                )
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        ) {
            // Home indicator pill down at the bottom
            Box(
                Modifier
                    .width(44.dp)
                    .height(4.dp)
                    .background(
                        color = Palette.current.accent.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(2.dp),
                    )
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "drawer  ·  hold: switcher",
                color = Palette.current.dim,
                fontSize = 11.sp,
                fontFamily = AdosauraMono,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private object SwipeBar {
    val HEIGHT = 38.dp
    val TRIGGER = 24.dp
    const val HOLD_MS = 250L
}
