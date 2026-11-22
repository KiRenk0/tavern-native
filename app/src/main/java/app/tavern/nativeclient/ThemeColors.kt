package app.tavern.nativeclient

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

internal fun ThemePalette.color(role: ThemeColor): Color? = colors[role]?.let { Color(it) }

internal fun ColorScheme.withThemePalette(palette: ThemePalette): ColorScheme {
    val panel = palette.color(ThemeColor.PANEL)?.compositeOver(surface)
    val text = palette.color(ThemeColor.TEXT) ?: panel?.let { if (it.luminance() < 0.30f) Color.White else Color.Black }
    val border = palette.color(ThemeColor.BORDER)
    return copy(
        surface = panel ?: surface,
        background = panel ?: background,
        onBackground = text ?: onBackground,
        onSurface = text ?: onSurface,
        onSurfaceVariant = text ?: onSurfaceVariant,
        surfaceVariant = panel?.let { lerp(it, text ?: onSurface, 0.08f) } ?: surfaceVariant,
        surfaceContainerLowest = panel ?: surfaceContainerLowest,
        surfaceContainerLow = panel?.let { lerp(it, text ?: onSurface, 0.03f) } ?: surfaceContainerLow,
        surfaceContainer = panel?.let { lerp(it, text ?: onSurface, 0.06f) } ?: surfaceContainer,
        surfaceContainerHigh = panel?.let { lerp(it, text ?: onSurface, 0.09f) } ?: surfaceContainerHigh,
        surfaceContainerHighest = panel?.let { lerp(it, text ?: onSurface, 0.12f) } ?: surfaceContainerHighest,
        outline = border ?: outline,
        outlineVariant = border ?: outlineVariant,
    )
}
