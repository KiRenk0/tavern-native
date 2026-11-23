package app.tavern.nativeclient

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min

internal val LocalReducedMotion = compositionLocalOf { false }
internal val LocalHighContrast = compositionLocalOf { false }

internal fun ThemePalette.color(role: ThemeColor): Color? = colors[role]?.let { Color(it) }

internal fun contrastRatio(foreground: Color, background: Color): Double {
    val l1 = foreground.luminance().toDouble()
    val l2 = background.luminance().toDouble()
    return (max(l1, l2) + 0.05) / (min(l1, l2) + 0.05)
}

internal fun ColorScheme.withHighContrast(dark: Boolean): ColorScheme = if (dark) {
    copy(
        primary = Color(0xFF7BFFBE),
        onPrimary = Color.Black,
        primaryContainer = Color(0xFF005734),
        onPrimaryContainer = Color.White,
        secondary = Color(0xFFD6F5E3),
        onSecondary = Color.Black,
        secondaryContainer = Color(0xFF1E3B2E),
        onSecondaryContainer = Color.White,
        tertiary = Color(0xFFFFE08A),
        onTertiary = Color.Black,
        tertiaryContainer = Color(0xFF543A00),
        onTertiaryContainer = Color.White,
        background = Color.Black,
        onBackground = Color.White,
        surface = Color.Black,
        onSurface = Color.White,
        surfaceVariant = Color(0xFF1F2420),
        onSurfaceVariant = Color(0xFFF0F5F0),
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF0E120F),
        surfaceContainer = Color(0xFF161B17),
        surfaceContainerHigh = Color(0xFF1E2520),
        surfaceContainerHighest = Color(0xFF28302A),
        outline = Color(0xFFE0E6E1),
        outlineVariant = Color(0xFFB4BEB6),
        error = Color(0xFFFF8A80),
        onError = Color.Black,
    )
} else {
    copy(
        primary = Color(0xFF004728),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFB5F2D1),
        onPrimaryContainer = Color.Black,
        secondary = Color(0xFF183629),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFD2EADC),
        onSecondaryContainer = Color.Black,
        tertiary = Color(0xFF4D3200),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFFDF9E),
        onTertiaryContainer = Color.Black,
        background = Color.White,
        onBackground = Color.Black,
        surface = Color.White,
        onSurface = Color.Black,
        surfaceVariant = Color(0xFFE6ECE7),
        onSurfaceVariant = Color(0xFF141815),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF6F8F6),
        surfaceContainer = Color(0xFFEDF1EE),
        surfaceContainerHigh = Color(0xFFE3E9E4),
        surfaceContainerHighest = Color(0xFFD7E0D9),
        outline = Color(0xFF1C211D),
        outlineVariant = Color(0xFF424A44),
        error = Color(0xFF8C0009),
        onError = Color.White,
    )
}

internal fun ColorScheme.withThemePalette(palette: ThemePalette, highContrast: Boolean = false, dark: Boolean = surface.luminance() < 0.30f): ColorScheme {
    if (highContrast) return withHighContrast(dark)
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
