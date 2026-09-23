package dev.aura.adosaura.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.aura.adosaura.R

/**
 * One family for the whole launcher: JetBrains Mono, bundled in `res/font`
 * with its OFL licence in `assets/licenses/JetBrainsMono-OFL.txt`. No icon
 * font, no glyph font — if we ever want the Nerd Font glyphs, the swap is
 * documented in docs/DESIGN.md.
 *
 * If the resources ever fail to resolve we fall back to the platform
 * monospace: a typeface is never worth a crash on the home screen.
 */
val AdosauraMono: FontFamily = runCatching {
    FontFamily(
        Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
        Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
    )
}.getOrElse { FontFamily.Monospace }
