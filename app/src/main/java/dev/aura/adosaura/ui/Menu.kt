package dev.aura.adosaura.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.aura.adosaura.theme.AdosauraMono
import dev.aura.adosaura.theme.Metrics
import dev.aura.adosaura.theme.Palette

/** One row of a [TextMenu]. */
data class MenuItem(
    val label: String,
    val action: () -> Unit,
)

/**
 * A flat, text-only menu: a bordered panel of monospace rows, no icons, no
 * elevation, no Material chrome — the same shape as the omarchy menu the
 * launcher is modelled on.
 */
@Composable
fun TextMenu(
    title: String,
    items: List<MenuItem>,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = modifier
            .width(300.dp)
            .border(Metrics.border, Palette.current.border)
            .background(Palette.current.bg.copy(alpha = 0.97f))
            .pointerInput(onDismiss) {
                detectTapGestures(onTap = { onDismiss() })
            }
            .padding(vertical = Metrics.padV),
    ) {
        Text(
            text = "─ $title ─",
            color = Palette.current.dim,
            fontSize = Metrics.titleSize,
            fontWeight = FontWeight.Bold,
            fontFamily = AdosauraMono,
            modifier = Modifier.padding(horizontal = Metrics.padH, vertical = 2.dp),
        )

        items.forEach { item ->
            Text(
                text = item.label,
                color = Palette.current.fg,
                fontSize = Metrics.bodySize,
                fontFamily = AdosauraMono,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { item.action() }
                    .padding(horizontal = Metrics.padH, vertical = 6.dp),
            )
        }
    }
}
