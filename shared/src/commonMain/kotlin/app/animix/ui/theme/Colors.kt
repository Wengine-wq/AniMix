package app.animix.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * AniMix palette: graphite surfaces with a pastel pink accent.
 * Brand references: graphite #292929, pastel pink #C88E9B, bright pink #FFB4C8.
 */
@Immutable
data class AniMixColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceGlass: Color,
    val outline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val accentStrong: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val gradientStart: Color,
    val gradientEnd: Color,
    val scrim: Color,
    val star: Color,
    val danger: Color,
) {
    val accentGradient: Brush get() = Brush.horizontalGradient(listOf(gradientStart, gradientEnd))
}

val DarkColors = AniMixColors(
    isDark = true,
    background = Color(0xFF09090B),
    surface = Color(0xFF141417),
    surfaceRaised = Color(0xFF1C1C20),
    surfaceGlass = Color(0xB31C1C20),
    outline = Color(0xFF29292E),
    textPrimary = Color(0xFFF7F3F5),
    textSecondary = Color(0xFFA7A1A6),
    textMuted = Color(0xFF6E696E),
    accent = Color(0xFFFFB4C8),
    accentStrong = Color(0xFFFF9EBB),
    accentSoft = Color(0x26FFB4C8),
    onAccent = Color(0xFF2A1219),
    gradientStart = Color(0xFFFFB4C8),
    gradientEnd = Color(0xFFC88E9B),
    scrim = Color(0xCC09090B),
    star = Color(0xFFFFC978),
    danger = Color(0xFFFF7A85),
)

val LightColors = AniMixColors(
    isDark = false,
    background = Color(0xFFFBF8FA),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFF4EEF1),
    surfaceGlass = Color(0xCCFFFFFF),
    outline = Color(0xFFE9E1E5),
    textPrimary = Color(0xFF292929),
    textSecondary = Color(0xFF6F676C),
    textMuted = Color(0xFFA39BA0),
    accent = Color(0xFFC88E9B),
    accentStrong = Color(0xFFA9677A),
    accentSoft = Color(0x24C88E9B),
    onAccent = Color(0xFFFFFFFF),
    gradientStart = Color(0xFFE7A3B6),
    gradientEnd = Color(0xFFC88E9B),
    scrim = Color(0x99292929),
    star = Color(0xFFE59A2E),
    danger = Color(0xFFD94B5A),
)
